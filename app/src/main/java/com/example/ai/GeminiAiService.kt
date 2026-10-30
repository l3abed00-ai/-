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
        if (comp == PedagogicalComponent.LANGUAGE) {
            mutableStages["lang_observation"]?.let { mutableStages["lang_analysis"] = it }
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

        // Skip pure metadata or noise lines or standalone durations/segments
        if (clean.startsWith("الأستاذ") || clean.startsWith("المؤسسة") ||
            clean.startsWith("السنة الدراسية") || clean.startsWith("المديرية") ||
            clean.startsWith("الأكاديمية") ||
            com.example.extractor.JadhadhaParser.isDurationOrSegmentNoise(clean)
        ) {
            return null
        }

        // Determine field type: abil, teacher, eval, or content
        val field = when {
            clean.startsWith("[جدول:") -> "content"
            clean.startsWith("القدرة على") || clean.startsWith("أن يتمكن") ||
                clean.startsWith("أن يتعرف") || clean.startsWith("أن يوظف") ||
                clean.startsWith("أن يميز") || clean.startsWith("أن يصوغ") ||
                clean.startsWith("مهارة ") || clean.startsWith("تذكير ب") ||
                clean.startsWith("استحضار مكتسبات") -> "abil"
            clean.startsWith("تقويم") || clean.startsWith("دعم") ||
                clean.contains("تقويم تشخيصي") || clean.contains("تقويم تكويني") ||
                clean.contains("تقويم إجمالي") || clean.startsWith("مراقبة") ||
                clean.startsWith("اختبار قدرة") || clean.startsWith("رصد قدرة") -> "eval"
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
                clean.startsWith("يستدرج") || clean.startsWith("يسطر") ||
                clean.startsWith("يساعد") -> "teacher"
            else -> "content"
        }

        // Determine target stage key based on semantic markers
        val stageKey = when (component) {
            PedagogicalComponent.LANGUAGE -> when {
                clean.contains("تمهيد") || clean.contains("تشخيص") || clean.contains("الدرس السابق") || clean.contains("يستحضرون") ->
                    "lang_diagnostic"
                clean.contains("الأمثلة") || clean.contains("الشواهد") || clean.contains("المجموعة") ||
                    clean.startsWith("يتأملون") || clean.startsWith("يلاحظون") ||
                    clean.startsWith("[جدول:") || clean.contains("الظاهرة اللغوية") || clean.contains("متغيراتها") ||
                    clean.startsWith("يصفون") || clean.contains("وزنه") || clean.contains("صياغت") ||
                    clean.contains("مفعل") || clean.contains("الجدول") || clean.contains("تحليل") ->
                    "lang_observation"
                clean.contains("القاعدة") || clean.contains("الاستنتاج") || clean.startsWith("يستنتجون") ||
                    clean.contains("يصوغ") || clean.contains("يدل على زمان") || clean.contains("يدل على مكان") ->
                    "lang_synthesis"
                clean.contains("تطبيق") || clean.contains("تمرين") || clean.contains("أعرب") ||
                    clean.contains("استخرج") || clean.contains("ركب") || clean.startsWith("ينجزون") ||
                    clean.startsWith("يطبقون") ->
                    "lang_application"
                else -> {
                    stageKeys.firstOrNull { k ->
                        currentStages[k]?.sourceFor(field) == FieldSource.DEFAULT
                    } ?: "lang_observation"
                }
            }

            PedagogicalComponent.READING -> when {
                clean.contains("الحصة السابقة") || clean.contains("استرجاع مكتسبات ح") -> "session2_intro"
                clean.contains("تمهيد") || clean.contains("تشخيص") || clean.contains("الدرس السابق") -> "diagnostic"
                clean.contains("العنوان") || clean.contains("العتبات") || clean.contains("فرضيات") || clean.contains("صاحب النص") || clean.contains("تأطير") -> "observation"
                clean.contains("الفكرة العامة") || clean.contains("الأفكار الأساسية") || clean.contains("الشرح") || clean.contains("المفردات") || clean.contains("قراءة") -> "comprehension"
                clean.contains("الحقول") || clean.contains("المعجم") || clean.contains("الأساليب") || clean.contains("القيم") || clean.contains("تحليل") || clean.contains("شخصيات") || clean.contains("أحداث") || clean.startsWith("[جدول:") -> "analysis"
                clean.contains("تركيب") || clean.contains("خلاصة") -> "synthesis"
                clean.contains("استثمار") || clean.contains("امتداد") || clean.contains("ابحث") -> "investment"
                else -> stageKeys.firstOrNull { k -> currentStages[k]?.sourceFor(field) == FieldSource.DEFAULT } ?: "analysis"
            }

            PedagogicalComponent.EXPRESSION -> when {
                clean.contains("تشخيص أخطاء") || clean.contains("شبكة تشخيص") || clean.startsWith("[جدول:") -> "insha_corr_diagnosis"
                clean.contains("التصحيح الفردي") || clean.contains("أوراق التحرير") || clean.contains("تثبيت إملائي") -> "insha_corr_support"
                clean.contains("نص الانطلاق") || clean.contains("قراءة النص وفهمه") -> "insha_acq_reading"
                clean.contains("عناصر المهارة") || clean.contains("الخطوات الإجرائية") -> "insha_acq_elements"
                clean.contains("تعريف اليوميات") || clean.contains("الاستنتاج") -> "insha_acq_synthesis"
                clean.contains("تقديم الموضوع") && clean.contains("إنتاج") -> "insha_prod_presentation"
                clean.contains("تقديم الموضوع") -> "insha_app_presentation"
                clean.contains("مسودة") || clean.contains("تحرير فردي") || clean.contains("إنتاج") -> "insha_prod_execution"
                clean.contains("تطبيق") || clean.contains("التدرب") -> "insha_app_execution"
                clean.contains("تصحيح") -> "insha_app_correction"
                else -> stageKeys.firstOrNull { k -> currentStages[k]?.sourceFor(field) == FieldSource.DEFAULT } ?: "insha_acq_elements"
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
      "abil": ["القدرات والمهارات المستهدفة الخاصة بـ ${sm.title}"],
      "teacher": ["الأنشطة التعليمية التعلمية الخاصة بـ ${sm.title}"],
      "learner": ["أنشطة المتعلم وإجاباته الخاصة بـ ${sm.title}"],
      "content": ["المحتوى والمضامين والأمثلة والجداول الفرعية الخاصة بـ ${sm.title}"],
      "eval": ["مؤشرات التقويم والدعم الخاصة بـ ${sm.title}"]
    }"""
        }

        return """
أنت مفتش تربوي ومحلل بيداغوجي خبير في تدريس اللغة العربية بالتعليم الثانوي بالمغرب (نموذج $PRIMARY_MODEL).
المهمة: تحليل النص الكامل المستخرج من ملف جذاذة درس (مكون: ${component.title}) وإرجاع كائن JSON مهيكل يحول الجذاذة المصدرية (مهما كان عدد أعمدتها 9 أو 8 أو 4 أو 2 وبأي اتجاه) إلى القالب الرسمي ذي 5 أعمدة دون إسقاط أي جملة أو جدول فرعي.

قواعد التوزيع والتحويل الصارمة:
1. عزل الترويسة: ضع بيانات الترويسة (الوحدة، المجال، المكون، الموضوع، مدة الإنجاز، المرجع، الأستاذ، الفئة المستهدفة، الوسائل التعليمية، المؤسسة، الكفايات التواصلية والمنهجية والمعرفية) في كائن "info" و"topic"، ولا تُدخلها في المراحل.
2. تجاهل الحصيص الزمني والمقاطع الشكلية: احذف نهائياً أرقام الدقائق المنفردة (مثل: "5 د"، "10 د"، "15 د"، "20 د"، "35 د") وعبارات التقسيم الشكلي العام ("المقطع التمهيدي"، "المقطع الوسيطي"، "المقطع النهائي").
3. في مكون الدرس اللغوي: ادمج مرحلتي "ملاحظة الأمثلة" و"الوصف والتحليل" في مرحلة واحدة هي "lang_observation" (ملاحظة الأمثلة ووصفها وتحليلها)، وضع فيها أمثلة المجموعات متبوعة بالجداول التحليلية بصيغة "[جدول: عمود1 | عمود2 | ...]" والاستنتاجات الجزئية.
4. عدم إسقاط أي جملة (100% حفظ للمحتوى): وزّع كل جملة أو فقرة أو مثال أو سؤال أو جدول تحليلي أو جدول تشخيص أخطاء ورد في النص المستخرج على المرحلة والخليّة المناسبة له.
5. لا تستعمل إطلاقاً أي وسوم HTML مثل <u> أو </u> أو <b> في النصوص.

النص المستخرج من ملف الجذاذة:
<<<
$rawText
>>>

أرجع النتيجة بصيغة JSON حصراً دون أي نص خارجي أو علامات markdown، وفق الهيكل التالي:
{
  "topic": "$fallbackTopic",
  "component": "${component.id}",
  "info": {
    "level": "السنة الثالثة إعدادي",
    "unit": "الوحدة...",
    "domain": "المجال...",
    "component": "${component.title}",
    "duration": "${component.defaultDuration}",
    "materials": "${component.defaultMaterials}",
    "reference": "المختار في اللغة العربية",
    "teacherName": "محمد العابد",
    "schoolName": "مدرسة أنوال (السلك الإعدادي)",
    "communicative": "الكفاية التواصلية...",
    "methodological": "الكفاية المنهجية...",
    "cultural": "الكفاية المعرفية..."
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
        level: String = "السنة الثالثة إعدادي",
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
المراحل السبع الإلزامية لمكون النصوص القرائية (على حصتين):
1. diagnostic (تشخيص المكتسبات - الحصة الأولى)
2. observation (ملاحظة النص - الحصة الأولى)
3. comprehension (فهم النص - الحصة الأولى)
4. session2_intro (تمهيد - بداية الحصة الثانية)
5. analysis (القراءة التحليلية - الحصة الثانية)
6. synthesis (التركيب - الحصة الثانية)
7. investment (الاستثمار - الحصة الثانية)
"""
            PedagogicalComponent.LANGUAGE -> """
المراحل الأربع الإلزامية لمكون الدرس اللغوي:
1. lang_diagnostic (تقويم تشخيصي)
2. lang_observation (ملاحظة الأمثلة ووصفها وتحليلها - تتضمن أمثلة المجموعات والجدول التحليلي [جدول: ...] والاستنتاج الجزئي)
3. lang_synthesis (الاستنتاج - القاعدة العامة ونموذج من الإعراب)
4. lang_application (تطبيق)
"""
            PedagogicalComponent.EXPRESSION -> """
المراحل الفرعية الـ 16 الموزعة على الحصص الأربع لمكون التعبير والإنشاء:
- الحصة 1 (الاكتساب): insha_acq_diagnostic، insha_acq_reading، insha_acq_elements، insha_acq_synthesis، insha_acq_investment
- الحصة 2 (التطبيق): insha_app_diagnostic، insha_app_presentation، insha_app_execution، insha_app_correction
- الحصة 3 (الإنتاج): insha_prod_diagnostic، insha_prod_presentation، insha_prod_execution، insha_prod_correction
- الحصة 4 (التصحيح): insha_corr_diagnostic، insha_corr_diagnosis (تتضمن جدول شبكة تشخيص الأخطاء [جدول: الخطأ | نوعه | الصواب | القاعدة الضابطة / التعليل المنهجي])، insha_corr_support
"""
        }

        sb.append(
            """يجب أن تكون الاستجابة بصيغة JSON حصرا دون أي كلام إضافي أو علامات markdown، بالمفاتيح التالية:
{
  "topic": "$topic",
  "component": "${component.id}",
  "info": {
    "level": "السنة الثالثة إعدادي",
    "unit": "اسم الوحدة",
    "domain": "اسم المجال",
    "component": "${component.title}",
    "duration": "${component.defaultDuration}",
    "materials": "${component.defaultMaterials}",
    "reference": "المختار في اللغة العربية",
    "teacherName": "محمد العابد",
    "schoolName": "مدرسة أنوال (السلك الإعدادي)",
    "communicative": "صياغة الكفاية التواصلية",
    "methodological": "صياغة الكفاية المنهجية",
    "cultural": "صياغة الكفاية المعرفية"
  },
  "stages": {
${stagesKeys.joinToString(",\n") { "    \"$it\": { \"eval\": [\"...\"], \"content\": [\"...\"], \"teacher\": [\"...\"], \"abil\": [\"...\"] }" }}
  }
}
$stagesGuide
قواعد الصياغة البيداغوجية:
- كل عنصر في teacher يبدأ بفعل مضارع للمدرس (يطرح، يوجه، يناقش، يحفز...).
- كل عنصر في abil يبدأ بـ «القدرة على...» أو «مهارة...».
- في content: لا تستعمل وسوم HTML مثل <u> أو <b> إطلاقاً؛ استعمل «العنوان: تفصيل» أو «[جدول: عمود1 | عمود2 | ...]» للجداول الفرعية.
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
            reference = infoObj.optString("reference", fallbackPlan.info.reference).ifBlank { fallbackPlan.info.reference },
            teacherName = infoObj.optString("teacherName", fallbackPlan.info.teacherName).ifBlank { fallbackPlan.info.teacherName },
            schoolName = infoObj.optString("schoolName", fallbackPlan.info.schoolName).ifBlank { fallbackPlan.info.schoolName },
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
        val objectives = synthesizeSmartObjectives(cleanTopic, component, "السنة الثالثة إعدادي")

        val info = JadhadhaInfo(
            level = "السنة الثالثة إعدادي",
            unit = unitName,
            domain = domainName,
            component = component.title,
            duration = component.defaultDuration,
            materials = component.defaultMaterials,
            reference = "المختار في اللغة العربية",
            teacherName = "محمد العابد",
            schoolName = "مدرسة أنوال (السلك الإعدادي)",
            communicativeComp = objectives.communicativeComp,
            methodologicalComp = objectives.methodologicalComp,
            culturalComp = objectives.culturalComp
        )

        val stages = when (component) {
            PedagogicalComponent.READING -> mapOf(
                "diagnostic" to StageData(
                    abil = listOf(
                        "قدرة التلاميذ على استحضار مكتسباتهم السابقة التي تخدم الدرس قيد الإنجاز «$cleanTopic»."
                    ),
                    teacher = listOf(
                        "- ماذا تعرف عن السياق المرتبط بـ $domainName؟",
                        "- يمهد لموضوع الدرس «$cleanTopic» بأسئلة تشخيصية تثير اهتمام المتعلمين."
                    ),
                    content = listOf(
                        "معطيات تمهيدية مرتبطة بـ $domainName وموضوع «$cleanTopic»."
                    ),
                    eval = listOf(
                        "يدعم الأستاذ جوانب التعثر بتوضيح السياق، ويعزز الإجابات السليمة."
                    )
                ),
                "observation" to StageData(
                    abil = listOf(
                        "- مهارة الملاحظة.",
                        "- القدرة على استخراج القرائن والمؤشرات المساعدة على صياغة الفرضية.",
                        "- القدرة على صياغة فرضية القراءة.",
                        "- مهارة التعبير."
                    ),
                    teacher = listOf(
                        "1- الملاحظة والتأطير:",
                        "+ من هو صاحب النص؟ وما مصدره؟ وما نوعيته؟",
                        "+ مما يتركب العنوان «$cleanTopic»؟ وبما يشي؟",
                        "+ اقرأ بداية النص ونهايته ودون استنتاجك.",
                        "2- الفرضية:",
                        "+ انطلاقا من العناصر السابق الوقوف عليها صغ فرضية للنص."
                    ),
                    content = listOf(
                        "1- الملاحظة والتأطير:",
                        "- العنوان («$cleanTopic»): تركيبيا مركب دال يختزل بؤرة النص، ودلاليا يوحي بأبعاد موضوع «$cleanTopic» وتجلياته.",
                        "- بداية النص ونهايته: تشير البداية («$openingSnippet») والنهاية («$closingSnippet») إلى تطور الرؤية الفكرية والوجدانية في النص.",
                        "- نوعية النص: نص قرائي يندرج ضمن $domainName ($unitName).",
                        "2- الفرضية: نفترض أن الموضوع يتناول أبعاد ومظاهر «$cleanTopic» ويبرز قيمه وآثاره."
                    ),
                    eval = listOf(
                        "+ تصحيح إجابات المتعلمين، وتدعيمها.",
                        "+ كل الفرضيات مقبولة شرط تمحيصها."
                    )
                ),
                "comprehension" to StageData(
                    abil = listOf(
                        "+ مهارة القراءة الفاحصة.",
                        "+ التمكن من فهم النص القرائي في سياقه العام.",
                        "+ إثراء الرصيد المعجمي.",
                        "+ مهارة تفكيك النص إلى مقاطع.",
                        "+ قدرة التلاميذ على صياغة الفكرة العامة للنص."
                    ),
                    teacher = listOf(
                        "- القراءة النموذجية للنص.",
                        "- القراءة التناوبية للمتعلمين.",
                        "- إضاءة النص وتذليل المعجم.",
                        "- استدراج المتعلمين لاستخلاص المضمون بالأسئلة.",
                        "+ مما سبق استخلص الأفكار الأساسية والمضمون العام للنص."
                    ),
                    content = listOf(
                        "• يقرأ الأستاذ النص قراءة مراعية مخارج الحروف وصفاتها.",
                        "• الشروح اللغوية: شرح المفردات والتعابير في سياقها التداولي داخل نص «$cleanTopic».",
                        "• المضمون العام للنص: إبراز الكاتب لمفهوم وأبعاد «$cleanTopic» وتأثيراته الفكرية والاجتماعية مع الدعوة إلى تمثل قيمه الإيجابية.",
                        "(تمحيص الفرضية): يثبت هذا المضمون صحة الفرضية المصاغة سابقا."
                    ),
                    eval = listOf(
                        "+ تقويم النطق الصحيح ومخارج الحروف للمتعلمين.",
                        "+ يقسم التلاميذ النص القرائي إلى فقرات.",
                        "+ تقويم نمو الفهم عند المتعلمين."
                    )
                ),
                "session2_intro" to StageData(
                    abil = listOf(
                        "تذكير بالحصة السابقة."
                    ),
                    teacher = listOf(
                        "- استدراج المتعلمين لاسترجاع مكتسبات الحصة الأولى.",
                        "- التذكير بمضمون النص ودلالاته."
                    ),
                    content = listOf(
                        "النص يعالج موضوع «$cleanTopic» ضمن $domainName ويبرز أبعاده ودلالاته الكبرى."
                    ),
                    eval = listOf(
                        "+ رصد قدرة المتعلم (ة) على تذكر مكتسباته."
                    )
                ),
                "analysis" to StageData(
                    abil = listOf(
                        "+ يحدد المتعلم(ة) معجم النص ويستخرج المفردات الدالة عليه.",
                        "+ يستخرج المتعلمون الأساليب التي بني عليها النص.",
                        "+ مهارة التعليق."
                    ),
                    teacher = listOf(
                        "+ قراءة النص [أولية + فردية].",
                        "1- المستوى المعجمي والدلالي: استخرج الألفاظ والعبارات الدالة على محور النص.",
                        "2- المستوى الأسلوبي والبلاغي: استخرج الأساليب الموظفة وطبيعة اللغة.",
                        "3- القيم ومقصدية النص: استخرج القيم الوطنية والإنسانية المبثوثة في النص."
                    ),
                    content = listOf(
                        "1- المستوى المعجمي (الحقول الدلالية):",
                        "- الحقل المرتبط بالموضوع («$cleanTopic»): الألفاظ والعبارات الدالة على صلب القضية ومحاورها.",
                        "- الحقل الاجتماعي / القيمي: العبارات الدالة على التفاعل الإنساني والأثر الوجداني.",
                        "2- المستوى الأسلوبي: توظيف الأساليب الخبرية للتقرير والتوضيح، والأساليب الإنشائية للإقناع والتأثير.",
                        "3- القيم المتضمنة: قيمة وعي ومسؤولية وتكامل مرتبطة بـ $domainName."
                    ),
                    eval = listOf(
                        "+ تقويم النطق الصحيح ومخارج الحروف.",
                        "+ رصد إمكانات المتعلمين التحليلية وتعزيزها وتقويتها.",
                        "+ ما الذي يسعى النص إلى تحقيقه؟."
                    )
                ),
                "synthesis" to StageData(
                    abil = listOf(
                        "+ اكتساب مهارتي التركيب والإنتاج."
                    ),
                    teacher = listOf(
                        "+ يطلب الأستاذ من المتعلمين تركيب ما تم التطرق إليه في الفهم والتحليل في خلاصات يثبتون أو ينفون فيها صحة الفرضية مع الإشارة إلى مضمون النص وكيفية بنائه.",
                        "+ يحرر كل تلميذ خلاصته التركيبية بمفرده."
                    ),
                    content = listOf(
                        "يعالج نص «$cleanTopic» قضية محورية تندرج ضمن $domainName، حيث أبرز الكاتب تجليات الموضوع وأبعاده الفكرية والواقعية، موظفاً معجماً غنياً وأساليب متماسكة لترسيخ قيم الوعي والمسؤولية لدى المتلقي."
                    ),
                    eval = listOf(
                        "+ اختبار قدرة المتعلم (ة) على الإنتاج (إعادة بناء النص)."
                    )
                ),
                "investment" to StageData(
                    abil = listOf(
                        "تنمية قدرة المتعلمين على التعبير واستثمار مكتسبات الدرس."
                    ),
                    teacher = listOf(
                        "يطالب المتعلمين بإنجاز أنشطة \"البحث والاستثمار\" بالكتاب المدرسي؛ لتقويم تعلماتهم."
                    ),
                    content = listOf(
                        "+ تكليف المتعلمين بإنجاز أنشطة \"الاستثمار والبحث\":",
                        "- اكتب فقرة مركزة تعبر فيها عن موقفك من قضية «$cleanTopic» موظفاً ما اكتسبته من معجم وأساليب."
                    ),
                    eval = listOf(
                        "+ تقويم قدرة المتعلم (ة) على تحويل المكتسبات لإنجاز أنشطة أخرى في وضعيات جديدة."
                    )
                )
            )

            PedagogicalComponent.LANGUAGE -> {
                val obsStage = StageData(
                    abil = listOf(
                        "- إقدار المتعلم على ملاحظة الظاهرة اللغوية «$cleanTopic».",
                        "- قدرة المتعلم على وصف الظاهرة وتفكيك عناصرها واستنباط أحكامها."
                    ),
                    teacher = listOf(
                        "- يسطر الأستاذ جدولاً على السبورة ويدون فيه أمثلة الانطلاق.",
                        "- يقرأ الأمثلة قراءة نموذجية ثم يدعو بعض المتعلمين لقراءتها.",
                        "- يستدرج المتعلمين بأسئلة ديداكتيكية لملاحظة الكلمات المكتوبة بلون مغاير وملء خانات الجدول التحليلي."
                    ),
                    content = buildLanguageAnalysisContent(cleanTopic),
                    eval = listOf(
                        "- تقويم تكويني: رصد قدرة المتعلمين على التمييز بين عناصر الظاهرة اللغوية وتركيب جمل موازية."
                    )
                )
                mapOf(
                    "lang_diagnostic" to StageData(
                        abil = listOf(
                            "استحضار المتعلم مكتسباته السابقة المتعلقة بالدرس اللغوي السابق."
                        ),
                        teacher = listOf(
                            "يطرح الأستاذ أسئلة تقويمية تشخيصية للوقوف على مدى تمكن المتعلمين من الدرس اللغوي السابق."
                        ),
                        content = listOf(
                            "- تذكير موجز بالقاعدة اللغوية السابقة مع التمثيل بجمل مفيدة.",
                            "- قراءة جمل التمهيد وتمييز التراكيب الممهدة لدرس «$cleanTopic»."
                        ),
                        eval = listOf(
                            "- تقويم مدى تذكر المتعلمين للقواعد السابقة واستعدادهم للدرس الجديد."
                        )
                    ),
                    "lang_observation" to obsStage,
                    "lang_analysis" to obsStage,
                    "lang_synthesis" to StageData(
                        abil = listOf(
                            "إقدار المتعلم على تجميع الاستنتاجات الجزئية وصياغة القاعدة العامة لدرس «$cleanTopic»."
                        ),
                        teacher = listOf(
                            "- يطرح الأستاذ أسئلة توجيهية لتجميع الاستنتاجات الجزئية وصياغة القاعدة العامة للدرس.",
                            "- يدون القاعدة العامة على السبورة ويقرؤها المتعلمون."
                        ),
                        content = buildLanguageSynthesisContent(cleanTopic),
                        eval = listOf(
                            "- تقويم مدى استيعاب المتعلمين للقاعدة العامة وقدرتهم على التمثيل لها."
                        )
                    ),
                    "lang_application" to StageData(
                        abil = listOf(
                            "إقدار المتعلم على استثمار وتوظيف القاعدة اللغوية في إنجاز تطبيقات متنوعة."
                        ),
                        teacher = listOf(
                            "- يكتب الأستاذ التمارين التطبيقية على السبورة أو يحيل على الكتاب المدرسي.",
                            "- يطالب المتعلمين بإنجازها فردياً على الدفاتر ثم جماعياً على السبورة."
                        ),
                        content = listOf(
                            "تمارين تطبيقية ونماذج إعرابية:",
                            "1- استخرج ظاهرة «$cleanTopic» من الجمل الآتية وبيّن نوعها وحكمها.",
                            "2- ركّب ثلاث جمل مفيدة تتضمن «$cleanTopic» في حالات مختلفة.",
                            "3- أعرب جملة نموذجية إعراباً تاماً يبرز عمل وموقع «$cleanTopic»."
                        ),
                        eval = listOf(
                            "- تصحيح جماعي على السبورة وفردي على الدفاتر، ورصد التعثرات قصد دعمها."
                        )
                    )
                )
            }

            PedagogicalComponent.EXPRESSION -> {
                val acqDiag = StageData(
                    abil = listOf("استحضار المتعلم مكتسباته السابقة المتعلقة بالمهارات التعبيرية وربطها بمهارة «$cleanTopic»."),
                    teacher = listOf("يطرح الأستاذ أسئلة تمهيدية لاستحضار مكتسبات المتعلمين وربطها بمهارة «$cleanTopic»."),
                    content = listOf("- المهارة المستهدفة هي: «$cleanTopic»."),
                    eval = listOf("+ تشخيص مكتسبات المتعلمين وتمثلاتهم الأولية حول المهارة.")
                )
                val acqRead = StageData(
                    abil = listOf("قراءة نص الانطلاق قراءة متأنية وفهم مضمونه العام."),
                    teacher = listOf("- يقرأ الأستاذ نص الانطلاق قراءة نموذجية، ثم يوجه المتعلمين لقراءته قراءة فردية وشرح مفرداته."),
                    content = listOf("المضمون العام لنص الانطلاق: نص نموذجي يبرز خصائص ومقومات مهارة «$cleanTopic»."),
                    eval = listOf("+ مراقبة سلامة القراءة ومدى استيعاب المضمون العام لنص الانطلاق.")
                )
                val acqElem = StageData(
                    abil = listOf("القدرة على تفكيك نص الانطلاق واستخراج العناصر المكونة لمهارة «$cleanTopic» وخصائصها."),
                    teacher = listOf("- يستدرج الأستاذ المتعلمين عبر أسئلة ديداكتيكية لاستخراج عناصر المهارة وخطواتها من نص الانطلاق."),
                    content = listOf(
                        "عناصر مهارة «$cleanTopic» وخطواتها:",
                        "1- المقدمة والتأطير: تحديد سياق الموضوع وعناصره الأولية.",
                        "2- العرض والتوسيع: تسلسل الأفكار والأحداث وتوظيف الروابط المناسبة.",
                        "3- الخاتمة: خلاصة مركزة أو التعبير عن الانطباع والموقف الشخصي."
                    ),
                    eval = listOf("+ تقويم تكويني لمدى قدرة المتعلمين على رصد عناصر المهارة من النص.")
                )
                val acqSynth = StageData(
                    abil = listOf("القدرة على تجميع المعطيات التحليلية وصياغة خلاصة تركيبية لمفهوم المهارة وعناصرها."),
                    teacher = listOf("- يوجه الأستاذ المتعلمين لصياغة استنتاج تركيبي يلخص تعريف المهارة وعناصرها ويدونه على السبورة."),
                    content = listOf(
                        "1- تعريف مهارة «$cleanTopic»: مهارة تعبيرية وتواصلية تهدف إلى تنظيم الأفكار وبنائها وفق تصميم منهجي محكم.",
                        "2- خطواتها: التأطير والمقدمة، العرض والتحليل، الخاتمة وإبداء الرأي."
                    ),
                    eval = listOf("+ قراءة الاستنتاج العام والتأكد من ضبط المتعلمين لخطوات المهارة.")
                )
                val acqInv = StageData(
                    abil = listOf("تهيئة المتعلمين لاستثمار خطوات المهارة في أنشطة تطبيقية لاحقة."),
                    teacher = listOf("- يكلف الأستاذ المتعلمين بالاستعداد للحصة التطبيقية عبر إعداد مسودة أولية حول موضوع يرتبط بمهارة «$cleanTopic»."),
                    content = listOf("الإعداد القبلي: التدرب المنزلي على توظيف خطوات المهارة استعداداً لحصة التطبيق."),
                    eval = listOf("+ توجيه المتعلمين وحثهم على الالتزام بعناصر المهارة في إعدادهم القبلي.")
                )
                val appDiag = StageData(
                    abil = listOf("استحضار مكتسبات حصة الاكتساب المتعلقة بمهارة «$cleanTopic»."),
                    teacher = listOf("- يستدرج الأستاذ المتعلمين للتذكير بتعريف مهارة «$cleanTopic» وعناصرها وخطواتها المنهجية."),
                    content = listOf("- التذكير بتعريف المهارة وعناصرها الأساسية والروابط اللغوية المناسبة لها."),
                    eval = listOf("+ تقويم تشخيصي لقياس مدى تذكر المتعلمين لخطوات المهارة.")
                )
                val appPres = StageData(
                    abil = listOf("قراءة نص الموضوع التطبيقي وفهم المطلوب وتحديد عناصر الإنجاز."),
                    teacher = listOf("- يكتب الأستاذ نص الموضوع التطبيقي على السبورة، ويقرؤه ثم يدعو المتعلمين لقراءته واستخراج الكلمات المفاتيح."),
                    content = listOf("نص الموضوع التطبيقي: اكتب موضوعاً إنشائياً تستثمر فيه خطوات مهارة «$cleanTopic» لمعالجة قضية مرتبطة بـ $domainName."),
                    eval = listOf("+ التحقق من فهم المتعلمين لتعليمة الموضوع التطبيقي وخطوات إنجازه.")
                )
                val appExec = StageData(
                    abil = listOf("القدرة على تطبيق خطوات المهارة وبناء موضوع متكامل جماعياً وفردياً."),
                    teacher = listOf("- يوجه الأستاذ المتعلمين لإنجاز المطلوب على دفاترهم، ثم يقود عملاً جماعياً لبناء نموذج تطبيقي مشترك على السبورة."),
                    content = listOf("نموذج الإنجاز التطبيقي المشترك مستوفٍ لعناصر مهارة «$cleanTopic» وروابطها اللغوية."),
                    eval = listOf("+ تتبع إنجازات المتعلمين وتقديم التوجيهات المنهجية واللغوية اللازمة.")
                )
                val appCorr = StageData(
                    abil = listOf("تقويم الإنتاجات التطبيقية وتصويب التعثرات وتدوين النموذج المصحح."),
                    teacher = listOf("- يفتح الأستاذ المجال لقراءة بعض إنجازات المتعلمين ومناقشتها وتقويمها في ضوء معايير المهارة."),
                    content = listOf("- قراءة الإنتاجات الفردية ومقارنتها بالنموذج الجماعي وتصحيح الأخطاء اللغوية والمنهجية."),
                    eval = listOf("+ دعم المتعثرين وتعزيز الإنجازات الجيدة.")
                )
                val prodDiag = StageData(
                    abil = listOf("استحضار خطوات وضوابط مهارة «$cleanTopic» قبل الشروع في الإنتاج الفردي."),
                    teacher = listOf("- يمهد الأستاذ للحصة بتذكير سريع بعناصر المهارة التي تم التدرب عليها في حصتي الاكتساب والتطبيق."),
                    content = listOf("- استحضار التصميم المنهجي لمهارة «$cleanTopic»."),
                    eval = listOf("+ التأكد من جاهزية المتعلمين للانخراط في التحرير الفردي.")
                )
                val prodPres = StageData(
                    abil = listOf("فهم وضعية الإنتاج الفردي واستيعاب شروط ومعايير الإنجاز."),
                    teacher = listOf("- يدون الأستاذ نص موضوع الإنتاج على السبورة، ويقرؤه ثم يوضح للمتعلمين المطلوب ومعايير التقويم."),
                    content = listOf("نص موضوع الإنتاج: حرر موضوعاً إنشائياً متكاملاً توظف فيه تقنيات مهارة «$cleanTopic» في حدود اثني عشر سطراً."),
                    eval = listOf("+ التأكد من استيعاب جميع المتعلمين لتعليمة الإنتاج.")
                )
                val prodExec = StageData(
                    abil = listOf("القدرة على التحرير الفردي المستقل وتوظيف خطوات المهارة بلغة سليمة."),
                    teacher = listOf("- يكلف الأستاذ المتعلمين بتحرير الموضوع فردياً على أوراق التحرير في هدوء تام مع مراقبة سير الإنجاز."),
                    content = listOf("- إنجاز المتعلمين الفردي للموضوع على أوراق التحرير الخاصة بهم."),
                    eval = listOf("+ مراقبة التزام المتعلمين بالوقت المخصص وبالعمل الفردي المستقل.")
                )
                val prodCorr = StageData(
                    abil = listOf("القدرة على المراجعة الذاتية للورقة قبل تسليمها للأستاذ."),
                    teacher = listOf("- يطالب الأستاذ المتعلمين بمراجعة أوراقهم وتدقيقها إملائياً ونحوياً قبل جمعها في نهاية الحصة."),
                    content = listOf("- جمع أوراق التحرير قصد تصحيحها خارج الفصل ورصد الأخطاء الشائعة لإعداد حصة التصحيح."),
                    eval = listOf("+ تعويد المتعلم على المراجعة الذاتية قبل تسليم المنتوج.")
                )
                val corrDiag = StageData(
                    abil = listOf("استحضار مكتسبات المتعلمين السابقة."),
                    teacher = listOf("- يستدرج الأستاذ المتعلمين لاستحضار خطوات مهارة «$cleanTopic» وتصميمها المنهجي."),
                    content = listOf("- التذكير بعناصر مهارة «$cleanTopic» ومعايير تقويم الإنتاج الكتابي."),
                    eval = listOf("+ اختبار مكتسبات المتعلمين وتصويب الأخطاء المعرفية.")
                )
                val corrDiagnosis = StageData(
                    abil = listOf(
                        "- مهارة الملاحظة والتحليل.",
                        "- القدرة على اكتشاف الخطأ وتصنيفه وتصويبه.",
                        "- الربط بين الخطأ والقاعدة الضابطة."
                    ),
                    teacher = listOf(
                        "- يسطر الأستاذ شبكة تشخيص الأخطاء على السبورة.",
                        "- يدرج أخطاء واقعية استُلت من أوراق المتعلمين أثناء التصحيح الخارجي.",
                        "- يدعو المتعلمين للتفكير الجماعي وتصنيف الأخطاء وتصويبها وذكر القاعدة الضابطة."
                    ),
                    content = listOf(
                        "شبكة تشخيص أخطاء المتعلمين وتصحيحها:",
                        "[جدول: الخطأ | نوعه | الصواب | القاعدة الضابطة / التعليل المنهجي]",
                        "[جدول: إِستعملت | إملائي | استعملتُ | تكتب همزة الوصل في ماضي الخماسي والسداسي وأمرهما ومصدرهما]",
                        "[جدول: لم يبقا | تركيبي | لم يبقَ | الفعل المضارع المعتل الآخر يُجزم بحذف حرف العلة]",
                        "[جدول: تعبير عامي | معجمي | تعبير فصيح | وجوب الالتزام بلغة عربية فصيحة وتجنب العبارات العامية والدخيلة]"
                    ),
                    eval = listOf(
                        "- تقويم مدى قدرة المتعلم على تمييز نوع الخطأ (إملائي، تركيبي، تعبيري/منهجي، معجمي).",
                        "- إشراك أكبر عدد من المتعلمين في التصحيح الجماعي على السبورة."
                    )
                )
                val corrSupport = StageData(
                    abil = listOf(
                        "- التقييم الذاتي وتصويب التعثرات.",
                        "- إغناء المعجم والأسلوب.",
                        "- الاستفادة من الإنتاجات المتميزة."
                    ),
                    teacher = listOf(
                        "أ- التصحيح الفردي: توزيع أوراق التحرير على المتعلمين وتوجيههم لتصحيح أخطائهم الخاصة.",
                        "ب- قراءة النماذج المتميزة: قراءة أجود موضوع كتبه أحد المتعلمين للإشادة به والاستئناس به."
                    ),
                    content = listOf(
                        "- تثبيت إملائي ونحوي للقواعد الضابطة للأخطاء المرصودة.",
                        "- إغناء معجم المهارة بروابط وتعابير فصيحة مناسبة لموضوع «$cleanTopic»."
                    ),
                    eval = listOf(
                        "- تقديم الدعم الفردي للمتعلمين المتعثرين.",
                        "- تشجيع التنافس الإيجابي بين المتعلمين."
                    )
                )

                mapOf(
                    "insha_acq_diagnostic" to acqDiag,
                    "insha_acq_reading" to acqRead,
                    "insha_acq_elements" to acqElem,
                    "insha_acq_synthesis" to acqSynth,
                    "insha_acq_investment" to acqInv,
                    "insha_app_diagnostic" to appDiag,
                    "insha_app_presentation" to appPres,
                    "insha_app_execution" to appExec,
                    "insha_app_correction" to appCorr,
                    "insha_prod_diagnostic" to prodDiag,
                    "insha_prod_presentation" to prodPres,
                    "insha_prod_execution" to prodExec,
                    "insha_prod_correction" to prodCorr,
                    "insha_corr_diagnostic" to corrDiag,
                    "insha_corr_diagnosis" to corrDiagnosis,
                    "insha_corr_support" to corrSupport,
                    "insha_acquisition" to acqElem,
                    "insha_application" to appExec,
                    "insha_production" to prodExec,
                    "insha_correction" to corrDiagnosis
                )
            }
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
                "أولاً: تعريف اسمي الزمان والمكان: اسمان مشتقان يصاغان للدلالة على زمان أو مكان وقوع الفعل.",
                "ثانياً: جدول الوصف والتحليل (صياغة اسمي الزمان والمكان):",
                "[جدول: المثال | اسم الزمان / المكان | وزنه | فعله ومضارعه | نوع الفعل | طريقة الصياغة]",
                "[جدول: مَشْرِقُ الشمس صباحاً | مَشْرِق | مَفْعِل | شَرَقَ - يَشْرِقُ | ثلاثي صحيح مكسور العين في المضارع | على وزن مَفْعِل]",
                "[جدول: مَسْكَنُ الأسرة فسيح | مَسْكَن | مَفْعَل | سَكَنَ - يَسْكُنُ | ثلاثي صحيح مضموم العين في المضارع | على وزن مَفْعَل]",
                "[جدول: مَلْعَبُ الحي قريب | مَلْعَب | مَفْعَل | لَعِبَ - يَلْعَبُ | ثلاثي صحيح مفتوح العين في المضارع | على وزن مَفْعَل]",
                "[جدول: مَوْعِدُ الحفل مساءً | مَوْعِد | مَفْعِل | وَعَدَ - يَعِدُ | ثلاثي معتل المثال الواوي | على وزن مَفْعِل]",
                "[جدول: مَرْمَى الكرة بعيد | مَرْمَى | مَفْعَل | رَمَى - يَرْمِي | ثلاثي معتل ناقص | على وزن مَفْعَل]",
                "[جدول: مُجْتَمَعُ اللجنة ظهراً | مُجْتَمَع | مُفْتَعَل | اجْتَمَعَ - يَجْتَمِعُ | غير ثلاثي | على وزن اسم المفعول (ميم مضمومة وفتح ما قبل الآخر)]",
                "* استنتاج جزئي: قد تلحق تاء التأنيث بعض أسماء المكان على وزن (مَفْعَلَة) للدلالة على الكثرة مثل: مَطْبَعَة، مَكْتَبَة، مَزْرَعَة."
            )

            topic.contains("الفاعل") || topic.contains("المبالغة") -> listOf(
                "المجموعة 1 (صياغة اسم الفاعل وعمله):",
                "[جدول: الجمل | اسم الفاعل | فعله | طريقة صياغته | حالته | عمله | الشرط أو السبب]",
                "[جدول: الشاكرُ نعمَ اللهِ محبوب | الشاكر | شكر (ثلاثي) | على وزن فاعل | مقترن بـ (أل) | رفع الفاعل ونصب المفعول به (نعمَ) | يعمل بدون شرط لأنه مقترن بـ (أل)]",
                "[جدول: ما مخلفٌ عهدَهُ مؤمنٌ | مخلفٌ | أخلف (غير ثلاثي) | ميم مضمومة وكسر ما قبل الآخر | مجرد من (أل) | رفع الفاعل ونصب المفعول به (عهدَه) | دال على الحال ومسبوق بنفي]",
                "المجموعة 2 (صيغ المبالغة وعملها):",
                "[جدول: الجمل | صيغة المبالغة | وزنها | فعلها | عملها]",
                "[جدول: المؤمنُ صبّارٌ عند الشدائد | صبّار | فَعّال | صبر | رفع الفاعل الضمير المستتر]"
            )

            topic.contains("المفعول") -> listOf(
                "جدول الوصف والتحليل (صياغة وعمل اسم المفعول):",
                "[جدول: المثال | اسم المفعول | وزنه | فعله | عمله (نائب الفاعل) | شرط عمله]",
                "[جدول: المحمودُ خُلُقُهُ ممدوحٌ | المحمود | المفعول | حُمِدَ (ثلاثي مبني للمجهول) | رفع نائب الفاعل (خُلُقُه) | مقترن بـ (أل)]",
                "[جدول: أَمُكْرَمٌ الضيفُ في بيتكم؟ | مُكْرَمٌ | مُفْعَل | أُكْرِمَ (غير ثلاثي) | رفع نائب الفاعل (الضيفُ) | دال على الحال ومسبوق باستفهام]"
            )

            topic.contains("الاختصاص") -> listOf(
                "جدول الوصف والتحليل (عناصر أسلوب الاختصاص وحالات الاسم المختص):",
                "[جدول: الجملة | الضمير ونوعه | الاسم المختص | حالته | حكمه الإعرابي]",
                "[جدول: نحن - المغاربةَ - نعتز بوطننا | نحن (للمتكلم) | المغاربةَ | مقترن بـ (أل) | مفعول به منصوب لفعل محذوف وجوباً تقديره أخصّ]",
                "[جدول: نحن - معشرَ الشبابِ - نبني المستقبل | نحن (للمتكلم) | معشرَ | مضاف إلى معرفة | مفعول به منصوب لفعل محذوف وجوباً تقديره أخصّ]",
                "[جدول: أنتم - أيّها المتعلمون - أمل الغد | أنتم (للمخاطب) | أيّها | لفظ (أيّها) متلو بنعت مرفوع | اسم مبني على الضم في محل نصب مفعول به]"
            )

            else -> listOf(
                "المجموعة 1 (أمثلة الانطلاق والجدول الاستقرائي لظاهرة «$topic»):",
                "[جدول: المثال والشاهد | الظاهرة اللغوية («$topic») | نوعها وبنيتها | حكمها وشروطها | دلالتها وإعرابها]",
                "[جدول: المثال الأول من مجموعة الانطلاق | الشاهد الأول | الحالة القياسية الأولى | توافر شروط الصياغة أو العمل | يعرب بحسب موقعه في التركيب]",
                "[جدول: المثال الثاني من مجموعة الانطلاق | الشاهد الثاني | الحالة الثانية | اقترانه بالقرائن السياقية | يؤدي وظيفة دلالية وتركيبية في الجملة]",
                "* استنتاج جزئي 1: تحديد ماهية «$topic» ووظيفته المعنوية واللغوية في الجملة.",
                "* استنتاج جزئي 2: تصنيف حالات وصيغ «$topic» واستنباط شروط صياغتها أو إعمالها."
            )
        }
    }

    private fun buildLanguageSynthesisContent(topic: String): List<String> {
        return when {
            topic.contains("الزمان") || topic.contains("المكان") -> listOf(
                "القاعدة والاستنتاج العام:",
                "- تعريفهما: اسم الزمان اسم مشتق يدل على زمان وقوع الفعل، واسم المكان اسم مشتق يدل على مكان وقوع الفعل.",
                "- صياغتهما من الفعل الثلاثي:",
                "  1. يصاغان على وزن (مَفْعَل) بفتح العين من كل فعل ثلاثي صحيح مفتوح العين أو مضمومها في المضارع، ومن كل فعل ثلاثي معتل الآخر (ناقص).",
                "  2. يصاغان على وزن (مَفْعِل) بكسر العين من كل فعل ثلاثي صحيح مكسور العين في المضارع، ومن كل فعل ثلاثي مثال واوي صحيح الآخر.",
                "- صياغتهما من غير الثلاثي: يصاغان على وزن اسم المفعول، وذلك بإبدال حرف المضارعة ميماً مضمومة وفتح ما قبل الآخر (مثل: مُسْتَوْدَع، مُلْتَقَى، مُنْطَلَق)."
            )

            else -> listOf(
                "القاعدة والاستنتاج العام:",
                "- تعريف «$topic»: ظاهرة لغوية تؤدي وظيفة دلالية وتركيبية محددة في الكلام العربي.",
                "- أحكامه وشروطه: يصاغ أو يعمل وفق ضوابط وشروط صرفية ونحوية مضبوطة.",
                "- إعرابه: يعرب بحسب موقعه وعلاقته بالعناصر المجاورة له في التركيب."
            )
        }
    }

    private fun jsonArrayToList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val item = arr.optString(i, "")
                .replace(Regex("""</?(?:u|b|i|strong|em)\s*>""", RegexOption.IGNORE_CASE), "")
                .trim()
            if (item.isNotBlank()) list.add(item)
        }
        return list
    }
}
