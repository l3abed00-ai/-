package com.example.data

import android.content.Context
import com.example.model.AttachmentInfo
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

class PlanFileManager(private val context: Context) {

    private val basePlansDir: File
        get() = File(context.filesDir, "plans").apply { if (!exists()) mkdirs() }

    fun getPlanDir(planId: String, createIfMissing: Boolean = false): File {
        val sanitizedId = sanitizeFileName(planId)
        val dir = File(basePlansDir, sanitizedId)
        if (createIfMissing && !dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getPlanAttachmentsDir(planId: String, createIfMissing: Boolean = false): File {
        val dir = File(getPlanDir(planId, createIfMissing), "attachments")
        if (createIfMissing && !dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getPlanExportsDir(planId: String, createIfMissing: Boolean = false): File {
        val dir = File(getPlanDir(planId, createIfMissing), "exports")
        if (createIfMissing && !dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun sanitizeFileName(rawName: String): String {
        val nameOnly = File(rawName).name
        val cleaned = nameOnly
            .replace("..", "")
            .replace("/", "")
            .replace("\\", "")
            .replace("\u0000", "")
            .replace(Regex("[^a-zA-Z0-9._\\-\\u0600-\\u06FF]"), "_")
            .trim()
        return cleaned.ifBlank { "file_${System.currentTimeMillis()}" }
    }

    fun saveAttachment(
        planId: String,
        fileName: String,
        bytes: ByteArray,
        mimeType: String = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    ): Result<AttachmentInfo> = runCatching {
        require(bytes.isNotEmpty()) { "الملف المرفق فارغ" }
        require(bytes.size <= MAX_ATTACHMENT_BYTES) { "حجم الملف يتجاوز الحد الأقصى المسموح به (25 ميغابايت)" }

        val safeName = sanitizeFileName(fileName)
        val attachmentsDir = getPlanAttachmentsDir(planId, createIfMissing = true)
        val targetFile = File(attachmentsDir, safeName)

        // Write atomically using a temporary file
        val tempFile = File(attachmentsDir, "${safeName}_${UUID.randomUUID()}.tmp")
        FileOutputStream(tempFile).use { fos ->
            fos.write(bytes)
            fos.flush()
        }

        if (targetFile.exists()) {
            targetFile.delete()
        }
        val renamed = tempFile.renameTo(targetFile)
        if (!renamed) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }

        val relativePath = "plans/${sanitizeFileName(planId)}/attachments/$safeName"
        AttachmentInfo(
            id = UUID.randomUUID().toString(),
            planId = planId,
            fileName = safeName,
            relativePath = relativePath,
            mimeType = mimeType,
            fileSize = targetFile.length(),
            createdAt = System.currentTimeMillis()
        )
    }

    fun saveAttachmentFromStream(
        planId: String,
        fileName: String,
        inputStream: InputStream,
        mimeType: String = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    ): Result<AttachmentInfo> = runCatching {
        val safeName = sanitizeFileName(fileName)
        val attachmentsDir = getPlanAttachmentsDir(planId, createIfMissing = true)
        val targetFile = File(attachmentsDir, safeName)

        val tempFile = File(attachmentsDir, "${safeName}_${UUID.randomUUID()}.tmp")
        tempFile.outputStream().use { output ->
            inputStream.copyTo(output)
            output.flush()
        }

        require(tempFile.length() > 0) {
            tempFile.delete()
            "الملف المرفق فارغ"
        }
        require(tempFile.length() <= MAX_ATTACHMENT_BYTES) {
            tempFile.delete()
            "حجم الملف يتجاوز الحد الأقصى المسموح به (25 ميغابايت)"
        }

        if (targetFile.exists()) {
            targetFile.delete()
        }
        if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }

        val relativePath = "plans/${sanitizeFileName(planId)}/attachments/$safeName"
        AttachmentInfo(
            id = UUID.randomUUID().toString(),
            planId = planId,
            fileName = safeName,
            relativePath = relativePath,
            mimeType = mimeType,
            fileSize = targetFile.length(),
            createdAt = System.currentTimeMillis()
        )
    }

    fun getAttachmentFile(planId: String, fileNameOrRelativePath: String): File? {
        val safeName = File(fileNameOrRelativePath).name
        val cleanName = sanitizeFileName(safeName)
        val planDir = getPlanDir(planId, createIfMissing = false)
        if (!planDir.exists()) return null

        val attachmentsDir = getPlanAttachmentsDir(planId, createIfMissing = false)
        if (!attachmentsDir.exists()) return null

        val file = File(attachmentsDir, cleanName)

        // Security check: ensure the file is strictly contained within the planDir to prevent path traversal
        val canonicalPlanPath = planDir.canonicalPath
        val canonicalFilePath = file.canonicalPath
        if (!canonicalFilePath.startsWith(canonicalPlanPath)) {
            return null
        }

        return if (file.exists() && file.isFile) file else null
    }

    fun deleteAttachmentFile(planId: String, fileNameOrRelativePath: String): Boolean {
        val file = getAttachmentFile(planId, fileNameOrRelativePath) ?: return false
        return file.delete()
    }

    fun deletePlanDirectory(planId: String): Boolean {
        val sanitizedId = sanitizeFileName(planId)
        val planDir = File(basePlansDir, sanitizedId)
        if (!planDir.exists()) return true

        // Security check
        val canonicalBase = basePlansDir.canonicalPath
        val canonicalPlan = planDir.canonicalPath
        if (!canonicalPlan.startsWith(canonicalBase) || canonicalPlan == canonicalBase) {
            return false
        }

        return planDir.deleteRecursively()
    }

    fun cleanupOrphanFiles(validPlanIds: Set<String>): Int {
        val sanitizedValidIds = validPlanIds.map { sanitizeFileName(it) }.toSet()
        var cleanedCount = 0
        basePlansDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.name !in sanitizedValidIds) {
                if (dir.deleteRecursively()) {
                    cleanedCount++
                }
            }
        }
        return cleanedCount
    }

    fun verifyFileIntegrity(file: File?): Boolean {
        return file != null && file.exists() && file.isFile && file.canRead() && file.length() > 0
    }

    companion object {
        const val MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024 // 25 MB
    }
}
