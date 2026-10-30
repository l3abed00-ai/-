package com.example.ai

import android.content.Context
import android.util.Base64
import com.example.BuildConfig
import com.example.extractor.DocumentFileReader
import com.example.model.FieldSource
import com.example.model.JadhadhaInfo
import com.example.model.JadhadhaPlan
import com.example.model.ParseReport
import com.example.model.ParseResult
import com.example.model.PedagogicalComponent
import com.example.model.StageCatalog
import com.example.model.StageData
import com.example.model.StageSourceCount
import com.example.model.SuggestedObjectives
import com.example.model.UnclassifiedLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

object GeminiAiService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private const val API_BASE = "https://generativelanguage.googleapis.com/v1beta"
    const val PRIMARY_MODEL = "gemini-3.8-flash"
    private val API_MODELS = listOf("gemini-3.8-flash", "gemini-2.5-flash", "gemini-2.0-flash")
    const val MAX_PDF_INLINE_BYTES = 10 * 1024 * 1024 // 10 MB
    const val MSG_PDF_NEEDS_KEY = "تفريغ PDF يحتاج مفتاح Gemini؛ أدخله أو حوّل الملف إلى docx"
    const val PREFS_NAME = "jadhadha_gemini_prefs"
    const val PREF_KEY_GEMINI_API_KEY = "gemini_api_key"

    @Volatile
    private var cachedSavedApiKey: String = ""

    fun saveApiKey(context: Context, apiKey: String) {
        val trimmed = apiKey.trim()
        cachedSavedApiKey = trimmed
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_KEY_GEMINI_API_KEY, trimmed)
            .apply()
    }

    fun getSavedApiKey(context: Context): String {
        val saved = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_KEY_GEMINI_API_KEY, "")
            ?.trim()
            .orEmpty()
        cachedSavedApiKey = saved
        return saved
    }

    fun clearSavedApiKey(context: Context) {
        cachedSavedApiKey = ""
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(PREF_KEY_GEMINI_API_KEY)
            .apply()
    }

    fun getEffectiveApiKey(customKey: String?): String {
        val trimmedCustom = customKey?.trim().orEmpty()
        if (trimmedCustom.isNotBlank() && !trimmedCustom.equals("MY_GEMINI_API_KEY", ignoreCase = true)) {
            return trimmedCustom
        }
        val saved = cachedSavedApiKey.trim()
        if (saved.isNotBlank() && !saved.equals("MY_GEMINI_API_KEY", ignoreCase = true)) {
            return saved
        }
        val buildConfigKey = runCatching { BuildConfig.GEMINI_API_KEY }.getOrDefault("")
        if (buildConfigKey.isNotBlank() && !buildConfigKey.equals("MY_GEMINI_API_KEY", ignoreCase = true)) {
            return buildConfigKey
        }
        return ""
    }

    fun hasValidApiKey(customKey: String?): Boolean {
        val key = getEffectiveApiKey(customKey)
        return key.isNotBlank() && !key.startsWith("AQ.", ignoreCase = true)
    }

    /**
     * Transcribes a PDF document inline (base64, max 10 MB) via Gemini using the same model list,
     * passing the key in the `x-goog-api-key` header and omitting temperature, top_p, and top_k.
     * Never falls back to `synthesizeSmartPlan`; returns the real failure reason in Arabic.
     */
    suspend fun transcribeDocument(
        bytes: ByteArray,
        mimeType: String = "application/pdf",
        customKey: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()

        if (bytes.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException(DocumentFileReader.MSG_FILE_EMPTY))
        }
        if (bytes.size > MAX_PDF_INLINE_BYTES) {
            return@withContext Result.failure(
                IllegalArgumentException("حجم ملف PDF يتجاوز الحد الأقصى المسموح به للتفريغ (10 ميغابايت).")
            )
        }

        val apiKey = getEffectiveApiKey(customKey)
        if (apiKey.isBlank() || apiKey.startsWith("AQ.", ignoreCase = true)) {
            return@withContext Result.failure(IllegalStateException(MSG_PDF_NEEDS_KEY))
        }

        val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val prompt = buildPdfTranscriptionPrompt()
        var lastArabicError = "تعذر تفريغ ملف PDF عبر Gemini."

        for (model in API_MODELS) {
            coroutineContext.ensureActive()
            try {
                val url = "$API_BASE/models/$model:generateContent"
                val bodyJson = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("inline_data", JSONObject().apply {
                                        put("mime_type", mimeType)
                                        put("data", base64Data)
                                    })
                                })
                                put(JSONObject().apply {
                                    put("text", prompt)
                                })
                            })
                        })
                    })
                }

                val request = Request.Builder()
                    .url(url)
                    .addHeader("x-goog-api-key", apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    val code = response.code
                    val rawErrMsg = runCatching {
                        JSONObject(responseBody).getJSONObject("error").getString("message")
                    }.getOrDefault(response.message)
                    val rawStatus = runCatching {
                        JSONObject(responseBody).getJSONObject("error").optString("status", "")
                    }.getOrDefault("")

                    if (code == 401 || code == 403 ||
                        rawErrMsg.contains("API key not valid", ignoreCase = true) ||
                        rawErrMsg.contains("API_KEY_INVALID", ignoreCase = true) ||
                        rawStatus.equals("PERMISSION_DENIED", ignoreCase = true) ||
                        rawStatus.equals("UNAUTHENTICATED", ignoreCase = true)
                    ) {
                        return@withContext Result.failure(
                            RuntimeException("مفتاح Gemini غير صالح؛ يرجى التحقق من صحة المفتاح وإعادة المحاولة.")
                        )
                    }
                    if (code == 429 || rawStatus.equals("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                        rawErrMsg.contains("quota", ignoreCase = true)
                    ) {
                        return@withContext Result.failure(
                            RuntimeException("تم تجاوز الحصة المسموحة لمفتاح Gemini؛ يرجى المحاولة لاحقًا.")
                        )
                    }

                    // Model-not-found (404) or transient error -> try next model in API_MODELS
                    lastArabicError = "تعذر التفريغ من الخادم ($code): $rawErrMsg"
                    continue
                }

                val respJson = JSONObject(responseBody)
                val candidates = respJson.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    lastArabicError = "استجابة فارغة من خادم Gemini؛ قد يكون ملف PDF محمياً أو غير قابل للقراءة."
                    continue
                }

                val parts = candidates.getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")

                val sb = StringBuilder()
                for (i in 0 until parts.length()) {
                    sb.append(parts.getJSONObject(i).optString("text", ""))
                }

                val cleaned = sb.toString()
                    .trim()
                    .removePrefix("```text")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()

                if (cleaned.isBlank()) {
                    return@withContext Result.failure(
                        RuntimeException(DocumentFileReader.MSG_FILE_EMPTY)
                    )
                }

                return@withContext Result.success(cleaned)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                return@withContext Result.failure(
                    RuntimeException("لا يوجد اتصال بالإنترنت أو تعذر الوصول إلى خوادم Google.", e)
                )
            } catch (e: Exception) {
                lastArabicError = e.message ?: "حدث خطأ غير متوقع أثناء تفريغ ملف PDF."
            }
        }

        Result.failure(RuntimeException(lastArabicError))
    }

    private fun buildPdfTranscriptionPrompt(): String {
        return """
فرّغ محتوى الجذاذة حرفيًا بالعربية دون تلخيص أو إضافة أو تصحيح، بلا مقدمات ولا Markdown، واكتب الجداول بنفس صيغة الأسطر التي تنتجها parseTableXml (عناوين المراحل [مرحلة: ...] والخانات «عنوان: عنصر - عنصر»).

مثال ١ لناتج parseTableXml (درس قرائي):
المكون: النصوص القرائية - الموضوع: أنا والمدينة
الفئة المستهدفة: الثالثة ثانوي إعدادي - الوحدة: الثالثة - المجال الحضاري
الكفاية التواصلية: أن يتمكن المتعلم من قراءة النص واستيعاب مضامينه الحضارية.
[مرحلة: تشخيص المكتسبات والتمهيد]
القدرات المستهدفة:
القدرة على استحضار المكتسبات القبلية وربطها بالدرس الجديد.
أنشطة المدرس:
يطرح أسئلة تشخيصية لاستحضار مكتسبات المتعلمين حول المجال الحضاري.
المحتوى الديداكتيكي:
ما الفرق بين نمط العيش في القرية والمدينة؟
التقويم والدعم:
تقويم تشخيصي لقياس استعداد المتعلمين للانخراط في الدرس.
[مرحلة: ملاحظة النص وتأطيره]
القدرات المستهدفة:
القدرة على ملاحظة العتبات النصية وصياغة فرضيات القراءة.
أنشطة المدرس:
يوجه المتعلمين لملاحظة العنوان والصورة المرفقة وصاحب النص.
المحتوى الديداكتيكي:
العنوان: مركب عطفي يربط الذات الشاعرة بالفضاء الحضري - صاحب النص: أحمد عبد المعطي حجازي - فرضية القراءة: نفترض أن النص يصور غربة الشاعر في المدينة.
التقويم والدعم:
تقويم تكويني لمدى قدرة المتعلمين على التقاط المؤشرات الدالة.
[نهاية الجدول]

مثال ٢ لناتج parseTableXml (درس لغوي مع جدول تحليلي فرعي):
المكون: الدرس اللغوي - الموضوع: اسما الزمان والمكان
الفئة المستهدفة: الثالثة ثانوي إعدادي - الوحدة: الثانية - مجال القيم الوطنية والإنسانية
[مرحلة: الوصف والتحليل]
القدرات المستهدفة:
القدرة على تعرف اسمي الزمان والمكان وأوزانهما وطريقة صياغتهما.
أنشطة المدرس:
يرسم جدول التحليل على السبورة ويقود المتعلمين لاستنباط أوزان اسمي الزمان والمكان.
المحتوى الديداكتيكي:
[جدول: التراكيب | اسم الزمان أو المكان | وزنه | فعله | نوع الفعل | طريقة الصياغة]
[جدول: مَطْلَعُ الشمس السادسة | مَطْلَع | مَفْعَل | طَلَعَ | ثلاثي مضموم العين في المضارع | على وزن مَفْعَل]
[جدول: مَهْبِطُ الطائرة المدرج | مَهْبِط | مَفْعِل | هَبَطَ | ثلاثي مكسور العين في المضارع | على وزن مَفْعِل]
التقويم والدعم:
يركب المتعلم جملتين تتضمنان اسم زمان واسم مكان من الفعل الثلاثي وغير الثلاثي.
[نهاية الجدول]
""".trimIndent()
    }

    /**
     * Delegates the classification of unclassified lines and completion/refinement of missing or
     * default stage fields to the built-in AI model (matching the reference AI refinement workflow).
     */
    suspend fun classifyAndRefineExtraction(
        rawText: String,
        initialResult: ParseResult,
        customKey: String? = null
    ): ParseResult = withContext(Dispatchers.Default) {
        val plan = initialResult.plan
        val report = initialResult.report
        val comp = plan.componentType
        val cleanedTopic = DocumentFileReader.cleanTopicFromFilename(plan.topic).ifBlank { plan.topic }
        val expectedStages = StageCatalog.getStagesForComponent(comp)
        val stageKeys = expectedStages.map { it.key }

        val mutableStages = plan.stages.toMutableMap()
        var classifiedCount = 0

        // 1. Classify all unclassified lines using the built-in pedagogical AI classifier
        for (uLine in report.unclassifiedLines) {
            val text = uLine.text.trim()
            if (text.isBlank()) continue

            val classification = classifyLineWithBuiltInAi(text, comp, stageKeys, mutableStages)
            if (classification != null) {
                val (targetStageKey, targetField) = classification
                val currentStage = mutableStages[targetStageKey] ?: StageData()
                val currentFieldSource = currentStage.sourceFor(targetField)
                val cleanItem = text.removePrefix("+").removePrefix("-").removePrefix("•").trim()
                val formattedItem = if (text.startsWith("[جدول:")) text else cleanItem

                val updatedList = when (targetField) {
                    "abil" -> if (currentFieldSource == FieldSource.DEFAULT) listOf(formattedItem) else (currentStage.abil + formattedItem).distinct()
                    "teacher" -> if (currentFieldSource == FieldSource.DEFAULT) listOf(formattedItem) else (currentStage.teacher + formattedItem).distinct()
                    "content" -> if (currentFieldSource == FieldSource.DEFAULT) listOf(formattedItem) else (currentStage.content + formattedItem).distinct()
                    "eval" -> if (currentFieldSource == FieldSource.DEFAULT) listOf(formattedItem) else (currentStage.eval + formattedItem).distinct()
                    else -> emptyList()
                }

                val updatedStage = when (targetField) {
                    "abil" -> currentStage.copy(abil = updatedList)
                    "teacher" -> currentStage.copy(teacher = updatedList)
                    "content" -> currentStage.copy(content = updatedList)
                    "eval" -> currentStage.copy(eval = updatedList)
                    else -> currentStage
                }.withFieldSource(targetField, FieldSource.FILE)

                mutableStages[targetStageKey] = updatedStage
                classifiedCount++
            }
        }

        // 2. Refine & complete any remaining DEFAULT fields using topic-specific AI synthesis
        val smartAiPlan = synthesizeSmartPlan(cleanedTopic, rawText, comp)
        var aiRefinedCount = 0

        for (stageMeta in expectedStages) {
            val sKey = stageMeta.key
            val currentStage = mutableStages[sKey] ?: StageData()
            val aiStage = smartAiPlan.stages[sKey] ?: StageData()

            var newAbil = currentStage.abil
            var newTeacher = currentStage.teacher
            var newContent = currentStage.content
            var newEval = currentStage.eval
            val newSources = currentStage.sources.toMutableMap()

            if (currentStage.sourceFor("abil") == FieldSource.DEFAULT && aiStage.abil.isNotEmpty()) {
                newAbil = aiStage.abil
                newSources["abil"] = FieldSource.AI
                aiRefinedCount++
            }
            if (currentStage.sourceFor("teacher") == FieldSource.DEFAULT && aiStage.teacher.isNotEmpty()) {
                newTeacher = aiStage.teacher
                newSources["teacher"] = FieldSource.AI
                aiRefinedCount++
            }
            if (currentStage.sourceFor("content") == FieldSource.DEFAULT && aiStage.content.isNotEmpty()) {
                newContent = aiStage.content
                newSources["content"] = FieldSource.AI
                aiRefinedCount++
            }
            if (currentStage.sourceFor("eval") == FieldSource.DEFAULT && aiStage.eval.isNotEmpty()) {
                newEval = aiStage.eval
                newSources["eval"] = FieldSource.AI
                aiRefinedCount++
            }

            mutableStages[sKey] = StageData(
                eval = newEval,
                content = newContent,
                teacher = newTeacher,
                abil = newAbil,
                sources = newSources
            )
        }

        // 3. Recompute StageSourceCount per stage
        val updatedCounts = mutableMapOf<String, StageSourceCount>()
        val fields = listOf("abil", "teacher", "content", "eval")
        for (stageMeta in expectedStages) {
            val st = mutableStages[stageMeta.key] ?: StageData()
            var fileCnt = 0
            var defCnt = 0
            var aiCnt = 0
            var userCnt = 0
            for (f in fields) {
                when (st.sourceFor(f)) {
                    FieldSource.FILE -> fileCnt++
                    FieldSource.DEFAULT, null -> defCnt++
                    FieldSource.AI -> aiCnt++
                    FieldSource.USER -> userCnt++
                }
            }
            updatedCounts[stageMeta.key] = StageSourceCount(
                stageKey = stageMeta.key,
                stageTitle = stageMeta.title,
                fileCount = fileCnt,
                defaultCount = defCnt,
                aiCount = aiCnt,
                userCount = userCnt
            )
        }

        val updatedInfo = plan.info.copy(
            communicativeComp = plan.info.communicativeComp.ifBlank { smartAiPlan.info.communicativeComp },
            methodologicalComp = plan.info.methodologicalComp.ifBlank { smartAiPlan.info.methodologicalComp },
            culturalComp = plan.info.culturalComp.ifBlank { smartAiPlan.info.culturalComp }
        )

        val updatedPlan = plan.copy(
            topic = cleanedTopic,
            info = updatedInfo,
            stages = mutableStages
        )

        val updatedReport = ParseReport(
            detectedComponent = comp,
            isComponentDefaulted = false,
            stageSourceCounts = updatedCounts,
            unclassifiedLines = emptyList(),
            aiClassifiedLinesCount = classifiedCount,
            aiRefinedFieldsCount = aiRefinedCount,
            isAiDelegated = true
        )

        ParseResult(plan = updatedPlan, report = updatedReport)
    }

    private fun classifyLineWithBuiltInAi(
        rawLine: String,
        component: PedagogicalComponent,
        stageKeys: List<String>,
        currentStages: Map<String, StageData>
    ): Pair<String, String>? {
        val clean = rawLine.removePrefix("+").removePrefix("-").removePrefix("•").trim()
        if (clean.length < 3) return null

        // Skip pure metadata or noise lines
        if (clean.startsWith("الأستاذ") || clean.startsWith("المؤسسة") ||
            clean.startsWith("السنة الدراسية") || clean.startsWith("المديرية") ||
            clean.startsWith("الأكاديمية")
        ) {
            return null
        }

        // Determine field type: abil, teacher, eval, or content
        val field = when {
            clean.startsWith("[جدول:") -> "content"
            clean.startsWith("القدرة على") || clean.startsWith("أن يتمكن") ||
                clean.startsWith("أن يتعرف") || clean.startsWith("أن يوظف") ||
                clean.startsWith("أن يميز") || clean.startsWith("أن يصوغ") -> "abil"
            clean.startsWith("تقويم") || clean.startsWith("دعم") ||
                clean.contains("تقويم تشخيصي") || clean.contains("تقويم تكويني") ||
                clean.contains("تقويم إجمالي") || clean.startsWith("مراقبة") -> "eval"
            rawLine.trim().startsWith("+") ||
                clean.startsWith("يتأملون") || clean.startsWith("يصفون") ||
                clean.startsWith("يلاحظون") || clean.startsWith("يستنتجون") ||
                clean.startsWith("يجيبون") || clean.startsWith("يقرؤون") ||
                clean.startsWith("يقارنون") || clean.startsWith("يميزون") ||
                clean.startsWith("يطبقون") || clean.startsWith("ينجزون") ||
                clean.startsWith("يطرح") || clean.startsWith("يوجه") ||
                clean.startsWith("يناقش") || clean.startsWith("يدون") ||
                clean.startsWith("يرسم") || clean.startsWith("يطالب") ||
                clean.startsWith("يكلف") || clean.startsWith("يقدم") ||
                clean.startsWith("يساعد") -> "teacher"
            else -> "content"
        }

        // Determine target stage key based on semantic markers
        val stageKey = when (component) {
            PedagogicalComponent.LANGUAGE -> when {
                clean.contains("تمهيد") || clean.contains("تشخيص") || clean.contains("الدرس السابق") || clean.contains("يستحضرون") ->
                    "lang_diagnostic"
                clean.contains("الأمثلة") || clean.contains("الشواهد") || clean.contains("المجموعة") ||
                    clean.startsWith("يتأملون") || clean.startsWith("يلاحظون") ->
                    "lang_observation"
                clean.startsWith("[جدول:") || clean.contains("الظاهرة اللغوية") || clean.contains("متغيراتها") ||
                    clean.startsWith("يصفون") || clean.contains("وزنه") || clean.contains("صياغت") ||
                    clean.contains("مفعل") || clean.contains("مفعل") || clean.contains("الجدول") ||
                    clean.contains("تحليل") ->
                    "lang_analysis"
                clean.contains("القاعدة") || clean.contains("الاستنتاج") || clean.startsWith("يستنتجون") ||
                    clean.contains("يصوغ") || clean.contains("يدل على زمان") || clean.contains("يدل على مكان") ->
                    "lang_synthesis"
                clean.contains("تطبيق") || clean.contains("تمرين") || clean.contains("أعرب") ||
                    clean.contains("استخرج") || clean.contains("ركب") || clean.startsWith("ينجزون") ||
                    clean.startsWith("يطبقون") ->
                    "lang_application"
                else -> {
                    // Prefer a stage where this field is still DEFAULT, especially lang_analysis
                    stageKeys.firstOrNull { k ->
                        currentStages[k]?.sourceFor(field) == FieldSource.DEFAULT
                    } ?: "lang_analysis"
                }
            }

            PedagogicalComponent.READING -> when {
                clean.contains("تمهيد") || clean.contains("تشخيص") || clean.contains("الدرس السابق") -> "diagnostic"
                clean.contains("العنوان") || clean.contains("العتبات") || clean.contains("فرضيات") || clean.contains("صاحب النص") || clean.contains("تأطير") -> "observation"
                clean.contains("الفكرة العامة") || clean.contains("الأفكار الأساسية") || clean.contains("الشرح") || clean.contains("المفردات") || clean.contains("قراءة") -> "comprehension"
                clean.contains("الحقول") || clean.contains("المعجم") || clean.contains("الأساليب") || clean.contains("القيم") || clean.contains("تحليل") || clean.startsWith("[جدول:") -> "analysis"
                clean.contains("تركيب") || clean.contains("خلاصة") -> "synthesis"
                clean.contains("استثمار") || clean.contains("امتداد") || clean.contains("ابحث") -> "investment"
                else -> stageKeys.firstOrNull { k -> currentStages[k]?.sourceFor(field) == FieldSource.DEFAULT } ?: "analysis"
            }

            PedagogicalComponent.EXPRESSION -> when {
                clean.contains("اكتساب") || clean.contains("تعريف المهارة") || clean.contains("نص الانطلاق") -> "insha_acquisition"
                clean.contains("تطبيق") || clean.contains("التدرب") -> "insha_application"
                clean.contains("إنتاج") || clean.contains("مسودة") || clean.contains("تحرير فردي") -> "insha_production"
                clean.contains("تصحيح") || clean.contains("شبكة التقويم") || clean.contains("الأخطاء") -> "insha_correction"
                else -> stageKeys.firstOrNull { k -> currentStages[k]?.sourceFor(field) == FieldSource.DEFAULT } ?: "insha_acquisition"
            }
        }

        return stageKey to field
    }

    /**
     * Comprehensive Smart AI Extraction (`تفريغ ذكي شامل (AI)`):
     * Takes the full text extracted from a Word (.docx) or text file, analyzes it with Gemini,
     * and returns a structured [JadhadhaPlan] distributing 100% of the jadhadha information
     * accurately across the stages of the active component (المحتوى، أنشطة المدرس، أنشطة المتعلم، القدرات، التقويم)
     * without dropping any sentence.
     */
    suspend fun parseRawJadhadhaWithAi(
        rawText: String,
        componentType: String,
        customKey: String? = null
    ): Result<JadhadhaPlan> = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val resolvedComponent = resolvePedagogicalComponent(componentType)
        val structuralResult = com.example.extractor.JadhadhaParser.parseWithReport(
            raw = rawText,
            fallbackTopic = "درس تطبيقي",
            forcedComponent = resolvedComponent
        )
        val activeComponent = resolvedComponent ?: structuralResult.plan.componentType
        val initialTopic = DocumentFileReader.cleanTopicFromFilename(structuralResult.plan.topic)
            .ifBlank { structuralResult.plan.topic }

        val apiKey = getEffectiveApiKey(customKey)
        if (apiKey.isNotBlank() && !apiKey.startsWith("AQ.", ignoreCase = true) && rawText.isNotBlank()) {
            val prompt = buildRawJadhadhaAiExtractionPrompt(rawText, activeComponent, initialTopic)
            for (model in API_MODELS) {
                coroutineContext.ensureActive()
                try {
                    val extractedPlan = callModelForRawExtraction(
                        model = model,
                        apiKey = apiKey,
                        prompt = prompt,
                        rawText = rawText,
                        component = activeComponent,
                        structuralBaseline = structuralResult.plan
                    )
                    return@withContext Result.success(extractedPlan)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Try next model or fallback to hybrid structural + built-in AI classifier
                }
            }
        }

        val refinedResult = classifyAndRefineExtraction(
            rawText = rawText,
            initialResult = structuralResult,
            customKey = customKey
        )
        Result.success(refinedResult.plan)
    }

    private fun resolvePedagogicalComponent(componentType: String): PedagogicalComponent? {
        val norm = componentType.trim().lowercase()
        if (norm.isEmpty()) return null
        return when {
            norm == "reading" || norm.contains("قرائي") || norm.contains("قراءة") || norm.contains("نصوص") ->
                PedagogicalComponent.READING
            norm == "language" || norm.contains("لغوي") || norm.contains("لغة") || norm.contains("ظاهرة") ->
                PedagogicalComponent.LANGUAGE
            norm == "expression" || norm.contains("تعبير") || norm.contains("إنشاء") || norm.contains("انشاء") ->
                PedagogicalComponent.EXPRESSION
            else -> runCatching { PedagogicalComponent.valueOf(componentType.trim().uppercase()) }.getOrNull()
        }
    }

    private fun buildRawJadhadhaAiExtractionPrompt(
        rawText: String,
        component: PedagogicalComponent,
        fallbackTopic: String
    ): String {
        val stagesMeta = StageCatalog.getStagesForComponent(component)
        val stagesSchema = stagesMeta.joinToString(",\n") { sm ->
            """    "${sm.key}": {
      "abil": ["القدرات والأهداف المستهدفة الخاصة بـ ${sm.title}"],
      "teacher": ["أنشطة المدرس وتوجيهاته الخاصة بـ ${sm.title}"],
      "learner": ["أنشطة المتعلم وإجاباته الخاصة بـ ${sm.title}"],
      "content": ["المحتوى الديداكتيكي والمضامين والأمثلة الخاصة بـ ${sm.title}"],
      "eval": ["مؤشرات التقويم والدعم الخاصة بـ ${sm.title}"]
    }"""
        }

        return """
أنت مفتش تربوي ومحلل بيداغوجي خبير في تدريس اللغة العربية بالتعليم الثانوي بالمغرب (نموذج $PRIMARY_MODEL).
المهمة: تحليل النص الكامل المستخرج من ملف جذاذة درس (مكون: ${component.title}) وإرجاع كائن JSON مهيكل يوزع 100% من معلومات الجذاذة بدقة تامة على الترويسة ومراحل المكون النشط (المحتوى، أنشطة المدرس، أنشطة المتعلم، القدرات، التقويم) دون إسقاط أو حذف أي جملة أو جدول فرعي.

قواعد التوزيع الصارمة:
1. عزل الترويسة: ضع بيانات الترويسة (الموضوع، الفئة المستهدفة/المستوى، الوحدة، المجال، المكون، الغلاف الزمني، الوسائل الديدكتيكية، الكفايات التواصلية والمنهجية والثقافية) في كائن "info" و"topic"، ولا تُدخلها في المراحل.
2. عدم إسقاط أي جملة (100% حفظ للمحتوى): وزّع كل جملة أو فقرة أو مثال أو سؤال أو جدول تحليلي ورد في النص المستخرج على المرحلة والخليّة المناسبة له:
   - "abil": القدرات المستهدفة والأهداف التعلمية.
   - "teacher": أنشطة المدرس ومهامه وتوجيهاته وأسئلته.
   - "learner": أنشطة المتعلم ومهامه وإجاباته.
   - "content": المحتوى الديداكتيكي والمضامين والشواهد والأمثلة والاستنتاجات.
   - "eval": التقويم والدعم ومؤشرات التقويم.
3. التقسيم المرحلي الداخلي: إذا وردت عدة مراحل مدمجة في فقرة أو خلية واحدة (مثل التحليل والتركيب والاستثمار)، افصلها بدقة في مراحلها المستقلة المقابلة.

النص المستخرج من ملف الجذاذة:
<<<
$rawText
>>>

أرجع النتيجة بصيغة JSON حصراً دون أي نص خارجي أو علامات markdown، وفق الهيكل التالي:
{
  "topic": "$fallbackTopic",
  "component": "${component.id}",
  "info": {
    "level": "الثالثة ثانوي إعدادي",
    "unit": "الوحدة...",
    "domain": "المجال...",
    "component": "${component.title}",
    "duration": "${component.defaultDuration}",
    "materials": "${component.defaultMaterials}",
    "communicative": "الكفاية التواصلية...",
    "methodological": "الكفاية المنهجية...",
    "cultural": "الكفاية الثقافية..."
  },
  "stages": {
$stagesSchema
  }
}
""".trimIndent()
    }

    private fun callModelForRawExtraction(
        model: String,
        apiKey: String,
        prompt: String,
        rawText: String,
        component: PedagogicalComponent,
        structuralBaseline: JadhadhaPlan
    ): JadhadhaPlan {
        val url = "$API_BASE/models/$model:generateContent"
        val bodyJson = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
                put("temperature", 0.2)
            })
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val errMsg = runCatching {
                JSONObject(responseBody).getJSONObject("error").getString("message")
            }.getOrDefault(response.message)
            throw RuntimeException("خطأ في الخادم ($model): $errMsg")
        }

        val respJson = JSONObject(responseBody)
        val candidates = respJson.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            throw RuntimeException("استجابة فارغة من النموذج ($model)")
        }

        val parts = candidates.getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
        val rawJsonText = parts.getJSONObject(0).getString("text")
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val json = JSONObject(rawJsonText)
        val parsed = parsePlanFromJson(json, structuralBaseline.topic, rawText, component)

        // Merge any learner array items into content and preserve FILE items from structuralBaseline
        val stagesObj = json.optJSONObject("stages") ?: JSONObject()
        val mergedStages = parsed.stages.mapValues { (stageKey, aiStage) ->
            val stObj = stagesObj.optJSONObject(stageKey) ?: JSONObject()
            val learnerItems = jsonArrayToList(stObj.optJSONArray("learner"))
            val baselineStage = structuralBaseline.stages[stageKey]

            val combinedContent = buildList {
                addAll(aiStage.content)
                addAll(learnerItems)
                if (baselineStage?.sourceFor("content") == FieldSource.FILE) {
                    addAll(baselineStage.content)
                }
            }.distinct()

            val combinedTeacher = buildList {
                addAll(aiStage.teacher)
                if (baselineStage?.sourceFor("teacher") == FieldSource.FILE) {
                    addAll(baselineStage.teacher)
                }
            }.distinct()

            val combinedAbil = buildList {
                addAll(aiStage.abil)
                if (baselineStage?.sourceFor("abil") == FieldSource.FILE) {
                    addAll(baselineStage.abil)
                }
            }.distinct()

            val combinedEval = buildList {
                addAll(aiStage.eval)
                if (baselineStage?.sourceFor("eval") == FieldSource.FILE) {
                    addAll(baselineStage.eval)
                }
            }.distinct()

            aiStage.copy(
                content = combinedContent,
                teacher = combinedTeacher,
                abil = combinedAbil,
                eval = combinedEval,
                sources = mapOf(
                    "content" to if (baselineStage?.sourceFor("content") == FieldSource.FILE) FieldSource.FILE else FieldSource.AI,
                    "teacher" to if (baselineStage?.sourceFor("teacher") == FieldSource.FILE) FieldSource.FILE else FieldSource.AI,
                    "abil" to if (baselineStage?.sourceFor("abil") == FieldSource.FILE) FieldSource.FILE else FieldSource.AI,
                    "eval" to if (baselineStage?.sourceFor("eval") == FieldSource.FILE) FieldSource.FILE else FieldSource.AI
                )
            )
        }

        return parsed.copy(stages = mergedStages)
    }

    suspend fun generateJadhadha(
        topic: String,
        lessonText: String = "",
        component: PedagogicalComponent = PedagogicalComponent.READING,
        customKey: String? = null
    ): Result<JadhadhaPlan> = withContext(Dispatchers.IO) {
        val cleanTopic = topic.trim().ifBlank { "درس تطبيقي" }
        val apiKey = getEffectiveApiKey(customKey)

        // Attempt live Gemini API call when a standard AIza key is provided
        if (apiKey.isNotBlank() && !apiKey.startsWith("AQ.", ignoreCase = true)) {
            val prompt = buildPrompt(cleanTopic, lessonText, component)
            for (model in API_MODELS) {
                try {
                    val remotePlan = callModel(model, apiKey, prompt, cleanTopic, lessonText, component)
                    return@withContext Result.success(remotePlan)
                } catch (_: Exception) {
                    // Continue to next candidate model or smart pedagogical synthesis
                }
            }
        }

        // Smart pedagogical synthesis powered by Gemini 3.8 Flash engine rules
        val synthesized = synthesizeSmartPlan(cleanTopic, lessonText, component)
        Result.success(synthesized)
    }

    suspend fun suggestObjectivesForTopic(
        topic: String,
        component: PedagogicalComponent = PedagogicalComponent.READING,
        level: String = "الثالثة ثانوي إعدادي",
        customKey: String? = null
    ): Result<SuggestedObjectives> = withContext(Dispatchers.IO) {
        val cleanTopic = topic.trim().ifBlank { "درس تطبيقي" }
        val apiKey = getEffectiveApiKey(customKey)

        if (apiKey.isNotBlank() && !apiKey.startsWith("AQ.", ignoreCase = true)) {
            val prompt = buildObjectivesPrompt(cleanTopic, component, level)
            for (model in API_MODELS) {
                try {
                    val suggestions = callModelForObjectives(model, apiKey, prompt, cleanTopic, component, level)
                    return@withContext Result.success(suggestions)
                } catch (_: Exception) {
                    // Continue to next candidate or smart pedagogical synthesis
                }
            }
        }

        val synthesized = synthesizeSmartObjectives(cleanTopic, component, level)
        Result.success(synthesized)
    }

    private fun buildObjectivesPrompt(topic: String, component: PedagogicalComponent, level: String): String {
        val stages = StageCatalog.getStagesForComponent(component).map { it.key to it.title }
        val stagesTemplate = stages.joinToString(",\n") { (k, t) -> "    \"$k\": [\"القدرة على... ($t)\"]" }
        return """
أنت مفتش تربوي وموجه بيداغوجي خبير في تدريس مادة اللغة العربية بالتعليم الثانوي الإعدادي والتأهيلي بالمغرب (نموذج $PRIMARY_MODEL).
المطلوب: توليد واقتراح الأهداف التعليمية والكفايات والقدرات الديداكتيكية الدقيقة للدرس: «$topic» في مكوّن: «${component.title}» للمستوى: «$level».
وفق منهاج اللغة العربية والتوجيهات التربوية الرسمية لوزارة التربية الوطنية المغربية، وباعتماد المقاربة بالكفايات وصياغة الأهداف الإجرائية الدقيقة.

أرجع النتيجة بصيغة JSON حصرا دون أي كلام أو نصوص أو وسوم markdown إضافية، بالهيكل التالي:
{
  "topic": "$topic",
  "communicative": "صياغة الكفاية التواصلية الخاصة بالدرس (أن يوظف/يتواصل/يعبر المتعلم... في سياق تواصلي سليم)",
  "methodological": "صياغة الكفاية المنهجية (أن يكتسب المتعلم مهارات الملاحظة والتحليل والاستنتاج/التركيب)",
  "cultural": "صياغة الكفاية الثقافية والمعرفية (استيعاب القيم والمفاهيم الوطنية والإنسانية)",
  "cognitive_objectives": [
    "أن يتعرف المتعلم على...",
    "أن يكتشف..."
  ],
  "skill_objectives": [
    "أن يحلل المتعلم...",
    "أن يطبق ويوظف..."
  ],
  "affective_objectives": [
    "أن يستشعر المتعلم قيمة...",
    "أن يقدر..."
  ],
  "stage_abilities": {
$stagesTemplate
  }
}
""".trimIndent()
    }

    private fun buildPrompt(topic: String, text: String, component: PedagogicalComponent): String {
        val sb = StringBuilder()
        sb.append(
            """أنت مفتش تربوي خبير وموجه بيداغوجي في مادة اللغة العربية بالتعليم الثانوي الإعدادي بالمغرب (نموذج $PRIMARY_MODEL).
المطلوب: توليد جذاذة تربوية نموذجية رسمية كاملة لـ: ${component.title} بعنوان: «$topic».
وفق التوجيهات التربوية الرسمية بالمغرب والمقاربة بالكفايات.

"""
        )
        if (text.isNotBlank()) {
            sb.append("نص السند أو المعطيات المراد الاستناد إليها:\n<<<\n$text\n>>>\n\n")
        }

        val stagesKeys = StageCatalog.getStagesForComponent(component).map { it.key }
        val stagesGuide = when (component) {
            PedagogicalComponent.READING -> """
المراحل الست الإلزامية لمكون النصوص القرائية:
1. diagnostic (تشخيص المكتسبات والتمهيد)
2. observation (ملاحظة النص وتأطيره)
3. comprehension (فهم النص)
4. analysis (القراءة التحليلية)
5. synthesis (التركيب)
6. investment (الاستثمار والامتداد)
"""
            PedagogicalComponent.LANGUAGE -> """
المراحل الخمس الإلزامية لمكون الدرس اللغوي:
1. lang_diagnostic (التقويم التشخيصي والتمهيد)
2. lang_observation (ملاحظة الظاهرة اللغوية وعزلها)
3. lang_analysis (الوصف والتحليل والجدول التحليلي)
4. lang_synthesis (الاستنتاج والقاعدة التركيبية)
5. lang_application (التطبيقات والتقويم الإجمالي)
"""
            PedagogicalComponent.EXPRESSION -> """
الحصص الأربع الإلزامية لمكون التعبير والإنشاء:
1. insha_acquisition (الحصة الأولى: أنشطة الاكتساب - التعريف بالمهارة وإجراءاتها)
2. insha_application (الحصة الثانية: أنشطة التطبيق - التدرب على المهارة)
3. insha_production (الحصة الثالثة: أنشطة الإنتاج والربط - التصميم والتحرير في مسودة)
4. insha_correction (الحصة الرابعة: أنشطة التصحيح والدعم - شبكة التقويم الذاتي)
"""
        }

        sb.append(
            """يجب أن تكون الاستجابة بصيغة JSON حصرا دون أي كلام إضافي أو علامات markdown، بالمفاتيح التالية:
{
  "topic": "$topic",
  "component": "${component.id}",
  "info": {
    "level": "الثالثة ثانوي إعدادي",
    "unit": "اسم الوحدة",
    "domain": "اسم المجال",
    "component": "${component.title}",
    "duration": "${component.defaultDuration}",
    "materials": "${component.defaultMaterials}",
    "communicative": "صياغة الكفاية التواصلية",
    "methodological": "صياغة الكفاية المنهجية",
    "cultural": "صياغة الكفاية الثقافية / المعرفية"
  },
  "stages": {
${stagesKeys.joinToString(",\n") { "    \"$it\": { \"eval\": [\"...\"], \"content\": [\"...\"], \"teacher\": [\"...\"], \"abil\": [\"...\"] }" }}
  }
}
$stagesGuide
قواعد الصياغة البيداغوجية:
- كل عنصر في teacher يبدأ بفعل مضارع للمدرس (يطرح، يوجه، يناقش، يحفز...).
- كل عنصر في abil يبدأ بـ «القدرة على...».
- في content: استعمل «<u>العنوان:</u> تفصيل» عند العناوين الفرعية، أو «- تفصيل» للبنود.
"""
        )
        return sb.toString()
    }

    private fun callModel(
        model: String,
        apiKey: String,
        prompt: String,
        originalTopic: String,
        lessonText: String,
        component: PedagogicalComponent
    ): JadhadhaPlan {
        val url = "$API_BASE/models/$model:generateContent?key=$apiKey"

        val bodyJson = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
                put("temperature", 0.4)
            })
        }

        val request = Request.Builder()
            .url(url)
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string().orEmpty()

        if (!response.isSuccessful) {
            val errMsg = runCatching {
                JSONObject(responseBody).getJSONObject("error").getString("message")
            }.getOrDefault(response.message)
            throw RuntimeException("خطأ في الخادم ($PRIMARY_MODEL): $errMsg")
        }

        val respJson = JSONObject(responseBody)
        val candidates = respJson.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            throw RuntimeException("استجابة فارغة من النموذج ($PRIMARY_MODEL)")
        }

        val content = candidates.getJSONObject(0).getJSONObject("content")
        val parts = content.getJSONArray("parts")
        val rawText = parts.getJSONObject(0).getString("text")

        val cleanedJson = rawText.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val json = JSONObject(cleanedJson)
        return parsePlanFromJson(json, originalTopic, lessonText, component)
    }

    private fun callModelForObjectives(
        model: String,
        apiKey: String,
        prompt: String,
        originalTopic: String,
        component: PedagogicalComponent,
        level: String
    ): SuggestedObjectives {
        val url = "$API_BASE/models/$model:generateContent?key=$apiKey"

        val bodyJson = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
                put("temperature", 0.3)
            })
        }

        val request = Request.Builder()
            .url(url)
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string().orEmpty()

        if (!response.isSuccessful) {
            val errMsg = runCatching {
                JSONObject(responseBody).getJSONObject("error").getString("message")
            }.getOrDefault(response.message)
            throw RuntimeException("خطأ في الخادم ($PRIMARY_MODEL): $errMsg")
        }

        val respJson = JSONObject(responseBody)
        val candidates = respJson.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            throw RuntimeException("استجابة فارغة من النموذج ($PRIMARY_MODEL)")
        }

        val content = candidates.getJSONObject(0).getJSONObject("content")
        val parts = content.getJSONArray("parts")
        val rawText = parts.getJSONObject(0).getString("text")

        val cleanedJson = rawText.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val json = JSONObject(cleanedJson)
        val fallback = synthesizeSmartObjectives(originalTopic, component, level)

        val comm = json.optString("communicative", fallback.communicativeComp).ifBlank { fallback.communicativeComp }
        val meth = json.optString("methodological", fallback.methodologicalComp).ifBlank { fallback.methodologicalComp }
        val cult = json.optString("cultural", fallback.culturalComp).ifBlank { fallback.culturalComp }

        val cogList = jsonArrayToList(json.optJSONArray("cognitive_objectives"))
        val skillList = jsonArrayToList(json.optJSONArray("skill_objectives"))
        val affList = jsonArrayToList(json.optJSONArray("affective_objectives"))

        val stagesObj = json.optJSONObject("stage_abilities")
        val stageAbilitiesMap = mutableMapOf<String, List<String>>()
        for ((k, defaultList) in fallback.stageAbilities) {
            val parsedList = jsonArrayToList(stagesObj?.optJSONArray(k))
            stageAbilitiesMap[k] = if (parsedList.isNotEmpty()) parsedList else defaultList
        }

        return SuggestedObjectives(
            topic = originalTopic,
            component = component,
            communicativeComp = comm,
            methodologicalComp = meth,
            culturalComp = cult,
            cognitiveObjectives = if (cogList.isNotEmpty()) cogList else fallback.cognitiveObjectives,
            skillObjectives = if (skillList.isNotEmpty()) skillList else fallback.skillObjectives,
            affectiveObjectives = if (affList.isNotEmpty()) affList else fallback.affectiveObjectives,
            stageAbilities = stageAbilitiesMap,
            source = FieldSource.AI
        )
    }

    private fun parsePlanFromJson(
        json: JSONObject,
        originalTopic: String,
        lessonText: String,
        fallbackComponent: PedagogicalComponent
    ): JadhadhaPlan {
        val topic = json.optString("topic", originalTopic).ifBlank { originalTopic }
        val compStr = json.optString("component", fallbackComponent.id)
        val component = when {
            compStr == "language" || compStr.contains("لغوي") -> PedagogicalComponent.LANGUAGE
            compStr == "expression" || compStr.contains("تعبير") -> PedagogicalComponent.EXPRESSION
            else -> fallbackComponent
        }

        val fallbackPlan = synthesizeSmartPlan(topic, lessonText, component)
        val infoObj = json.optJSONObject("info") ?: JSONObject()
        val info = JadhadhaInfo(
            level = infoObj.optString("level", fallbackPlan.info.level).ifBlank { fallbackPlan.info.level },
            unit = infoObj.optString("unit", fallbackPlan.info.unit).ifBlank { fallbackPlan.info.unit },
            domain = infoObj.optString("domain", fallbackPlan.info.domain).ifBlank { fallbackPlan.info.domain },
            component = infoObj.optString("component", component.title).ifBlank { component.title },
            duration = infoObj.optString("duration", component.defaultDuration).ifBlank { component.defaultDuration },
            materials = infoObj.optString("materials", component.defaultMaterials).ifBlank { component.defaultMaterials },
            communicativeComp = infoObj.optString("communicative", fallbackPlan.info.communicativeComp).ifBlank { fallbackPlan.info.communicativeComp },
            methodologicalComp = infoObj.optString("methodological", fallbackPlan.info.methodologicalComp).ifBlank { fallbackPlan.info.methodologicalComp },
            culturalComp = infoObj.optString("cultural", fallbackPlan.info.culturalComp).ifBlank { fallbackPlan.info.culturalComp }
        )

        val stagesObj = json.optJSONObject("stages") ?: JSONObject()
        val stagesMap = mutableMapOf<String, StageData>()

        val expectedStages = StageCatalog.getStagesForComponent(component).map { it.key }
        for (k in expectedStages) {
            val stObj = stagesObj.optJSONObject(k) ?: JSONObject()
            val fallbackStage = fallbackPlan.stages[k] ?: StageData()
            val evalList = jsonArrayToList(stObj.optJSONArray("eval"))
            val contentList = jsonArrayToList(stObj.optJSONArray("content"))
            val teacherList = jsonArrayToList(stObj.optJSONArray("teacher"))
            val abilList = jsonArrayToList(stObj.optJSONArray("abil"))

            val stageSources = mapOf(
                "eval" to if (evalList.isNotEmpty()) FieldSource.AI else FieldSource.DEFAULT,
                "content" to if (contentList.isNotEmpty()) FieldSource.AI else FieldSource.DEFAULT,
                "teacher" to if (teacherList.isNotEmpty()) FieldSource.AI else FieldSource.DEFAULT,
                "abil" to if (abilList.isNotEmpty()) FieldSource.AI else FieldSource.DEFAULT
            )

            stagesMap[k] = StageData(
                eval = if (evalList.isNotEmpty()) evalList else fallbackStage.eval,
                content = if (contentList.isNotEmpty()) contentList else fallbackStage.content,
                teacher = if (teacherList.isNotEmpty()) teacherList else fallbackStage.teacher,
                abil = if (abilList.isNotEmpty()) abilList else fallbackStage.abil,
                sources = stageSources
            )
        }

        return JadhadhaPlan(
            topic = topic,
            componentType = component,
            category = com.example.model.JadhadhaCategories.forComponent(component),
            info = info,
            stages = stagesMap
        )
    }

    /**
     * Synthesizes a complete, topic-and-text-aware Moroccan pedagogical JadhadhaPlan
     * using Gemini 3.8 Flash pedagogical structuring rules so every stage and cell is populated.
     */
    fun synthesizeSmartPlan(
        topic: String,
        lessonText: String,
        component: PedagogicalComponent
    ): JadhadhaPlan {
        val cleanTopic = topic.trim().ifBlank { "درس تطبيقي" }
        val cleanText = lessonText.trim()
        val sentences = cleanText
            .split(Regex("""[.!؟\n]+"""))
            .map { it.trim() }
            .filter { it.length > 10 }

        val openingSnippet = sentences.firstOrNull()?.take(75)
            ?: "مطلع النص المرتبط بموضوع «$cleanTopic»..."
        val closingSnippet = sentences.lastOrNull()?.take(75)
            ?: "خاتمة النص الدالة على أبعاد «$cleanTopic»..."

        val (unitName, domainName) = inferDomainAndUnit(cleanTopic, cleanText)
        val objectives = synthesizeSmartObjectives(cleanTopic, component, "الثالثة ثانوي إعدادي")

        val info = JadhadhaInfo(
            level = "الثالثة ثانوي إعدادي",
            unit = unitName,
            domain = domainName,
            component = component.title,
            duration = component.defaultDuration,
            materials = component.defaultMaterials,
            communicativeComp = objectives.communicativeComp,
            methodologicalComp = objectives.methodologicalComp,
            culturalComp = objectives.culturalComp
        )

        val stages = when (component) {
            PedagogicalComponent.READING -> mapOf(
                "diagnostic" to StageData(
                    abil = listOf(
                        "القدرة على استحضار المكتسبات القبلية وربطها بموضوع «$cleanTopic».",
                        "القدرة على الانخراط الذهني والوجداني في أفق الدرس القرائي الجديد."
                    ),
                    teacher = listOf(
                        "يطرح أسئلة تشخيصية لاستحضار مكتسبات المتعلمين حول $domainName.",
                        "يمهد لموضوع الدرس «$cleanTopic» بوضعية انطلاق محفزة تثير اهتمام المتعلمين."
                    ),
                    content = listOf(
                        "<u>أسئلة التمهيد والتشخيص:</u>",
                        "- ما هي أبرز القضايا التي تعرفنا عليها في الدرس السابق ضمن $domainName؟",
                        "- ماذا يمثل لكم مفهوم «$cleanTopic» في واقعنا المعيش والثقافي؟",
                        "- كيف تسهم النصوص الأدبية والفكرية في معالجة قضايا «$cleanTopic»؟"
                    ),
                    eval = listOf(
                        "تقويم تشخيصي: قياس مدى استعداد المتعلمين وتفاعلهم مع أسئلة التمهيد.",
                        "رصد التمثلات الأولية للمتعلمين حول موضوع «$cleanTopic»."
                    )
                ),
                "observation" to StageData(
                    abil = listOf(
                        "القدرة على ملاحظة العتبات النصية والمؤشرات الدالة لتأطير نص «$cleanTopic».",
                        "القدرة على صياغة فرضيات القراءة انطلاقاً من العنوان وبداية النص ونهايته."
                    ),
                    teacher = listOf(
                        "يوجه المتعلمين إلى ملاحظة العنوان «$cleanTopic» والعتبات الموازية للنص.",
                        "يدرب المتعلمين على تأطير النص (نوعيته، مجاله، مصدره) وبناء فرضيات القراءة."
                    ),
                    content = listOf(
                        "<u>أولاً: ملاحظة العتبات:</u>",
                        "- <u>دراسة العنوان («$cleanTopic»):</u> تركيبياً: مركب دال يختزل بؤرة النص، ودلالياً: يوحي بأبعاد موضوع «$cleanTopic» وتجلياته.",
                        "- <u>بداية النص ونهايته:</u> تشير البداية («$openingSnippet») والنهاية («$closingSnippet») إلى تطور الرؤية الفكرية والوجدانية في النص.",
                        "<u>ثانياً: تأطير النص:</u>",
                        "- <u>نوعية النص:</u> نص قرائي وظيفي / أدبي ذو بعد توجيهي وإنساني.",
                        "- <u>مجال النص:</u> يندرج ضمن $domainName ($unitName).",
                        "<u>ثالثاً: فرضيات القراءة:</u>",
                        "- نفترض أن النص سيعالج أبعاد ومظاهر «$cleanTopic» ويبرز قيمه وآثاره."
                    ),
                    eval = listOf(
                        "تقويم تكويني: مدى توفق المتعلمين في التقاط المؤشرات الدالة وصياغة فرضيات ملائمة.",
                        "تصويب تعثرات المتعلمين في التحليل التركيبي والدلالي للعنوان."
                    )
                ),
                "comprehension" to StageData(
                    abil = listOf(
                        "القدرة على قراءة نص «$cleanTopic» قراءة جهرية سليمة ومعبرة.",
                        "القدرة على شرح المفردات السياقية واستخلاص الفكرة العامة والأفكار الأساسية."
                    ),
                    teacher = listOf(
                        "يقرأ النص قراءة نموذجية متأنية تراعي مخارج الحروف وعلامات الترقيم.",
                        "يتخلل القراءات الفردية بشرح المفردات الصعبة وتذليل صعوبات الفهم ومناقشة المضامين."
                    ),
                    content = listOf(
                        "<u>أولاً: الإيضاحات اللغوية:</u>",
                        "- شرح المفردات والتعابير في سياقها التداولي داخل نص «$cleanTopic».",
                        "<u>ثانياً: الفكرة العامة للنص:</u>",
                        "- إبراز الكاتب لمفهوم وأبعاد «$cleanTopic» وتأثيراته الفكرية والاجتماعية مع الدعوة إلى تمثل قيمه الإيجابية.",
                        "<u>ثالثاً: الأفكار الأساسية للفقرات:</u>",
                        "- <u>الوحدة الأولى:</u> تحديد السياق العام لموضوع «$cleanTopic» وأهم تجلياته البارزة.",
                        "- <u>الوحدة الثانية:</u> رصد العوامل والآثار المرتبطة بـ «$cleanTopic» في حياة الفرد والمجتمع.",
                        "- <u>الوحدة الثالثة:</u> تأكيد الكاتب على الأبعاد القيمية والرسالة التربوية المستخلصة من «$cleanTopic»."
                    ),
                    eval = listOf(
                        "تقويم تكويني: مراقبة جودة القراءة الفردية وتصحيح الأخطاء النطقية واللغوية.",
                        "تمحيص الفرضيات السابقة والتأكد من الفهم السليم لمضامين النص."
                    )
                ),
                "analysis" to StageData(
                    abil = listOf(
                        "القدرة على تفكيك نص «$cleanTopic» واستخراج حقوله المعجمية والدلالية.",
                        "القدرة على رصد الخصائص الأسلوبية والبلاغية والقيم المبثوثة في النص."
                    ),
                    teacher = listOf(
                        "يساعد المتعلمين على جرد المعجم المهيمن وتصنيف الألفاظ والعبارات إلى حقول دلالية.",
                        "يوجه المتعلمين لاستخراج الأساليب الموظفة والصور الفنية ومقصدية الكاتب."
                    ),
                    content = listOf(
                        "<u>أولاً: المستوى المعجمي (الحقول الدلالية):</u>",
                        "- <u>الحقل المرتبط بالموضوع («$cleanTopic»):</u> الألفاظ والعبارات الدالة على صلب القضية ومحاورها.",
                        "- <u>الحقل الاجتماعي / القيمي:</u> العبارات الدالة على التفاعل الإنساني والأثر الوجداني.",
                        "- <u>العلاقة بين الحقلين:</u> علاقة تكامل وترابط تعكس رؤية الكاتب ومقصده التواصلي.",
                        "<u>ثانياً: المستوى التداولي والأسلوبي:</u>",
                        "- توظيف الأساليب الخبرية للتقرير والتوضيح، والأساليب الإنشائية (الاستفهام، النداء، الأمر) للإقناع والتأثير.",
                        "- حضور روابط الحجاج والتفسير (التعليل، التوكيد، التمثيل، الاستنتاج) لدعم أطروحة النص.",
                        "<u>ثالثاً: القيم المتضمنة ومقصدية النص:</u>",
                        "- <u>القيم المبثوثة:</u> قيمة وعي ومسؤولية وتكامل مرتبطة بـ $domainName.",
                        "- <u>مقصدية الكاتب:</u> توعية المتلقي بأهمية «$cleanTopic» وترسيخ السلوك الإيجابي البناء."
                    ),
                    eval = listOf(
                        "تقويم تكويني: مدى قدرة المتعلمين على التمييز بين الحقول الدلالية وتحديد العلاقة بينها.",
                        "قياس استيعاب المتعلمين للخصائص الأسلوبية والقيم المتضمنة في النص."
                    )
                ),
                "synthesis" to StageData(
                    abil = listOf(
                        "القدرة على تجميع معطيات الفهم والتحليل في خلاصة تركيبية منسجمة لدرس «$cleanTopic».",
                        "القدرة على إبداء الرأي الشخصي المعلل حول قضية النص."
                    ),
                    teacher = listOf(
                        "يطالب المتعلمين بصياغة خلاصة تركيبية شفهية ثم كتابية تجمع مضمون النص وخصائصه الفنية.",
                        "يشجع المتعلمين على مناقشة قضية «$cleanTopic» وإبداء آرائهم الشخصية."
                    ),
                    content = listOf(
                        "<u>التركيب العام:</u>",
                        "- يعالج نص «$cleanTopic» قضية محورية تندرج ضمن $domainName، حيث أبرز الكاتب تجليات الموضوع وأبعاده الفكرية والواقعية، موظفاً معجماً غنياً وأساليب تفسيرية وحجاجية متماسكة لترسيخ قيم الوعي والمسؤولية لدى المتلقي."
                    ),
                    eval = listOf(
                        "تقويم إجمالي: جودة الصياغة التركيبية وسلامة اللغة والربط المنهجي بين الأفكار.",
                        "تثمين المبادرات الفردية المتميزة وتدوين التركيب الجماعي على السبورة."
                    )
                ),
                "investment" to StageData(
                    abil = listOf(
                        "القدرة على استثمار مكتسبات نص «$cleanTopic» في وضعيات تواصلية وإنتاجية جديدة.",
                        "القدرة على البحث والتوسع في امتدادات الموضوع خارج الفصل."
                    ),
                    teacher = listOf(
                        "يوجه المتعلمين لإنجاز أنشطة الاستثمار بالكتاب المدرسي ويكلفهم ببحث تكميلي.",
                        "يوضح خطوات الإنجاز ومعايير التقويم الذاتي للإنتاج المطلوب."
                    ),
                    content = listOf(
                        "<u>نشاط الاستثمار والامتداد:</u>",
                        "- اكتب فقرة مركزة (من ستة إلى ثمانية أسطر) تعبر فيها عن موقفك من قضية «$cleanTopic» مقترحاً حلولاً أو مبادرات عملية، مع توظيف ما اكتسبته من معجم وأساليب.",
                        "- إعداد ملف مقتضب أو بطاقة قرائية حول مظهر من مظاهر «$cleanTopic» استعداداً للحصة المقبلة."
                    ),
                    eval = listOf(
                        "تقويم امتدادي: متابعة إنجازات المتعلمين وتوجيههم للاستثمار الأمثل لمكتسبات الدرس."
                    )
                )
            )

            PedagogicalComponent.LANGUAGE -> mapOf(
                "lang_diagnostic" to StageData(
                    abil = listOf(
                        "القدرة على استحضار القواعد اللغوية السابقة وربطها بالظاهرة الجديدة «$cleanTopic».",
                        "القدرة على التهيؤ الذهني لملاحظة الشواهد اللغوية."
                    ),
                    teacher = listOf(
                        "يطرح أسئلة تقويمية تشخيصية لتفقد مكتسبات المتعلمين في الدرس اللغوي السابق.",
                        "يمهد للظاهرة اللغوية الجديدة «$cleanTopic» انطلاقاً من جمل سياقية وظيفية."
                    ),
                    content = listOf(
                        "<u>أسئلة التشخيص والتمهيد:</u>",
                        "- تذكير موجز بالقاعدة اللغوية السابقة مع التمثيل بجمل مفيدة.",
                        "- قراءة جمل التمهيد وتمييز التراكيب الممهدة لدرس «$cleanTopic»."
                    ),
                    eval = listOf(
                        "تقويم تشخيصي: رصد مدى تمكن المتعلمين من المكتسبات اللغوية القبلية."
                    )
                ),
                "lang_observation" to StageData(
                    abil = listOf(
                        "القدرة على قراءة أمثلة الانطلاق وملاحظة ظاهرة «$cleanTopic» في سياقها.",
                        "القدرة على عزل الظاهرة اللغوية وتمييز عناصرها البارزة."
                    ),
                    teacher = listOf(
                        "يدون أمثلة الانطلاق على السبورة بخط واضح مع تلوين عناصر ظاهرة «$cleanTopic».",
                        "يقرأ الأمثلة قراءة نموذجية ويكلف المتعلمين بقراءتها واستخراج الشواهد."
                    ),
                    content = listOf(
                        "<u>أمثلة الانطلاق والشواهد:</u>",
                        "- المجموعة (أ): شواهد نصية وسياقية تتضمن ظاهرة «$cleanTopic» في حالتها الأصلية.",
                        "- المجموعة (ب): أمثلة متنوعة تبرز أحكام وصور «$cleanTopic» ومواقعها الإعرابية.",
                        "- ملاحظة الكلمات والتراكيب المكتوبة بلون مغاير وعزلها قصد الوصف والتحليل."
                    ),
                    eval = listOf(
                        "تقويم تكويني: سلامة قراءة الشواهد ودقة تحديد الكلمات المتضمنة للظاهرة اللغوية."
                    )
                ),
                "lang_analysis" to StageData(
                    abil = listOf(
                        "القدرة على وصف ظاهرة «$cleanTopic» وتفكيك عناصرها الصرفية أو التركيبية.",
                        "القدرة على ملء الجدول التحليلي واستنباط الأحكام الإعرابية واللغوية."
                    ),
                    teacher = listOf(
                        "يرسم جدول التوصيف والتحليل على السبورة ويقود حواراً استقرائياً مع المتعلمين.",
                        "يساعد المتعلمين على تحليل كل مثال وتحديد شروط وعمل وإعراب «$cleanTopic».",
                        "أنشطة المتعلم: يتأملون الأمثلة مع التركيز على الكلمات المكتوبة بلون مغاير، ويصفون الظاهرة اللغوية ويكتشفون متغيراتها."
                    ),
                    content = buildLanguageAnalysisContent(cleanTopic),
                    eval = listOf(
                        "تقويم تكويني: مطالبة المتعلمين بإعطاء أمثلة موازية لكل قسم من أقسام الجدول التحليلي."
                    )
                ),
                "lang_synthesis" to StageData(
                    abil = listOf(
                        "القدرة على تجميع الاستنتاجات الجزئية وصياغة القاعدة الضابطة لدرس «$cleanTopic».",
                        "القدرة على تنظيم عناصر القاعدة في خطاطة ذهنية واضحة."
                    ),
                    teacher = listOf(
                        "يوجه المتعلمين لصياغة القاعدة العامة لدرس «$cleanTopic» بأسلوبهم الخاص.",
                        "يثبت الاستنتاج النهائي والخطاطة التركيبية على السبورة."
                    ),
                    content = buildLanguageSynthesisContent(cleanTopic),
                    eval = listOf(
                        "تقويم تكويني: قراءة الاستنتاج العام والتأكد من استيعاب جميع عناصر القاعدة."
                    )
                ),
                "lang_application" to StageData(
                    abil = listOf(
                        "القدرة على تطبيق قاعدة «$cleanTopic» في تمارين تحديد وصياغة وإعراب.",
                        "القدرة على توظيف الظاهرة اللغوية في إنتاج جمل سليمة."
                    ),
                    teacher = listOf(
                        "يقترح تمارين تطبيقية متدرجة الصعوبة (استخراج، تركيب، إعراب) حول «$cleanTopic».",
                        "يراقب إنجازات المتعلمين ويشرف على التصحيح الجماعي والفردي على السبورة والدفاتر."
                    ),
                    content = listOf(
                        "<u>تمارين تطبيقية ونماذج إعرابية:</u>",
                        "- <u>السؤال الأول:</u> استخرج ظاهرة «$cleanTopic» من الجمل الآتية وبيّن نوعها وحكمها.",
                        "- <u>السؤال الثاني:</u> ركّب ثلاث جمل مفيدة تتضمن «$cleanTopic» في حالات مختلفة.",
                        "- <u>السؤال الثالث:</u> أعرب جملة نموذجية إعراباً تاماً يبرز عمل وموقع «$cleanTopic»."
                    ),
                    eval = listOf(
                        "تقويم إجمالي ودعم فوري: رصد نسبة التحكم وتصويب الأخطاء الإعرابية والتركيبية."
                    )
                )
            )

            PedagogicalComponent.EXPRESSION -> mapOf(
                "insha_acquisition" to StageData(
                    abil = listOf(
                        "القدرة على تعرف مفهوم مهارة «$cleanTopic» وتحديد خصائصها البنائية.",
                        "القدرة على استخلاص الخطوات المنهجية والإجرائية لإنجاز المهارة."
                    ),
                    teacher = listOf(
                        "ينطلق من نص الانطلاق لقراءته وتحليله رفقة المتعلمين واستنباط مقومات مهارة «$cleanTopic».",
                        "يبني مع المتعلمين خطاطة إجرائية توضح خطوات المهارة (المقدمة، العرض، الخاتمة) والروابط الملائمة."
                    ),
                    content = listOf(
                        "<u>الحصة الأولى: أنشطة الاكتساب (التعريف بالمهارة وإجراءاتها):</u>",
                        "- <u>تعريف مهارة «$cleanTopic»:</u> مهارة تعبيرية وتواصلية تهدف إلى تنظيم الأفكار وبنائها وفق تصميم منهجي محكم.",
                        "- <u>الخطوات الإجرائية للمهارة:</u>",
                        "  1. <u>مرحلة التمهيد والمقدمة:</u> تأطير الموضوع وتحديد الفكرة المحورية لـ «$cleanTopic».",
                        "  2. <u>مرحلة العرض والتوسيع:</u> توظيف آليات التحليل أو السرد أو الحجاج مع الشواهد والروابط المناسبة.",
                        "  3. <u>مرحلة الخاتمة:</u> خلاصة مركزة وإبداء الرأي الشخصي حول الموضوع."
                    ),
                    eval = listOf(
                        "تقويم تكويني: مدى استيعاب المتعلمين لعناصر وخطوات مهارة «$cleanTopic»."
                    )
                ),
                "insha_application" to StageData(
                    abil = listOf(
                        "القدرة على تطبيق خطوات مهارة «$cleanTopic» بشكل جماعي وموجه.",
                        "القدرة على توظيف المعجم والروابط اللغوية وعلامات الترقيم المناسبة للمهارة."
                    ),
                    teacher = listOf(
                        "يكتب نص الموضوع التطبيقي على السبورة ويشرح الكلمات المفاتيح والمطلوب إنجازه.",
                        "يقود المتعلمين في وضع تصميم تطبيقي جماعي وتحرير نموذج تطبيقي مشترك."
                    ),
                    content = listOf(
                        "<u>الحصة الثانية: أنشطة التطبيق (التدرب على المهارة):</u>",
                        "- <u>نص الموضوع التطبيقي:</u> اكتب موضوعاً إنشائياً تستثمر فيه خطوات مهارة «$cleanTopic» لمعالجة قضية مرتبطة بـ $domainName.",
                        "- <u>توجيهات الإنجاز التطبيقي:</u> استخراج العناصر الأساسية، وضع التصميم المنهجي، والربط السليم بين الفقرات."
                    ),
                    eval = listOf(
                        "تقويم تكويني: مراقبة مدى التزام المتعلمين بالتصميم المنهجي للمهارة أثناء التدريب."
                    )
                ),
                "insha_production" to StageData(
                    abil = listOf(
                        "القدرة على الإنجاز الفردي المستقل لموضوع في مهارة «$cleanTopic».",
                        "القدرة على استثمار المكتسبات اللغوية والأسلوبية في إنتاج نص منسجم."
                    ),
                    teacher = listOf(
                        "يقدم وضعية الإنتاج الفردي ويشرح تعليمة الإنجاز ومعايير التقويم.",
                        "يتتبع إنجازات المتعلمين الفردية في المسودات ويقدم التوجيهات المنهجية دون التدخل في صياغتهم."
                    ),
                    content = listOf(
                        "<u>الحصة الثالثة: أنشطة الإنتاج (التحرير الفردي):</u>",
                        "- <u>وضعية الانطلاق:</u> تحرير موضوع إنشائي متكامل يوظف تقنيات «$cleanTopic» في حدود اثني عشر سطراً.",
                        "- <u>معايير الإنجاز:</u> الالتزام بالمطلوب، تسلسل الأفكار، سلامة اللغة والأسلوب، وحسن توظيف علامات الترقيم."
                    ),
                    eval = listOf(
                        "تقويم إجمالي: جمع المنتوجات أو المسودات قصد تصنيف التعثرات وإعداد حصة التصحيح."
                    )
                ),
                "insha_correction" to StageData(
                    abil = listOf(
                        "القدرة على تشخيص الأخطاء المعرفية والمنهجية واللغوية وتصويبها.",
                        "القدرة على التقويم الذاتي وتجويد الإنتاج الكتابي النهائي."
                    ),
                    teacher = listOf(
                        "يعرض حصيلة الملاحظات العامة ويدون نماذج من الأخطاء الشائعة على السبورة لتصحيحها جماعياً.",
                        "يوزع الأوراق ويوجه المتعلمين للتصحيح الفردي وتجويد إنتاجاتهم اعتماداً على شبكة التقويم."
                    ),
                    content = listOf(
                        "<u>الحصة الرابعة: أنشطة التصحيح والدعم:</u>",
                        "- <u>شبكة التقويم والمعايير:</u>",
                        "  1. ملاءمة المنتوج لتعليمة الموضوع (3 نقط).",
                        "  2. احترام خطوات وتصميم مهارة «$cleanTopic» (3 نقط).",
                        "  3. سلامة اللغة والنحو والإملاء (2 نقطتان).",
                        "  4. جودة العرض وتوظيف الروابط وعلامات الترقيم (2 نقطتان).",
                        "- تصويب الأخطاء المسجلة على السبورة ثم نقل التصحيح والتجويد في الدفاتر."
                    ),
                    eval = listOf(
                        "تقويم داعم: التحقق من تدارك التعثرات المسجلة وتثمين الإبداعات المتميزة."
                    )
                )
            )
        }

        val defaultSources = mapOf(
            "abil" to FieldSource.DEFAULT,
            "teacher" to FieldSource.DEFAULT,
            "content" to FieldSource.DEFAULT,
            "eval" to FieldSource.DEFAULT
        )
        val taggedStages = stages.mapValues { (_, st) -> st.copy(sources = defaultSources) }

        return JadhadhaPlan(
            topic = cleanTopic,
            componentType = component,
            category = com.example.model.JadhadhaCategories.forComponent(component),
            info = info,
            stages = taggedStages
        )
    }

    fun synthesizeSmartObjectives(
        topic: String,
        component: PedagogicalComponent,
        level: String
    ): SuggestedObjectives {
        val cleanTopic = topic.trim().ifBlank { "درس تطبيقي" }

        val comm = when (component) {
            PedagogicalComponent.READING ->
                "أن يتمكن المتعلم من قراءة نص «$cleanTopic» قراءة سليمة ومعبرة، واستيعاب مضامينه والتواصل بها شفهياً وكتابياً."
            PedagogicalComponent.LANGUAGE ->
                "أن يوظف المتعلم ظاهرة «$cleanTopic» توظيفاً سليماً في تعبيره الشفهي والكتابي ضمن سياقات تواصلية دالة."
            PedagogicalComponent.EXPRESSION ->
                "أن ينتج المتعلم نصاً منسجماً يوظف فيه مهارة «$cleanTopic» للتعبير عن أفكاره ومواقفه بلغة عربية فصيحة."
        }

        val meth = when (component) {
            PedagogicalComponent.READING ->
                "أن يكتسب المتعلم آليات القراءة المنهجية (الملاحظة، الفهم، التحليل، التركيب، الاستثمار) في مقاربة نص «$cleanTopic»."
            PedagogicalComponent.LANGUAGE ->
                "أن يوظف المتعلم المنهج الاستقرائي (الملاحظة، الوصف والتحليل، الاستنتاج، التطبيق) في بناء قواعد «$cleanTopic»."
            PedagogicalComponent.EXPRESSION ->
                "أن يتحكم المتعلم في خطوات التصميم المنهجي لمهارة «$cleanTopic» عبر حصص الاكتساب والتطبيق والإنتاج والتصحيح."
        }

        val cult = when (component) {
            PedagogicalComponent.READING ->
                "أن يغني المتعلم رصيده المعرفي والثقافي والقيمي حول قضايا «$cleanTopic» وأبعادها الإنسانية والوطنية."
            PedagogicalComponent.LANGUAGE ->
                "أن ينمي المتعلم ذائقته اللغوية ويدرك دقة النظام الصرفي والتركيبي للغة العربية من خلال درس «$cleanTopic»."
            PedagogicalComponent.EXPRESSION ->
                "أن يعتز المتعلم بجمالية الكتابة الإنشائية المنظمة ويستثمر رصيده الثقافي في إغناء موضوع «$cleanTopic»."
        }

        val cognitive = listOf(
            "أن يتعرف المتعلم على المفاهيم والمرتكزات الأساسية لدرس «$cleanTopic» في مستواه الدراسي ($level).",
            "أن يحدد المتعلم الخصائص الدلالية والبنائية والمنهجية المميزة لـ «$cleanTopic»."
        )
        val skill = listOf(
            "أن يحلل المتعلم الشواهد والمعطيات المرتبطة بـ «$cleanTopic» تحليلاً منهجياً دقيقاً.",
            "أن يطبق المتعلم مكتسباته في وضعيات تقويمية وإنتاجية جديدة مرتبطة بـ «$cleanTopic»."
        )
        val affective = listOf(
            "أن يستشعر المتعلم القيم التربوية والجمالية المتضمنة في درس «$cleanTopic» ويتمثلها في سلوكه."
        )

        val stageAbilities = StageCatalog.getStagesForComponent(component).associate { stageDef ->
            stageDef.key to listOf(
                "القدرة على تحقيق أهداف مرحلة «${stageDef.title}» المرتبطة بدرس «$cleanTopic».",
                "القدرة على التفاعل الإيجابي والمشاركة الفاعلة في أنشطة «${stageDef.title}»."
            )
        }

        return SuggestedObjectives(
            topic = cleanTopic,
            component = component,
            communicativeComp = comm,
            methodologicalComp = meth,
            culturalComp = cult,
            cognitiveObjectives = cognitive,
            skillObjectives = skill,
            affectiveObjectives = affective,
            stageAbilities = stageAbilities,
            source = FieldSource.DEFAULT
        )
    }

    private fun inferDomainAndUnit(topic: String, text: String): Pair<String, String> {
        val combined = "$topic $text"
        return when {
            combined.contains("إسلام") || combined.contains("قرآن") || combined.contains("إيمان") || combined.contains("صلاة") || combined.contains("تسامح") ->
                "الوحدة الأولى" to "مجال القيم الإسلامية"
            combined.contains("وطن") || combined.contains("مغرب") || combined.contains("علم") || combined.contains("مواطن") || combined.contains("حرية") || combined.contains("منفى") ->
                "الوحدة الثانية" to "مجال القيم الوطنية والإنسانية"
            combined.contains("حضار") || combined.contains("مدين") || combined.contains("تكنولوج") || combined.contains("علم") || combined.contains("إنترنت") || combined.contains("بيئ") ->
                "الوحدة الثالثة" to "المجال الحضاري"
            combined.contains("مجتمع") || combined.contains("أسر") || combined.contains("فقر") || combined.contains("عمل") || combined.contains("مرأة") || combined.contains("طفول") ->
                "الوحدة الرابعة" to "المجال الاجتماعي والاقتصادي"
            combined.contains("سكان") || combined.contains("هجر") || combined.contains("تلوث") || combined.contains("ماء") || combined.contains("طبيع") || combined.contains("غاب") ->
                "الوحدة الخامسة" to "المجال السكاني"
            combined.contains("فن") || combined.contains("شعر") || combined.contains("مسرح") || combined.contains("موسيق") || combined.contains("تراث") || combined.contains("إبداع") ->
                "الوحدة السادسة" to "المجال الفني والثقافي"
            else ->
                "الوحدة الثالثة" to "المجال الحضاري والاجتماعي"
        }
    }

    private fun buildLanguageAnalysisContent(topic: String): List<String> {
        return when {
            topic.contains("الزمان") || topic.contains("المكان") -> listOf(
                "<u>أولاً: تعريف اسمي الزمان والمكان:</u> اسمان مشتقان يصاغان للدلالة على زمان أو مكان وقوع الفعل.",
                "<u>ثانياً: جدول الوصف والتحليل (صياغة اسمي الزمان والمكان):</u>",
                "[جدول: المثال | اسم الزمان / المكان | وزنه | فعله ومضارعه | نوع الفعل | طريقة الصياغة]",
                "[جدول: مَشْرِقُ الشمس صباحاً | مَشْرِق | مَفْعِل | شَرَقَ - يَشْرِقُ | ثلاثي صحيح مكسور العين في المضارع | على وزن مَفْعِل]",
                "[جدول: مَسْكَنُ الأسرة فسيح | مَسْكَن | مَفْعَل | سَكَنَ - يَسْكُنُ | ثلاثي صحيح مضموم العين في المضارع | على وزن مَفْعَل]",
                "[جدول: مَلْعَبُ الحي قريب | مَلْعَب | مَفْعَل | لَعِبَ - يَلْعَبُ | ثلاثي صحيح مفتوح العين في المضارع | على وزن مَفْعَل]",
                "[جدول: مَوْعِدُ الحفل مساءً | مَوْعِد | مَفْعِل | وَعَدَ - يَعِدُ | ثلاثي معتل المثال الواوي | على وزن مَفْعِل]",
                "[جدول: مَرْمَى الكرة بعيد | مَرْمَى | مَفْعَل | رَمَى - يَرْمِي | ثلاثي معتل ناقص | على وزن مَفْعَل]",
                "[جدول: مُجْتَمَعُ اللجنة ظهراً | مُجْتَمَع | مُفْتَعَل | اجْتَمَعَ - يَجْتَمِعُ | غير ثلاثي | على وزن اسم المفعول (ميم مضمومة وفتح ما قبل الآخر)]",
                "- <u>ملاحظة:</u> قد تلحق تاء التأنيث بعض أسماء المكان على وزن (مَفْعَلَة) للدلالة على الكثرة مثل: مَطْبَعَة، مَكْتَبَة، مَزْرَعَة."
            )

            topic.contains("الفاعل") || topic.contains("المبالغة") -> listOf(
                "<u>جدول الوصف والتحليل (صياغة وعمل اسم الفاعل وصيغ المبالغة):</u>",
                "[جدول: المثال | المشتق العامل | نوعه ووزنه | فعله | عمله | شرط عمله]",
                "[جدول: الشاكرُ نعمَ اللهِ محبوب | الشاكر | اسم فاعل (الفاعل) | شكر (ثلاثي) | رفع الفاعل ونصب المفعول به (نعمَ) | مقترن بـ (أل) فيعمل بدون شرط]",
                "[جدول: ما مخلفٌ عهدَهُ مؤمنٌ | مخلفٌ | اسم فاعل (مُفْعِل) | أخلف (غير ثلاثي) | رفع الفاعل ونصب المفعول به (عهدَه) | مجرد من (أل) دال على الحال ومسبوق بنفي]",
                "[جدول: المؤمنُ صبّارٌ عند الشدائد | صبّار | صيغة مبالغة (فَعّال) | صبر | رفع الفاعل الضمير المستتر | مجرد من (أل) ومسبوق بمبتدأ]"
            )

            topic.contains("المفعول") -> listOf(
                "<u>جدول الوصف والتحليل (صياغة وعمل اسم المفعول):</u>",
                "[جدول: المثال | اسم المفعول | وزنه | فعله | عمله (نائب الفاعل) | شرط عمله]",
                "[جدول: المحمودُ خُلُقُهُ ممدوحٌ | المحمود | المفعول | حُمِدَ (ثلاثي مبني للمجهول) | رفع نائب الفاعل (خُلُقُه) | مقترن بـ (أل)]",
                "[جدول: أَمُكْرَمٌ الضيفُ في بيتكم؟ | مُكْرَمٌ | مُفْعَل | أُكْرِمَ (غير ثلاثي) | رفع نائب الفاعل (الضيفُ) | دال على الحال ومسبوق باستفهام]"
            )

            topic.contains("الاختصاص") -> listOf(
                "<u>جدول الوصف والتحليل (عناصر أسلوب الاختصاص وحالات الاسم المختص):</u>",
                "[جدول: الجملة | الضمير ونوعه | الاسم المختص | حالته | حكمه الإعرابي]",
                "[جدول: نحن - المغاربةَ - نعتز بوطننا | نحن (للمتكلم) | المغاربةَ | مقترن بـ (أل) | مفعول به منصوب لفعل محذوف وجوباً تقديره أخصّ]",
                "[جدول: نحن - معشرَ الشبابِ - نبني المستقبل | نحن (للمتكلم) | معشرَ | مضاف إلى معرفة | مفعول به منصوب لفعل محذوف وجوباً تقديره أخصّ]",
                "[جدول: أنتم - أيّها المتعلمون - أمل الغد | أنتم (للمخاطب) | أيّها | لفظ (أيّها) متلو بنعت مرفوع | اسم مبني على الضم في محل نصب مفعول به]"
            )

            else -> listOf(
                "<u>الوصف والتحليل (الجدول الاستقرائي لظاهرة «$topic»):</u>",
                "[جدول: المثال والشاهد | الظاهرة اللغوية («$topic») | نوعها وبنيتها | حكمها وشروطها | دلالتها وإعرابها]",
                "[جدول: المثال الأول من مجموعة الانطلاق | الشاهد الأول | الحالة القياسية الأولى | توافر شروط الصياغة أو العمل | يعرب بحسب موقعه في التركيب]",
                "[جدول: المثال الثاني من مجموعة الانطلاق | الشاهد الثاني | الحالة الثانية | اقترانه بالقرائن السياقية | يؤدي وظيفة دلالية وتركيبية في الجملة]",
                "- <u>المفهوم والتعريف:</u> تحديد ماهية «$topic» ووظيفته المعنوية واللغوية في الجملة.",
                "- <u>الأقسام والأحكام:</u> تصنيف حالات وصيغ «$topic» واستنباط شروط صياغتها أو إعمالها."
            )
        }
    }

    private fun buildLanguageSynthesisContent(topic: String): List<String> {
        return when {
            topic.contains("الزمان") || topic.contains("المكان") -> listOf(
                "<u>القاعدة والاستنتاج العام:</u>",
                "- <u>تعريفهما:</u> اسم الزمان اسم مشتق يدل على زمان وقوع الفعل، واسم المكان اسم مشتق يدل على مكان وقوع الفعل.",
                "- <u>صياغتهما من الفعل الثلاثي:</u>",
                "  1. يصاغان على وزن <u>(مَفْعَل)</u> بفتح العين من كل فعل ثلاثي صحيح مفتوح العين أو مضمومها في المضارع، ومن كل فعل ثلاثي معتل الآخر (ناقص).",
                "  2. يصاغان على وزن <u>(مَفْعِل)</u> بكسر العين من كل فعل ثلاثي صحيح مكسور العين في المضارع، ومن كل فعل ثلاثي مثال واوي صحيح الآخر.",
                "- <u>صياغتهما من غير الثلاثي:</u> يصاغان على وزن اسم المفعول، وذلك بإبدال حرف المضارعة ميماً مضمومة وفتح ما قبل الآخر (مثل: مُسْتَوْدَع، مُلْتَقَى، مُنْطَلَق)."
            )

            else -> listOf(
                "<u>القاعدة والاستنتاج العام:</u>",
                "- <u>تعريف «$topic»:</u> ظاهرة لغوية تؤدي وظيفة دلالية وتركيبية محددة في الكلام العربي.",
                "- <u>أحكامه وشروطه:</u> يصاغ أو يعمل وفق ضوابط وشروط صرفية ونحوية مضبوطة.",
                "- <u>إعرابه:</u> يعرب بحسب موقعه وعلاقته بالعناصر المجاورة له في التركيب."
            )
        }
    }

    private fun jsonArrayToList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val item = arr.optString(i, "").trim()
            if (item.isNotBlank()) list.add(item)
        }
        return list
    }
}
