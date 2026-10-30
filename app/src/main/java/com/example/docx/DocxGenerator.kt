package com.example.docx

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.example.model.JadhadhaInfo
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
    private const val FONT_TITLE_SIZE = 20
    const val HEADER_BG_HEX = "F2F2F2" // Official light grey header shading (#F2F2F2) for clear printing
    private const val STAGE_BG_HEX = "FFFFFF"
    const val TABLE_HEADER_BG_HEX = "F2F2F2" // Official light grey table header (#F2F2F2) with black text
    private const val BORDER_COLOR_HEX = "000000"
    const val DOCX_MIME_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    // 5-column widths in dxa (Total = 11106 dxa):
    // 12% المراحل (1333) | 18% القدرات والمهارات (1999) | 25% الأنشطة التعليمية التعلمية (2776) | 30% المحتوى (3332) | 15% التقويم والدعم (1666)
    val MAIN_TABLE_COL_WIDTHS = listOf(1333, 1999, 2776, 3332, 1666)

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
        <w:bidi/>
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

        if (plan.componentType == PedagogicalComponent.EXPRESSION) {
            appendExpressionFourSessionTables(sb, plan)
        } else {
            // 1. Official 7-Row x 4-Column RTL Header Table
            appendHeaderTable(sb, plan)

            // Compact spacer paragraph
            sb.append("""<w:p><w:pPr><w:bidi/><w:spacing w:before="0" w:after="60"/></w:pPr></w:p>""")

            // 2. Official 5-Column RTL Main Stages Table
            appendStagesTable(sb, plan)
        }

        // Section properties with compact 400 dxa (~0.28 in) margins
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

    /**
     * Generates 4 independent Header + 5-Column Stages tables for التعبير والإنشاء:
     * 1. حصة الاكتساب
     * 2. حصة التطبيق
     * 3. حصة الإنتاج
     * 4. حصة التصحيح
     */
    private fun appendExpressionFourSessionTables(sb: StringBuilder, plan: JadhadhaPlan) {
        data class ExpressionSessionSpec(
            val sessionNumber: Int,
            val componentLabel: String,
            val stageKeys: List<String>,
            val legacyKey: String,
            val overrideInfo: (JadhadhaInfo) -> JadhadhaInfo
        )

        val sessionSpecs = listOf(
            ExpressionSessionSpec(
                sessionNumber = 1,
                componentLabel = "التعبير والإنشاء – حصة الاكتساب",
                stageKeys = listOf(
                    "insha_acq_diagnostic",
                    "insha_acq_reading",
                    "insha_acq_elements",
                    "insha_acq_synthesis",
                    "insha_acq_investment"
                ),
                legacyKey = "insha_acquisition",
                overrideInfo = { info -> info.copy(component = "التعبير والإنشاء – حصة الاكتساب", duration = "ساعة واحدة") }
            ),
            ExpressionSessionSpec(
                sessionNumber = 2,
                componentLabel = "التعبير والإنشاء – حصة التطبيق",
                stageKeys = listOf(
                    "insha_app_diagnostic",
                    "insha_app_presentation",
                    "insha_app_execution",
                    "insha_app_correction"
                ),
                legacyKey = "insha_application",
                overrideInfo = { info -> info.copy(component = "التعبير والإنشاء – حصة التطبيق", duration = "ساعة واحدة") }
            ),
            ExpressionSessionSpec(
                sessionNumber = 3,
                componentLabel = "التعبير والإنشاء – حصة الإنتاج",
                stageKeys = listOf(
                    "insha_prod_diagnostic",
                    "insha_prod_presentation",
                    "insha_prod_execution",
                    "insha_prod_correction"
                ),
                legacyKey = "insha_production",
                overrideInfo = { info -> info.copy(component = "التعبير والإنشاء – حصة الإنتاج", duration = "ساعة واحدة") }
            ),
            ExpressionSessionSpec(
                sessionNumber = 4,
                componentLabel = "التعبير والإنشاء – حصة التصحيح",
                stageKeys = listOf(
                    "insha_corr_diagnostic",
                    "insha_corr_diagnosis",
                    "insha_corr_support"
                ),
                legacyKey = "insha_correction",
                overrideInfo = { info ->
                    info.copy(
                        component = "التعبير والإنشاء – حصة التصحيح",
                        duration = "ساعة واحدة",
                        communicativeComp = if (info.communicativeComp.contains("تطوير أدوات التواصل")) {
                            "تمكين المتعلم من التعبير والتواصل بلغة عربية سليمة عن الأخطاء الإملائية والتركيبية والمعجمية."
                        } else {
                            info.communicativeComp
                        },
                        methodologicalComp = if (info.methodologicalComp.contains("وضع تصميم لعناصر")) {
                            "اكتساب القدرة على التقويم الذاتي والتصحيح الجماعي للإنتاجات الكتابية."
                        } else {
                            info.methodologicalComp
                        },
                        culturalComp = if (info.culturalComp.contains("التشبع بالقيم الإسلامية")) {
                            "تثبيت الظواهر اللغوية والإملائية المتعثرة، وإغناء الرصيد المعجمي الخاص بأساليب التدوين والسرد الذاتي."
                        } else {
                            info.culturalComp
                        }
                    )
                }
            )
        )

        var renderedAnySession = false
        for (spec in sessionSpecs) {
            val activeKeys = spec.stageKeys.filter { plan.stages.containsKey(it) }
            val keysToRender = when {
                activeKeys.isNotEmpty() -> activeKeys
                plan.stages.containsKey(spec.legacyKey) -> listOf(spec.legacyKey)
                else -> spec.stageKeys
            }

            if (renderedAnySession) {
                // Page break paragraph before next session's header table
                sb.append("""<w:p><w:pPr><w:bidi/><w:pageBreakBefore/><w:spacing w:before="0" w:after="60"/></w:pPr></w:p>""")
            }

            val sessionPlan = plan.copy(info = spec.overrideInfo(plan.info))
            appendHeaderTable(sb, sessionPlan, overrideComponentTitle = spec.componentLabel)
            sb.append("""<w:p><w:pPr><w:bidi/><w:spacing w:before="0" w:after="60"/></w:pPr></w:p>""")
            appendStagesTableForKeys(sb, sessionPlan, keysToRender, stageHeaderLabel = "المراحل")
            renderedAnySession = true
        }
    }

    /**
     * Appends the official 7-row x 4-column RTL Header Table matching the exemplar screenshots:
     * Row 1: الوحدة | <unit> | الفئة المستهدفة | <level>
     * Row 2: المجال | <domain> | الوسائل التعليمية | <materials> (#F2F2F2)
     * Row 3: المكون | <component> | الكفايات المستهدفة / الأهداف التعلمية (gridSpan=2)
     * Row 4: الموضوع | <topic> | - كفاية تواصلية: ... (gridSpan=2, #F2F2F2)
     * Row 5: مدة الإنجاز | <duration> | - كفاية منهجية: ... (gridSpan=2)
     * Row 6: المرجع | <reference> | - كفاية معرفية: ... (gridSpan=2, #F2F2F2)
     * Row 7: الأستاذ | <teacherName> | المؤسسة | <schoolName>
     */
    private fun appendHeaderTable(
        sb: StringBuilder,
        plan: JadhadhaPlan,
        overrideComponentTitle: String? = null
    ) {
        val borders = """<w:tblBorders>
      <w:top w:val="single" w:sz="6" w:space="0" w:color="000000"/>
      <w:left w:val="none" w:sz="0" w:space="0" w:color="auto"/>
      <w:bottom w:val="single" w:sz="6" w:space="0" w:color="000000"/>
      <w:right w:val="none" w:sz="0" w:space="0" w:color="auto"/>
      <w:insideH w:val="single" w:sz="4" w:space="0" w:color="BFBFBF"/>
      <w:insideV w:val="none" w:sz="0" w:space="0" w:color="auto"/>
    </w:tblBorders>"""

        // 4 columns in RTL (<w:bidiVisual/>): RightLabel (1400) | RightVal (3600) | LeftLabel (1900) | LeftVal (4206) = 11106 dxa
        val colWidths = listOf(1400, 3600, 1900, 4206)

        sb.append("""
    <w:tbl>
      <w:tblPr>
        <w:tblW w:w="11106" w:type="dxa"/>
        <w:jc w:val="center"/>
        <w:bidiVisual/>
        $borders
      </w:tblPr>
      <w:tblGrid>
        <w:gridCol w:w="${colWidths[0]}"/>
        <w:gridCol w:w="${colWidths[1]}"/>
        <w:gridCol w:w="${colWidths[2]}"/>
        <w:gridCol w:w="${colWidths[3]}"/>
      </w:tblGrid>
""")

        val compTitle = overrideComponentTitle ?: plan.info.component.ifBlank { plan.componentType.title }
        val competenciesHeader = if (plan.componentType == PedagogicalComponent.READING) {
            "الكفايات المستهدفة"
        } else {
            "الأهداف التعلمية:"
        }

        // Row 1 (White): الوحدة & الفئة المستهدفة
        appendFourColHeaderRow(
            sb = sb,
            rightLabel = "الوحدة :",
            rightVal = plan.info.unit,
            leftLabel = "الفئة المستهدفة :",
            leftVal = plan.info.level,
            bg = null,
            colWidths = colWidths
        )

        // Row 2 (#F2F2F2): المجال & الوسائل التعليمية
        appendFourColHeaderRow(
            sb = sb,
            rightLabel = "المجال :",
            rightVal = plan.info.domain,
            leftLabel = "الوسائل التعليمية :",
            leftVal = plan.info.materials,
            bg = HEADER_BG_HEX,
            colWidths = colWidths
        )

        // Row 3 (White): المكون & الكفايات المستهدفة / الأهداف التعلمية (underlined, spanned 2 cols on left)
        appendTwoPlusSpannedLeftHeaderRow(
            sb = sb,
            rightLabel = "المكون :",
            rightVal = compTitle,
            leftSpannedText = competenciesHeader,
            bg = null,
            colWidths = colWidths,
            underlineLeft = true
        )

        // Row 4 (#F2F2F2): الموضوع & - كفاية تواصلية
        appendTwoPlusSpannedLeftHeaderRow(
            sb = sb,
            rightLabel = "الموضوع :",
            rightVal = plan.topic,
            leftSpannedText = "- كفاية تواصلية: ${plan.info.communicativeComp}",
            bg = HEADER_BG_HEX,
            colWidths = colWidths,
            rightValSz = FONT_TITLE_SIZE
        )

        // Row 5 (White): مدة الإنجاز & - كفاية منهجية
        appendTwoPlusSpannedLeftHeaderRow(
            sb = sb,
            rightLabel = "مدة الإنجاز :",
            rightVal = plan.info.duration,
            leftSpannedText = "- كفاية منهجية: ${plan.info.methodologicalComp}",
            bg = null,
            colWidths = colWidths
        )

        // Row 6 (#F2F2F2): المرجع & - كفاية معرفية
        appendTwoPlusSpannedLeftHeaderRow(
            sb = sb,
            rightLabel = "المرجع :",
            rightVal = plan.info.reference,
            leftSpannedText = "- كفاية معرفية: ${plan.info.culturalComp}",
            bg = HEADER_BG_HEX,
            colWidths = colWidths
        )

        // Row 7 (White): الأستاذ & المؤسسة
        appendFourColHeaderRow(
            sb = sb,
            rightLabel = "الأستاذ :",
            rightVal = plan.info.teacherName,
            leftLabel = "المؤسسة :",
            leftVal = plan.info.schoolName,
            bg = null,
            colWidths = colWidths
        )

        sb.append("    </w:tbl>\n")
    }

    private fun appendFourColHeaderRow(
        sb: StringBuilder,
        rightLabel: String,
        rightVal: String,
        leftLabel: String,
        leftVal: String,
        bg: String?,
        colWidths: List<Int>
    ) {
        sb.append("      <w:tr>\n")
        appendCell(sb, rightLabel, widthDxa = colWidths[0], bgHex = bg, bold = true, isCenter = true)
        appendCell(sb, rightVal, widthDxa = colWidths[1], bgHex = bg, bold = true, isCenter = true)
        appendCell(sb, leftLabel, widthDxa = colWidths[2], bgHex = bg, bold = true, isCenter = true)
        appendCell(sb, leftVal, widthDxa = colWidths[3], bgHex = bg, bold = true, isCenter = true)
        sb.append("      </w:tr>\n")
    }

    private fun appendTwoPlusSpannedLeftHeaderRow(
        sb: StringBuilder,
        rightLabel: String,
        rightVal: String,
        leftSpannedText: String,
        bg: String?,
        colWidths: List<Int>,
        underlineLeft: Boolean = false,
        rightValSz: Int = FONT_SIZE_HALF_PT
    ) {
        val leftWidth = colWidths[2] + colWidths[3]
        sb.append("      <w:tr>\n")
        appendCell(sb, rightLabel, widthDxa = colWidths[0], bgHex = bg, bold = true, isCenter = true)
        appendCell(sb, rightVal, widthDxa = colWidths[1], bgHex = bg, bold = true, isCenter = true, sz = rightValSz)
        sb.append("        <w:tc>\n")
        sb.append("          <w:tcPr>\n")
        sb.append("            <w:tcW w:w=\"$leftWidth\" w:type=\"dxa\"/>\n")
        sb.append("            <w:gridSpan w:val=\"2\"/>\n")
        sb.append("            <w:vAlign w:val=\"center\"/>\n")
        if (bg != null) {
            sb.append("            <w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"$bg\"/>\n")
        }
        sb.append("            <w:tcMar><w:top w:w=\"20\" w:type=\"dxa\"/><w:bottom w:w=\"20\" w:type=\"dxa\"/><w:left w:w=\"60\" w:type=\"dxa\"/><w:right w:w=\"60\" w:type=\"dxa\"/></w:tcMar>\n")
        sb.append("          </w:tcPr>\n")
        if (underlineLeft) {
            appendUnderlinedParagraph(sb, leftSpannedText, "", isCenter = true)
        } else {
            appendParagraph(sb, leftSpannedText, isCenter = true, bold = true)
        }
        sb.append("        </w:tc>\n")
        sb.append("      </w:tr>\n")
    }

    private fun appendStagesTable(sb: StringBuilder, plan: JadhadhaPlan) {
        when (plan.componentType) {
            PedagogicalComponent.READING -> {
                val colWidths = MAIN_TABLE_COL_WIDTHS
                val borders = buildMainTableBorders()

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

                // Session 1 Banner Row + 5-column Header Row
                appendSessionBanner(sb, "الحصة الأولى: تأطير النص والقراءة التوجيهية", pageBreak = false)
                appendMainTableHeaderRow(sb, colWidths, stageColLabel = "المقاطع")

                val session1Keys = listOf("diagnostic", "observation", "comprehension")
                session1Keys.forEach { key ->
                    appendStageRow(sb, key, plan, colWidths)
                }

                // Session 2 Banner Row
                appendSessionBanner(sb, "الحصة الثانية: القراءة التحليلية والتركيبية", pageBreak = true)
                appendMainTableHeaderRow(sb, colWidths, stageColLabel = "مقاطع الدرس", evalColLabel = "تقويم ودعم")

                if (plan.stages.containsKey("session2_intro")) {
                    appendStageRow(sb, "session2_intro", plan, colWidths)
                }
                val session2Keys = listOf("analysis", "synthesis", "investment")
                session2Keys.forEach { key ->
                    appendStageRow(sb, key, plan, colWidths)
                }

                sb.append("    </w:tbl>\n")
            }

            PedagogicalComponent.LANGUAGE -> {
                val langKeys = buildList {
                    add("lang_diagnostic")
                    add("lang_observation")
                    if (plan.stages.containsKey("lang_analysis") &&
                        plan.stages["lang_analysis"] != plan.stages["lang_observation"]
                    ) {
                        add("lang_analysis")
                    }
                    add("lang_synthesis")
                    add("lang_application")
                }
                appendStagesTableForKeys(sb, plan, langKeys, stageHeaderLabel = "المراحل")
            }

            PedagogicalComponent.EXPRESSION -> {
                appendExpressionFourSessionTables(sb, plan)
            }
        }
    }

    private fun appendStagesTableForKeys(
        sb: StringBuilder,
        plan: JadhadhaPlan,
        stageKeys: List<String>,
        stageHeaderLabel: String = "المراحل"
    ) {
        val colWidths = MAIN_TABLE_COL_WIDTHS
        val borders = buildMainTableBorders()

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

        appendMainTableHeaderRow(sb, colWidths, stageColLabel = stageHeaderLabel)

        stageKeys.forEach { key ->
            appendStageRow(sb, key, plan, colWidths)
        }

        sb.append("    </w:tbl>\n")
    }

    private fun buildMainTableBorders(): String = """<w:tblBorders>
      <w:top w:val="single" w:sz="8" w:space="0" w:color="$BORDER_COLOR_HEX"/>
      <w:left w:val="single" w:sz="8" w:space="0" w:color="$BORDER_COLOR_HEX"/>
      <w:bottom w:val="single" w:sz="8" w:space="0" w:color="$BORDER_COLOR_HEX"/>
      <w:right w:val="single" w:sz="8" w:space="0" w:color="$BORDER_COLOR_HEX"/>
      <w:insideH w:val="single" w:sz="4" w:space="0" w:color="$BORDER_COLOR_HEX"/>
      <w:insideV w:val="single" w:sz="4" w:space="0" w:color="$BORDER_COLOR_HEX"/>
    </w:tblBorders>"""

    private fun appendMainTableHeaderRow(
        sb: StringBuilder,
        colWidths: List<Int>,
        stageColLabel: String = "المراحل",
        evalColLabel: String = "التقويم والدعم"
    ) {
        sb.append("      <w:tr>\n")
        val headers = listOf(
            stageColLabel,
            "القدرات والمهارات",
            "الأنشطة التعليمية التعلمية",
            "المحتوى",
            evalColLabel
        )
        headers.forEachIndexed { idx, h ->
            appendCell(
                sb,
                h,
                colWidths[idx],
                bgHex = TABLE_HEADER_BG_HEX,
                textColorHex = "000000",
                bold = true,
                isCenter = true
            )
        }
        sb.append("      </w:tr>\n")
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
        sb.append("                <w:color w:val=\"000000\"/>\n")
        sb.append("                <w:sz w:val=\"20\"/><w:szCs w:val=\"20\"/>\n")
        sb.append("                <w:bidi/>\n")
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
        val stageTitle = when (key) {
            "lang_analysis" -> "الوصف والتحليل"
            "insha_acquisition" -> "حصة الاكتساب"
            "insha_application" -> "حصة التطبيق"
            "insha_production" -> "حصة الإنتاج"
            "insha_correction" -> "حصة التصحيح"
            else -> stageMeta?.title ?: key
        }
        val data = plan.stages[key] ?: com.example.model.StageData()

        sb.append("      <w:tr>\n")

        // 1. المراحل / المقاطع (12% = 1333 dxa, far right)
        appendCell(sb, stageTitle, widths[0], bgHex = null, textColorHex = "000000", bold = true, isCenter = true)

        // 2. القدرات والمهارات (18% = 1999 dxa)
        val abilItems = data.abil.map { formatItem(it, "- ") }
        appendCellWithLines(sb, abilItems, widths[1], isCenter = true)

        // 3. الأنشطة التعليمية التعلمية (25% = 2776 dxa)
        val teacherItems = data.teacher.map { formatItem(it, "- ") }
        appendCellWithLines(sb, teacherItems, widths[2], isCenter = true)

        // 4. المحتوى (30% = 3332 dxa, supports underlined labels AND nested [جدول: ...] analytical tables)
        appendContentCell(sb, data.content, widths[3])

        // 5. التقويم والدعم (15% = 1666 dxa, far left)
        val evalItems = data.eval.map { formatItem(it, "+ ") }
        appendCellWithLines(sb, evalItems, widths[4], isCenter = true)

        sb.append("      </w:tr>\n")
    }

    private fun formatItem(text: String, defaultPrefix: String): String {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return ""
        if (cleaned.startsWith("-") || cleaned.startsWith("+") || cleaned.startsWith("*") ||
            Regex("^\\d+[-–.)]").containsMatchIn(cleaned) ||
            Regex("^[أبجدهوز][-–.)]").containsMatchIn(cleaned)
        ) {
            return cleaned
        }
        return "$defaultPrefix$cleaned"
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
                    appendNestedWordTable(sb, pendingTableRows, widthDxa - 120)
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
                    val cleanLine = trimmed.replace("<u>", "").replace("</u>", "").trim()
                    val colonIdx = cleanLine.indexOf(':')
                    if (colonIdx in 1..45 && !cleanLine.startsWith("http")) {
                        val label = cleanLine.substring(0, colonIdx + 1).trim()
                        val rest = cleanLine.substring(colonIdx + 1).trim()
                        appendUnderlinedParagraph(sb, label, rest, isCenter = true)
                    } else {
                        appendParagraph(sb, cleanLine, isCenter = true, bold = true)
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
     * Renders a real nested <w:tbl> inside the المحتوى cell (e.g. جدول اسم الفاعل وصيغ المبالغة or شبكة تشخيص الأخطاء).
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
            sb.append("                <w:color w:val=\"$textColorHex\"/>\n")
            sb.append("                <w:sz w:val=\"$sz\"/><w:szCs w:val=\"$sz\"/>\n")
            sb.append("                <w:bidi/>\n")
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
        sb.append("                <w:color w:val=\"000000\"/>\n")
        sb.append("                <w:sz w:val=\"$FONT_SIZE_HALF_PT\"/><w:szCs w:val=\"$FONT_SIZE_HALF_PT\"/>\n")
        sb.append("                <w:bidi/>\n")
        sb.append("                <w:rtl/>\n")
        sb.append("              </w:rPr>\n")
        sb.append("              <w:t xml:space=\"preserve\">${escapeXml(label)} </w:t>\n")
        sb.append("            </w:r>\n")
        if (rest.isNotEmpty()) {
            sb.append("            <w:r>\n")
            sb.append("              <w:rPr>\n")
            sb.append("                <w:rFonts w:ascii=\"$FONT_NAME\" w:hAnsi=\"$FONT_NAME\" w:cs=\"$FONT_NAME\"/>\n")
            sb.append("                <w:b/><w:bCs/>\n")
            sb.append("                <w:color w:val=\"000000\"/>\n")
            sb.append("                <w:sz w:val=\"$FONT_SIZE_HALF_PT\"/><w:szCs w:val=\"$FONT_SIZE_HALF_PT\"/>\n")
            sb.append("                <w:bidi/>\n")
            sb.append("                <w:rtl/>\n")
            sb.append("              </w:rPr>\n")
            sb.append("              <w:t xml:space=\"preserve\">${escapeXml(rest)}</w:t>\n")
            sb.append("            </w:r>\n")
        }
        sb.append("          </w:p>\n")
    }
}
