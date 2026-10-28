package com.example.extractor

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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

    private enum class TableColType {
        STAGE, ABIL, TEACHER, CONTENT, EVAL, UNKNOWN
    }

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
     * Extracts text and structured table cells from Word .docx file by inspecting word/document.xml.
     */
    fun extractTextFromDocx(inputStream: InputStream): String {
        val zip = ZipInputStream(inputStream)
        var entry: ZipEntry? = zip.nextEntry

        while (entry != null) {
            if (entry.name == "word/document.xml") {
                val xml = zip.readBytes().toString(StandardCharsets.UTF_8)
                return parseDocumentXml(xml)
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
        return ""
    }

    /**
     * Parses word/document.xml, preserving table column roles (Stage, Abilities, Teacher, Content, Evaluation)
     * so that cell contents are never mixed up or lost during extraction.
     */
    fun parseDocumentXml(xml: String): String {
        val outputLines = mutableListOf<String>()

        // Match top-level tables <w:tbl>...</w:tbl> or paragraphs <w:p>...</w:p> in order
        val blockRegex = Regex("<w:tbl(?:\\s[^>]*)?>.*?</w:tbl>|<w:p(?:\\s[^>]*)?>.*?</w:p>", RegexOption.DOT_MATCHES_ALL)
        val matches = blockRegex.findAll(xml)

        for (match in matches) {
            val block = match.value
            if (block.startsWith("<w:tbl")) {
                outputLines.addAll(parseTableXml(block))
            } else {
                val pText = extractParagraphText(block)
                if (pText.isNotEmpty()) {
                    outputLines.add(pText)
                }
            }
        }

        return outputLines.joinToString("\n")
    }

    private fun parseTableXml(tblXml: String): List<String> {
        val result = mutableListOf<String>()
        val trRegex = Regex("<w:tr(?:\\s[^>]*)?>(.*?)</w:tr>", RegexOption.DOT_MATCHES_ALL)
        val tcRegex = Regex("<w:tc(?:\\s[^>]*)?>(.*?)</w:tc>", RegexOption.DOT_MATCHES_ALL)

        // Each row is a list of cells; each cell is a list of paragraph strings
        val rows = mutableListOf<List<List<String>>>()
        for (trMatch in trRegex.findAll(tblXml)) {
            val trContent = trMatch.groupValues[1]
            val cells = mutableListOf<List<String>>()
            for (tcMatch in tcRegex.findAll(trContent)) {
                val tcContent = tcMatch.groupValues[1]
                val cellParagraphs = extractCellParagraphs(tcContent)
                cells.add(cellParagraphs)
            }
            if (cells.isNotEmpty()) {
                rows.add(cells)
            }
        }

        if (rows.isEmpty()) return result

        // Check if this table is a Stages Table by looking for a header row in the first 3 rows
        var headerRowIndex = -1
        var colMapping = listOf<TableColType>()

        for (rIdx in 0 until minOf(3, rows.size)) {
            val row = rows[rIdx]
            if (row.size >= 3) {
                val flatHeaders = row.map { it.joinToString(" ").trim() }
                val mapped = flatHeaders.map { classifyColumnHeader(it) }
                val knownCount = mapped.count { it != TableColType.UNKNOWN }
                if (knownCount >= 2 && (mapped.contains(TableColType.STAGE) || mapped.contains(TableColType.CONTENT) || mapped.contains(TableColType.ABIL))) {
                    headerRowIndex = rIdx
                    colMapping = resolveUnknownColumns(mapped)
                    break
                }
            }
        }

        if (headerRowIndex != -1) {
            // Parse as Stages Table using column mapping
            for (rIdx in (headerRowIndex + 1) until rows.size) {
                val row = rows[rIdx]
                // Single-cell spanned row (e.g., Session Banner)
                if (row.size == 1) {
                    val bannerText = row[0].joinToString(" ").trim()
                    if (bannerText.isNotEmpty()) {
                        // Only emit as stage if it's an Expression session stage without a separate stage row
                        if (bannerText.contains("أنشطة الاكتساب") ||
                            bannerText.contains("أنشطة التطبيق") ||
                            bannerText.contains("أنشطة الإنتاج") ||
                            bannerText.contains("أنشطة التصحيح")
                        ) {
                            result.add("[حصة: $bannerText]")
                        }
                    }
                    continue
                }

                val stageLines = mutableListOf<String>()
                val abilLines = mutableListOf<String>()
                val teacherLines = mutableListOf<String>()
                val contentLines = mutableListOf<String>()
                val evalLines = mutableListOf<String>()

                for (cIdx in row.indices) {
                    val cellParas = row[cIdx].filter { it.isNotBlank() }
                    if (cellParas.isEmpty()) continue
                    val colType = colMapping.getOrElse(cIdx) { TableColType.CONTENT }
                    when (colType) {
                        TableColType.STAGE -> stageLines.addAll(cellParas)
                        TableColType.ABIL -> abilLines.addAll(cellParas)
                        TableColType.TEACHER -> teacherLines.addAll(cellParas)
                        TableColType.CONTENT -> contentLines.addAll(cellParas)
                        TableColType.EVAL -> evalLines.addAll(cellParas)
                        TableColType.UNKNOWN -> contentLines.addAll(cellParas)
                    }
                }

                if (stageLines.isNotEmpty()) {
                    result.add("[مرحلة: ${stageLines.joinToString(" - ")}]")
                }

                if (abilLines.isNotEmpty()) {
                    result.add("القدرات المستهدفة:")
                    abilLines.forEach { result.add(it) }
                }
                if (teacherLines.isNotEmpty()) {
                    result.add("أنشطة المدرس:")
                    teacherLines.forEach { result.add(it) }
                }
                if (contentLines.isNotEmpty()) {
                    result.add("المحتوى الديداكتيكي:")
                    contentLines.forEach { result.add(it) }
                }
                if (evalLines.isNotEmpty()) {
                    result.add("التقويم والدعم:")
                    evalLines.forEach { result.add(it) }
                }
            }
        } else {
            // Parse as Header / Metadata Table
            for (row in rows) {
                var cIdx = 0
                while (cIdx < row.size) {
                    val cellParas = row[cIdx].filter { it.isNotBlank() }
                    if (cellParas.size == 1 && !cellParas[0].contains(":") && cellParas[0].length <= 35 && cIdx + 1 < row.size) {
                        val nextParas = row[cIdx + 1].filter { it.isNotBlank() }
                        if (nextParas.isNotEmpty() && !nextParas[0].contains(":")) {
                            result.add("${cellParas[0]}: ${nextParas.joinToString(" - ")}")
                            cIdx += 2
                            continue
                        }
                    }
                    result.addAll(cellParas)
                    cIdx++
                }
            }
        }

        return result
    }

    private fun classifyColumnHeader(headerText: String): TableColType {
        val h = headerText.trim()
        return when {
            h.contains("المقاطع") || h.contains("المراحل") || h.contains("مراحل") ||
                    h.contains("الخطوات") || h.contains("المرحلة") -> TableColType.STAGE

            h.contains("القدرات") || h.contains("الأهداف") || h.contains("أهداف") ||
                    h.contains("الكفايات المستهدفة") -> TableColType.ABIL

            h.contains("التقويم") || h.contains("الدعم") || h.contains("مؤشرات") ||
                    h.contains("معايير") -> TableColType.EVAL

            h.contains("المحتوى") || h.contains("التعلمية") || h.contains("المتعلم") ||
                    h.contains("المضامين") || h.contains("سيرورة") -> TableColType.CONTENT

            h.contains("المدرس") || h.contains("الأستاذ") || h.contains("التعليمية") ||
                    h.contains("توجيهات") -> TableColType.TEACHER

            else -> TableColType.UNKNOWN
        }
    }

    private fun resolveUnknownColumns(mapped: List<TableColType>): List<TableColType> {
        if (!mapped.contains(TableColType.UNKNOWN)) return mapped
        val standard5 = listOf(TableColType.STAGE, TableColType.ABIL, TableColType.TEACHER, TableColType.CONTENT, TableColType.EVAL)
        val standard4 = listOf(TableColType.STAGE, TableColType.ABIL, TableColType.CONTENT, TableColType.EVAL)
        return mapped.mapIndexed { idx, type ->
            if (type != TableColType.UNKNOWN) {
                type
            } else if (mapped.size == 5) {
                standard5[idx]
            } else if (mapped.size == 4) {
                standard4[idx]
            } else {
                TableColType.CONTENT
            }
        }
    }

    private fun extractCellParagraphs(tcXml: String): List<String> {
        val pRegex = Regex("<w:p(?:\\s[^>]*)?>(.*?)</w:p>", RegexOption.DOT_MATCHES_ALL)
        val paras = mutableListOf<String>()
        for (pMatch in pRegex.findAll(tcXml)) {
            val pText = extractParagraphText(pMatch.value)
            if (pText.isNotEmpty()) {
                paras.add(pText)
            }
        }
        return paras
    }

    private fun extractParagraphText(pXml: String): String {
        val rRegex = Regex("<w:r(?:\\s[^>]*)?>(.*?)</w:r>", RegexOption.DOT_MATCHES_ALL)
        val tRegex = Regex("<w:t(?:\\s[^>]*)?>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
        val sb = StringBuilder()

        val rMatches = rRegex.findAll(pXml).toList()
        if (rMatches.isNotEmpty()) {
            for (rMatch in rMatches) {
                val rContent = rMatch.groupValues[1]
                val isUnderlined = rContent.contains("<w:u ") || rContent.contains("<w:u/>")
                val runTextBuilder = StringBuilder()
                for (tMatch in tRegex.findAll(rContent)) {
                    runTextBuilder.append(decodeXmlEntities(tMatch.groupValues[1]))
                }
                val runText = runTextBuilder.toString()
                if (runText.isNotEmpty()) {
                    if (isUnderlined && runText.trim().endsWith(":")) {
                        sb.append("<u>${runText.trim()}</u> ")
                    } else {
                        sb.append(runText)
                    }
                }
            }
        } else {
            for (tMatch in tRegex.findAll(pXml)) {
                sb.append(decodeXmlEntities(tMatch.groupValues[1]))
            }
        }

        return sb.toString().trim()
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
        return try {
            val utf8 = String(bytes, StandardCharsets.UTF_8)
            if (utf8.contains('\uFFFD')) {
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
