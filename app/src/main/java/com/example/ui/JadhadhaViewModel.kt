package com.example.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.GeminiAiService
import com.example.data.AppDatabase
import com.example.data.JadhadhaRepository
import com.example.docx.DocxGenerator
import com.example.extractor.DetectedFileType
import com.example.extractor.DocumentFileReader
import com.example.extractor.DocumentReadException
import com.example.extractor.JadhadhaParser
import com.example.model.FieldSource
import com.example.model.JadhadhaInfo
import com.example.model.JadhadhaPlan
import com.example.model.ParseReport
import com.example.model.ParseResult
import com.example.model.PedagogicalComponent
import com.example.model.SamplePlans
import com.example.model.StageCatalog
import com.example.model.StageData
import com.example.model.SuggestedObjectives
import com.example.model.UnclassifiedLine
import com.example.python.PythonToolkit
import com.example.ui.components.ltr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class AppTab(val title: String) {
    COMPONENTS_HUB("ركن المكونات"),
    MODEL_EDITOR("المحرر"),
    WORD_PREVIEW("معاينة ${ltr("Word")}"),
    EXTRACTOR("تفريغ وتحويل"),
    AI_GENERATOR("الذكاء الاصطناعي"),
    EXPORT_DOCX("تصدير ${ltr("Word")}"),
    PYTHON_TOOLKIT("أداة بايثون"),
    SAMPLES("بنك النماذج")
}

sealed interface ExtractionState {
    data object Idle : ExtractionState
    data class Reading(val fileName: String) : ExtractionState
    data object Parsing : ExtractionState
    data class Done(
        val fileName: String? = null,
        val extractedText: String = "",
        val isAiTranscribed: Boolean = false
    ) : ExtractionState
    data class Error(val message: String) : ExtractionState
}

data class JadhadhaUiState(
    val currentPlan: JadhadhaPlan = SamplePlans.MANFA_PLAN,
    val activeComponent: PedagogicalComponent = PedagogicalComponent.READING,
    val savedPlans: List<JadhadhaPlan> = SamplePlans.ALL_SAMPLES,
    val selectedTab: AppTab = AppTab.COMPONENTS_HUB,
    val isAiLoading: Boolean = false,
    val isParsing: Boolean = false,
    val lastParseReport: ParseReport? = null,
    val isSuggestingContent: Boolean = false,
    val suggestedObjectives: SuggestedObjectives? = null,
    val isDarkMode: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val lastGeneratedFile: File? = null,
    val customApiKey: String = "",
    // Persistent Extractor screen state (survives screen rotation)
    val extraction: ExtractionState = ExtractionState.Idle,
    val extractorFileName: String? = null,
    val extractorFileSize: Long = 0L,
    val extractorRawText: String = "",
    val extractorTopicName: String = "",
    val extractorComponentFilter: PedagogicalComponent? = null,
    val extractorParseResult: ParseResult? = null,
    val extractorIsAiTranscribed: Boolean = false,
    val showPdfConfirmationDialog: Boolean = false,
    val showPdfApiKeyDialog: Boolean = false,
    val pendingPdfFileName: String? = null,
    val pendingPdfSizeBytes: Long = 0L,
    val pendingPdfBytes: ByteArray? = null
) {
    val extractionState: ExtractionState
        get() = extraction
    val totalWordCount: Int
        get() {
            var count = 0
            count += currentPlan.topic.split("\\s+".toRegex()).count { it.isNotBlank() }
            count += currentPlan.info.level.split("\\s+".toRegex()).count { it.isNotBlank() }
            count += currentPlan.info.unit.split("\\s+".toRegex()).count { it.isNotBlank() }
            count += currentPlan.info.domain.split("\\s+".toRegex()).count { it.isNotBlank() }
            count += currentPlan.info.communicativeComp.split("\\s+".toRegex()).count { it.isNotBlank() }
            count += currentPlan.info.methodologicalComp.split("\\s+".toRegex()).count { it.isNotBlank() }
            count += currentPlan.info.culturalComp.split("\\s+".toRegex()).count { it.isNotBlank() }
            currentPlan.stages.values.forEach { stage ->
                stage.eval.forEach { count += it.split("\\s+".toRegex()).count { w -> w.isNotBlank() } }
                stage.content.forEach { count += it.split("\\s+".toRegex()).count { w -> w.isNotBlank() } }
                stage.teacher.forEach { count += it.split("\\s+".toRegex()).count { w -> w.isNotBlank() } }
                stage.abil.forEach { count += it.split("\\s+".toRegex()).count { w -> w.isNotBlank() } }
            }
            return count
        }

    val totalStagesRequiredCount: Int
        get() = StageCatalog.getStagesForComponent(activeComponent).size

    val stagesFilledCount: Int
        get() = StageCatalog.getStagesForComponent(activeComponent).count { stageMeta ->
            val data = currentPlan.stages[stageMeta.key]
            data != null && (
                (data.content.any { it.isNotBlank() } && data.sourceFor("content") != FieldSource.DEFAULT) ||
                    (data.eval.any { it.isNotBlank() } && data.sourceFor("eval") != FieldSource.DEFAULT) ||
                    (data.teacher.any { it.isNotBlank() } && data.sourceFor("teacher") != FieldSource.DEFAULT) ||
                    (data.abil.any { it.isNotBlank() } && data.sourceFor("abil") != FieldSource.DEFAULT)
                )
        }

    val completionPercentage: Int
        get() {
            lastParseReport?.let { report ->
                if (report.detectedComponent == activeComponent) {
                    return report.verificationCompletionPercentage
                }
            }
            val stageMetaList = StageCatalog.getStagesForComponent(activeComponent)
            val totalFields = stageMetaList.size * 4
            if (totalFields == 0) return 0
            val verifiedFields = stageMetaList.sumOf { stageMeta ->
                val data = currentPlan.stages[stageMeta.key] ?: return@sumOf 0
                listOf("abil", "teacher", "content", "eval").count { field ->
                    val items = when (field) {
                        "abil" -> data.abil
                        "teacher" -> data.teacher
                        "content" -> data.content
                        "eval" -> data.eval
                        else -> emptyList()
                    }
                    items.any { it.isNotBlank() } && data.sourceFor(field) != FieldSource.DEFAULT
                }
            }
            return ((verifiedFields.toFloat() / totalFields) * 100).toInt().coerceIn(0, 100)
        }
}

