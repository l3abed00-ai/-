package com.example.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.GeminiAiService
import com.example.data.AppDatabase
import com.example.data.JadhadhaRepository
import com.example.docx.DocxGenerator
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
import com.example.python.PythonToolkit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class AppTab(val title: String) {
    COMPONENTS_HUB("ركن المكونات"),
    MODEL_EDITOR("المحرر"),
    WORD_PREVIEW("معاينة Word"),
    EXTRACTOR("تفريغ وتحويل"),
    AI_GENERATOR("الذكاء الاصطناعي"),
    EXPORT_DOCX("تصدير Word"),
    PYTHON_TOOLKIT("أداة بايثون"),
    SAMPLES("بنك النماذج")
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
    val customApiKey: String = ""
) {
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
        get() {
            val required = StageCatalog.getStagesForComponent(activeComponent)
            return required.count { stageMeta ->
                val data = currentPlan.stages[stageMeta.key]
                data != null && (
                    data.content.any { it.isNotBlank() } ||
                    data.eval.any { it.isNotBlank() } ||
                    data.teacher.any { it.isNotBlank() } ||
                    data.abil.any { it.isNotBlank() }
                )
            }
        }

    val completionPercentage: Int
        get() {
            val total = totalStagesRequiredCount
            if (total == 0) return 100
            return ((stagesFilledCount.toFloat() / total) * 100).toInt().coerceIn(0, 100)
        }
}

class JadhadhaViewModel(
    application: Application,
    private val repository: JadhadhaRepository
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        JadhadhaRepository(AppDatabase.getDatabase(application).jadhadhaDao())
    )

    private val _uiState = MutableStateFlow(JadhadhaUiState())
    val uiState: StateFlow<JadhadhaUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.prepopulateSamplesIfEmpty()
        }
        viewModelScope.launch {
            repository.allPlans.collect { plans ->
                if (plans.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(savedPlans = plans)
                }
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

    fun parseAndApplyText(
        rawText: String,
        topic: String,
        forcedComponent: PedagogicalComponent? = null,
        preParsedResult: ParseResult? = null
    ) {
        if (rawText.isBlank() && preParsedResult == null) return
        _uiState.value = _uiState.value.copy(isParsing = true, errorMessage = null)

        viewModelScope.launch {
            val parseResult = preParsedResult ?: withContext(Dispatchers.Default) {
                JadhadhaParser.parseWithReport(
                    raw = rawText,
                    fallbackTopic = topic.ifBlank { "درس جديد" },
                    forcedComponent = forcedComponent
                )
            }
            val parsed = parseResult.plan
            repository.savePlan(parsed, isCustom = true)
            _uiState.value = _uiState.value.copy(
                currentPlan = parsed,
                activeComponent = parsed.componentType,
                isParsing = false,
                lastParseReport = parseResult.report,
                selectedTab = AppTab.MODEL_EDITOR,
                successMessage = "تم تفريغ المحتوى (${parseResult.report.totalFileFields} خانة من الملف، ${parseResult.report.totalDefaultFields} افتراضية) وحفظه بنجاح!"
            )
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
                    successMessage = "تم اقتراح الأهداف التعليمية بنموذج Gemini 3.8 Flash بنجاح!"
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
        _uiState.value = _uiState.value.copy(
            customApiKey = key.trim(),
            successMessage = "تم حفظ مفتاح Gemini بنجاح."
        )
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(errorMessage = null, successMessage = null)
    }

    fun exportToDocx(context: Context): File {
        val file = DocxGenerator.generateDocx(context, _uiState.value.currentPlan, saveToDownloads = true)
        _uiState.value = _uiState.value.copy(
            lastGeneratedFile = file,
            successMessage = "تم حفظ ملف Word في مجلد التنزيلات (Downloads) بالهاتف: ${file.name}"
        )
        return file
    }

    fun exportToJson(context: Context): File {
        val file = PythonToolkit.exportJsonFile(context, _uiState.value.currentPlan)
        _uiState.value = _uiState.value.copy(
            lastGeneratedFile = file,
            successMessage = "تم تصدير ملف JSON بنجاح: ${file.name}"
        )
        return file
    }

    fun exportPythonScript(context: Context, component: PedagogicalComponent? = null): File {
        val comp = component ?: _uiState.value.currentPlan.componentType
        val file = PythonToolkit.exportPythonScript(context, comp)
        _uiState.value = _uiState.value.copy(
            lastGeneratedFile = file,
            successMessage = "تم تصدير سكريبت ${file.name} بنجاح!"
        )
        return file
    }
}
