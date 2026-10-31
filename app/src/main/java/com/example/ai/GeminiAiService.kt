package com.example.ai

import android.content.Context
import android.util.Base64
import com.example.BuildConfig
import com.example.extractor.DocumentFileReader
import com.example.model.ClassificationSource
import com.example.model.ContentUnitStatus
import com.example.model.ExtractedUnit
import com.example.model.FieldSource
import com.example.model.GeminiClassificationValidationResult
import com.example.model.JadhadhaInfo
import com.example.model.JadhadhaPlan
import com.example.model.ParseReport
import com.example.model.ParseResult
import com.example.model.PedagogicalCategory
import com.example.model.PedagogicalClassificationItem
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

    /**
     * Pluggable HTTP transport for Gemini requests.
     * Uses OkHttp in production and can be overridden in unit tests via [setTransportForTesting]
     * to verify real JSON parsing, network failures, invalid responses, and confidence thresholds.
     */
    fun interface GeminiTransport {
        suspend fun executePost(url: String, jsonBody: String, apiKey: String): Pair<Int, String>
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val defaultTransport = GeminiTransport { url, jsonBody, apiKey ->
        val reqBuilder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
        if (apiKey.isNotBlank()) {
            reqBuilder.addHeader("x-goog-api-key", apiKey)
        }
        val request = reqBuilder
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        response.code to body
    }

    @Volatile
    private var customTransportForTesting: GeminiTransport? = null

    fun setTransportForTesting(transport: GeminiTransport?) {
        customTransportForTesting = transport
    }

    fun resetTransportForTesting() {
        customTransportForTesting = null
    }

    private suspend fun executePostRequest(url: String, jsonBody: String, apiKey: String): Pair<Int, String> {
        val transport = customTransportForTesting ?: defaultTransport
        return transport.executePost(url, jsonBody, apiKey)
    }

    private const val API_BASE = "https://generativelanguage.googleapis.com/v1beta"
    const val PRIMARY_MODEL = "gemini-3.8-flash"
    private val API_MODELS = listOf("gemini-3.8-flash", "gemini-2.5-flash", "gemini-2.0-flash")
    const val MAX_PDF_INLINE_BYTES = 10 * 1024 * 1024 // 10 MB
    const val DEFAULT_CONFIDENCE_THRESHOLD = 0.65
    const val LOCAL_RULE_HIGH_CONFIDENCE_THRESHOLD = 0.75
    const val MSG_PDF_NEEDS_KEY = "تفريغ PDF يحتاج مفتاح Gemini؛ أدخله أو حوّل الملف إلى docx"
    const val MSG_GEMINI_KEY_MISSING = "مفتاح Gemini غير متوفر أو غير صالح؛ يرجى إدخال مفتاح صالح في الإعدادات."
    const val MSG_LOCAL_FALLBACK_NOTICE = "تعذر الاتصال بـ Gemini؛ تم تطبيق القواعد المحلية فقط وتحتاج النتيجة إلى مراجعة يدوية."
    const val PREFS_NAME = "jadhadha_gemini_prefs"
    const val PREF_KEY_GEMINI_API_KEY = "gemini_api_key"

    @Volatile
    private var applicationContextRef: Context? = null

    fun initContext(context: Context) {
        applicationContextRef = context.applicationContext
    }

    fun saveApiKey(context: Context, apiKey: String) {
        initContext(context)
        com.example.security.SecretManager.saveApiKey(context, apiKey)
    }

    fun getSavedApiKey(context: Context): String {
        initContext(context)
        return com.example.security.SecretManager.getApiKey(context)
    }

    fun clearSavedApiKey(context: Context) {
        initContext(context)
        com.example.security.SecretManager.clearApiKey(context)
    }

    fun getEffectiveApiKey(customKey: String?, context: Context? = null): String {
        if (context != null) initContext(context)
        val trimmedCustom = customKey?.trim().orEmpty()
        if (trimmedCustom.isNotBlank() && !trimmedCustom.equals("MY_GEMINI_API_KEY", ignoreCase = true)) {
            return trimmedCustom
        }
        val saved = applicationContextRef?.let { com.example.security.SecretManager.getApiKey(it) }.orEmpty().trim()
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

    private fun canReachGemini(customKey: String?): Boolean {
        return customTransportForTesting != null || hasValidApiKey(customKey)
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
        if (customTransportForTesting == null && (apiKey.isBlank() || apiKey.startsWith("AQ.", ignoreCase = true))) {
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

                val (code, responseBody) = executePostRequest(url, bodyJson.toString(), apiKey)

                if (code !in 200..299) {
                    val rawErrMsg = runCatching {
                        JSONObject(responseBody).getJSONObject("error").getString("message")
                    }.getOrDefault("HTTP $code")
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
     * Phase 2 Context-Aware Pedagogical Classifier (Local Rules Engine).
     *
     * Analyzes the full syntactic and pedagogical structure of [rawLine] rather than isolated keywords:
     * - Distinguishes all 9 [PedagogicalCategory] values:
     *   1. [PedagogicalCategory.OBJECTIVES_COMPETENCIES]
     *   2. [PedagogicalCategory.MATERIALS_METHODS]
     *   3. [PedagogicalCategory.INTRODUCTION_LAUNCH]
     *   4. [PedagogicalCategory.TEACHER_ACTIVITIES]
     *   5. [PedagogicalCategory.LEARNER_ACTIVITIES]
     *   6. [PedagogicalCategory.EDUCATIONAL_CONTENT]
     *   7. [PedagogicalCategory.ANALYSIS_DISCUSSION]
     *   8. [PedagogicalCategory.EVALUATION]
     *   9. [PedagogicalCategory.SUPPORT_REMEDIATION]
     * - Does NOT classify an item based on a single isolated keyword (e.g., "تقويم" or "تحليل" or "فرضية")
     *   when the grammatical head/subject of the sentence proves a different function.
     * - Always tags its output with [ClassificationSource.LOCAL_RULE] (never [ClassificationSource.GEMINI]).
     * - Returns `null` when the sentence is ambiguous so it can be escalated to Gemini or human review.
     */
    fun classifyPedagogicalContextLocally(
        rawLine: String,
        component: PedagogicalComponent,
        unitId: String = "",
        stageKeys: List<String> = StageCatalog.getStagesForComponent(component).map { it.key },
        currentStages: Map<String, StageData> = emptyMap()
    ): PedagogicalClassificationItem? {
        val verbatim = rawLine.trim()
        val clean = verbatim.removePrefix("+").removePrefix("-").removePrefix("•").removePrefix("*").trim()
        if (clean.length < 3) return null

        // Skip administrative header labels or standalone duration noise
        if (clean.startsWith("الأستاذ") || clean.startsWith("المؤسسة") ||
            clean.startsWith("السنة الدراسية") || clean.startsWith("المديرية") ||
            clean.startsWith("الأكاديمية") ||
            com.example.extractor.JadhadhaParser.isDurationOrSegmentNoise(clean)
        ) {
            return null
        }

        // Determine pedagogical category and target field from sentence-level syntactic frame
        val categoryAndConfidence: Pair<PedagogicalCategory, Pair<Double, String>>? = when {
            // 1. OBJECTIVES_COMPETENCIES: Nominal competency/objective head ("القدرة على...", "أن يتمكن...", "مهارة...")
            // Even if the object clause mentions "تقويم" or "تحليل" or "فرضية", the syntactic head is an objective.
            Regex("""^(القدرة على|قدرة المتعلم|قدرة التلاميذ|أن يتمكن|أن يتعرف|أن يوظف|أن يميز|أن يصوغ|أن يحلل|أن يستنتج|أن يدرك|أن يقرأ|أن يكتسب|مهارة |كفاية |استحضار المتعلم مكتسباته|استحضار مكتسبات المتعلمين|تنمية قدرة المتعلمين)""")
                .containsMatchIn(clean) -> {
                PedagogicalCategory.OBJECTIVES_COMPETENCIES to (0.92 to "بنية مصدرية/مؤولية تدل على قدرة أو هدف تعلمي مستهدف")
            }

            // 4. TEACHER_ACTIVITIES: Explicit teacher subject or didactic directive verb directed at learners
            // Even if the complement mentions "لتقويم الفهم" or "لتحليل النص" or "فرضية القراءة", the grammatical agent is the teacher.
            Regex("""^(يطرح الأستاذ|يوجه الأستاذ|يدون الأستاذ|يرسم الأستاذ|يطالب الأستاذ|يكلف الأستاذ|يستدرج الأستاذ|يسطر الأستاذ|يناقش الأستاذ|يقرأ الأستاذ|يمهد الأستاذ|يسجل الأستاذ|يوزع الأستاذ|يطلب الأستاذ|يساعد الأستاذ|يطرح أسئلة|يوجه المتعلمين|يستدرج المتعلمين|يطالب المتعلمين|يكلف المتعلمين|يدعو المتعلمين|يساعد المتعلمين|يرسم جدول|يدون أمثلة|القراءة النموذجية للنص|إضاءة النص وتذليل المعجم)""")
                .containsMatchIn(clean) -> {
                PedagogicalCategory.TEACHER_ACTIVITIES to (0.90 to "فعل ديداكتيكي مسند إلى الأستاذ أو موجه للمتعلمين")
            }

            // 5. LEARNER_ACTIVITIES: Plural learner verbs or explicit learner subject
            // Note: In the official 5-column template, learner activities are placed in the didactic content/activities column ("content") or alongside teacher activities when unstructured.
            Regex("""^(يجيب المتعلمون|يقرأ المتعلمون|يتأمل المتعلمون|يلاحظ المتعلمون|يستنتج المتعلمون|ينجز المتعلمون|يصوغ المتعلمون|يستخرج المتعلمون|يحدد المتعلمون|يشتغل المتعلمون|يتأملون|يصفون|يلاحظون|يستنتجون|يجيبون|يقرؤون|يقارنون|يميزون|يطبقون|ينجزون)""")
                .containsMatchIn(clean) -> {
                PedagogicalCategory.LEARNER_ACTIVITIES to (0.88 to "فعل مسند إلى المتعلمين يصف نشاطهم التعلمي")
            }

            // 9. SUPPORT_REMEDIATION: Remediation, error treatment, and learner support
            Regex("""^(دعم |الدعم |معالجة |تقديم الدعم|أنشطة الدعم|تصحيح تعثرات|معالجة تعثرات|يدعم الأستاذ جوانب التعثر|إشراك أكبر عدد من المتعلمين في التصحيح|تشجيع التنافس الإيجابي)""")
                .containsMatchIn(clean) -> {
                PedagogicalCategory.SUPPORT_REMEDIATION to (0.88 to "عبارة تدل في سياقها على الدعم التربوي ومعالجة التعثرات")
            }

            // 8. EVALUATION: Assessment criteria, verification of understanding, or diagnostic/formative/summative check
            Regex("""^(تقويم |مؤشرات التقويم|اختبار قدرة|اختبار مكتسبات|رصد قدرة|رصد إمكانات|مراقبة سلامة|تقويم مدى|تقويم تشخيصي|تقويم تكويني|تقويم إجمالي|تصحيح إجابات المتعلمين|كل الفرضيات مقبولة شرط تمحيصها)""")
                .containsMatchIn(clean) -> {
                PedagogicalCategory.EVALUATION to (0.88 to "مؤشر تقويمي لقياس درجة تحقق التعلمات")
            }

            // 2. MATERIALS_METHODS: Pedagogical tools, media, and teaching methods
            Regex("""^(الوسائل التعليمية|الوسائل الديداكتيكية|الطرائق البيداغوجية|اعتماد الطريقة|الاعتماد على السبورة|السبورة\s*-|الكتاب المدرسي والسبورة|طريقة حوارية|عمل في مجموعات)""")
                .containsMatchIn(clean) -> {
                PedagogicalCategory.MATERIALS_METHODS to (0.86 to "تحديد للوسائل الديداكتيكية أو الطرائق البيداغوجية")
            }

            // 3. INTRODUCTION_LAUNCH: Introductory context, prior lesson recall, or problem launch
            Regex("""^(وضعية الانطلاق|تمهيد إشكالي|تذكير بالدرس السابق|تذكير بالحصة السابقة|معطيات تمهيدية|معطيات تاريخية مرتبطة|إجابات المتعلمين تستثمر للربط)""")
                .containsMatchIn(clean) -> {
                PedagogicalCategory.INTRODUCTION_LAUNCH to (0.85 to "سياق تمهيدي أو انطلاق للربط بالتعلمات السابقة")
            }

            // 7. ANALYSIS_DISCUSSION: Analytical tables, lexical fields, stylistic analysis, error diagnosis grids
            clean.startsWith("[جدول:") ||
                Regex("""^(جدول الوصف والتحليل|شبكة تشخيص أخطاء|الحقول الدلالية|الحقول المعجمية|الخصائص الأسلوبية|عناصر التحليل|أحداث النص:|الشخصيات:|الزمان والمكان:|مناقشة الأمثلة وتفكيكها)""")
                    .containsMatchIn(clean) -> {
                PedagogicalCategory.ANALYSIS_DISCUSSION to (0.87 to "بنية تحليلية أو جدول تفكيك ومناقشة للمعطيات")
            }

            // 6. EDUCATIONAL_CONTENT: Structured lesson content (definitions, text thresholds, rules, examples, synthesis)
            Regex("""^(صاحب النص|مصدر النص|نوعية النص|العنوان:|دلالة العنوان|بداية النص|نهاية النص|الفرضية:|فرضية القراءة|الشروح اللغوية|المضمون العام|الفكرة العامة|الأفكار الأساسية|القاعدة العامة|القاعدة والاستنتاج|استنتاج جزئي|الاستنتاج:|خلاصة تركيبية|المجموعة [0-9١-٩]|أمثلة الانطلاق|تثبيت إملائي|تثبيت تركيبي|إغناء معجم)""")
                .containsMatchIn(clean) -> {
                PedagogicalCategory.EDUCATIONAL_CONTENT to (0.86 to "محتوى معرفي/ديداكتيكي منظم خاص بالدرس")
            }

            else -> null
        }

        if (categoryAndConfidence == null) {
            return null
        }

        val (category, confAndRationale) = categoryAndConfidence
        val (confidence, rationale) = confAndRationale

        // Map category to the 4 stage fields ("abil", "teacher", "content", "eval")
        val targetField = when (category) {
            PedagogicalCategory.OBJECTIVES_COMPETENCIES -> "abil"
            PedagogicalCategory.TEACHER_ACTIVITIES -> "teacher"
            PedagogicalCategory.EVALUATION,
            PedagogicalCategory.SUPPORT_REMEDIATION -> "eval"
            PedagogicalCategory.LEARNER_ACTIVITIES -> {
                // In Language lessons where unstructured "+" lines represent analytical steps in teacher/activity column,
                // keep compatibility if explicitly prefixed with "+" and teacher is DEFAULT
                if (component == PedagogicalComponent.LANGUAGE && verbatim.startsWith("+")) "teacher" else "content"
            }
            PedagogicalCategory.MATERIALS_METHODS,
            PedagogicalCategory.INTRODUCTION_LAUNCH,
            PedagogicalCategory.EDUCATIONAL_CONTENT,
            PedagogicalCategory.ANALYSIS_DISCUSSION -> "content"
        }

        val stageKey = inferContextStageKey(clean, category, component, targetField, stageKeys, currentStages)

        return PedagogicalClassificationItem(
            unitId = unitId,
            originalText = verbatim,
            proposedCategory = category,
            targetField = targetField,
            component = component,
            stageKey = stageKey,
            confidence = confidence,
            source = ClassificationSource.LOCAL_RULE,
            rationale = rationale
        )
    }

    private fun inferContextStageKey(
        clean: String,
        category: PedagogicalCategory,
        component: PedagogicalComponent,
        field: String,
        stageKeys: List<String>,
        currentStages: Map<String, StageData>
    ): String {
        val officialSet = StageCatalog.getStagesForComponent(component).map { it.key }.toSet()
        val candidate = when (component) {
            PedagogicalComponent.LANGUAGE -> when {
                category == PedagogicalCategory.INTRODUCTION_LAUNCH -> "lang_diagnostic"
                category == PedagogicalCategory.ANALYSIS_DISCUSSION -> "lang_observation"
                clean.contains("الدرس السابق") || clean.contains("تشخيص") || clean.contains("تمهيد") ->
                    "lang_diagnostic"
                clean.startsWith("[جدول:") || clean.contains("الأمثلة") || clean.contains("الظاهرة اللغوية") ||
                    clean.contains("متغيراتها") || clean.contains("يتأملون") || clean.contains("يصفون") ||
                    clean.contains("الوصف والتحليل") || clean.contains("استنتاج جزئي") ->
                    "lang_observation"
                clean.contains("القاعدة العامة") || clean.contains("القاعدة الكلية") ||
                    clean.contains("تجميع الاستنتاجات") || clean.startsWith("القاعدة والاستنتاج") ->
                    "lang_synthesis"
                clean.contains("تطبيق") || clean.contains("تمارين") || clean.contains("أعرب") ||
                    clean.contains("ركب جمل") || clean.startsWith("يطبقون") || clean.startsWith("ينجزون") ->
                    "lang_application"
                else -> stageKeys.firstOrNull { k ->
                    k in officialSet && currentStages[k]?.sourceFor(field) == FieldSource.DEFAULT
                } ?: "lang_observation"
            }

            PedagogicalComponent.READING -> when {
                category == PedagogicalCategory.INTRODUCTION_LAUNCH -> {
                    if (clean.contains("الحصة السابقة") || clean.contains("الحصة الأولى") ||
                        clean.contains("استرجاع مكتسبات ح")
                    ) {
                        "session2_intro"
                    } else {
                        "diagnostic"
                    }
                }
                category == PedagogicalCategory.ANALYSIS_DISCUSSION -> "analysis"
                clean.contains("الحصة السابقة") || clean.contains("استرجاع مكتسبات الحصة الأولى") ||
                    clean.contains("استرجاع مكتسبات ح") ->
                    "session2_intro"
                clean.contains("تشخيص") || clean.contains("تمهيد") ->
                    "diagnostic"
                clean.contains("صاحب النص") || clean.contains("مصدر النص") || clean.contains("نوعية النص") ||
                    clean.contains("العنوان") || clean.contains("الفرضية") || clean.contains("فرضية القراءة") ||
                    clean.contains("بداية النص") ->
                    "observation"
                clean.contains("القراءة النموذجية") || clean.contains("قراءة نموذجية") ||
                    clean.contains("القراءة التناوبية") || clean.contains("قراءة تناوبية") ||
                    clean.contains("الشروح اللغوية") || clean.contains("شرح المفردات") ||
                    clean.contains("يشرحون المفردات") || clean.contains("المضمون العام") ||
                    clean.contains("الفكرة العامة") || clean.contains("الأفكار الأساسية") ->
                    "comprehension"
                clean.contains("الحقول") || clean.contains("المعجم") || clean.contains("الأساليب") ||
                    clean.contains("القيم") || clean.contains("أحداث النص") || clean.contains("الشخصيات") ->
                    "analysis"
                clean.contains("خلاصة تركيبية") || clean.contains("التركيب") || clean.contains("إعادة بناء النص") ->
                    "synthesis"
                clean.contains("الاستثمار") || clean.contains("أنشطة البحث") || clean.contains("امتداد") ->
                    "investment"
                else -> stageKeys.firstOrNull { k ->
                    k in officialSet && currentStages[k]?.sourceFor(field) == FieldSource.DEFAULT
                } ?: "analysis"
            }

            PedagogicalComponent.EXPRESSION -> when {
                category == PedagogicalCategory.INTRODUCTION_LAUNCH -> when {
                    clean.contains("التصحيح") || clean.contains("أخطاء") -> "insha_corr_diagnostic"
                    clean.contains("الإنتاج") || clean.contains("تحرير") -> "insha_prod_diagnostic"
                    clean.contains("التطبيق") || clean.contains("التدرب") -> "insha_app_diagnostic"
                    else -> "insha_acq_diagnostic"
                }
                category == PedagogicalCategory.ANALYSIS_DISCUSSION -> {
                    if (clean.contains("شبكة تشخيص") || clean.contains("تشخيص أخطاء") ||
                        clean.contains("أخطاء") || clean.startsWith("[جدول:")
                    ) {
                        "insha_corr_diagnosis"
                    } else {
                        "insha_acq_elements"
                    }
                }
                clean.contains("شبكة تشخيص") || clean.contains("تشخيص أخطاء") || clean.startsWith("[جدول:") ->
                    "insha_corr_diagnosis"
                clean.contains("التصحيح الفردي") || clean.contains("أوراق التحرير") ||
                    clean.contains("تثبيت إملائي") || clean.contains("تثبيت تركيبي") || clean.contains("إغناء معجم") ->
                    "insha_corr_support"
                clean.contains("نص الانطلاق") ->
                    "insha_acq_reading"
                clean.contains("عناصر المهارة") || clean.contains("خطوات المهارة") ->
                    "insha_acq_elements"
                clean.contains("القاعدة الضابطة للمهارة") || clean.contains("خلاصة المهارة") ->
                    "insha_acq_synthesis"
                clean.contains("تقديم الموضوع") && clean.contains("إنتاج") ->
                    "insha_prod_presentation"
                clean.contains("تقديم الموضوع") ->
                    "insha_app_presentation"
                clean.contains("تحرير فردي") || clean.contains("إنتاج فردي") ->
                    "insha_prod_execution"
                clean.contains("التدرب") || clean.contains("إنجاز جماعي") ->
                    "insha_app_execution"
                else -> stageKeys.firstOrNull { k ->
                    k in officialSet && currentStages[k]?.sourceFor(field) == FieldSource.DEFAULT
                } ?: "insha_acq_elements"
            }
        }
        return if (candidate in officialSet) candidate else officialSet.first()
    }

    /**
     * Kept for backward-compatible signature; delegates to [classifyPedagogicalContextLocally]
     * and returns `(stageKey, targetField)` ONLY when a high-confidence local contextual rule matches.
     */
    internal fun classifyLineWithBuiltInAi(
        rawLine: String,
        component: PedagogicalComponent,
        stageKeys: List<String>,
        currentStages: Map<String, StageData>
    ): Pair<String, String>? {
        val item = classifyPedagogicalContextLocally(
            rawLine = rawLine,
            component = component,
            stageKeys = stageKeys,
            currentStages = currentStages
        ) ?: return null
        return if (item.confidence >= LOCAL_RULE_HIGH_CONFIDENCE_THRESHOLD) {
            item.stageKey to item.targetField
        } else {
            null
        }
    }

    /**
     * Validates and parses a structured JSON response from Gemini for unit classification.
     *
     * Enforces:
     * 1. Valid JSON syntax (`{"items": [...]}` or `{"classifications": [...]}` or `[...]`).
     * 2. Every `unitId` must match an existing unit in [originalUnits] (rejects unknown/invented unit IDs).
     * 3. Duplicate `unitId` detection: rejects redundant or conflicting duplicate `unitId` entries so a unit is never
     *    assigned two conflicting classifications or inserted twice.
     * 4. Canonical verbatim text is preserved from [originalUnits] (rejects hallucinated text that does not match the unit).
     * 5. `stageKey` must be an official stage in [StageCatalog] for [targetComponent] (7 for Reading, 4 for Language, 16 for Expression)
     *    and compatible with `proposedCategory`.
     * 6. `targetField` must be in `{"abil", "teacher", "content", "eval"}` and compatible with `proposedCategory`.
     * 7. `proposedCategory` must be a valid [PedagogicalCategory] and `confidence` must be in `0.0..1.0`.
     * 8. Items with `confidence < confidenceThreshold` are placed in [GeminiClassificationValidationResult.lowConfidenceItems]
     *    for human review instead of being auto-applied.
     * 9. Units in [originalUnits] omitted by Gemini from the JSON response are recorded in [GeminiClassificationValidationResult.omittedUnitIds].
     */
    fun validateAndParseGeminiClassificationJson(
        rawJson: String,
        originalUnits: List<ExtractedUnit>,
        targetComponent: PedagogicalComponent,
        confidenceThreshold: Double = DEFAULT_CONFIDENCE_THRESHOLD
    ): GeminiClassificationValidationResult {
        val cleaned = rawJson.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        if (cleaned.isBlank()) {
            return GeminiClassificationValidationResult(
                isValid = false,
                rejectedReasons = listOf("استجابة Gemini فارغة."),
                omittedUnitIds = originalUnits.map { it.id },
                errorMessage = "استجابة Gemini فارغة."
            )
        }

        val itemsArray: JSONArray = try {
            if (cleaned.startsWith("[")) {
                JSONArray(cleaned)
            } else {
                val root = JSONObject(cleaned)
                root.optJSONArray("items")
                    ?: root.optJSONArray("classifications")
                    ?: return GeminiClassificationValidationResult(
                        isValid = false,
                        rejectedReasons = listOf("بنية JSON غير صالحة: الحقل 'items' أو 'classifications' مفقود."),
                        omittedUnitIds = originalUnits.map { it.id },
                        errorMessage = "بنية JSON غير صالحة: الحقل 'items' مفقود."
                    )
            }
        } catch (e: Exception) {
            return GeminiClassificationValidationResult(
                isValid = false,
                rejectedReasons = listOf("فشل تحليل JSON الوارد من Gemini: ${e.message}"),
                omittedUnitIds = originalUnits.map { it.id },
                errorMessage = "فشل تحليل JSON الوارد من Gemini: ${e.message}"
            )
        }

        val unitsById = originalUnits.associateBy { it.id }
        val officialStageKeys = StageCatalog.getStagesForComponent(targetComponent).map { it.key }.toSet()
        val validFields = setOf("abil", "teacher", "content", "eval")

        val accepted = mutableListOf<PedagogicalClassificationItem>()
        val lowConfidence = mutableListOf<PedagogicalClassificationItem>()
        val rejectedIds = mutableListOf<String>()
        val rejectedReasons = mutableListOf<String>()
        val seenItemsByUnitId = mutableMapOf<String, PedagogicalClassificationItem>()
        val conflictedUnitIds = mutableSetOf<String>()

        fun markRejected(id: String, reason: String) {
            if (id !in rejectedIds) {
                rejectedIds.add(id)
            }
            rejectedReasons.add(reason)
        }

        for (i in 0 until itemsArray.length()) {
            val obj = itemsArray.optJSONObject(i) ?: continue
            val unitId = obj.optString("unitId", "").trim()
            if (unitId.isEmpty() || unitId !in unitsById) {
                val badId = unitId.ifEmpty { "missing_id_$i" }
                markRejected(badId, "معرف وحدة غير معروف أو مخترع: $badId")
                continue
            }

            if (unitId in conflictedUnitIds) {
                markRejected(unitId, "تكرار متعارض لمعرف الوحدة ($unitId) في استجابة Gemini")
                continue
            }

            val sourceUnit = unitsById.getValue(unitId)
            val returnedText = obj.optString("originalText", "").trim()

            // Prevent hallucinated text: if originalText is provided, it must correspond to sourceUnit.verbatimText
            if (returnedText.isNotEmpty()) {
                val normSource = sourceUnit.verbatimText.removePrefix("+").removePrefix("-").removePrefix("•").trim()
                val normReturned = returnedText.removePrefix("+").removePrefix("-").removePrefix("•").trim()
                val textMatches = normSource == normReturned ||
                    normSource.contains(normReturned.take(15)) ||
                    normReturned.contains(normSource.take(15))
                if (!textMatches) {
                    accepted.removeAll { it.unitId == unitId }
                    lowConfidence.removeAll { it.unitId == unitId }
                    seenItemsByUnitId.remove(unitId)
                    markRejected(unitId, "نص محرف لا يطابق النص الأصلي للوحدة ($unitId)")
                    continue
                }
            }

            // Validate component if explicitly provided
            val rawComponent = obj.optString("component", "").trim()
            if (rawComponent.isNotEmpty()) {
                val resolvedComp = resolvePedagogicalComponent(rawComponent)
                if (resolvedComp != null && resolvedComp != targetComponent) {
                    accepted.removeAll { it.unitId == unitId }
                    lowConfidence.removeAll { it.unitId == unitId }
                    seenItemsByUnitId.remove(unitId)
                    markRejected(unitId, "مكون غير متوافق ($rawComponent) مع المكون المستهدف (${targetComponent.name}) للوحدة ($unitId)")
                    continue
                }
            }

            val categoryStr = obj.optString("proposedCategory", "").trim()
            val category = runCatching { PedagogicalCategory.valueOf(categoryStr.uppercase()) }.getOrNull()
                ?: PedagogicalCategory.fromString(categoryStr)
            if (category == null) {
                accepted.removeAll { it.unitId == unitId }
                lowConfidence.removeAll { it.unitId == unitId }
                seenItemsByUnitId.remove(unitId)
                markRejected(unitId, "فئة بيداغوجية غير صالحة ($categoryStr) للوحدة ($unitId)")
                continue
            }

            // Enforce official StageCatalog stage keys and pedagogical stage compatibility — reject any invented or incompatible stage
            val stageKey = obj.optString("stageKey", "").trim()
            if (stageKey.isEmpty() || stageKey !in officialStageKeys) {
                accepted.removeAll { it.unitId == unitId }
                lowConfidence.removeAll { it.unitId == unitId }
                seenItemsByUnitId.remove(unitId)
                markRejected(unitId, "مرحلة غير رسمية أو مخترعة ($stageKey) للوحدة ($unitId)")
                continue
            }
            if (!category.isCompatibleWithStage(stageKey, targetComponent)) {
                accepted.removeAll { it.unitId == unitId }
                lowConfidence.removeAll { it.unitId == unitId }
                seenItemsByUnitId.remove(unitId)
                markRejected(unitId, "مرحلة ($stageKey) غير متوافقة تربوياً مع الفئة (${category.name}) للوحدة ($unitId)")
                continue
            }

            // Enforce valid and category-compatible targetField
            val rawTargetField = obj.optString("targetField", "").trim().lowercase()
            if (rawTargetField.isNotEmpty() && rawTargetField !in validFields) {
                accepted.removeAll { it.unitId == unitId }
                lowConfidence.removeAll { it.unitId == unitId }
                seenItemsByUnitId.remove(unitId)
                markRejected(unitId, "حقل مستهدف غير صالح ($rawTargetField) للوحدة ($unitId)")
                continue
            }
            val targetField = if (rawTargetField.isNotEmpty()) rawTargetField else category.defaultTargetField
            if (!category.isCompatibleWithField(targetField, targetComponent)) {
                accepted.removeAll { it.unitId == unitId }
                lowConfidence.removeAll { it.unitId == unitId }
                seenItemsByUnitId.remove(unitId)
                markRejected(unitId, "حقل مستهدف ($targetField) غير متوافق مع الفئة (${category.name}) للوحدة ($unitId)")
                continue
            }

            val confidence = obj.optDouble("confidence", Double.NaN)
            if (confidence.isNaN() || confidence < 0.0 || confidence > 1.0) {
                accepted.removeAll { it.unitId == unitId }
                lowConfidence.removeAll { it.unitId == unitId }
                seenItemsByUnitId.remove(unitId)
                markRejected(unitId, "قيمة ثقة غير صالحة ($confidence) للوحدة ($unitId)")
                continue
            }

            val rationale = obj.optString("rationale", "").trim().ifEmpty {
                "تصنيف دلالي عبر Gemini (${category.arabicLabel})"
            }

            // Check duplicate unitId in the same response
            val existingForUnit = seenItemsByUnitId[unitId]
            if (existingForUnit != null) {
                val isConflicting = existingForUnit.proposedCategory != category ||
                    existingForUnit.stageKey != stageKey ||
                    existingForUnit.targetField != targetField
                if (isConflicting) {
                    accepted.removeAll { it.unitId == unitId }
                    lowConfidence.removeAll { it.unitId == unitId }
                    seenItemsByUnitId.remove(unitId)
                    conflictedUnitIds.add(unitId)
                    markRejected(unitId, "تكرار متعارض لمعرف الوحدة ($unitId) بتصنيفين مختلفين في استجابة Gemini")
                } else {
                    rejectedReasons.add("تكرار معرف الوحدة ($unitId) في استجابة Gemini")
                }
                continue
            }

            if (unitId in rejectedIds) {
                rejectedReasons.add("تكرار معرف الوحدة ($unitId) بعد رفض سابق في استجابة Gemini")
                continue
            }

            val item = PedagogicalClassificationItem(
                unitId = sourceUnit.id,
                originalText = sourceUnit.verbatimText,
                proposedCategory = category,
                targetField = targetField,
                component = targetComponent,
                stageKey = stageKey,
                confidence = confidence,
                source = ClassificationSource.GEMINI,
                rationale = rationale
            )
            seenItemsByUnitId[unitId] = item

            if (confidence >= confidenceThreshold) {
                accepted.add(item)
            } else {
                lowConfidence.add(item)
            }
        }

        val accountedValidOrRejectedIds = (
            accepted.map { it.unitId } +
                lowConfidence.map { it.unitId } +
                rejectedIds.filter { it in unitsById }
            ).toSet()
        val omittedIds = originalUnits.map { it.id }.filter { it !in accountedValidOrRejectedIds }

        val isValidResponse = accepted.isNotEmpty() || lowConfidence.isNotEmpty() || itemsArray.length() == 0
        return GeminiClassificationValidationResult(
            isValid = isValidResponse,
            acceptedItems = accepted,
            lowConfidenceItems = lowConfidence,
            rejectedUnitIds = rejectedIds,
            rejectedReasons = rejectedReasons,
            omittedUnitIds = omittedIds,
            errorMessage = if (!isValidResponse) "تم رفض عناصر استجابة Gemini لعدم تطابق المعرفات أو المراحل أو الحقول الرسمية." else null
        )
    }

    private fun buildGeminiUnitClassificationPrompt(
        units: List<ExtractedUnit>,
        component: PedagogicalComponent,
        topic: String
    ): String {
        val officialStages = StageCatalog.getStagesForComponent(component)
        val stagesListText = officialStages.joinToString("\n") { "- ${it.key}: ${it.title} (${it.description})" }
        val categoriesText = PedagogicalCategory.entries.joinToString("\n") {
            "- ${it.name} (${it.arabicLabel}) -> الحقل الافتراضي: ${it.defaultTargetField}"
        }
        val unitsJsonArray = JSONArray()
        for (u in units) {
            unitsJsonArray.put(
                JSONObject().apply {
                    put("unitId", u.id)
                    put("originalText", u.verbatimText)
                    put("lineNumber", u.location.lineNumber)
                }
            )
        }

        return """
أنت مفتش تربوي ومحلل بيداغوجي خبير في تدريس اللغة العربية بالتعليم الثانوي الإعدادي بالمغرب.
المهمة: تصنيف الوحدات النصية المستخرجة من جذاذة درس «$topic» في مكون «${component.title}» بالاعتماد على السياق البيداغوجي والوظيفة النحوية والديداكتيكية للجملة كاملة، وليس بناءً على كلمة مفردة معزولة.

الفئات البيداغوجية التسع المعتمدة (proposedCategory):
$categoriesText

المراحل الرسمية الحصرية لمكون «${component.title}» (stageKey) — يُمنع منعاً باتاً اختراع أي مرحلة خارج هذه القائمة:
$stagesListText

الحقول الأربعة المسموحة داخل كل مرحلة (targetField):
- "abil": القدرات والمهارات والأهداف التعلمية
- "teacher": أنشطة الأستاذ والأسئلة التوجيهية الديداكتيكية
- "content": المحتوى التعليمي، أنشطة المتعلمين، الأمثلة، الجداول التحليلية، الاستنتاجات
- "eval": مؤشرات التقويم والدعم والمعالجة

قواعد صارمة:
1. حافظ على معرف الوحدة (unitId) والنص الأصلي (originalText) كما هما حرفياً دون أي تعديل أو حذف أو اختراع.
2. لا تصنف جملة بناءً على كلمة مفردة إذا كان سياقها النحوي والتربوي يدل على وظيفة أخرى (مثلاً: "يطرح الأستاذ أسئلة لتقويم الفهم" هي نشاط للأستاذ TEACHER_ACTIVITIES في حقل teacher وليست تقويماً).
3. حدد مستوى الثقة (confidence) برقم عشري بين 0.0 و 1.0، واكتب تعليلاً مختصراً (rationale) يوضح سبب التصنيف.

الوحدات المطلوب تصنيفها:
$unitsJsonArray

أرجع النتيجة بصيغة JSON حصراً دون أي نص خارجي وفق الهيكل التالي:
{
  "items": [
    {
      "unitId": "unit_1",
      "originalText": "النص الأصلي كما ورد",
      "proposedCategory": "TEACHER_ACTIVITIES",
      "targetField": "teacher",
      "component": "${component.name}",
      "stageKey": "${officialStages.first().key}",
      "confidence": 0.92,
      "source": "GEMINI",
      "rationale": "جملة فعلية مسندة للأستاذ توجه المتعلمين"
    }
  ]
}
""".trimIndent()
    }

    /**
     * Calls Gemini to classify the given [units] and validates the response via [validateAndParseGeminiClassificationJson].
     */
    suspend fun classifyUnitsWithGemini(
        units: List<ExtractedUnit>,
        component: PedagogicalComponent,
        topic: String,
        customKey: String? = null,
        confidenceThreshold: Double = DEFAULT_CONFIDENCE_THRESHOLD
    ): Result<GeminiClassificationValidationResult> = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        if (units.isEmpty()) {
            return@withContext Result.success(GeminiClassificationValidationResult(isValid = true))
        }

        val apiKey = getEffectiveApiKey(customKey)
        if (!canReachGemini(customKey)) {
            return@withContext Result.failure(IllegalStateException(MSG_GEMINI_KEY_MISSING))
        }

        val prompt = buildGeminiUnitClassificationPrompt(units, component, topic)
        var lastError = "تعذر تصنيف العناصر عبر Gemini."

        for (model in API_MODELS) {
            coroutineContext.ensureActive()
            try {
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
                        put("temperature", 0.1)
                    })
                }

                val (code, responseBody) = executePostRequest(url, bodyJson.toString(), apiKey)
                if (code !in 200..299) {
                    val errMsg = runCatching {
                        JSONObject(responseBody).getJSONObject("error").getString("message")
                    }.getOrDefault("HTTP $code")
                    lastError = "خطأ من خادم Gemini ($code): $errMsg"
                    if (code == 401 || code == 403 || code == 429) {
                        return@withContext Result.failure(RuntimeException(lastError))
                    }
                    continue
                }

                val rawText = extractCandidateTextFromResponse(responseBody)
                if (rawText.isBlank()) {
                    lastError = "استجابة فارغة من خادم Gemini."
                    continue
                }

                val validation = validateAndParseGeminiClassificationJson(
                    rawJson = rawText,
                    originalUnits = units,
                    targetComponent = component,
                    confidenceThreshold = confidenceThreshold
                )
                if (!validation.isValid) {
                    return@withContext Result.failure(
                        IllegalArgumentException(validation.errorMessage ?: "استجابة Gemini غير صالحة.")
                    )
                }
                return@withContext Result.success(validation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                return@withContext Result.failure(
                    RuntimeException("انقطاع الاتصال بالشبكة أثناء التواصل مع Gemini.", e)
                )
            } catch (e: Exception) {
                lastError = e.message ?: "خطأ غير متوقع أثناء التصنيف عبر Gemini."
            }
        }

        Result.failure(RuntimeException(lastError))
    }

    private fun extractCandidateTextFromResponse(responseBody: String): String {
        // Support direct JSON test payloads in transport as well as standard Gemini generateContent envelope
        val trimmed = responseBody.trim()
        if (trimmed.startsWith("[") || (trimmed.startsWith("{") && !trimmed.contains("\"candidates\""))) {
            return trimmed
        }
        val respJson = JSONObject(responseBody)
        val candidates = respJson.optJSONArray("candidates") ?: return ""
        if (candidates.length() == 0) return ""
        val parts = candidates.getJSONObject(0)
            .optJSONObject("content")
            ?.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            sb.append(parts.getJSONObject(i).optString("text", ""))
        }
        return sb.toString().trim()
    }

    /**
     * Phase 2 Hybrid Classification & Refinement:
     * 1. Separates local contextual rule classification ([ClassificationSource.LOCAL_RULE] / [FieldSource.LOCAL_RULE])
     *    from genuine Gemini classification ([ClassificationSource.GEMINI] / [FieldSource.AI]).
     * 2. Never uses [FieldSource.AI] for an item that was not genuinely classified by Gemini.
     * 3. Never overwrites DEFAULT fields with fake AI template text.
     * 4. Preserves all unclassified or low-confidence items in [ParseReport.unclassifiedLines] for human review
     *    (never deletes any unit that could not be classified).
     * 5. Records [ParseReport.geminiFailed], [ParseReport.geminiErrorMessage], and [ParseReport.isLocalFallback]
     *    honestly when Gemini is unreachable or returns an invalid response.
     */
    suspend fun classifyAndRefineExtraction(
        rawText: String,
        initialResult: ParseResult,
        customKey: String? = null,
        preferLocalRulesFirst: Boolean = true,
        confidenceThreshold: Double = DEFAULT_CONFIDENCE_THRESHOLD
    ): ParseResult = withContext(Dispatchers.Default) {
        coroutineContext.ensureActive()
        val plan = initialResult.plan
        val report = initialResult.report
        val comp = plan.componentType
        val cleanedTopic = DocumentFileReader.cleanTopicFromFilename(plan.topic).ifBlank { plan.topic }
        val expectedStages = StageCatalog.getStagesForComponent(comp)
        val stageKeys = expectedStages.map { it.key }

        val mutableStages = plan.stages.toMutableMap()
        val mutableUnits = report.extractedUnits.toMutableList()
        val allClassificationItems = report.classificationItems.toMutableList()

        var localRuleCount = report.localRuleClassifiedLinesCount
        var geminiClassifiedCount = report.aiClassifiedLinesCount
        val remainingForGemini = mutableListOf<UnclassifiedLine>()
        val finalUnclassifiedLines = mutableListOf<UnclassifiedLine>()

        fun applyClassificationToStageAndUnit(
            uLine: UnclassifiedLine,
            item: PedagogicalClassificationItem,
            fieldSource: FieldSource,
            classSource: ClassificationSource
        ) {
            val targetStageKey = item.stageKey
            val targetField = item.targetField
            val verbatimText = uLine.verbatimText.ifBlank { uLine.text }.trim()

            // Prevent assigning the same unit to two conflicting stage/field locations
            val unitIdx = mutableUnits.indexOfFirst { u ->
                (uLine.unitId.isNotBlank() && u.id == uLine.unitId) ||
                    (u.location.lineNumber == uLine.lineNumber && u.verbatimText.trim() == verbatimText)
            }
            if (unitIdx >= 0) {
                val prevUnit = mutableUnits[unitIdx]
                val prevStageKey = prevUnit.assignedStageKey
                val prevField = prevUnit.assignedField
                if (prevStageKey != null && prevField != null &&
                    (prevStageKey != targetStageKey || prevField != targetField)
                ) {
                    val prevStage = mutableStages[prevStageKey]
                    if (prevStage != null) {
                        mutableStages[prevStageKey] = when (prevField) {
                            "abil" -> prevStage.copy(abil = prevStage.abil.filterNot { it == verbatimText })
                            "teacher" -> prevStage.copy(teacher = prevStage.teacher.filterNot { it == verbatimText })
                            "content" -> prevStage.copy(content = prevStage.content.filterNot { it == verbatimText })
                            "eval" -> prevStage.copy(eval = prevStage.eval.filterNot { it == verbatimText })
                            else -> prevStage
                        }
                    }
                }
            }

            val currentStage = mutableStages[targetStageKey] ?: StageData()
            val currentFieldSource = currentStage.sourceFor(targetField)

            val updatedList = when (targetField) {
                "abil" -> if (currentFieldSource == FieldSource.DEFAULT) listOf(verbatimText) else (currentStage.abil + verbatimText).distinct()
                "teacher" -> if (currentFieldSource == FieldSource.DEFAULT) listOf(verbatimText) else (currentStage.teacher + verbatimText).distinct()
                "content" -> if (currentFieldSource == FieldSource.DEFAULT) listOf(verbatimText) else (currentStage.content + verbatimText).distinct()
                "eval" -> if (currentFieldSource == FieldSource.DEFAULT) listOf(verbatimText) else (currentStage.eval + verbatimText).distinct()
                else -> emptyList()
            }

            val updatedStage = when (targetField) {
                "abil" -> currentStage.copy(abil = updatedList)
                "teacher" -> currentStage.copy(teacher = updatedList)
                "content" -> currentStage.copy(content = updatedList)
                "eval" -> currentStage.copy(eval = updatedList)
                else -> currentStage
            }.withFieldSource(targetField, fieldSource, classSource)

            mutableStages[targetStageKey] = updatedStage

            if (unitIdx >= 0) {
                val existing = mutableUnits[unitIdx]
                mutableUnits[unitIdx] = existing.copy(
                    status = ContentUnitStatus.CLASSIFIED,
                    assignedStageKey = targetStageKey,
                    assignedField = targetField,
                    finalClassifiedText = verbatimText,
                    classificationSource = classSource,
                    pedagogicalCategory = item.proposedCategory,
                    confidence = item.confidence,
                    rationale = item.rationale
                )
            }
            allClassificationItems.removeAll { it.unitId == item.unitId && item.unitId.isNotBlank() }
            allClassificationItems.add(item)
        }

        // Step 1: Apply context-aware local rules to clear cases (marked strictly as LOCAL_RULE, never AI!)
        for (uLine in report.unclassifiedLines) {
            val text = uLine.verbatimText.ifBlank { uLine.text }.trim()
            if (text.isBlank()) continue

            val localItem = if (preferLocalRulesFirst) {
                classifyPedagogicalContextLocally(
                    rawLine = text,
                    component = comp,
                    unitId = uLine.unitId,
                    stageKeys = stageKeys,
                    currentStages = mutableStages
                )
            } else {
                null
            }

            if (localItem != null && localItem.confidence >= LOCAL_RULE_HIGH_CONFIDENCE_THRESHOLD) {
                applyClassificationToStageAndUnit(
                    uLine = uLine,
                    item = localItem,
                    fieldSource = FieldSource.LOCAL_RULE,
                    classSource = ClassificationSource.LOCAL_RULE
                )
                localRuleCount++
            } else {
                remainingForGemini.add(uLine)
            }
        }

        // Step 2: Escalate remaining unclassified units to Gemini if needed
        var geminiSucceeded = false
        var geminiFailed = false
        var geminiErrorMsg: String? = null
        var isLocalFallback = false

        if (remainingForGemini.isNotEmpty()) {
            if (canReachGemini(customKey)) {
                val unitsToClassify = remainingForGemini.map { uLine ->
                    mutableUnits.firstOrNull { it.id == uLine.unitId } ?: ExtractedUnit(
                        id = uLine.unitId,
                        verbatimText = uLine.verbatimText.ifBlank { uLine.text },
                        location = uLine.location,
                        status = ContentUnitStatus.NEEDS_REVIEW
                    )
                }

                val geminiResult = classifyUnitsWithGemini(
                    units = unitsToClassify,
                    component = comp,
                    topic = cleanedTopic,
                    customKey = customKey,
                    confidenceThreshold = confidenceThreshold
                )

                geminiResult.fold(
                    onSuccess = { validation ->
                        geminiSucceeded = true
                        val acceptedById = validation.acceptedItems.associateBy { it.unitId }
                        val lowConfById = validation.lowConfidenceItems.associateBy { it.unitId }

                        for (uLine in remainingForGemini) {
                            val acceptedItem = acceptedById[uLine.unitId]
                            val lowConfItem = lowConfById[uLine.unitId]
                            when {
                                acceptedItem != null -> {
                                    applyClassificationToStageAndUnit(
                                        uLine = uLine,
                                        item = acceptedItem,
                                        fieldSource = FieldSource.AI,
                                        classSource = ClassificationSource.GEMINI
                                    )
                                    geminiClassifiedCount++
                                }
                                lowConfItem != null -> {
                                    // Low confidence -> DO NOT adopt a potentially wrong classification; keep for human review!
                                    finalUnclassifiedLines.add(
                                        uLine.copy(
                                            proposedCategory = lowConfItem.proposedCategory,
                                            confidence = lowConfItem.confidence,
                                            rationale = lowConfItem.rationale,
                                            classificationSource = ClassificationSource.GEMINI
                                        )
                                    )
                                    val unitIdx = mutableUnits.indexOfFirst { it.id == uLine.unitId }
                                    if (unitIdx >= 0) {
                                        mutableUnits[unitIdx] = mutableUnits[unitIdx].copy(
                                            status = ContentUnitStatus.NEEDS_REVIEW,
                                            pedagogicalCategory = lowConfItem.proposedCategory,
                                            confidence = lowConfItem.confidence,
                                            rationale = lowConfItem.rationale,
                                            classificationSource = ClassificationSource.GEMINI
                                        )
                                    }
                                    allClassificationItems.removeAll { it.unitId == lowConfItem.unitId && lowConfItem.unitId.isNotBlank() }
                                    allClassificationItems.add(lowConfItem)
                                }
                                else -> {
                                    // Rejected or omitted by Gemini -> NEVER delete the unit; keep in unclassifiedLines!
                                    finalUnclassifiedLines.add(uLine)
                                    val unitIdx = mutableUnits.indexOfFirst { it.id == uLine.unitId }
                                    if (unitIdx >= 0) {
                                        mutableUnits[unitIdx] = mutableUnits[unitIdx].copy(
                                            status = ContentUnitStatus.NEEDS_REVIEW
                                        )
                                    }
                                }
                            }
                        }
                    },
                    onFailure = { err ->
                        geminiFailed = true
                        isLocalFallback = true
                        geminiErrorMsg = err.message ?: MSG_LOCAL_FALLBACK_NOTICE
                        // Preserve all remaining unclassified lines without deleting any item
                        finalUnclassifiedLines.addAll(remainingForGemini)
                    }
                )
            } else {
                geminiFailed = true
                isLocalFallback = true
                geminiErrorMsg = MSG_GEMINI_KEY_MISSING
                finalUnclassifiedLines.addAll(remainingForGemini)
            }
        } else {
            geminiSucceeded = false
            if (!canReachGemini(customKey) || localRuleCount > 0) {
                isLocalFallback = true
            }
        }

        if (comp == PedagogicalComponent.LANGUAGE) {
            mutableStages["lang_observation"]?.let { mutableStages["lang_analysis"] = it }
        }

        val updatedPlan = plan.copy(
            topic = cleanedTopic,
            stages = mutableStages
        )

        val updatedReport = com.example.extractor.JadhadhaParser.buildReport(
            plan = updatedPlan,
            isComponentDefaulted = report.isComponentDefaulted,
            unclassifiedLines = finalUnclassifiedLines,
            aiClassifiedLinesCount = geminiClassifiedCount,
            aiRefinedFieldsCount = 0,
            localRuleClassifiedLinesCount = localRuleCount,
            isAiDelegated = geminiSucceeded && !geminiFailed,
            geminiFailed = geminiFailed,
            geminiErrorMessage = geminiErrorMsg,
            isLocalFallback = isLocalFallback,
            classificationItems = allClassificationItems,
            originalRawText = report.originalRawText.ifEmpty { rawText },
            extractedUnits = mutableUnits,
            unextractableItems = report.unextractableItems,
            recordedModifications = report.integrityReport?.modifiedUnits.orEmpty(),
            recordedDuplicateUnits = report.integrityReport?.duplicateUnits.orEmpty(),
            recordedDuplicateItems = report.integrityReport?.duplicateItems.orEmpty()
        )

        ParseResult(plan = updatedPlan, report = updatedReport)
    }

    /**
     * Explicit local-only classification helper (marked strictly as [ClassificationSource.LOCAL_RULE]).
     * Never claims that Gemini succeeded.
     */
    fun classifyWithLocalRulesOnly(
        rawText: String,
        initialResult: ParseResult
    ): ParseResult = classifyWithLocalRulesOnlyInternal(rawText, initialResult)

    fun classifyWithLocalRulesOnly(
        initialResult: ParseResult
    ): ParseResult = classifyWithLocalRulesOnlyInternal(initialResult.report.originalRawText, initialResult)

    private fun classifyWithLocalRulesOnlyInternal(
        rawText: String,
        initialResult: ParseResult
    ): ParseResult {
        val plan = initialResult.plan
        val report = initialResult.report
        val comp = plan.componentType
        val cleanedTopic = DocumentFileReader.cleanTopicFromFilename(plan.topic).ifBlank { plan.topic }
        val expectedStages = StageCatalog.getStagesForComponent(comp)
        val stageKeys = expectedStages.map { it.key }

        val mutableStages = plan.stages.toMutableMap()
        val mutableUnits = report.extractedUnits.toMutableList()
        val allClassificationItems = report.classificationItems.toMutableList()
        val remainingUnclassified = mutableListOf<UnclassifiedLine>()
        var localRuleCount = report.localRuleClassifiedLinesCount

        for (uLine in report.unclassifiedLines) {
            val text = uLine.verbatimText.ifBlank { uLine.text }.trim()
            if (text.isBlank()) continue

            val localItem = classifyPedagogicalContextLocally(
                rawLine = text,
                component = comp,
                unitId = uLine.unitId,
                stageKeys = stageKeys,
                currentStages = mutableStages
            )

            if (localItem != null && localItem.confidence >= LOCAL_RULE_HIGH_CONFIDENCE_THRESHOLD) {
                val targetStageKey = localItem.stageKey
                val targetField = localItem.targetField
                val currentStage = mutableStages[targetStageKey] ?: StageData()
                val currentFieldSource = currentStage.sourceFor(targetField)

                val updatedList = when (targetField) {
                    "abil" -> if (currentFieldSource == FieldSource.DEFAULT) listOf(text) else (currentStage.abil + text).distinct()
                    "teacher" -> if (currentFieldSource == FieldSource.DEFAULT) listOf(text) else (currentStage.teacher + text).distinct()
                    "content" -> if (currentFieldSource == FieldSource.DEFAULT) listOf(text) else (currentStage.content + text).distinct()
                    "eval" -> if (currentFieldSource == FieldSource.DEFAULT) listOf(text) else (currentStage.eval + text).distinct()
                    else -> emptyList()
                }

                mutableStages[targetStageKey] = when (targetField) {
                    "abil" -> currentStage.copy(abil = updatedList)
                    "teacher" -> currentStage.copy(teacher = updatedList)
                    "content" -> currentStage.copy(content = updatedList)
                    "eval" -> currentStage.copy(eval = updatedList)
                    else -> currentStage
                }.withFieldSource(targetField, FieldSource.LOCAL_RULE, ClassificationSource.LOCAL_RULE)

                val unitIdx = mutableUnits.indexOfFirst { u ->
                    (uLine.unitId.isNotBlank() && u.id == uLine.unitId) ||
                        (u.location.lineNumber == uLine.lineNumber && u.verbatimText.trim() == text)
                }
                if (unitIdx >= 0) {
                    val existing = mutableUnits[unitIdx]
                    mutableUnits[unitIdx] = existing.copy(
                        status = ContentUnitStatus.CLASSIFIED,
                        assignedStageKey = targetStageKey,
                        assignedField = targetField,
                        finalClassifiedText = text,
                        classificationSource = ClassificationSource.LOCAL_RULE,
                        pedagogicalCategory = localItem.proposedCategory,
                        confidence = localItem.confidence,
                        rationale = localItem.rationale
                    )
                }
                allClassificationItems.add(localItem)
                localRuleCount++
            } else {
                remainingUnclassified.add(uLine)
            }
        }

        if (comp == PedagogicalComponent.LANGUAGE) {
            mutableStages["lang_observation"]?.let { mutableStages["lang_analysis"] = it }
        }

        val updatedPlan = plan.copy(topic = cleanedTopic, stages = mutableStages)
        val updatedReport = com.example.extractor.JadhadhaParser.buildReport(
            plan = updatedPlan,
            isComponentDefaulted = report.isComponentDefaulted,
            unclassifiedLines = remainingUnclassified,
            aiClassifiedLinesCount = report.aiClassifiedLinesCount,
            aiRefinedFieldsCount = 0,
            localRuleClassifiedLinesCount = localRuleCount,
            isAiDelegated = false,
            geminiFailed = false,
            geminiErrorMessage = null,
            isLocalFallback = true,
            classificationItems = allClassificationItems,
            originalRawText = report.originalRawText.ifEmpty { rawText },
            extractedUnits = mutableUnits,
            unextractableItems = report.unextractableItems
        )
        return ParseResult(plan = updatedPlan, report = updatedReport)
    }

    /**
     * Comprehensive Smart AI Extraction (`تفريغ ذكي شامل (AI)`):
     * Analyzes [rawText] with Gemini and returns a structured [JadhadhaPlan] strictly adhering to the
     * official [StageCatalog] stages for [componentType].
     *
     * Phase 2 Guarantee:
     * - When Gemini cannot be reached (missing key, network failure, HTTP error, or invalid JSON),
     *   this function returns `Result.failure(...)` with a clear Arabic error message.
     * - It NEVER returns a local result disguised as a successful Gemini AI result.
     */
    suspend fun parseRawJadhadhaWithAi(
        rawText: String,
        componentType: String,
        customKey: String? = null
    ): Result<JadhadhaPlan> = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        if (rawText.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException(DocumentFileReader.MSG_FILE_EMPTY))
        }
        if (!canReachGemini(customKey)) {
            return@withContext Result.failure(IllegalStateException(MSG_GEMINI_KEY_MISSING))
        }

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
        val prompt = buildRawJadhadhaAiExtractionPrompt(rawText, activeComponent, initialTopic)
        var lastError = "تعذر إجراء التفريغ الذكي الشامل عبر Gemini."

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
            } catch (e: IOException) {
                return@withContext Result.failure(
                    RuntimeException("لا يوجد اتصال بالإنترنت أو تعذر الوصول إلى خادم Gemini.", e)
                )
            } catch (e: Exception) {
                lastError = e.message ?: "استجابة غير صالحة من خادم Gemini."
            }
        }

        Result.failure(RuntimeException(lastError))
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

    private suspend fun callModelForRawExtraction(
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

        val (code, responseBody) = executePostRequest(url, bodyJson.toString(), apiKey)
        if (code !in 200..299) {
            val errMsg = runCatching {
                JSONObject(responseBody).getJSONObject("error").getString("message")
            }.getOrDefault("HTTP $code")
            throw RuntimeException("خطأ في الخادم ($model): $errMsg")
        }

        val rawJsonText = extractCandidateTextFromResponse(responseBody)
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        if (rawJsonText.isBlank()) {
            throw RuntimeException("استجابة فارغة من النموذج ($model)")
        }

        val json = JSONObject(rawJsonText)
        val stagesObj = json.optJSONObject("stages")
            ?: throw IllegalArgumentException("استجابة Gemini لا تحتوي على كائن المراحل 'stages'.")

        val parsed = parsePlanFromJson(json, structuralBaseline.topic, rawText, component)

        // Merge any learner array items into content and preserve FILE items from structuralBaseline
        val mergedStages = parsed.stages.mapValues { (stageKey, aiStage) ->
            val stObj = stagesObj.optJSONObject(stageKey) ?: JSONObject()
            val learnerItems = jsonArrayToList(stObj.optJSONArray("learner"))
            val baselineStage = structuralBaseline.stages[stageKey]

            val hasAiContent = jsonArrayToList(stObj.optJSONArray("content")).isNotEmpty() || learnerItems.isNotEmpty()
            val hasAiTeacher = jsonArrayToList(stObj.optJSONArray("teacher")).isNotEmpty()
            val hasAiAbil = jsonArrayToList(stObj.optJSONArray("abil")).isNotEmpty()
            val hasAiEval = jsonArrayToList(stObj.optJSONArray("eval")).isNotEmpty()

            val combinedContent = buildList {
                if (hasAiContent) {
                    addAll(aiStage.content)
                    addAll(learnerItems)
                }
                if (baselineStage?.sourceFor("content") == FieldSource.FILE) {
                    addAll(baselineStage.content)
                }
                if (isEmpty()) addAll(aiStage.content)
            }.distinct()

            val combinedTeacher = buildList {
                if (hasAiTeacher) addAll(aiStage.teacher)
                if (baselineStage?.sourceFor("teacher") == FieldSource.FILE) {
                    addAll(baselineStage.teacher)
                }
                if (isEmpty()) addAll(aiStage.teacher)
            }.distinct()

            val combinedAbil = buildList {
                if (hasAiAbil) addAll(aiStage.abil)
                if (baselineStage?.sourceFor("abil") == FieldSource.FILE) {
                    addAll(baselineStage.abil)
                }
                if (isEmpty()) addAll(aiStage.abil)
            }.distinct()

            val combinedEval = buildList {
                if (hasAiEval) addAll(aiStage.eval)
                if (baselineStage?.sourceFor("eval") == FieldSource.FILE) {
                    addAll(baselineStage.eval)
                }
                if (isEmpty()) addAll(aiStage.eval)
            }.distinct()

            val contentSrc = when {
                baselineStage?.sourceFor("content") == FieldSource.FILE -> FieldSource.FILE
                hasAiContent -> FieldSource.AI
                else -> FieldSource.DEFAULT
            }
            val teacherSrc = when {
                baselineStage?.sourceFor("teacher") == FieldSource.FILE -> FieldSource.FILE
                hasAiTeacher -> FieldSource.AI
                else -> FieldSource.DEFAULT
            }
            val abilSrc = when {
                baselineStage?.sourceFor("abil") == FieldSource.FILE -> FieldSource.FILE
                hasAiAbil -> FieldSource.AI
                else -> FieldSource.DEFAULT
            }
            val evalSrc = when {
                baselineStage?.sourceFor("eval") == FieldSource.FILE -> FieldSource.FILE
                hasAiEval -> FieldSource.AI
                else -> FieldSource.DEFAULT
            }

            aiStage.copy(
                content = combinedContent,
                teacher = combinedTeacher,
                abil = combinedAbil,
                eval = combinedEval,
                sources = mapOf(
                    "content" to contentSrc,
                    "teacher" to teacherSrc,
                    "abil" to abilSrc,
                    "eval" to evalSrc
                ),
                classificationSources = mapOf(
                    "content" to if (contentSrc == FieldSource.AI) ClassificationSource.GEMINI else if (contentSrc == FieldSource.FILE) ClassificationSource.IMPORTED else ClassificationSource.DEFAULT,
                    "teacher" to if (teacherSrc == FieldSource.AI) ClassificationSource.GEMINI else if (teacherSrc == FieldSource.FILE) ClassificationSource.IMPORTED else ClassificationSource.DEFAULT,
                    "abil" to if (abilSrc == FieldSource.AI) ClassificationSource.GEMINI else if (abilSrc == FieldSource.FILE) ClassificationSource.IMPORTED else ClassificationSource.DEFAULT,
                    "eval" to if (evalSrc == FieldSource.AI) ClassificationSource.GEMINI else if (evalSrc == FieldSource.FILE) ClassificationSource.IMPORTED else ClassificationSource.DEFAULT
                )
            )
        }

        return parsed.copy(stages = mergedStages)
    }

    /**
     * Generates a complete [JadhadhaPlan] using Gemini.
     *
     * Phase 2 Guarantee:
     * - Requires a valid Gemini API key (or configured test transport).
     * - Returns `Result.failure(...)` when Gemini cannot be reached or returns an invalid response.
     * - Never returns a local `synthesizeSmartPlan` result disguised as a Gemini AI success.
     */
    suspend fun generateJadhadha(
        topic: String,
        lessonText: String = "",
        component: PedagogicalComponent = PedagogicalComponent.READING,
        customKey: String? = null
    ): Result<JadhadhaPlan> = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val cleanTopic = topic.trim().ifBlank { "درس تطبيقي" }
        if (!canReachGemini(customKey)) {
            return@withContext Result.failure(IllegalStateException(MSG_GEMINI_KEY_MISSING))
        }

        val apiKey = getEffectiveApiKey(customKey)
        val prompt = buildPrompt(cleanTopic, lessonText, component)
        var lastError = "تعذر توليد الجذاذة عبر Gemini."

        for (model in API_MODELS) {
            coroutineContext.ensureActive()
            try {
                val remotePlan = callModel(model, apiKey, prompt, cleanTopic, lessonText, component)
                return@withContext Result.success(remotePlan)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                return@withContext Result.failure(
                    RuntimeException("لا يوجد اتصال بالإنترنت أو تعذر الوصول إلى خادم Gemini.", e)
                )
            } catch (e: Exception) {
                lastError = e.message ?: "فشل توليد الجذاذة من استجابة Gemini."
            }
        }

        Result.failure(RuntimeException(lastError))
    }

    suspend fun suggestObjectivesForTopic(
        topic: String,
        component: PedagogicalComponent = PedagogicalComponent.READING,
        level: String = "السنة الثالثة إعدادي",
        customKey: String? = null
    ): Result<SuggestedObjectives> = withContext(Dispatchers.IO) {
        val cleanTopic = topic.trim().ifBlank { "درس تطبيقي" }
        val apiKey = getEffectiveApiKey(customKey)

        if (canReachGemini(customKey)) {
            val prompt = buildObjectivesPrompt(cleanTopic, component, level)
            for (model in API_MODELS) {
                try {
                    val suggestions = callModelForObjectives(model, apiKey, prompt, cleanTopic, component, level)
                    return@withContext Result.success(suggestions)
                } catch (_: Exception) {
                    // Continue to next candidate or local fallback tagged as DEFAULT/LOCAL_RULE
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

    private suspend fun callModel(
        model: String,
        apiKey: String,
        prompt: String,
        originalTopic: String,
        lessonText: String,
        component: PedagogicalComponent
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
                put("temperature", 0.4)
            })
        }

        val (code, responseBody) = executePostRequest(url, bodyJson.toString(), apiKey)

        if (code !in 200..299) {
            val errMsg = runCatching {
                JSONObject(responseBody).getJSONObject("error").getString("message")
            }.getOrDefault("HTTP $code")
            throw RuntimeException("خطأ في الخادم ($PRIMARY_MODEL): $errMsg")
        }

        val cleanedJson = extractCandidateTextFromResponse(responseBody)
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        if (cleanedJson.isBlank()) {
            throw RuntimeException("استجابة فارغة من النموذج ($PRIMARY_MODEL)")
        }

        val json = JSONObject(cleanedJson)
        if (!json.has("stages") || json.optJSONObject("stages") == null) {
            throw IllegalArgumentException("استجابة Gemini غير صالحة: حقل 'stages' مفقود.")
        }
        return parsePlanFromJson(json, originalTopic, lessonText, component)
    }

    private suspend fun callModelForObjectives(
        model: String,
        apiKey: String,
        prompt: String,
        originalTopic: String,
        component: PedagogicalComponent,
        level: String
    ): SuggestedObjectives {
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
                put("temperature", 0.3)
            })
        }

        val (code, responseBody) = executePostRequest(url, bodyJson.toString(), apiKey)

        if (code !in 200..299) {
            val errMsg = runCatching {
                JSONObject(responseBody).getJSONObject("error").getString("message")
            }.getOrDefault("HTTP $code")
            throw RuntimeException("خطأ في الخادم ($PRIMARY_MODEL): $errMsg")
        }

        val cleanedJson = extractCandidateTextFromResponse(responseBody)
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        if (cleanedJson.isBlank()) {
            throw RuntimeException("استجابة فارغة من النموذج ($PRIMARY_MODEL)")
        }

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

    /**
     * Parses a Gemini JSON object into a [JadhadhaPlan] while strictly preserving the official
     * [StageCatalog] structure for [fallbackComponent] (7 stages for Reading, 4 for Language,
     * 16 sub-stages for Expression). Any non-official stage key returned by the model is ignored.
     */
    private fun parsePlanFromJson(
        json: JSONObject,
        originalTopic: String,
        lessonText: String,
        fallbackComponent: PedagogicalComponent
    ): JadhadhaPlan {
        val topic = json.optString("topic", originalTopic).ifBlank { originalTopic }
        // Preserve target pedagogical component structure
        val component = fallbackComponent

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

        // Strictly iterate ONLY over the official stages for this component
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
            val stageClassSources = mapOf(
                "eval" to if (evalList.isNotEmpty()) ClassificationSource.GEMINI else ClassificationSource.DEFAULT,
                "content" to if (contentList.isNotEmpty()) ClassificationSource.GEMINI else ClassificationSource.DEFAULT,
                "teacher" to if (teacherList.isNotEmpty()) ClassificationSource.GEMINI else ClassificationSource.DEFAULT,
                "abil" to if (abilList.isNotEmpty()) ClassificationSource.GEMINI else ClassificationSource.DEFAULT
            )

            stagesMap[k] = StageData(
                eval = if (evalList.isNotEmpty()) evalList else fallbackStage.eval,
                content = if (contentList.isNotEmpty()) contentList else fallbackStage.content,
                teacher = if (teacherList.isNotEmpty()) teacherList else fallbackStage.teacher,
                abil = if (abilList.isNotEmpty()) abilList else fallbackStage.abil,
                sources = stageSources,
                classificationSources = stageClassSources
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
     * Synthesizes a local template-based pedagogical JadhadhaPlan (tagged as [FieldSource.DEFAULT] /
     * [ClassificationSource.LOCAL_RULE], NEVER as [FieldSource.AI]).
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
