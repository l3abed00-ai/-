package com.example.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ai.GeminiAiService
import com.example.docx.DocxGenerator
import com.example.extractor.DetectedFileType
import com.example.extractor.DocumentFileReader
import com.example.extractor.DocumentReadException
import com.example.extractor.JadhadhaParser
import com.example.model.ParseResult
import com.example.model.PedagogicalComponent
import com.example.model.StageCatalog
import com.example.model.UnclassifiedLine
import com.example.ui.ExtractionState
import com.example.ui.JadhadhaViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExtractorScreen(
    initialComponent: PedagogicalComponent = PedagogicalComponent.READING,
    onApplyExtractedPlan: (rawText: String, topic: String, forcedComponent: PedagogicalComponent?, preParsedResult: ParseResult?) -> Unit,
    viewModel: JadhadhaViewModel? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager: ClipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    val vmState = viewModel?.uiState?.collectAsState()?.value

    // Local fallback state when ExtractorScreen is rendered standalone without a ViewModel
    var localComponentFilter by remember { mutableStateOf<PedagogicalComponent?>(null) }
    var localRawInputText by remember { mutableStateOf("") }
    var localTopicName by remember { mutableStateOf("") }
    var localUploadedFileName by remember { mutableStateOf<String?>(null) }
    var localUploadedFileSize by remember { mutableLongStateOf(0L) }
    var localParseResult by remember { mutableStateOf<ParseResult?>(null) }
    var localExtractionState by remember { mutableStateOf<ExtractionState>(ExtractionState.Idle) }
    var localIsAiTranscribed by remember { mutableStateOf(false) }
    var localAutoDelegateAi by remember { mutableStateOf(false) }
    var localShowPdfConfirmDialog by remember { mutableStateOf(false) }
    var localShowPdfKeyDialog by remember { mutableStateOf(false) }
    var localPendingPdfBytes by remember { mutableStateOf<ByteArray?>(null) }
    var localPendingPdfName by remember { mutableStateOf<String?>(null) }
    var localPendingPdfSize by remember { mutableLongStateOf(0L) }
    var localCustomApiKey by remember { mutableStateOf("") }
    var localJob by remember { mutableStateOf<Job?>(null) }

    val selectedComponentFilter = if (vmState != null) vmState.extractorComponentFilter else localComponentFilter
    val rawInputText = if (vmState != null) vmState.extractorRawText else localRawInputText
    val topicName = if (vmState != null) vmState.extractorTopicName else localTopicName
    val uploadedFileName = if (vmState != null) vmState.extractorFileName else localUploadedFileName
    val uploadedFileSize = if (vmState != null) vmState.extractorFileSize else localUploadedFileSize
    val parseResult = if (vmState != null) vmState.extractorParseResult else localParseResult
    val extractionState = if (vmState != null) vmState.extraction else localExtractionState
    val isAiTranscribed = if (vmState != null) vmState.extractorIsAiTranscribed else localIsAiTranscribed
    val showPdfConfirmDialog = if (vmState != null) vmState.showPdfConfirmationDialog else localShowPdfConfirmDialog
    val showPdfKeyDialog = if (vmState != null) vmState.showPdfApiKeyDialog else localShowPdfKeyDialog
    val pendingPdfFileName = if (vmState != null) vmState.pendingPdfFileName else localPendingPdfName
    val currentApiKey = if (vmState != null) vmState.customApiKey else localCustomApiKey

    val isBusy = extractionState is ExtractionState.Reading || extractionState is ExtractionState.Parsing

    // Show quick Arabic Toast whenever extractionState enters Error or Done from file upload
    LaunchedEffect(extractionState) {
        when (val st = extractionState) {
            is ExtractionState.Error -> {
                Toast.makeText(context, st.message, Toast.LENGTH_LONG).show()
            }
            is ExtractionState.Done -> {
                if (st.fileName != null) {
                    val toastMsg = if (st.isAiTranscribed) {
                        "فُرِّغ هذا الملف بالذكاء الاصطناعي، راجع النص"
                    } else {
                        "تم رفع وتفريغ الملف «${st.fileName}» بنجاح!"
                    }
                    Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                }
            }
            else -> {}
        }
    }

    // Track bound Model Jadhadha templates from "الملف التربوي" (Google Drive)
    var templateRefreshTick by remember { mutableStateOf(0) }
    var targetTemplateComponent by remember { mutableStateOf(PedagogicalComponent.LANGUAGE) }

    val modelTemplatePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val savedName = DocxGenerator.saveModelTemplate(context, targetTemplateComponent, uri)
            templateRefreshTick++
            if (savedName != null) {
                Toast.makeText(
                    context,
                    "تم ربط الجذاذة النموذجية «$savedName» لمكون ${targetTemplateComponent.title} بنجاح!",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // Run parsing on Dispatchers.Default in standalone mode whenever input, topic, or component filter changes
    LaunchedEffect(localRawInputText, localTopicName, localComponentFilter) {
        if (viewModel == null) {
            if (localRawInputText.isBlank()) {
                localParseResult = null
                if (localExtractionState is ExtractionState.Parsing) {
                    localExtractionState = ExtractionState.Idle
                }
            } else {
                localExtractionState = ExtractionState.Parsing
                val initial = withContext(Dispatchers.Default) {
                    ensureActive()
                    JadhadhaParser.parseWithReport(
                        raw = localRawInputText,
                        fallbackTopic = localTopicName.ifBlank { "درس جديد" },
                        forcedComponent = localComponentFilter
                    )
                }
                if (localAutoDelegateAi) {
                    localAutoDelegateAi = false
                    val refined = withContext(Dispatchers.Default) {
                        ensureActive()
                        GeminiAiService.classifyAndRefineExtraction(
                            rawText = localRawInputText,
                            initialResult = initial,
                            customKey = localCustomApiKey
                        )
                    }
                    localParseResult = refined
                } else {
                    localParseResult = initial
                }
                localExtractionState = ExtractionState.Done(
                    fileName = localUploadedFileName,
                    extractedText = localRawInputText,
                    isAiTranscribed = localIsAiTranscribed
                )
            }
        }
    }

    fun launchFileExtraction(uri: Uri, initialName: String? = null) {
        if (viewModel != null) {
            viewModel.readAndExtractFile(context, uri, initialName)
        } else {
            localJob?.cancel()
            val startName = initialName ?: uri.lastPathSegment?.substringAfterLast('/') ?: "ملف_جذاذة"
            localExtractionState = ExtractionState.Reading(startName)
            localJob = coroutineScope.launch {
                try {
                    ensureActive()
                    val fileInfo = withContext(Dispatchers.IO) {
                        DocumentFileReader.readUploadedFile(context, uri)
                    }
                    ensureActive()
                    if (fileInfo.detectedType == DetectedFileType.PDF) {
                        val pdfBytes = fileInfo.pdfBytes
                        if (pdfBytes == null || pdfBytes.isEmpty()) {
                            localExtractionState = ExtractionState.Error(DocumentFileReader.MSG_FILE_EMPTY)
                            return@launch
                        }
                        if (pdfBytes.size > GeminiAiService.MAX_PDF_INLINE_BYTES) {
                            localExtractionState = ExtractionState.Error(
                                "حجم ملف PDF يتجاوز الحد الأقصى المسموح به للتفريغ (10 ميغابايت)."
                            )
                            return@launch
                        }
                        localPendingPdfBytes = pdfBytes
                        localPendingPdfName = fileInfo.name
                        localPendingPdfSize = fileInfo.sizeBytes
                        if (!GeminiAiService.hasValidApiKey(localCustomApiKey)) {
                            localExtractionState = ExtractionState.Error(GeminiAiService.MSG_PDF_NEEDS_KEY)
                            localShowPdfKeyDialog = true
                        } else {
                            localExtractionState = ExtractionState.Idle
                            localShowPdfConfirmDialog = true
                        }
                        return@launch
                    }

                    localUploadedFileName = fileInfo.name
                    localUploadedFileSize = fileInfo.sizeBytes
                    val cleanedFromName = DocumentFileReader.cleanTopicFromFilename(fileInfo.name)
                    if (cleanedFromName.isNotBlank() && cleanedFromName.length in 2..60) {
                        localTopicName = cleanedFromName
                    }
                    localIsAiTranscribed = false
                    localAutoDelegateAi = true
                    localRawInputText = fileInfo.textContent
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DocumentReadException) {
                    localExtractionState = ExtractionState.Error(e.arabicMessage)
                } catch (_: Exception) {
                    localExtractionState = ExtractionState.Error(DocumentFileReader.MSG_FILE_CORRUPTED_OR_PROTECTED)
                }
            }
        }
    }

    fun confirmPdfUpload() {
        if (viewModel != null) {
            viewModel.confirmPdfTranscription()
        } else {
            val bytes = localPendingPdfBytes ?: return
            val name = localPendingPdfName ?: "ملف.pdf"
            val size = localPendingPdfSize
            localShowPdfConfirmDialog = false
            localJob?.cancel()
            localExtractionState = ExtractionState.Reading(name)
            localJob = coroutineScope.launch {
                try {
                    val res = GeminiAiService.transcribeDocument(
                        bytes = bytes,
                        mimeType = "application/pdf",
                        customKey = localCustomApiKey
                    )
                    res.onSuccess { text ->
                        localUploadedFileName = name
                        localUploadedFileSize = size
                        val cleaned = DocumentFileReader.cleanTopicFromFilename(name)
                        if (cleaned.isNotBlank() && cleaned.length in 2..60) {
                            localTopicName = cleaned
                        }
                        localIsAiTranscribed = true
                        localAutoDelegateAi = true
                        localRawInputText = text
                    }.onFailure { err ->
                        localExtractionState = ExtractionState.Error(
                            err.message ?: "تعذر تفريغ ملف PDF عبر Gemini."
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    localExtractionState = ExtractionState.Error("تعذر تفريغ ملف PDF عبر Gemini.")
                }
            }
        }
    }

    // Call site 1: File picker launcher supporting Word .docx, PDF, text
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            launchFileExtraction(uri)
        }
    }

    // PDF Confirmation Dialog: «سيُرسل هذا الملف إلى خوادم Google لتفريغه» (موافق/إلغاء)
    if (showPdfConfirmDialog) {
        AlertDialog(
            onDismissRequest = {
                if (viewModel != null) viewModel.dismissPdfDialogs() else localShowPdfConfirmDialog = false
            },
            title = {
                Text(
                    text = "تأكيد تفريغ ملف PDF",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "سيُرسل هذا الملف إلى خوادم Google لتفريغه",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("pdf_confirm_dialog_message")
                    )
                    pendingPdfFileName?.let { fname ->
                        Text(
                            text = "الملف: $fname",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { confirmPdfUpload() },
                    modifier = Modifier.testTag("pdf_confirm_accept_button")
                ) {
                    Text("موافق", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        if (viewModel != null) viewModel.dismissPdfDialogs() else localShowPdfConfirmDialog = false
                    },
                    modifier = Modifier.testTag("pdf_confirm_cancel_button")
                ) {
                    Text("إلغاء")
                }
            }
        )
    }

    // Missing Gemini API Key Dialog for PDF: «تفريغ PDF يحتاج مفتاح Gemini؛ أدخله أو حوّل الملف إلى docx»
    if (showPdfKeyDialog) {
        var keyDraft by remember(currentApiKey) { mutableStateOf(currentApiKey) }
        AlertDialog(
            onDismissRequest = {
                if (viewModel != null) viewModel.dismissPdfDialogs() else localShowPdfKeyDialog = false
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "مفتاح Gemini مطلوب لتفريغ PDF",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = GeminiAiService.MSG_PDF_NEEDS_KEY,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("pdf_missing_key_dialog_message")
                    )
                    OutlinedTextField(
                        value = keyDraft,
                        onValueChange = { keyDraft = it },
                        label = { Text("مفتاح Gemini API (AIza...)") },
                        placeholder = { Text("الصق مفتاح Gemini هنا...") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pdf_api_key_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (viewModel != null) {
                            viewModel.submitPdfApiKey(keyDraft)
                        } else {
                            localCustomApiKey = keyDraft.trim()
                            localShowPdfKeyDialog = false
                            if (GeminiAiService.hasValidApiKey(localCustomApiKey) && localPendingPdfBytes != null) {
                                localExtractionState = ExtractionState.Idle
                                localShowPdfConfirmDialog = true
                            }
                        }
                    },
                    enabled = keyDraft.trim().isNotBlank(),
                    modifier = Modifier.testTag("pdf_api_key_save_button")
                ) {
                    Text("حفظ ومتابعة", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (viewModel != null) viewModel.dismissPdfDialogs() else localShowPdfKeyDialog = false
                    },
                    modifier = Modifier.testTag("pdf_api_key_cancel_button")
                ) {
                    Text("إلغاء")
                }
            }
        )
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
                        text = "تفريغ ونقل محتوى الجذاذات (مع الذكاء الاصطناعي المدمج)",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "ارفع ملف الجذاذة المصدر (.docx أو PDF أو نص عربي) أو الصق مسودتها، وسيتولى نموذج الذكاء الاصطناعي المدمج تصنيف الأسطر والجداول الفرعية وتنقيح المراحل مع الحفاظ التام على تنسيق الجذاذة النموذجية (خط Sakkal Majalla 10pt عريض والألوان الرسمية) في جميع المكونات.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
            }
        }

        // Model Jadhadha Formatting & Google Drive "الملف التربوي" Template Binding Card
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("model_template_drive_card")
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "تنسيق الجذاذة النموذجية (مجلد «الملف التربوي» — Google Drive):",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Surface(
                        color = Color(0xFFD1FAE5),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "Sakkal Majalla 10pt Bold ✓",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF065F46),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "يحافظ التطبيق تلقائياً على تنسيق الجذاذات النموذجية في جميع المكونات (الألوان، الإطارات، الجداول التحليلية، وخط Sakkal Majalla بحجم 10pt عريض). كما يمكنك ربط ملف الجذاذة النموذجية من مجلد «الملف التربوي» على Google Drive لأي مكون:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Per-component template binding buttons
                val _tick = templateRefreshTick
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PedagogicalComponent.entries.forEach { comp ->
                        val boundName = remember(comp, _tick) {
                            DocxGenerator.getModelTemplateName(context, comp)
                        }
                        OutlinedButton(
                            onClick = {
                                targetTemplateComponent = comp
                                modelTemplatePickerLauncher.launch(
                                    arrayOf(
                                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                        "application/msword",
                                        "*/*"
                                    )
                                )
                            },
                            modifier = Modifier.testTag("bind_template_${comp.name.lowercase()}")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (boundName != null) Color(0xFF059669) else MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (boundName != null) "نموذج ${comp.title}: $boundName ✓" else "ربط نموذج ${comp.title} (Google Drive)",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
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
                        if (viewModel != null) {
                            viewModel.updateExtractorComponentFilter(null)
                        } else {
                            localComponentFilter = null
                        }
                    },
                    label = { Text("كشف تلقائي") },
                    modifier = Modifier.testTag("chip_component_auto")
                )
                PedagogicalComponent.entries.forEach { comp ->
                    FilterChip(
                        selected = selectedComponentFilter == comp,
                        onClick = {
                            if (viewModel != null) {
                                viewModel.updateExtractorComponentFilter(comp)
                            } else {
                                localComponentFilter = comp
                            }
                        },
                        label = { Text(comp.title) },
                        modifier = Modifier.testTag("chip_component_${comp.name.lowercase()}")
                    )
                }
            }
        }

        val availableDocxFiles = remember {
            runCatching { DocxGenerator.getOrCreateAvailableDocxFiles(context) }.getOrDefault(emptyList())
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
                    text = "رفع الجذاذة المصدر من جهازك أو Google Drive:",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "تُحفظ الجذاذات المصدّرة تلقائياً في مجلد «التنزيلات / Downloads» على هاتفك. يمكنك اختيار ملف الجذاذة المصدر (.docx أو PDF أو نص) من الهاتف أو Google Drive أو الضغط على أحد ملفات Word الجاهزة أسفله:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                FilledTonalButton(
                    onClick = {
                        filePickerLauncher.launch(
                            arrayOf(
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                "application/pdf",
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
                        text = "استعراض ملفات الهاتف / Google Drive (الملف التربوي)",
                        fontWeight = FontWeight.Bold
                    )
                }

                // Call site 2: Quick-load available .docx files using readUploadedFile on Dispatchers.IO
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
                                    launchFileExtraction(Uri.fromFile(docxFile), docxFile.name)
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
                                        if (viewModel != null) {
                                            viewModel.clearExtractedFile()
                                        } else {
                                            localUploadedFileName = null
                                            localUploadedFileSize = 0L
                                            localRawInputText = ""
                                            localParseResult = null
                                            localIsAiTranscribed = false
                                            localExtractionState = ExtractionState.Idle
                                        }
                                    },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "إلغاء الملف",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Prominent Arabic Error Alert Card when extractionState is ExtractionState.Error
        if (extractionState is ExtractionState.Error) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(12.dp))
                    .testTag("extractor_error_card")
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = "تنبيه خطأ",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = extractionState.message,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.testTag("extractor_error_text")
                            )
                        }
                        IconButton(
                            onClick = {
                                if (viewModel != null) {
                                    viewModel.clearExtractionError()
                                } else {
                                    localExtractionState = ExtractionState.Idle
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("dismiss_error_card_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "إغلاق التنبيه",
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    if (extractionState.message == GeminiAiService.MSG_PDF_NEEDS_KEY) {
                        Spacer(modifier = Modifier.height(8.dp))
                        FilledTonalButton(
                            onClick = {
                                if (viewModel != null) {
                                    viewModel.submitPdfApiKey(currentApiKey)
                                }
                                localShowPdfKeyDialog = true
                            },
                            modifier = Modifier.testTag("open_pdf_key_dialog_button")
                        ) {
                            Icon(imageVector = Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("إدخال مفتاح Gemini الآن", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Progress Indicator with «إلغاء» (Cancel) button during Reading or Parsing
        if (extractionState is ExtractionState.Reading || extractionState is ExtractionState.Parsing) {
            val statusLabel = when (extractionState) {
                is ExtractionState.Reading -> "جارٍ قراءة الملف «${extractionState.fileName}»..."
                is ExtractionState.Parsing -> "جارٍ تحليل الجذاذة وتصنيف الخانات بالذكاء الاصطناعي المدمج..."
                else -> "جارٍ المعالجة..."
            }
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("extractor_loading_indicator")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            if (viewModel != null) {
                                viewModel.cancelExtraction()
                            } else {
                                localJob?.cancel()
                                localJob = null
                                localExtractionState = ExtractionState.Idle
                            }
                            Toast.makeText(context, "تم إلغاء عملية القراءة والتفريغ.", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.testTag("cancel_extraction_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "إلغاء",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("إلغاء", fontWeight = FontWeight.Bold)
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
                            if (viewModel != null) {
                                viewModel.updateExtractorRawText(clip, clearUploadedFile = true)
                            } else {
                                localRawInputText = clip
                                localUploadedFileName = null
                                localIsAiTranscribed = false
                            }
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
                        if (viewModel != null) {
                            viewModel.updateExtractorRawText(
                                newText = SAMPLE_READING_TEXT,
                                newTopic = "أنا والمدينة",
                                newComponentFilter = PedagogicalComponent.READING,
                                clearUploadedFile = true
                            )
                        } else {
                            localComponentFilter = PedagogicalComponent.READING
                            localTopicName = "أنا والمدينة"
                            localRawInputText = SAMPLE_READING_TEXT
                            localUploadedFileName = null
                            localIsAiTranscribed = false
                        }
                    }
                ) {
                    Text("مسودة قراءة")
                }

                OutlinedButton(
                    onClick = {
                        if (viewModel != null) {
                            viewModel.updateExtractorRawText(
                                newText = SAMPLE_LANGUAGE_TEXT,
                                newTopic = "أسلوب الاختصاص",
                                newComponentFilter = PedagogicalComponent.LANGUAGE,
                                clearUploadedFile = true
                            )
                        } else {
                            localComponentFilter = PedagogicalComponent.LANGUAGE
                            localTopicName = "أسلوب الاختصاص"
                            localRawInputText = SAMPLE_LANGUAGE_TEXT
                            localUploadedFileName = null
                            localIsAiTranscribed = false
                        }
                    }
                ) {
                    Text("مسودة درس لغوي")
                }

                OutlinedButton(
                    onClick = {
                        if (viewModel != null) {
                            viewModel.updateExtractorRawText(
                                newText = SAMPLE_EXPRESSION_TEXT,
                                newTopic = "كتابة حكاية عجيبة",
                                newComponentFilter = PedagogicalComponent.EXPRESSION,
                                clearUploadedFile = true
                            )
                        } else {
                            localComponentFilter = PedagogicalComponent.EXPRESSION
                            localTopicName = "كتابة حكاية عجيبة"
                            localRawInputText = SAMPLE_EXPRESSION_TEXT
                            localUploadedFileName = null
                            localIsAiTranscribed = false
                        }
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
                if (viewModel != null) {
                    viewModel.updateExtractorTopic(it)
                } else {
                    localTopicName = it
                }
            },
            label = { Text("عنوان الدرس / المهارة (اختياري، يمكن استخراجه آليا)") },
            placeholder = { Text("مثال: اسما الزمان والمكان / أنا والمدينة / كتابة حكاية عجيبة...") },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("extract_topic_input"),
            singleLine = true
        )

        OutlinedTextField(
            value = rawInputText,
            onValueChange = {
                if (viewModel != null) {
                    viewModel.updateExtractorRawText(it)
                } else {
                    localRawInputText = it
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
                .height(190.dp)
                .testTag("raw_text_input")
        )

        // Extraction Review Card («مراجعة التفريغ») BEFORE applying
        parseResult?.let { currentResult ->
            ExtractionReviewCard(
                parseResult = currentResult,
                isAiTranscribed = isAiTranscribed,
                onDelegateToBuiltInAi = {
                    if (viewModel != null) {
                        viewModel.delegateExtractorToBuiltInAi()
                        Toast.makeText(
                            context,
                            "تم إسناد المهمة إلى الذكاء الاصطناعي المدمج: صُنّفت جميع الأسطر ونُقّحت المراحل بنجاح ✨",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        coroutineScope.launch {
                            localExtractionState = ExtractionState.Parsing
                            val refined = withContext(Dispatchers.Default) {
                                GeminiAiService.classifyAndRefineExtraction(
                                    rawText = rawInputText,
                                    initialResult = currentResult,
                                    customKey = currentApiKey
                                )
                            }
                            localParseResult = refined
                            localExtractionState = ExtractionState.Done(
                                fileName = uploadedFileName,
                                extractedText = rawInputText,
                                isAiTranscribed = isAiTranscribed
                            )
                            Toast.makeText(
                                context,
                                "تم إسناد المهمة إلى الذكاء الاصطناعي المدمج: صُنّفت جميع الأسطر ونُقّحت المراحل بنجاح ✨",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                },
                onChangeComponentAndReparse = { newComp ->
                    if (viewModel != null) {
                        viewModel.updateExtractorComponentFilter(newComp)
                    } else {
                        localComponentFilter = newComp
                    }
                },
                onAssignUnclassifiedLine = { line, stageKey, field ->
                    if (viewModel != null) {
                        viewModel.assignExtractorUnclassifiedLine(line, stageKey, field)
                    } else {
                        localParseResult = JadhadhaParser.assignUnclassifiedLine(
                            currentResult = currentResult,
                            line = line,
                            targetStageKey = stageKey,
                            targetField = field
                        )
                    }
                    Toast.makeText(
                        context,
                        "تم إسناد السطر (${line.lineNumber}) إلى الخانة المختارة بنجاح (المصدر: من الملف ✓)",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onIgnoreUnclassifiedLine = { line ->
                    if (viewModel != null) {
                        viewModel.ignoreExtractorUnclassifiedLine(line)
                    } else {
                        localParseResult = JadhadhaParser.ignoreUnclassifiedLine(currentResult, line)
                    }
                }
            )
        }

        // Action Button
        Button(
            onClick = {
                if (rawInputText.isNotBlank()) {
                    onApplyExtractedPlan(rawInputText, topicName, selectedComponentFilter, parseResult)
                }
            },
            enabled = rawInputText.isNotBlank() && !isBusy,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
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
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExtractionReviewCard(
    parseResult: ParseResult,
    isAiTranscribed: Boolean = false,
    onDelegateToBuiltInAi: () -> Unit,
    onChangeComponentAndReparse: (PedagogicalComponent) -> Unit,
    onAssignUnclassifiedLine: (UnclassifiedLine, stageKey: String, field: String) -> Unit,
    onIgnoreUnclassifiedLine: (UnclassifiedLine) -> Unit
) {
    val plan = parseResult.plan
    val report = parseResult.report
    val stages = StageCatalog.getStagesForComponent(plan.componentType)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("extraction_review_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.FactCheck,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "مراجعة التفريغ قبل التطبيق",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = report.detectedComponent.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            // AI PDF Transcription Notice Banner: «فُرِّغ هذا الملف بالذكاء الاصطناعي، راجع النص»
            if (isAiTranscribed) {
                Surface(
                    color = Color(0xFFFEF3C7),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFFD97706), RoundedCornerShape(10.dp))
                        .testTag("ai_transcribed_review_notice")
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = Color(0xFFB45309),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "فُرِّغ هذا الملف بالذكاء الاصطناعي، راجع النص",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF92400E)
                        )
                    }
                }
            }

            // 1. Summary Banner: «س خانة من ملفك، ص خانة افتراضية، ع سطرًا غير مصنّف»
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("review_summary_banner")
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    val aiSummarySuffix = if (report.totalAiFields > 0) " (${report.totalAiFields} خانة ذكاء اصطناعي ✨)" else ""
                    Text(
                        text = "«${report.totalFileFields} خانة من ملفك، ${report.totalDefaultFields} خانة افتراضية، ${report.unclassifiedLines.size} سطرًا غير مصنّف»$aiSummarySuffix",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "العنوان: «${plan.topic}» • المستوى: ${plan.info.level} — ${plan.info.unit}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 1b. Built-in AI Delegation Button & Status Banner
            if (report.isAiDelegated) {
                Surface(
                    color = Color(0xFFEDE9FE),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFF7C3AED), RoundedCornerShape(10.dp))
                        .testTag("ai_delegated_status_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = Color(0xFF5B21B6),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "تم إسناد المهمة إلى نموذج الذكاء الاصطناعي المدمج بنجاح ✨",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF5B21B6)
                            )
                            Text(
                                text = "صُنّف ${report.aiClassifiedLinesCount} سطرًا من الملف، وتم تنقيح واستكمال ${report.aiRefinedFieldsCount} خانة وفق تنسيق الجذاذة النموذجية.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF4C1D95)
                            )
                        }
                    }
                }
            } else if (report.unclassifiedLines.isNotEmpty() || report.totalDefaultFields > 0) {
                FilledTonalButton(
                    onClick = onDelegateToBuiltInAi,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("ai_delegate_refine_button"),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xFFEDE9FE),
                        contentColor = Color(0xFF5B21B6)
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "إسناد المهمة إلى الذكاء الاصطناعي المدمج (تصنيف الأسطر وتنقيح المراحل) ✨",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            // 2. Prominent Alert if Component was Defaulted (no keywords found)
            if (report.isComponentDefaulted) {
                Surface(
                    color = Color(0xFFFEF3C7),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFFD97706), RoundedCornerShape(10.dp))
                        .testTag("defaulted_component_warning")
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = Color(0xFFB45309),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "تنبيه: تم افتراض مكون «النصوص القرائية» تلقائياً",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF92400E)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "لم يُعثر على كلمات مفتاحية صريحة للمكوّن في النص. يمكنك تغيير المكوّن وإعادة التفريغ فوراً:",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF78350F)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            PedagogicalComponent.entries.forEach { comp ->
                                OutlinedButton(
                                    onClick = { onChangeComponentAndReparse(comp) },
                                    modifier = Modifier.testTag("reparse_as_${comp.name.lowercase()}")
                                ) {
                                    Text(
                                        text = "إعادة التفريغ كـ: ${comp.title}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF92400E)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Per-Stage Source Breakdown (FILE vs AI vs DEFAULT per stage)
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "توزيع مصادر الخانات حسب المراحل (${stages.size} مراحل × 4 خانات):",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    stages.forEach { stageMeta ->
                        val count = report.stageSourceCounts[stageMeta.key]
                        val fileCount = count?.fileCount ?: 0
                        val aiCount = count?.aiCount ?: 0
                        val defCount = count?.defaultCount ?: 0
                        val isAllDefault = fileCount == 0 && aiCount == 0 && defCount > 0

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "• ${stageMeta.title}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (fileCount > 0) {
                                    Surface(
                                        color = Color(0xFFD1FAE5),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = "$fileCount من الملف",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF065F46),
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                if (aiCount > 0) {
                                    Surface(
                                        color = Color(0xFFEDE9FE),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = "$aiCount ذكاء اصطناعي ✨",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF5B21B6),
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                if (defCount > 0) {
                                    Surface(
                                        color = Color(0xFFFEF3C7),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = if (isAllDefault) "افتراضي بالكامل ($defCount)" else "$defCount افتراضي",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF92400E),
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4. Unclassified Lines List with Dropdown to Assign Stage + Field or Ignore
            if (report.unclassifiedLines.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = "أسطر غير مصنّفة (${report.unclassifiedLines.size}) — يمكنك إسناد المهمة للذكاء الاصطناعي أعلاه أو اختيار المرحلة يدوياً:",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.testTag("unclassified_lines_list")
                ) {
                    report.unclassifiedLines.forEach { uLine ->
                        UnclassifiedLineRow(
                            line = uLine,
                            stages = stages,
                            onAssign = { stageKey, field ->
                                onAssignUnclassifiedLine(uLine, stageKey, field)
                            },
                            onIgnore = {
                                onIgnoreUnclassifiedLine(uLine)
                            }
                        )
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF059669),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "جميع أسطر الملف تم تصنيفها بنجاح (0 سطر غير مصنّف).",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF065F46),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun UnclassifiedLineRow(
    line: UnclassifiedLine,
    stages: List<com.example.model.StageMetadata>,
    onAssign: (stageKey: String, field: String) -> Unit,
    onIgnore: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    val fieldLabels = listOf(
        "content" to "المحتوى الديداكتيكي",
        "teacher" to "أنشطة المدرس",
        "abil" to "القدرات المستهدفة",
        "eval" to "التقويم والدعم"
    )

    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .testTag("unclassified_line_${line.lineNumber}")
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "سطر ${line.lineNumber}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = line.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    FilledTonalButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.testTag("assign_dropdown_btn_${line.lineNumber}")
                    ) {
                        Text(
                            text = "إسناد إلى مرحلة وخانة",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "تجاهل هذا السطر",
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onIgnore()
                            }
                        )
                        HorizontalDivider()
                        stages.forEach { stage ->
                            fieldLabels.forEach { (fieldKey, fieldTitle) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "${stage.title} ← $fieldTitle",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onAssign(stage.key, fieldKey)
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                OutlinedButton(
                    onClick = onIgnore,
                    modifier = Modifier.testTag("ignore_line_btn_${line.lineNumber}")
                ) {
                    Text(
                        text = "تجاهل",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
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
