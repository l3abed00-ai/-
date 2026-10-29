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
        val stem = fileName.substringBeforeLast(".")
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
     * Parses word/document.xml using depth-balanced XML scanning so nested <w:tbl> inside <w:tc>
     * (such as analytical tables in "الوصف والتحليل") never truncate the outer stages table.
     */
    fun parseDocumentXml(xml: String): String {
        val outputLines = mutableListOf<String>()
        val topBlocks = extractTopLevelBlocks(xml)

        for ((isTable, blockXml) in topBlocks) {
            if (isTable) {
                outputLines.addAll(parseTableXml(blockXml))
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

    private fun parseTableXml(tblXml: String): List<String> {
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

                val pBlocks = extractCellParagraphBlocks(tcXml)
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
        // Include learner activities cleanly under teacher/didactic activities so they are never lost or unclassified
        combinedActivities.addAll(learnerLines)
        if (combinedActivities.isNotEmpty()) {
            result.add("أنشطة المدرس:")
            combinedActivities.forEach { result.add(it) }
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

        if (!cols.containsKey(TableColType.TEACHER) && !cols.containsKey(TableColType.LEARNER)) {
            val actHit = colHeaders.indices.firstOrNull { idx ->
                idx !in cols.values && (colHeaders[idx].contains("الانشطه") || colHeaders[idx].contains("التعليميه"))
            }
            if (actHit != null) {
                cols[TableColType.ACTIVITIES] = actHit
            }
        }

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
     * Extracts paragraph blocks from a <w:tc> (ignoring nested tables), grouping paragraphs separated by blank lines
     * and re-joining single-word broken lines (matching reflow_blocks in reference Python scripts).
     */
    private fun extractCellParagraphBlocks(tcXml: String): List<List<String>> {
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
     * Extracts both direct paragraphs and any nested <w:tbl> tables inside a <w:tc>.
     * Nested table rows are formatted as `[جدول: خلية1 | خلية2 | ...]` so they stay inside the stage's content cell
     * and can be rendered as actual nested Word tables in DocxGenerator and LiveWordPreviewScreen.
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
                val pBlocks = extractCellParagraphBlocks(tc)
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
