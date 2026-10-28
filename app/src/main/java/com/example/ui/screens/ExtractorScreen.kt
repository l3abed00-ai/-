package com.example.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.extractor.DocumentFileReader
import com.example.extractor.JadhadhaParser
import com.example.model.JadhadhaPlan
import com.example.model.PedagogicalComponent

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExtractorScreen(
    initialComponent: PedagogicalComponent = PedagogicalComponent.READING,
    onApplyExtractedPlan: (rawText: String, topic: String, forcedComponent: PedagogicalComponent?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager: ClipboardManager = LocalClipboardManager.current

    var selectedComponentFilter by remember { mutableStateOf<PedagogicalComponent?>(initialComponent) }
    var rawInputText by remember { mutableStateOf("") }
    var topicName by remember { mutableStateOf("") }
    var uploadedFileName by remember { mutableStateOf<String?>(null) }
    var uploadedFileSize by remember { mutableLongStateOf(0L) }
    var previewPlan by remember { mutableStateOf<JadhadhaPlan?>(null) }

    // File picker launcher supporting Word .docx, text, json
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val fileInfo = DocumentFileReader.readUploadedFile(context, uri)
                uploadedFileName = fileInfo.name
                uploadedFileSize = fileInfo.sizeBytes
                rawInputText = fileInfo.textContent

                if (topicName.isBlank()) {
                    val base = fileInfo.name.substringBeforeLast(".")
                        .replace("جذاذة_", "")
                        .replace("جذاذة ", "")
                        .replace("درس_", "")
                        .replace("درس ", "")
                        .replace("مهارة_", "")
                        .replace("مهارة ", "")
                        .trim()
                    if (base.isNotBlank() && base.length in 2..50) {
                        topicName = base
                    }
                }

                if (fileInfo.textContent.isNotBlank()) {
                    previewPlan = JadhadhaParser.parseRawText(
                        raw = fileInfo.textContent,
                        fallbackTopic = topicName.ifBlank { "درس جديد" },
                        forcedComponent = selectedComponentFilter
                    )
                    Toast.makeText(context, "تم رفع واستخراج محتوى الملف بنجاح!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "الملف المرفوع فارغ أو تعذر استخراج النص منه.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "حدث خطأ أثناء قراءة الملف: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "تفريغ ونقل محتوى الجذاذات",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "ارفع ملف الجذاذة المصدر (بصيغة Word .docx أو ملف نصي) أو الصق مسودتها، وسيقوم المحرك الذكي بقراءتها وتفريغها وتوزيع عناصرها في الجذاذة النموذجية الرسمية للمكون المختار.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
            }
        }

        // Component Selection Chips
        Column {
            Text(
                text = "المكون المستهدف للتفريغ والنقل:",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilterChip(
                    selected = selectedComponentFilter == null,
                    onClick = {
                        selectedComponentFilter = null
                        if (rawInputText.isNotBlank()) {
                            previewPlan = JadhadhaParser.parseRawText(rawInputText, topicName.ifBlank { "درس جديد" }, null)
                        }
                    },
                    label = { Text("كشف تلقائي") }
                )
                PedagogicalComponent.entries.forEach { comp ->
                    FilterChip(
                        selected = selectedComponentFilter == comp,
                        onClick = {
                            selectedComponentFilter = comp
                            if (rawInputText.isNotBlank()) {
                                previewPlan = JadhadhaParser.parseRawText(rawInputText, topicName.ifBlank { "درس جديد" }, comp)
                            }
                        },
                        label = { Text(comp.title) }
                    )
                }
            }
        }

        val availableDocxFiles = remember {
            runCatching { com.example.docx.DocxGenerator.getOrCreateAvailableDocxFiles(context) }.getOrDefault(emptyList())
        }

        // Upload Source File Action Card
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "رفع الجذاذة المصدر من جهازك:",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "تُحفظ الجذاذات المصدّرة تلقائياً في مجلد «التنزيلات / Downloads» على هاتفك. يمكنك اختيار ملف من الهاتف أو الضغط على أحد ملفات Word الجاهزة أسفله:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                FilledTonalButton(
                    onClick = {
                        filePickerLauncher.launch(
                            arrayOf(
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                "text/plain",
                                "application/json",
                                "application/msword",
                                "*/*"
                            )
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("upload_source_file_button"),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.UploadFile,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "استعراض ملفات الهاتف (التنزيلات / المستندات)",
                        fontWeight = FontWeight.Bold
                    )
                }

                if (availableDocxFiles.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "ملفات Word (.docx) متوفرة في الهاتف/التطبيق (اضغط للرفع الفوري):",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        availableDocxFiles.forEach { docxFile ->
                            OutlinedButton(
                                onClick = {
                                    runCatching {
                                        val extracted = java.io.FileInputStream(docxFile).use { stream ->
                                            DocumentFileReader.extractTextFromDocx(stream)
                                        }
                                        uploadedFileName = docxFile.name
                                        uploadedFileSize = docxFile.length()
                                        rawInputText = extracted
                                        val cleanBase = docxFile.name.substringBeforeLast(".")
                                            .replace("جذاذة_", "")
                                            .replace("_", " ")
                                            .trim()
                                        if (cleanBase.isNotBlank()) {
                                            topicName = cleanBase
                                        }
                                        previewPlan = JadhadhaParser.parseRawText(
                                            raw = extracted,
                                            fallbackTopic = topicName.ifBlank { "درس جديد" },
                                            forcedComponent = selectedComponentFilter
                                        )
                                        Toast.makeText(
                                            context,
                                            "تم رفع وتفريغ ملف ${docxFile.name} بنجاح!",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(docxFile.name, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }

                // If file is currently uploaded, show badge
                AnimatedVisibility(visible = uploadedFileName != null) {
                    uploadedFileName?.let { fname ->
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = fname,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        if (uploadedFileSize > 0) {
                                            Text(
                                                text = "${(uploadedFileSize / 1024).coerceAtLeast(1)} كيلوبايت — تم استخراج المحتوى",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                IconButton(
                                    onClick = {
                                        uploadedFileName = null
                                        uploadedFileSize = 0L
                                        rawInputText = ""
                                        previewPlan = null
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "إلغاء الملف",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Secondary Shortcuts: Paste or Examples for each component
        Column {
            Text(
                text = "أو الصق مسودة أو جرب نماذج جاهزة:",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val clip = clipboardManager.getText()?.text
                        if (!clip.isNullOrBlank()) {
                            rawInputText = clip
                            previewPlan = JadhadhaParser.parseRawText(clip, topicName.ifBlank { "درس جديد" }, selectedComponentFilter)
                            uploadedFileName = null
                            Toast.makeText(context, "تم لصق المحتوى من الحافظة", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.testTag("paste_clipboard_button")
                ) {
                    Icon(imageVector = Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("لصق من الحافظة")
                }

                OutlinedButton(
                    onClick = {
                        selectedComponentFilter = PedagogicalComponent.READING
                        topicName = "أنا والمدينة"
                        rawInputText = SAMPLE_READING_TEXT
                        previewPlan = JadhadhaParser.parseRawText(SAMPLE_READING_TEXT, "أنا والمدينة", PedagogicalComponent.READING)
                        uploadedFileName = null
                    }
                ) {
                    Text("مسودة قراءة")
                }

                OutlinedButton(
                    onClick = {
                        selectedComponentFilter = PedagogicalComponent.LANGUAGE
                        topicName = "أسلوب الاختصاص"
                        rawInputText = SAMPLE_LANGUAGE_TEXT
                        previewPlan = JadhadhaParser.parseRawText(SAMPLE_LANGUAGE_TEXT, "أسلوب الاختصاص", PedagogicalComponent.LANGUAGE)
                        uploadedFileName = null
                    }
                ) {
                    Text("مسودة درس لغوي")
                }

                OutlinedButton(
                    onClick = {
                        selectedComponentFilter = PedagogicalComponent.EXPRESSION
                        topicName = "كتابة حكاية عجيبة"
                        rawInputText = SAMPLE_EXPRESSION_TEXT
                        previewPlan = JadhadhaParser.parseRawText(SAMPLE_EXPRESSION_TEXT, "كتابة حكاية عجيبة", PedagogicalComponent.EXPRESSION)
                        uploadedFileName = null
                    }
                ) {
                    Text("مسودة تعبير وإنشاء")
                }
            }
        }

        // Inputs
        OutlinedTextField(
            value = topicName,
            onValueChange = {
                topicName = it
                if (rawInputText.isNotBlank()) {
                    previewPlan = JadhadhaParser.parseRawText(rawInputText, it.ifBlank { "درس جديد" }, selectedComponentFilter)
                }
            },
            label = { Text("عنوان الدرس / المهارة (اختياري، يمكن استخراجه آليا)") },
            placeholder = { Text("مثال: اسم الفاعل / أنا والمدينة / كتابة حكاية عجيبة...") },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("extract_topic_input"),
            singleLine = true
        )

        OutlinedTextField(
            value = rawInputText,
            onValueChange = {
                rawInputText = it
                if (it.isNotBlank()) {
                    previewPlan = JadhadhaParser.parseRawText(it, topicName.ifBlank { "درس جديد" }, selectedComponentFilter)
                } else {
                    previewPlan = null
                }
            },
            label = { Text("محتوى الجذاذة المستخرج أو الملصق") },
            placeholder = {
                Text(
                    "سيظهر هنا نص الجذاذة بعد رفع الملف، أو يمكنك لصق أي محتوى لجذاذة قديمة لتفريغها ونقلها..."
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .testTag("raw_text_input")
        )

        // Action Button
        Button(
            onClick = {
                if (rawInputText.isNotBlank()) {
                    onApplyExtractedPlan(rawInputText, topicName, selectedComponentFilter)
                }
            },
            enabled = rawInputText.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("execute_extract_button"),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "نقل المحتوى إلى الجذاذة النموذجية الآن",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyLarge
            )
        }

        // Live Extraction Preview
        previewPlan?.let { plan ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "معاينة التفريغ والتحويل:",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = plan.componentType.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "• العنوان: «${plan.topic}»",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "• المستوى والوحدة: ${plan.info.level} — ${plan.info.unit}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    val totalContentItems = plan.stages.values.sumOf { it.content.size }
                    val totalTeacherItems = plan.stages.values.sumOf { it.teacher.size }
                    val totalAbilItems = plan.stages.values.sumOf { it.abil.size }
                    val totalEvalItems = plan.stages.values.sumOf { it.eval.size }

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "العناصر المصنفة في المراحل الرسمية (${plan.stages.size} مراحل):",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "• المحتوى والأنشطة التعلمية: $totalContentItems عنصرا\n" +
                                        "• أنشطة المدرس: $totalTeacherItems إجراء\n" +
                                        "• القدرات المستهدفة: $totalAbilItems هدفا\n" +
                                        "• مؤشرات التقويم والدعم: $totalEvalItems مؤشرا",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val SAMPLE_READING_TEXT = """
موضوع الدرس: أنا والمدينة
المستوى: الثالثة ثانوي إعدادي
المكون: النصوص القرائية
المجال: المجال الحضاري
الوحدة: الثالثة
الوسائل: الكتاب المدرسي والسبورة
الكفاية التواصلية: قراءة النص واستيعاب مظاهر التحول في المدينة.
الكفاية المنهجية: تحليل الصورة الفنية والمعجم الحضاري.
الكفاية الثقافية: إدراك التحديات العمرانية في المدن المعاصرة.

[مرحلة: تشخيص المكتسبات والتمهيد]
القدرات المستهدفة:
- القدرة على استحضار المكتسبات السابقة حول المجال الحضاري.
أنشطة المدرس:
- يطرح أسئلة تمهيدية لتشخيص مكتسبات المتعلمين وربطها بالدرس الجديد.
المحتوى الديداكتيكي:
- ما الفرق بين نمط العيش في القرية والمدينة؟
- ما هي أبرز المشكلات النفسية والاجتماعية التي يعاني منها سكان المدن الكبرى؟
التقويم والدعم:
- تقويم تشخيصي لقياس استعداد المتعلمين للانخراط في الدرس.

[مرحلة: ملاحظة النص وتأطيره]
القدرات المستهدفة:
- القدرة على ملاحظة العتبات النصية وتأطير النص وصياغة فرضيات القراءة.
أنشطة المدرس:
- يوجه المتعلمين لملاحظة العنوان والصورة المرفقة وصاحب النص لبناء فرضيات القراءة.
المحتوى الديداكتيكي:
- العنوان: مركب عطفي يربط الذات الشاعرة بالفضاء الحضري (المدينة).
- صاحب النص: أحمد عبد المعطي حجازي، شاعر مصري رائد في حركة الشعر الحر.
- نوعية النص: قصيدة شعرية من شعر التفعيلة ذات بعد حضاري وإنساني.
- فرضية القراءة: نفترض أن النص يصور غربة الشاعر ووحدته وسط صخب المدينة.
التقويم والدعم:
- تقويم تكويني لمدى قدرة المتعلمين على التقاط المؤشرات الدالة وصياغة الفرضيات.

[مرحلة: فهم النص]
القدرات المستهدفة:
- القدرة على القراءة السليمة والمعبرة واستخلاص مضامين القصيدة.
أنشطة المدرس:
- يقرأ القصيدة قراءة نموذجية ثم يوزع القراءات الفردية ويشرح المفردات الصعبة.
المحتوى الديداكتيكي:
- الفكرة العامة: تصوير وحشة الشاعر وغربته النفسية في شوارع المدينة المزدحمة.
- الأفكار الأساسية:
- وصول الشاعر إلى المدينة وانبهاره بضخامتها وقسوة عمرانها.
- إحساسه بالضياع والوحدة وسط الزحام وانغلاق الأبواب.
التقويم والدعم:
- تقويم تكويني لمراقبة سلامة القراءة وتمحيص الفرضيات السابقة.

[مرحلة: القراءة التحليلية]
القدرات المستهدفة:
- القدرة على تحليل الحقول المعجمية والخصائص الفنية والأسلوبية في القصيدة.
أنشطة المدرس:
- يساعد المتعلمين على استخراج الحقول الدلالية والصور الشعرية والقيم المتضمنة.
المحتوى الديداكتيكي:
- الحقول المعجمية: حقل المدينة (الأبراج، الشوارع، الأسوار، الزحام) وحقل المعاناة والغربة (الحيرة، الضياع، الصمت، الظلام).
- أساليب النص: توظيف الانزياح المجازي، التكرار، والصور الشعرية الموحية بالاغتراب.
- القيم المتضمنة: قيمة إنسانية وحضارية تدعو إلى أنسنة العلاقات داخل المدينة.
التقويم والدعم:
- تقويم تكويني لقياس استيعاب المتعلمين للحقول المعجمية والخصائص البلاغية.

[مرحلة: التركيب]
القدرات المستهدفة:
- القدرة على تجميع معطيات الفهم والتحليل في خلاصة تركيبية منسجمة.
أنشطة المدرس:
- يطالب المتعلمين بصياغة تركيب شامل لمضمون القصيدة وخصائصها الفنية.
المحتوى الديداكتيكي:
- تعتبر قصيدة «أنا والمدينة» مرآة فنية تعكس أزمة الإنسان المعاصر واغترابه الروحي في المدينة الحديثة بلغة شعرية موحية.
التقويم والدعم:
- تقويم إجمالي لجودة الصياغة التركيبية وسلامة اللغة.

[مرحلة: الاستثمار والامتداد]
القدرات المستهدفة:
- القدرة على استثمار مكتسبات النص في إنتاج كتابي شخصي.
أنشطة المدرس:
- يكلف المتعلمين بإنجاز نشاط الاستثمار ويشرح خطوات الإنجاز.
المحتوى الديداكتيكي:
- اكتب فقرة من سبعة أسطر تقارن فيها بين إيجابيات وسلبيات العيش في المدينة المعاصرة.
التقويم والدعم:
- متابعة وتوجيه إنجازات المتعلمين وتقويم امتدادات التعلم.
"""

private const val SAMPLE_LANGUAGE_TEXT = """
المكون: الدرس اللغوي
الموضوع: أسلوب الاختصاص
المستوى: الثالثة ثانوي إعدادي
الوحدة: الخامسة
المجال: المجال الاجتماعي
الكفاية التواصلية: تعرف أسلوب الاختصاص وتمييز الاسم المختص وإعرابه.
الكفاية المنهجية: تحليل بنية أسلوب الاختصاص وعناصره التركيبية.

[مرحلة: التقويم التشخيصي والتمهيد]
القدرات المستهدفة:
- القدرة على استحضار أحكام الضمائر ومفهوم المفعول به للتمهيد للدرس.
أنشطة المدرس:
- يطرح أسئلة تشخيصية لاسترجاع المكتسبات النحوية السابقة المرتبطة بالضمائر.
المحتوى الديداكتيكي:
- استحضار أحكام الضمائر المنفصلة والمتصلة ومفهوم المفعول به وعلامات نصبه.
التقويم والدعم:
- تقويم تشخيصي لرصد استعداد المتعلمين وتمكنهم من المكتسبات القبلية.

[مرحلة: ملاحظة الظاهرة اللغوية وعزلها]
القدرات المستهدفة:
- القدرة على ملاحظة جمل الانطلاق وعزل تراكيب أسلوب الاختصاص.
أنشطة المدرس:
- يدون أمثلة الانطلاق على السبورة ويقرؤها ويوجه المتعلمين لملاحظة الأسماء المختصة.
المحتوى الديداكتيكي:
- نحن - المغاربةَ - نعتز بوطننا.
- أنا - التلميذَ - أجدّ في دراستي.
- نحن - معشرَ الشبابِ - نطمح للمستقبل.
- أنتم - أيّها المتعلمون - عماد النهضة.
التقويم والدعم:
- تقويم تكويني لسلامة قراءة الشواهد ودقة عزل الظاهرة اللغوية.

[مرحلة: الوصف والتحليل]
القدرات المستهدفة:
- القدرة على وصف عناصر أسلوب الاختصاص وتحديد حالات الاسم المختص وإعرابه.
أنشطة المدرس:
- يرسم جدول التحليل على السبورة ويقود المتعلمين لاستنباط حالات الاسم المختص وحكمه الإعرابي.
المحتوى الديداكتيكي:
- الاسم المختص اسم ظاهر معرف يقع بعد ضمير المتكلم غالباً (أو المخاطب أحياناً) لتوضيح المقصود منه.
- حالات الاسم المختص: مقترن بـ (أل)، أو مضاف إلى معرفة، أو بلفظ (أيّها / أيّتها) المتلو بنعت مرفوع.
- حكمه الإعرابي: مفعول به منصوب على الاختصاص بفعل محذوف وجوباً تقديره (أخصّ أو أعني).
التقويم والدعم:
- تقويم تكويني بمطالبة المتعلمين بصياغة أمثلة موازية لكل حالة من حالات الاسم المختص.

[مرحلة: الاستنتاج]
القدرات المستهدفة:
- القدرة على صياغة القاعدة الضابطة لأسلوب الاختصاص.
أنشطة المدرس:
- يوجه المتعلمين لتجميع الاستنتاجات الجزئية وبناء القاعدة العامة على السبورة.
المحتوى الديداكتيكي:
- أسلوب الاختصاص تركيب يتكون من ضمير للمتكلم أو المخاطب يليه اسم مختص يفسره ويبين المقصود منه.
- يأتي الاسم المختص معرفاً بـ (أل) أو مضافاً إلى معرفة أو بلفظ (أيّها / أيّتها)، ويعرب مفعولاً به منصوباً بفعل محذوف وجوباً تقديره أخصّ.
التقويم والدعم:
- تقويم تكويني للتأكد من استيعاب عناصر القاعدة التركيبية.

[مرحلة: التطبيقات]
القدرات المستهدفة:
- القدرة على تطبيق قاعدة أسلوب الاختصاص في التحديد والتركيب والإعراب.
أنشطة المدرس:
- يكلف المتعلمين بإنجاز التمارين التطبيقية ويشرف على التصحيح الجماعي والفردي.
المحتوى الديداكتيكي:
- عين الاسم المختص وبيّن حالته وإعرابه في: «نحن - الرياضيينَ - نحرص على النشاط».
- أعرب إعراباً تاماً: «نحن - معشرَ الأساتذةِ - نربي الأجيال».
التقويم والدعم:
- تقويم إجمالي ودعم فوري لتصويب الأخطاء الإعرابية والتركيبية.
"""

private const val SAMPLE_EXPRESSION_TEXT = """
المكون: التعبير والإنشاء
الموضوع: مهارة كتابة حكاية عجيبة أو من الخيال العلمي
المستوى: الثالثة ثانوي إعدادي
الوحدة: الثالثة: المجال الحضاري
الكفاية التواصلية: نسج حكاية خيالية عجيبة بلغة سردية محكمة.
الكفاية المنهجية: احترام الخطوات المنهجية لبناء حكاية عجيبة.

[مرحلة: الحصة الأولى: أنشطة الاكتساب]
القدرات المستهدفة:
- القدرة على تعرف مفهوم الحكاية العجيبة وأدب الخيال العلمي وخصائصهما البنائية.
أنشطة المدرس:
- يوجه المتعلمين لقراءة نص الانطلاق واستخلاص مقومات وخطوات مهارة كتابة حكاية عجيبة.
المحتوى الديداكتيكي:
- التعريف بالمهارة ومفهوم الحكاية العجيبة وأدب الخيال العلمي.
- عناصر المهارة: الشخصيات الغريبة أو الفضائية، الزمان المستقبلي والمكان الخارق، الأحداث غير المألوفة.
- سيرورة السرد: وضعية البداية ← الحدث المحرك ← العقدة ← الحل والانفراج ← وضعية النهاية.
التقويم والدعم:
- تقويم تكويني لمدى استيعاب المتعلمين لعناصر وخطوات بناء الحكاية العجيبة.

[مرحلة: الحصة الثانية: أنشطة التطبيق]
القدرات المستهدفة:
- القدرة على تطبيق خطوات المهارة بشكل جماعي وموجه في كتابة حكاية عجيبة.
أنشطة المدرس:
- يدون نص الموضوع التطبيقي على السبورة ويساعد المتعلمين على وضع التصميم وتحرير نموذج تطبيقي.
المحتوى الديداكتيكي:
- نص الموضوع: كنت تتجول فإذا ببوابة زمنية تنقلك إلى الفضاء... احكِ تفاصيل المغامرة مستثمراً خطوات المهارة.
- توجيهات الإنجاز: تحديد الزمان والمكان والشخصيات، وبناء الحبكة السردية بتسلسل مشوق.
التقويم والدعم:
- تقويم تكويني لمراقبة احترام خطوات التصميم المنهجي أثناء التدريب.

[مرحلة: الحصة الثالثة: أنشطة الإنتاج]
القدرات المستهدفة:
- القدرة على الإنجاز الفردي المستقل لحكاية عجيبة أو من الخيال العلمي.
أنشطة المدرس:
- يقدم وضعية الإنتاج الفردي ويشرح تعليمة الإنجاز ويتتبع تحرير المتعلمين في مسوداتهم.
المحتوى الديداكتيكي:
- وضعية الانطلاق وتصميم خطاطة الإنجاز والتحرير الفردي في مسودة مع احترام الروابط السردية وعلامات الترقيم.
التقويم والدعم:
- تقويم إجمالي وجمع المسودات والمنتوجات لتشخيص التعثرات وإعداد حصة التصحيح.

[مرحلة: الحصة الرابعة: أنشطة التصحيح والدعم]
القدرات المستهدفة:
- القدرة على تشخيص الأخطاء السردية واللغوية وتصويبها وتجويد الإنتاج الكتابي.
أنشطة المدرس:
- يعرض الأخطاء الشائعة على السبورة ويشرف على التصحيح الجماعي والتقويم الذاتي.
المحتوى الديداكتيكي:
- شبكة التقويم الذاتي: ملاءمة الموضوع لتقنيات الحكاية العجيبة (4ن)، تسلسل الأحداث السردية (2ن)، سلامة اللغة والترقيم (4ن).
التقويم والدعم:
- تقويم داعم للتحقق من تدارك التعثرات وتثمين الإبداعات السردية المتميزة.
"""
