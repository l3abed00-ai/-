package com.example.extractor

import com.example.model.JadhadhaInfo
import com.example.model.JadhadhaPlan
import com.example.model.PedagogicalComponent
import com.example.model.StageCatalog
import com.example.model.StageData

object JadhadhaParser {

    /**
     * Parses raw pasted text or uploaded document content and extracts structured data into
     * the Moroccan pedagogical framework for Reading, Language, or Expression components.
     */
    fun parseRawText(
        raw: String,
        fallbackTopic: String = "درس جديد",
        forcedComponent: PedagogicalComponent? = null
    ): JadhadhaPlan {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        var topic = fallbackTopic

        // Detect component type
        val detectedComponent = forcedComponent ?: detectComponent(raw, lines)

        var level = "الثالثة ثانوي إعدادي"
        var unit = "الوحدة الأولى"
        var domain = when (detectedComponent) {
            PedagogicalComponent.LANGUAGE -> "الظواهر اللغوية والأسلوبية"
            PedagogicalComponent.EXPRESSION -> "مهارات التعبير والإنشاء"
            PedagogicalComponent.READING -> "مجال القيم الإسلامية والوطنية"
        }
        var component = detectedComponent.title
        var duration = detectedComponent.defaultDuration
        var materials = detectedComponent.defaultMaterials
        var communicative = when (detectedComponent) {
            PedagogicalComponent.LANGUAGE -> "أن يوظف المتعلم الظاهرة اللغوية في سياقات تواصلية سليمة."
            PedagogicalComponent.EXPRESSION -> "أن يتمكن المتعلم من التعبير والإنتاج الكتابي وفق خطوات المهارة."
            PedagogicalComponent.READING -> "قراءة النص قراءة سليمة ومعبرة واستيعاب أبعاده الدلالية."
        }
        var methodological = when (detectedComponent) {
            PedagogicalComponent.LANGUAGE -> "أن يحلل المتعلم الظاهرة اللغوية ويستنبط أحكامها الصرفية والتركيبية."
            PedagogicalComponent.EXPRESSION -> "أن يكتسب المتعلم الخطوات المنهجية لإنتاج نصوص منسجمة."
            PedagogicalComponent.READING -> "توظيف آليات القراءة المنهجية (الملاحظة، الفهم، التحليل، والتركيب)."
        }
        var cultural = when (detectedComponent) {
            PedagogicalComponent.LANGUAGE -> "إدراك فصاحة اللسان العربي ودقة بنائه الدلالي."
            PedagogicalComponent.EXPRESSION -> "الانفتاح على قضايا المجتمع والتعبير عنها بتفكير نقدي."
            PedagogicalComponent.READING -> "استخلاص القيم التربوية والإنسانية المتضمنة في النص."
        }

        // Available stages for this component
        val stageMetaList = StageCatalog.getStagesForComponent(detectedComponent)
        val stageMap = mutableMapOf<String, MutableMap<String, MutableList<String>>>()
        stageMetaList.forEach { sm ->
            stageMap[sm.key] = mutableMapOf(
                "eval" to mutableListOf(),
                "content" to mutableListOf(),
                "teacher" to mutableListOf(),
                "abil" to mutableListOf()
            )
        }

        var currentStageKey = stageMetaList.first().key
        var currentField = "content"

        for (line in lines) {
            // Header extraction
            if (line.contains("موضوع") || line.contains("عنوان الدرس") || line.contains("النص القرائي") || line.contains("المهارة")) {
                val extracted = extractAfterColon(line)
                if (extracted.isNotBlank() && extracted.length in 2..60) {
                    topic = extracted.removeSurrounding("«", "»").removeSurrounding("\"", "\"").trim()
                    continue
                }
            }
            if (line.contains("المستوى") || line.contains("السلك") || line.contains("الفئة المستهدفة")) {
                val extracted = extractAfterColon(line)
                if (extracted.isNotBlank()) {
                    level = extracted
                    continue
                }
            }
            if (line.contains("الوحدة")) {
                val extracted = extractAfterColon(line)
                if (extracted.isNotBlank()) {
                    unit = extracted
                    continue
                }
            }
            if (line.contains("المجال")) {
                val extracted = extractAfterColon(line)
                if (extracted.isNotBlank()) {
                    domain = extracted
                    continue
                }
            }
            if (line.contains("المكون") || line.contains("المكوّن")) {
                val extracted = extractAfterColon(line)
                if (extracted.isNotBlank()) {
                    component = extracted
                    continue
                }
            }
            if (line.contains("المدة") || line.contains("الغلاف الزمني")) {
                val extracted = extractAfterColon(line)
                if (extracted.isNotBlank()) {
                    duration = extracted
                    continue
                }
            }
            if (line.contains("الوسائل") || line.contains("الديداكتيكية") || line.contains("المرجع")) {
                val extracted = extractAfterColon(line)
                if (extracted.isNotBlank()) {
                    materials = extracted
                    continue
                }
            }
            if (line.contains("كفاية تواصلية") || (line.contains("تواصلية") && line.contains("كفاية"))) {
                val extracted = extractAfterColon(line)
                if (extracted.isNotBlank()) communicative = extracted
                continue
            }
            if (line.contains("كفاية منهجية") || (line.contains("منهجية") && line.contains("كفاية"))) {
                val extracted = extractAfterColon(line)
                if (extracted.isNotBlank()) methodological = extracted
                continue
            }
            if (line.contains("كفاية ثقافية") || line.contains("كفاية معرفية") || (line.contains("ثقافية") && line.contains("كفاية"))) {
                val extracted = extractAfterColon(line)
                if (extracted.isNotBlank()) cultural = extracted
                continue
            }

            // Stage Detection
            val detectedStage = detectStage(line, detectedComponent)
            if (detectedStage != null && stageMap.containsKey(detectedStage)) {
                currentStageKey = detectedStage
                currentField = "content"
                continue
            }

            // Field Detection within Stage
            val detectedField = detectField(line)
            if (detectedField != null) {
                currentField = detectedField
                val rest = extractAfterColon(line)
                if (rest.isNotBlank()) {
                    stageMap[currentStageKey]?.get(currentField)?.add(cleanBullet(rest))
                }
                continue
            }

            // Add content item to currentStage and currentField
            val clean = cleanBullet(line)
            if (clean.isNotBlank()) {
                val inferredField = when {
                    clean.startsWith("القدرة على") || clean.startsWith("أن يكون المتعلم") -> "abil"
                    clean.startsWith("يطرح") || clean.startsWith("يوجه") || clean.startsWith("يطالب") ||
                            clean.startsWith("يحفز") || clean.startsWith("يشرف") || clean.startsWith("يدعو") -> "teacher"
                    clean.startsWith("تقويم") || clean.startsWith("فحص") || clean.startsWith("مدى") -> "eval"
                    else -> currentField
                }
                stageMap[currentStageKey]?.get(inferredField)?.add(clean)
            }
        }

        // Fill empty defaults for stages to ensure pedagogical completeness
        ensureDefaults(stageMap, detectedComponent, topic)

        val builtStages = stageMap.mapValues { (_, fieldMap) ->
            StageData(
                eval = fieldMap["eval"]?.distinct() ?: emptyList(),
                content = fieldMap["content"]?.distinct() ?: emptyList(),
                teacher = fieldMap["teacher"]?.distinct() ?: emptyList(),
                abil = fieldMap["abil"]?.distinct() ?: emptyList()
            )
        }

        return JadhadhaPlan(
            topic = topic,
            componentType = detectedComponent,
            info = JadhadhaInfo(
                level = level,
                unit = unit,
                domain = domain,
                component = component,
                duration = duration,
                materials = materials,
                communicativeComp = communicative,
                methodologicalComp = methodological,
                culturalComp = cultural
            ),
            stages = builtStages
        )
    }

