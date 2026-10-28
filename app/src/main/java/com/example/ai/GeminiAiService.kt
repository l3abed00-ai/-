package com.example.ai

import com.example.BuildConfig
import com.example.model.JadhadhaInfo
import com.example.model.JadhadhaPlan
import com.example.model.PedagogicalComponent
import com.example.model.StageCatalog
import com.example.model.StageData
import com.example.model.SuggestedObjectives
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiAiService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private const val API_BASE = "https://generativelanguage.googleapis.com/v1beta"
    // gemini-3.8-flash is the primary upgraded model
    private val MODELS = listOf("gemini-3.8-flash", "gemini-2.5-flash", "gemini-1.5-flash")

    fun getEffectiveApiKey(customKey: String?): String {
        val trimmedCustom = customKey?.trim().orEmpty()
        if (trimmedCustom.isNotBlank() && !trimmedCustom.equals("MY_GEMINI_API_KEY", ignoreCase = true)) {
            return trimmedCustom
        }
        val buildConfigKey = runCatching { BuildConfig.GEMINI_API_KEY }.getOrDefault("")
        if (buildConfigKey.isNotBlank() && !buildConfigKey.equals("MY_GEMINI_API_KEY", ignoreCase = true)) {
            return buildConfigKey
        }
        return ""
    }

    suspend fun generateJadhadha(
        topic: String,
        lessonText: String = "",
        component: PedagogicalComponent = PedagogicalComponent.READING,
        customKey: String? = null
    ): Result<JadhadhaPlan> = withContext(Dispatchers.IO) {
        val apiKey = getEffectiveApiKey(customKey)
        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("لم يتم توفير مفتاح Gemini API. يمكنك إدخال المفتاح في الإعدادات أو استخدام الاستخراج والتفريغ الذاتي.")
            )
        }

        val prompt = buildPrompt(topic, lessonText, component)
        var lastException: Exception? = null

        for (model in MODELS) {
            try {
                val plan = callModel(model, apiKey, prompt, topic, component)
                return@withContext Result.success(plan)
            } catch (e: Exception) {
                lastException = e
            }
        }

        Result.failure(lastException ?: RuntimeException("فشل استدعاء نموذج الذكاء الاصطناعي (Gemini 3.8 Flash)."))
    }

    suspend fun suggestObjectivesForTopic(
        topic: String,
        component: PedagogicalComponent = PedagogicalComponent.READING,
        level: String = "الثالثة ثانوي إعدادي",
        customKey: String? = null
    ): Result<SuggestedObjectives> = withContext(Dispatchers.IO) {
        val apiKey = getEffectiveApiKey(customKey)
        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("لم يتم توفير مفتاح Gemini API. يرجى إدخال مفتاح Gemini في إعدادات التطبيق لاقتراح الأهداف بالذكاء الاصطناعي.")
            )
        }

        val prompt = buildObjectivesPrompt(topic, component, level)
        var lastException: Exception? = null

        for (model in MODELS) {
            try {
                val suggestions = callModelForObjectives(model, apiKey, prompt, topic, component)
                return@withContext Result.success(suggestions)
            } catch (e: Exception) {
                lastException = e
            }
        }

        Result.failure(lastException ?: RuntimeException("فشل استدعاء نموذج الذكاء الاصطناعي (Gemini 3.8 Flash) لاقتراح الأهداف."))
    }

    private fun buildObjectivesPrompt(topic: String, component: PedagogicalComponent, level: String): String {
        val stages = StageCatalog.getStagesForComponent(component).map { it.key to it.title }
        val stagesTemplate = stages.joinToString(",\n") { (k, t) -> "    \"$k\": [\"القدرة على... ($t)\"]" }
        return """
أنت مفتش تربوي وموجه بيداغوجي خبير في تدريس مادة اللغة العربية بالتعليم الثانوي الإعدادي والتأهيلي بالمغرب.
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
            """أنت مفتش تربوي خبير وموجه بيداغوجي في مادة اللغة العربية بالتعليم الثانوي الإعدادي بالمغرب.
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
            throw RuntimeException("خطأ في الخادم ($model): $errMsg")
        }

        val respJson = JSONObject(responseBody)
        val candidates = respJson.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            throw RuntimeException("استجابة فارغة من النموذج")
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
        return parsePlanFromJson(json, originalTopic, component)
    }

    private fun callModelForObjectives(
        model: String,
        apiKey: String,
        prompt: String,
        originalTopic: String,
        component: PedagogicalComponent
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
            throw RuntimeException("خطأ في الخادم ($model): $errMsg")
        }

        val respJson = JSONObject(responseBody)
        val candidates = respJson.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            throw RuntimeException("استجابة فارغة من النموذج ($model)")
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
        val comm = json.optString("communicative", "أن يوظف المتعلم معارف درس «$originalTopic» في سياق تواصلي سليم.")
        val meth = json.optString("methodological", "أن يكتسب المتعلم مهارات الملاحظة والتحليل والتركيب المرتبطة بـ «$originalTopic».")
        val cult = json.optString("cultural", "أن يستوعب المتعلم القيم المعرفية والوجدانية لدرس «$originalTopic».")

        val cogList = jsonArrayToList(json.optJSONArray("cognitive_objectives"))
        val skillList = jsonArrayToList(json.optJSONArray("skill_objectives"))
        val affList = jsonArrayToList(json.optJSONArray("affective_objectives"))

        val stagesObj = json.optJSONObject("stage_abilities")
        val stageAbilitiesMap = mutableMapOf<String, List<String>>()
        if (stagesObj != null) {
            val keys = stagesObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val list = jsonArrayToList(stagesObj.optJSONArray(k))
                if (list.isNotEmpty()) {
                    stageAbilitiesMap[k] = list
                }
            }
        }

        return SuggestedObjectives(
            topic = originalTopic,
            component = component,
            communicativeComp = comm,
            methodologicalComp = meth,
            culturalComp = cult,
            cognitiveObjectives = if (cogList.isNotEmpty()) cogList else listOf(
                "أن يتعرف المتعلم على مفهوم ومحددات درس «$originalTopic».",
                "أن يكتشف القواعد والخصائص البنيوية المرتبطة بالموضوع."
            ),
            skillObjectives = if (skillList.isNotEmpty()) skillList else listOf(
                "أن يحلل المتعلم الأمثلة والشواهد تحليلاً منهجياً دقيقاً.",
                "أن يطبق المكتسبات في سياقات لغوية وتعبيرية سليمة."
            ),
            affectiveObjectives = if (affList.isNotEmpty()) affList else listOf(
                "أن يقدر المتعلم دقة اللغة العربية وجماليتها وقيم التعبير الهادف."
            ),
            stageAbilities = stageAbilitiesMap
        )
    }


    private fun parsePlanFromJson(
        json: JSONObject,
        originalTopic: String,
        fallbackComponent: PedagogicalComponent
    ): JadhadhaPlan {
        val topic = json.optString("topic", originalTopic).ifBlank { originalTopic }
        val compStr = json.optString("component", fallbackComponent.id)
        val component = when {
            compStr == "language" || compStr.contains("لغوي") -> PedagogicalComponent.LANGUAGE
            compStr == "expression" || compStr.contains("تعبير") -> PedagogicalComponent.EXPRESSION
            else -> fallbackComponent
        }

        val infoObj = json.optJSONObject("info") ?: JSONObject()
        val info = JadhadhaInfo(
            level = infoObj.optString("level", "الثالثة ثانوي إعدادي"),
            unit = infoObj.optString("unit", "الوحدة الأولى"),
            domain = infoObj.optString("domain", "مجال القيم الإسلامية والوطنية"),
            component = infoObj.optString("component", component.title),
            duration = infoObj.optString("duration", component.defaultDuration),
            materials = infoObj.optString("materials", component.defaultMaterials),
            communicativeComp = infoObj.optString("communicative", "أن يوظف المتعلم المعارف في سياق تواصلي سليم."),
            methodologicalComp = infoObj.optString("methodological", "توظيف الخطوات المنهجية المنظمة."),
            culturalComp = infoObj.optString("cultural", "استخلاص القيم التربوية والمعرفية المستهدفة.")
        )

        val stagesObj = json.optJSONObject("stages") ?: JSONObject()
        val stagesMap = mutableMapOf<String, StageData>()

        val expectedStages = StageCatalog.getStagesForComponent(component).map { it.key }
        for (k in expectedStages) {
            val stObj = stagesObj.optJSONObject(k) ?: JSONObject()
            stagesMap[k] = StageData(
                eval = jsonArrayToList(stObj.optJSONArray("eval")),
                content = jsonArrayToList(stObj.optJSONArray("content")),
                teacher = jsonArrayToList(stObj.optJSONArray("teacher")),
                abil = jsonArrayToList(stObj.optJSONArray("abil"))
            )
        }

        return JadhadhaPlan(
            topic = topic,
            componentType = component,
            info = info,
            stages = stagesMap
        )
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
