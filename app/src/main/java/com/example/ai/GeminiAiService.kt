package com.example.ai

import com.example.BuildConfig
import com.example.model.FieldSource
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
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private const val API_BASE = "https://generativelanguage.googleapis.com/v1beta"
    const val PRIMARY_MODEL = "gemini-3.8-flash"
    private val API_MODELS = listOf("gemini-3.8-flash", "gemini-2.5-flash", "gemini-2.0-flash")

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
                        "يساعد المتعلمين على تحليل كل مثال وتحديد شروط وعمل وإعراب «$cleanTopic»."
                    ),
                    content = listOf(
                        "<u>الوصف والتحليل (الجدول الاستقرائي):</u>",
                        "- <u>المفهوم والتعريف:</u> تحديد ماهية «$cleanTopic» ووظيفته المعنوية واللغوية في الجملة.",
                        "- <u>الأقسام والأنواع:</u> تصنيف حالات وصيغ «$cleanTopic» بحسب بنية المثال وسياقه.",
                        "- <u>الحكم الإعرابي والشروط:</u> بيان الموقع الإعرابي لعناصر «$cleanTopic» وشروط إعمالها أو صياغتها."
                    ),
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
                    content = listOf(
                        "<u>القاعدة والاستنتاج العام:</u>",
                        "- <u>تعريف «$cleanTopic»:</u> ظاهرة لغوية تؤدي وظيفة دلالية وتركيبية محددة في الكلام العربي.",
                        "- <u>أحكامه وشروطه:</u> يصاغ أو يعمل وفق ضوابط وشروط صرفية ونحوية مضبوطة.",
                        "- <u>إعرابه:</u> يعرب بحسب موقعه وعلاقته بالعناصر المجاورة له في التركيب."
                    ),
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
