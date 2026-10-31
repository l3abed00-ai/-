package com.example.python

import android.content.Context
import com.example.model.ClassificationSource
import com.example.model.DocxTemplateArchive
import com.example.model.FieldSource
import com.example.model.JadhadhaInfo
import com.example.model.JadhadhaPlan
import com.example.model.PedagogicalComponent
import com.example.model.StageCatalog
import com.example.model.StageData
import com.example.model.StructuredTable
import com.example.model.StructuredTableCell
import com.example.model.StructuredTableRow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

object PythonToolkit {

    /**
     * Converts a [JadhadhaPlan] to a comprehensive, lossless JSON string containing
     * all metadata, competencies, 4 sessions / stages, teacher & learner activities,
     * educational content, evaluations, structured tables (with nested cells and merges),
     * classification sources, review statuses, and unclassified items.
     */
    fun planToJsonString(plan: JadhadhaPlan): String {
        val root = JSONObject()
        root.put("id", plan.id)
        root.put("topic", plan.topic)
        root.put("component", plan.componentType.id)
        root.put("componentTitle", plan.componentType.title)
        root.put("category", plan.category)
        root.put("createdAt", plan.createdAt)

        val info = JSONObject().apply {
            put("level", plan.info.level)
            put("unit", plan.info.unit)
            put("domain", plan.info.domain)
            put("component", plan.info.component)
            put("duration", plan.info.duration)
            put("materials", plan.info.materials)
            put("reference", plan.info.reference)
            put("teacherName", plan.info.teacherName)
            put("schoolName", plan.info.schoolName)
            put("subject", "اللغة العربية")

            val comps = JSONObject().apply {
                put("communicative", plan.info.communicativeComp)
                put("methodological", plan.info.methodologicalComp)
                put("cultural", plan.info.culturalComp)
            }
            put("comps", comps)
        }
        root.put("info", info)

        val stages = JSONObject()
        plan.stages.forEach { (key, data) ->
            val meta = StageCatalog.getByKey(key)
            val stageObj = JSONObject().apply {
                put("key", key)
                put("title", meta?.title ?: key)
                put("session", meta?.session ?: 1)
                put("sessionName", meta?.sessionName ?: "")
                put("description", meta?.description ?: "")
                put("eval", JSONArray(data.eval))
                put("content", JSONArray(data.content))
                put("teacher", JSONArray(data.teacher))
                put("learner", JSONArray(data.learner))
                put("abil", JSONArray(data.abil))

                // Sources & Classification Sources
                if (data.sources.isNotEmpty()) {
                    val sourcesObj = JSONObject()
                    data.sources.forEach { (f, s) -> sourcesObj.put(f, s.name) }
                    put("sources", sourcesObj)
                }
                if (data.classificationSources.isNotEmpty()) {
                    val classSourcesObj = JSONObject()
                    data.classificationSources.forEach { (f, cs) -> classSourcesObj.put(f, cs.name) }
                    put("classificationSources", classSourcesObj)
                }

                // Structured Tables (including nested tables)
                val effectiveTbls = data.effectiveTables(key)
                if (effectiveTbls.isNotEmpty()) {
                    put("tables", structuredTablesToJson(effectiveTbls))
                }
            }
            stages.put(key, stageObj)
        }
        root.put("stages", stages)

        if (plan.customTables.isNotEmpty()) {
            root.put("customTables", structuredTablesToJson(plan.customTables))
        }
        if (plan.extraParagraphs.isNotEmpty()) {
            root.put("extraParagraphs", JSONArray(plan.extraParagraphs))
        }
        if (plan.unclassifiedItems.isNotEmpty()) {
            root.put("unclassifiedItems", JSONArray(plan.unclassifiedItems))
        }

        plan.templateArchive?.let { archive ->
            val archObj = JSONObject().apply {
                put("documentRootAttributes", archive.documentRootAttributes)
                put("preservedSectPrXml", archive.preservedSectPrXml ?: "")
                put("primaryFontName", archive.primaryFontName ?: "")
                put("primaryFontSizeHalfPt", archive.primaryFontSizeHalfPt ?: 20)
            }
            root.put("templateArchiveMeta", archObj)
        }

        return root.toString(2)
    }

