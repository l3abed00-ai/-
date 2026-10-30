package com.example.extractor

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
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

data class UploadedFileInfo(
    val name: String,
    val sizeBytes: Long,
    val textContent: String,
    val detectedType: DetectedFileType = DetectedFileType.PLAIN_TEXT,
    val pdfBytes: ByteArray? = null
)

object DocumentFileReader {

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
        val vMerge: String? = null
    )

    data class Table(
        val rows: List<List<TableCell>>
    )

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
        val contentLinesWithTables: List<String>
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
            val extractedText = extractTextFromDocxStreamInternal(
                inputStream = countedStream,
                requireDocumentXmlEntry = true,
                onCheckActive = onCheckActive
            )
            if (extractedText.isBlank()) {
                throw DocumentReadException(MSG_FILE_EMPTY)
            }
            return UploadedFileInfo(
                name = fileName,
                sizeBytes = if (declaredSize > 0L) declaredSize else countedStream.totalBytesRead,
                textContent = extractedText.trim(),
                detectedType = DetectedFileType.DOCX
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

        return UploadedFileInfo(
            name = fileName,
            sizeBytes = if (declaredSize > 0L) declaredSize else rawBytes.size.toLong(),
            textContent = decodedText.trim(),
            detectedType = DetectedFileType.PLAIN_TEXT
        )
    }

    /**
     * Extracts text and structured table cells from Word .docx file by streaming word/document.xml,
     * word/header*.xml, and word/footer*.xml with a 20 MB uncompressed size limit.
     */
    fun extractTextFromDocx(inputStream: InputStream): String {
        return extractTextFromDocxStreamInternal(
            inputStream = inputStream,
            requireDocumentXmlEntry = false,
            onCheckActive = {}
        )
    }

    private fun extractTextFromDocxStreamInternal(
        inputStream: InputStream,
        requireDocumentXmlEntry: Boolean,
        onCheckActive: () -> Unit
    ): String {
        var foundDocumentXml = false
        var foundAnyEntry = false
        var totalUncompressedBytes = 0L
        var documentXmlRaw = ""
        val headerFooterXmlList = mutableListOf<Pair<String, String>>()

        fun readCurrentZipEntryUtf8(zip: ZipInputStream): String {
            val out = ByteArrayOutputStream(32 * 1024)
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
            return out.toByteArray().toString(StandardCharsets.UTF_8)
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

                if (entryName.equals("word/document.xml", ignoreCase = true)) {
                    foundDocumentXml = true
                    documentXmlRaw = readCurrentZipEntryUtf8(zip)
                } else if (HEADER_FOOTER_ENTRY_REGEX.matches(entryName)) {
                    val hfXml = readCurrentZipEntryUtf8(zip)
                    headerFooterXmlList.add(entryName to hfXml)
                } else {
                    // Drain entry while counting uncompressed bytes to guard against zip bombs
                    val skipBuf = ByteArray(8 * 1024)
                    while (true) {
                        onCheckActive()
                        val read = zip.read(skipBuf)
                        if (read == -1) break
                        totalUncompressedBytes += read
                        if (totalUncompressedBytes > MAX_DOCX_UNCOMPRESSED_BYTES) {
                            throw DocumentReadException(MSG_DOCX_UNCOMPRESSED_TOO_LARGE)
                        }
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
            return ""
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
            return ""
        }

        val mainText = parseDocumentXml(documentXmlRaw)
        if (headerFooterXmlList.isEmpty()) {
            return mainText
        }

        // Sort headers first (header1, header2, ...) then footers (footer1, footer2, ...) deterministically
        val sortedHeaderFooters = headerFooterXmlList.sortedWith(
            compareBy<Pair<String, String>> { (name, _) ->
                if (name.lowercase().contains("header")) 0 else 1
            }.thenBy { (name, _) -> name.lowercase() }
        )

        val outputLines = mutableListOf<String>()
        val seenNonBlankLines = mutableSetOf<String>()
        if (mainText.isNotEmpty()) {
            for (line in mainText.lines()) {
                outputLines.add(line)
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    seenNonBlankLines.add(trimmed)
                }
            }
        }

        for ((_, hfXml) in sortedHeaderFooters) {
            val hfParsed = parseDocumentXml(hfXml)
            if (hfParsed.isNotEmpty()) {
                for (line in hfParsed.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && trimmed != "[نهاية الجدول]" && seenNonBlankLines.add(trimmed)) {
                        outputLines.add(trimmed)
                    }
                }
            }
        }

        return outputLines.joinToString("\n")
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

        return decoded
    }

    /**
     * Parses Word XML (word/document.xml, word/header*.xml, word/footer*.xml) using streaming
     * XmlPullParser with FEATURE_PROCESS_NAMESPACES enabled. Falls back to [parseDocumentXmlLegacy]
     * if the XML is malformed or any parsing error occurs.
     */
    fun parseDocumentXml(xml: String): String {
        if (xml.isBlank()) return ""
        return try {
            parseDocumentXmlWithPullParser(xml)
        } catch (_: Throwable) {
            parseDocumentXmlLegacy(xml)
        }
    }

    private enum class AlternateContentState {
        BEFORE_CHOICE,
        IN_CHOICE,
        AFTER_CHOICE
    }

    private class TableBuilder {
        val rows = mutableListOf<List<TableCell>>()
    }

    private class RowBuilder {
        val cells = mutableListOf<TableCell>()
    }

    private class CellBuilder {
        val paragraphs = mutableListOf<Para>()
        var gridSpan: Int = 1
        var vMerge: String? = null
        var inTcPr: Boolean = false
    }

    private class ParagraphBuilder {
        val parts = mutableListOf(StringBuilder())
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
                    target.append("<u>${runText.trim()}</u> ")
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
                    val t = element.para.text.trim()
                    if (t.isNotEmpty()) {
                        outputLines.add(t)
                    }
                }
                is DocElement.TableItem -> {
                    outputLines.addAll(parseTableXml(element.table))
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
                            "tr" -> {
                                if (tableStack.isNotEmpty()) {
                                    rowStack.addLast(RowBuilder())
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
                            "p" -> {
                                pStack.addLast(ParagraphBuilder())
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
                            "p" -> {
                                if (pStack.isNotEmpty()) {
                                    val finishedPara = pStack.removeLast()
                                    val cleanedParts = finishedPara.parts.map { normalizeParagraphPart(it.toString()) }
                                    val nonEmptyParts = cleanedParts.filter { it.isNotEmpty() }

                                    if (cellStack.isNotEmpty()) {
                                        val currentCell = cellStack.last()
                                        if (nonEmptyParts.isEmpty()) {
                                            currentCell.paragraphs.add(Para(""))
                                        } else {
                                            for (part in nonEmptyParts) {
                                                currentCell.paragraphs.add(Para(part))
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
                                                vMerge = finishedCell.vMerge
                                            )
                                        )
                                    }
                                }
                            }
                            "tr" -> {
                                if (rowStack.isNotEmpty()) {
                                    val finishedRow = rowStack.removeLast()
                                    if (tableStack.isNotEmpty() && finishedRow.cells.isNotEmpty()) {
                                        tableStack.last().rows.add(finishedRow.cells.toList())
                                    }
                                }
                            }
                            "tbl" -> {
                                if (tableStack.isNotEmpty()) {
                                    val finishedTable = Table(tableStack.removeLast().rows.toList())
                                    if (cellStack.isNotEmpty()) {
                                        // Nested table inside a cell: append its cells' paragraphs in order to the parent cell
                                        val parentCell = cellStack.last()
                                        for (row in finishedTable.rows) {
                                            for (cell in row) {
                                                for (para in cell.paragraphs) {
                                                    if (para.text.isNotEmpty()) {
                                                        parentCell.paragraphs.add(para)
                                                    }
                                                }
                                            }
                                        }
                                    } else {
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
        return raw
            .replace("\u0640", "")
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
        return cell.paragraphs.map { it.text.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Groups a [TableCell]'s paragraphs into blocks separated by blank paragraphs while keeping
     * each paragraph (and each br/cr split) as a separate line.
     */
    private fun extractCellParagraphBlocks(cell: TableCell): List<List<String>> {
        val blocks = mutableListOf<MutableList<String>>()
        var afterBlank = true
        for (para in cell.paragraphs) {
            val t = para.text.trim()
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
     * or header/metadata lines (`عنوان: عنصر - عنصر`), preserving column classification and resolution logic.
     */
    internal fun parseTableXml(table: Table): List<String> {
        val result = mutableListOf<String>()
        if (table.rows.isEmpty()) return result

        // Build 2D grid supporting gridSpan and vMerge
        val grid = mutableListOf<List<CellNode?>>()
        var nextCellId = 1

        for (tableRow in table.rows) {
            val row = mutableListOf<CellNode?>()
            for (tc in tableRow) {
                val pBlocks = extractCellParagraphBlocks(tc)
                val flatParas = extractCellParagraphs(tc)
                val node = CellNode(
                    id = nextCellId++,
                    rawXml = "",
                    paragraphBlocks = pBlocks,
                    flatParagraphs = flatParas,
                    contentLinesWithTables = flatParas
                )

                val span = tc.gridSpan.coerceAtLeast(1)
                for (s in 0 until span) {
                    if (tc.vMerge == "continue" && grid.isNotEmpty() && row.size < grid.last().size) {
                        row.add(grid.last()[row.size])
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

    /**
     * Legacy fallback parser using regex/depth-balanced XML slicing.
     * Kept intact for safety when XmlPullParser encounters corrupted XML.
     */
    internal fun parseDocumentXmlLegacy(xml: String): String {
        val outputLines = mutableListOf<String>()
        val topBlocks = extractTopLevelBlocks(xml)

        for ((isTable, blockXml) in topBlocks) {
            if (isTable) {
                outputLines.addAll(parseTableXmlLegacy(blockXml))
            } else {
                val pText = extractParagraphText(blockXml)
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

        // Build 2D grid supporting gridSpan and vMerge (matching build_grid in reference Python scripts)
        val grid = mutableListOf<List<CellNode?>>()
        var nextCellId = 1

        for (trXml in trList) {
            val row = mutableListOf<CellNode?>()
            val tcList = extractDirectChildrenIgnoreNestedTables(trXml, "tc", stripOuterTableTag = false)
            for (tcXml in tcList) {
                val tcPrXml = extractFirstTagBlock(tcXml, "tcPr")
                val span = extractGridSpan(tcPrXml)
                val vMerge = extractVMerge(tcPrXml)

                val pBlocks = extractCellParagraphBlocksLegacy(tcXml)
                val flatParas = pBlocks.flatten()
                val contentWithTables = extractCellContentWithNestedTables(tcXml)
                val node = CellNode(
                    id = nextCellId++,
                    rawXml = tcXml,
                    paragraphBlocks = pBlocks,
                    flatParagraphs = flatParas,
                    contentLinesWithTables = contentWithTables
                )

                for (s in 0 until span) {
                    if (vMerge == "continue" && grid.isNotEmpty() && row.size < grid.last().size) {
                        row.add(grid.last()[row.size])
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

    private fun formatTableGridToLines(
        grid: List<List<CellNode?>>,
        result: MutableList<String>
    ): List<String> {
        if (grid.isEmpty()) return result

        // Detect header rows (1..3) and column mapping
        var bestHeaderRows = -1
        var bestColMap = mapOf<TableColType, Int>()
        var bestScore = -1

        for (hRows in listOf(1, 2, 3)) {
            if (hRows >= grid.size) continue
            if (hRows > 1) {
                val prevRowCells = grid[hRows - 2].filterNotNull()
                val prevRowHasSpan = prevRowCells.size > prevRowCells.distinctBy { it.id }.size
                val currRowDistinct = grid[hRows - 1].filterNotNull().distinctBy { it.id }
                val sharesVerticalMerge = currRowDistinct.any { cell -> prevRowCells.any { it.id == cell.id } }
                if (currRowDistinct.size <= 1 || (!prevRowHasSpan && !sharesVerticalMerge)) {
                    break
                }
            }
            val candidateMap = detectColumnsFromGrid(grid, hRows)
            val hasStageOrAbil = candidateMap.containsKey(TableColType.STAGE) ||
                candidateMap.containsKey(TableColType.SEGMENT) ||
                candidateMap.containsKey(TableColType.ABIL)
            val hasContentOrAct = candidateMap.containsKey(TableColType.CONTENT) ||
                candidateMap.containsKey(TableColType.TEACHER) ||
                candidateMap.containsKey(TableColType.LEARNER) ||
                candidateMap.containsKey(TableColType.ACTIVITIES)

            if (hasStageOrAbil && hasContentOrAct && candidateMap.size >= 2) {
                if (candidateMap.size > bestScore) {
                    bestScore = candidateMap.size
                    bestHeaderRows = hRows
                    bestColMap = candidateMap
                }
            }
        }

        if (bestHeaderRows != -1) {
            val seenIds = mutableSetOf<Int>()
            var lastSegmentStageText = ""

            for (rIdx in bestHeaderRows until grid.size) {
                val row = grid[rIdx]
                val distinctCells = row.filterNotNull().distinctBy { it.id }

                // Check if this is a single-cell spanned banner row (e.g., Session Banner)
                if (distinctCells.size == 1) {
                    val bannerNode = distinctCells[0]
                    seenIds.add(bannerNode.id)
                    val bannerText = bannerNode.flatParagraphs.joinToString(" ").trim()
                    if (bannerText.isNotEmpty()) {
                        if (bannerText.contains("أنشطة الاكتساب") ||
                            bannerText.contains("أنشطة التطبيق") ||
                            bannerText.contains("أنشطة الإنتاج") ||
                            bannerText.contains("أنشطة التصحيح") ||
                            bannerText.contains("الحصة الأولى") ||
                            bannerText.contains("الحصة الثانية") ||
                            bannerText.contains("الحصة الثالثة") ||
                            bannerText.contains("الحصة الرابعة")
                        ) {
                            result.add("[حصة: $bannerText]")
                        }
                    }
                    continue
                }

                fun takeCell(type: TableColType): CellNode? {
                    val colIdx = bestColMap[type] ?: return null
                    val cell = row.getOrNull(colIdx) ?: return null
                    if (cell.id in seenIds) return null
                    seenIds.add(cell.id)
                    return cell
                }

                val stageCell = takeCell(TableColType.STAGE)
                val segmentCell = takeCell(TableColType.SEGMENT)
                val timeCell = takeCell(TableColType.TIME)
                val abilCell = takeCell(TableColType.ABIL)
                val teacherCell = takeCell(TableColType.TEACHER)
                val learnerCell = takeCell(TableColType.LEARNER)
                val genericActCell = takeCell(TableColType.ACTIVITIES)
                val contentCell = takeCell(TableColType.CONTENT)
                val evalCell = takeCell(TableColType.EVAL)

                // Also collect any unmapped non-empty cells so nothing is ever lost
                val mappedIndices = bestColMap.values.toSet()
                val extraContentLines = mutableListOf<String>()
                for (cIdx in row.indices) {
                    if (cIdx !in mappedIndices) {
                        val extraCell = row[cIdx]
                        if (extraCell != null && extraCell.id !in seenIds) {
                            seenIds.add(extraCell.id)
                            extraContentLines.addAll(extraCell.contentLinesWithTables)
                        }
                    }
                }

                val rawStageLines = stageCell?.flatParagraphs.orEmpty()
                val rawSegmentLines = segmentCell?.flatParagraphs.orEmpty()
                if (rawSegmentLines.isNotEmpty()) {
                    lastSegmentStageText = rawSegmentLines.joinToString(" ")
                }
                val combinedStageLines = when {
                    rawStageLines.isNotEmpty() -> rawStageLines
                    rawSegmentLines.isNotEmpty() -> rawSegmentLines
                    lastSegmentStageText.isNotBlank() -> listOf(lastSegmentStageText)
                    else -> emptyList()
                }

                val abilBlocks = abilCell?.paragraphBlocks.orEmpty()
                val teacherBlocks = teacherCell?.paragraphBlocks.orEmpty()
                val learnerBlocks = learnerCell?.paragraphBlocks.orEmpty()
                val genericBlocks = genericActCell?.paragraphBlocks.orEmpty()
                val evalBlocks = evalCell?.paragraphBlocks.orEmpty()
                val contentLines = (contentCell?.contentLinesWithTables.orEmpty() + extraContentLines).filter { it.isNotBlank() }

                val hasAnyData = combinedStageLines.isNotEmpty() ||
                    abilBlocks.isNotEmpty() ||
                    teacherBlocks.isNotEmpty() ||
                    learnerBlocks.isNotEmpty() ||
                    genericBlocks.isNotEmpty() ||
                    evalBlocks.isNotEmpty() ||
                    contentLines.isNotEmpty()
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
        } else {
            // Parse as Header / Metadata Table (handling RTL value-before-label or label-before-value cells)
            val seenHeaderCells = mutableSetOf<Int>()
            for (row in grid) {
                val distinctRowCells = row.filterNotNull().filter { seenHeaderCells.add(it.id) }.map { it.flatParagraphs }
                var cIdx = 0
                while (cIdx < distinctRowCells.size) {
                    val cellParas = distinctRowCells[cIdx].filter { it.isNotBlank() }
                    if (cellParas.size == 1 && !cellParas[0].contains(":") && cellParas[0].length <= 35 && cIdx + 1 < distinctRowCells.size) {
                        val nextParas = distinctRowCells[cIdx + 1].filter { it.isNotBlank() }
                        if (nextParas.isNotEmpty() && !nextParas[0].contains(":")) {
                            // Check which one is the known header label (in RTL tables, sometimes value cell comes before label cell)
                            val firstIsLabel = isKnownHeaderFieldLabel(cellParas[0])
                            val secondIsLabel = nextParas.size == 1 && isKnownHeaderFieldLabel(nextParas[0])
                            if (secondIsLabel && !firstIsLabel) {
                                result.add("${nextParas[0]}: ${cellParas.joinToString(" - ")}")
                            } else {
                                result.add("${cellParas[0]}: ${nextParas.joinToString(" - ")}")
                            }
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

    private fun isKnownHeaderFieldLabel(text: String): Boolean {
        val norm = normalizeArabicForMatch(text)
        return norm in setOf(
            "المستوي", "المستويالدراسي", "الفئهالمستهدفه", "الوحده", "المجال",
            "المكون", "الموضوع", "موضوعالدرس", "عنوانالدرس", "المهارهالمستهدفه",
            "مدهالانجاز", "الحصيصالزمني", "المده", "الغلافالزمني",
            "الوسائلالتعليميه", "الوسائلالديداكتيكيه", "المرجع", "القدرهالخاصهبالدرس"
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
        val combinedActivities = mutableListOf<String>()
        combinedActivities.addAll(teacherLines)
        combinedActivities.addAll(genericActLines)
        if (contentLines.isNotEmpty()) {
            // When a separate content column exists, include learner activities under teacher/didactic activities
            combinedActivities.addAll(learnerLines)
        }
        if (combinedActivities.isNotEmpty()) {
            result.add("أنشطة المدرس:")
            combinedActivities.forEach { result.add(it) }
        }
        val effectiveContentLines = if (contentLines.isNotEmpty()) contentLines else learnerLines
        if (effectiveContentLines.isNotEmpty()) {
            result.add("المحتوى الديداكتيكي:")
            effectiveContentLines.forEach { result.add(it) }
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
        } else if (splitIdx == 0) {
            emptyList<String>() to lines
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
            TableColType.EVAL to listOf("التقويم", "الدعم", "مؤشرات"),
            TableColType.CONTENT to listOf("محتوي", "التعلمات", "المضامين", "الدعامات"),
            TableColType.TEACHER to listOf("المدرس", "الاستاذ"),
            TableColType.LEARNER to listOf("المتعلم", "التلميذ"),
            TableColType.STAGE to listOf("المراحل", "المهارات", "الخطوات"),
            TableColType.SEGMENT to listOf("مقاطع", "المقطع"),
            TableColType.ABIL to listOf("القدرات", "الكفايات", "الاهداف")
        )
        for ((colType, keywords) in rules) {
            if (colType in alreadyAssigned) continue
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

    private fun detectColumnsFromGrid(grid: List<List<CellNode?>>, headerRows: Int): Map<TableColType, Int> {
        val nCols = grid.maxOfOrNull { it.size } ?: 0
        if (nCols == 0) return emptyMap()

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

        val cols = mutableMapOf<TableColType, Int>()
        val rules = listOf(
            TableColType.TIME to listOf("الحصيص", "الزمني", "المده"),
            TableColType.EVAL to listOf("التقويم", "الدعم", "مؤشرات"),
            TableColType.CONTENT to listOf("محتوي", "التعلمات", "المضامين", "الدعامات"),
            TableColType.TEACHER to listOf("المدرس", "الاستاذ"),
            TableColType.LEARNER to listOf("المتعلم", "التلميذ"),
            TableColType.STAGE to listOf("المراحل", "المهارات", "الخطوات"),
            TableColType.SEGMENT to listOf("مقاطع", "المقطع"),
            TableColType.ABIL to listOf("القدرات", "الكفايات", "الاهداف")
        )

        for ((colType, keywords) in rules) {
            for (kw in keywords) {
                val hit = colHeaders.indices.firstOrNull { idx ->
                    idx !in cols.values && colHeaders[idx].contains(kw)
                }
                if (hit != null) {
                    cols[colType] = hit
                    break
                }
            }
        }

        resolveUnknownColumns(cols, colHeaders)

        return cols
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
     * Legacy helper: Extracts paragraph blocks from a <w:tc> (ignoring nested tables), grouping paragraphs separated by blank lines
     * and re-joining single-word broken lines.
     */
    private fun extractCellParagraphBlocksLegacy(tcXml: String): List<List<String>> {
        val directParas = extractDirectChildrenIgnoreNestedTables(tcXml, "p", stripOuterTableTag = false)
        val rawTexts = directParas.map { extractParagraphText(it) }
        val blocks = mutableListOf<MutableList<String>>()
        var afterBlank = true

        for (raw in rawTexts) {
            val subLines = raw.split("\n")
            for (sub in subLines) {
                val t = sub.trim()
                if (t.isEmpty()) {
                    afterBlank = true
                    continue
                }
                if (afterBlank || blocks.isEmpty()) {
                    blocks.add(mutableListOf(t))
                } else {
                    val lastBlock = blocks.last()
                    val prevLine = lastBlock.last()
                    if (startsNewItem(t) || endsWithTerminal(prevLine)) {
                        lastBlock.add(t)
                    } else {
                        lastBlock[lastBlock.lastIndex] = "$prevLine $t"
                    }
                }
                afterBlank = false
            }
        }
        return blocks
    }

    /**
     * Legacy helper: Extracts both direct paragraphs and any nested <w:tbl> tables inside a <w:tc>.
     */
    private fun extractCellContentWithNestedTables(tcXml: String): List<String> {
        val out = mutableListOf<String>()
        val innerBlocks = extractCellChildParagraphsAndTables(tcXml)
        val pendingParagraphs = mutableListOf<String>()

        fun flushParagraphs() {
            if (pendingParagraphs.isEmpty()) return
            val reflowed = reflowParagraphStrings(pendingParagraphs)
            out.addAll(reflowed)
            pendingParagraphs.clear()
        }

        for ((isTbl, blockXml) in innerBlocks) {
            if (!isTbl) {
                pendingParagraphs.add(extractParagraphText(blockXml))
            } else {
                flushParagraphs()
                val nestedRows = extractNestedTableRows(blockXml)
                out.addAll(nestedRows)
            }
        }
        flushParagraphs()
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

    private fun extractNestedTableRows(nestedTblXml: String): List<String> {
        val rowsOut = mutableListOf<String>()
        val trList = extractDirectChildrenIgnoreNestedTables(nestedTblXml, "tr", stripOuterTableTag = true)
        for (trXml in trList) {
            val tcList = extractDirectChildrenIgnoreNestedTables(trXml, "tc", stripOuterTableTag = false)
            val cellsText = tcList.map { tc ->
                val pBlocks = extractCellParagraphBlocksLegacy(tc)
                pBlocks.flatten().joinToString(" ").trim()
            }
            if (cellsText.any { it.isNotEmpty() }) {
                rowsOut.add("[جدول: ${cellsText.joinToString(" | ")}]")
            }
        }
        return rowsOut
    }

    private fun reflowParagraphStrings(rawParas: List<String>): List<String> {
        val blocks = mutableListOf<MutableList<String>>()
        var afterBlank = true
        for (raw in rawParas) {
            for (sub in raw.split("\n")) {
                val t = sub.trim()
                if (t.isEmpty()) {
                    afterBlank = true
                    continue
                }
                if (afterBlank || blocks.isEmpty()) {
                    blocks.add(mutableListOf(t))
                } else {
                    val lastBlock = blocks.last()
                    val prev = lastBlock.last()
                    if (startsNewItem(t) || endsWithTerminal(prev)) {
                        lastBlock.add(t)
                    } else {
                        lastBlock[lastBlock.lastIndex] = "$prev $t"
                    }
                }
                afterBlank = false
            }
        }
        return blocks.flatten()
    }

    private fun startsNewItem(t: String): Boolean {
        val trimmed = t.trim()
        if (trimmed.startsWith("-") || trimmed.startsWith("+") || trimmed.startsWith("•") ||
            trimmed.startsWith("*") || trimmed.startsWith("<u>")
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

        return sb.toString()
            .replace("\u0640", "")
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