    private fun detectComponent(raw: String, lines: List<String>): PedagogicalComponent {
        val sample = (lines.take(20).joinToString(" ") + " " + raw.take(500)).lowercase()
        return when {
            sample.contains("درس لغوي") || sample.contains("الدرس اللغوي") || sample.contains("ظاهرة لغوية") ||
                    sample.contains("اسم الفاعل") || sample.contains("صيغ المبالغة") || sample.contains("أسلوب الاختصاص") ||
                    sample.contains("أمثلة الانطلاق") || sample.contains("القاعدة التركيبية") -> PedagogicalComponent.LANGUAGE

            sample.contains("تعبير وإنشاء") || sample.contains("التعبير والإنشاء") || sample.contains("أنشطة الاكتساب") ||
                    sample.contains("أنشطة التطبيق") || sample.contains("أنشطة الإنتاج") || sample.contains("أنشطة التصحيح") ||
                    sample.contains("مهارة") || sample.contains("كتابة حكاية") || sample.contains("كتابة يوميات") -> PedagogicalComponent.EXPRESSION

            else -> PedagogicalComponent.READING
        }
    }

    private fun extractAfterColon(line: String): String {
        val idx = line.indexOf(':')
        return if (idx >= 0 && idx < line.length - 1) {
            line.substring(idx + 1).trim()
        } else {
            val hyphenIdx = line.indexOf('-')
            if (hyphenIdx in 1..15) line.substring(hyphenIdx + 1).trim() else ""
        }
    }

