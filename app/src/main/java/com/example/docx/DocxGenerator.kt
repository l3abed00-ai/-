package com.example.docx

import android.content.Context
import com.example.model.JadhadhaPlan
import com.example.model.PedagogicalComponent
import com.example.model.StageCatalog
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DocxGenerator {

    private const val FONT_NAME = "Sakkal Majalla"
    private const val FONT_SIZE_HALF_PT = 20 // 10pt (20 half-points)
    private const val FONT_TITLE_SIZE = 26 // 13pt
    private const val HEADER_BG_HEX = "E2E8F0"
    private const val STAGE_BG_HEX = "F1F5F9"
    private const val ACCENT_BG_HEX = "D1FAE5"

    fun generateDocx(context: Context, plan: JadhadhaPlan): File {
        val safeTitle = plan.topic.replace(Regex("[^\\w\\p{L}]+"), "_").take(50).ifEmpty { "jadhadha" }
        val outDir = File(context.cacheDir, "generated_plans").apply { mkdirs() }
        val outFile = File(outDir, "جذاذة_${safeTitle}.docx")

        ZipOutputStream(FileOutputStream(outFile)).use { zip ->
            addZipEntry(zip, "[Content_Types].xml", buildContentTypesXml())
            addZipEntry(zip, "_rels/.rels", buildPackageRelsXml())
            addZipEntry(zip, "word/_rels/document.xml.rels", buildDocumentRelsXml())
            addZipEntry(zip, "docProps/core.xml", buildCorePropsXml(plan.topic))
            addZipEntry(zip, "docProps/app.xml", buildAppPropsXml())
            addZipEntry(zip, "word/styles.xml", buildStylesXml())
            addZipEntry(zip, "word/document.xml", buildDocumentXml(plan))
        }

        return outFile
    }

    private fun addZipEntry(zip: ZipOutputStream, path: String, content: String) {
        val entry = ZipEntry(path)
        zip.putNextEntry(entry)
        zip.write(content.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun buildContentTypesXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
  <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
  <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
  <Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
</Types>"""

    private fun buildPackageRelsXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
</Relationships>"""

    private fun buildDocumentRelsXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    private fun buildCorePropsXml(title: String): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <dc:title>${escapeXml(title)}</dc:title>
  <dc:creator>الجذاذة النموذجية</dc:creator>
</cp:coreProperties>"""

    private fun buildAppPropsXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties">
  <Application>الجذاذة النموذجية للمنهاج المغربي</Application>
</Properties>"""

    private fun buildStylesXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:docDefaults>
    <w:rPrDefault>
      <w:rPr>
        <w:rFonts w:ascii="$FONT_NAME" w:hAnsi="$FONT_NAME" w:cs="$FONT_NAME"/>
        <w:sz w:val="$FONT_SIZE_HALF_PT"/>
        <w:szCs w:val="$FONT_SIZE_HALF_PT"/>
        <w:rtl/>
      </w:rPr>
    </w:rPrDefault>
    <w:pPrDefault>
      <w:pPr>
        <w:bidi/>
        <w:spacing w:after="0" w:line="240" w:lineRule="auto"/>
      </w:pPr>
    </w:pPrDefault>
  </w:docDefaults>
</w:styles>"""

    private fun buildDocumentXml(plan: JadhadhaPlan): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>
""")

        // 1. Header Table
        appendHeaderTable(sb, plan)

        // Spacer paragraph
        sb.append("""<w:p><w:pPr><w:bidi/><w:spacing w:after="100"/></w:pPr></w:p>""")

        // 2. Main Stages Table
        appendStagesTable(sb, plan)

        // Section properties with 0.5 in margins (720 dxa)
        sb.append("""
    <w:sectPr>
      <w:pgSz w:w="11906" w:h="16838"/>
      <w:pgMar w:top="720" w:right="720" w:bottom="720" w:left="720" w:header="360" w:footer="360" w:gutter="0"/>
      <w:bidi/>
    </w:sectPr>
  </w:body>
</w:document>""")
        return sb.toString()
    }

    private fun appendHeaderTable(sb: StringBuilder, plan: JadhadhaPlan) {
        val borders = """<w:tblBorders>
      <w:top w:val="single" w:sz="6" w:space="0" w:color="0F5132"/>
      <w:left w:val="single" w:sz="6" w:space="0" w:color="0F5132"/>
      <w:bottom w:val="single" w:sz="6" w:space="0" w:color="0F5132"/>
      <w:right w:val="single" w:sz="6" w:space="0" w:color="0F5132"/>
      <w:insideH w:val="single" w:sz="4" w:space="0" w:color="CBD5E1"/>
      <w:insideV w:val="single" w:sz="4" w:space="0" w:color="CBD5E1"/>
    </w:tblBorders>"""

        sb.append("""
    <w:tbl>
      <w:tblPr>
        <w:tblW w:w="10466" w:type="dxa"/>
        <w:jc w:val="center"/>
        <w:bidiVisual/>
        $borders
      </w:tblPr>
      <w:tblGrid>
        <w:gridCol w:w="5233"/>
        <w:gridCol w:w="5233"/>
      </w:tblGrid>
""")

        // Row 0: المستوى & الوحدة
        appendHeaderRow(sb, "المستوى الدراسي: " + plan.info.level, "الوحدة: " + plan.info.unit, bg = STAGE_BG_HEX)

        // Row 1: الوسائل & المجال
        appendHeaderRow(sb, "الوسائل الديداكتيكية: " + plan.info.materials, "المجال: " + plan.info.domain)

        // Row 2: المكون (Span 2)
        val compTitle = plan.componentType.title
        appendSpannedHeaderRow(sb, "المكوّن: $compTitle", bg = ACCENT_BG_HEX, bold = true, isCenter = true)

        // Row 3: كفاية تواصلية & موضوع الدرس
        val topicLabel = if (plan.componentType == PedagogicalComponent.EXPRESSION) "المهارة المستهدفة: " else "موضوع الدرس: "
        appendHeaderRow(
            sb,
            "• كفاية تواصلية: " + plan.info.communicativeComp,
            "$topicLabel«" + plan.topic + "»",
            bg2 = ACCENT_BG_HEX,
            bold2 = true,
            sz2 = FONT_TITLE_SIZE
        )

        // Row 4: كفاية منهجية & المدة
        appendHeaderRow(sb, "• كفاية منهجية: " + plan.info.methodologicalComp, "الغلاف الزمني: " + plan.info.duration)

        // Row 5: كفاية ثقافية (Span 2)
        appendSpannedHeaderRow(sb, "• كفاية ثقافية / معرفية: " + plan.info.culturalComp)

        sb.append("    </w:tbl>\n")
    }

    private fun appendHeaderRow(
        sb: StringBuilder,
        cell1: String,
        cell2: String,
        bg: String? = null,
        bg2: String? = null,
        bold2: Boolean = false,
        sz2: Int = FONT_SIZE_HALF_PT
    ) {
        sb.append("      <w:tr>\n")
        appendCell(sb, cell1, widthDxa = 5233, bgHex = bg, bold = false)
        appendCell(sb, cell2, widthDxa = 5233, bgHex = bg2 ?: bg, bold = bold2, sz = sz2)
        sb.append("      </w:tr>\n")
    }

    private fun appendSpannedHeaderRow(
        sb: StringBuilder,
        text: String,
        bg: String? = null,
        bold: Boolean = false,
        isCenter: Boolean = false
    ) {
        sb.append("      <w:tr>\n")
        sb.append("        <w:tc>\n")
        sb.append("          <w:tcPr>\n")
        sb.append("            <w:tcW w:w=\"10466\" w:type=\"dxa\"/>\n")
        sb.append("            <w:gridSpan w:val=\"2\"/>\n")
        if (bg != null) {
            sb.append("            <w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"$bg\"/>\n")
        }
        sb.append("          </w:tcPr>\n")
        appendParagraph(sb, text, isCenter = isCenter, bold = bold)
        sb.append("        </w:tc>\n")
        sb.append("      </w:tr>\n")
    }

    private fun appendStagesTable(sb: StringBuilder, plan: JadhadhaPlan) {
        // Table columns: (RTL order: Stage Title, Target Abilities, Teacher Activities, Content, Evaluation)
        val colWidths = listOf(1400, 1600, 2400, 3466, 1600) // Total: 10466 dxa

        val borders = """<w:tblBorders>
      <w:top w:val="single" w:sz="8" w:space="0" w:color="0F5132"/>
      <w:left w:val="single" w:sz="8" w:space="0" w:color="0F5132"/>
      <w:bottom w:val="single" w:sz="8" w:space="0" w:color="0F5132"/>
      <w:right w:val="single" w:sz="8" w:space="0" w:color="0F5132"/>
      <w:insideH w:val="single" w:sz="4" w:space="0" w:color="94A3B8"/>
      <w:insideV w:val="single" w:sz="4" w:space="0" w:color="94A3B8"/>
    </w:tblBorders>"""

        sb.append("""
    <w:tbl>
      <w:tblPr>
        <w:tblW w:w="10466" w:type="dxa"/>
        <w:jc w:val="center"/>
        <w:bidiVisual/>
        $borders
      </w:tblPr>
      <w:tblGrid>
""")
        colWidths.forEach { w -> sb.append("        <w:gridCol w:w=\"$w\"/>\n") }
        sb.append("      </w:tblGrid>\n")

        // Table Header Row
        sb.append("      <w:tr>\n")
        val headers = listOf(
            "المقاطع والمراحل",
            "القدرات المستهدفة",
            "أنشطة المدرس",
            "المحتوى الديداكتيكي والأنشطة التعلمية",
            "التقويم والدعم"
        )
        headers.forEachIndexed { idx, h ->
            appendCell(sb, h, colWidths[idx], bgHex = "047857", textColorHex = "FFFFFF", bold = true, isCenter = true)
        }
        sb.append("      </w:tr>\n")

        when (plan.componentType) {
            PedagogicalComponent.READING -> {
                // Session 1: diagnostic, observation, comprehension
                val session1Keys = listOf("diagnostic", "observation", "comprehension")
                session1Keys.forEach { key ->
                    appendStageRow(sb, key, plan, colWidths)
                }

                // Session 2 Header Banner / Page Break
                appendSessionBanner(sb, "الحصة الثانية: القراءة التحليلية والتركيب والاستثمار", pageBreak = true)

                // Session 2: analysis, synthesis, investment
                val session2Keys = listOf("analysis", "synthesis", "investment")
                session2Keys.forEach { key ->
                    appendStageRow(sb, key, plan, colWidths)
                }
            }

            PedagogicalComponent.LANGUAGE -> {
                // Language lesson stages
                val langKeys = listOf(
                    "lang_diagnostic",
                    "lang_observation",
                    "lang_analysis",
                    "lang_synthesis",
                    "lang_application"
                )
                langKeys.forEach { key ->
                    appendStageRow(sb, key, plan, colWidths)
                }
            }

            PedagogicalComponent.EXPRESSION -> {
                // Expression across 4 sessions
                // Session 1
                appendSessionBanner(sb, "الحصة الأولى: أنشطة الاكتساب (التعريف بالمهارة وخطواتها)", pageBreak = false)
                appendStageRow(sb, "insha_acquisition", plan, colWidths)

                // Session 2
                appendSessionBanner(sb, "الحصة الثانية: أنشطة التطبيق (التدرب على المهارة)", pageBreak = true)
                appendStageRow(sb, "insha_application", plan, colWidths)

                // Session 3
                appendSessionBanner(sb, "الحصة الثالثة: أنشطة الإنتاج والربط (التحرير في مسودة)", pageBreak = true)
                appendStageRow(sb, "insha_production", plan, colWidths)

                // Session 4
                appendSessionBanner(sb, "الحصة الرابعة: أنشطة التصحيح والدعم (شبكة التقويم)", pageBreak = true)
                appendStageRow(sb, "insha_correction", plan, colWidths)
            }
        }

        sb.append("    </w:tbl>\n")
    }

    private fun appendSessionBanner(sb: StringBuilder, title: String, pageBreak: Boolean) {
        sb.append("      <w:tr>\n")
        sb.append("        <w:tc>\n")
        sb.append("          <w:tcPr>\n")
        sb.append("            <w:tcW w:w=\"10466\" w:type=\"dxa\"/>\n")
        sb.append("            <w:gridSpan w:val=\"5\"/>\n")
        sb.append("            <w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"065F46\"/>\n")
        sb.append("          </w:tcPr>\n")
        sb.append("          <w:p>\n")
        sb.append("            <w:pPr>\n")
        if (pageBreak) {
            sb.append("              <w:pageBreakBefore/>\n")
        }
        sb.append("              <w:bidi/>\n")
        sb.append("              <w:jc w:val=\"center\"/>\n")
        sb.append("              <w:spacing w:before=\"80\" w:after=\"80\"/>\n")
        sb.append("            </w:pPr>\n")
        sb.append("            <w:r>\n")
        sb.append("              <w:rPr>\n")
        sb.append("                <w:rFonts w:ascii=\"$FONT_NAME\" w:hAnsi=\"$FONT_NAME\" w:cs=\"$FONT_NAME\"/>\n")
        sb.append("                <w:b/><w:bCs/>\n")
        sb.append("                <w:color w:val=\"FFFFFF\"/>\n")
        sb.append("                <w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/>\n")
        sb.append("                <w:rtl/>\n")
        sb.append("              </w:rPr>\n")
        sb.append("              <w:t>${escapeXml(title)}</w:t>\n")
        sb.append("            </w:r>\n")
        sb.append("          </w:p>\n")
        sb.append("        </w:tc>\n")
        sb.append("      </w:tr>\n")
    }

    private fun appendStageRow(sb: StringBuilder, key: String, plan: JadhadhaPlan, widths: List<Int>) {
        val stageMeta = StageCatalog.getByKey(key)
        val stageTitle = stageMeta?.title ?: key
        val data = plan.stages[key] ?: com.example.model.StageData()

        sb.append("      <w:tr>\n")

        // 1. Stage Name
        appendCell(sb, stageTitle, widths[0], bgHex = STAGE_BG_HEX, bold = true, isCenter = true)

        // 2. Abilities (prefixed with - )
        val abilItems = data.abil.map { formatItem(it, "- ") }
        appendCellWithLines(sb, abilItems, widths[1], isCenter = false)

        // 3. Teacher Activities (prefixed with + )
        val teacherItems = data.teacher.map { formatItem(it, "+ ") }
        appendCellWithLines(sb, teacherItems, widths[2], isCenter = false)

        // 4. Content (with underlined label if title: rest)
        appendContentCell(sb, data.content, widths[3])

        // 5. Evaluation (prefixed with + )
        val evalItems = data.eval.map { formatItem(it, "+ ") }
        appendCellWithLines(sb, evalItems, widths[4], isCenter = false)

        sb.append("      </w:tr>\n")
    }

    private fun formatItem(text: String, defaultPrefix: String): String {
        val cleaned = text.trim().removePrefix("+ ").removePrefix("- ").removePrefix("• ").trim()
        return if (cleaned.startsWith("-") || cleaned.startsWith("+")) cleaned else "$defaultPrefix$cleaned"
    }

    private fun appendCell(
        sb: StringBuilder,
        text: String,
        widthDxa: Int,
        bgHex: String? = null,
        textColorHex: String = "000000",
        bold: Boolean = false,
        isCenter: Boolean = false,
        sz: Int = FONT_SIZE_HALF_PT
    ) {
        sb.append("        <w:tc>\n")
        sb.append("          <w:tcPr>\n")
        sb.append("            <w:tcW w:w=\"$widthDxa\" w:type=\"dxa\"/>\n")
        if (bgHex != null) {
            sb.append("            <w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"$bgHex\"/>\n")
        }
        sb.append("            <w:tcMar><w:top w:w=\"80\" w:type=\"dxa\"/><w:bottom w:w=\"80\" w:type=\"dxa\"/><w:left w:w=\"100\" w:type=\"dxa\"/><w:right w:w=\"100\" w:type=\"dxa\"/></w:tcMar>\n")
        sb.append("          </w:tcPr>\n")
        appendParagraph(sb, text, isCenter = isCenter, bold = bold, textColorHex = textColorHex, sz = sz)
        sb.append("        </w:tc>\n")
    }

    private fun appendCellWithLines(
        sb: StringBuilder,
        lines: List<String>,
        widthDxa: Int,
        isCenter: Boolean = false
    ) {
        sb.append("        <w:tc>\n")
        sb.append("          <w:tcPr>\n")
        sb.append("            <w:tcW w:w=\"$widthDxa\" w:type=\"dxa\"/>\n")
        sb.append("            <w:tcMar><w:top w:w=\"60\" w:type=\"dxa\"/><w:bottom w:w=\"60\" w:type=\"dxa\"/><w:left w:w=\"80\" w:type=\"dxa\"/><w:right w:w=\"80\" w:type=\"dxa\"/></w:tcMar>\n")
        sb.append("          </w:tcPr>\n")

        if (lines.isEmpty()) {
            appendParagraph(sb, "", isCenter = isCenter)
        } else {
            lines.forEach { line ->
                appendParagraph(sb, line, isCenter = isCenter)
            }
        }
        sb.append("        </w:tc>\n")
    }

    private fun appendContentCell(sb: StringBuilder, lines: List<String>, widthDxa: Int) {
        sb.append("        <w:tc>\n")
        sb.append("          <w:tcPr>\n")
        sb.append("            <w:tcW w:w=\"$widthDxa\" w:type=\"dxa\"/>\n")
        sb.append("            <w:tcMar><w:top w:w=\"60\" w:type=\"dxa\"/><w:bottom w:w=\"60\" w:type=\"dxa\"/><w:left w:w=\"80\" w:type=\"dxa\"/><w:right w:w=\"80\" w:type=\"dxa\"/></w:tcMar>\n")
        sb.append("          </w:tcPr>\n")

        if (lines.isEmpty()) {
            appendParagraph(sb, "")
        } else {
            lines.forEach { line ->
                val cleanLine = line.trim().removePrefix("- ").removePrefix("• ").trim()
                val colonIdx = cleanLine.indexOf(':')
                if (colonIdx in 1..40 && !cleanLine.startsWith("http")) {
                    val label = cleanLine.substring(0, colonIdx + 1).replace("<u>", "").replace("</u>", "").trim()
                    val rest = cleanLine.substring(colonIdx + 1).replace("<u>", "").replace("</u>", "").trim()
                    appendUnderlinedParagraph(sb, label, rest)
                } else {
                    val contentText = if (cleanLine.startsWith("-")) cleanLine else "- $cleanLine"
                    appendParagraph(sb, contentText)
                }
            }
        }
        sb.append("        </w:tc>\n")
    }

    private fun appendParagraph(
        sb: StringBuilder,
        text: String,
        isCenter: Boolean = false,
        bold: Boolean = false,
        textColorHex: String = "000000",
        sz: Int = FONT_SIZE_HALF_PT
    ) {
        val jc = if (isCenter) "center" else "both"
        sb.append("          <w:p>\n")
        sb.append("            <w:pPr>\n")
        sb.append("              <w:bidi/>\n")
        sb.append("              <w:jc w:val=\"$jc\"/>\n")
        sb.append("              <w:spacing w:after=\"40\" w:line=\"220\" w:lineRule=\"auto\"/>\n")
        sb.append("            </w:pPr>\n")
        if (text.isNotEmpty()) {
            sb.append("            <w:r>\n")
            sb.append("              <w:rPr>\n")
            sb.append("                <w:rFonts w:ascii=\"$FONT_NAME\" w:hAnsi=\"$FONT_NAME\" w:cs=\"$FONT_NAME\"/>\n")
            if (bold) sb.append("                <w:b/><w:bCs/>\n")
            if (textColorHex != "000000") sb.append("                <w:color w:val=\"$textColorHex\"/>\n")
            sb.append("                <w:sz w:val=\"$sz\"/><w:szCs w:val=\"$sz\"/>\n")
            sb.append("                <w:rtl/>\n")
            sb.append("              </w:rPr>\n")
            sb.append("              <w:t xml:space=\"preserve\">${escapeXml(text)}</w:t>\n")
            sb.append("            </w:r>\n")
        }
        sb.append("          </w:p>\n")
    }

    private fun appendUnderlinedParagraph(sb: StringBuilder, label: String, rest: String) {
        sb.append("          <w:p>\n")
        sb.append("            <w:pPr>\n")
        sb.append("              <w:bidi/>\n")
        sb.append("              <w:jc w:val=\"both\"/>\n")
        sb.append("              <w:spacing w:after=\"40\" w:line=\"220\" w:lineRule=\"auto\"/>\n")
        sb.append("            </w:pPr>\n")
        sb.append("            <w:r>\n")
        sb.append("              <w:rPr>\n")
        sb.append("                <w:rFonts w:ascii=\"$FONT_NAME\" w:hAnsi=\"$FONT_NAME\" w:cs=\"$FONT_NAME\"/>\n")
        sb.append("                <w:b/><w:bCs/>\n")
        sb.append("                <w:u w:val=\"single\"/>\n")
        sb.append("                <w:sz w:val=\"$FONT_SIZE_HALF_PT\"/><w:szCs w:val=\"$FONT_SIZE_HALF_PT\"/>\n")
        sb.append("                <w:rtl/>\n")
        sb.append("              </w:rPr>\n")
        sb.append("              <w:t xml:space=\"preserve\">${escapeXml(label)} </w:t>\n")
        sb.append("            </w:r>\n")
        if (rest.isNotEmpty()) {
            sb.append("            <w:r>\n")
            sb.append("              <w:rPr>\n")
            sb.append("                <w:rFonts w:ascii=\"$FONT_NAME\" w:hAnsi=\"$FONT_NAME\" w:cs=\"$FONT_NAME\"/>\n")
            sb.append("                <w:sz w:val=\"$FONT_SIZE_HALF_PT\"/><w:szCs w:val=\"$FONT_SIZE_HALF_PT\"/>\n")
            sb.append("                <w:rtl/>\n")
            sb.append("              </w:rPr>\n")
            sb.append("              <w:t xml:space=\"preserve\">${escapeXml(rest)}</w:t>\n")
            sb.append("            </w:r>\n")
        }
        sb.append("          </w:p>\n")
    }
}