    /**
     * Parses a JSON string back into a full [JadhadhaPlan], restoring all metadata,
     * stages, activities, tables, nested tables, classification sources, and unclassified items.
     */
    fun jsonStringToPlan(jsonString: String): JadhadhaPlan {
        val root = JSONObject(jsonString)
        val topic = root.optString("topic", "").trim()
        if (topic.isEmpty()) {
            throw IllegalArgumentException("خطأ: حقل الموضوع (topic) مفقود في ملف JSON.")
        }
        val compId = root.optString("component", "reading")
        val compType = when (compId.lowercase()) {
            "language" -> PedagogicalComponent.LANGUAGE
            "expression" -> PedagogicalComponent.EXPRESSION
            else -> PedagogicalComponent.READING
        }
        val id = root.optString("id", java.util.UUID.randomUUID().toString())
        val category = root.optString("category", compType.title)
        val createdAt = root.optLong("createdAt", System.currentTimeMillis())

        val infoObj = root.optJSONObject("info") ?: JSONObject()
        val compsObj = infoObj.optJSONObject("comps") ?: JSONObject()
        val info = JadhadhaInfo(
            level = infoObj.optString("level", "السنة الثالثة إعدادي"),
            unit = infoObj.optString("unit", "الثانية"),
            domain = infoObj.optString("domain", "القيم الوطنية والإنسانية"),
            component = infoObj.optString("component", compType.title),
            duration = infoObj.optString("duration", compType.defaultDuration),
            materials = infoObj.optString("materials", compType.defaultMaterials),
            reference = infoObj.optString("reference", "المختار في اللغة العربية"),
            teacherName = infoObj.optString("teacherName", "محمد العابد"),
            schoolName = infoObj.optString("schoolName", "مدرسة أنوال (السلك الإعدادي)"),
            communicativeComp = compsObj.optString("communicative", ""),
            methodologicalComp = compsObj.optString("methodological", ""),
            culturalComp = compsObj.optString("cultural", "")
        )

        val stagesMap = mutableMapOf<String, StageData>()
        val stagesObj = root.optJSONObject("stages")
        if (stagesObj != null) {
            val keys = stagesObj.keys()
            while (keys.hasNext()) {
                val stageKey = keys.next()
                val sObj = stagesObj.getJSONObject(stageKey)
                val eval = jsonArrayToStringList(sObj.optJSONArray("eval"))
                val content = jsonArrayToStringList(sObj.optJSONArray("content"))
                val teacher = jsonArrayToStringList(sObj.optJSONArray("teacher"))
                val learner = jsonArrayToStringList(sObj.optJSONArray("learner"))
                val abil = jsonArrayToStringList(sObj.optJSONArray("abil"))

                val sources = mutableMapOf<String, FieldSource>()
                sObj.optJSONObject("sources")?.let { srcObj ->
                    val srcKeys = srcObj.keys()
                    while (srcKeys.hasNext()) {
                        val f = srcKeys.next()
                        FieldSource.fromString(srcObj.optString(f))?.let { sources[f] = it }
                    }
                }

                val classSources = mutableMapOf<String, ClassificationSource>()
                sObj.optJSONObject("classificationSources")?.let { csObj ->
                    val csKeys = csObj.keys()
                    while (csKeys.hasNext()) {
                        val f = csKeys.next()
                        ClassificationSource.fromString(csObj.optString(f))?.let { classSources[f] = it }
                    }
                }

                val tablesList = parseStructuredTablesFromJson(sObj.optJSONArray("tables"))

                stagesMap[stageKey] = StageData(
                    eval = eval,
                    content = content,
                    teacher = teacher,
                    abil = abil,
                    learner = learner,
                    sources = sources,
                    classificationSources = classSources,
                    tables = tablesList
                )
            }
        }

        val customTables = parseStructuredTablesFromJson(root.optJSONArray("customTables"))
        val extraParagraphs = jsonArrayToStringList(root.optJSONArray("extraParagraphs"))
        val unclassifiedItems = jsonArrayToStringList(root.optJSONArray("unclassifiedItems"))

        val templateArchive = root.optJSONObject("templateArchiveMeta")?.let { archObj ->
            DocxTemplateArchive(
                documentRootAttributes = archObj.optString("documentRootAttributes", ""),
                preservedSectPrXml = archObj.optString("preservedSectPrXml").takeIf { it.isNotBlank() },
                primaryFontName = archObj.optString("primaryFontName").takeIf { it.isNotBlank() },
                primaryFontSizeHalfPt = archObj.optInt("primaryFontSizeHalfPt", 20)
            )
        }

        return JadhadhaPlan(
            id = id,
            topic = topic,
            componentType = compType,
            category = category,
            info = info,
            stages = stagesMap,
            createdAt = createdAt,
            customTables = customTables,
            extraParagraphs = extraParagraphs,
            unclassifiedItems = unclassifiedItems,
            templateArchive = templateArchive
        )
    }