    private fun cleanBullet(line: String): String {
        return line.trim()
            .removePrefix("-")
            .removePrefix("+")
            .removePrefix("•")
            .removePrefix("*")
            .replace(Regex("^\\d+[\\.\\-\\)]\\s*"), "")
            .trim()
    }

    private fun detectStage(line: String, component: PedagogicalComponent): String? {
        val t = line.replace(":", " ").replace("-", " ").trim()
        return when (component) {
            PedagogicalComponent.READING -> when {
                t.contains("تشخيص المكتسبات") || t.contains("تقويم تشخيصي") || t.contains("التمهيد") -> "diagnostic"
                t.contains("ملاحظة النص") || t.contains("تأطير النص") || t.contains("عتبات النص") -> "observation"
                t.contains("فهم النص") || t.contains("الفكرة العامة") || t.contains("الأفكار الأساسية") -> "comprehension"
                t.contains("القراءة التحليلية") || t.contains("تحليل النص") || t.contains("الحقول المعجمية") -> "analysis"
                t.contains("التركيب") || t.contains("الخلاصة التركيبية") -> "synthesis"
                t.contains("الاستثمار") || t.contains("الأنشطة الاستثمارية") -> "investment"
                else -> null
            }

            PedagogicalComponent.LANGUAGE -> when {
                t.contains("تشخيص") || t.contains("تمهيد") || t.contains("تقويم تشخيصي") -> "lang_diagnostic"
                t.contains("ملاحظة") || t.contains("أمثلة الانطلاق") || t.contains("عزل الظاهرة") -> "lang_observation"
                t.contains("الوصف") || t.contains("التحليل") || t.contains("تفكيك الظاهرة") || t.contains("الجدول") -> "lang_analysis"
                t.contains("استنتاج") || t.contains("تركيب") || t.contains("القاعدة") || t.contains("خلاصة") -> "lang_synthesis"
                t.contains("تطبيق") || t.contains("تقويم إجمالي") || t.contains("تمارين") -> "lang_application"
                else -> null
            }

            PedagogicalComponent.EXPRESSION -> when {
                t.contains("اكتساب") || t.contains("الحصة الأولى") || t.contains("التعريف بالمهارة") -> "insha_acquisition"
                t.contains("تطبيق") || t.contains("الحصة الثانية") || t.contains("أنشطة التطبيق") -> "insha_application"
                t.contains("إنتاج") || t.contains("انتاج") || t.contains("الحصة الثالثة") || t.contains("التحرير") -> "insha_production"
                t.contains("تصحيح") || t.contains("الحصة الرابعة") || t.contains("شبكة التقويم") || t.contains("دعم") -> "insha_correction"
                else -> null
            }
        }
    }

    private fun detectField(line: String): String? {
        val t = line.trim()
        return when {
            t.startsWith("التقويم") || t.startsWith("مؤشرات التقويم") || t.startsWith("التقويم والدعم") -> "eval"
            t.startsWith("أنشطة المدرس") || t.startsWith("أنشطة الأستاذ") || t.startsWith("الأنشطة التعليمية") -> "teacher"
            t.startsWith("القدرات المستهدفة") || t.startsWith("الأهداف") || t.startsWith("الكفايات المستهدفة") -> "abil"
            t.startsWith("المحتوى") || t.startsWith("الأنشطة التعلمية") || t.startsWith("المحتوى الديداكتيكي") -> "content"
            else -> null
        }
    }

