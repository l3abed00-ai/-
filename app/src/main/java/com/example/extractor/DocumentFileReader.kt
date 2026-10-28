package com.example.extractor

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class UploadedFileInfo(
    val name: String,
    val sizeBytes: Long,
    val textContent: String
)

object DocumentFileReader {

    fun readUploadedFile(context: Context, uri: Uri): UploadedFileInfo {
        val contentResolver = context.contentResolver
        var fileName = "ملف_جذاذة"
        var fileSize = 0L

        // Query file metadata
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) fileName = cursor.getString(nameIndex) ?: fileName
                if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
            }
        }

        val textContent = contentResolver.openInputStream(uri)?.use { stream ->
            if (fileName.lowercase().endsWith(".docx")) {
                extractTextFromDocx(stream)
            } else {
                readPlainText(stream)
            }
        }.orEmpty()

        return UploadedFileInfo(
            name = fileName,
            sizeBytes = fileSize,
            textContent = textContent.trim()
        )
    }

    /**
     * Extracts text from Word .docx file by inspecting word/document.xml inside the zip stream.
     */
    fun extractTextFromDocx(inputStream: InputStream): String {
        val zip = ZipInputStream(inputStream)
        var entry: ZipEntry? = zip.nextEntry
        val sb = StringBuilder()

        while (entry != null) {
            if (entry.name == "word/document.xml") {
                val xml = zip.readBytes().toString(StandardCharsets.UTF_8)
                return parseDocumentXml(xml)
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
        return sb.toString()
    }

    /**
     * Parses word/document.xml, turning paragraphs <w:p> into lines and extracting text from <w:t>.
     */
    fun parseDocumentXml(xml: String): String {
        val lines = mutableListOf<String>()
        // Match each paragraph <w:p>...</w:p> or self-closing <w:p/>
        val pRegex = Regex("<w:p(?:\\s[^>]*)?>(.*?)</w:p>", RegexOption.DOT_MATCHES_ALL)
        val tRegex = Regex("<w:t(?:\\s[^>]*)?>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)

        val pMatches = pRegex.findAll(xml)
        for (pMatch in pMatches) {
            val pContent = pMatch.groupValues[1]
            val textBuilder = StringBuilder()
            val tMatches = tRegex.findAll(pContent)
            for (tMatch in tMatches) {
                textBuilder.append(tMatch.groupValues[1])
            }
            val paragraphText = decodeXmlEntities(textBuilder.toString()).trim()
            if (paragraphText.isNotEmpty()) {
                lines.add(paragraphText)
            }
        }

        return lines.joinToString("\n")
    }

    private fun decodeXmlEntities(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    private fun readPlainText(stream: InputStream): String {
        val bytes = stream.readBytes()
        // Try UTF-8 first
        return try {
            val utf8 = String(bytes, StandardCharsets.UTF_8)
            if (utf8.contains('\uFFFD')) {
                // If replacement chars found, try Arabic Windows-1256
                try {
                    String(bytes, Charset.forName("windows-1256"))
                } catch (e: Exception) {
                    utf8
                }
            } else {
                utf8
            }
        } catch (e: Exception) {
            String(bytes, StandardCharsets.ISO_8859_1)
        }
    }
}
