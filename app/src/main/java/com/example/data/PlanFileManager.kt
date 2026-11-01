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

        // Write atomically using a temporary file in the same directory
        val tempFile = File(attachmentsDir, "${safeName}_${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(tempFile).use { fos ->
                fos.write(bytes)
                fos.flush()
                runCatching { fos.fd.sync() }
            }
            require(tempFile.exists() && tempFile.length() == bytes.size.toLong()) {
                "فشل التحقق من اكتمال كتابة الملف المؤقت"
            }

            // Atomic POSIX rename replaces targetFile without leaving a window where targetFile is missing
            val renamed = tempFile.renameTo(targetFile)
            if (!renamed) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
        } catch (t: Throwable) {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            throw t
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
        try {
            FileOutputStream(tempFile).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var totalRead = 0L
                while (true) {
                    val read = inputStream.read(buffer)
                    if (read < 0) break
                    totalRead += read
                    if (totalRead > MAX_ATTACHMENT_BYTES) {
                        throw IllegalArgumentException("حجم الملف يتجاوز الحد الأقصى المسموح به (25 ميغابايت)")
                    }
                    output.write(buffer, 0, read)
                }
                output.flush()
                runCatching { output.fd.sync() }
            }

            require(tempFile.length() > 0) {
                "الملف المرفق فارغ"
            }

            val renamed = tempFile.renameTo(targetFile)
            if (!renamed) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
        } catch (t: Throwable) {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            throw t
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

    /**
     * Deletes a plan directory and all its contents bottom-up so that if a single attachment file
     * fails to delete, all other attachments and files in the directory are still deleted.
     * Returns true only if the entire directory was completely removed.
     */
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

        var allDeleted = true
        planDir.walkBottomUp().forEach { child ->
            if (child.exists() && !child.delete()) {
                allDeleted = false
            }
        }
        return allDeleted && !planDir.exists()
    }

    /**
     * Cleans up any leftover temporary (`*.tmp`) files from interrupted atomic writes
     * across all plan directories without touching valid attachment files.
     */
    fun cleanupTempFiles(): Int {
        var deletedCount = 0
        if (!basePlansDir.exists()) return 0
        basePlansDir.walkTopDown().forEach { file ->
            if (file.isFile && file.name.endsWith(".tmp")) {
                if (file.delete()) {
                    deletedCount++
                }
            }
        }
        return deletedCount
    }

    fun cleanupOrphanFiles(validPlanIds: Set<String>): Int {
        val sanitizedValidIds = validPlanIds.map { sanitizeFileName(it) }.toSet()
        var cleanedCount = 0
        basePlansDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.name !in sanitizedValidIds) {
                if (deletePlanDirectory(dir.name)) {
                    cleanedCount++
                }
            }
        }
        return cleanedCount
    }

    fun verifyFileIntegrity(file: File?): Boolean {
        return file != null && file.exists() && file.isFile && file.canRead() && file.length() > 0 && !file.name.endsWith(".tmp")
    }

    companion object {
        const val MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024 // 25 MB
    }
}
