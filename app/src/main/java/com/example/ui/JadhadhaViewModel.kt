package com.example.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.GeminiAiService
import com.example.docx.DocxGenerator
import com.example.extractor.JadhadhaParser
import com.example.model.JadhadhaInfo
import com.example.model.JadhadhaPlan
import com.example.model.PedagogicalComponent
import com.example.model.SamplePlans
import com.example.model.StageCatalog
import com.example.model.StageData
import com.example.python.PythonToolkit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

class JadhadhaViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(JadhadhaUiState())
    val uiState: StateFlow<JadhadhaUiState> = _uiState.asStateFlow()

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

        _uiState.value = _uiState.value.copy(
            activeComponent = component,
            currentPlan = matchingSample ?: _uiState.value.currentPlan.copy(
                componentType = component,
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
            successMessage = "تم تحميل جذاذة: «${plan.topic}» (${plan.componentType.title})"
        )
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
        _uiState.value = _uiState.value.copy(currentPlan = curr.copy(stages = newStages))
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
        }
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
        }
        updateStage(stageKey, updated)
    }

    fun parseAndApplyText(rawText: String, topic: String, forcedComponent: PedagogicalComponent? = null) {
        if (rawText.isBlank()) return
        val parsed = JadhadhaParser.parseRawText(
            raw = rawText,
            fallbackTopic = topic.ifBlank { "درس جديد" },
            forcedComponent = forcedComponent ?: _uiState.value.activeComponent
        )
        _uiState.value = _uiState.value.copy(
            currentPlan = parsed,
            activeComponent = parsed.componentType,
            selectedTab = AppTab.MODEL_EDITOR,
            successMessage = "تم تفريغ المحتوى وتنسيقه في جذاذة (${parsed.componentType.title}) بنجاح!"
        )
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
                val updatedSaved = listOf(generatedPlan) + _uiState.value.savedPlans
                _uiState.value = _uiState.value.copy(
                    currentPlan = generatedPlan,
                    activeComponent = generatedPlan.componentType,
                    savedPlans = updatedSaved,
                    isAiLoading = false,
                    selectedTab = AppTab.MODEL_EDITOR,
                    successMessage = "تم توليد جذاذة (${generatedPlan.componentType.title}) بالذكاء الاصطناعي بنجاح!"
                )
            }.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    isAiLoading = false,
                    errorMessage = err.message ?: "تعذر توليد الجذاذة بالذكاء الاصطناعي."
                )
            }
        }
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
        val file = DocxGenerator.generateDocx(context, _uiState.value.currentPlan)
        _uiState.value = _uiState.value.copy(
            lastGeneratedFile = file,
            successMessage = "تم إنشاء ملف Word (.docx) بنجاح: ${file.name}"
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
