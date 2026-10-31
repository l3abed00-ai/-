package com.example.extractor

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import com.example.model.ContentUnitStatus
import com.example.model.DocumentElementType
import com.example.model.DocxTemplateArchive
import com.example.model.ExtractedUnit
import com.example.model.StructuredTable
import com.example.model.StructuredTableCell
import com.example.model.StructuredTableRow
import com.example.model.UnitLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

enum class DetectedFileType {
    DOCX,
    PDF,
    PLAIN_TEXT
}

class DocumentReadException(
    val arabicMessage: String,
    cause: Throwable? = null
) : Exception(arabicMessage, cause)

data class StructuredDocxExtraction(
    val rawOriginalText: String,
    val structuredText: String,
    val units: List<ExtractedUnit>,
    val unextractableSegments: List<String> = emptyList(),
    val tables: List<StructuredTable> = emptyList(),
    val stageNestedTables: Map<String, List<StructuredTable>> = emptyMap(),
    val customTables: List<StructuredTable> = emptyList(),
    val templateArchive: DocxTemplateArchive? = null
)

data class UploadedFileInfo(
    val name: String,
    val sizeBytes: Long,
    val textContent: String,
    val detectedType: DetectedFileType = DetectedFileType.PLAIN_TEXT,
    val pdfBytes: ByteArray? = null,
    val rawOriginalText: String = textContent,
    val extractedUnits: List<ExtractedUnit> = emptyList(),
    val unextractableSegments: List<String> = emptyList(),
    val tables: List<StructuredTable> = emptyList(),
    val templateArchive: DocxTemplateArchive? = null
)

object DocumentFileReader {

    private val recentExtractions = ConcurrentHashMap<Int, StructuredDocxExtraction>()

    internal fun recordExtraction(extraction: StructuredDocxExtraction) {
        if (recentExtractions.size > 32) {
            recentExtractions.clear()
        }
        val trimmedKey = extraction.structuredText.trim().hashCode()
        recentExtractions[trimmedKey] = extraction
        recentExtractions[extraction.structuredText.hashCode()] = extraction
    }

    fun getRecordedExtraction(structuredText: String): StructuredDocxExtraction? {
        return recentExtractions[structuredText.hashCode()]
            ?: recentExtractions[structuredText.trim().hashCode()]
    }

    const val MAX_FILE_SIZE_BYTES: Long = 25L * 1024 * 1024 // 25 MB
    const val MAX_DOCX_UNCOMPRESSED_BYTES: Long = 20L * 1024 * 1024 // 20 MB
    const val MAX_PDF_SIZE_BYTES: Long = 10L * 1024 * 1024 // 10 MB

    const val MSG_DOC_UNSUPPORTED = "صيغة .doc القديمة غير مدعومة؛ افتحه في Word أو WPS واحفظه بصيغة .docx"
    const val MSG_FILE_TOO_LARGE = "حجم الملف كبير جدًا (يتجاوز الحد الأقصى 25 ميغابايت)؛ يرجى اختيار ملف أصغر حجماً."
    const val MSG_DOCX_UNCOMPRESSED_TOO_LARGE = "حجم محتوى ملف Word بعد فك الضغط كبير جدًا (يتجاوز 20 ميغابايت)."
    const val MSG_FILE_EMPTY = "الملف المرفوع فارغ ولا يحتوي على نص قابل للتفريغ."
    const val MSG_FILE_CORRUPTED_OR_PROTECTED = "الملف تالف أو محمي بكلمة سر؛ تعذر استخراج محتوى الجذاذة."
    const val MSG_FORMAT_UNSUPPORTED = "صيغة الملف غير مدعومة؛ يرجى اختيار ملف وورد (.docx) أو PDF أو ملف نصي عربي."

    private val HEADER_FOOTER_ENTRY_REGEX = Regex("^word/(header|footer)\\d*\\.xml$", RegexOption.IGNORE_CASE)

    /**
     * Intermediate model for streaming XmlPullParser extraction.
     */
    data class Para(val text: String)

    data class TableCell(
        val paragraphs: List<Para>,
        val gridSpan: Int = 1,
        val vMerge: String? = null,
        val ownParagraphs: List<Para> = paragraphs,
        val nestedTables: List<Table> = emptyList(),
        val widthDxa: Int? = null,
        val backgroundHex: String? = null,
        val horizontalAlignment: String? = null,
        val verticalAlignment: String? = null
    ) {
        fun toStructuredTableCell(isHeaderRow: Boolean = false): StructuredTableCell {
            val cleanParas = ownParagraphs
                .map { stripFormattingTags(it.text).trim() }
                .filter { it.isNotEmpty() }
            return StructuredTableCell(
                paragraphs = cleanParas,
                gridSpan = gridSpan.coerceAtLeast(1),
                vMerge = vMerge,
                widthDxa = widthDxa,
                backgroundHex = backgroundHex,
                horizontalAlignment = horizontalAlignment,
                verticalAlignment = verticalAlignment,
                isHeader = isHeaderRow,
                nestedTables = nestedTables.mapIndexed { idx, tbl ->
                    tbl.toStructuredTable(id = "nested_tbl_$idx")
                }
            )
        }
    }

    data class Table(
        val rows: List<List<TableCell>>,
        val columnWidthsDxa: List<Int> = emptyList(),
        val isRtl: Boolean = true,
        val hasExplicitBidiVisual: Boolean = false,
        val hasBorders: Boolean = true,
        val rowHeaderFlags: List<Boolean> = emptyList()
    ) {
        fun toStructuredTable(
            id: String = "table",
            title: String = "",
            sourceStageKey: String? = null,
            sourceField: String = "content"
        ): StructuredTable {
            return StructuredTable(
                id = id,
                title = title,
                rows = rows.mapIndexed { rIdx, rowCells ->
                    val isHdr = rowHeaderFlags.getOrNull(rIdx) ?: false
                    StructuredTableRow(
                        cells = rowCells.map { it.toStructuredTableCell(isHdr) },
                        isHeader = isHdr
                    )
                },
                columnWidthsDxa = columnWidthsDxa,
                isRtl = isRtl,
                hasBorders = hasBorders,
                sourceStageKey = sourceStageKey,
                sourceField = sourceField
            )
        }
    }

    private sealed interface DocElement {
        data class ParaItem(val para: Para) : DocElement
        data class TableItem(val table: Table) : DocElement
    }

    private enum class TableColType {
        STAGE, SEGMENT, ABIL, TEACHER, LEARNER, ACTIVITIES, CONTENT, EVAL, TIME, UNKNOWN
    }

    private data class CellNode(
        val id: Int,
        val rawXml: String,
        val paragraphBlocks: List<List<String>>,
        val flatParagraphs: List<String>,
        val contentLinesWithTables: List<String>,
        val nestedTables: List<StructuredTable> = emptyList()
    )

    private data class TableParseResult(
        val lines: List<String>,
        val isPreLessonHeader: Boolean = false,
        val isLessonStagesTable: Boolean = false,
        val stageNestedTables: Map<String, List<StructuredTable>> = emptyMap()
    )