    private fun ensureDefaults(
        map: MutableMap<String, MutableMap<String, MutableList<String>>>,
        component: PedagogicalComponent,
        topic: String
    ) {
        when (component) {
            PedagogicalComponent.READING -> {
                if (map["diagnostic"]?.get("content")?.isEmpty() == true) {
                    map["diagnostic"]?.get("content")?.add("<u>التمهيد:</u> استحضار المكتسبات القبلية المتعلقة بموضوع «$topic».")
                }
                if (map["observation"]?.get("content")?.isEmpty() == true) {
                    map["observation"]?.get("content")?.add("<u>العنوان:</u> دلالة العنوان وصياغة فرضيات القراءة.")
                }
                if (map["comprehension"]?.get("content")?.isEmpty() == true) {
                    map["comprehension"]?.get("content")?.add("<u>الفكرة العامة:</u> المعنى الإجمالي للنص وأبعاده.")
                }
                if (map["analysis"]?.get("content")?.isEmpty() == true) {
                    map["analysis"]?.get("content")?.add("<u>الحقول المعجمية والأساليب:</u> المعجم الدلالي وقيم النص.")
                }
                if (map["synthesis"]?.get("content")?.isEmpty() == true) {
                    map["synthesis"]?.get("content")?.add("<u>التركيب:</u> خلاصة جامعة لمضامين النص ومقاصده.")
                }
                if (map["investment"]?.get("content")?.isEmpty() == true) {
                    map["investment"]?.get("content")?.add("<u>الاستثمار:</u> نشاط تعبيري يوظف مكتسبات الدرس.")
                }
            }

            PedagogicalComponent.LANGUAGE -> {
                if (map["lang_diagnostic"]?.get("content")?.isEmpty() == true) {
                    map["lang_diagnostic"]?.get("content")?.add("<u>التقويم التشخيصي:</u> استحضار القواعد الصرفية والتركيبية السابقة ذات الصلة.")
                }
                if (map["lang_observation"]?.get("content")?.isEmpty() == true) {
                    map["lang_observation"]?.get("content")?.add("<u>أمثلة الانطلاق:</u> رصد الشواهد اللغوية لظاهرة «$topic».")
                }
                if (map["lang_analysis"]?.get("content")?.isEmpty() == true) {
                    map["lang_analysis"]?.get("content")?.add("<u>الوصف والتحليل:</u> دراسة خصائص الظاهرة واستنباط القواعد الجزئية.")
                }
                if (map["lang_synthesis"]?.get("content")?.isEmpty() == true) {
                    map["lang_synthesis"]?.get("content")?.add("<u>الاستنتاج:</u> صياغة القاعدة العامة الشاملة لدرس «$topic».")
                }
                if (map["lang_application"]?.get("content")?.isEmpty() == true) {
                    map["lang_application"]?.get("content")?.add("<u>التطبيقات:</u> تمارين إعرابية وتحويلية لترسيخ المفهوم.")
                }
            }

            PedagogicalComponent.EXPRESSION -> {
                if (map["insha_acquisition"]?.get("content")?.isEmpty() == true) {
                    map["insha_acquisition"]?.get("content")?.add("<u>أنشطة الاكتساب:</u> التعريف بالمهارة واستخلاص خطواتها المنهجية.")
                }
                if (map["insha_application"]?.get("content")?.isEmpty() == true) {
                    map["insha_application"]?.get("content")?.add("<u>أنشطة التطبيق:</u> نص الموضوع والتدرب على إنجاز المهارة.")
                }
                if (map["insha_production"]?.get("content")?.isEmpty() == true) {
                    map["insha_production"]?.get("content")?.add("<u>أنشطة الإنتاج:</u> وضعية الانطلاق والتحرير الفردي للموضوع في مسودة.")
                }
                if (map["insha_correction"]?.get("content")?.isEmpty() == true) {
                    map["insha_correction"]?.get("content")?.add("<u>أنشطة التصحيح:</u> معايير شبكة التقويم وتصحيح الأخطاء الشائعة والدعم.")
                }
            }
        }
    }
}
