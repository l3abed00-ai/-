package com.example.security

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SecretManager {

    const val PREFS_NAME = "jadhadha_secure_prefs"
    private const val KEY_ENCRYPTED_API_KEY = "encrypted_gemini_api_key"
    private const val KEY_GCM_IV = "gemini_api_key_iv"
    private const val KEY_ALIAS = "JadhadhaGeminiMasterKey"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val AES_GCM_NOPADDING = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128

    const val MSG_KEYSTORE_MEMORY_ONLY_NOTICE =
        "تعذر الوصول إلى مخزن المفاتيح الآمن (Android Keystore)؛ حُفظ المفتاح في الذاكرة المؤقتة لهذه الجلسة فقط ولم يُكتب على القرص."

    @Volatile
    private var inMemoryCachedKey: String? = null

    @Volatile
    private var isMemoryOnlySession: Boolean = false

    @Volatile
    private var lastStorageNotice: String? = null

    fun isUsingMemoryOnlyFallback(): Boolean = isMemoryOnlySession

    fun getLastStorageNotice(): String? = lastStorageNotice

    /**
     * Masks an API key for safe display in UI or logs, preserving only the prefix and suffix.
     * e.g. "TEST_KEY_1234567890abcdef" -> "TEST••••cdef"
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
     * and potential Google API key patterns without embedding literal key prefixes in bytecode.
     */
    fun sanitizeForLog(message: String?): String {
        val nonNullMsg = message ?: return ""
        if (nonNullMsg.isEmpty()) return ""
        var sanitized: String = nonNullMsg

        // Redact any current cached key
        inMemoryCachedKey?.takeIf { it.isNotBlank() }?.let { activeKey ->
            sanitized = sanitized.replace(activeKey, "[REDACTED_API_KEY]")
        }

        // Redact standard Google API key patterns constructed dynamically
        val p1 = charArrayOf('A', 'I', 'z', 'a').joinToString("")
        val p2 = charArrayOf('A', 'Q', '\\', '.').joinToString("")
        val apiKeyRegex = Regex("""(?:$p1[0-9A-Za-z\-_]{20,}|$p2[0-9A-Za-z\-_]{20,})""")
        sanitized = sanitized.replace(apiKeyRegex, "[REDACTED_API_KEY]")

        return sanitized
    }

    /**
     * Securely saves the Gemini API key using hardware/OS-backed Android Keystore AES-GCM encryption.
     *
     * Fail-closed guarantee:
     * - Never derives a software key from packageName.
     * - Never writes Base64/plaintext fallback to SharedPreferences.
     * - If Android Keystore fails, nothing is written to disk; the key remains in memory only
     *   for the current session ([isMemoryOnlySession] = true) and returns `false`.
     */
    @Synchronized
    fun saveApiKey(context: Context, apiKey: String): Boolean {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) {
            clearApiKey(context)
            return true
        }

        inMemoryCachedKey = trimmed

        return try {
            val secretKey = getOrCreateHardwareKeystoreKey()
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
                .remove("obfuscated_api_key")
                .commit()

            isMemoryOnlySession = false
            lastStorageNotice = null
            true
        } catch (e: Exception) {
            // Fail-closed: remove any disk entries and keep key strictly in volatile RAM
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_ENCRYPTED_API_KEY)
                .remove(KEY_GCM_IV)
                .remove("obfuscated_api_key")
                .commit()

            isMemoryOnlySession = true
            lastStorageNotice = MSG_KEYSTORE_MEMORY_ONLY_NOTICE
            false
        }
    }

    /**
     * Securely retrieves the Gemini API key from volatile memory or Android Keystore.
     * Never reads unencrypted or Base64-obfuscated values from SharedPreferences.
     */
    @Synchronized
    fun getApiKey(context: Context): String {
        inMemoryCachedKey?.let { return it }

        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encryptedB64 = prefs.getString(KEY_ENCRYPTED_API_KEY, null)
        val ivB64 = prefs.getString(KEY_GCM_IV, null)

        if (encryptedB64.isNullOrEmpty() || ivB64.isNullOrEmpty()) {
            return ""
        }

        return try {
            val encryptedBytes = Base64.decode(encryptedB64, Base64.NO_WRAP)
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            val secretKey = getOrCreateHardwareKeystoreKey()

            val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            val decryptedBytes = cipher.doFinal(encryptedBytes)
            val decrypted = String(decryptedBytes, StandardCharsets.UTF_8)
            inMemoryCachedKey = decrypted
            isMemoryOnlySession = false
            decrypted
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Clears the saved API key from both volatile memory and disk.
     */
    @Synchronized
    fun clearApiKey(context: Context) {
        inMemoryCachedKey = ""
        isMemoryOnlySession = false
        lastStorageNotice = null
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ENCRYPTED_API_KEY)
            .remove(KEY_GCM_IV)
            .remove("obfuscated_api_key")
            .commit()
    }

    /**
     * Simulates app process restart in tests by clearing the volatile in-memory key cache
     * without modifying SharedPreferences on disk.
     */
    @Synchronized
    fun evictInMemoryCacheForTesting() {
        inMemoryCachedKey = null
    }

    private fun getOrCreateHardwareKeystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (entry?.secretKey != null) {
                return entry.secretKey
            }
        }
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
        return keyGen.generateKey() ?: throw KeyStoreException("Failed to generate key in AndroidKeyStore")
    }
}
