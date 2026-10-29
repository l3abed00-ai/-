package com.example.docx

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.example.model.JadhadhaPlan
import com.example.model.PedagogicalComponent
import com.example.model.SamplePlans
import com.example.model.StageCatalog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object DocxGenerator {

    const val FONT_NAME = "Sakkal Majalla"
    const val FONT_SIZE_HALF_PT = 20 // 10pt (20 half-points) as in Model Jadhadha
    private const val FONT_TITLE_SIZE = 22 // 11pt bold for main lesson title
    private const val HEADER_BG_HEX = "D9E2F3" // Model Jadhadha soft blue-grey header shading
    private const val STAGE_BG_HEX = "F2F4F8"
    private const val ACCENT_BG_HEX = "E2EFDA" // Model Jadhadha soft green accent shading
    private const val TABLE_HEADER_BG_HEX = "1F4E79" // Official deep navy-emerald table header
    private const val BORDER_COLOR_HEX = "2F5597"
    const val DOCX_MIME_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    /**
     * Saves a custom Model Jadhadha .docx template selected by the user (e.g. from the
     * "الملف التربوي" folder on Google Drive) for a specific PedagogicalComponent.
     */
    fun saveModelTemplate(
        context: Context,
        component: PedagogicalComponent,
        uri: Uri
    ): String? {
        return runCatching {
            val templatesDir = File(context.filesDir, "model_templates").apply { mkdirs() }
            val targetFile = File(templatesDir, "template_${component.id}.docx")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            val displayName = queryDisplayName(context, uri) ?: "نموذج_${component.title}.docx"
            File(templatesDir, "template_${component.id}.name").writeText(displayName, StandardCharsets.UTF_8)
            displayName
        }.getOrNull()
    }

    fun getModelTemplateFile(context: Context, component: PedagogicalComponent): File? {
        val file = File(File(context.filesDir, "model_templates"), "template_${component.id}.docx")
        return if (file.exists() && file.length() > 0L) file else null
    }

    fun getModelTemplateName(context: Context, component: PedagogicalComponent): String? {
        val nameFile = File(File(context.filesDir, "model_templates"), "template_${component.id}.name")
        val docxFile = getModelTemplateFile(context, component)
        if (docxFile == null) return null
        return if (nameFile.exists()) nameFile.readText(StandardCharsets.UTF_8).trim() else docxFile.name
    }

    fun clearModelTemplate(context: Context, component: PedagogicalComponent) {
        val dir = File(context.filesDir, "model_templates")
        File(dir, "template_${component.id}.docx").delete()
        File(dir, "template_${component.id}.name").delete()
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    fun generateDocx(context: Context, plan: JadhadhaPlan, saveToDownloads: Boolean = true): File {
        val safeTitle = plan.topic.replace(Regex("[^\\w\\p{L}]+"), "_").take(50).ifEmpty { "jadhadha" }
        val fileName = "جذاذة_${safeTitle}.docx"
        val outDir = File(context.cacheDir, "generated_plans").apply { mkdirs() }
        val outFile = File(outDir, fileName)

        val customTemplate = getModelTemplateFile(context, plan.componentType)
        if (customTemplate != null) {
            val injected = runCatching {
                generateFromTemplateDocx(customTemplate, outFile, plan)
            }.getOrDefault(false)
            if (!injected) {
                writeStandaloneModelDocx(outFile, plan)
            }
        } else {
            writeStandaloneModelDocx(outFile, plan)
        }

        // Also copy to app's external documents folder if available
        runCatching {
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.let { extDocs ->
                val targetDir = File(extDocs, "Jadhadhat").apply { mkdirs() }
                outFile.copyTo(File(targetDir, fileName), overwrite = true)
            }
        }

        // Automatically save a copy to the phone's public Downloads (التنزيلات) folder
        if (saveToDownloads) {
            saveToPublicDownloads(context, outFile)
        }

        return outFile
    }

    private fun writeStandaloneModelDocx(outFile: File, plan: JadhadhaPlan) {
        ZipOutputStream(FileOutputStream(outFile)).use { zip ->
            addZipEntry(zip, "[Content_Types].xml", buildContentTypesXml())
            addZipEntry(zip, "_rels/.rels", buildPackageRelsXml())
            addZipEntry(zip, "word/_rels/document.xml.rels", buildDocumentRelsXml())
            addZipEntry(zip, "docProps/core.xml", buildCorePropsXml(plan.topic))
            addZipEntry(zip, "docProps/app.xml", buildAppPropsXml())
            addZipEntry(zip, "word/styles.xml", buildStylesXml())
            addZipEntry(zip, "word/document.xml", buildDocumentXml(plan, preservedSectPr = null))
        }
    }

    /**
     * Clones the user's custom Model Jadhadha .docx from "الملف التربوي" (preserving its styles.xml,
     * theme1.xml, fontTable.xml, settings.xml, and sectPr) while injecting the new lesson content
     * formatted with Sakkal Majalla 10pt bold.
     */
    private fun generateFromTemplateDocx(templateFile: File, outFile: File, plan: JadhadhaPlan): Boolean {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(FileInputStream(templateFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    entries[entry.name] = zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        if (!entries.containsKey("word/document.xml")) return false

        val originalDocXml = String(entries["word/document.xml"]!!, StandardCharsets.UTF_8)
        val sectPrMatch = Regex("""<w:sectPr\b[\s\S]*?</w:sectPr>""").find(originalDocXml)?.value

        entries["word/document.xml"] = buildDocumentXml(plan, preservedSectPr = sectPrMatch)
            .toByteArray(StandardCharsets.UTF_8)

        ZipOutputStream(FileOutputStream(outFile)).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return true
    }

    /**
     * Copies a generated .docx file into the phone's public Downloads (التنزيلات) folder
     * so the user can find it immediately in their phone's Files / Downloads app.
     */
    fun saveToPublicDownloads(context: Context, file: File): Uri? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                runCatching {
                    resolver.delete(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                        arrayOf(file.name)
                    )
                }
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, DOCX_MIME_TYPE)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { outStream ->
                        FileInputStream(file).use { inStream ->
                            inStream.copyTo(outStream)
                        }
                    }
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }
                uri
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                downloadsDir.mkdirs()
                val dest = File(downloadsDir, file.name)
                file.copyTo(dest, overwrite = true)
                Uri.fromFile(dest)
            }
        }.getOrNull()
    }

    /**
     * Saves the source file to any user-chosen URI from ActivityResultContracts.CreateDocument
     */
    fun saveFileToUri(context: Context, sourceFile: File, targetUri: Uri): Boolean {
        return runCatching {
            context.contentResolver.openOutputStream(targetUri)?.use { outStream ->
                FileInputStream(sourceFile).use { inStream ->
                    inStream.copyTo(outStream)
                }
            }
            true
        }.getOrDefault(false)
    }

    /**
     * Ensures sample .docx files exist in generated_plans and returns all available .docx files
     * so the user can always find and test uploading/opening .docx files on their phone.
     */
    fun getOrCreateAvailableDocxFiles(context: Context): List<File> {
        val outDir = File(context.cacheDir, "generated_plans").apply { mkdirs() }
        val existing = outDir.listFiles { f -> f.isFile && f.name.endsWith(".docx", ignoreCase = true) }
            ?.toList()
            .orEmpty()
        if (existing.isEmpty()) {
            SamplePlans.ALL_SAMPLES.forEach { sample ->
                runCatching { generateDocx(context, sample, saveToDownloads = true) }
            }
        }
        return outDir.listFiles { f -> f.isFile && f.name.endsWith(".docx", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
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
  <dc:creator>الجذاذة النموذجية - الملف التربوي</dc:creator>
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
        <w:b/>
        <w:bCs/>
        <w:sz w:val="$FONT_SIZE_HALF_PT"/>
        <w:szCs w:val="$FONT_SIZE_HALF_PT"/>
        <w:rtl/>
      </w:rPr>
    </w:rPrDefault>
    <w:pPrDefault>
      <w:pPr>
        <w:bidi/>
        <w:jc w:val="center"/>
        <w:spacing w:before="0" w:after="0" w:line="240" w:lineRule="auto"/>
      </w:pPr>
    </w:pPrDefault>
  </w:docDefaults>
</w:styles>"""

    private fun buildDocumentXml(plan: JadhadhaPlan, preservedSectPr: String?): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>
""")

        // 1. Header Table (matching Model Jadhadha 6-row header layout)
        appendHeaderTable(sb, plan)

        // Compact spacer paragraph
        sb.append("""<w:p><w:pPr><w:bidi/><w:spacing w:before="0" w:after="60"/></w:pPr></w:p>""")

        // 2. Main Stages Table (Sakkal Majalla 10pt Bold, RTL, with nested tables support)
        appendStagesTable(sb, plan)

        // Section properties with compact 400 dxa (~0.28 in) margins as in build_jadhadha.py
        if (preservedSectPr != null) {
            sb.append("\n    ").append(preservedSectPr).append("\n")
        } else {
            sb.append("""
    <w:sectPr>
      <w:pgSz w:w="11906" w:h="16838"/>
      <w:pgMar w:top="400" w:right="400" w:bottom="400" w:left="400" w:header="300" w:footer="300" w:gutter="0"/>
      <w:bidi/>
    </w:sectPr>""")
        }
        sb.append("""
  </w:body>
</w:document>""")
        return sb.toString()
    }

    private fun appendHeaderTable(sb: StringBuilder, plan: JadhadhaPlan) {
        val borders = """<w:tblBorders>
      <w:top w:val="single" w:sz="8" w:space="0" w:color="$BORDER_COLOR_HEX"/>
      <w:left w:val="single" w:sz="8" w:space="0" w:color="$BORDER_COLOR_HEX"/>
      <w:bottom w:val="single" w:sz="8" w:space="0" w:color="$BORDER_COLOR_HEX"/>
      <w:right w:val="single" w:sz="8" w:space="0" w:color="$BORDER_COLOR_HEX"/>
      <w:insideH w:val="single" w:sz="4" w:space="0" w:color="8FAADC"/>
      <w:insideV w:val="single" w:sz="4" w:space="0" w:color="8FAADC"/>
    </w:tblBorders>"""

        sb.append("""
    <w:tbl>
      <w:tblPr>
        <w:tblW w:w="11106" w:type="dxa"/>
        <w:jc w:val="center"/>
        <w:bidiVisual/>
        $borders
      </w:tblPr>
      <w:tblGrid>
        <w:gridCol w:w="5553"/>
        <w:gridCol w:w="5553"/>
      </w:tblGrid>
""")

        // Row 0: الفئة المستهدفة & الوحدة
        appendHeaderRow(sb, "الفئة المستهدفة: " + plan.info.level, "الوحدة: " + plan.info.unit, bg = HEADER_BG_HEX)

        // Row 1: الوسائل التعليمية & المجال
        appendHeaderRow(sb, "الوسائل التعليمية: " + plan.info.materials, "المجال: " + plan.info.domain, bg = STAGE_BG_HEX)

        // Row 2: المكون (Span 2)
        val compTitle = plan.componentType.title
        appendSpannedHeaderRow(sb, "المكوّن: $compTitle", bg = ACCENT_BG_HEX, bold = true, isCenter = true)

        // Row 3: كفاية تواصلية & الموضوع
        appendHeaderRow(
            sb,
            "- كفاية تواصلية: " + plan.info.communicativeComp,
            "الموضوع: " + plan.topic,
            bg2 = ACCENT_BG_HEX,
            bold2 = true,
            sz2 = FONT_TITLE_SIZE
        )

        // Row 4: كفاية منهجية & المدة الزمنية
        appendHeaderRow(sb, "- كفاية منهجية: " + plan.info.methodologicalComp, "المدة الزمنية: " + plan.info.duration)

        // Row 5: كفاية ثقافية (Span 2)
        appendSpannedHeaderRow(sb, "- كفاية ثقافية: " + plan.info.culturalComp)

        sb.append("    </w:tbl>\n")
    }

    private fun appendHeaderRow(
        sb: StringBuilder,
        cell1: String,
        cell2: String,
        bg: String? = null,
        bg2: String? = null,
        bold2: Boolean = true,
        sz2: Int = FONT_SIZE_HALF_PT
    ) {
        sb.append("      <w:tr>\n")
        appendCell(sb, cell1, widthDxa = 5553, bgHex = bg, bold = true, isCenter = true)
        appendCell(sb, cell2, widthDxa = 5553, bgHex = bg2 ?: bg, bold = bold2, isCenter = true, sz = sz2)
        sb.append("      </w:tr>\n")
    }

    private fun appendSpannedHeaderRow(
        sb: StringBuilder,
        text: String,
        bg: String? = null,
        bold: Boolean = true,
        isCenter: Boolean = true
    ) {
        sb.append("      <w:tr>\n")
        sb.append("        <w:tc>\n")
        sb.append("          <w:tcPr>\n")
        sb.append("            <w:tcW w:w=\"11106\" w:type=\"dxa\"/>\n")
        sb.append("            <w:gridSpan w:val=\"2\"/>\n")
        sb.append("            <w:vAlign w:val=\"center\"/>\n")
        if (bg != null) {
            sb.append("            <w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"$bg\"/>\n")
        }
        sb.append("          </w:tcPr>\n")
        appendParagraph(sb, text, isCenter = isCenter, bold = bold)
        sb.append("        </w:tc>\n")
        sb.append("      </w:tr>\n")
    }

    private fun appendStagesTable(sb: StringBuilder, plan: JadhadhaPlan) {
        // Table columns in RTL visual order:
        // المقاطع/المراحل | القدرات المستهدفة | أنشطة المدرس والمتعلم | المحتوى الديداكتيكي | التقويم والدعم
        val colWidths = listOf(1400, 1700, 2600, 3806, 1600) // Total: 11106 dxa

        val borders = """<w:tblBorders>
      <w:top w:val="single" w:sz="8" w:space="0" w:color="$BORDER_COLOR_HEX"/>
      <w:left w:val="single" w:sz="8" w:space="0" w:color="$BORDER_COLOR_HEX"/>
      <w:bottom w:val="single" w:sz="8" w:space="0" w:color="$BORDER_COLOR_HEX"/>
      <w:right w:val="single" w:sz="8" w:space="0" w:color="$BORDER_COLOR_HEX"/>
      <w:insideH w:val="single" w:sz="4" w:space="0" w:color="8FAADC"/>
      <w:insideV w:val="single" w:sz="4" w:space="0" w:color="8FAADC"/>
    </w:tblBorders>"""

        sb.append("""
    <w:tbl>
      <w:tblPr>
        <w:tblW w:w="11106" w:type="dxa"/>
        <w:jc w:val="center"/>
        <w:bidiVisual/>
        $borders
      </w:tblPr>
      <w:tblGrid>
""")
        colWidths.forEach { w -> sb.append("        <w:gridCol w:w=\"$w\"/>\n") }
        sb.append("      </w:tblGrid>\n")

        // Table Header Row (Model Jadhadha header styling)
        sb.append("      <w:tr>\n")
        val headers = listOf(
            "المراحل والمقاطع",
            "القدرات المستهدفة",
            "أنشطة المدرس والمتعلم",
            "المحتوى الديداكتيكي",
            "التقويم والدعم"
        )
        headers.forEachIndexed { idx, h ->
            appendCell(
                sb,
                h,
                colWidths[idx],
                bgHex = TABLE_HEADER_BG_HEX,
                textColorHex = "FFFFFF",
                bold = true,
                isCenter = true
            )
        }
        sb.append("      </w:tr>\n")

        when (plan.componentType) {
            PedagogicalComponent.READING -> {
                val session1Keys = listOf("diagnostic", "observation", "comprehension")
                session1Keys.forEach { key ->
                    appendStageRow(sb, key, plan, colWidths)
                }

                appendSessionBanner(sb, "الحصة الثانية: القراءة التحليلية والتركيب والاستثمار", pageBreak = true)

                val session2Keys = listOf("analysis", "synthesis", "investment")
                session2Keys.forEach { key ->
                    appendStageRow(sb, key, plan, colWidths)
                }
            }

            PedagogicalComponent.LANGUAGE -> {
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
                appendSessionBanner(sb, "الحصة الأولى: أنشطة الاكتساب (التعريف بالمهارة وخطواتها)", pageBreak = false)
                appendStageRow(sb, "insha_acquisition", plan, colWidths)

                appendSessionBanner(sb, "الحصة الثانية: أنشطة التطبيق (التدرب على المهارة)", pageBreak = true)
                appendStageRow(sb, "insha_application", plan, colWidths)

                appendSessionBanner(sb, "الحصة الثالثة: أنشطة الإنتاج والربط (التحرير في مسودة)", pageBreak = true)
                appendStageRow(sb, "insha_production", plan, colWidths)

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
        sb.append("            <w:tcW w:w=\"11106\" w:type=\"dxa\"/>\n")
        sb.append("            <w:gridSpan w:val=\"5\"/>\n")
        sb.append("            <w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"$HEADER_BG_HEX\"/>\n")
        sb.append("          </w:tcPr>\n")
        sb.append("          <w:p>\n")
        sb.append("            <w:pPr>\n")
        if (pageBreak) {
            sb.append("              <w:pageBreakBefore/>\n")
        }
        sb.append("              <w:bidi/>\n")
        sb.append("              <w:jc w:val=\"center\"/>\n")
        sb.append("              <w:spacing w:before=\"40\" w:after=\"40\" w:line=\"240\" w:lineRule=\"auto\"/>\n")
        sb.append("            </w:pPr>\n")
        sb.append("            <w:r>\n")
        sb.append("              <w:rPr>\n")
        sb.append("                <w:rFonts w:ascii=\"$FONT_NAME\" w:hAnsi=\"$FONT_NAME\" w:cs=\"$FONT_NAME\"/>\n")
        sb.append("                <w:b/><w:bCs/>\n")
        sb.append("                <w:color w:val=\"1F4E79\"/>\n")
        sb.append("                <w:sz w:val=\"20\"/><w:szCs w:val=\"20\"/>\n")
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

        // 1. Stage Name (Centered, Sakkal Majalla 10pt bold, soft stage shading)
        appendCell(sb, stageTitle, widths[0], bgHex = STAGE_BG_HEX, bold = true, isCenter = true)

        // 2. Abilities (prefixed with - , Sakkal Majalla 10pt bold, centered as in Model Jadhadha)
        val abilItems = data.abil.map { formatItem(it, "- ") }
        appendCellWithLines(sb, abilItems, widths[1], isCenter = true)

        // 3. Teacher & Learner Activities (prefixed with + , underlined sub-label if present)
        val teacherItems = data.teacher.map { formatItem(it, "+ ") }
        appendCellWithLines(sb, teacherItems, widths[2], isCenter = true)

        // 4. Content (supports underlined labels AND nested [جدول: ...] analytical tables!)
        appendContentCell(sb, data.content, widths[3])

        // 5. Evaluation (prefixed with + , Sakkal Majalla 10pt bold, centered)
        val evalItems = data.eval.map { formatItem(it, "+ ") }
        appendCellWithLines(sb, evalItems, widths[4], isCenter = true)

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
        bold: Boolean = true,
        isCenter: Boolean = true,
        sz: Int = FONT_SIZE_HALF_PT
    ) {
        sb.append("        <w:tc>\n")
        sb.append("          <w:tcPr>\n")
        sb.append("            <w:tcW w:w=\"$widthDxa\" w:type=\"dxa\"/>\n")
        sb.append("            <w:vAlign w:val=\"center\"/>\n")
        if (bgHex != null) {
            sb.append("            <w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"$bgHex\"/>\n")
        }
        sb.append("            <w:tcMar><w:top w:w=\"40\" w:type=\"dxa\"/><w:bottom w:w=\"40\" w:type=\"dxa\"/><w:left w:w=\"60\" w:type=\"dxa\"/><w:right w:w=\"60\" w:type=\"dxa\"/></w:tcMar>\n")
        sb.append("          </w:tcPr>\n")
        appendParagraph(sb, text, isCenter = isCenter, bold = bold, textColorHex = textColorHex, sz = sz)
        sb.append("        </w:tc>\n")
    }

    private fun appendCellWithLines(
        sb: StringBuilder,
        lines: List<String>,
        widthDxa: Int,
        isCenter: Boolean = true
    ) {
        sb.append("        <w:tc>\n")
        sb.append("          <w:tcPr>\n")
        sb.append("            <w:tcW w:w=\"$widthDxa\" w:type=\"dxa\"/>\n")
        sb.append("            <w:vAlign w:val=\"center\"/>\n")
        sb.append("            <w:tcMar><w:top w:w=\"40\" w:type=\"dxa\"/><w:bottom w:w=\"40\" w:type=\"dxa\"/><w:left w:w=\"60\" w:type=\"dxa\"/><w:right w:w=\"60\" w:type=\"dxa\"/></w:tcMar>\n")
        sb.append("          </w:tcPr>\n")

        if (lines.isEmpty()) {
            appendParagraph(sb, "", isCenter = isCenter, bold = true)
        } else {
            lines.forEach { line ->
                val clean = line.trim()
                if (clean.contains("أنشطة المتعلم:") || clean.contains("أنشطة المدرس:")) {
                    val colonIdx = clean.indexOf(':')
                    val label = clean.substring(0, colonIdx + 1).removePrefix("+").removePrefix("-").trim()
                    val rest = clean.substring(colonIdx + 1).trim()
                    appendUnderlinedParagraph(sb, label, rest, isCenter = isCenter)
                } else {
                    appendParagraph(sb, clean, isCenter = isCenter, bold = true)
                }
            }
        }
        sb.append("        </w:tc>\n")
    }

    private fun appendContentCell(sb: StringBuilder, lines: List<String>, widthDxa: Int) {
        sb.append("        <w:tc>\n")
        sb.append("          <w:tcPr>\n")
        sb.append("            <w:tcW w:w=\"$widthDxa\" w:type=\"dxa\"/>\n")
        sb.append("            <w:vAlign w:val=\"center\"/>\n")
        sb.append("            <w:tcMar><w:top w:w=\"40\" w:type=\"dxa\"/><w:bottom w:w=\"40\" w:type=\"dxa\"/><w:left w:w=\"60\" w:type=\"dxa\"/><w:right w:w=\"60\" w:type=\"dxa\"/></w:tcMar>\n")
        sb.append("          </w:tcPr>\n")

        if (lines.isEmpty()) {
            appendParagraph(sb, "", isCenter = true, bold = true)
        } else {
            val pendingTableRows = mutableListOf<List<String>>()

            fun flushNestedTable() {
                if (pendingTableRows.isNotEmpty()) {
                    appendNestedWordTable(sb, pendingTableRows, widthDxa - 160)
                    pendingTableRows.clear()
                }
            }

            lines.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("[جدول:") && trimmed.endsWith("]")) {
                    val rowCells = trimmed.removePrefix("[جدول:").removeSuffix("]")
                        .split("|")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    if (rowCells.isNotEmpty()) {
                        pendingTableRows.add(rowCells)
                    }
                } else {
                    flushNestedTable()
                    val cleanLine = trimmed.removePrefix("- ").removePrefix("• ").trim()
                    val colonIdx = cleanLine.indexOf(':')
                    if (colonIdx in 1..45 && !cleanLine.startsWith("http")) {
                        val label = cleanLine.substring(0, colonIdx + 1).replace("<u>", "").replace("</u>", "").trim()
                        val rest = cleanLine.substring(colonIdx + 1).replace("<u>", "").replace("</u>", "").trim()
                        appendUnderlinedParagraph(sb, label, rest, isCenter = true)
                    } else {
                        val cleanNoTags = cleanLine.replace("<u>", "").replace("</u>", "").trim()
                        val contentText = if (cleanNoTags.startsWith("-")) cleanNoTags else "- $cleanNoTags"
                        appendParagraph(sb, contentText, isCenter = true, bold = true)
                    }
                }
            }
            flushNestedTable()
            // Word requires a trailing <w:p> inside a <w:tc> if the last child is a nested <w:tbl>
            if (lines.lastOrNull()?.trim()?.startsWith("[جدول:") == true) {
                appendParagraph(sb, "", isCenter = true, bold = true)
            }
        }
        sb.append("        </w:tc>\n")
    }

    /**
     * Renders a real nested <w:tbl> inside the المحتوى cell (matching make_table in build_jadhadha.py).
     */
    private fun appendNestedWordTable(
        sb: StringBuilder,
        rows: List<List<String>>,
        availableWidthDxa: Int
    ) {
        val colCount = rows.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: 1
        val colW = (availableWidthDxa / colCount).coerceAtLeast(400)

        sb.append("""          <w:tbl>
            <w:tblPr>
              <w:tblW w:w="5000" w:type="pct"/>
              <w:jc w:val="center"/>
              <w:bidiVisual/>
              <w:tblBorders>
                <w:top w:val="single" w:sz="4" w:space="0" w:color="000000"/>
                <w:left w:val="single" w:sz="4" w:space="0" w:color="000000"/>
                <w:bottom w:val="single" w:sz="4" w:space="0" w:color="000000"/>
                <w:right w:val="single" w:sz="4" w:space="0" w:color="000000"/>
                <w:insideH w:val="single" w:sz="4" w:space="0" w:color="000000"/>
                <w:insideV w:val="single" w:sz="4" w:space="0" w:color="000000"/>
              </w:tblBorders>
            </w:tblPr>
            <w:tblGrid>
""")
        repeat(colCount) {
            sb.append("              <w:gridCol w:w=\"$colW\"/>\n")
        }
        sb.append("            </w:tblGrid>\n")

        rows.forEachIndexed { rIdx, row ->
            val isHeader = rIdx == 0
            sb.append("            <w:tr>\n")
            for (cIdx in 0 until colCount) {
                val cellText = row.getOrNull(cIdx).orEmpty()
                sb.append("              <w:tc>\n")
                sb.append("                <w:tcPr>\n")
                sb.append("                  <w:tcW w:w=\"$colW\" w:type=\"dxa\"/>\n")
                sb.append("                  <w:vAlign w:val=\"center\"/>\n")
                if (isHeader) {
                    sb.append("                  <w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"$HEADER_BG_HEX\"/>\n")
                }
                sb.append("                </w:tcPr>\n")
                appendParagraph(sb, cellText, isCenter = true, bold = true, sz = 18)
                sb.append("              </w:tc>\n")
            }
            sb.append("            </w:tr>\n")
        }

        sb.append("          </w:tbl>\n")
    }

    private fun appendParagraph(
        sb: StringBuilder,
        text: String,
        isCenter: Boolean = true,
        bold: Boolean = true,
        textColorHex: String = "000000",
        sz: Int = FONT_SIZE_HALF_PT
    ) {
        val jc = if (isCenter) "center" else "both"
        sb.append("          <w:p>\n")
        sb.append("            <w:pPr>\n")
        sb.append("              <w:bidi/>\n")
        sb.append("              <w:jc w:val=\"$jc\"/>\n")
        sb.append("              <w:spacing w:before=\"0\" w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/>\n")
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

    private fun appendUnderlinedParagraph(
        sb: StringBuilder,
        label: String,
        rest: String,
        isCenter: Boolean = true
    ) {
        val jc = if (isCenter) "center" else "both"
        sb.append("          <w:p>\n")
        sb.append("            <w:pPr>\n")
        sb.append("              <w:bidi/>\n")
        sb.append("              <w:jc w:val=\"$jc\"/>\n")
        sb.append("              <w:spacing w:before=\"0\" w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/>\n")
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
            sb.append("                <w:b/><w:bCs/>\n")
            sb.append("                <w:sz w:val=\"$FONT_SIZE_HALF_PT\"/><w:szCs w:val=\"$FONT_SIZE_HALF_PT\"/>\n")
            sb.append("                <w:rtl/>\n")
            sb.append("              </w:rPr>\n")
            sb.append("              <w:t xml:space=\"preserve\">${escapeXml(rest)}</w:t>\n")
            sb.append("            </w:r>\n")
        }
        sb.append("          </w:p>\n")
    }
}

