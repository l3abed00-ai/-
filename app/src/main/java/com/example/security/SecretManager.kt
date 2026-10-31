package com.example.security

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object SecretManager {

    private const val PREFS_NAME = "jadhadha_secure_prefs"
    private const val KEY_ENCRYPTED_API_KEY = "encrypted_gemini_api_key"
    private const val KEY_GCM_IV = "gemini_api_key_iv"
    private const val KEY_ALIAS = "JadhadhaGeminiMasterKey"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val AES_GCM_NOPADDING = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128

    @Volatile
    private var inMemoryCachedKey: String? = null

    /**
     * Masks an API key for safe display in UI or logs, preserving only the prefix and suffix.
     * e.g. "AIzaSyD1234567890abcdef" -> "AIza...cdef"
     */
    fun maskApiKey(key: String?): String {
        val trimmed = key?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        if (trimmed.length <= 8) return "••••••••"
        val prefix = trimmed.take(4)
        val suffix = trimmed.takeLast(4)
        return "$prefix••••$suffix"
    }

    /**
     * Sanitizes any string (logs, error messages, stack traces) to remove known API keys
     * and potential Google API key patterns (e.g., AIza...).
     */
    fun sanitizeForLog(message: String?): String {
        val nonNullMsg = message ?: return ""
        if (nonNullMsg.isEmpty()) return ""
        var sanitized: String = nonNullMsg

        // Redact any current cached key
        inMemoryCachedKey?.takeIf { it.isNotBlank() }?.let { activeKey ->
            sanitized = sanitized.replace(activeKey, "[REDACTED_API_KEY]")
        }

        // Redact standard Google API key patterns
        val apiKeyRegex = Regex("""AIza[0-9A-Za-z\-_]{35}""")
        sanitized = sanitized.replace(apiKeyRegex, "[REDACTED_API_KEY]")

        return sanitized
    }

    /**
     * Securely saves the Gemini API key using AES-GCM encryption.
     */
    @Synchronized
    fun saveApiKey(context: Context, apiKey: String) {
        val trimmed = apiKey.trim()
        inMemoryCachedKey = trimmed
        if (trimmed.isEmpty()) {
            clearApiKey(context)
            return
        }

        try {
            val secretKey = getOrCreateSecretKey(context)
            val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(trimmed.toByteArray(StandardCharsets.UTF_8))

            val encryptedB64 = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)

            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ENCRYPTED_API_KEY, encryptedB64)
                .putString(KEY_GCM_IV, ivB64)
                .apply()
        } catch (e: Exception) {
            // Fallback for environments where KeyStore/GCM may fail
            fallbackObfuscatedSave(context, trimmed)
        }
    }

    /**
     * Securely retrieves the Gemini API key.
     */
    @Synchronized
    fun getApiKey(context: Context): String {
        inMemoryCachedKey?.let { return it }

        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encryptedB64 = prefs.getString(KEY_ENCRYPTED_API_KEY, null)
        val ivB64 = prefs.getString(KEY_GCM_IV, null)

        if (encryptedB64.isNullOrEmpty() || ivB64.isNullOrEmpty()) {
            val fallback = fallbackObfuscatedGet(context)
            inMemoryCachedKey = fallback
            return fallback
        }

        return try {
            val encryptedBytes = Base64.decode(encryptedB64, Base64.NO_WRAP)
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val secretKey = getOrCreateSecretKey(context)

            val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            val decryptedBytes = cipher.doFinal(encryptedBytes)
            val decrypted = String(decryptedBytes, StandardCharsets.UTF_8)
            inMemoryCachedKey = decrypted
            decrypted
        } catch (e: Exception) {
            val fallback = fallbackObfuscatedGet(context)
            inMemoryCachedKey = fallback
            fallback
        }
    }

    /**
     * Clears the saved API key.
     */
    @Synchronized
    fun clearApiKey(context: Context) {
        inMemoryCachedKey = ""
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ENCRYPTED_API_KEY)
            .remove(KEY_GCM_IV)
            .remove("obfuscated_api_key")
            .apply()
    }

    private fun getOrCreateSecretKey(context: Context): SecretKey {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            if (keyStore.containsAlias(KEY_ALIAS)) {
                (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
                    ?: generateSoftwareKey(context)
            } else {
                try {
                    val keyGen = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE)
                    val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                            android.security.keystore.KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                    keyGen.init(spec)
                    keyGen.generateKey()
                } catch (e: Exception) {
                    generateSoftwareKey(context)
                }
            }
        } catch (e: Exception) {
            generateSoftwareKey(context)
        }
    }

    private fun generateSoftwareKey(context: Context): SecretKey {
        val digest = MessageDigest.getInstance("SHA-256")
        val seed = "${context.packageName}_jadhadha_local_device_key_salt_2026".toByteArray(StandardCharsets.UTF_8)
        val keyBytes = digest.digest(seed)
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun fallbackObfuscatedSave(context: Context, key: String) {
        val encoded = Base64.encodeToString(key.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("obfuscated_api_key", encoded)
            .apply()
    }

    private fun fallbackObfuscatedGet(context: Context): String {
        val encoded = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("obfuscated_api_key", null) ?: return ""
        return runCatching {
            String(Base64.decode(encoded, Base64.NO_WRAP), StandardCharsets.UTF_8)
        }.getOrDefault("")
    }
}
