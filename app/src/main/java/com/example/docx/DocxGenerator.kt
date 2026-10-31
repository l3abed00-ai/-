package com.example.docx

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.example.model.DocxTemplateArchive
import com.example.model.JadhadhaInfo
import com.example.model.JadhadhaPlan
import com.example.model.PedagogicalComponent
import com.example.model.SamplePlans
import com.example.model.StageCatalog
import com.example.model.StageData
import com.example.model.StructuredTable
import com.example.model.StructuredTableCell
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

    private val HEADER_FOOTER_XML_REGEX = Regex("^word/(header|footer)\\d*\\.xml$", RegexOption.IGNORE_CASE)
    private val SECT_PR_REGEX = Regex("""<w:sectPr\b[\s\S]*?</w:sectPr>""")
    private val ROOT_DOC_ATTR_REGEX = Regex("""<w:document\b([^>]*)>""")
    private val DRAWING_PARAGRAPH_REGEX = Regex("""<w:p\b[^>]*>(?:(?!</w:p>)[\s\S])*?(?:<w:drawing\b|<w:pict\b|<v:imagedata\b)[\s\S]*?</w:p>""")
    private val R_FONTS_ASCII_REGEX = Regex("""<w:rFonts\b[^>]*?\bw:(?:cs|ascii)="([^"]+)"""")

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

    /**
     * Generates a .docx file directly to [outFile], preserving any provided [templateFile] or
     * [plan.templateArchive] (including styles, fonts, headers/footers, media/logos, numbering,
     * relationships, and section properties).
     */
    fun generateDocxToFile(outFile: File, plan: JadhadhaPlan, templateFile: File? = null): File {
        outFile.parentFile?.mkdirs()
        val injectedFromFile = if (templateFile != null && templateFile.exists() && templateFile.length() > 0L) {
            runCatching { generateFromTemplateDocx(templateFile, outFile, plan) }.getOrDefault(false)
        } else {
            false
        }

        if (!injectedFromFile) {
            val archive = plan.templateArchive
            val injectedFromArchive = if (archive != null && archive.zipEntries.isNotEmpty()) {
                runCatching { generateFromTemplateArchive(archive, outFile, plan) }.getOrDefault(false)
            } else {
                false
            }
            if (!injectedFromArchive) {
                writeStandaloneModelDocx(outFile, plan)
            }
        }
        return outFile
    }

    fun generateDocx(context: Context, plan: JadhadhaPlan, saveToDownloads: Boolean = true): File {
        val safeTitle = plan.topic.replace(Regex("[^\\w\\p{L}]+"), "_").take(50).ifEmpty { "jadhadha" }
        val fileName = "جذاذة_${safeTitle}.docx"
        val outDir = File(context.cacheDir, "generated_plans").apply { mkdirs() }
        val outFile = File(outDir, fileName)

        val customTemplate = getModelTemplateFile(context, plan.componentType)
        generateDocxToFile(outFile, plan, customTemplate)

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
            addZipEntry(
                zip,
                "word/document.xml",
                buildDocumentXml(
                    plan = plan,
                    preservedSectPr = null,
                    rootAttributes = null,
                    leadingBodyXml = "",
                    trailingBodyXml = "",
                    fontName = FONT_NAME
                )
            )
        }
    }

    /**
     * Clones a template .docx file (preserving its styles.xml, theme1.xml, fontTable.xml,
     * numbering.xml, settings.xml, header.xml, footer.xml, word/media/ images, relationships,
     * leading/trailing logos/drawings, and sectPr) while injecting the complete lesson content.
     */
    fun generateFromTemplateDocx(templateFile: File, outFile: File, plan: JadhadhaPlan): Boolean {
        val entries = LinkedHashMap<String, ByteArray>()
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
        val sectPrMatch = SECT_PR_REGEX.find(originalDocXml)?.value
        val rootAttrs = ROOT_DOC_ATTR_REGEX.find(originalDocXml)?.groupValues?.getOrNull(1)?.trim().orEmpty()

        val firstTblIdx = originalDocXml.indexOf("<w:tbl")
        val leadingDrawings = if (firstTblIdx > 0) {
            DRAWING_PARAGRAPH_REGEX.findAll(originalDocXml.substring(0, firstTblIdx))
                .map { it.value }.toList()
        } else {
            DRAWING_PARAGRAPH_REGEX.findAll(originalDocXml)
                .map { it.value }.toList()
        }

        val lastTblEnd = originalDocXml.lastIndexOf("</w:tbl>")
        val trailingDrawings = if (lastTblEnd >= 0) {
            DRAWING_PARAGRAPH_REGEX.findAll(originalDocXml.substring(lastTblEnd + 8))
                .map { it.value }.toList()
        } else {
            emptyList()
        }

        val stylesXml = entries["word/styles.xml"]?.toString(StandardCharsets.UTF_8).orEmpty()
        val detectedFont = R_FONTS_ASCII_REGEX.find(stylesXml)?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }

        val archiveMeta = DocxTemplateArchive(
            zipEntries = entries,
            documentRootAttributes = rootAttrs,
            preservedSectPrXml = sectPrMatch,
            preservedLeadingElementsXml = leadingDrawings,
            preservedTrailingElementsXml = trailingDrawings,
            primaryFontName = detectedFont
        )
        return writeFromTemplateEntries(entries, outFile, plan, archiveMeta)
    }

    /**
     * Generates a .docx from an in-memory [DocxTemplateArchive] captured when a template/source
     * .docx was imported via [com.example.extractor.DocumentFileReader].
     */
    fun generateFromTemplateArchive(archive: DocxTemplateArchive, outFile: File, plan: JadhadhaPlan): Boolean {
        if (!archive.zipEntries.containsKey("word/document.xml")) return false
        val mutableEntries = LinkedHashMap<String, ByteArray>()
        archive.zipEntries.forEach { (k, v) -> mutableEntries[k] = v.copyOf() }
        return writeFromTemplateEntries(mutableEntries, outFile, plan, archive)
    }

    private fun writeFromTemplateEntries(
        entries: MutableMap<String, ByteArray>,
        outFile: File,
        plan: JadhadhaPlan,
        archiveMeta: DocxTemplateArchive?
    ): Boolean {
        val originalDocXml = entries["word/document.xml"]?.toString(StandardCharsets.UTF_8) ?: return false
        val sectPr = archiveMeta?.preservedSectPrXml
            ?: SECT_PR_REGEX.find(originalDocXml)?.value
        val rootAttrs = archiveMeta?.documentRootAttributes?.takeIf { it.isNotBlank() }
            ?: ROOT_DOC_ATTR_REGEX.find(originalDocXml)?.groupValues?.getOrNull(1)?.trim()
        val leadingXml = replaceTemplatePlaceholders(
            archiveMeta?.preservedLeadingElementsXml?.joinToString("\n").orEmpty(),
            plan
        )
        val trailingXml = replaceTemplatePlaceholders(
            archiveMeta?.preservedTrailingElementsXml?.joinToString("\n").orEmpty(),
            plan
        )
        val fontName = archiveMeta?.primaryFontName?.takeIf { it.isNotBlank() } ?: FONT_NAME

        // Replace placeholders inside any header*.xml or footer*.xml while preserving their XML structure & logos
        for (entryName in entries.keys.toList()) {
            if (HEADER_FOOTER_XML_REGEX.matches(entryName)) {
                val xmlText = String(entries[entryName]!!, StandardCharsets.UTF_8)
                val updated = replaceTemplatePlaceholders(xmlText, plan)
                entries[entryName] = updated.toByteArray(StandardCharsets.UTF_8)
            }
        }

        // Update <dc:title> in docProps/core.xml if present
        if (entries.containsKey("docProps/core.xml") && plan.topic.isNotBlank()) {
            val coreXml = String(entries["docProps/core.xml"]!!, StandardCharsets.UTF_8)
            val updatedCore = if (coreXml.contains("<dc:title>")) {
                coreXml.replace(Regex("<dc:title>[\\s\\S]*?</dc:title>"), "<dc:title>${escapeXml(plan.topic)}</dc:title>")
            } else {
                coreXml
            }
            entries["docProps/core.xml"] = updatedCore.toByteArray(StandardCharsets.UTF_8)
        }

        entries["word/document.xml"] = buildDocumentXml(
            plan = plan,
            preservedSectPr = sectPr,
            rootAttributes = rootAttrs,
            leadingBodyXml = leadingXml,
            trailingBodyXml = trailingXml,
            fontName = fontName
        ).toByteArray(StandardCharsets.UTF_8)

        ZipOutputStream(FileOutputStream(outFile)).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return true
    }

    private fun replaceTemplatePlaceholders(xml: String, plan: JadhadhaPlan): String {
        if (xml.isEmpty()) return xml
        val compTitle = plan.info.component.ifBlank { plan.componentType.title }
        val replacements = mapOf(
            "{{الموضوع}}" to escapeXml(plan.topic),
            "{{عنوان_الدرس}}" to escapeXml(plan.topic),
            "{{المؤسسة}}" to escapeXml(plan.info.schoolName),
            "{{الأستاذ}}" to escapeXml(plan.info.teacherName),
            "{{المستوى}}" to escapeXml(plan.info.level),
            "{{الفئة_المستهدفة}}" to escapeXml(plan.info.level),
            "{{الوحدة}}" to escapeXml(plan.info.unit),
            "{{المجال}}" to escapeXml(plan.info.domain),
            "{{المكون}}" to escapeXml(compTitle),
            "{{المادة}}" to escapeXml(compTitle),
            "{{المدة}}" to escapeXml(plan.info.duration),
            "{{مدة_الإنجاز}}" to escapeXml(plan.info.duration),
            "{{الوسائل}}" to escapeXml(plan.info.materials),
            "{{الوسائل_التعليمية}}" to escapeXml(plan.info.materials),
            "{{المرجع}}" to escapeXml(plan.info.reference),
            "{{الكفاية_التواصلية}}" to escapeXml(plan.info.communicativeComp),
            "{{الكفاية_المنهجية}}" to escapeXml(plan.info.methodologicalComp),
            "{{الكفاية_المعرفية}}" to escapeXml(plan.info.culturalComp)
        )
        var result = xml
        for ((token, value) in replacements) {
            if (result.contains(token)) {
                result = result.replace(token, value)
            }
        }
        return result
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

    private fun buildStylesXml(fontName: String = FONT_NAME): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:docDefaults>
    <w:rPrDefault>
      <w:rPr>
        <w:rFonts w:ascii="${escapeXml(fontName)}" w:hAnsi="${escapeXml(fontName)}" w:cs="${escapeXml(fontName)}"/>
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

    private fun buildDocumentRootOpenTag(rootAttributes: String?): String {
        val requiredNamespaces = linkedMapOf(
            "xmlns:w" to "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
            "xmlns:r" to "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
            "xmlns:wp" to "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing",
            "xmlns:a" to "http://schemas.openxmlformats.org/drawingml/2006/main",
            "xmlns:pic" to "http://schemas.openxmlformats.org/drawingml/2006/picture",
            "xmlns:v" to "urn:schemas-microsoft-com:vml",
            "xmlns:mc" to "http://schemas.openxmlformats.org/markup-compatibility/2006",
            "xmlns:wps" to "http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
        )
        val mergedAttrs = StringBuilder()
        val existingAttrText = rootAttributes?.trim().orEmpty()
        if (existingAttrText.isNotEmpty()) {
            mergedAttrs.append(" ").append(existingAttrText)
        }
        for ((nsKey, nsUri) in requiredNamespaces) {
            if (!existingAttrText.contains("$nsKey=")) {
                mergedAttrs.append(" ").append(nsKey).append("=\"").append(nsUri).append("\"")
            }
        }
        return "<w:document$mergedAttrs>"
    }

    private fun buildDocumentXml(
        plan: JadhadhaPlan,
        preservedSectPr: String?,
        rootAttributes: String? = null,
        leadingBodyXml: String = "",
        trailingBodyXml: String = "",
        fontName: String = FONT_NAME
    ): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("\n")
        sb.append(buildDocumentRootOpenTag(rootAttributes))
        sb.append("\n  <w:body>\n")

        if (leadingBodyXml.isNotBlank()) {
            sb.append("    ").append(leadingBodyXml.trim()).append("\n")
        }

        if (plan.componentType == PedagogicalComponent.EXPRESSION) {
            appendExpressionFourSessionTables(sb, plan, fontName)
        } else {
            // 1. Official 7-Row x 4-Column RTL Header Table
            appendHeaderTable(sb, plan, fontName = fontName)

            // Compact spacer paragraph
            sb.append("""    <w:p><w:pPr><w:bidi/><w:spacing w:before="0" w:after="60"/></w:pPr></w:p>""")
            sb.append("\n")

            // 2. Official 5-Column RTL Main Stages Table
            appendStagesTable(sb, plan, fontName)
        }

        // 3. Any custom / additional structured tables preserved in the plan
        for (customTbl in plan.customTables) {
            sb.append("""    <w:p><w:pPr><w:bidi/><w:spacing w:before="0" w:after="60"/></w:pPr></w:p>""")
            sb.append("\n")
            appendStructuredWordTable(
                sb = sb,
                table = customTbl,
                isNested = false,
                availableWidthDxa = 11106,
                fontName = fontName
            )
        }

        // 4. Any extra body paragraphs or unclassified lines so zero extracted units are ever lost
        val alreadyRenderedTexts = collectRenderedTextsForDedup(plan)
        val remainingParagraphs = LinkedHashSet<String>()
        plan.extraParagraphs.map { it.trim() }.filter { it.isNotEmpty() }.forEach { p ->
            if (!alreadyRenderedTexts.contains(p)) {
                remainingParagraphs.add(p)
            }
        }
        plan.unclassifiedItems.map { it.trim() }.filter { it.isNotEmpty() }.forEach { u ->
            if (!alreadyRenderedTexts.contains(u)) {
                remainingParagraphs.add(u)
            }
        }
        for (extraPara in remainingParagraphs) {
            appendParagraph(sb, extraPara, isCenter = false, bold = true, fontName = fontName)
        }

        if (trailingBodyXml.isNotBlank()) {
            sb.append("    ").append(trailingBodyXml.trim()).append("\n")
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

    private fun collectRenderedTextsForDedup(plan: JadhadhaPlan): Set<String> {
        val set = HashSet<String>()
        fun addText(s: String) {
            val t = s.trim()
            if (t.isNotEmpty()) {
                set.add(t)
                set.add(t.removePrefix("-").removePrefix("+").removePrefix("*").trim())
            }
        }
        addText(plan.topic)
        addText(plan.info.level)
        addText(plan.info.unit)
        addText(plan.info.domain)
        addText(plan.info.component)
        addText(plan.info.duration)
        addText(plan.info.materials)
        addText(plan.info.reference)
        addText(plan.info.teacherName)
        addText(plan.info.schoolName)
        addText(plan.info.communicativeComp)
        addText(plan.info.methodologicalComp)
        addText(plan.info.culturalComp)

        fun collectTableTexts(tbl: StructuredTable) {
            tbl.toPipeLines().forEach { addText(it) }
            for (r in tbl.rows) {
                for (c in r.cells) {
                    addText(c.text)
                    c.paragraphs.forEach { addText(it) }
                    c.nestedTables.forEach { collectTableTexts(it) }
                }
            }
        }

        plan.stages.values.forEach { stage ->
            stage.abil.forEach { addText(it) }
            stage.teacher.forEach { addText(it) }
            stage.learner.forEach { addText(it) }
            stage.content.forEach { addText(it) }
            stage.eval.forEach { addText(it) }
            stage.tables.forEach { collectTableTexts(it) }
        }
        plan.customTables.forEach { collectTableTexts(it) }
        return set
    }

    /**
     * Generates 4 independent Header + 5-Column Stages tables for التعبير والإنشاء:
     * 1. حصة الاكتساب
     * 2. حصة التطبيق
     * 3. حصة الإنتاج
     * 4. حصة التصحيح
     */
    private fun appendExpressionFourSessionTables(
        sb: StringBuilder,
        plan: JadhadhaPlan,
        fontName: String = FONT_NAME
    ) {
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

        val renderedStageKeys = mutableSetOf<String>()
        var renderedAnySession = false
        for ((index, spec) in sessionSpecs.withIndex()) {
            val activeKeys = spec.stageKeys.filter { plan.stages.containsKey(it) }
            val baseKeys = when {
                activeKeys.isNotEmpty() -> activeKeys
                plan.stages.containsKey(spec.legacyKey) -> listOf(spec.legacyKey)
                else -> spec.stageKeys
            }
            renderedStageKeys.addAll(baseKeys)
            val keysToRender = if (index == sessionSpecs.lastIndex) {
                val extraStageKeys = plan.stages.keys.filter { it !in renderedStageKeys }
                baseKeys + extraStageKeys
            } else {
                baseKeys
            }

            if (renderedAnySession) {
                // Page break paragraph before next session's header table
                sb.append("""<w:p><w:pPr><w:bidi/><w:pageBreakBefore/><w:spacing w:before="0" w:after="60"/></w:pPr></w:p>""")
            }

            val sessionPlan = plan.copy(info = spec.overrideInfo(plan.info))
            appendHeaderTable(sb, sessionPlan, overrideComponentTitle = spec.componentLabel, fontName = fontName)
            sb.append("""<w:p><w:pPr><w:bidi/><w:spacing w:before="0" w:after="60"/></w:pPr></w:p>""")
            appendStagesTableForKeys(sb, sessionPlan, keysToRender, stageHeaderLabel = "المراحل", fontName = fontName)
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
        overrideComponentTitle: String? = null,
        fontName: String = FONT_NAME
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
            colWidths = colWidths,
            fontName = fontName
        )

        // Row 2 (#F2F2F2): المجال & الوسائل التعليمية
        appendFourColHeaderRow(
            sb = sb,
            rightLabel = "المجال :",
            rightVal = plan.info.domain,
            leftLabel = "الوسائل التعليمية :",
            leftVal = plan.info.materials,
            bg = HEADER_BG_HEX,
            colWidths = colWidths,
            fontName = fontName
        )

        // Row 3 (White): المكون & الكفايات المستهدفة / الأهداف التعلمية (underlined, spanned 2 cols on left)
        appendTwoPlusSpannedLeftHeaderRow(
            sb = sb,
            rightLabel = "المكون :",
            rightVal = compTitle,
            leftSpannedText = competenciesHeader,
            bg = null,
            colWidths = colWidths,
            underlineLeft = true,
            fontName = fontName
        )

        // Row 4 (#F2F2F2): الموضوع & - كفاية تواصلية
        appendTwoPlusSpannedLeftHeaderRow(
            sb = sb,
            rightLabel = "الموضوع :",
            rightVal = plan.topic,
            leftSpannedText = "- كفاية تواصلية: ${plan.info.communicativeComp}",
            bg = HEADER_BG_HEX,
            colWidths = colWidths,
            rightValSz = FONT_TITLE_SIZE,
            fontName = fontName
        )

        // Row 5 (White): مدة الإنجاز & - كفاية منهجية
        appendTwoPlusSpannedLeftHeaderRow(
            sb = sb,
            rightLabel = "مدة الإنجاز :",
            rightVal = plan.info.duration,
            leftSpannedText = "- كفاية منهجية: ${plan.info.methodologicalComp}",
            bg = null,
            colWidths = colWidths,
            fontName = fontName
        )

        // Row 6 (#F2F2F2): المرجع & - كفاية معرفية
        appendTwoPlusSpannedLeftHeaderRow(
            sb = sb,
            rightLabel = "المرجع :",
            rightVal = plan.info.reference,
            leftSpannedText = "- كفاية معرفية: ${plan.info.culturalComp}",
            bg = HEADER_BG_HEX,
            colWidths = colWidths,
            fontName = fontName
        )

        // Row 7 (White): الأستاذ & المؤسسة
        appendFourColHeaderRow(
            sb = sb,
            rightLabel = "الأستاذ :",
            rightVal = plan.info.teacherName,
            leftLabel = "المؤسسة :",
            leftVal = plan.info.schoolName,
            bg = null,
            colWidths = colWidths,
            fontName = fontName
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
        colWidths: List<Int>,
        fontName: String = FONT_NAME
    ) {
        sb.append("      <w:tr>\n")
        appendCell(sb, rightLabel, widthDxa = colWidths[0], bgHex = bg, bold = true, isCenter = true, fontName = fontName)
        appendCell(sb, rightVal, widthDxa = colWidths[1], bgHex = bg, bold = true, isCenter = true, fontName = fontName)
        appendCell(sb, leftLabel, widthDxa = colWidths[2], bgHex = bg, bold = true, isCenter = true, fontName = fontName)
        appendCell(sb, leftVal, widthDxa = colWidths[3], bgHex = bg, bold = true, isCenter = true, fontName = fontName)
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
        rightValSz: Int = FONT_SIZE_HALF_PT,
        fontName: String = FONT_NAME
    ) {
        val leftWidth = colWidths[2] + colWidths[3]
        sb.append("      <w:tr>\n")
        appendCell(sb, rightLabel, widthDxa = colWidths[0], bgHex = bg, bold = true, isCenter = true, fontName = fontName)
        appendCell(sb, rightVal, widthDxa = colWidths[1], bgHex = bg, bold = true, isCenter = true, sz = rightValSz, fontName = fontName)
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
            appendUnderlinedParagraph(sb, leftSpannedText, "", isCenter = true, fontName = fontName)
        } else {
            appendParagraph(sb, leftSpannedText, isCenter = true, bold = true, fontName = fontName)
        }
        sb.append("        </w:tc>\n")
        sb.append("      </w:tr>\n")
    }

    private fun appendStagesTable(
        sb: StringBuilder,
        plan: JadhadhaPlan,
        fontName: String = FONT_NAME
    ) {
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
                appendSessionBanner(sb, "الحصة الأولى: تأطير النص والقراءة التوجيهية", pageBreak = false, fontName = fontName)
                appendMainTableHeaderRow(sb, colWidths, stageColLabel = "المقاطع", fontName = fontName)

                val session1Keys = listOf("diagnostic", "observation", "comprehension")
                session1Keys.forEach { key ->
                    appendStageRow(sb, key, plan, colWidths, fontName = fontName)
                }

                // Session 2 Banner Row
                appendSessionBanner(sb, "الحصة الثانية: القراءة التحليلية والتركيبية", pageBreak = true, fontName = fontName)
                appendMainTableHeaderRow(sb, colWidths, stageColLabel = "مقاطع الدرس", evalColLabel = "تقويم ودعم", fontName = fontName)

                if (plan.stages.containsKey("session2_intro")) {
                    appendStageRow(sb, "session2_intro", plan, colWidths, fontName = fontName)
                }
                val session2Keys = listOf("analysis", "synthesis", "investment")
                session2Keys.forEach { key ->
                    appendStageRow(sb, key, plan, colWidths, fontName = fontName)
                }

                // Render any custom/additional stage keys so no stage is ever dropped
                val renderedKeys = (session1Keys + session2Keys + "session2_intro").toSet()
                plan.stages.keys.filter { it !in renderedKeys }.forEach { extraKey ->
                    appendStageRow(sb, extraKey, plan, colWidths, fontName = fontName)
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
                    val standardSet = setOf("lang_diagnostic", "lang_observation", "lang_analysis", "lang_synthesis", "lang_application")
                    plan.stages.keys.filter { it !in standardSet }.forEach { add(it) }
                }
                appendStagesTableForKeys(sb, plan, langKeys, stageHeaderLabel = "المراحل", fontName = fontName)
            }

            PedagogicalComponent.EXPRESSION -> {
                appendExpressionFourSessionTables(sb, plan, fontName)
            }
        }
    }

    private fun appendStagesTableForKeys(
        sb: StringBuilder,
        plan: JadhadhaPlan,
        stageKeys: List<String>,
        stageHeaderLabel: String = "المراحل",
        fontName: String = FONT_NAME
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

        appendMainTableHeaderRow(sb, colWidths, stageColLabel = stageHeaderLabel, fontName = fontName)

        stageKeys.forEach { key ->
            appendStageRow(sb, key, plan, colWidths, fontName = fontName)
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
        evalColLabel: String = "التقويم والدعم",
        fontName: String = FONT_NAME
    ) {
        sb.append("      <w:tr>\n")
        sb.append("        <w:trPr><w:tblHeader/></w:trPr>\n")
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
                isCenter = true,
                fontName = fontName
            )
        }
        sb.append("      </w:tr>\n")
    }

    private fun appendSessionBanner(
        sb: StringBuilder,
        title: String,
        pageBreak: Boolean,
        fontName: String = FONT_NAME
    ) {
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
        sb.append("                <w:rFonts w:ascii=\"${escapeXml(fontName)}\" w:hAnsi=\"${escapeXml(fontName)}\" w:cs=\"${escapeXml(fontName)}\"/>\n")
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

    private fun appendStageRow(
        sb: StringBuilder,
        key: String,
        plan: JadhadhaPlan,
        widths: List<Int>,
        fontName: String = FONT_NAME
    ) {
        val stageMeta = StageCatalog.getByKey(key)
        val stageTitle = when (key) {
            "lang_analysis" -> "الوصف والتحليل"
            "insha_acquisition" -> "حصة الاكتساب"
            "insha_application" -> "حصة التطبيق"
            "insha_production" -> "حصة الإنتاج"
            "insha_correction" -> "حصة التصحيح"
            else -> stageMeta?.title ?: key
        }
        val data = plan.stages[key] ?: StageData()

        sb.append("      <w:tr>\n")

        // 1. المراحل / المقاطع (12% = 1333 dxa, far right)
        appendCell(sb, stageTitle, widths[0], bgHex = null, textColorHex = "000000", bold = true, isCenter = true, fontName = fontName)

        // 2. القدرات والمهارات (18% = 1999 dxa)
        val abilItems = data.abil.map { formatItem(it, "- ") }
        appendCellWithLines(sb, abilItems, widths[1], isCenter = true, fontName = fontName)

        // 3. الأنشطة التعليمية التعلمية (25% = 2776 dxa) — includes both teacher and any distinct learner activities
        val teacherSet = data.teacher.map { it.trim() }.toSet()
        val combinedActivities = buildList {
            data.teacher.forEach { add(formatItem(it, "- ")) }
            data.learner.forEach { learnerLine ->
                if (learnerLine.trim().isNotEmpty() && learnerLine.trim() !in teacherSet) {
                    add(formatItem(learnerLine, "+ "))
                }
            }
        }
        appendCellWithLines(sb, combinedActivities, widths[2], isCenter = true, fontName = fontName)

        // 4. المحتوى (30% = 3332 dxa, supports underlined labels AND structured nested <w:tbl> tables)
        appendContentCell(sb, data, widths[3], fontName = fontName)

        // 5. التقويم والدعم (15% = 1666 dxa, far left)
        val evalItems = data.eval.map { formatItem(it, "+ ") }
        appendCellWithLines(sb, evalItems, widths[4], isCenter = true, fontName = fontName)

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
        sz: Int = FONT_SIZE_HALF_PT,
        fontName: String = FONT_NAME
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
        appendParagraph(sb, text, isCenter = isCenter, bold = bold, textColorHex = textColorHex, sz = sz, fontName = fontName)
        sb.append("        </w:tc>\n")
    }

    private fun appendCellWithLines(
        sb: StringBuilder,
        lines: List<String>,
        widthDxa: Int,
        isCenter: Boolean = true,
        fontName: String = FONT_NAME
    ) {
        sb.append("        <w:tc>\n")
        sb.append("          <w:tcPr>\n")
        sb.append("            <w:tcW w:w=\"$widthDxa\" w:type=\"dxa\"/>\n")
        sb.append("            <w:vAlign w:val=\"center\"/>\n")
        sb.append("            <w:tcMar><w:top w:w=\"40\" w:type=\"dxa\"/><w:bottom w:w=\"40\" w:type=\"dxa\"/><w:left w:w=\"60\" w:type=\"dxa\"/><w:right w:w=\"60\" w:type=\"dxa\"/></w:tcMar>\n")
        sb.append("          </w:tcPr>\n")

        if (lines.isEmpty()) {
            appendParagraph(sb, "", isCenter = isCenter, bold = true, fontName = fontName)
        } else {
            lines.forEach { line ->
                val clean = line.trim()
                if (clean.contains("أنشطة المتعلم:") || clean.contains("أنشطة المدرس:")) {
                    val colonIdx = clean.indexOf(':')
                    val label = clean.substring(0, colonIdx + 1).removePrefix("+").removePrefix("-").trim()
                    val rest = clean.substring(colonIdx + 1).trim()
                    appendUnderlinedParagraph(sb, label, rest, isCenter = isCenter, fontName = fontName)
                } else {
                    appendParagraph(sb, clean, isCenter = isCenter, bold = true, fontName = fontName)
                }
            }
        }
        sb.append("        </w:tc>\n")
    }

    private fun appendContentCell(
        sb: StringBuilder,
        data: StageData,
        widthDxa: Int,
        fontName: String = FONT_NAME
    ) {
        sb.append("        <w:tc>\n")
        sb.append("          <w:tcPr>\n")
        sb.append("            <w:tcW w:w=\"$widthDxa\" w:type=\"dxa\"/>\n")
        sb.append("            <w:vAlign w:val=\"center\"/>\n")
        sb.append("            <w:tcMar><w:top w:w=\"40\" w:type=\"dxa\"/><w:bottom w:w=\"40\" w:type=\"dxa\"/><w:left w:w=\"60\" w:type=\"dxa\"/><w:right w:w=\"60\" w:type=\"dxa\"/></w:tcMar>\n")
        sb.append("          </w:tcPr>\n")

        val lines = data.content
        val explicitTables = data.tables.toMutableList()
        val matchedTableIndices = mutableSetOf<Int>()

        // Pre-scan [جدول: ...] blocks in lines to match with explicitTables
        val pipeBlocks = mutableListOf<List<String>>()
        val tempBlock = mutableListOf<String>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[جدول:") && trimmed.endsWith("]")) {
                tempBlock.add(trimmed)
            } else if (tempBlock.isNotEmpty()) {
                pipeBlocks.add(tempBlock.toList())
                tempBlock.clear()
            }
        }
        if (tempBlock.isNotEmpty()) {
            pipeBlocks.add(tempBlock.toList())
        }

        val blockToMatchedTable = mutableMapOf<Int, StructuredTable>()
        pipeBlocks.forEachIndexed { blockIdx, blockLines ->
            val matchIdx = explicitTables.indexOfFirst { tbl ->
                tbl.toPipeLines() == blockLines
            }.takeIf { it >= 0 && it !in matchedTableIndices }
            if (matchIdx != null) {
                matchedTableIndices.add(matchIdx)
                blockToMatchedTable[blockIdx] = explicitTables[matchIdx]
            } else {
                blockToMatchedTable[blockIdx] = StructuredTable.fromPipeLines(blockLines)
            }
        }

        // Remaining explicit tables that were not represented as [جدول: ...] blocks (e.g., 2-col nested tables or direct StageData.tables)
        val standaloneTables = explicitTables.filterIndexed { idx, _ -> idx !in matchedTableIndices }
        val flattenedStandaloneCellTexts = standaloneTables.flatMap { tbl ->
            tbl.rows.flatMap { r -> r.cells.flatMap { c -> c.paragraphs.ifEmpty { listOf(c.text) } } }
        }.map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        if (lines.isEmpty() && standaloneTables.isEmpty()) {
            appendParagraph(sb, "", isCenter = true, bold = true, fontName = fontName)
        } else {
            val pendingPipeLines = mutableListOf<String>()
            var currentBlockIdx = 0
            var lastEmittedWasTable = false
            var emittedAnything = false

            fun flushPipeBlock() {
                if (pendingPipeLines.isNotEmpty()) {
                    val structuredTbl = blockToMatchedTable[currentBlockIdx]
                        ?: StructuredTable.fromPipeLines(pendingPipeLines)
                    currentBlockIdx++
                    pendingPipeLines.clear()
                    if (structuredTbl.rows.isNotEmpty()) {
                        appendStructuredWordTable(
                            sb = sb,
                            table = structuredTbl,
                            isNested = true,
                            availableWidthDxa = (widthDxa - 120).coerceAtLeast(800),
                            fontName = fontName
                        )
                        lastEmittedWasTable = true
                        emittedAnything = true
                    }
                }
            }

            lines.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("[جدول:") && trimmed.endsWith("]")) {
                    pendingPipeLines.add(trimmed)
                } else {
                    flushPipeBlock()
                    val cleanLine = trimmed.replace("<u>", "").replace("</u>", "").trim()
                    if (cleanLine.isNotEmpty() && cleanLine !in flattenedStandaloneCellTexts) {
                        val colonIdx = cleanLine.indexOf(':')
                        if (colonIdx in 1..45 && !cleanLine.startsWith("http")) {
                            val label = cleanLine.substring(0, colonIdx + 1).trim()
                            val rest = cleanLine.substring(colonIdx + 1).trim()
                            appendUnderlinedParagraph(sb, label, rest, isCenter = true, fontName = fontName)
                        } else {
                            appendParagraph(sb, cleanLine, isCenter = true, bold = true, fontName = fontName)
                        }
                        lastEmittedWasTable = false
                        emittedAnything = true
                    }
                }
            }
            flushPipeBlock()

            for (standaloneTbl in standaloneTables) {
                if (standaloneTbl.rows.isNotEmpty()) {
                    appendStructuredWordTable(
                        sb = sb,
                        table = standaloneTbl,
                        isNested = true,
                        availableWidthDxa = (widthDxa - 120).coerceAtLeast(800),
                        fontName = fontName
                    )
                    lastEmittedWasTable = true
                    emittedAnything = true
                }
            }

            // Word OpenXML requires a trailing <w:p> inside a <w:tc> if the last child is a nested <w:tbl> or if empty
            if (!emittedAnything || lastEmittedWasTable) {
                appendParagraph(sb, "", isCenter = true, bold = true, fontName = fontName)
            }
        }
        sb.append("        </w:tc>\n")
    }

    /**
     * Renders a full [StructuredTable] as a real OpenXML `<w:tbl>` element, preserving:
     * - Multi-row and multi-column structure
     * - Empty cells (`""`) without dropping or shifting columns
     * - Horizontal cell merges (`<w:gridSpan w:val="..."/>`)
     * - Vertical cell merges (`<w:vMerge w:val="restart"/>` and `<w:vMerge/>`)
     * - Recursive nested tables (`<w:tbl>` inside `<w:tc>`)
     * - RTL direction (`<w:bidiVisual/>`), borders, column widths, headers, and cell shading
     */
    fun appendStructuredWordTable(
        sb: StringBuilder,
        table: StructuredTable,
        isNested: Boolean = false,
        availableWidthDxa: Int = 11106,
        fontName: String = FONT_NAME
    ) {
        val colCount = maxOf(
            table.columnWidthsDxa.size,
            table.rows.maxOfOrNull { row -> row.cells.sumOf { it.gridSpan.coerceAtLeast(1) } } ?: 1
        ).coerceAtLeast(1)

        val colWidths = if (table.columnWidthsDxa.size == colCount && table.columnWidthsDxa.all { it > 0 }) {
            table.columnWidthsDxa
        } else {
            val defaultW = (availableWidthDxa / colCount).coerceAtLeast(400)
            List(colCount) { defaultW }
        }

        val borderSz = if (isNested) "4" else "6"
        val tblWXml = if (isNested) {
            """<w:tblW w:w="5000" w:type="pct"/>"""
        } else {
            """<w:tblW w:w="${colWidths.sum()}" w:type="dxa"/>"""
        }
        val bidiXml = if (table.isRtl) "<w:bidiVisual/>" else ""
        val bordersXml = if (table.hasBorders) {
            """<w:tblBorders>
                <w:top w:val="single" w:sz="$borderSz" w:space="0" w:color="000000"/>
                <w:left w:val="single" w:sz="$borderSz" w:space="0" w:color="000000"/>
                <w:bottom w:val="single" w:sz="$borderSz" w:space="0" w:color="000000"/>
                <w:right w:val="single" w:sz="$borderSz" w:space="0" w:color="000000"/>
                <w:insideH w:val="single" w:sz="4" w:space="0" w:color="000000"/>
                <w:insideV w:val="single" w:sz="4" w:space="0" w:color="000000"/>
              </w:tblBorders>"""
        } else {
            ""
        }

        sb.append("""          <w:tbl>
            <w:tblPr>
              $tblWXml
              <w:jc w:val="center"/>
              $bidiXml
              $bordersXml
            </w:tblPr>
            <w:tblGrid>
""")
        colWidths.forEach { w ->
            sb.append("              <w:gridCol w:w=\"$w\"/>\n")
        }
        sb.append("            </w:tblGrid>\n")

        table.rows.forEachIndexed { rIdx, row ->
            val isHeaderRow = row.isHeader || (rIdx == 0 && isNested && table.rows.size > 1)
            sb.append("            <w:tr>\n")
            if (row.isHeader) {
                sb.append("              <w:trPr><w:tblHeader/></w:trPr>\n")
            }
            var colCursor = 0
            for (cell in row.cells) {
                val span = cell.gridSpan.coerceAtLeast(1)
                val cellWidth = cell.widthDxa ?: (0 until span).sumOf { idx ->
                    colWidths.getOrElse(colCursor + idx) { colWidths.lastOrNull() ?: 1000 }
                }
                colCursor += span

                sb.append("              <w:tc>\n")
                sb.append("                <w:tcPr>\n")
                sb.append("                  <w:tcW w:w=\"$cellWidth\" w:type=\"dxa\"/>\n")
                if (span > 1) {
                    sb.append("                  <w:gridSpan w:val=\"$span\"/>\n")
                }
                if (cell.vMerge != null) {
                    if (cell.vMerge.equals("restart", ignoreCase = true)) {
                        sb.append("                  <w:vMerge w:val=\"restart\"/>\n")
                    } else {
                        sb.append("                  <w:vMerge/>\n")
                    }
                }
                sb.append("                  <w:vAlign w:val=\"center\"/>\n")
                val fillHex = cell.backgroundHex ?: if (isHeaderRow || cell.isHeader) HEADER_BG_HEX else null
                if (fillHex != null) {
                    sb.append("                  <w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"$fillHex\"/>\n")
                }
                sb.append("                </w:tcPr>\n")

                val cellParagraphs = when {
                    cell.paragraphs.isNotEmpty() -> cell.paragraphs
                    cell.text.isNotEmpty() -> cell.text.split("\n")
                    else -> emptyList()
                }
                val isCenter = cell.alignment != "right" && cell.alignment != "left"
                val isBold = cell.bold ?: true
                val sz = if (isNested) 18 else FONT_SIZE_HALF_PT

                if (cellParagraphs.isEmpty() && cell.nestedTables.isEmpty()) {
                    appendParagraph(sb, "", isCenter = isCenter, bold = isBold, sz = sz, fontName = fontName)
                } else {
                    for (pText in cellParagraphs) {
                        appendParagraph(sb, pText, isCenter = isCenter, bold = isBold, sz = sz, fontName = fontName)
                    }
                    for (nestedTbl in cell.nestedTables) {
                        appendStructuredWordTable(
                            sb = sb,
                            table = nestedTbl,
                            isNested = true,
                            availableWidthDxa = (cellWidth - 120).coerceAtLeast(600),
                            fontName = fontName
                        )
                    }
                    if (cell.nestedTables.isNotEmpty()) {
                        // OpenXML requires a <w:p> after the last <w:tbl> inside a <w:tc>
                        appendParagraph(sb, "", isCenter = isCenter, bold = isBold, sz = sz, fontName = fontName)
                    }
                }
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
        sz: Int = FONT_SIZE_HALF_PT,
        fontName: String = FONT_NAME
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
            sb.append("                <w:rFonts w:ascii=\"${escapeXml(fontName)}\" w:hAnsi=\"${escapeXml(fontName)}\" w:cs=\"${escapeXml(fontName)}\"/>\n")
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
        isCenter: Boolean = true,
        fontName: String = FONT_NAME
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
        sb.append("                <w:rFonts w:ascii=\"${escapeXml(fontName)}\" w:hAnsi=\"${escapeXml(fontName)}\" w:cs=\"${escapeXml(fontName)}\"/>\n")
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
            sb.append("                <w:rFonts w:ascii=\"${escapeXml(fontName)}\" w:hAnsi=\"${escapeXml(fontName)}\" w:cs=\"${escapeXml(fontName)}\"/>\n")
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