    fun cleanTopicFromFilename(fileName: String): String {
        val stem = if (fileName.contains(".")) fileName.substringBeforeLast(".") else fileName
        val stopWords = setOf(
            "جذاذة", "جذاذه", "درس", "تربوية", "تربويه", "رقم", "مهارة", "مهاره",
            "المختار", "الاحترافية", "الاحترافيه", "اللغة", "اللغه", "العربية", "العربيه",
            "اع", "إع", "أع", "ثانوي", "اعدادي", "إعدادي", "أعدادي",
            "الاسدوس", "الأسدوس", "الاول", "الأول", "الثاني", "الثالثة", "الثالثه"
        )
        val tokens = stem
            .replace(Regex("[_\\-.\\d()\\[\\]«»\"']+"), " ")
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() && it !in stopWords }
        var s = tokens.joinToString(" ").trim()
        if (s.startsWith("في ")) s = s.removePrefix("في ").trim()
        return s.ifBlank { stem.trim() }
    }

    suspend fun readUploadedFile(context: Context, uri: Uri): UploadedFileInfo = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val contentResolver = context.contentResolver
        var fileName = "ملف_جذاذة"
        var fileSize = 0L

        if (uri.scheme == "file") {
            val path = uri.path
            if (!path.isNullOrBlank()) {
                val f = File(path)
                fileName = f.name.ifBlank { fileName }
                fileSize = f.length()
            }
        } else {
            runCatching {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIndex != -1) {
                            val n = cursor.getString(nameIndex)
                            if (!n.isNullOrBlank()) fileName = n
                        }
                        if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                            fileSize = cursor.getLong(sizeIndex)
                        }
                    }
                }
            }
            if (fileName == "ملف_جذاذة") {
                uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let {
                    fileName = it
                }
            }
        }

        if (fileSize > MAX_FILE_SIZE_BYTES) {
            throw DocumentReadException(MSG_FILE_TOO_LARGE)
        }

        val rawStream = try {
            if (uri.scheme == "file") {
                File(uri.path ?: throw DocumentReadException(MSG_FILE_CORRUPTED_OR_PROTECTED)).inputStream()
            } else {
                contentResolver.openInputStream(uri)
            }
        } catch (e: DocumentReadException) {
            throw e
        } catch (e: Exception) {
            throw DocumentReadException(MSG_FILE_CORRUPTED_OR_PROTECTED, e)
        } ?: throw DocumentReadException(MSG_FILE_CORRUPTED_OR_PROTECTED)

        rawStream.use { stream ->
            inspectAndReadStream(
                inputStream = stream,
                fileName = fileName,
                declaredSize = fileSize,
                onCheckActive = { coroutineContext.ensureActive() }
            )
        }
    }

    /**
     * Inspects magic bytes from [inputStream] rather than relying on the file extension,
     * enforces size limits (25 MB raw, 20 MB uncompressed DOCX), and returns [UploadedFileInfo]
     * or throws [DocumentReadException] with a clear Arabic message.
     */
    fun inspectAndReadStream(
        inputStream: InputStream,
        fileName: String = "ملف_جذاذة",
        declaredSize: Long = 0L,
        onCheckActive: () -> Unit = {}
    ): UploadedFileInfo {
        if (declaredSize > MAX_FILE_SIZE_BYTES) {
            throw DocumentReadException(MSG_FILE_TOO_LARGE)
        }

        val buffered = if (inputStream is BufferedInputStream) inputStream else BufferedInputStream(inputStream, 64 * 1024)
        buffered.mark(16)
        val header = ByteArray(8)
        var headerRead = 0
        while (headerRead < header.size) {
            val r = buffered.read(header, headerRead, header.size - headerRead)
            if (r == -1) break
            headerRead += r
        }
        if (headerRead == 0) {
            throw DocumentReadException(MSG_FILE_EMPTY)
        }
        buffered.reset()

        val b0 = header[0].toInt() and 0xFF
        val b1 = if (headerRead > 1) header[1].toInt() and 0xFF else -1
        val b2 = if (headerRead > 2) header[2].toInt() and 0xFF else -1
        val b3 = if (headerRead > 3) header[3].toInt() and 0xFF else -1

        // 1. Check OLE2 Compound Document: D0 CF 11 E0 -> Legacy Word (.doc) or encrypted Office container
        if (headerRead >= 4 && b0 == 0xD0 && b1 == 0xCF && b2 == 0x11 && b3 == 0xE0) {
            throw DocumentReadException(MSG_DOC_UNSUPPORTED)
        }

        // 2. Check PDF: %PDF (25 50 44 46)
        if (headerRead >= 4 && b0 == 0x25 && b1 == 0x50 && b2 == 0x44 && b3 == 0x46) {
            val pdfBytes = readStreamBounded(buffered, MAX_FILE_SIZE_BYTES, onCheckActive)
            if (pdfBytes.size <= 4) {
                throw DocumentReadException(MSG_FILE_EMPTY)
            }
            return UploadedFileInfo(
                name = fileName,
                sizeBytes = if (declaredSize > 0L) declaredSize else pdfBytes.size.toLong(),
                textContent = "",
                detectedType = DetectedFileType.PDF,
                pdfBytes = pdfBytes
            )
        }

        // 3. Check ZIP / PK header: 50 4B 03 04 (or 50 4B 05 06 / 50 4B 07 08)
        if (headerRead >= 2 && b0 == 0x50 && b1 == 0x4B) {
            if (headerRead >= 4 && b2 == 0x05 && b3 == 0x06) {
                // Empty ZIP archive
                throw DocumentReadException(MSG_FILE_EMPTY)
            }
            val countedStream = BoundedCountingInputStream(buffered, MAX_FILE_SIZE_BYTES, onCheckActive)
            val structured = extractStructuredDocxInternal(
                inputStream = countedStream,
                requireDocumentXmlEntry = true,
                onCheckActive = onCheckActive
            )
            if (structured.structuredText.isBlank() && structured.rawOriginalText.isBlank() && structured.tables.isEmpty()) {
                throw DocumentReadException(MSG_FILE_EMPTY)
            }
            return UploadedFileInfo(
                name = fileName,
                sizeBytes = if (declaredSize > 0L) declaredSize else countedStream.totalBytesRead,
                textContent = structured.structuredText.trim(),
                detectedType = DetectedFileType.DOCX,
                rawOriginalText = structured.rawOriginalText,
                extractedUnits = structured.units,
                unextractableSegments = structured.unextractableSegments,
                tables = structured.tables,
                templateArchive = structured.templateArchive
            )
        }

        // 4. Reject known binary signatures (PNG, JPEG, GIF, RAR, 7Z, ELF, GZIP)
        if (isKnownBinarySignature(header, headerRead)) {
            throw DocumentReadException(MSG_FORMAT_UNSUPPORTED)
        }

        // 5. Plain text (UTF-8 then windows-1256) with binary/garbled rejection
        val rawBytes = readStreamBounded(buffered, MAX_FILE_SIZE_BYTES, onCheckActive)
        if (rawBytes.isEmpty()) {
            throw DocumentReadException(MSG_FILE_EMPTY)
        }
        val decodedText = decodeAndValidatePlainText(rawBytes)
        if (decodedText.isBlank()) {
            throw DocumentReadException(MSG_FILE_EMPTY)
        }

        val plainUnits = buildPlainTextUnits(decodedText)
        val plainExtraction = StructuredDocxExtraction(
            rawOriginalText = decodedText,
            structuredText = decodedText.trim(),
            units = plainUnits
        )
        recordExtraction(plainExtraction)

        return UploadedFileInfo(
            name = fileName,
            sizeBytes = if (declaredSize > 0L) declaredSize else rawBytes.size.toLong(),
            textContent = decodedText.trim(),
            detectedType = DetectedFileType.PLAIN_TEXT,
            rawOriginalText = decodedText,
            extractedUnits = plainUnits
        )
    }

    /**
     * Extracts text and structured table cells from Word .docx file by streaming word/document.xml,
     * word/header*.xml, and word/footer*.xml with a 20 MB uncompressed size limit.
     */
    fun extractTextFromDocx(inputStream: InputStream): String {
        return extractStructuredDocxInternal(
            inputStream = inputStream,
            requireDocumentXmlEntry = false,
            onCheckActive = {}
        ).structuredText
    }

    /**
     * Extracts the full [StructuredDocxExtraction] from a .docx stream, including the verbatim
     * original text before processing, structured text, and traceable [ExtractedUnit]s with [UnitLocation].
     */
    fun extractStructuredDocx(inputStream: InputStream): StructuredDocxExtraction {
        return extractStructuredDocxInternal(
            inputStream = inputStream,
            requireDocumentXmlEntry = false,
            onCheckActive = {}
        )
    }

    fun extractStructuredDocx(file: java.io.File): StructuredDocxExtraction {
        return java.io.FileInputStream(file).use { extractStructuredDocx(it) }
    }

    private fun extractStructuredDocxInternal(
        inputStream: InputStream,
        requireDocumentXmlEntry: Boolean,
        onCheckActive: () -> Unit
    ): StructuredDocxExtraction {
        var foundDocumentXml = false
        var foundAnyEntry = false
        var totalUncompressedBytes = 0L
        var documentXmlRaw = ""
        val headerFooterXmlList = mutableListOf<Pair<String, String>>()
        val zipEntriesMap = LinkedHashMap<String, ByteArray>()

        fun readCurrentZipEntryBytes(zip: ZipInputStream): ByteArray {
            val out = ByteArrayOutputStream(16 * 1024)
            val buffer = ByteArray(8 * 1024)
            while (true) {
                onCheckActive()
                val read = zip.read(buffer)
                if (read == -1) break
                totalUncompressedBytes += read
                if (totalUncompressedBytes > MAX_DOCX_UNCOMPRESSED_BYTES) {
                    throw DocumentReadException(MSG_DOCX_UNCOMPRESSED_TOO_LARGE)
                }
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }

        try {
            val zip = ZipInputStream(inputStream)
            var entry: ZipEntry? = zip.nextEntry

            while (entry != null) {
                onCheckActive()
                foundAnyEntry = true
                val entryName = entry.name

                if (entryName.equals("EncryptionInfo", ignoreCase = true) ||
                    entryName.equals("EncryptedPackage", ignoreCase = true)
                ) {
                    throw DocumentReadException(MSG_FILE_CORRUPTED_OR_PROTECTED)
                }

                if (!entry.isDirectory) {
                    val entryBytes = readCurrentZipEntryBytes(zip)
                    zipEntriesMap[entryName] = entryBytes
                    if (entryName.equals("word/document.xml", ignoreCase = true)) {
                        foundDocumentXml = true
                        documentXmlRaw = entryBytes.toString(StandardCharsets.UTF_8)
                    } else if (HEADER_FOOTER_ENTRY_REGEX.matches(entryName)) {
                        val hfXml = entryBytes.toString(StandardCharsets.UTF_8)
                        headerFooterXmlList.add(entryName to hfXml)
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        } catch (e: DocumentReadException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ZipException) {
            throw DocumentReadException(MSG_FILE_CORRUPTED_OR_PROTECTED, e)
        } catch (e: Exception) {
            if (requireDocumentXmlEntry) {
                throw DocumentReadException(MSG_FILE_CORRUPTED_OR_PROTECTED, e)
            }
            return StructuredDocxExtraction("", "", emptyList())
        }

        if (requireDocumentXmlEntry) {
            if (!foundAnyEntry) {
                throw DocumentReadException(MSG_FILE_CORRUPTED_OR_PROTECTED)
            }
            if (!foundDocumentXml) {
                throw DocumentReadException(MSG_FORMAT_UNSUPPORTED)
            }
        }

        if (!foundDocumentXml) {
            return StructuredDocxExtraction("", "", emptyList())
        }

        val archiveMetadata = buildDocxTemplateArchive(zipEntriesMap, documentXmlRaw)

        val mainStructured = extractStructuredXml(
            xml = documentXmlRaw,
            sourcePart = "word/document.xml",
            startUnitId = 1,
            startLineNumber = 1
        ).copy(templateArchive = archiveMetadata)

        if (headerFooterXmlList.isEmpty()) {
            recordExtraction(mainStructured)
            return mainStructured
        }

        // Sort headers first (header1, header2, ...) then footers (footer1, footer2, ...) deterministically
        val sortedHeaderFooters = headerFooterXmlList.sortedWith(
            compareBy<Pair<String, String>> { (name, _) ->
                if (name.lowercase().contains("header")) 0 else 1
            }.thenBy { (name, _) -> name.lowercase() }
        )

        val outputLines = mutableListOf<String>()
        val seenNonBlankLines = mutableSetOf<String>()
        val allUnits = mainStructured.units.toMutableList()
        val allUnextractable = mainStructured.unextractableSegments.toMutableList()
        val rawOriginalParts = mutableListOf<String>()
        if (mainStructured.rawOriginalText.isNotEmpty()) {
            rawOriginalParts.add(mainStructured.rawOriginalText)
        }

        if (mainStructured.structuredText.isNotEmpty()) {
            for (line in mainStructured.structuredText.lines()) {
                outputLines.add(line)
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    seenNonBlankLines.add(trimmed)
                }
            }
        }

        for ((entryName, hfXml) in sortedHeaderFooters) {
            val hfStructured = extractStructuredXml(
                xml = hfXml,
                sourcePart = entryName,
                startUnitId = allUnits.size + 1,
                startLineNumber = outputLines.size + 1
            )
            allUnextractable.addAll(hfStructured.unextractableSegments)
            if (hfStructured.structuredText.isNotEmpty()) {
                for (line in hfStructured.structuredText.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && trimmed != "[نهاية الجدول]" && seenNonBlankLines.add(trimmed)) {
                        outputLines.add(trimmed)
                    }
                }
            }
            for (u in hfStructured.units) {
                val alreadyInStructuredOutput = outputLines.any { it.trim() == u.verbatimText.trim() }
                val unitToAdd = if (alreadyInStructuredOutput) {
                    u.copy(
                        id = "unit_${allUnits.size + 1}",
                        status = ContentUnitStatus.CLASSIFIED,
                        assignedField = u.assignedField ?: "header_metadata"
                    )
                } else {
                    u.copy(id = "unit_${allUnits.size + 1}")
                }
                allUnits.add(unitToAdd)
            }
        }

        val finalStructuredText = outputLines.joinToString("\n")
        val fullRawOriginalText = if (allUnits.isNotEmpty()) {
            allUnits.joinToString("\n") { it.verbatimText }
        } else {
            rawOriginalParts.joinToString("\n")
        }
        val combinedExtraction = StructuredDocxExtraction(
            rawOriginalText = fullRawOriginalText,
            structuredText = finalStructuredText,
            units = allUnits,
            unextractableSegments = allUnextractable,
            tables = mainStructured.tables,
            stageNestedTables = mainStructured.stageNestedTables,
            customTables = mainStructured.customTables,
            templateArchive = archiveMetadata
        )
        recordExtraction(combinedExtraction)
        return combinedExtraction
    }

    internal fun buildDocxTemplateArchive(
        zipEntries: Map<String, ByteArray>,
        documentXml: String
    ): DocxTemplateArchive {
        val docRootAttrs = Regex("<w:document\\b([^>]*)>", RegexOption.DOT_MATCHES_ALL)
            .find(documentXml)?.groupValues?.get(1)?.trim().orEmpty()
        val sectPrXml = Regex("<w:sectPr\\b[^>]*>.*?</w:sectPr>", RegexOption.DOT_MATCHES_ALL)
            .findAll(documentXml).lastOrNull()?.value

        val bodyContent = Regex("<w:body(?:\\s[^>]*)?>(.*)</w:body>", RegexOption.DOT_MATCHES_ALL)
            .find(documentXml)?.groupValues?.get(1).orEmpty()
        val bodyWithoutSectPr = if (sectPrXml != null) bodyContent.replace(sectPrXml, "") else bodyContent
        val topBlocks = extractTopLevelBlocks(bodyWithoutSectPr)
        val firstTableIdx = topBlocks.indexOfFirst { it.first }
        val lastTableIdx = topBlocks.indexOfLast { it.first }

        fun isPreservableDecorativeBlock(isTable: Boolean, blockXml: String): Boolean {
            if (isTable) return false
            return blockXml.contains("<w:drawing") ||
                blockXml.contains("<v:shape") ||
                blockXml.contains("<v:imagedata") ||
                blockXml.contains("<wp:inline") ||
                blockXml.contains("<wp:anchor") ||
                blockXml.contains("<w:pict")
        }

        val leadingXml = if (firstTableIdx > 0) {
            topBlocks.subList(0, firstTableIdx)
                .filter { (isTbl, xml) -> isPreservableDecorativeBlock(isTbl, xml) }
                .map { it.second }
        } else {
            topBlocks.filter { (isTbl, xml) -> isPreservableDecorativeBlock(isTbl, xml) }
                .map { it.second }
        }

        val trailingXml = if (lastTableIdx >= 0 && lastTableIdx + 1 < topBlocks.size) {
            topBlocks.subList(lastTableIdx + 1, topBlocks.size)
                .filter { (isTbl, xml) -> isPreservableDecorativeBlock(isTbl, xml) }
                .map { it.second }
        } else {
            emptyList()
        }

        val stylesXml = zipEntries.entries
            .firstOrNull { it.key.equals("word/styles.xml", ignoreCase = true) }
            ?.value?.toString(StandardCharsets.UTF_8).orEmpty()

        val fontSearchSource = stylesXml.ifEmpty { documentXml }
        val primaryFont = Regex("w:(?:cs|ascii|hAnsi)=\"([^\"]+)\"")
            .find(fontSearchSource)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        val primaryFontSize = Regex("<w:szCs\\s+w:val=\"(\\d+)\"")
            .find(fontSearchSource)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("<w:sz\\s+w:val=\"(\\d+)\"")
                .find(fontSearchSource)?.groupValues?.get(1)?.toIntOrNull()

        return DocxTemplateArchive(
            zipEntries = zipEntries,
            documentRootAttributes = docRootAttrs,
            preservedSectPrXml = sectPrXml,
            preservedLeadingElementsXml = leadingXml,
            preservedTrailingElementsXml = trailingXml,
            primaryFontName = primaryFont,
            primaryFontSizeHalfPt = primaryFontSize
        )
    }

    private class BoundedCountingInputStream(
        private val delegate: InputStream,
        private val maxBytes: Long,
        private val onCheckActive: () -> Unit
    ) : InputStream() {
        var totalBytesRead: Long = 0L
            private set

        override fun read(): Int {
            onCheckActive()
            val b = delegate.read()
            if (b != -1) {
                totalBytesRead++
                if (totalBytesRead > maxBytes) {
                    throw DocumentReadException(MSG_FILE_TOO_LARGE)
                }
            }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            onCheckActive()
            val n = delegate.read(b, off, len)
            if (n > 0) {
                totalBytesRead += n
                if (totalBytesRead > maxBytes) {
                    throw DocumentReadException(MSG_FILE_TOO_LARGE)
                }
            }
            return n
        }

        override fun close() {
            delegate.close()
        }
    }

    private fun readStreamBounded(
        inputStream: InputStream,
        maxBytes: Long,
        onCheckActive: () -> Unit
    ): ByteArray {
        val out = ByteArrayOutputStream(16 * 1024)
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            onCheckActive()
            val read = inputStream.read(buffer)
            if (read == -1) break
            total += read
            if (total > maxBytes) {
                throw DocumentReadException(MSG_FILE_TOO_LARGE)
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun isKnownBinarySignature(header: ByteArray, length: Int): Boolean {
        if (length < 4) return false
        val b0 = header[0].toInt() and 0xFF
        val b1 = header[1].toInt() and 0xFF
        val b2 = header[2].toInt() and 0xFF
        val b3 = header[3].toInt() and 0xFF
        // PNG: 89 50 4E 47
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return true
        // JPEG: FF D8 FF
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return true
        // GIF: 47 49 46 38
        if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46 && b3 == 0x38) return true
        // RAR: 52 61 72 21
        if (b0 == 0x52 && b1 == 0x61 && b2 == 0x72 && b3 == 0x21) return true
        // 7Z: 37 7A BC AF
        if (b0 == 0x37 && b1 == 0x7A && b2 == 0xBC && b3 == 0xAF) return true
        // ELF: 7F 45 4C 46
        if (b0 == 0x7F && b1 == 0x45 && b2 == 0x4C && b3 == 0x46) return true
        // GZIP: 1F 8B
        if (b0 == 0x1F && b1 == 0x8B) return true
        return false
    }

    private fun decodeAndValidatePlainText(bytes: ByteArray): String {
        // Check raw bytes for null bytes or excessive control bytes
        val sampleSize = minOf(bytes.size, 8192)
        var nullCount = 0
        var rawControlCount = 0
        for (i in 0 until sampleSize) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0x00) {
                nullCount++
            } else if (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D) {
                rawControlCount++
            }
        }
        // Check UTF-16 BOM (FE FF or FF FE) which may legitimately contain null bytes for ASCII chars
        val hasUtf16Bom = bytes.size >= 2 && (
            ((bytes[0].toInt() and 0xFF) == 0xFE && (bytes[1].toInt() and 0xFF) == 0xFF) ||
                ((bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xFF) == 0xFE)
            )
        if (!hasUtf16Bom && (nullCount > 0 || (sampleSize > 0 && rawControlCount.toDouble() / sampleSize > 0.08))) {
            throw DocumentReadException(MSG_FORMAT_UNSUPPORTED)
        }

        val decoded = if (hasUtf16Bom) {
            String(bytes, StandardCharsets.UTF_16)
        } else {
            val utf8 = String(bytes, StandardCharsets.UTF_8)
            if (utf8.contains('\uFFFD')) {
                val win1256 = try {
                    String(bytes, Charset.forName("windows-1256"))
                } catch (_: Exception) {
                    utf8
                }
                win1256
            } else {
                utf8
            }
        }

        // Verify the decoded string does not have excessive replacement characters or control chars
        if (decoded.isNotEmpty()) {
            var replacementOrControl = 0
            for (ch in decoded) {
                val code = ch.code
                if (ch == '\uFFFD' || (code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r') || code in 0x7F..0x9F) {
                    replacementOrControl++
                }
            }
            val ratio = replacementOrControl.toDouble() / decoded.length.toDouble()
            if (ratio > 0.05) {
                throw DocumentReadException(MSG_FORMAT_UNSUPPORTED)
            }
        }

        return stripFormattingTags(decoded)
    }

    /**
     * Strips all HTML/XML formatting tags such as <u>, </u>, <b>, </b> from extracted text.
     */
    internal fun stripFormattingTags(text: String): String {
        if (!text.contains('<')) return text
        return text.replace(Regex("</?(?:u|b|i|strong|em)\\b[^>]*>", RegexOption.IGNORE_CASE), "")
    }

    /**
     * Parses Word XML (word/document.xml, word/header*.xml, word/footer*.xml) using streaming
     * XmlPullParser with FEATURE_PROCESS_NAMESPACES enabled. Falls back to [parseDocumentXmlLegacy]
     * if the XML is malformed or any parsing error occurs.
     */
    fun parseDocumentXml(xml: String): String {
        if (xml.isBlank()) return ""
        val structured = extractStructuredXml(xml)
        recordExtraction(structured)
        return structured.structuredText
    }

    /**
     * Parses Word XML into a [StructuredDocxExtraction] containing:
     * 1. [StructuredDocxExtraction.rawOriginalText]: Verbatim copy of extracted text before table marker formatting.
     * 2. [StructuredDocxExtraction.structuredText]: Formatted text for [JadhadhaParser].
     * 3. [StructuredDocxExtraction.units]: Independent traceable [ExtractedUnit]s with unique IDs and [UnitLocation].
     * 4. [StructuredDocxExtraction.unextractableSegments]: Any corrupted/unclosed XML segments that could not be parsed cleanly.
     * 5. [StructuredDocxExtraction.tables]: Structured representation of all top-level tables.
     */
    fun extractStructuredXml(
        xml: String,
        sourcePart: String = "word/document.xml",
        startUnitId: Int = 1,
        startLineNumber: Int = 1
    ): StructuredDocxExtraction {
        if (xml.isBlank()) return StructuredDocxExtraction("", "", emptyList())
        val isHeaderFooter = sourcePart.lowercase().contains("header") || sourcePart.lowercase().contains("footer")
        return try {
            val elements = buildDocumentElements(xml)
            val outputLines = mutableListOf<String>()
            val units = mutableListOf<ExtractedUnit>()
            val rawVerbatimLines = mutableListOf<String>()
            val allStructuredTables = mutableListOf<StructuredTable>()
            val stageNestedTablesMap = LinkedHashMap<String, MutableList<StructuredTable>>()
            val customStandaloneTables = mutableListOf<StructuredTable>()
            var nextUnitId = startUnitId
            var nextLineNum = startLineNumber
            var paraCounter = 0
            var tableCounter = 0

            for ((elemIdx, element) in elements.withIndex()) {
                when (element) {
                    is DocElement.ParaItem -> {
                        val t = stripFormattingTags(element.para.text).trim()
                        if (t.isNotEmpty()) {
                            outputLines.add(t)
                            rawVerbatimLines.add(t)
                            units.add(
                                ExtractedUnit(
                                    id = "unit_${nextUnitId++}",
                                    verbatimText = t,
                                    elementType = if (isHeaderFooter) DocumentElementType.HEADER_FOOTER else DocumentElementType.PARAGRAPH,
                                    location = UnitLocation(
                                        sourcePart = sourcePart,
                                        elementIndex = elemIdx,
                                        paragraphIndex = paraCounter++,
                                        tableIndex = null,
                                        rowIndex = null,
                                        colIndex = null,
                                        lineNumber = nextLineNum++
                                    ),
                                    status = ContentUnitStatus.EXTRACTED
                                )
                            )
                        }
                    }
                    is DocElement.TableItem -> {
                        val currentTableIdx = tableCounter++
                        val structuredTbl = element.table.toStructuredTable(id = "tbl_$currentTableIdx")
                        allStructuredTables.add(structuredTbl)

                        for ((rIdx, row) in element.table.rows.withIndex()) {
                            for ((cIdx, cell) in row.withIndex()) {
                                if (cell.vMerge == "continue" && cell.paragraphs.all { it.text.isBlank() }) continue
                                var cellParaIdx = 0
                                for (para in cell.paragraphs) {
                                    val t = stripFormattingTags(para.text).trim()
                                    if (t.isNotEmpty()) {
                                        rawVerbatimLines.add(t)
                                        units.add(
                                            ExtractedUnit(
                                                id = "unit_${nextUnitId++}",
                                                verbatimText = t,
                                                elementType = if (isHeaderFooter) DocumentElementType.HEADER_FOOTER else DocumentElementType.TABLE_CELL,
                                                location = UnitLocation(
                                                    sourcePart = sourcePart,
                                                    elementIndex = elemIdx,
                                                    paragraphIndex = cellParaIdx++,
                                                    tableIndex = currentTableIdx,
                                                    rowIndex = rIdx,
                                                    colIndex = cIdx,
                                                    lineNumber = nextLineNum++
                                                ),
                                                status = ContentUnitStatus.EXTRACTED
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        val tableMeta = parseTableXmlWithMetadata(element.table)
                        outputLines.addAll(tableMeta.lines.map { stripFormattingTags(it) })
                        for ((stgTitle, nestedTbls) in tableMeta.stageNestedTables) {
                            stageNestedTablesMap.getOrPut(stgTitle) { mutableListOf() }.addAll(nestedTbls)
                        }
                        if (!isHeaderFooter && !tableMeta.isPreLessonHeader && !tableMeta.isLessonStagesTable) {
                            customStandaloneTables.add(structuredTbl)
                        }
                    }
                }
            }

            StructuredDocxExtraction(
                rawOriginalText = rawVerbatimLines.joinToString("\n"),
                structuredText = outputLines.joinToString("\n"),
                units = units,
                unextractableSegments = emptyList(),
                tables = allStructuredTables,
                stageNestedTables = stageNestedTablesMap,
                customTables = customStandaloneTables
            )
        } catch (_: Throwable) {
            val legacyText = parseDocumentXmlLegacy(xml)
            val units = mutableListOf<ExtractedUnit>()
            val unextractable = detectUnclosedXmlSegments(xml)
            var nextUnitId = startUnitId
            var nextLineNum = startLineNumber
            for ((idx, line) in legacyText.lines().withIndex()) {
                val t = line.trim()
                if (t.isNotEmpty() && t != "[نهاية الجدول]") {
                    units.add(
                        ExtractedUnit(
                            id = "unit_${nextUnitId++}",
                            verbatimText = t,
                            elementType = if (isHeaderFooter) DocumentElementType.HEADER_FOOTER else DocumentElementType.PARAGRAPH,
                            location = UnitLocation(
                                sourcePart = sourcePart,
                                elementIndex = idx,
                                paragraphIndex = idx,
                                lineNumber = nextLineNum++
                            ),
                            status = ContentUnitStatus.EXTRACTED
                        )
                    )
                }
            }
            for (corrupted in unextractable) {
                units.add(
                    ExtractedUnit(
                        id = "unit_${nextUnitId++}",
                        verbatimText = corrupted,
                        elementType = DocumentElementType.PARAGRAPH,
                        location = UnitLocation(
                            sourcePart = sourcePart,
                            elementIndex = units.size,
                            paragraphIndex = units.size,
                            lineNumber = nextLineNum++
                        ),
                        status = ContentUnitStatus.UNEXTRACTABLE
                    )
                )
            }
            StructuredDocxExtraction(
                rawOriginalText = units.joinToString("\n") { it.verbatimText },
                structuredText = legacyText,
                units = units,
                unextractableSegments = unextractable
            )
        }.also { recordExtraction(it) }
    }

    private fun detectUnclosedXmlSegments(xml: String): List<String> {
        val lastClosedP = xml.lastIndexOf("</w:p>")
        val lastClosedTbl = xml.lastIndexOf("</w:tbl>")
        val tailStart = maxOf(lastClosedP, lastClosedTbl)
        val tail = if (tailStart >= 0 && tailStart + 6 < xml.length) {
            xml.substring(tailStart + 6)
        } else if (tailStart == -1) {
            xml
        } else {
            ""
        }
        if (tail.isBlank()) return emptyList()
        val unclosedMatches = Regex("<w:t(?:\\s[^>]*)?>([^<]+)").findAll(tail)
            .map { decodeXmlEntities(it.groupValues[1]).trim() }
            .filter { it.isNotEmpty() }
            .toList()
        return unclosedMatches
    }

    internal fun buildPlainTextUnits(rawText: String): List<ExtractedUnit> {
        val units = mutableListOf<ExtractedUnit>()
        var nextId = 1
        val lines = rawText.lines()
        val isTabDelimited = lines.count { it.count { ch -> ch == '\t' } >= 2 } >= 2
        val isPipeDelimited = lines.count { line ->
            val tr = line.trim()
            !tr.startsWith("[جدول:") && tr.count { ch -> ch == '|' } >= 2
        } >= 2

        for ((lineIdx, line) in lines.withIndex()) {
            val trimmed = stripFormattingTags(line).trim()
            if (trimmed.isEmpty() || trimmed == "[نهاية الجدول]") continue
            if ((isTabDelimited && line.contains('\t')) || (isPipeDelimited && !trimmed.startsWith("[جدول:") && trimmed.count { it == '|' } >= 2)) {
                val cells = if (isTabDelimited && line.contains('\t')) {
                    line.split('\t').map { it.trim() }
                } else {
                    trimmed.removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
                }
                if (cells.all { col -> col.isEmpty() || col.all { ch -> ch == '-' || ch == ':' || ch == ' ' } }) {
                    continue
                }
                for ((colIdx, cellText) in cells.withIndex()) {
                    if (cellText.isNotEmpty()) {
                        units.add(
                            ExtractedUnit(
                                id = "unit_${nextId++}",
                                verbatimText = cellText,
                                elementType = DocumentElementType.TABLE_CELL,
                                location = UnitLocation(
                                    sourcePart = "plain_text",
                                    elementIndex = lineIdx,
                                    paragraphIndex = lineIdx,
                                    tableIndex = 0,
                                    rowIndex = lineIdx,
                                    colIndex = colIdx,
                                    lineNumber = lineIdx + 1
                                ),
                                status = ContentUnitStatus.EXTRACTED
                            )
                        )
                    }
                }
            } else {
                val innerText = if ((trimmed.startsWith("[مرحلة:") || trimmed.startsWith("[حصة:")) && trimmed.endsWith("]")) {
                    trimmed.substringAfter(":").removeSuffix("]").trim()
                } else {
                    trimmed
                }
                if (innerText.isNotEmpty()) {
                    units.add(
                        ExtractedUnit(
                            id = "unit_${nextId++}",
                            verbatimText = innerText,
                            elementType = DocumentElementType.PLAIN_LINE,
                            location = UnitLocation(
                                sourcePart = "plain_text",
                                elementIndex = lineIdx,
                                paragraphIndex = lineIdx,
                                lineNumber = lineIdx + 1
                            ),
                            status = ContentUnitStatus.EXTRACTED
                        )
                    )
                }
            }
        }
        return units
    }

    private enum class AlternateContentState {
        BEFORE_CHOICE,
        IN_CHOICE,
        AFTER_CHOICE
    }

    private class TableBuilder {
        val rows = mutableListOf<List<TableCell>>()
        val columnWidthsDxa = mutableListOf<Int>()
        val rowHeaderFlags = mutableListOf<Boolean>()
        var inTblPr: Boolean = false
        var inTblGrid: Boolean = false
        var isRtl: Boolean = true
        var hasExplicitBidiVisual: Boolean = false
        var hasBorders: Boolean = true
    }

    private class RowBuilder {
        val cells = mutableListOf<TableCell>()
        var gridBefore: Int = 0
        var inTrPr: Boolean = false
        var isHeader: Boolean = false
    }

    private class CellBuilder {
        val paragraphs = mutableListOf<Para>()
        val ownParagraphs = mutableListOf<Para>()
        val nestedTables = mutableListOf<Table>()
        var gridSpan: Int = 1
        var vMerge: String? = null
        var widthDxa: Int? = null
        var backgroundHex: String? = null
        var horizontalAlignment: String? = null
        var verticalAlignment: String? = null
        var inTcPr: Boolean = false
    }

    private class ParagraphBuilder {
        val parts = mutableListOf(StringBuilder())
        var inPPr: Boolean = false
        var jcAlignment: String? = null
    }

    private class RunBuilder {
        val segment = StringBuilder()
        var inRPr: Boolean = false
        var isUnderlined: Boolean = false
        var inT: Boolean = false

        fun flushTo(paraBuilder: ParagraphBuilder) {
            val runText = segment.toString()
            if (runText.isNotEmpty()) {
                val target = paraBuilder.parts.last()
                if (isUnderlined && runText.trim().endsWith(":")) {
                    target.append("${runText.trim()} ")
                } else {
                    target.append(runText)
                }
                segment.setLength(0)
            }
        }
    }

    private fun parseDocumentXmlWithPullParser(xml: String): String {
        val elements = buildDocumentElements(xml)
        val outputLines = mutableListOf<String>()

        for (element in elements) {
            when (element) {
                is DocElement.ParaItem -> {
                    val t = stripFormattingTags(element.para.text).trim()
                    if (t.isNotEmpty()) {
                        outputLines.add(t)
                    }
                }
                is DocElement.TableItem -> {
                    outputLines.addAll(parseTableXml(element.table).map { stripFormattingTags(it) })
                }
            }
        }

        return outputLines.joinToString("\n")
    }

    private fun buildDocumentElements(rawXml: String): List<DocElement> {
        val trimmedXml = rawXml.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (trimmedXml.isEmpty()) return emptyList()

        // If a partial test XML snippet is provided without root xmlns declarations, wrap it
        // so FEATURE_PROCESS_NAMESPACES can resolve w:, mc:, v:, wps: prefixes cleanly while still
        // failing with XmlPullParserException on any malformed/unclosed XML.
        val preparedXml = ensureNamespaceDeclarations(trimmedXml)

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(preparedXml))

        val topElements = mutableListOf<DocElement>()
        val tableStack = ArrayDeque<TableBuilder>()
        val rowStack = ArrayDeque<RowBuilder>()
        val cellStack = ArrayDeque<CellBuilder>()
        val pStack = ArrayDeque<ParagraphBuilder>()
        val rStack = ArrayDeque<RunBuilder>()
        val altStack = ArrayDeque<AlternateContentState>()

        var ignoredDepth = 0

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val localName = parser.name ?: ""

                    if (ignoredDepth > 0) {
                        ignoredDepth++
                    } else if (altStack.isNotEmpty() && altStack.last() != AlternateContentState.IN_CHOICE) {
                        if (localName == "Choice" && altStack.last() == AlternateContentState.BEFORE_CHOICE) {
                            altStack[altStack.lastIndex] = AlternateContentState.IN_CHOICE
                        } else {
                            // Inside AlternateContent but outside Choice (e.g., Fallback) -> ignore subtree
                            ignoredDepth = 1
                        }
                    } else if (localName == "AlternateContent") {
                        altStack.addLast(AlternateContentState.BEFORE_CHOICE)
                    } else if (localName == "del" || localName == "delText" || localName == "instrText") {
                        ignoredDepth = 1
                    } else {
                        when (localName) {
                            "tbl" -> {
                                tableStack.addLast(TableBuilder())
                            }
                            "tblPr" -> {
                                if (tableStack.isNotEmpty()) {
                                    tableStack.last().inTblPr = true
                                }
                            }
                            "bidiVisual" -> {
                                if (tableStack.isNotEmpty() && tableStack.last().inTblPr) {
                                    val bVal = getLocalAttributeValue(parser, "val")
                                    val enabled = bVal == null || (!bVal.equals("0", ignoreCase = true) && !bVal.equals("false", ignoreCase = true))
                                    tableStack.last().isRtl = enabled
                                    tableStack.last().hasExplicitBidiVisual = enabled
                                }
                            }
                            "tblGrid" -> {
                                if (tableStack.isNotEmpty()) {
                                    tableStack.last().inTblGrid = true
                                }
                            }
                            "gridCol" -> {
                                if (tableStack.isNotEmpty() && tableStack.last().inTblGrid) {
                                    val colW = getLocalAttributeValue(parser, "w")?.toIntOrNull()
                                    if (colW != null && colW > 0) {
                                        tableStack.last().columnWidthsDxa.add(colW)
                                    }
                                }
                            }
                            "tr" -> {
                                if (tableStack.isNotEmpty()) {
                                    rowStack.addLast(RowBuilder())
                                }
                            }
                            "trPr" -> {
                                if (rowStack.isNotEmpty()) {
                                    rowStack.last().inTrPr = true
                                }
                            }
                            "tblHeader" -> {
                                if (rowStack.isNotEmpty() && rowStack.last().inTrPr) {
                                    val hVal = getLocalAttributeValue(parser, "val")
                                    rowStack.last().isHeader = hVal == null || (!hVal.equals("0", ignoreCase = true) && !hVal.equals("false", ignoreCase = true))
                                }
                            }
                            "gridBefore" -> {
                                if (rowStack.isNotEmpty() && rowStack.last().inTrPr) {
                                    val beforeVal = getLocalAttributeValue(parser, "val")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                                    rowStack.last().gridBefore = beforeVal
                                }
                            }
                            "tc" -> {
                                if (rowStack.isNotEmpty()) {
                                    cellStack.addLast(CellBuilder())
                                }
                            }
                            "tcPr" -> {
                                if (cellStack.isNotEmpty()) {
                                    cellStack.last().inTcPr = true
                                }
                            }
                            "tcW" -> {
                                if (cellStack.isNotEmpty() && cellStack.last().inTcPr) {
                                    val wVal = getLocalAttributeValue(parser, "w")?.toIntOrNull()
                                    if (wVal != null && wVal > 0) {
                                        cellStack.last().widthDxa = wVal
                                    }
                                }
                            }
                            "gridSpan" -> {
                                if (cellStack.isNotEmpty() && cellStack.last().inTcPr) {
                                    val spanVal = getLocalAttributeValue(parser, "val")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                                    cellStack.last().gridSpan = spanVal
                                }
                            }
                            "vMerge" -> {
                                if (cellStack.isNotEmpty() && cellStack.last().inTcPr) {
                                    val vVal = getLocalAttributeValue(parser, "val").orEmpty()
                                    cellStack.last().vMerge = vVal.ifEmpty { "continue" }
                                }
                            }
                            "shd" -> {
                                if (cellStack.isNotEmpty() && cellStack.last().inTcPr) {
                                    val fillVal = getLocalAttributeValue(parser, "fill")
                                    if (!fillVal.isNullOrBlank() && !fillVal.equals("auto", ignoreCase = true)) {
                                        cellStack.last().backgroundHex = fillVal
                                    }
                                }
                            }
                            "vAlign" -> {
                                if (cellStack.isNotEmpty() && cellStack.last().inTcPr) {
                                    cellStack.last().verticalAlignment = getLocalAttributeValue(parser, "val")
                                }
                            }
                            "p" -> {
                                pStack.addLast(ParagraphBuilder())
                            }
                            "pPr" -> {
                                if (pStack.isNotEmpty()) {
                                    pStack.last().inPPr = true
                                }
                            }
                            "jc" -> {
                                if (pStack.isNotEmpty() && pStack.last().inPPr) {
                                    val jcVal = getLocalAttributeValue(parser, "val")
                                    if (!jcVal.isNullOrBlank()) {
                                        pStack.last().jcAlignment = jcVal
                                    }
                                }
                            }
                            "r" -> {
                                if (pStack.isNotEmpty()) {
                                    rStack.addLast(RunBuilder())
                                }
                            }
                            "rPr" -> {
                                if (rStack.isNotEmpty()) {
                                    rStack.last().inRPr = true
                                }
                            }
                            "u" -> {
                                if (rStack.isNotEmpty() && rStack.last().inRPr) {
                                    val uVal = getLocalAttributeValue(parser, "val")
                                    rStack.last().isUnderlined = uVal == null || !uVal.equals("none", ignoreCase = true)
                                }
                            }
                            "t" -> {
                                if (rStack.isNotEmpty()) {
                                    rStack.last().inT = true
                                }
                            }
                            "tab" -> {
                                if (rStack.isNotEmpty()) {
                                    rStack.last().segment.append(" ")
                                } else if (pStack.isNotEmpty()) {
                                    pStack.last().parts.last().append(" ")
                                }
                            }
                            "noBreakHyphen" -> {
                                if (rStack.isNotEmpty()) {
                                    rStack.last().segment.append("-")
                                } else if (pStack.isNotEmpty()) {
                                    pStack.last().parts.last().append("-")
                                }
                            }
                            "br", "cr" -> {
                                if (pStack.isNotEmpty()) {
                                    val currentPara = pStack.last()
                                    if (rStack.isNotEmpty()) {
                                        rStack.last().flushTo(currentPara)
                                    }
                                    currentPara.parts.add(StringBuilder())
                                }
                            }
                        }
                    }
                }

                XmlPullParser.TEXT -> {
                    if (ignoredDepth == 0 && (altStack.isEmpty() || altStack.last() == AlternateContentState.IN_CHOICE)) {
                        if (rStack.isNotEmpty() && rStack.last().inT) {
                            rStack.last().segment.append(parser.text.orEmpty())
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    val localName = parser.name ?: ""

                    if (ignoredDepth > 0) {
                        ignoredDepth--
                    } else if (localName == "Choice" && altStack.isNotEmpty() && altStack.last() == AlternateContentState.IN_CHOICE) {
                        altStack[altStack.lastIndex] = AlternateContentState.AFTER_CHOICE
                    } else if (localName == "AlternateContent" && altStack.isNotEmpty()) {
                        altStack.removeLast()
                    } else {
                        when (localName) {
                            "t" -> {
                                if (rStack.isNotEmpty()) {
                                    rStack.last().inT = false
                                }
                            }
                            "rPr" -> {
                                if (rStack.isNotEmpty()) {
                                    rStack.last().inRPr = false
                                }
                            }
                            "r" -> {
                                if (rStack.isNotEmpty()) {
                                    val finishedRun = rStack.removeLast()
                                    if (pStack.isNotEmpty()) {
                                        finishedRun.flushTo(pStack.last())
                                    }
                                }
                            }
                            "pPr" -> {
                                if (pStack.isNotEmpty()) {
                                    pStack.last().inPPr = false
                                }
                            }
                            "p" -> {
                                if (pStack.isNotEmpty()) {
                                    val finishedPara = pStack.removeLast()
                                    val cleanedParts = finishedPara.parts.map { normalizeParagraphPart(it.toString()) }
                                    val nonEmptyParts = cleanedParts.filter { it.isNotEmpty() }

                                    if (cellStack.isNotEmpty()) {
                                        val currentCell = cellStack.last()
                                        if (currentCell.horizontalAlignment == null && finishedPara.jcAlignment != null) {
                                            currentCell.horizontalAlignment = finishedPara.jcAlignment
                                        }
                                        if (nonEmptyParts.isEmpty()) {
                                            currentCell.paragraphs.add(Para(""))
                                            currentCell.ownParagraphs.add(Para(""))
                                        } else {
                                            for (part in nonEmptyParts) {
                                                currentCell.paragraphs.add(Para(part))
                                                currentCell.ownParagraphs.add(Para(part))
                                            }
                                        }
                                    } else {
                                        for (part in nonEmptyParts) {
                                            topElements.add(DocElement.ParaItem(Para(part)))
                                        }
                                    }
                                }
                            }
                            "tcPr" -> {
                                if (cellStack.isNotEmpty()) {
                                    cellStack.last().inTcPr = false
                                }
                            }
                            "tc" -> {
                                if (cellStack.isNotEmpty()) {
                                    val finishedCell = cellStack.removeLast()
                                    if (rowStack.isNotEmpty()) {
                                        rowStack.last().cells.add(
                                            TableCell(
                                                paragraphs = finishedCell.paragraphs.toList(),
                                                gridSpan = finishedCell.gridSpan,
                                                vMerge = finishedCell.vMerge,
                                                ownParagraphs = finishedCell.ownParagraphs.toList(),
                                                nestedTables = finishedCell.nestedTables.toList(),
                                                widthDxa = finishedCell.widthDxa,
                                                backgroundHex = finishedCell.backgroundHex,
                                                horizontalAlignment = finishedCell.horizontalAlignment,
                                                verticalAlignment = finishedCell.verticalAlignment
                                            )
                                        )
                                    }
                                }
                            }
                            "trPr" -> {
                                if (rowStack.isNotEmpty()) {
                                    rowStack.last().inTrPr = false
                                }
                            }
                            "tr" -> {
                                if (rowStack.isNotEmpty()) {
                                    val finishedRow = rowStack.removeLast()
                                    if (tableStack.isNotEmpty() && finishedRow.cells.isNotEmpty()) {
                                        val rowCells = if (finishedRow.gridBefore > 0) {
                                            val leadingEmpty = List(finishedRow.gridBefore) {
                                                TableCell(paragraphs = emptyList(), gridSpan = 1, vMerge = null)
                                            }
                                            leadingEmpty + finishedRow.cells
                                        } else {
                                            finishedRow.cells.toList()
                                        }
                                        tableStack.last().rows.add(rowCells)
                                        tableStack.last().rowHeaderFlags.add(finishedRow.isHeader)
                                    }
                                }
                            }
                            "tblPr" -> {
                                if (tableStack.isNotEmpty()) {
                                    tableStack.last().inTblPr = false
                                }
                            }
                            "tblGrid" -> {
                                if (tableStack.isNotEmpty()) {
                                    tableStack.last().inTblGrid = false
                                }
                            }
                            "tbl" -> {
                                if (tableStack.isNotEmpty()) {
                                    val finishedBuilder = tableStack.removeLast()
                                    val finishedTable = Table(
                                        rows = finishedBuilder.rows.toList(),
                                        columnWidthsDxa = finishedBuilder.columnWidthsDxa.toList(),
                                        isRtl = finishedBuilder.isRtl,
                                        hasExplicitBidiVisual = finishedBuilder.hasExplicitBidiVisual,
                                        hasBorders = finishedBuilder.hasBorders,
                                        rowHeaderFlags = finishedBuilder.rowHeaderFlags.toList()
                                    )
                                    if (cellStack.isNotEmpty()) {
                                        // Nested table inside a cell: store full nested Table in parentCell.nestedTables
                                        // AND keep legacy parentCell.paragraphs behavior for backward compatibility.
                                        val parentCell = cellStack.last()
                                        parentCell.nestedTables.add(finishedTable)
                                        val isAnalyticalSubTable = finishedTable.rows.size >= 2 &&
                                            finishedTable.rows.first().size >= 3
                                        if (isAnalyticalSubTable) {
                                            for (row in finishedTable.rows) {
                                                val rowCellsText = row.map { cell ->
                                                    cell.paragraphs.map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
                                                }.filter { it.isNotEmpty() }
                                                if (rowCellsText.isNotEmpty()) {
                                                    parentCell.paragraphs.add(Para("[جدول: ${rowCellsText.joinToString(" | ")}]"))
                                                }
                                            }
                                        } else {
                                            for (row in finishedTable.rows) {
                                                for (cell in row) {
                                                    for (para in cell.paragraphs) {
                                                        if (para.text.isNotEmpty()) {
                                                            parentCell.paragraphs.add(para)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        // Top-level table
                                        topElements.add(DocElement.TableItem(finishedTable))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        return topElements
    }

    private fun getLocalAttributeValue(parser: XmlPullParser, targetLocalName: String): String? {
        for (i in 0 until parser.attributeCount) {
            val attrName = parser.getAttributeName(i) ?: continue
            if (attrName == targetLocalName || attrName.endsWith(":$targetLocalName")) {
                return parser.getAttributeValue(i)
            }
        }
        return null
    }

    private fun normalizeParagraphPart(raw: String): String {
        return stripFormattingTags(raw)
            .replace("\u00a0", " ")
            .replace(Regex("[ \\t]+"), " ")
            .trim()
    }

    private fun ensureNamespaceDeclarations(xml: String): String {
        if (xml.contains("xmlns:w=") && xml.contains("xmlns:mc=")) {
            return xml
        }
        val bodyWithoutDecl = if (xml.startsWith("<?xml")) {
            val endDecl = xml.indexOf("?>")
            if (endDecl >= 0) xml.substring(endDecl + 2).trimStart() else xml
        } else {
            xml
        }
        // Only wrap if it looks like well-structured XML starting with '<'
        if (!bodyWithoutDecl.startsWith("<")) return xml
        return buildString(bodyWithoutDecl.length + 380) {
            append("<root ")
            append("xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" ")
            append("xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\" ")
            append("xmlns:wps=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\" ")
            append("xmlns:v=\"urn:schemas-microsoft-com:vml\" ")
            append("xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" ")
            append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
            append(bodyWithoutDecl)
            append("</root>")
        }
    }

    /**
     * Extracts non-blank paragraph strings from a [TableCell].
     */
    internal fun extractCellParagraphs(cell: TableCell): List<String> {
        return cell.paragraphs.map { stripFormattingTags(it.text).trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Groups a [TableCell]'s paragraphs into blocks separated by blank paragraphs while keeping
     * each paragraph (and each br/cr split) as a separate line.
     */
    private fun extractCellParagraphBlocks(cell: TableCell): List<List<String>> {
        val blocks = mutableListOf<MutableList<String>>()
        var afterBlank = true
        for (para in cell.paragraphs) {
            val t = stripFormattingTags(para.text).trim()
            if (t.isEmpty()) {
                afterBlank = true
                continue
            }
            if (afterBlank || blocks.isEmpty()) {
                blocks.add(mutableListOf(t))
            } else {
                blocks.last().add(t)
            }
            afterBlank = false
        }
        return blocks
    }

    /**
     * Parses a [Table] model into structured stage lines (`[مرحلة: ...]`, field headers, `[نهاية الجدول]`)
     * or header/metadata lines (`عنوان: عنصر - عنصر`), respecting `<w:gridSpan>` and `<w:vMerge>`
     * so column indices never shift in rows containing merged cells.
     */
    internal fun parseTableXml(table: Table): List<String> {
        return parseTableXmlWithMetadata(table).lines
    }

    private fun parseTableXmlWithMetadata(table: Table): TableParseResult {
        val result = mutableListOf<String>()
        if (table.rows.isEmpty()) return TableParseResult(result)

        // Build 2D grid supporting gridSpan and vMerge without column index drift
        val grid = mutableListOf<List<CellNode?>>()
        var nextCellId = 1

        for (tableRow in table.rows) {
            val row = mutableListOf<CellNode?>()
            val prevRow = grid.lastOrNull()
            val explicitRowCols = tableRow.sumOf { it.gridSpan.coerceAtLeast(1) }

            for (tc in tableRow) {
                val pBlocks = extractCellParagraphBlocks(tc)
                val flatParas = extractCellParagraphs(tc)
                val structuredNested = tc.nestedTables.mapIndexed { idx, nested ->
                    nested.toStructuredTable(id = "cell_nested_${nextCellId}_$idx")
                }
                val node = CellNode(
                    id = nextCellId++,
                    rawXml = "",
                    paragraphBlocks = pBlocks,
                    flatParagraphs = flatParas,
                    contentLinesWithTables = flatParas,
                    nestedTables = structuredNested
                )

                var span = tc.gridSpan.coerceAtLeast(1)
                if (tc.vMerge == "continue" && span == 1 && prevRow != null && explicitRowCols < prevRow.size && row.size < prevRow.size) {
                    val aboveCell = prevRow[row.size]
                    if (aboveCell != null) {
                        var aboveSpan = 0
                        var c = row.size
                        while (c < prevRow.size && prevRow[c]?.id == aboveCell.id) {
                            aboveSpan++
                            c++
                        }
                        if (aboveSpan > 1) {
                            span = aboveSpan
                        }
                    }
                }

                for (s in 0 until span) {
                    val colIdx = row.size
                    if (tc.vMerge == "continue" && prevRow != null && colIdx < prevRow.size) {
                        val continued = prevRow[colIdx]
                        if (continued != null && flatParas.isEmpty()) {
                            row.add(continued)
                        } else {
                            row.add(node)
                        }
                    } else {
                        row.add(node)
                    }
                }
            }
            if (row.isNotEmpty()) {
                grid.add(row)
            }
        }

        return formatTableGridToLinesWithMetadata(grid, result)
    }

    /**
     * Legacy fallback parser using regex/depth-balanced XML slicing.
     * Kept intact for safety when XmlPullParser encounters corrupted XML.
     */
    internal fun parseDocumentXmlLegacy(xml: String): String {
        val outputLines = mutableListOf<String>()
        val topBlocks = extractTopLevelBlocks(xml)

        for ((isTable, blockXml) in topBlocks) {
            if (isTable) {
                outputLines.addAll(parseTableXmlLegacy(blockXml).map { stripFormattingTags(it) })
            } else {
                val pText = stripFormattingTags(extractParagraphText(blockXml))
                if (pText.isNotEmpty()) {
                    pText.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                        outputLines.add(it)
                    }
                }
            }
        }

        return outputLines.joinToString("\n")
    }

    /**
     * Scans XML for top-level <w:tbl>...</w:tbl> and <w:p>...</w:p> blocks using tag depth counting.
     */
    private fun extractTopLevelBlocks(xml: String): List<Pair<Boolean, String>> {
        val blocks = mutableListOf<Pair<Boolean, String>>()
        val tagRegex = Regex("<(/?)w:(tbl|p)(?:\\s[^>]*)?(/?)>")
        var tblDepth = 0
        var pDepth = 0
        var currentStart = -1
        var currentIsTable = false

        for (m in tagRegex.findAll(xml)) {
            val isClosing = m.groupValues[1] == "/"
            val tagName = m.groupValues[2]
            val isSelfClosing = m.groupValues[3] == "/"

            if (tagName == "tbl") {
                if (!isClosing && !isSelfClosing) {
                    if (tblDepth == 0 && pDepth == 0) {
                        currentStart = m.range.first
                        currentIsTable = true
                    }
                    tblDepth++
                } else if (isClosing) {
                    tblDepth = (tblDepth - 1).coerceAtLeast(0)
                    if (tblDepth == 0 && currentIsTable && currentStart >= 0) {
                        blocks.add(true to xml.substring(currentStart, m.range.last + 1))
                        currentStart = -1
                    }
                }
            } else if (tagName == "p" && tblDepth == 0) {
                if (!isClosing && !isSelfClosing) {
                    if (pDepth == 0) {
                        currentStart = m.range.first
                        currentIsTable = false
                    }
                    pDepth++
                } else if (isClosing) {
                    pDepth = (pDepth - 1).coerceAtLeast(0)
                    if (pDepth == 0 && !currentIsTable && currentStart >= 0) {
                        blocks.add(false to xml.substring(currentStart, m.range.last + 1))
                        currentStart = -1
                    }
                }
            }
        }
        return blocks
    }

    /**
     * Extracts direct child elements of a given tag (e.g. "tr" inside outer "tbl", or "tc" inside "tr")
     * while ignoring any occurrences inside nested <w:tbl> elements.
     */
    private fun extractDirectChildrenIgnoreNestedTables(
        containerXml: String,
        childTag: String,
        stripOuterTableTag: Boolean = false
    ): List<String> {
        val innerXml = if (stripOuterTableTag) {
            val firstClose = containerXml.indexOf('>')
            val lastOpen = containerXml.lastIndexOf("</w:tbl>")
            if (firstClose >= 0 && lastOpen > firstClose) {
                containerXml.substring(firstClose + 1, lastOpen)
            } else {
                containerXml
            }
        } else {
            containerXml
        }

        val results = mutableListOf<String>()
        val tagRegex = Regex("<(/?)w:(tbl|$childTag)(?:\\s[^>]*)?(/?)>")
        var nestedTblDepth = 0
        var childDepth = 0
        var childStart = -1

        for (m in tagRegex.findAll(innerXml)) {
            val isClosing = m.groupValues[1] == "/"
            val tagName = m.groupValues[2]
            val isSelfClosing = m.groupValues[3] == "/"

            if (tagName == "tbl") {
                if (!isClosing && !isSelfClosing) {
                    nestedTblDepth++
                } else if (isClosing) {
                    nestedTblDepth = (nestedTblDepth - 1).coerceAtLeast(0)
                }
            } else if (tagName == childTag && nestedTblDepth == 0) {
                if (!isClosing && !isSelfClosing) {
                    if (childDepth == 0) {
                        childStart = m.range.first
                    }
                    childDepth++
                } else if (isClosing) {
                    childDepth = (childDepth - 1).coerceAtLeast(0)
                    if (childDepth == 0 && childStart >= 0) {
                        results.add(innerXml.substring(childStart, m.range.last + 1))
                        childStart = -1
                    }
                }
            }
        }
        return results
    }

    private fun parseTableXmlLegacy(tblXml: String): List<String> {
        val result = mutableListOf<String>()
        val trList = extractDirectChildrenIgnoreNestedTables(tblXml, "tr", stripOuterTableTag = true)
        if (trList.isEmpty()) return result

        // Build 2D grid supporting gridSpan and vMerge
        val grid = mutableListOf<List<CellNode?>>()
        var nextCellId = 1

        for (trXml in trList) {
            val row = mutableListOf<CellNode?>()
            val prevRow = grid.lastOrNull()
            val tcList = extractDirectChildrenIgnoreNestedTables(trXml, "tc", stripOuterTableTag = false)
            val spans = tcList.map { extractGridSpan(extractFirstTagBlock(it, "tcPr")) }
            val explicitRowCols = spans.sum()

            for ((tcIdx, tcXml) in tcList.withIndex()) {
                val tcPrXml = extractFirstTagBlock(tcXml, "tcPr")
                var span = spans[tcIdx]
                val vMerge = extractVMerge(tcPrXml)

                val pBlocks = extractCellParagraphBlocksLegacy(tcXml)
                val flatParas = pBlocks.flatten()
                val node = CellNode(
                    id = nextCellId++,
                    rawXml = tcXml,
                    paragraphBlocks = pBlocks,
                    flatParagraphs = flatParas,
                    contentLinesWithTables = flatParas
                )

                if (vMerge == "continue" && span == 1 && prevRow != null && explicitRowCols < prevRow.size && row.size < prevRow.size) {
                    val aboveCell = prevRow[row.size]
                    if (aboveCell != null) {
                        var aboveSpan = 0
                        var c = row.size
                        while (c < prevRow.size && prevRow[c]?.id == aboveCell.id) {
                            aboveSpan++
                            c++
                        }
                        if (aboveSpan > 1) span = aboveSpan
                    }
                }

                for (s in 0 until span) {
                    val colIdx = row.size
                    if (vMerge == "continue" && prevRow != null && colIdx < prevRow.size) {
                        val continued = prevRow[colIdx]
                        if (continued != null && flatParas.isEmpty()) {
                            row.add(continued)
                        } else {
                            row.add(node)
                        }
                    } else {
                        row.add(node)
                    }
                }
            }
            if (row.isNotEmpty()) {
                grid.add(row)
            }
        }

        return formatTableGridToLines(grid, result)
    }

    private fun isPreLessonHeaderGrid(grid: List<List<CellNode?>>): Boolean {
        val firstRowsText = grid.take(2)
            .flatten()
            .filterNotNull()
            .distinctBy { it.id }
            .joinToString(" ") { it.flatParagraphs.joinToString(" ") }
        val normFirst = normalizeArabicForMatch(firstRowsText)

        val hasExplicitStageCol = normFirst.contains("المراحل") ||
            normFirst.contains("الخطوات") ||
            normFirst.contains("خطواتالقراءه") ||
            normFirst.contains("المقاطع") ||
            normFirst.contains("مقاطعالدرس") ||
            normFirst.contains("انشطهالمدرس") ||
            normFirst.contains("انشطهالاستاذ") ||
            normFirst.contains("انشطهالمتعلم") ||
            normFirst.contains("الانشطهالتعليميه")

        if (hasExplicitStageCol) return false

        val allTableText = grid.flatten()
            .filterNotNull()
            .distinctBy { it.id }
            .joinToString(" ") { it.flatParagraphs.joinToString(" ") }
        val normAll = normalizeArabicForMatch(allTableText)

        val headerHits = listOf(
            "جذاذهتربويه",
            "الفئهالمستهدفه",
            "المكون",
            "الموضوع",
            "الوسائلالديدكتيكيه",
            "الوسائلالديداكتيكيه",
            "الوسائلالتعليميه",
            "الكفايهالتواصليه",
            "الكفايهالمنهجيه",
            "الكفايهالثقافيه",
            "كفايهتواصليه",
            "كفايهمنهجيه",
            "كفايهمعرفيه",
            "المرجع",
            "الاستاذ",
            "المؤسسه"
        ).count { normAll.contains(it) }

        return headerHits >= 2
    }

    private fun isGenericSegmentLabel(text: String): Boolean {
        val norm = normalizeArabicForMatch(text)
        return norm in setOf(
            "المقطعالتمهيدي",
            "المقطعالوسيطي",
            "المقطعالنهائي",
            "المقطعالاستهلالي",
            "المقطعالختامي",
            "مقاطعالدرس",
            "المقاطع",
            "المراحل"
        )
    }

    private fun isRepeatedTableHeaderRow(distinctCells: List<CellNode>): Boolean {
        if (distinctCells.size < 3) return false
        val normCells = distinctCells.map { normalizeArabicForMatch(it.flatParagraphs.joinToString(" ")) }
        val headerMatches = normCells.count { n ->
            n == "المراحل" || n == "المقاطع" || n == "مقاطعالدرس" || n == "الخطوات" ||
                n.contains("القدراتوالمهارات") || n == "القدرات" || n == "المهارات" ||
                n.contains("الانشطهالتعليميه") || n.contains("انشطهالمدرس") || n.contains("انشطهالمتعلم") ||
                n == "المحتوي" || n.contains("المحتويالديداكتيكي") ||
                n.contains("التقويموالدعم") || n.contains("تقويمودعم") || n == "التقويم"
        }
        return headerMatches >= 3
    }

    private fun formatTableGridToLines(
        grid: List<List<CellNode?>>,
        result: MutableList<String>
    ): List<String> {
        return formatTableGridToLinesWithMetadata(grid, result).lines
    }

    private fun formatTableGridToLinesWithMetadata(
        grid: List<List<CellNode?>>,
        result: MutableList<String>
    ): TableParseResult {
        if (grid.isEmpty()) return TableParseResult(result)

        // Isolate header/metadata tables that precede the lesson table
        if (isPreLessonHeaderGrid(grid)) {
            return TableParseResult(
                lines = formatHeaderGridToLines(grid, result, isPreLessonHeader = true),
                isPreLessonHeader = true,
                isLessonStagesTable = false
            )
        }

        // Detect header rows (1..3, inspecting row 1 and row 2 together for split headers or session banner row 0)
        var bestHeaderRows = -1
        var bestMultiColMap = mapOf<TableColType, List<Int>>()
        var bestScore = -1

        for (hRows in listOf(1, 2, 3)) {
            if (hRows >= grid.size) continue
            if (hRows > 1) {
                val prevRowCells = grid[hRows - 2].filterNotNull()
                val prevRowHasSpan = prevRowCells.size > prevRowCells.distinctBy { it.id }.size
                val currRowDistinct = grid[hRows - 1].filterNotNull().distinctBy { it.id }
                val sharesVerticalMerge = currRowDistinct.any { cell -> prevRowCells.any { it.id == cell.id } }
                val currRowHasSubHeaders = currRowDistinct.any { cell ->
                    val norm = normalizeArabicForMatch(cell.flatParagraphs.joinToString(" "))
                    norm.contains("انشطهالمدرس") || norm.contains("انشطهالاستاذ") ||
                        norm.contains("انشطهالمتعلم") || norm.contains("مهامالمدرس") ||
                        norm.contains("مهامالمتعلم") || norm.contains("مؤشراتالتقويم") ||
                        norm.contains("القدراتوالمهارات") || norm.contains("الانشطهالتعليميه")
                }
                if (currRowDistinct.size <= 1 || (!prevRowHasSpan && !sharesVerticalMerge && !currRowHasSubHeaders)) {
                    break
                }
            }
            val candidateMultiMap = detectMultiColumnsFromGrid(grid, hRows)
            val hasStageOrAbil = candidateMultiMap.containsKey(TableColType.STAGE) ||
                candidateMultiMap.containsKey(TableColType.SEGMENT) ||
                candidateMultiMap.containsKey(TableColType.ABIL)
            val hasContentOrAct = candidateMultiMap.containsKey(TableColType.CONTENT) ||
                candidateMultiMap.containsKey(TableColType.TEACHER) ||
                candidateMultiMap.containsKey(TableColType.LEARNER) ||
                candidateMultiMap.containsKey(TableColType.ACTIVITIES) ||
                candidateMultiMap.containsKey(TableColType.EVAL)

            val totalMappedCols = candidateMultiMap.values.sumOf { it.size }
            if (hasStageOrAbil && hasContentOrAct && candidateMultiMap.size >= 2) {
                val score = candidateMultiMap.size * 10 + totalMappedCols
                if (score > bestScore) {
                    bestScore = score
                    bestHeaderRows = hRows
                    bestMultiColMap = candidateMultiMap
                }
            }
        }

        val stageNestedMap = LinkedHashMap<String, MutableList<StructuredTable>>()

        if (bestHeaderRows != -1) {
            val seenIds = mutableSetOf<Int>()
            var lastSegmentStageText = ""
            var lastMergedStageCellId = -1
            var lastMergedStageText = ""

            // If row 0 before the header row was a single-cell session banner or title row, emit it without losing text
            for (hIdx in 0 until bestHeaderRows) {
                val hDistinct = grid[hIdx].filterNotNull().distinctBy { it.id }
                if (hDistinct.size == 1) {
                    val bannerText = hDistinct[0].flatParagraphs.joinToString(" ").trim()
                    if (bannerText.contains("الحصة") || bannerText.contains("أنشطة")) {
                        result.add("[حصة: $bannerText]")
                    } else if (bannerText.isNotEmpty()) {
                        hDistinct[0].flatParagraphs.filter { it.isNotBlank() }.forEach { result.add(it) }
                    }
                }
            }

            for (rIdx in bestHeaderRows until grid.size) {
                val row = grid[rIdx]
                val distinctCells = row.filterNotNull().distinctBy { it.id }

                // Skip repeated table header rows (e.g. after Session 2 banner in Reading)
                if (isRepeatedTableHeaderRow(distinctCells)) {
                    distinctCells.forEach { seenIds.add(it.id) }
                    continue
                }

                // Check if this is a single-cell spanned row (e.g., Session Banner or spanned note row)
                if (distinctCells.size == 1) {
                    val bannerNode = distinctCells[0]
                    seenIds.add(bannerNode.id)
                    val bannerText = bannerNode.flatParagraphs.joinToString(" ").trim()
                    if (bannerText.isNotEmpty()) {
                        if (bannerText.contains("أنشطة الاكتساب") ||
                            bannerText.contains("أنشطة التطبيق") ||
                            bannerText.contains("أنشطة الإنتاج") ||
                            bannerText.contains("أنشطة التصحيح") ||
                            bannerText.contains("حصة الاكتساب") ||
                            bannerText.contains("حصة التطبيق") ||
                            bannerText.contains("حصة الإنتاج") ||
                            bannerText.contains("حصة التصحيح") ||
                            bannerText.contains("الحصة الأولى") ||
                            bannerText.contains("الحصة الثانية") ||
                            bannerText.contains("الحصة الثالثة") ||
                            bannerText.contains("الحصة الرابعة")
                        ) {
                            result.add("[حصة: $bannerText]")
                        } else {
                            // Never drop non-banner single-cell rows in a table
                            bannerNode.contentLinesWithTables.filter { it.isNotBlank() }.forEach { result.add(it) }
                        }
                    }
                    continue
                }

                fun takeCells(type: TableColType): List<CellNode> {
                    val colIndices = bestMultiColMap[type].orEmpty()
                    val taken = mutableListOf<CellNode>()
                    for (colIdx in colIndices) {
                        val cell = row.getOrNull(colIdx) ?: continue
                        if (cell.id !in seenIds) {
                            seenIds.add(cell.id)
                            taken.add(cell)
                        }
                    }
                    return taken
                }

                // Track vertically merged stage cell across rows
                val primaryStageColIdx = bestMultiColMap[TableColType.STAGE]?.firstOrNull()
                val rawStageCellInRow = primaryStageColIdx?.let { row.getOrNull(it) }

                val stageCells = takeCells(TableColType.STAGE)
                val segmentCells = takeCells(TableColType.SEGMENT)
                // Always consume and discard TIME column cells
                takeCells(TableColType.TIME)
                val abilCells = takeCells(TableColType.ABIL)
                val teacherCells = takeCells(TableColType.TEACHER)
                val learnerCells = takeCells(TableColType.LEARNER)
                val genericActCells = takeCells(TableColType.ACTIVITIES)
                val contentCells = takeCells(TableColType.CONTENT)
                val evalCells = takeCells(TableColType.EVAL)

                // Also collect any unmapped non-empty cells (excluding generic segments and durations)
                val mappedIndices = bestMultiColMap.values.flatten().toSet()
                val extraContentLines = mutableListOf<String>()
                val extraTakenCells = mutableListOf<CellNode>()
                for (cIdx in row.indices) {
                    if (cIdx !in mappedIndices) {
                        val extraCell = row[cIdx]
                        if (extraCell != null && extraCell.id !in seenIds) {
                            seenIds.add(extraCell.id)
                            extraTakenCells.add(extraCell)
                            extraContentLines.addAll(
                                extraCell.contentLinesWithTables.filter { !isGenericSegmentLabel(it) }
                            )
                        }
                    }
                }

                val rawStageLines = stageCells.flatMap { it.flatParagraphs }.filter { !isGenericSegmentLabel(it) }
                val rawSegmentLines = segmentCells.flatMap { it.flatParagraphs }.filter { !isGenericSegmentLabel(it) }
                if (rawStageLines.isNotEmpty() && rawStageCellInRow != null) {
                    lastMergedStageCellId = rawStageCellInRow.id
                    lastMergedStageText = rawStageLines.joinToString(" - ")
                }
                if (rawSegmentLines.isNotEmpty()) {
                    lastSegmentStageText = rawSegmentLines.joinToString(" ")
                }
                val combinedStageLines = when {
                    rawStageLines.isNotEmpty() -> rawStageLines
                    rawStageCellInRow != null && rawStageCellInRow.id == lastMergedStageCellId && lastMergedStageText.isNotBlank() ->
                        listOf(lastMergedStageText)
                    rawSegmentLines.isNotEmpty() -> rawSegmentLines
                    lastSegmentStageText.isNotBlank() -> listOf(lastSegmentStageText)
                    else -> emptyList()
                }

                val abilBlocks = abilCells.flatMap { it.paragraphBlocks }
                val teacherBlocks = teacherCells.flatMap { it.paragraphBlocks }
                val learnerBlocks = learnerCells.flatMap { it.paragraphBlocks }
                val genericBlocks = genericActCells.flatMap { it.paragraphBlocks }
                // Merge multiple evaluation/indicator columns together into evalBlocks
                val evalBlocks = evalCells.flatMap { it.paragraphBlocks }
                val contentLines = (contentCells.flatMap { it.contentLinesWithTables } + extraContentLines).filter { it.isNotBlank() }

                val rowNestedTables = (
                    contentCells + teacherCells + learnerCells + genericActCells + abilCells + evalCells + extraTakenCells
                    ).flatMap { it.nestedTables }

                val hasAnyData = combinedStageLines.isNotEmpty() ||
                    abilBlocks.isNotEmpty() ||
                    teacherBlocks.isNotEmpty() ||
                    learnerBlocks.isNotEmpty() ||
                    genericBlocks.isNotEmpty() ||
                    evalBlocks.isNotEmpty() ||
                    contentLines.isNotEmpty() ||
                    rowNestedTables.isNotEmpty()
                if (!hasAnyData) continue

                // Check if this row combines two stages (e.g., "التقويم التشخيصي" + "ملاحظة الأمثلة ووصفها" in المختار)
                if (isCombinedTwoStageRow(combinedStageLines)) {
                    val firstStageName = combinedStageLines.first()
                    val secondStageName = combinedStageLines.drop(1).joinToString(" - ")

                    val (aAbil, bAbil) = splitBlocksTwoWays(abilBlocks)
                    val (aTea, bTea) = splitBlocksTwoWays(teacherBlocks)
                    val (aLea, bLea) = splitBlocksTwoWays(learnerBlocks)
                    val (aGen, bGen) = splitBlocksTwoWays(genericBlocks)
                    val (aEval, bEval) = splitBlocksTwoWays(evalBlocks)
                    val (aCont, bCont) = splitContentLinesTwoWays(contentLines, secondStageName)

                    if (rowNestedTables.isNotEmpty()) {
                        stageNestedMap.getOrPut(secondStageName) { mutableListOf() }.addAll(rowNestedTables)
                    }

                    emitStageRow(
                        result = result,
                        stageTitle = firstStageName,
                        abilLines = aAbil,
                        teacherLines = aTea,
                        learnerLines = aLea,
                        genericActLines = aGen,
                        contentLines = aCont,
                        evalLines = aEval
                    )
                    emitStageRow(
                        result = result,
                        stageTitle = secondStageName,
                        abilLines = bAbil,
                        teacherLines = bTea,
                        learnerLines = bLea,
                        genericActLines = bGen,
                        contentLines = bCont,
                        evalLines = bEval
                    )
                } else {
                    val stageTitle = combinedStageLines.joinToString(" - ").trim()
                    if (rowNestedTables.isNotEmpty() && stageTitle.isNotEmpty()) {
                        stageNestedMap.getOrPut(stageTitle) { mutableListOf() }.addAll(rowNestedTables)
                    }
                    emitStageRow(
                        result = result,
                        stageTitle = stageTitle,
                        abilLines = abilBlocks.flatten(),
                        teacherLines = teacherBlocks.flatten(),
                        learnerLines = learnerBlocks.flatten(),
                        genericActLines = genericBlocks.flatten(),
                        contentLines = contentLines,
                        evalLines = evalBlocks.flatten()
                    )
                }
            }
            result.add("[نهاية الجدول]")
            return TableParseResult(
                lines = result,
                isPreLessonHeader = false,
                isLessonStagesTable = true,
                stageNestedTables = stageNestedMap
            )
        } else if (isTwoColumnLessonTable(grid)) {
            val lines = formatTwoColumnLessonGridToLines(grid, result, stageNestedMap)
            return TableParseResult(
                lines = lines,
                isPreLessonHeader = false,
                isLessonStagesTable = true,
                stageNestedTables = stageNestedMap
            )
        } else {
            return TableParseResult(
                lines = formatHeaderGridToLines(grid, result, isPreLessonHeader = false),
                isPreLessonHeader = false,
                isLessonStagesTable = false
            )
        }
    }

    private fun isTwoColumnLessonTable(grid: List<List<CellNode?>>): Boolean {
        if (grid.isEmpty()) return false
        val allText = normalizeArabicForMatch(
            grid.flatten().filterNotNull().distinctBy { it.id }.joinToString(" ") { it.flatParagraphs.joinToString(" ") }
        )
        val stageKeywords = listOf(
            "تقويمتشخيصي", "تشخيصالمكتسبات", "تشخيصاخطاء", "التصحيحالفردي", "الدعموالتعزيز",
            "ملاحظهالنص", "فهمالنص", "القراءهالتحليليه", "التركيب", "الاستثمار", "الاستنتاج", "تطبيق",
            "ملاحظهالظاهره", "الوصفوالتحليل"
        )
        return stageKeywords.count { allText.contains(it) } >= 2
    }

    private fun formatTwoColumnLessonGridToLines(
        grid: List<List<CellNode?>>,
        result: MutableList<String>,
        stageNestedMap: MutableMap<String, MutableList<StructuredTable>> = mutableMapOf()
    ): List<String> {
        val seenCells = mutableSetOf<Int>()
        for (row in grid) {
            val distinctCells = row.filterNotNull().filter { seenCells.add(it.id) }
            if (distinctCells.isEmpty()) continue
            if (distinctCells.size == 1) {
                val text = distinctCells[0].flatParagraphs.joinToString(" ").trim()
                if (text.isNotEmpty()) result.add(text)
            } else {
                val firstColText = distinctCells[0].flatParagraphs.joinToString(" - ").trim()
                val restCells = distinctCells.drop(1)
                val restLines = restCells.flatMap { it.contentLinesWithTables }.filter { it.isNotBlank() }
                val nested = restCells.flatMap { it.nestedTables }
                if (firstColText.isNotEmpty() && firstColText.length <= 65) {
                    result.add("[مرحلة: $firstColText]")
                    restLines.forEach { result.add(it) }
                    if (nested.isNotEmpty()) {
                        stageNestedMap.getOrPut(firstColText) { mutableListOf() }.addAll(nested)
                    }
                } else {
                    distinctCells.flatMap { it.contentLinesWithTables }.filter { it.isNotBlank() }.forEach { result.add(it) }
                }
            }
        }
        result.add("[نهاية الجدول]")
        return result
    }

    private fun formatHeaderGridToLines(
        grid: List<List<CellNode?>>,
        result: MutableList<String>,
        isPreLessonHeader: Boolean = true
    ): List<String> {
        val seenHeaderCells = mutableSetOf<Int>()
        for (row in grid) {
            val distinctRowCells = row.filterNotNull().filter { seenHeaderCells.add(it.id) }.map { it.flatParagraphs }
            var cIdx = 0
            while (cIdx < distinctRowCells.size) {
                val cellParas = distinctRowCells[cIdx].filter { it.isNotBlank() }
                val rawFirst = cellParas.firstOrNull().orEmpty().trim()
                val strippedFirst = rawFirst.trimEnd(':', ' ').trim()
                val hasInternalColonValue = rawFirst.contains(":") && !rawFirst.trimEnd().endsWith(":")

                if (cellParas.size == 1 && !hasInternalColonValue && strippedFirst.length <= 35 && cIdx + 1 < distinctRowCells.size) {
                    val nextParas = distinctRowCells[cIdx + 1].filter { it.isNotBlank() }
                    val nextFirst = nextParas.firstOrNull().orEmpty().trim()
                    val nextStripped = nextFirst.trimEnd(':', ' ').trim()
                    val nextHasInternalColonValue = nextFirst.contains(":") && !nextFirst.trimEnd().endsWith(":")

                    if (nextParas.isNotEmpty() && !nextHasInternalColonValue) {
                        val firstIsLabel = isKnownHeaderFieldLabel(strippedFirst)
                        val secondIsLabel = nextParas.size == 1 && isKnownHeaderFieldLabel(nextStripped)
                        if (secondIsLabel && !firstIsLabel) {
                            result.add("$nextStripped: ${cellParas.joinToString(" - ")}")
                            cIdx += 2
                            continue
                        } else if (firstIsLabel || (isPreLessonHeader && !rawFirst.endsWith(":"))) {
                            val nextNorm = normalizeArabicForMatch(nextStripped)
                            if (nextNorm != "الكفاياتالمستهدفه" && nextNorm != "الاهدافالتعلميه") {
                                result.add("$strippedFirst: ${nextParas.joinToString(" - ")}")
                                cIdx += 2
                                continue
                            }
                        }
                    }
                }
                for (p in cellParas) {
                    val pNorm = normalizeArabicForMatch(p)
                    if (isPreLessonHeader && (pNorm == "الكفاياتالمستهدفه" || pNorm == "الاهدافالتعلميه")) continue
                    result.add(p)
                }
                cIdx++
            }
        }
        if (!isPreLessonHeader) {
            result.add("[نهاية الجدول]")
        }
        return result
    }

    private fun isKnownHeaderFieldLabel(text: String): Boolean {
        val norm = normalizeArabicForMatch(text)
        return norm in setOf(
            "جذاذهتربويه", "المستوي", "المستويالدراسي", "الفئهالمستهدفه", "الوحده", "الدوره", "المجال",
            "المكون", "الموضوع", "موضوعالدرس", "عنوانالدرس", "المهارهالمستهدفه",
            "مدهالانجاز", "الحصيصالزمني", "المده", "الغلافالزمني", "المدهالزمنيه",
            "الوسائلالتعليميه", "الوسائلالديداكتيكيه", "الوسائلالديدكتيكيه", "المرجع",
            "الاستاذ", "المؤسسه", "الكفاياتالمستهدفه", "الاهدافالتعلميه", "السنهالدراسيه",
            "القدرهالخاصهبالدرس", "الكفايهالتواصليه", "الكفايهالمنهجيه", "الكفايهالثقافيه", "الهدف"
        )
    }

    private fun emitStageRow(
        result: MutableList<String>,
        stageTitle: String,
        abilLines: List<String>,
        teacherLines: List<String>,
        learnerLines: List<String>,
        genericActLines: List<String>,
        contentLines: List<String>,
        evalLines: List<String>
    ) {
        if (stageTitle.isNotBlank()) {
            result.add("[مرحلة: $stageTitle]")
        }
        if (abilLines.isNotEmpty()) {
            result.add("القدرات المستهدفة:")
            abilLines.forEach { result.add(it) }
        }
        if (teacherLines.isNotEmpty()) {
            result.add("أنشطة المدرس:")
            teacherLines.forEach { result.add(it) }
        }
        if (genericActLines.isNotEmpty()) {
            if (teacherLines.isEmpty() && contentLines.isNotEmpty()) {
                result.add("أنشطة المدرس:")
                genericActLines.forEach { result.add(it) }
            } else {
                result.add("الأنشطة التعليمية التعلمية:")
                genericActLines.forEach { result.add(it) }
            }
        }
        if (contentLines.isNotEmpty()) {
            result.add("المحتوى الديداكتيكي:")
            contentLines.forEach { result.add(it) }
        }
        if (learnerLines.isNotEmpty()) {
            if (contentLines.isEmpty()) {
                result.add("المحتوى الديداكتيكي:")
            } else {
                result.add("أنشطة المتعلم:")
            }
            learnerLines.forEach { result.add(it) }
        }
        if (evalLines.isNotEmpty()) {
            result.add("التقويم والدعم:")
            evalLines.forEach { result.add(it) }
        }
    }

    private fun isCombinedTwoStageRow(stageLines: List<String>): Boolean {
        if (stageLines.size < 2) return false
        val firstNorm = normalizeArabicForMatch(stageLines[0])
        val restNorm = stageLines.drop(1).map { normalizeArabicForMatch(it) }
        // Case 1: Diagnostic + Observation in same row
        if ((firstNorm.contains("تشخيص") || firstNorm.contains("تمهيد")) &&
            restNorm.any { it.contains("ملاحظه") || it.contains("امثله") || it.contains("ظاهره") || it.contains("تاطير") }
        ) {
            return true
        }
        // Case 2: Observation + Analysis in same row
        if ((firstNorm.contains("ملاحظه") || firstNorm.contains("امثله")) &&
            restNorm.any { it.contains("تحليل") || it.contains("وصف") }
        ) {
            return true
        }
        return false
    }

    private fun splitBlocksTwoWays(blocks: List<List<String>>): Pair<List<String>, List<String>> {
        if (blocks.isEmpty()) return emptyList<String>() to emptyList()
        if (blocks.size >= 2) {
            return blocks.first() to blocks.drop(1).flatten()
        }
        val single = blocks.first()
        if (single.size >= 2) {
            return listOf(single.first()) to single.drop(1)
        }
        return single to emptyList()
    }

    private fun splitContentLinesTwoWays(lines: List<String>, secondStageName: String): Pair<List<String>, List<String>> {
        if (lines.isEmpty()) return emptyList<String>() to emptyList()
        val secondNorm = normalizeArabicForMatch(secondStageName)
        val splitIdx = lines.indexOfFirst { ln ->
            val n = normalizeArabicForMatch(ln)
            ln.startsWith("[جدول:") ||
                (secondNorm.isNotEmpty() && n.contains(secondNorm.take(8))) ||
                n.startsWith("الامثله") ||
                n.startsWith("امثلهالانطلاق") ||
                n.startsWith("المجموعه")
        }
        return if (splitIdx > 0) {
            lines.subList(0, splitIdx) to lines.subList(splitIdx, lines.size)
        } else {
            emptyList<String>() to lines
        }
    }

    private fun classifyColumnHeader(
        normalizedHeader: String,
        alreadyAssigned: Set<TableColType> = emptySet()
    ): TableColType {
        val rules = listOf(
            TableColType.TIME to listOf("الحصيص", "الزمني", "المده"),
            TableColType.EVAL to listOf("التقويم", "الدعم", "مؤشراتالتقويم", "مؤشرات"),
            TableColType.TEACHER to listOf("انشطهالمدرس", "انشطهالاستاذ", "مهامالمدرس", "توجيهات", "المدرس", "الاستاذ"),
            TableColType.LEARNER to listOf("انشطهالمتعلم", "انشطهالمتعلمين", "مهامالمتعلم", "المتعلمين", "المتعلم", "التلميذ"),
            TableColType.CONTENT to listOf("محتوي", "مضامين", "سيرالدرس", "الامثله", "النصوص", "التعلمات", "الدعامات"),
            TableColType.STAGE to listOf("المراحل", "الخطوات", "خطواتالقراءه", "المقاطع", "المقطع"),
            TableColType.SEGMENT to listOf("مقاطع", "المقطع"),
            TableColType.ABIL to listOf("القدرات", "الكفايات", "الاهداف", "المهارات")
        )
        for ((colType, keywords) in rules) {
            if (colType in alreadyAssigned && colType != TableColType.EVAL) continue
            if (keywords.any { kw -> normalizedHeader.contains(kw) }) {
                return colType
            }
        }
        return TableColType.UNKNOWN
    }

    private fun resolveUnknownColumns(
        cols: MutableMap<TableColType, Int>,
        colHeaders: List<String>
    ) {
        if (!cols.containsKey(TableColType.TEACHER) && !cols.containsKey(TableColType.LEARNER)) {
            val actHit = colHeaders.indices.firstOrNull { idx ->
                idx !in cols.values && (colHeaders[idx].contains("الانشطه") || colHeaders[idx].contains("التعليميه"))
            }
            if (actHit != null) {
                cols[TableColType.ACTIVITIES] = actHit
            }
        }
    }

    /**
     * Inspects the first [headerRows] rows (especially row 1 and row 2 together for split headers
     * such as "الأنشطة التعليمية التعلمية" split into "أنشطة المدرس" and "أنشطة المتعلمين")
     * and maps each column role to all matching column indices (allowing multiple EVAL columns
     * such as "مؤشرات التقويم" and "التقويم والدعم" to be merged).
     */
    private fun detectMultiColumnsFromGrid(
        grid: List<List<CellNode?>>,
        headerRows: Int
    ): Map<TableColType, List<Int>> {
        val nCols = grid.maxOfOrNull { it.size } ?: 0
        if (nCols == 0) return emptyMap()

        // For each column, examine both the deepest header row (row 2 if split) and the combined header text
        val perColSpecificHeader = (0 until nCols).map { c ->
            val deepestCell = (minOf(headerRows, grid.size) - 1 downTo 0)
                .firstNotNullOfOrNull { r -> grid[r].getOrNull(c) }
            normalizeArabicForMatch(deepestCell?.flatParagraphs?.joinToString(" ").orEmpty())
        }

        val colHeaders = (0 until nCols).map { c ->
            val seen = mutableSetOf<Int>()
            val parts = mutableListOf<String>()
            for (r in 0 until minOf(headerRows, grid.size)) {
                val cell = grid[r].getOrNull(c)
                if (cell != null && seen.add(cell.id)) {
                    parts.add(normalizeArabicForMatch(cell.flatParagraphs.joinToString(" ")))
                }
            }
            parts.joinToString(" ")
        }

        val result = mutableMapOf<TableColType, MutableList<Int>>()
        val assignedIndices = mutableSetOf<Int>()

        fun assignRole(role: TableColType, colIdx: Int) {
            result.getOrPut(role) { mutableListOf() }.add(colIdx)
            assignedIndices.add(colIdx)
        }

        // Pass 1: Match using the most specific (deepest) header cell first so split headers in row 2
        // ("أنشطة المدرس" vs "أنشطة المتعلمين") win over the spanned parent cell ("الأنشطة التعليمية التعلمية").
        val rules = listOf(
            TableColType.TIME to listOf("الحصيص", "الزمني", "المده"),
            TableColType.EVAL to listOf("التقويم", "الدعم", "مؤشراتالتقويم", "مؤشرات"),
            TableColType.TEACHER to listOf("انشطهالمدرس", "انشطهالاستاذ", "مهامالمدرس", "توجيهات", "المدرس", "الاستاذ"),
            TableColType.LEARNER to listOf("انشطهالمتعلم", "انشطهالمتعلمين", "مهامالمتعلم", "المتعلمين", "المتعلم", "التلميذ"),
            TableColType.CONTENT to listOf("محتوي", "مضامين", "سيرالدرس", "الامثله", "النصوص", "التعلمات", "الدعامات"),
            TableColType.STAGE to listOf("المراحل", "الخطوات", "خطواتالقراءه"),
            TableColType.SEGMENT to listOf("المقاطع", "مقاطع", "المقطع"),
            TableColType.ABIL to listOf("القدرات", "الكفايات", "الاهداف", "المهارات")
        )

        for ((colType, keywords) in rules) {
            for (idx in 0 until nCols) {
                if (idx in assignedIndices) continue
                val spec = perColSpecificHeader[idx]
                if (keywords.any { kw -> spec.contains(kw) }) {
                    assignRole(colType, idx)
                    if (colType != TableColType.EVAL) break
                }
            }
        }

        // Pass 2: Match using combined row 1 + row 2 headers for any remaining unassigned columns
        for ((colType, keywords) in rules) {
            if (colType != TableColType.EVAL && result.containsKey(colType)) continue
            for (idx in 0 until nCols) {
                if (idx in assignedIndices) continue
                val comb = colHeaders[idx]
                if (keywords.any { kw -> comb.contains(kw) }) {
                    assignRole(colType, idx)
                    if (colType != TableColType.EVAL) break
                }
            }
        }

        // If SEGMENT was matched and STAGE was not, promote SEGMENT to STAGE
        if (!result.containsKey(TableColType.STAGE) && result.containsKey(TableColType.SEGMENT)) {
            result[TableColType.STAGE] = result.remove(TableColType.SEGMENT)!!
        }

        // Resolve generic ACTIVITIES column if neither TEACHER nor LEARNER was matched
        if (!result.containsKey(TableColType.TEACHER) && !result.containsKey(TableColType.LEARNER)) {
            val actHit = colHeaders.indices.firstOrNull { idx ->
                idx !in assignedIndices && (colHeaders[idx].contains("الانشطه") || colHeaders[idx].contains("التعليميه"))
            }
            if (actHit != null) {
                assignRole(TableColType.ACTIVITIES, actHit)
            }
        }

        return result
    }

    private fun detectColumnsFromGrid(grid: List<List<CellNode?>>, headerRows: Int): Map<TableColType, Int> {
        return detectMultiColumnsFromGrid(grid, headerRows).mapValues { it.value.first() }
    }

    private fun normalizeArabicForMatch(s: String): String {
        return s.replace(Regex("[\u0640\u064B-\u065F\u0670]"), "")
            .replace(Regex("[أإآٱ]"), "ا")
            .replace("ى", "ي")
            .replace("ة", "ه")
            .replace(Regex("[^\\w\\p{L}]+"), "")
            .lowercase()
    }

    private fun extractFirstTagBlock(xml: String, tag: String): String {
        val m = Regex("<w:$tag(?:\\s[^>]*)?>(.*?)</w:$tag>", RegexOption.DOT_MATCHES_ALL).find(xml)
        return m?.groupValues?.get(1).orEmpty()
    }

    private fun extractGridSpan(tcPrXml: String): Int {
        if (tcPrXml.isEmpty()) return 1
        val m = Regex("<w:gridSpan[^>]*w:val=\"(\\d+)\"").find(tcPrXml)
        return m?.groupValues?.get(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    }

    private fun extractVMerge(tcPrXml: String): String? {
        if (tcPrXml.isEmpty()) return null
        val m = Regex("<w:vMerge(?:\\s+w:val=\"([^\"]+)\")?\\s*/?>").find(tcPrXml) ?: return null
        val v = m.groupValues.getOrNull(1).orEmpty()
        return v.ifEmpty { "continue" }
    }

    /**
     * Legacy helper: Extracts paragraph blocks from a <w:tc>, including any nested <w:tbl> paragraphs
     * merged in order into the parent cell.
     */
    private fun extractCellParagraphBlocksLegacy(tcXml: String): List<List<String>> {
        val rawTexts = extractCellMergedParagraphsLegacy(tcXml)
        val blocks = mutableListOf<MutableList<String>>()
        var afterBlank = true

        for (raw in rawTexts) {
            val subLines = raw.split("\n")
            for (sub in subLines) {
                val t = stripFormattingTags(sub).trim()
                if (t.isEmpty()) {
                    afterBlank = true
                    continue
                }
                if (afterBlank || blocks.isEmpty()) {
                    blocks.add(mutableListOf(t))
                } else {
                    blocks.last().add(t)
                }
                afterBlank = false
            }
        }
        return blocks
    }

    /**
     * Legacy helper: Extracts direct paragraphs and merges any nested <w:tbl> cell paragraphs
     * directly into the parent cell's paragraph list.
     */
    private fun extractCellMergedParagraphsLegacy(tcXml: String): List<String> {
        val out = mutableListOf<String>()
        val innerBlocks = extractCellChildParagraphsAndTables(tcXml)
        for ((isTbl, blockXml) in innerBlocks) {
            if (!isTbl) {
                out.add(extractParagraphText(blockXml))
            } else {
                val trList = extractDirectChildrenIgnoreNestedTables(blockXml, "tr", stripOuterTableTag = true)
                val firstRowCells = trList.firstOrNull()?.let {
                    extractDirectChildrenIgnoreNestedTables(it, "tc", stripOuterTableTag = false)
                }.orEmpty()
                val isAnalyticalSubTable = trList.size >= 2 && firstRowCells.size >= 3
                if (isAnalyticalSubTable) {
                    for (trXml in trList) {
                        val tcList = extractDirectChildrenIgnoreNestedTables(trXml, "tc", stripOuterTableTag = false)
                        val rowCellsText = tcList.map { nestedTcXml ->
                            extractCellMergedParagraphsLegacy(nestedTcXml).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
                        }.filter { it.isNotEmpty() }
                        if (rowCellsText.isNotEmpty()) {
                            out.add("[جدول: ${rowCellsText.joinToString(" | ")}]")
                        }
                    }
                } else {
                    for (trXml in trList) {
                        val tcList = extractDirectChildrenIgnoreNestedTables(trXml, "tc", stripOuterTableTag = false)
                        for (nestedTcXml in tcList) {
                            out.addAll(extractCellMergedParagraphsLegacy(nestedTcXml))
                        }
                    }
                }
            }
        }
        return out
    }

    private fun extractCellChildParagraphsAndTables(tcXml: String): List<Pair<Boolean, String>> {
        val firstClose = tcXml.indexOf('>')
        val lastOpen = tcXml.lastIndexOf("</w:tc>")
        val inner = if (firstClose >= 0 && lastOpen > firstClose) {
            tcXml.substring(firstClose + 1, lastOpen)
        } else {
            tcXml
        }
        return extractTopLevelBlocks(inner)
    }

    private fun startsNewItem(t: String): Boolean {
        val trimmed = t.trim()
        if (trimmed.startsWith("-") || trimmed.startsWith("+") || trimmed.startsWith("•") ||
            trimmed.startsWith("*")
        ) return true
        if (Regex("^\\(?\\d{1,2}\\s*[-–.)/]").containsMatchIn(trimmed)) return true
        val norm = normalizeArabicForMatch(trimmed)
        return norm.startsWith("اولا") || norm.startsWith("ثانيا") || norm.startsWith("ثالثا") ||
            norm.startsWith("رابعا") || norm.startsWith("خامسا") || norm.startsWith("استنتاج") ||
            norm.startsWith("قاعده")
    }

    private fun endsWithTerminal(t: String): Boolean {
        val trimmed = t.trimEnd()
        if (trimmed.isEmpty()) return false
        return trimmed.last() in charArrayOf('.', ':', '؟', '?', '!', '؛', ';', '…')
    }

    private fun extractParagraphText(pXml: String): String {
        if (pXml.contains("<mc:Fallback")) {
            val cleaned = pXml.replace(Regex("<mc:Fallback>.*?</mc:Fallback>", RegexOption.DOT_MATCHES_ALL), "")
            return extractParagraphTextClean(cleaned)
        }
        return extractParagraphTextClean(pXml)
    }

    private fun extractParagraphTextClean(pXml: String): String {
        val rRegex = Regex("<w:r(?:\\s[^>]*)?>(.*?)</w:r>", RegexOption.DOT_MATCHES_ALL)
        val tRegex = Regex("<w:t(?:\\s[^>]*)?>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
        val sb = StringBuilder()

        val rMatches = rRegex.findAll(pXml).toList()
        if (rMatches.isNotEmpty()) {
            for (rMatch in rMatches) {
                val rContent = rMatch.groupValues[1]
                if (rContent.contains("<w:delText") || rContent.contains("<w:instrText")) continue
                val isUnderlined = rContent.contains("<w:u ") || rContent.contains("<w:u/>")
                val runTextBuilder = StringBuilder()
                for (tMatch in tRegex.findAll(rContent)) {
                    runTextBuilder.append(decodeXmlEntities(tMatch.groupValues[1]))
                }
                if (rContent.contains("<w:tab")) runTextBuilder.append(" ")
                if (rContent.contains("<w:br") || rContent.contains("<w:cr")) runTextBuilder.append("\n")
                val runText = runTextBuilder.toString()
                if (runText.isNotEmpty()) {
                    if (isUnderlined && runText.trim().endsWith(":")) {
                        sb.append("${runText.trim()} ")
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

        return stripFormattingTags(sb.toString())
            .replace("\u00a0", " ")
            .replace(Regex("[ \\t]+"), " ")
            .trim()
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