class JadhadhaViewModel(
    application: Application,
    private val repository: JadhadhaRepository
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        AppDatabase.getDatabase(application).let { db ->
            JadhadhaRepository(db.jadhadhaDao(), application, db.attachmentDao())
        }
    )

    private val _uiState = MutableStateFlow(JadhadhaUiState())
    val uiState: StateFlow<JadhadhaUiState> = _uiState.asStateFlow()

    private var extractionJob: Job? = null
    private var reparseJob: Job? = null

    init {
        val persistedKey = GeminiAiService.getSavedApiKey(application)
        if (persistedKey.isNotBlank()) {
            _uiState.value = _uiState.value.copy(customApiKey = persistedKey)
        }
        viewModelScope.launch {
            repository.prepopulateSamplesIfEmpty()
            repository.allPlans.collect { plans ->
                _uiState.value = _uiState.value.copy(savedPlans = plans)
            }
        }
    }

    fun selectTab(tab: AppTab) {
        _uiState.value = _uiState.value.copy(selectedTab = tab)
    }

    fun toggleTheme() {
        _uiState.value = _uiState.value.copy(isDarkMode = !_uiState.value.isDarkMode)
    }

    fun setDarkMode(dark: Boolean) {
        _uiState.value = _uiState.value.copy(isDarkMode = dark)
    }

    fun setActiveComponent(component: PedagogicalComponent) {
        // Also check if current plan matches; if not, switch to an exemplar of that component
        val matchingSample = _uiState.value.savedPlans.firstOrNull { it.componentType == component }
            ?: SamplePlans.ALL_SAMPLES.firstOrNull { it.componentType == component }

        val newCategory = com.example.model.JadhadhaCategories.forComponent(component)
        _uiState.value = _uiState.value.copy(
            activeComponent = component,
            currentPlan = matchingSample ?: _uiState.value.currentPlan.copy(
                componentType = component,
                category = newCategory,
                info = _uiState.value.currentPlan.info.copy(
                    component = component.title,
                    duration = component.defaultDuration,
                    materials = component.defaultMaterials
                )
            ),
            successMessage = "تم تفعيل مكون: ${component.title}"
        )
    }

    fun loadPlan(plan: JadhadhaPlan) {
        _uiState.value = _uiState.value.copy(
            currentPlan = plan,
            activeComponent = plan.componentType,
            selectedTab = AppTab.MODEL_EDITOR,
            successMessage = "تم تحميل جذاذة: «${plan.topic}» (${plan.category})"
        )
    }

    fun saveCurrentPlan() {
        val curr = _uiState.value.currentPlan
        viewModelScope.launch {
            repository.savePlan(curr, isCustom = true)
            _uiState.value = _uiState.value.copy(
                successMessage = "تم حفظ الجذاذة «${curr.topic}» [تصنيف: ${curr.category}] في قاعدة البيانات المحلية بنجاح ✓"
            )
        }
    }

    fun deleteSavedPlan(id: String) {
        viewModelScope.launch {
            repository.deletePlan(id)
            _uiState.value = _uiState.value.copy(
                successMessage = "تم حذف الجذاذة من قاعدة البيانات بنجاح"
            )
        }
    }

    fun clearAllStoredData() {
        viewModelScope.launch {
            repository.clearAllStoredData()
            _uiState.value = _uiState.value.copy(
                savedPlans = emptyList(),
                lastGeneratedFile = null,
                successMessage = "تم مسح جميع الجذاذات والملفات المؤقتة المحفوظة محلياً بنجاح"
            )
        }
    }

    fun updateCategory(newCategory: String) {
        if (newCategory.isBlank()) return
        val curr = _uiState.value.currentPlan
        _uiState.value = _uiState.value.copy(currentPlan = curr.copy(category = newCategory.trim()))
    }

    fun updateTopic(newTopic: String) {
        val curr = _uiState.value.currentPlan
        _uiState.value = _uiState.value.copy(currentPlan = curr.copy(topic = newTopic))
    }

    fun updateInfo(newInfo: JadhadhaInfo) {
        val curr = _uiState.value.currentPlan
        _uiState.value = _uiState.value.copy(currentPlan = curr.copy(info = newInfo))
    }

    fun updateStage(stageKey: String, data: StageData) {
        val curr = _uiState.value.currentPlan
        val newStages = curr.stages.toMutableMap()
        newStages[stageKey] = data
        val updatedPlan = curr.copy(stages = newStages)
        _uiState.value = _uiState.value.copy(currentPlan = updatedPlan)
    }

    fun addStageItem(stageKey: String, field: String, text: String) {
        if (text.isBlank()) return
        val curr = _uiState.value.currentPlan
        val stageData = curr.stages[stageKey] ?: StageData()
        val updated = when (field) {
            "eval" -> stageData.copy(eval = stageData.eval + text.trim())
            "content" -> stageData.copy(content = stageData.content + text.trim())
            "teacher" -> stageData.copy(teacher = stageData.teacher + text.trim())
            "abil" -> stageData.copy(abil = stageData.abil + text.trim())
            else -> stageData
        }.withFieldSource(field, FieldSource.USER)
        updateStage(stageKey, updated)
    }

    fun editStageItem(stageKey: String, field: String, index: Int, newText: String) {
        if (newText.isBlank()) return
        val curr = _uiState.value.currentPlan
        val stageData = curr.stages[stageKey] ?: return
        val updated = when (field) {
            "eval" -> stageData.copy(eval = stageData.eval.mapIndexed { i, old -> if (i == index) newText.trim() else old })
            "content" -> stageData.copy(content = stageData.content.mapIndexed { i, old -> if (i == index) newText.trim() else old })
            "teacher" -> stageData.copy(teacher = stageData.teacher.mapIndexed { i, old -> if (i == index) newText.trim() else old })
            "abil" -> stageData.copy(abil = stageData.abil.mapIndexed { i, old -> if (i == index) newText.trim() else old })
            else -> stageData
        }.withFieldSource(field, FieldSource.USER)
        updateStage(stageKey, updated)
    }

    fun removeStageItem(stageKey: String, field: String, index: Int) {
        val curr = _uiState.value.currentPlan
        val stageData = curr.stages[stageKey] ?: return
        val updated = when (field) {
            "eval" -> stageData.copy(eval = stageData.eval.filterIndexed { i, _ -> i != index })
            "content" -> stageData.copy(content = stageData.content.filterIndexed { i, _ -> i != index })
            "teacher" -> stageData.copy(teacher = stageData.teacher.filterIndexed { i, _ -> i != index })
            "abil" -> stageData.copy(abil = stageData.abil.filterIndexed { i, _ -> i != index })
            else -> stageData
        }.withFieldSource(field, FieldSource.USER)
        updateStage(stageKey, updated)
    }

    fun readAndExtractFile(context: Context, uri: Uri, initialFileName: String? = null) {
        extractionJob?.cancel()
        reparseJob?.cancel()

        val initialName = initialFileName
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "ملف_جذاذة"

        _uiState.value = _uiState.value.copy(
            extraction = ExtractionState.Reading(initialName),
            isParsing = true,
            showPdfConfirmationDialog = false,
            showPdfApiKeyDialog = false,
            errorMessage = null
        )

        extractionJob = viewModelScope.launch {
            try {
                ensureActive()
                val fileInfo = withContext(Dispatchers.IO) {
                    DocumentFileReader.readUploadedFile(context, uri)
                }
                ensureActive()

                _uiState.value = _uiState.value.copy(
                    extraction = ExtractionState.Reading(fileInfo.name)
                )

                if (fileInfo.detectedType == DetectedFileType.PDF) {
                    val pdfBytes = fileInfo.pdfBytes
                    if (pdfBytes == null || pdfBytes.isEmpty()) {
                        _uiState.value = _uiState.value.copy(
                            extraction = ExtractionState.Error(DocumentFileReader.MSG_FILE_EMPTY),
                            isParsing = false
                        )
                        return@launch
                    }
                    if (pdfBytes.size > GeminiAiService.MAX_PDF_INLINE_BYTES) {
                        _uiState.value = _uiState.value.copy(
                            extraction = ExtractionState.Error("حجم ملف ${ltr("PDF")} يتجاوز الحد الأقصى المسموح به للتفريغ (10 ميغابايت)."),
                            isParsing = false
                        )
                        return@launch
                    }

                    val hasKey = GeminiAiService.hasValidApiKey(_uiState.value.customApiKey)
                    if (!hasKey) {
                        _uiState.value = _uiState.value.copy(
                            extraction = ExtractionState.Error(GeminiAiService.MSG_PDF_NEEDS_KEY),
                            isParsing = false,
                            pendingPdfFileName = fileInfo.name,
                            pendingPdfSizeBytes = fileInfo.sizeBytes,
                            pendingPdfBytes = pdfBytes,
                            showPdfApiKeyDialog = true,
                            showPdfConfirmationDialog = false
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            extraction = ExtractionState.Idle,
                            isParsing = false,
                            pendingPdfFileName = fileInfo.name,
                            pendingPdfSizeBytes = fileInfo.sizeBytes,
                            pendingPdfBytes = pdfBytes,
                            showPdfConfirmationDialog = true,
                            showPdfApiKeyDialog = false
                        )
                    }
                    return@launch
                }

                // DOCX or PLAIN_TEXT
                val cleanedTopic = DocumentFileReader.cleanTopicFromFilename(fileInfo.name)
                val nextTopic = if (cleanedTopic.isNotBlank() && cleanedTopic.length in 2..60) {
                    cleanedTopic
                } else {
                    _uiState.value.extractorTopicName
                }

                _uiState.value = _uiState.value.copy(
                    extraction = ExtractionState.Parsing,
                    extractorFileName = fileInfo.name,
                    extractorFileSize = fileInfo.sizeBytes,
                    extractorTopicName = nextTopic,
                    extractorRawText = fileInfo.textContent,
                    extractorIsAiTranscribed = false
                )

                ensureActive()
                val parseResult = withContext(Dispatchers.Default) {
                    ensureActive()
                    JadhadhaParser.parseWithReport(
                        raw = fileInfo.textContent,
                        fallbackTopic = nextTopic.ifBlank { "درس جديد" },
                        forcedComponent = _uiState.value.extractorComponentFilter
                    )
                }
                ensureActive()

                _uiState.value = _uiState.value.copy(
                    extraction = ExtractionState.Done(
                        fileName = fileInfo.name,
                        extractedText = fileInfo.textContent,
                        isAiTranscribed = false
                    ),
                    isParsing = false,
                    extractorParseResult = parseResult,
                    extractorIsAiTranscribed = false
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: DocumentReadException) {
                _uiState.value = _uiState.value.copy(
                    extraction = ExtractionState.Error(e.arabicMessage),
                    isParsing = false
                )
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(
                    extraction = ExtractionState.Error(DocumentFileReader.MSG_FILE_CORRUPTED_OR_PROTECTED),
                    isParsing = false
                )
            }
        }
    }

    fun confirmPdfTranscription() {
        val pdfBytes = _uiState.value.pendingPdfBytes ?: return
        val fileName = _uiState.value.pendingPdfFileName ?: "ملف.pdf"
        val fileSize = _uiState.value.pendingPdfSizeBytes

        extractionJob?.cancel()
        reparseJob?.cancel()

        _uiState.value = _uiState.value.copy(
            showPdfConfirmationDialog = false,
            showPdfApiKeyDialog = false,
            extraction = ExtractionState.Reading(fileName),
            isParsing = true
        )

        extractionJob = viewModelScope.launch {
            try {
                ensureActive()
                val transcribeResult = GeminiAiService.transcribeDocument(
                    bytes = pdfBytes,
                    mimeType = "application/pdf",
                    customKey = _uiState.value.customApiKey
                )
                ensureActive()

                transcribeResult.onSuccess { transcribedText ->
                    val cleanedTopic = DocumentFileReader.cleanTopicFromFilename(fileName)
                    val nextTopic = if (cleanedTopic.isNotBlank() && cleanedTopic.length in 2..60) {
                        cleanedTopic
                    } else {
                        _uiState.value.extractorTopicName
                    }

                    _uiState.value = _uiState.value.copy(
                        extraction = ExtractionState.Parsing,
                        extractorFileName = fileName,
                        extractorFileSize = fileSize,
                        extractorTopicName = nextTopic,
                        extractorRawText = transcribedText,
                        extractorIsAiTranscribed = true,
                        pendingPdfBytes = null
                    )

                    val parsed = withContext(Dispatchers.Default) {
                        ensureActive()
                        JadhadhaParser.parseWithReport(
                            raw = transcribedText,
                            fallbackTopic = nextTopic.ifBlank { "درس جديد" },
                            forcedComponent = _uiState.value.extractorComponentFilter
                        )
                    }
                    ensureActive()

                    _uiState.value = _uiState.value.copy(
                        extraction = ExtractionState.Done(
                            fileName = fileName,
                            extractedText = transcribedText,
                            isAiTranscribed = true
                        ),
                        isParsing = false,
                        extractorParseResult = parsed,
                        extractorIsAiTranscribed = true
                    )
                }.onFailure { err ->
                    _uiState.value = _uiState.value.copy(
                        extraction = ExtractionState.Error(
                            err.message ?: "تعذر تفريغ ملف ${ltr("PDF")} عبر ${ltr("Gemini")}."
                        ),
                        isParsing = false
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(
                    extraction = ExtractionState.Error("تعذر تفريغ ملف ${ltr("PDF")} عبر ${ltr("Gemini")}."),
                    isParsing = false
                )
            }
        }
    }

    fun submitPdfApiKey(key: String) {
        val trimmed = key.trim()
        GeminiAiService.saveApiKey(getApplication(), trimmed)
        val hasPendingPdf = _uiState.value.pendingPdfBytes != null
        val isValid = GeminiAiService.hasValidApiKey(trimmed)
        _uiState.value = _uiState.value.copy(
            customApiKey = trimmed,
            showPdfApiKeyDialog = false,
            showPdfConfirmationDialog = hasPendingPdf && isValid,
            extraction = if (isValid) ExtractionState.Idle else ExtractionState.Error(GeminiAiService.MSG_PDF_NEEDS_KEY),
            successMessage = if (trimmed.isNotBlank()) "تم حفظ مفتاح ${ltr("Gemini")} بنجاح." else null
        )
    }

    fun dismissPdfDialogs() {
        _uiState.value = _uiState.value.copy(
            showPdfConfirmationDialog = false,
            showPdfApiKeyDialog = false,
            pendingPdfBytes = null,
            pendingPdfFileName = null,
            pendingPdfSizeBytes = 0L
        )
    }

    fun cancelExtraction() {
        extractionJob?.cancel()
        reparseJob?.cancel()
        extractionJob = null
        reparseJob = null
        _uiState.value = _uiState.value.copy(
            extraction = ExtractionState.Idle,
            isParsing = false,
            showPdfConfirmationDialog = false
        )
    }

    fun clearExtractionError() {
        val currExt = _uiState.value.extraction
        if (currExt is ExtractionState.Error) {
            _uiState.value = _uiState.value.copy(
                extraction = if (_uiState.value.extractorRawText.isNotBlank()) {
                    ExtractionState.Done(
                        fileName = _uiState.value.extractorFileName,
                        extractedText = _uiState.value.extractorRawText,
                        isAiTranscribed = _uiState.value.extractorIsAiTranscribed
                    )
                } else {
                    ExtractionState.Idle
                }
            )
        }
    }

    fun clearExtractedFile() {
        extractionJob?.cancel()
        reparseJob?.cancel()
        _uiState.value = _uiState.value.copy(
            extraction = ExtractionState.Idle,
            isParsing = false,
            extractorFileName = null,
            extractorFileSize = 0L,
            extractorRawText = "",
            extractorParseResult = null,
            extractorIsAiTranscribed = false,
            pendingPdfBytes = null,
            pendingPdfFileName = null,
            pendingPdfSizeBytes = 0L
        )
    }

    fun updateExtractorRawText(
        newText: String,
        newTopic: String? = null,
        newComponentFilter: PedagogicalComponent? = _uiState.value.extractorComponentFilter,
        clearUploadedFile: Boolean = false
    ) {
        val effectiveTopic = newTopic ?: _uiState.value.extractorTopicName
        _uiState.value = _uiState.value.copy(
            extractorRawText = newText,
            extractorTopicName = effectiveTopic,
            extractorComponentFilter = newComponentFilter,
            extractorFileName = if (clearUploadedFile) null else _uiState.value.extractorFileName,
            extractorFileSize = if (clearUploadedFile) 0L else _uiState.value.extractorFileSize,
            extractorIsAiTranscribed = if (clearUploadedFile) false else _uiState.value.extractorIsAiTranscribed
        )
        scheduleExtractorReparse(newText, effectiveTopic, newComponentFilter)
    }

    fun updateExtractorTopic(newTopic: String) {
        _uiState.value = _uiState.value.copy(extractorTopicName = newTopic)
        if (_uiState.value.extractorRawText.isNotBlank()) {
            scheduleExtractorReparse(
                _uiState.value.extractorRawText,
                newTopic,
                _uiState.value.extractorComponentFilter
            )
        }
    }

    fun updateExtractorComponentFilter(newComponent: PedagogicalComponent?) {
        _uiState.value = _uiState.value.copy(extractorComponentFilter = newComponent)
        if (_uiState.value.extractorRawText.isNotBlank()) {
            scheduleExtractorReparse(
                _uiState.value.extractorRawText,
                _uiState.value.extractorTopicName,
                newComponent
            )
        }
    }

    private fun restoreDoneOrIdleExtractionState(rawText: String = _uiState.value.extractorRawText): ExtractionState {
        return if (rawText.isNotBlank()) {
            ExtractionState.Done(
                fileName = _uiState.value.extractorFileName,
                extractedText = rawText,
                isAiTranscribed = _uiState.value.extractorIsAiTranscribed
            )
        } else {
            ExtractionState.Idle
        }
    }

    private fun scheduleExtractorReparse(
        rawText: String,
        topic: String,
        componentFilter: PedagogicalComponent?
    ) {
        reparseJob?.cancel()
        if (rawText.isBlank()) {
            _uiState.value = _uiState.value.copy(
                extractorParseResult = null,
                extraction = ExtractionState.Idle,
                isParsing = false
            )
            return
        }
        reparseJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                extraction = ExtractionState.Parsing,
                isParsing = true
            )
            try {
                val parsed = withContext(Dispatchers.Default) {
                    ensureActive()
                    JadhadhaParser.parseWithReport(
                        raw = rawText,
                        fallbackTopic = topic.ifBlank { "درس جديد" },
                        forcedComponent = componentFilter
                    )
                }
                ensureActive()
                _uiState.value = _uiState.value.copy(
                    extractorParseResult = parsed,
                    extraction = ExtractionState.Done(
                        fileName = _uiState.value.extractorFileName,
                        extractedText = rawText,
                        isAiTranscribed = _uiState.value.extractorIsAiTranscribed
                    ),
                    isParsing = false
                )
            } catch (e: CancellationException) {
                _uiState.value = _uiState.value.copy(
                    extraction = restoreDoneOrIdleExtractionState(rawText),
                    isParsing = false
                )
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    extraction = restoreDoneOrIdleExtractionState(rawText),
                    isParsing = false,
                    errorMessage = e.message ?: "حدث خطأ أثناء تحليل النص المستخرج."
                )
            }
        }
    }

    fun delegateExtractorToBuiltInAi() {
        val currentResult = _uiState.value.extractorParseResult ?: return
        val rawText = _uiState.value.extractorRawText
        reparseJob?.cancel()
        reparseJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                extraction = ExtractionState.Parsing,
                isParsing = true,
                errorMessage = null
            )
            try {
                val refined = withContext(Dispatchers.Default) {
                    ensureActive()
                    GeminiAiService.classifyAndRefineExtraction(
                        rawText = rawText,
                        initialResult = currentResult,
                        customKey = _uiState.value.customApiKey
                    )
                }
                ensureActive()
                val geminiFailed = refined.report.geminiFailed
                val warningOrError = if (geminiFailed) {
                    "${refined.report.geminiErrorMessage ?: GeminiAiService.MSG_LOCAL_FALLBACK_NOTICE} (تم الاحتفاظ بالبيانات وتطبيق القواعد المحلية كبديل صريح للمراجعة)"
                } else {
                    null
                }
                val successMsg = when {
                    geminiFailed -> null
                    refined.report.isAiDelegated ->
                        "تم التصنيف عبر Gemini بنجاح (${refined.report.aiClassifiedLinesCount} عنصر عبر Gemini، ${refined.report.localRuleClassifiedLinesCount} بقواعد محلية)."
                    refined.report.localRuleClassifiedLinesCount > 0 ->
                        "تم تصنيف العناصر محلياً وفق السياق التربوي (${refined.report.localRuleClassifiedLinesCount} عنصر بقواعد محلية)."
                    else -> null
                }
                _uiState.value = _uiState.value.copy(
                    extractorParseResult = refined,
                    extraction = ExtractionState.Done(
                        fileName = _uiState.value.extractorFileName,
                        extractedText = rawText,
                        isAiTranscribed = _uiState.value.extractorIsAiTranscribed
                    ),
                    isParsing = false,
                    errorMessage = warningOrError,
                    successMessage = successMsg ?: _uiState.value.successMessage
                )
            } catch (e: CancellationException) {
                _uiState.value = _uiState.value.copy(
                    extraction = restoreDoneOrIdleExtractionState(rawText),
                    isParsing = false
                )
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    extraction = restoreDoneOrIdleExtractionState(rawText),
                    isParsing = false,
                    errorMessage = e.message ?: GeminiAiService.MSG_LOCAL_FALLBACK_NOTICE
                )
            }
        }
    }

    /**
     * Retries Gemini classification/extraction on the already-extracted content without requiring the user
     * to re-import the file.
     */
    fun retryAiClassification(comprehensive: Boolean = false, applyImmediately: Boolean = false) {
        if (_uiState.value.extractorRawText.isBlank()) return
        if (comprehensive) {
            runComprehensiveAiExtraction(applyImmediately = applyImmediately)
        } else {
            if (_uiState.value.extractorParseResult == null) {
                val parsed = JadhadhaParser.parseWithReport(
                    raw = _uiState.value.extractorRawText,
                    fallbackTopic = _uiState.value.extractorTopicName.ifBlank { "درس جديد" },
                    forcedComponent = _uiState.value.extractorComponentFilter
                )
                _uiState.value = _uiState.value.copy(extractorParseResult = parsed)
            }
            delegateExtractorToBuiltInAi()
        }
    }

    /**
     * Runs comprehensive AI extraction ("تفريغ ذكي شامل (AI)") via [GeminiAiService.parseRawJadhadhaWithAi]
     * to reorganize and distribute 100% of any complex or non-standard jadhadha across all stages and fields.
     * When Gemini fails, never fakes AI success; preserves extracted data, applies explicit local fallback
     * ([com.example.model.ClassificationSource.LOCAL_RULE]), exits [ExtractionState.Parsing], and notifies the user.
     */
    fun runComprehensiveAiExtraction(applyImmediately: Boolean = false) {
        val rawText = _uiState.value.extractorRawText
        if (rawText.isBlank()) return
        val activeComp = _uiState.value.extractorComponentFilter
            ?: _uiState.value.extractorParseResult?.plan?.componentType
            ?: _uiState.value.activeComponent
        val baseParseResult = _uiState.value.extractorParseResult ?: JadhadhaParser.parseWithReport(
            raw = rawText,
            fallbackTopic = _uiState.value.extractorTopicName.ifBlank { "درس جديد" },
            forcedComponent = activeComp
        )
        val unclassifiedBefore = baseParseResult.report.unclassifiedLines.size

        reparseJob?.cancel()
        reparseJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                extraction = ExtractionState.Parsing,
                isParsing = true,
                errorMessage = null
            )
            try {
                val result = GeminiAiService.parseRawJadhadhaWithAi(
                    rawText = rawText,
                    componentType = activeComp.id,
                    customKey = _uiState.value.customApiKey
                )
                ensureActive()
                result.onSuccess { aiPlan ->
                    val aiFieldsCount = aiPlan.stages.values.sumOf { st ->
                        listOf("abil", "teacher", "content", "eval").count { f ->
                            st.sourceFor(f) == com.example.model.FieldSource.AI ||
                                st.sourceFor(f) == com.example.model.FieldSource.GEMINI
                        }
                    }
                    val updatedReport = JadhadhaParser.buildReport(
                        plan = aiPlan,
                        isComponentDefaulted = false,
                        unclassifiedLines = emptyList(),
                        aiClassifiedLinesCount = unclassifiedBefore,
                        aiRefinedFieldsCount = aiFieldsCount,
                        isAiDelegated = true,
                        geminiFailed = false,
                        isLocalFallback = false,
                        originalRawText = baseParseResult.report.originalRawText.ifEmpty { rawText },
                        extractedUnits = baseParseResult.report.extractedUnits,
                        unextractableItems = baseParseResult.report.unextractableItems
                    )
                    val updatedParseResult = ParseResult(plan = aiPlan, report = updatedReport)
                    if (applyImmediately) {
                        repository.savePlan(aiPlan, isCustom = true)
                        _uiState.value = _uiState.value.copy(
                            currentPlan = aiPlan,
                            activeComponent = aiPlan.componentType,
                            extractorParseResult = updatedParseResult,
                            extraction = ExtractionState.Done(
                                fileName = _uiState.value.extractorFileName,
                                extractedText = rawText,
                                isAiTranscribed = _uiState.value.extractorIsAiTranscribed
                            ),
                            lastParseReport = updatedReport,
                            isParsing = false,
                            selectedTab = AppTab.MODEL_EDITOR,
                            successMessage = "تم التفريغ الذكي الشامل (${ltr("AI")}) وتوزيع عناصر الجذاذة بالكامل بنجاح!"
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            extractorParseResult = updatedParseResult,
                            extraction = ExtractionState.Done(
                                fileName = _uiState.value.extractorFileName,
                                extractedText = rawText,
                                isAiTranscribed = _uiState.value.extractorIsAiTranscribed
                            ),
                            isParsing = false,
                            successMessage = "تم التفريغ الذكي الشامل (${ltr("AI")}) وتوزيع 100% من عناصر الجذاذة!"
                        )
                    }
                }.onFailure { err ->
                    // Explicit local fallback: never claim AI succeeded, preserve extracted content, and exit Parsing state
                    val localFallbackResult = withContext(Dispatchers.Default) {
                        val localOnly = GeminiAiService.classifyWithLocalRulesOnly(rawText, baseParseResult)
                        localOnly.copy(
                            report = localOnly.report.copy(
                                isAiDelegated = false,
                                geminiFailed = true,
                                geminiErrorMessage = err.message ?: GeminiAiService.MSG_LOCAL_FALLBACK_NOTICE,
                                isLocalFallback = true
                            )
                        )
                    }
                    _uiState.value = _uiState.value.copy(
                        extractorParseResult = localFallbackResult,
                        extraction = ExtractionState.Done(
                            fileName = _uiState.value.extractorFileName,
                            extractedText = rawText,
                            isAiTranscribed = _uiState.value.extractorIsAiTranscribed
                        ),
                        isParsing = false,
                        successMessage = null,
                        errorMessage = "${err.message ?: "تعذر إتمام التفريغ الذكي عبر Gemini."} — تم استخدام المعالجة المحلية كبديل صريح وتحتاج النتيجة إلى مراجعة."
                    )
                }
            } catch (e: CancellationException) {
                _uiState.value = _uiState.value.copy(
                    extraction = restoreDoneOrIdleExtractionState(rawText),
                    isParsing = false
                )
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    extraction = restoreDoneOrIdleExtractionState(rawText),
                    isParsing = false,
                    errorMessage = e.message ?: "تعذر إتمام التفريغ الذكي الشامل."
                )
            }
        }
    }

    fun assignExtractorUnclassifiedLine(line: UnclassifiedLine, stageKey: String, field: String) {
        val currentResult = _uiState.value.extractorParseResult ?: return
        val updated = JadhadhaParser.assignUnclassifiedLine(
            currentResult = currentResult,
            line = line,
            targetStageKey = stageKey,
            targetField = field
        )
        _uiState.value = _uiState.value.copy(extractorParseResult = updated)
    }

    fun modifyExtractorUnitClassification(
        unitId: String,
        stageKey: String,
        field: String,
        newCategory: com.example.model.PedagogicalCategory? = null
    ) {
        val currentResult = _uiState.value.extractorParseResult ?: return
        val updated = JadhadhaParser.modifyUnitClassification(
            currentResult = currentResult,
            unitId = unitId,
            targetStageKey = stageKey,
            targetField = field,
            newCategory = newCategory
        )
        _uiState.value = _uiState.value.copy(extractorParseResult = updated)
    }

    fun ignoreExtractorUnclassifiedLine(line: UnclassifiedLine) {
        val currentResult = _uiState.value.extractorParseResult ?: return
        val updated = JadhadhaParser.ignoreUnclassifiedLine(currentResult, line)
        _uiState.value = _uiState.value.copy(extractorParseResult = updated)
    }

    fun parseAndApplyText(
        rawText: String,
        topic: String,
        forcedComponent: PedagogicalComponent? = null,
        preParsedResult: ParseResult? = null
    ) {
        if (rawText.isBlank() && preParsedResult == null) return
        _uiState.value = _uiState.value.copy(
            isParsing = true,
            extraction = ExtractionState.Parsing,
            errorMessage = null
        )

        viewModelScope.launch {
            try {
                val initialResult = preParsedResult ?: withContext(Dispatchers.Default) {
                    val fallback = topic.ifBlank { "درس جديد" }
                    val rawPlan = JadhadhaParser.parseRawText(
                        raw = rawText,
                        fallbackTopic = fallback,
                        forcedComponent = forcedComponent
                    )
                    JadhadhaParser.parseWithReport(
                        raw = rawText,
                        fallbackTopic = rawPlan.topic.ifBlank { fallback },
                        forcedComponent = forcedComponent
                    )
                }
                val parseResult = initialResult
                val parsed = parseResult.plan
                repository.savePlan(parsed, isCustom = true)
                _uiState.value = _uiState.value.copy(
                    currentPlan = parsed,
                    activeComponent = parsed.componentType,
                    isParsing = false,
                    extraction = ExtractionState.Done(
                        fileName = _uiState.value.extractorFileName,
                        extractedText = rawText,
                        isAiTranscribed = _uiState.value.extractorIsAiTranscribed
                    ),
                    lastParseReport = parseResult.report,
                    selectedTab = AppTab.MODEL_EDITOR,
                    successMessage = "تم تفريغ المحتوى (${parseResult.report.totalFileFields} خانة من الملف، ${parseResult.report.totalLocalRuleFields} قواعد محلية، ${parseResult.report.totalAiFields} ذكاء اصطناعي، ${parseResult.report.totalDefaultFields} افتراضية) وحفظه بنجاح!"
                )
            } catch (e: CancellationException) {
                _uiState.value = _uiState.value.copy(
                    isParsing = false,
                    extraction = restoreDoneOrIdleExtractionState(rawText)
                )
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isParsing = false,
                    extraction = restoreDoneOrIdleExtractionState(rawText),
                    errorMessage = e.message ?: "حدث خطأ أثناء تطبيق النص المستخرج."
                )
            }
        }
    }

    fun generateWithAi(topic: String, lessonText: String, component: PedagogicalComponent? = null) {
        if (topic.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "يرجى إدخال عنوان الدرس أو المهارة أولا.")
            return
        }

        val targetComponent = component ?: _uiState.value.activeComponent
        _uiState.value = _uiState.value.copy(isAiLoading = true, errorMessage = null, successMessage = null)

        viewModelScope.launch {
            val result = GeminiAiService.generateJadhadha(
                topic = topic.trim(),
                lessonText = lessonText.trim(),
                component = targetComponent,
                customKey = _uiState.value.customApiKey
            )
            result.onSuccess { generatedPlan ->
                repository.savePlan(generatedPlan, isCustom = true)
                _uiState.value = _uiState.value.copy(
                    currentPlan = generatedPlan,
                    activeComponent = generatedPlan.componentType,
                    isAiLoading = false,
                    selectedTab = AppTab.MODEL_EDITOR,
                    successMessage = "تم توليد جذاذة (${generatedPlan.componentType.title}) وحفظها في قاعدة البيانات بنجاح!"
                )
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    isAiLoading = false,
                    errorMessage = err.message ?: "تعذر توليد الجذاذة بالذكاء الاصطناعي."
                )
            }
        }
    }

    fun suggestObjectives(
        topic: String,
        component: PedagogicalComponent? = null,
        onResult: ((SuggestedObjectives) -> Unit)? = null
    ) {
        val effectiveTopic = topic.ifBlank { _uiState.value.currentPlan.topic }
        if (effectiveTopic.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "يرجى كتابة موضوع الدرس أو المهارة أولا لتوليد واقتراح الأهداف.")
            return
        }

        val comp = component ?: _uiState.value.activeComponent
        _uiState.value = _uiState.value.copy(isSuggestingContent = true, errorMessage = null)

        viewModelScope.launch {
            val result = GeminiAiService.suggestObjectivesForTopic(
                topic = effectiveTopic.trim(),
                component = comp,
                level = _uiState.value.currentPlan.info.level,
                customKey = _uiState.value.customApiKey
            )
            result.onSuccess { suggestions ->
                _uiState.value = _uiState.value.copy(
                    isSuggestingContent = false,
                    suggestedObjectives = suggestions,
                    successMessage = "تم اقتراح الأهداف التعليمية بنموذج ${ltr("Gemini 3.8 Flash")} بنجاح!"
                )
                onResult?.invoke(suggestions)
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    isSuggestingContent = false,
                    errorMessage = err.message ?: "تعذر اقتراح المحتوى والأهداف التعليمية."
                )
            }
        }
    }

    fun applySuggestedObjectives(suggestions: SuggestedObjectives, applyToStages: Boolean = true) {
        val curr = _uiState.value.currentPlan
        val updatedInfo = curr.info.copy(
            communicativeComp = suggestions.communicativeComp.ifBlank { curr.info.communicativeComp },
            methodologicalComp = suggestions.methodologicalComp.ifBlank { curr.info.methodologicalComp },
            culturalComp = suggestions.culturalComp.ifBlank { curr.info.culturalComp }
        )
        val updatedStages = curr.stages.toMutableMap()
        if (applyToStages && suggestions.stageAbilities.isNotEmpty()) {
            suggestions.stageAbilities.forEach { (stageKey, abilities) ->
                val existing = updatedStages[stageKey] ?: StageData()
                val newAbilList = (existing.abil + abilities).distinct()
                updatedStages[stageKey] = existing.copy(abil = newAbilList).withFieldSource("abil", suggestions.source)
            }
        }
        val newPlan = curr.copy(
            topic = suggestions.topic.ifBlank { curr.topic },
            info = updatedInfo,
            stages = updatedStages
        )
        viewModelScope.launch {
            repository.savePlan(newPlan, isCustom = true)
        }
        _uiState.value = _uiState.value.copy(
            currentPlan = newPlan,
            suggestedObjectives = null,
            successMessage = "تم تطبيق الأهداف وحفظ التعديلات في قاعدة البيانات بنجاح!"
        )
    }

    fun dismissSuggestedObjectives() {
        _uiState.value = _uiState.value.copy(suggestedObjectives = null)
    }

    fun setApiKey(key: String) {
        val trimmed = key.trim()
        GeminiAiService.saveApiKey(getApplication(), trimmed)
        _uiState.value = _uiState.value.copy(
            customApiKey = trimmed,
            successMessage = "تم حفظ مفتاح ${ltr("Gemini")} بنجاح."
        )
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(errorMessage = null, successMessage = null)
    }

    fun exportToDocx(context: Context): File {
        val file = DocxGenerator.generateDocx(context, _uiState.value.currentPlan, saveToDownloads = true)
        _uiState.value = _uiState.value.copy(
            lastGeneratedFile = file,
            successMessage = "تم حفظ ملف ${ltr("Word")} في مجلد التنزيلات (${ltr("Downloads")}) بالهاتف: ${ltr(file.name)}"
        )
        return file
    }

    fun exportToJson(context: Context): File {
        val file = PythonToolkit.exportJsonFile(context, _uiState.value.currentPlan)
        _uiState.value = _uiState.value.copy(
            lastGeneratedFile = file,
            successMessage = "تم تصدير ملف ${ltr("JSON")} بنجاح: ${ltr(file.name)}"
        )
        return file
    }

    fun exportPythonScript(context: Context, component: PedagogicalComponent? = null): File {
        val comp = component ?: _uiState.value.currentPlan.componentType
        val file = PythonToolkit.exportPythonScript(context, comp)
        _uiState.value = _uiState.value.copy(
            lastGeneratedFile = file,
            successMessage = "تم تصدير سكريبت ${ltr(file.name)} بنجاح!"
        )
        return file
    }
}