    /**
     * Validates JSON content before attempting to pass it to Python or generate DOCX.
     * Returns an empty list if valid, or a list of specific human-readable error descriptions.
     */
    fun validatePlanJson(jsonString: String): List<String> {
        val errors = mutableListOf<String>()
        val root = try {
            JSONObject(jsonString)
        } catch (e: Exception) {
            return listOf("خطأ في بنية ملف JSON (فشل التحليل النحوي): ${e.message}")
        }

        val topic = root.optString("topic", "").trim()
        if (topic.isEmpty()) {
            errors.add("حقل موضوع الجذاذة (topic) مفقود أو فارغ.")
        }

        val component = root.optString("component", "").trim().lowercase()
        if (component !in listOf("reading", "language", "expression")) {
            errors.add("نوع المكون التعليمي (component) غير صالح: '$component'. المكونات المدعومة: reading, language, expression.")
        }

        val info = root.optJSONObject("info")
        if (info == null) {
            errors.add("كتلة معلومات الجذاذة (info) مفقودة بالكامل.")
        } else {
            if (info.optString("teacherName", "").isBlank()) {
                errors.add("اسم الأستاذ غير محدد في بيانات الترويسة.")
            }
            if (info.optString("schoolName", "").isBlank()) {
                errors.add("اسم المؤسسة غير محدد في بيانات الترويسة.")
            }
        }

        val stages = root.optJSONObject("stages")
        if (stages == null || stages.length() == 0) {
            errors.add("كتلة مراحل الجذاذة (stages) مفقودة أو فارغة.")
        }

        return errors
    }

    private fun jsonArrayToStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            list.add(arr.optString(i, ""))
        }
        return list
    }

    private fun structuredTablesToJson(tables: List<StructuredTable>): JSONArray {
        val arr = JSONArray()
        for (tbl in tables) {
            val tblObj = JSONObject()
            tblObj.put("title", tbl.title)
            tblObj.put("isRtl", tbl.isRtl)
            tblObj.put("hasBorders", tbl.hasBorders)
            tblObj.put("borderColorHex", tbl.borderColorHex)
            tblObj.put("borderSizeEighthsPt", tbl.borderSizeEighthsPt)
            tblObj.put("sourceStageKey", tbl.sourceStageKey ?: "")
            tblObj.put("sourceField", tbl.sourceField)
            if (tbl.columnWidthsDxa.isNotEmpty()) {
                tblObj.put("columnWidthsDxa", JSONArray(tbl.columnWidthsDxa))
            }
            val rowsArr = JSONArray()
            for (row in tbl.rows) {
                val rowObj = JSONObject()
                rowObj.put("isHeader", row.isHeader)
                val cellsArr = JSONArray()
                for (cell in row.cells) {
                    val cellObj = JSONObject()
                    cellObj.put("text", cell.text)
                    cellObj.put("paragraphs", JSONArray(cell.paragraphs))
                    cellObj.put("gridSpan", cell.gridSpan)
                    if (cell.vMerge != null) cellObj.put("vMerge", cell.vMerge)
                    if (cell.widthDxa != null) cellObj.put("widthDxa", cell.widthDxa)
                    if (cell.backgroundHex != null) cellObj.put("backgroundHex", cell.backgroundHex)
                    if (cell.horizontalAlignment != null) cellObj.put("horizontalAlignment", cell.horizontalAlignment)
                    if (cell.verticalAlignment != null) cellObj.put("verticalAlignment", cell.verticalAlignment)
                    cellObj.put("isHeader", cell.isHeader)
                    cellObj.put("bold", cell.bold)
                    if (cell.nestedTables.isNotEmpty()) {
                        cellObj.put("nestedTables", structuredTablesToJson(cell.nestedTables))
                    }
                    cellsArr.put(cellObj)
                }
                rowObj.put("cells", cellsArr)
                rowsArr.put(rowObj)
            }
            tblObj.put("rows", rowsArr)
            arr.put(tblObj)
        }
        return arr
    }

    private fun parseStructuredTablesFromJson(arr: JSONArray?): List<StructuredTable> {
        if (arr == null) return emptyList()
        val tables = mutableListOf<StructuredTable>()
        for (i in 0 until arr.length()) {
            val tblObj = arr.getJSONObject(i)
            val title = tblObj.optString("title", "")
            val isRtl = tblObj.optBoolean("isRtl", true)
            val hasBorders = tblObj.optBoolean("hasBorders", true)
            val borderColorHex = tblObj.optString("borderColorHex", "000000")
            val borderSizeEighthsPt = tblObj.optInt("borderSizeEighthsPt", 4)
            val sourceStageKey = tblObj.optString("sourceStageKey").takeIf { it.isNotBlank() }
            val sourceField = tblObj.optString("sourceField", "content")

            val colWidths = mutableListOf<Int>()
            tblObj.optJSONArray("columnWidthsDxa")?.let { wArr ->
                for (wIdx in 0 until wArr.length()) colWidths.add(wArr.getInt(wIdx))
            }

            val rowsList = mutableListOf<StructuredTableRow>()
            val rowsArr = tblObj.optJSONArray("rows")
            if (rowsArr != null) {
                for (rIdx in 0 until rowsArr.length()) {
                    val rObj = rowsArr.getJSONObject(rIdx)
                    val isRowHeader = rObj.optBoolean("isHeader", false)
                    val cellsList = mutableListOf<StructuredTableCell>()
                    val cellsArr = rObj.optJSONArray("cells")
                    if (cellsArr != null) {
                        for (cIdx in 0 until cellsArr.length()) {
                            val cObj = cellsArr.getJSONObject(cIdx)
                            val paragraphs = jsonArrayToStringList(cObj.optJSONArray("paragraphs"))
                            val text = cObj.optString("text", paragraphs.joinToString("\n"))
                            val gridSpan = cObj.optInt("gridSpan", 1)
                            val vMerge = cObj.optString("vMerge").takeIf { it.isNotBlank() }
                            val widthDxa = if (cObj.has("widthDxa")) cObj.getInt("widthDxa") else null
                            val bgHex = cObj.optString("backgroundHex").takeIf { it.isNotBlank() }
                            val hAlign = cObj.optString("horizontalAlignment").takeIf { it.isNotBlank() }
                            val vAlign = cObj.optString("verticalAlignment").takeIf { it.isNotBlank() }
                            val isCellHeader = cObj.optBoolean("isHeader", isRowHeader)
                            val bold = cObj.optBoolean("bold", true)
                            val nestedTbls = parseStructuredTablesFromJson(cObj.optJSONArray("nestedTables"))

                            cellsList.add(
                                StructuredTableCell(
                                    paragraphs = if (paragraphs.isNotEmpty()) paragraphs else listOf(text),
                                    gridSpan = gridSpan,
                                    vMerge = vMerge,
                                    widthDxa = widthDxa,
                                    backgroundHex = bgHex,
                                    horizontalAlignment = hAlign,
                                    verticalAlignment = vAlign,
                                    isHeader = isCellHeader,
                                    bold = bold,
                                    nestedTables = nestedTbls
                                )
                            )
                        }
                    }
                    rowsList.add(StructuredTableRow(cells = cellsList, isHeader = isRowHeader))
                }
            }

            tables.add(
                StructuredTable(
                    title = title,
                    rows = rowsList,
                    columnWidthsDxa = colWidths,
                    isRtl = isRtl,
                    hasBorders = hasBorders,
                    borderColorHex = borderColorHex,
                    borderSizeEighthsPt = borderSizeEighthsPt,
                    sourceStageKey = sourceStageKey,
                    sourceField = sourceField
                )
            )
        }
        return tables
    }

    fun exportJsonFile(context: Context, plan: JadhadhaPlan): File {
        val safeTitle = plan.topic.replace(Regex("[^\\w\\p{L}]+"), "_").take(40).ifEmpty { "plan" }
        val outDir = File(context.cacheDir, "python_exports").apply { mkdirs() }
        val file = File(outDir, "${safeTitle}.json")
        file.writeText(planToJsonString(plan), StandardCharsets.UTF_8)
        return file
    }

    fun exportPythonScript(context: Context, component: PedagogicalComponent = PedagogicalComponent.READING): File {
        val outDir = File(context.cacheDir, "python_exports").apply { mkdirs() }
        val (fileName, scriptContent) = when (component) {
            PedagogicalComponent.READING -> "jadhadha_build.py" to GET_READING_PYTHON_SCRIPT()
            PedagogicalComponent.LANGUAGE -> "build_jadhadha.py" to GET_LANGUAGE_PYTHON_SCRIPT()
            PedagogicalComponent.EXPRESSION -> "build_insha.py" to GET_EXPRESSION_PYTHON_SCRIPT()
        }
        val file = File(outDir, fileName)
        file.writeText(scriptContent, StandardCharsets.UTF_8)
        return file
    }

    fun getScriptForComponent(component: PedagogicalComponent): String = when (component) {
        PedagogicalComponent.READING -> GET_READING_PYTHON_SCRIPT()
        PedagogicalComponent.LANGUAGE -> GET_LANGUAGE_PYTHON_SCRIPT()
        PedagogicalComponent.EXPRESSION -> GET_EXPRESSION_PYTHON_SCRIPT()
    }

    fun GET_PYTHON_SCRIPT(): String = GET_READING_PYTHON_SCRIPT()

    /**
     * Executes a generated Python script with a given JSON file using python3.
     * Returns a pair of (exitCode, outputOrGeneratedDocxFile).
     */
    fun runPythonScript(scriptFile: File, jsonFile: File, outputDir: File): Pair<Int, String> {
        outputDir.mkdirs()
        val cmd = listOf("python3", scriptFile.absolutePath, "--from-json", jsonFile.absolutePath, "--out", outputDir.absolutePath)
        val process = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()

        val output = StringBuilder()
        BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).use { reader ->
            var line: String? = reader.readLine()
            while (line != null) {
                output.append(line).append("\n")
                line = reader.readLine()
            }
        }
        val exitCode = process.waitFor()
        return exitCode to output.toString()
    }

    // ────────────────────── Common Python python-docx Builder Engine ──────────────────────
    private val COMMON_PYTHON_DOCX_BUILDER_HELPERS = """
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Inches, Pt, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH

def set_table_rtl(table):
    tblPr = table._tbl.tblPr
    bidi = OxmlElement('w:bidiVisual')
    tblPr.append(bidi)

def set_cell_background(cell, hex_color):
    tcPr = cell._tc.get_or_add_tcPr()
    shd = OxmlElement('w:shd')
    shd.set(qn('w:val'), 'clear')
    shd.set(qn('w:color'), 'auto')
    shd.set(qn('w:fill'), hex_color.replace('#', ''))
    tcPr.append(shd)

def set_table_borders(table, color="000000", sz="4"):
    tblPr = table._tbl.tblPr
    tblBorders = OxmlElement('w:tblBorders')
    for b_name in ['top', 'left', 'bottom', 'right', 'insideH', 'insideV']:
        b = OxmlElement(f'w:{b_name}')
        b.set(qn('w:val'), 'single')
        b.set(qn('w:sz'), sz)
        b.set(qn('w:space'), '0')
        b.set(qn('w:color'), color)
        tblBorders.append(b)
    tblPr.append(tblBorders)

def format_cell_text(cell, lines, font_name="Sakkal Majalla", size_pt=10, bold=False, align="right", bg_color=None):
    if bg_color:
        set_cell_background(cell, bg_color)
    cell.text = ""
    effective_lines = [l for l in lines if str(l).strip() != ""]
    if not effective_lines:
        effective_lines = [""]
    for idx, line in enumerate(effective_lines):
        p = cell.paragraphs[0] if idx == 0 else cell.add_paragraph()
        p.paragraph_format.space_before = Pt(1)
        p.paragraph_format.space_after = Pt(1)
        p.paragraph_format.line_spacing = 1.15
        if align == "center":
            p.paragraph_format.alignment = WD_ALIGN_PARAGRAPH.CENTER
        elif align == "both":
            p.paragraph_format.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
        else:
            p.paragraph_format.alignment = WD_ALIGN_PARAGRAPH.RIGHT
        pPr = p._p.get_or_add_pPr()
        pPr.append(OxmlElement('w:bidi'))
        run = p.add_run(str(line))
        run.font.name = font_name
        run.font.size = Pt(size_pt)
        run.bold = bold
        rPr = run._r.get_or_add_rPr()
        rPr.append(OxmlElement('w:rtl'))

def append_nested_structured_table(cell, tbl_data, font_name="Sakkal Majalla"):
    rows_data = tbl_data.get("rows", [])
    if not rows_data:
        return
    max_cols = max(len(r.get("cells", [])) for r in rows_data)
    if max_cols == 0:
        return
    tbl = cell.add_table(rows=len(rows_data), cols=max_cols)
    set_table_rtl(tbl)
    set_table_borders(tbl, color="404040", sz="4")
    for r_idx, r in enumerate(rows_data):
        is_h = r.get("isHeader", False) or (r_idx == 0)
        c_list = r.get("cells", [])
        for c_idx in range(max_cols):
            sub_cell = tbl.rows[r_idx].cells[c_idx]
            if c_idx < len(c_list):
                c = c_list[c_idx]
                lines = c.get("paragraphs", [c.get("text", "")])
                c_bold = c.get("bold", is_h)
                bg = "F2F2F2" if is_h else None
                format_cell_text(sub_cell, lines, font_name=font_name, size_pt=9, bold=c_bold, align="center" if is_h else "right", bg_color=bg)
            else:
                format_cell_text(sub_cell, [""], font_name=font_name, size_pt=9, bg_color="F2F2F2" if is_h else None)
    # MANDATORY OPENXML RULE: Every cell containing a nested table MUST end with a paragraph
    trailing_p = cell.add_paragraph()
    trailing_p.paragraph_format.space_before = Pt(0)
    trailing_p.paragraph_format.space_after = Pt(0)

def append_header_table(doc, data, component_title=None):
    info = data.get("info", {})
    comps = info.get("comps", {})
    topic = data.get("topic", "")
    comp_name = component_title or info.get("component", "مادة اللغة العربية")
    tbl = doc.add_table(rows=7, cols=4)
    set_table_rtl(tbl)
    set_table_borders(tbl, color="000000", sz="4")
    rows_spec = [
        ("الوحدة :", info.get("unit", "الثانية"), "الفئة المستهدفة :", info.get("level", "السنة الثالثة إعدادي")),
        ("المجال :", info.get("domain", "القيم الوطنية والإنسانية"), "الوسائل التعليمية :", info.get("materials", "السبورة - كتاب المتعلم - الدفتر")),
        ("المكون :", comp_name, "الكفايات المستهدفة / الأهداف التعلمية:", ""),
        ("الموضوع :", topic, "- كفاية تواصلية: " + comps.get("communicative", ""), ""),
        ("مدة الإنجاز :", info.get("duration", "ساعتان"), "- كفاية منهجية: " + comps.get("methodological", ""), ""),
        ("المرجع :", info.get("reference", "المختار في اللغة العربية"), "- كفاية معرفية/ثقافية: " + comps.get("cultural", ""), ""),
        ("الأستاذ :", info.get("teacherName", "محمد العابد"), "المؤسسة :", info.get("schoolName", "ثانوية أنوال الإعدادية"))
    ]
    for r_idx, (c0, c1, c2, c3) in enumerate(rows_spec):
        row_cells = tbl.rows[r_idx].cells
        format_cell_text(row_cells[0], [c0], bold=True, align="right", bg_color="F2F2F2")
        format_cell_text(row_cells[1], [c1], bold=False, align="right")
        if r_idx in (2, 3, 4, 5):
            row_cells[2].merge(row_cells[3])
            is_hdr = (r_idx == 2)
            format_cell_text(row_cells[2], [c2], bold=is_hdr, align="right", bg_color="F2F2F2" if is_hdr else None)
        else:
            format_cell_text(row_cells[2], [c2], bold=True, align="right", bg_color="F2F2F2")
            format_cell_text(row_cells[3], [c3], bold=False, align="right")

def append_stages_table(doc, stages_dict, stage_keys, font_name="Sakkal Majalla"):
    tbl = doc.add_table(rows=1, cols=5)
    set_table_rtl(tbl)
    set_table_borders(tbl, color="000000", sz="4")
    headers = ["المراحل والخطوات", "القدرات والمهارات", "الأنشطة التعليمية التعلمية", "المحتوى الديدكتيكي", "التقويم والدعم"]
    for idx, h in enumerate(headers):
        format_cell_text(tbl.rows[0].cells[idx], [h], font_name=font_name, size_pt=10, bold=True, align="center", bg_color="F2F2F2")
    for key in stage_keys:
        st = stages_dict.get(key, {})
        row_cells = tbl.add_row().cells
        stage_title = st.get("title", key)
        session_name = st.get("sessionName", "")
        col0_lines = [stage_title]
        if session_name and not session_name.startswith("حصة"):
            col0_lines.append(f"({session_name})")
        format_cell_text(row_cells[0], col0_lines, font_name=font_name, size_pt=9, bold=True, align="center", bg_color="F9F9F9")
        format_cell_text(row_cells[1], st.get("abil", []), font_name=font_name, size_pt=9, bold=False, align="right")
        t_acts = st.get("teacher", [])
        l_acts = st.get("learner", [])
        acts_lines = []
        if t_acts:
            acts_lines.append("أنشطة الأستاذ:")
            acts_lines.extend(t_acts)
        if l_acts:
            if acts_lines:
                acts_lines.append("")
            acts_lines.append("أنشطة المتعلمين:")
            acts_lines.extend(l_acts)
        if not acts_lines:
            acts_lines = ["- يوجه الأستاذ المتعلمين لإنجاز أنشطة المرحلة ويقوم بتصويب التعثرات."]
        format_cell_text(row_cells[2], acts_lines, font_name=font_name, size_pt=9, bold=False, align="right")
        content_lines = [l for l in st.get("content", []) if not str(l).strip().startswith("[جدول:")]
        format_cell_text(row_cells[3], content_lines, font_name=font_name, size_pt=9, bold=False, align="right")
        nested_tables = st.get("tables", [])
        for nested in nested_tables:
            append_nested_structured_table(row_cells[3], nested, font_name=font_name)
        format_cell_text(row_cells[4], st.get("eval", []), font_name=font_name, size_pt=9, bold=False, align="right")
"""

    fun GET_READING_PYTHON_SCRIPT(): String = """#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# jadhadha_build.py — مولد جذاذات النصوص القرائية الرسمية (حصتان)
import sys, os, json, argparse
from pathlib import Path

try:
    from docx import Document
    from docx.shared import Inches, Pt
except ImportError:
    sys.exit("المكتبة python-docx غير مثبتة. نفذ الأمر: pip install python-docx")
$COMMON_PYTHON_DOCX_BUILDER_HELPERS
def validate_reading_json(data):
    if not data or not isinstance(data, dict):
        sys.exit("خطأ فادح: ملف JSON فارغ أو غير منسق بشكل سليم.")
    topic = data.get("topic", "").strip()
    if not topic:
        sys.exit("خطأ: حقل الموضوع (topic) مفقود في ملف JSON.")
    stages = data.get("stages", {})
    if not stages:
        sys.exit("خطأ: كتلة المراحل (stages) مفقودة أو فارغة.")
    return topic, stages

def build_from_json(json_path, output_dir="الجذاذات_المنجزة"):
    p = Path(json_path)
    if not p.is_file():
        sys.exit(f"خطأ: ملف JSON غير موجود في المسار: {json_path}")
    with open(p, "r", encoding="utf-8") as f:
        data = json.load(f)
    topic, stages = validate_reading_json(data)
    print(f"جارٍ بناء جذاذة النصوص القرائية «{topic}» بنظام الحصتين من ملف JSON...")
    doc = Document()
    for s in doc.sections:
        s.top_margin = Inches(0.28)
        s.bottom_margin = Inches(0.28)
        s.left_margin = Inches(0.28)
        s.right_margin = Inches(0.28)

    append_header_table(doc, data, component_title="النصوص القرائية (حصتان)")
    doc.add_paragraph()

    ordered_keys = [k for k in ["diagnostic", "observation", "comprehension", "session2_intro", "analysis", "synthesis", "investment"] if k in stages]
    for k in stages.keys():
        if k not in ordered_keys:
            ordered_keys.append(k)

    append_stages_table(doc, stages, ordered_keys)

    # Append any custom tables or extra unclassified content if present
    custom_tables = data.get("customTables", [])
    for ct in custom_tables:
        p_title = doc.add_paragraph()
        p_title.add_run(ct.get("title", "جدول إضافي")).bold = True
        cell_dummy = doc.add_table(rows=1, cols=1).rows[0].cells[0]
        append_nested_structured_table(cell_dummy, ct)

    out_dir = Path(output_dir) / "النصوص القرائية"
    out_dir.mkdir(parents=True, exist_ok=True)
    clean_title = "".join(c for c in topic if c.isalnum() or c in (' ', '_', '-')).strip().replace(' ', '_')
    out_file = out_dir / f"جذاذة_{clean_title}.docx"
    doc.save(str(out_file))
    print(f"✔ تم حفظ جذاذة النصوص القرائية بنجاح: {out_file}")
    return str(out_file)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="مولد جذاذات النصوص القرائية")
    parser.add_argument("--from-json", required=True, help="مسار ملف JSON للجذاذة")
    parser.add_argument("--out", default="الجذاذات_المنجزة", help="مجلد الإخراج")
    args = parser.parse_args()
    build_from_json(args.from_json, args.out)
"""

    fun GET_LANGUAGE_PYTHON_SCRIPT(): String = """#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# build_jadhadha.py — مولد جذاذات الدرس اللغوي الرسمية
import sys, os, json, argparse
from pathlib import Path

try:
    from docx import Document
    from docx.shared import Inches, Pt
except ImportError:
    sys.exit("المكتبة python-docx غير مثبتة. نفذ الأمر: pip install python-docx")
$COMMON_PYTHON_DOCX_BUILDER_HELPERS
def validate_language_json(data):
    if not data or not isinstance(data, dict):
        sys.exit("خطأ فادح: ملف JSON فارغ أو غير منسق بشكل سليم.")
    topic = data.get("topic", "").strip()
    if not topic:
        sys.exit("خطأ: حقل الموضوع (topic) مفقود في ملف JSON.")
    stages = data.get("stages", {})
    if not stages:
        sys.exit("خطأ: كتلة المراحل (stages) مفقودة أو فارغة.")
    return topic, stages

def build_language_plan(json_path, output_dir="الجذاذات_المنجزة"):
    p = Path(json_path)
    if not p.is_file():
        sys.exit(f"خطأ: ملف JSON غير موجود في المسار: {json_path}")
    with open(p, "r", encoding="utf-8") as f:
        data = json.load(f)
    topic, stages = validate_language_json(data)
    print(f"جارٍ بناء جذاذة الدرس اللغوي «{topic}» متضمنة الجداول التحليلية...")
    doc = Document()
    for s in doc.sections:
        s.top_margin = Inches(0.28)
        s.bottom_margin = Inches(0.28)
        s.left_margin = Inches(0.28)
        s.right_margin = Inches(0.28)

    append_header_table(doc, data, component_title="الدرس اللغوي (ساعة واحدة)")
    doc.add_paragraph()

    ordered_keys = [k for k in ["lang_diagnostic", "lang_observation", "lang_analysis", "lang_synthesis", "lang_application"] if k in stages]
    for k in stages.keys():
        if k not in ordered_keys:
            ordered_keys.append(k)

    append_stages_table(doc, stages, ordered_keys)

    out_dir = Path(output_dir) / "الدرس اللغوي"
    out_dir.mkdir(parents=True, exist_ok=True)
    clean_title = "".join(c for c in topic if c.isalnum() or c in (' ', '_', '-')).strip().replace(' ', '_')
    out_file = out_dir / f"جذاذة_{clean_title}.docx"
    doc.save(str(out_file))
    print(f"✔ تم حفظ جذاذة الدرس اللغوي بنجاح: {out_file}")
    return str(out_file)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="مولد جذاذات الدرس اللغوي")
    parser.add_argument("--from-json", required=True, help="مسار ملف JSON")
    parser.add_argument("--out", default="الجذاذات_المنجزة", help="مجلد الإخراج")
    args = parser.parse_args()
    build_language_plan(args.from_json, args.out)
"""

    fun GET_EXPRESSION_PYTHON_SCRIPT(): String = """#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# build_insha.py — مولد جذاذات التعبير والإنشاء للحصص الأربع الكاملة
import sys, os, json, argparse
from pathlib import Path

try:
    from docx import Document
    from docx.shared import Inches, Pt
except ImportError:
    sys.exit("المكتبة python-docx غير مثبتة. نفذ الأمر: pip install python-docx")
$COMMON_PYTHON_DOCX_BUILDER_HELPERS
def validate_insha_json(data):
    if not data or not isinstance(data, dict):
        sys.exit("خطأ فادح: ملف JSON فارغ أو غير منسق بشكل سليم.")
    topic = data.get("topic", "").strip()
    if not topic:
        sys.exit("خطأ: حقل الموضوع (topic) مفقود في ملف JSON.")
    stages = data.get("stages", {})
    if not stages:
        sys.exit("خطأ: كتلة المراحل (stages) مفقودة أو فارغة.")
    return topic, stages

def build_insha_plan(json_path, output_dir="الجذاذات_المنجزة"):
    p = Path(json_path)
    if not p.is_file():
        sys.exit(f"خطأ: ملف JSON غير موجود في المسار: {json_path}")
    with open(p, "r", encoding="utf-8") as f:
        data = json.load(f)
    topic, stages = validate_insha_json(data)
    print(f"جارٍ بناء جذاذة التعبير والإنشاء للحصص الأربع «{topic}»...")
    doc = Document()
    for s in doc.sections:
        s.top_margin = Inches(0.28)
        s.bottom_margin = Inches(0.28)
        s.left_margin = Inches(0.28)
        s.right_margin = Inches(0.28)

    sessions_config = [
        ("الحصة الأولى: أنشطة الاكتساب والتعرف على المهارة",
         ["insha_acq_diagnostic", "insha_acq_reading", "insha_acq_elements", "insha_acq_synthesis"]),
        ("الحصة الثانية: أنشطة التطبيق والإنتاج الموجه",
         ["insha_app_diagnostic", "insha_app_prep", "insha_app_execution", "insha_app_support"]),
        ("الحصة الثالثة: أنشطة الإنتاج والتحرير الفردي",
         ["insha_prod_diagnostic", "insha_prod_prep", "insha_prod_execution", "insha_prod_correction"]),
        ("الحصة الرابعة: أنشطة التصحيح والدعم وتصنيف التعثرات",
         ["insha_corr_diagnostic", "insha_corr_diagnosis", "insha_corr_support"])
    ]

    for s_idx, (session_title, session_keys) in enumerate(sessions_config):
        if s_idx > 0:
            doc.add_page_break()
        append_header_table(doc, data, component_title=f"التعبير والإنشاء — {session_title}")
        doc.add_paragraph()
        active_keys = [k for k in session_keys if k in stages]
        if not active_keys:
            # Fallback if custom stage keys exist
            active_keys = [k for k in stages.keys() if stages[k].get("session", 1) == (s_idx + 1)]
        append_stages_table(doc, stages, active_keys)

    out_dir = Path(output_dir) / "التعبير والإنشاء"
    out_dir.mkdir(parents=True, exist_ok=True)
    clean_title = "".join(c for c in topic if c.isalnum() or c in (' ', '_', '-')).strip().replace(' ', '_')
    out_file = out_dir / f"جذاذة_{clean_title}.docx"
    doc.save(str(out_file))
    print(f"✔ تم حفظ جذاذة التعبير والإنشاء بنجاح: {out_file}")
    return str(out_file)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="مولد جذاذات التعبير والإنشاء (الحصص الأربع)")
    parser.add_argument("--from-json", required=True, help="مسار ملف JSON")
    parser.add_argument("--out", default="الجذاذات_المنجزة", help="مجلد الإخراج")
    args = parser.parse_args()
    build_insha_plan(args.from_json, args.out)
"""
}
