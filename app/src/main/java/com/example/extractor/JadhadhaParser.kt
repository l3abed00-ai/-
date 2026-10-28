package com.example.extractor

import com.example.model.JadhadhaCategories
import com.example.model.JadhadhaInfo
import com.example.model.JadhadhaPlan
import com.example.model.PedagogicalComponent
import com.example.model.StageCatalog
import com.example.model.StageData

object JadhadhaParser {

    /**
     * Parses raw pasted text or uploaded document content and extracts structured data into
     * the Moroccan pedagogical framework for Reading, Language, or Expression components.
     * Preserves exact stage and cell assignments (Abilities, Teacher, Content, Evaluation)
     * and ensures no fields are displaced or left empty.
     */
    fun parseRawText(
        raw: String,
        fallbackTopic: String = "درس جديد",
        forcedComponent: PedagogicalComponent? = null
    ): JadhadhaPlan {
        val normalizedRaw = preprocessTabularText(raw)
        val lines = normalizedRaw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        var topic = fallbackTopic

        // Detect component type
        val detectedComponent = forcedComponent ?: detectComponent(normalizedRaw, lines)

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
        var hasEnteredStages = false
        val stagesWithExplicitFields = mutableSetOf<String>()

        for (line in lines) {
            val strippedHeaderLine = line
                .removePrefix("•")
                .removePrefix("-")
                .removePrefix("+")
                .removePrefix("*")
                .trim()

            // 1. Check explicit Stage Marker or Stage Header first
            val stageMatch = detectStageWithRemainder(line, detectedComponent)
            if (stageMatch != null && stageMap.containsKey(stageMatch.first)) {
                hasEnteredStages = true
                currentStageKey = stageMatch.first
                currentField = "content"
                val remainder = stageMatch.second
                if (!remainder.isNullOrBlank()) {
                    addClassifiedItem(
                        stageMap = stageMap,
                        stageKey = currentStageKey,
                        currentField = currentField,
                        hasExplicitFields = currentStageKey in stagesWithExplicitFields,
                        rawItem = remainder
                    )
                }
                continue
            }

            // 2. Header metadata extraction (only before stages or when strictly prefixed)
            if (!hasEnteredStages || isStrictHeaderPrefix(strippedHeaderLine)) {
                var matchedHeader = false

                if (strippedHeaderLine.startsWith("موضوع الدرس") ||
                    strippedHeaderLine.startsWith("عنوان الدرس") ||
                    strippedHeaderLine.startsWith("الموضوع:") ||
                    strippedHeaderLine.startsWith("النص القرائي:") ||
                    strippedHeaderLine.startsWith("المهارة المستهدفة") ||
                    (!hasEnteredStages && strippedHeaderLine.startsWith("المهارة:"))
                ) {
                    val extracted = extractAfterColon(strippedHeaderLine)
                    if (extracted.isNotBlank() && extracted.length in 2..80) {
                        topic = extracted.removeSurrounding("«", "»").removeSurrounding("\"", "\"").trim()
                        matchedHeader = true
                    }
                } else if (strippedHeaderLine.startsWith("المستوى") ||
                    strippedHeaderLine.startsWith("السلك") ||
                    strippedHeaderLine.startsWith("الفئة المستهدفة")
                ) {
                    val extracted = extractAfterColon(strippedHeaderLine)
                    if (extracted.isNotBlank()) {
                        level = extracted
                        matchedHeader = true
                    }
                } else if (strippedHeaderLine.startsWith("الوحدة")) {
                    val extracted = extractAfterColon(strippedHeaderLine)
                    if (extracted.isNotBlank()) {
                        unit = extracted
                        matchedHeader = true
                    }
                } else if (strippedHeaderLine.startsWith("المجال")) {
                    val extracted = extractAfterColon(strippedHeaderLine)
                    if (extracted.isNotBlank()) {
                        domain = extracted
                        matchedHeader = true
                    }
                } else if (strippedHeaderLine.startsWith("المكون") || strippedHeaderLine.startsWith("المكوّن")) {
                    val extracted = extractAfterColon(strippedHeaderLine)
                    if (extracted.isNotBlank()) {
                        component = extracted
                        matchedHeader = true
                    }
                } else if (strippedHeaderLine.startsWith("المدة") || strippedHeaderLine.startsWith("الغلاف الزمني")) {
                    val extracted = extractAfterColon(strippedHeaderLine)
                    if (extracted.isNotBlank()) {
                        duration = extracted
                        matchedHeader = true
                    }
                } else if (strippedHeaderLine.startsWith("الوسائل") ||
                    strippedHeaderLine.startsWith("الدعامات") ||
                    strippedHeaderLine.startsWith("المرجع")
                ) {
                    val extracted = extractAfterColon(strippedHeaderLine)
                    if (extracted.isNotBlank()) {
                        materials = extracted
                        matchedHeader = true
                    }
                } else if (strippedHeaderLine.contains("كفاية تواصلية") || strippedHeaderLine.startsWith("الكفاية التواصلية")) {
                    val extracted = extractAfterColon(strippedHeaderLine)
                    if (extracted.isNotBlank()) communicative = extracted
                    matchedHeader = true
                } else if (strippedHeaderLine.contains("كفاية منهجية") || strippedHeaderLine.startsWith("الكفاية المنهجية")) {
                    val extracted = extractAfterColon(strippedHeaderLine)
                    if (extracted.isNotBlank()) methodological = extracted
                    matchedHeader = true
                } else if (strippedHeaderLine.contains("كفاية ثقافية") ||
                    strippedHeaderLine.contains("كفاية معرفية") ||
                    strippedHeaderLine.startsWith("الكفاية الثقافية")
                ) {
                    val extracted = extractAfterColon(strippedHeaderLine)
                    if (extracted.isNotBlank()) cultural = extracted
                    matchedHeader = true
                }

                if (matchedHeader) continue
            }

            // 3. Field Detection within Stage (Abilities, Teacher, Content, Evaluation)
            val detectedField = detectField(strippedHeaderLine)
            if (detectedField != null) {
                hasEnteredStages = true
                currentField = detectedField
                stagesWithExplicitFields.add(currentStageKey)
                val rest = extractAfterColon(strippedHeaderLine)
                if (rest.isNotBlank()) {
                    addClassifiedItem(
                        stageMap = stageMap,
                        stageKey = currentStageKey,
                        currentField = currentField,
                        hasExplicitFields = true,
                        rawItem = rest
                    )
                }
                continue
            }

            // Skip raw table header lines if pasted directly
            if (isTableHeaderLine(strippedHeaderLine)) {
                continue
            }

            // 4. Add item to currentStage
            addClassifiedItem(
                stageMap = stageMap,
                stageKey = currentStageKey,
                currentField = currentField,
                hasExplicitFields = currentStageKey in stagesWithExplicitFields,
                rawItem = line
            )
        }

        // Fill any missing fields across all stages so no cell is ever left empty
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
            category = JadhadhaCategories.forComponent(detectedComponent),
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

    /**
     * Converts tab-separated or pipe-separated table rows (e.g. pasted from Word/Excel)
     * into explicit stage and field blocks.
     */
    private fun preprocessTabularText(raw: String): String {
        val rawLines = raw.lines()
        val tabLinesCount = rawLines.count { it.count { ch -> ch == '\t' } >= 3 }
        if (tabLinesCount < 2) return raw

        val sb = StringBuilder()
        for (line in rawLines) {
            val cols = line.split('\t').map { it.trim() }
            if (cols.size >= 4) {
                if (cols[0].contains("المقاطع") || cols[0].contains("المراحل")) {
                    continue
                }
                if (cols[0].isNotBlank()) {
                    sb.appendLine("[مرحلة: ${cols[0]}]")
                }
                if (cols.size >= 5) {
                    if (cols[1].isNotBlank()) {
                        sb.appendLine("القدرات المستهدفة:")
                        sb.appendLine(cols[1])
                    }
                    if (cols[2].isNotBlank()) {
                        sb.appendLine("أنشطة المدرس:")
                        sb.appendLine(cols[2])
                    }
                    if (cols[3].isNotBlank()) {
                        sb.appendLine("المحتوى الديداكتيكي:")
                        sb.appendLine(cols[3])
                    }
                    if (cols[4].isNotBlank()) {
                        sb.appendLine("التقويم والدعم:")
                        sb.appendLine(cols[4])
                    }
                } else {
                    if (cols[1].isNotBlank()) {
                        sb.appendLine("القدرات المستهدفة:")
                        sb.appendLine(cols[1])
                    }
                    if (cols[2].isNotBlank()) {
                        sb.appendLine("المحتوى الديداكتيكي:")
                        sb.appendLine(cols[2])
                    }
                    if (cols[3].isNotBlank()) {
                        sb.appendLine("التقويم والدعم:")
                        sb.appendLine(cols[3])
                    }
                }
            } else {
                sb.appendLine(line)
            }
        }
        return sb.toString()
    }

    private fun isStrictHeaderPrefix(s: String): Boolean {
        return s.startsWith("موضوع الدرس:") ||
                s.startsWith("عنوان الدرس:") ||
                s.startsWith("المهارة المستهدفة:") ||
                s.startsWith("المستوى الدراسي:") ||
                s.startsWith("المستوى:") ||
                s.startsWith("الوحدة:") ||
                s.startsWith("المجال:") ||
                s.startsWith("المكوّن:") ||
                s.startsWith("المكون:") ||
                s.startsWith("الغلاف الزمني:") ||
                s.startsWith("المدة الزمنية:") ||
                s.startsWith("الوسائل الديداكتيكية:") ||
                s.startsWith("كفاية تواصلية:") ||
                s.startsWith("الكفاية التواصلية:") ||
                s.startsWith("كفاية منهجية:") ||
                s.startsWith("الكفاية المنهجية:") ||
                s.startsWith("كفاية ثقافية") ||
                s.startsWith("الكفاية الثقافية")
    }

    private fun isTableHeaderLine(s: String): Boolean {
        return s == "المقاطع والمراحل" ||
                (s.contains("المقاطع والمراحل") && s.contains("القدرات المستهدفة"))
    }

    private fun addClassifiedItem(
        stageMap: MutableMap<String, MutableMap<String, MutableList<String>>>,
        stageKey: String,
        currentField: String,
        hasExplicitFields: Boolean,
        rawItem: String
    ) {
        val clean = cleanBullet(rawItem)
        if (clean.isBlank()) return

        val targetField = if (hasExplicitFields) {
            // Trust the explicit column/field header from the table or structured document!
            currentField
        } else {
            inferFieldFromUnstructuredLine(clean, currentField)
        }

        val formattedItem = if (targetField == "content") {
            formatContentSubLabel(clean)
        } else {
            clean.replace("<u>", "").replace("</u>", "").trim()
        }

        stageMap[stageKey]?.get(targetField)?.add(formattedItem)
    }

    private fun formatContentSubLabel(clean: String): String {
        if (clean.contains("<u>")) return clean
        val colonIdx = clean.indexOf(':')
        if (colonIdx in 2..32 && !clean.startsWith("http")) {
            val label = clean.substring(0, colonIdx).trim()
            val rest = clean.substring(colonIdx + 1).trim()
            if (rest.isNotEmpty() && !label.contains(" ")) {
                return "<u>$label:</u> $rest"
            }
            val knownSubLabels = listOf(
                "مكتسبات قبلية", "التمهيد", "العنوان", "الصورة", "صاحب النص", "مصدر النص",
                "نوعية النص", "مجال النص", "فرضية القراءة", "الفرضيات", "الشروح اللغوية",
                "الفكرة العامة", "الأفكار الأساسية", "الحقول الدلالية", "الحقول المعجمية",
                "الأساليب", "أساليب النص", "القيم المبثوثة", "قيم النص", "التركيب", "الاستثمار",
                "أمثلة الانطلاق", "الملاحظة والوصف", "الاستنتاج", "القاعدة", "تطبيقات",
                "التعريف بالمهارة", "خطوات المهارة", "عناصر المهارة", "نص الموضوع",
                "وضعية الانطلاق", "توجيهات الإنجاز", "شبكة التقويم", "شبكة التقويم الذاتي"
            )
            if (rest.isNotEmpty() && knownSubLabels.any { label.contains(it) }) {
                return "<u>$label:</u> $rest"
            }
        }
        return clean
    }

    private fun inferFieldFromUnstructuredLine(clean: String, fallbackField: String): String {
        val plain = clean.replace("<u>", "").replace("</u>", "").trim()
        return when {
            plain.startsWith("القدرة على") ||
                    plain.startsWith("أن يكون المتعلم") ||
                    plain.startsWith("أن يتعرف") ||
                    plain.startsWith("أن يتمكن") ||
                    plain.startsWith("أن يكتشف") ||
                    plain.startsWith("أن يستنتج") ||
                    plain.startsWith("أن يحلل") ||
                    plain.startsWith("أن يدرك") ||
                    plain.startsWith("أن يميز") ||
                    plain.startsWith("أن يوظف") ||
                    plain.startsWith("أن يصوغ") ||
                    plain.startsWith("أن يقرأ") ||
                    plain.startsWith("أن يستخلص") ||
                    plain.startsWith("أن يكتسب") ||
                    plain.startsWith("أن يستحضر") ||
                    plain.startsWith("تمكين المتعلم") -> "abil"

            plain.startsWith("يطرح") ||
                    plain.startsWith("يوجه") ||
                    plain.startsWith("يطالب") ||
                    plain.startsWith("يحفز") ||
                    plain.startsWith("يشرف") ||
                    plain.startsWith("يدعو") ||
                    plain.startsWith("يمهد") ||
                    plain.startsWith("يناقش") ||
                    plain.startsWith("يسجل") ||
                    plain.startsWith("يدون") ||
                    plain.startsWith("يستدرج") ||
                    plain.startsWith("يكلف") ||
                    plain.startsWith("يوزع") ||
                    plain.startsWith("يعرض") ||
                    plain.startsWith("يوضح") ||
                    plain.startsWith("يشرح") ||
                    plain.startsWith("ينظم") ||
                    plain.startsWith("يساعد") ||
                    plain.startsWith("يقرأ الأستاذ") ||
                    plain.startsWith("يقرأ المدرس") -> "teacher"

            plain.startsWith("تقويم ") ||
                    plain.startsWith("فحص ") ||
                    plain.startsWith("مدى ") ||
                    plain.startsWith("ملاحظة مدى") ||
                    plain.startsWith("رصد تعثرات") ||
                    plain.startsWith("مراقبة ") ||
                    plain.startsWith("دعم الفئات") ||
                    plain.startsWith("تصحيح الإنجازات") -> "eval"

            else -> fallbackField
        }
    }

    private fun detectComponent(raw: String, lines: List<String>): PedagogicalComponent {
        val sample = (lines.take(25).joinToString(" ") + " " + raw.take(600)).lowercase()
        return when {
            sample.contains("درس لغوي") || sample.contains("الدرس اللغوي") || sample.contains("ظاهرة لغوية") ||
                    sample.contains("اسم الفاعل") || sample.contains("صيغ المبالغة") || sample.contains("أسلوب الاختصاص") ||
                    sample.contains("اسم المفعول") || sample.contains("القاعدة التركيبية") -> PedagogicalComponent.LANGUAGE

            sample.contains("تعبير وإنشاء") || sample.contains("التعبير والإنشاء") || sample.contains("أنشطة الاكتساب") ||
                    sample.contains("أنشطة التطبيق") || sample.contains("أنشطة الإنتاج") || sample.contains("أنشطة التصحيح") ||
                    sample.contains("حصة الاكتساب") || sample.contains("كتابة حكاية") || sample.contains("كتابة يوميات") -> PedagogicalComponent.EXPRESSION

            else -> PedagogicalComponent.READING
        }
    }

    private fun extractAfterColon(line: String): String {
        val idx = line.indexOf(':')
        return if (idx >= 0 && idx < line.length - 1) {
            line.substring(idx + 1).trim()
        } else {
            val hyphenIdx = line.indexOf('-')
            if (hyphenIdx in 1..18) line.substring(hyphenIdx + 1).trim() else ""
        }
    }

    private fun cleanBullet(line: String): String {
        return line.trim()
            .removePrefix("-")
            .removePrefix("+")
            .removePrefix("•")
            .removePrefix("*")
            .replace(Regex("^\\d+[.\\-)]\\s*"), "")
            .trim()
    }

    /**
     * Detects if a line is a stage header.
     * Returns Pair(stageKey, optionalRemainderText) if matched, or null if it's a normal content line.
     */
    private fun detectStageWithRemainder(line: String, component: PedagogicalComponent): Pair<String, String?>? {
        val trimmed = line.trim()

        // 1. Explicit marker from DocumentFileReader: [مرحلة: ...] or [حصة: ...]
        if ((trimmed.startsWith("[مرحلة:") || trimmed.startsWith("[حصة:")) && trimmed.endsWith("]")) {
            val inner = trimmed.substringAfter(":").removeSuffix("]").trim()
            val key = matchStageName(inner, component)
            if (key != null) return key to null
        }

        // 2. Do NOT treat bulleted lines (-, +, •, *) or underlined content items (<u>...</u>) as stage headers
        if (trimmed.startsWith("-") ||
            trimmed.startsWith("+") ||
            trimmed.startsWith("•") ||
            trimmed.startsWith("*") ||
            trimmed.startsWith("<u>")
        ) {
            return null
        }

        // 3. Check if line is a standalone stage header or "StageHeader: optionalRemainder"
        val colonIdx = trimmed.indexOf(':')
        val headerPart = if (colonIdx >= 0) trimmed.substring(0, colonIdx).trim() else trimmed
        val remainderPart = if (colonIdx >= 0 && colonIdx < trimmed.length - 1) {
            trimmed.substring(colonIdx + 1).trim()
        } else {
            null
        }

        // Stage header part must be concise (not a long paragraph)
        if (headerPart.length > 50) return null

        // Do NOT confuse content sub-labels (like الفكرة العامة:, الحقول المعجمية:, العنوان:) with stage headers
        val contentSubLabels = listOf(
            "مكتسبات قبلية", "العنوان", "الصورة", "صاحب النص", "مصدر النص",
            "نوعية النص", "فرضية القراءة", "الفرضيات", "الشروح اللغوية",
            "الفكرة العامة", "الأفكار الأساسية", "الحقول الدلالية", "الحقول المعجمية",
            "أساليب النص", "الأساليب", "قيم النص", "التعريف بالمهارة", "عناصر المهارة",
            "خطوات المهارة", "نص الموضوع", "شبكة التقويم", "شبكة التقويم الذاتي",
            "حالات الاسم", "حكمه الإعرابي"
        )
        if (contentSubLabels.any { headerPart == it || headerPart.startsWith(it) }) {
            return null
        }

        // Special case: "التمهيد: ..." inside diagnostic stage is a content sub-label when remainder is a full sentence
        if (headerPart == "التمهيد" && !remainderPart.isNullOrBlank() && remainderPart.length > 15) {
            return "diagnostic" to "<u>التمهيد:</u> $remainderPart"
        }

        val stageKey = matchStageName(headerPart, component) ?: return null

        // If remainder is just a session subtitle (e.g. "الحصة الأولى: القراءة الاستكشافية"), ignore remainder
        val cleanRemainder = if (!remainderPart.isNullOrBlank() &&
            !remainderPart.startsWith("الحصة") &&
            !remainderPart.startsWith("أنشطة الاكتساب") &&
            !remainderPart.startsWith("أنشطة التطبيق") &&
            !remainderPart.startsWith("أنشطة الإنتاج") &&
            !remainderPart.startsWith("أنشطة التصحيح") &&
            !remainderPart.startsWith("القراءة الاستكشافية") &&
            !remainderPart.startsWith("القراءة التحليلية")
        ) {
            remainderPart
        } else {
            null
        }

        return stageKey to cleanRemainder
    }

    private fun matchStageName(text: String, component: PedagogicalComponent): String? {
        val t = text.replace(":", " ").replace("-", " ").trim()
        return when (component) {
            PedagogicalComponent.READING -> when {
                t.contains("تشخيص المكتسبات") || t.contains("تقويم تشخيصي") || t == "التمهيد" || t == "تمهيد" -> "diagnostic"
                t.contains("ملاحظة النص") || t.contains("تأطير النص") || t == "الملاحظة والتأطير" || t == "ملاحظة وتأطير" -> "observation"
                t.contains("فهم النص") || t == "الفهم" || t == "القراءة التوجيهية" -> "comprehension"
                t.contains("القراءة التحليلية") || t.contains("تحليل النص") || t == "التحليل" -> "analysis"
                t.contains("التركيب") || t.contains("الخلاصة التركيبية") || t == "تركيب" -> "synthesis"
                t.contains("الاستثمار") || t.contains("الأنشطة الاستثمارية") || t == "استثمار" -> "investment"
                else -> null
            }

            PedagogicalComponent.LANGUAGE -> when {
                t.contains("تشخيص") || t.contains("تقويم تشخيصي") || t == "تمهيد" || t == "التمهيد" -> "lang_diagnostic"
                t.contains("ملاحظة الظاهرة") || t.contains("عزل الظاهرة") || t == "الملاحظة" || t == "أمثلة الانطلاق" -> "lang_observation"
                t.contains("الوصف والتحليل") || t.contains("تفكيك الظاهرة") || t == "الوصف" || t == "التحليل" -> "lang_analysis"
                t.contains("الاستنتاج") || t.contains("استنتاج") || t.contains("القاعدة التركيبية") || t == "القاعدة" -> "lang_synthesis"
                t.contains("التطبيقات") || t.contains("تطبيق") || t.contains("تقويم إجمالي") || t.contains("تمارين") -> "lang_application"
                else -> null
            }

            PedagogicalComponent.EXPRESSION -> when {
                t.contains("اكتساب") || t.contains("الحصة الأولى") -> "insha_acquisition"
                t.contains("تطبيق") || t.contains("الحصة الثانية") -> "insha_application"
                t.contains("إنتاج") || t.contains("انتاج") || t.contains("الحصة الثالثة") -> "insha_production"
                t.contains("تصحيح") || t.contains("الحصة الرابعة") -> "insha_correction"
                else -> null
            }
        }
    }

    private fun detectField(line: String): String? {
        val t = line.trim()
        val colonIdx = t.indexOf(':')
        val label = if (colonIdx >= 0) t.substring(0, colonIdx).trim() else t
        if (label.length > 45) return null

        return when {
            label == "التقويم" || label == "التقويم والدعم" || label == "مؤشرات التقويم" ||
                    label == "مؤشرات التقويم والدعم" || label == "التقويم المرحلي" || label == "معايير التقويم" -> "eval"

            label == "أنشطة المدرس" || label == "أنشطة الأستاذ" || label == "الأنشطة التعليمية" ||
                    label == "مهام المدرس" || label == "دور المدرس" -> "teacher"

            label == "القدرات المستهدفة" || label == "الأهداف" || label == "الأهداف التعلمية" ||
                    label == "الأهداف التعليمية" || label == "الكفايات المستهدفة" -> "abil"

            label == "المحتوى" || label == "المحتوى الديداكتيكي" || label == "الأنشطة التعلمية" ||
                    label == "المحتوى الديداكتيكي والأنشطة التعلمية" || label == "أنشطة المتعلم" -> "content"

            else -> null
        }
    }

    /**
     * Ensures that every stage has complete, pedagogically sound entries for all 4 cells
     * (abil, teacher, content, eval) so that no cell is ever left blank after extraction.
     */
    private fun ensureDefaults(
        map: MutableMap<String, MutableMap<String, MutableList<String>>>,
        component: PedagogicalComponent,
        topic: String
    ) {
        when (component) {
            PedagogicalComponent.READING -> {
                fillStageDefaults(
                    stage = map["diagnostic"],
                    defaultAbil = "القدرة على استحضار المكتسبات القبلية وربطها بموضوع الدرس «$topic».",
                    defaultTeacher = "يطرح أسئلة تشخيصية لاستحضار مكتسبات المتعلمين القبلية وربطها بالدرس الجديد.",
                    defaultContent = "<u>التمهيد:</u> استحضار المكتسبات القبلية المتعلقة بموضوع «$topic».",
                    defaultEval = "تقويم مدى قدرة المتعلمين على استحضار المكتسبات السابقة والمشاركة في التمهيد."
                )
                fillStageDefaults(
                    stage = map["observation"],
                    defaultAbil = "القدرة على ملاحظة العتبات النصية وصياغة فرضيات القراءة.",
                    defaultTeacher = "يوجه المتعلمين إلى ملاحظة العنوان والمؤشرات الخارجية وبناء فرضيات القراءة.",
                    defaultContent = "<u>العنوان:</u> دلالة العنوان وصياغة فرضيات القراءة لنص «$topic».",
                    defaultEval = "فحص وجاهة الفرضيات المصوغة ومدى دقة ملاحظة العتبات النصية."
                )
                fillStageDefaults(
                    stage = map["comprehension"],
                    defaultAbil = "القدرة على قراءة النص قراءة سليمة واستخلاص مضمونه العام وأفكاره الأساسية.",
                    defaultTeacher = "يقرأ النص قراءة نموذجية، ويتخلل قراءات المتعلمين بشرح المفردات وطرح أسئلة الفهم.",
                    defaultContent = "<u>الفكرة العامة:</u> المعنى الإجمالي للنص وأفكاره الأساسية.",
                    defaultEval = "تقويم سلامة القراءة ومدى استيعاب المعاني الصريحة والضمنية للنص."
                )
                fillStageDefaults(
                    stage = map["analysis"],
                    defaultAbil = "القدرة على تحليل الحقول الدلالية والخصائص الفنية والقيم المتضمنة في النص.",
                    defaultTeacher = "يساعد المتعلمين على جرد الحقول المعجمية واستخراج الأساليب والصور الفنية وقيم النص.",
                    defaultContent = "<u>الحقول المعجمية والأساليب:</u> المعجم الدلالي والخصائص الفنية وقيم النص.",
                    defaultEval = "مدى قدرة المتعلمين على تصنيف المعجم واستخراج الأساليب والقيم بدقة."
                )
                fillStageDefaults(
                    stage = map["synthesis"],
                    defaultAbil = "القدرة على تجميع معطيات النص في خلاصة تركيبية منسجمة وتمحيص الفرضيات.",
                    defaultTeacher = "يطالب المتعلمين بصياغة خلاصة تركيبية لمضامين النص وربطها بفرضيات الانطلاق.",
                    defaultContent = "<u>التركيب:</u> خلاصة جامعة لمضامين النص ومقاصده التربوية والفنية.",
                    defaultEval = "فحص جودة الخلاصة التركيبية ومدى تحقق فرضيات القراءة."
                )
                fillStageDefaults(
                    stage = map["investment"],
                    defaultAbil = "القدرة على استثمار مكتسبات النص في وضعيات تواصلية وإنتاجية جديدة.",
                    defaultTeacher = "يكلف المتعلمين بإنجاز نشاط استثماري يربط النص بواقعهم المعيش.",
                    defaultContent = "<u>الاستثمار:</u> نشاط تعبيري أو بحثي يوظف مكتسبات درس «$topic».",
                    defaultEval = "تقويم الإنجازات الاستثمارية للمتعلمين وتصويب التعثرات."
                )
            }

            PedagogicalComponent.LANGUAGE -> {
                fillStageDefaults(
                    stage = map["lang_diagnostic"],
                    defaultAbil = "القدرة على استحضار المكتسبات اللغوية السابقة ذات الصلة بالظاهرة.",
                    defaultTeacher = "يطرح أسئلة تقويمية لتشخيص المكتسبات اللغوية السابقة والتمهيد للدرس.",
                    defaultContent = "<u>التقويم التشخيصي:</u> استحضار القواعد الصرفية والتركيبية السابقة ذات الصلة.",
                    defaultEval = "تقويم مدى تذكر المتعلمين للقواعد اللغوية المكتسبة سابقاً."
                )
                fillStageDefaults(
                    stage = map["lang_observation"],
                    defaultAbil = "القدرة على ملاحظة أمثلة الانطلاق ورصد الظاهرة اللغوية وعزلها.",
                    defaultTeacher = "يدون أمثلة الانطلاق على السبورة ويطالب المتعلمين بقراءتها وملاحظة الكلمات المكتوبة بلون مغاير.",
                    defaultContent = "<u>أمثلة الانطلاق:</u> رصد الشواهد اللغوية لظاهرة «$topic».",
                    defaultEval = "فحص قدرة المتعلمين على تحديد الظاهرة اللغوية المستهدفة داخل الشواهد."
                )
                fillStageDefaults(
                    stage = map["lang_analysis"],
                    defaultAbil = "القدرة على وصف الظاهرة اللغوية وتحليل خصائصها واستنباط أحكامها.",
                    defaultTeacher = "يستدرج المتعلمين عبر جدول توضيحي لوصف الظاهرة وتحليل بنيتها وشروطها.",
                    defaultContent = "<u>الوصف والتحليل:</u> دراسة خصائص ظاهرة «$topic» واستنباط القواعد الجزئية.",
                    defaultEval = "مراقبة مدى مشاركة المتعلمين في ملء الجدول التحليلي واستخلاص الاستنتاجات الجزئية."
                )
                fillStageDefaults(
                    stage = map["lang_synthesis"],
                    defaultAbil = "القدرة على تجميع الاستنتاجات الجزئية وصياغة القاعدة العامة للدرس.",
                    defaultTeacher = "يوجه المتعلمين إلى تجميع الاستنتاجات الجزئية لبناء القاعدة التركيبية الشاملة.",
                    defaultContent = "<u>الاستنتاج:</u> صياغة القاعدة العامة الشاملة لدرس «$topic».",
                    defaultEval = "تقويم قدرة المتعلمين على استيعاب القاعدة والتمثيل لها بجمل مفيدة."
                )
                fillStageDefaults(
                    stage = map["lang_application"],
                    defaultAbil = "القدرة على توظيف القاعدة اللغوية في سياقات تطبيقية وإعرابية متنوعة.",
                    defaultTeacher = "يقترح تمارين تطبيقية وإعرابية ويشرف على التصحيح الجماعي والفردي.",
                    defaultContent = "<u>التطبيقات:</u> تمارين إعرابية وتحويلية لترسيخ ظاهرة «$topic».",
                    defaultEval = "فحص نسبة التحكم في الظاهرة اللغوية ودعم المتعثرين."
                )
            }

            PedagogicalComponent.EXPRESSION -> {
                fillStageDefaults(
                    stage = map["insha_acquisition"],
                    defaultAbil = "القدرة على تعرف المهارة الإنشائية وتحديد خصائصها وخطواتها المنهجية.",
                    defaultTeacher = "يعرض نص الانطلاق ويوجه المتعلمين لاستخلاص تعريف المهارة وخطواتها الإجرائية.",
                    defaultContent = "<u>أنشطة الاكتساب:</u> التعريف بمهارة «$topic» واستخلاص خطواتها المنهجية.",
                    defaultEval = "تقويم مدى استيعاب المتعلمين لعناصر المهارة وتصميمها المنهجي."
                )
                fillStageDefaults(
                    stage = map["insha_application"],
                    defaultAbil = "القدرة على تطبيق خطوات المهارة الإنشائية جماعياً وثنائياً على نموذج تطبيقي.",
                    defaultTeacher = "يكتب نص الموضوع التطبيقي على السبورة ويشرف على التدريب الجماعي لتطبيق خطوات المهارة.",
                    defaultContent = "<u>أنشطة التطبيق:</u> نص الموضوع والتدرب على إنجاز مهارة «$topic».",
                    defaultEval = "مراقبة مدى التزام المتعلمين بخطوات المهارة أثناء التدريب التطبيقي."
                )
                fillStageDefaults(
                    stage = map["insha_production"],
                    defaultAbil = "القدرة على الإنتاج الفردي المستقل وفق معايير المهارة الإنشائية.",
                    defaultTeacher = "يقدم وضعية الانطلاق ويوضح المطلوب ويرافق المتعلمين أثناء التحرير الفردي في المسودة.",
                    defaultContent = "<u>أنشطة الإنتاج:</u> وضعية الانطلاق والتحرير الفردي للموضوع في مسودة.",
                    defaultEval = "تقويم مدى قدرة المتعلم على التخطيط والربط والتحرير السليم."
                )
                fillStageDefaults(
                    stage = map["insha_correction"],
                    defaultAbil = "القدرة على التقويم الذاتي وتصحيح الأخطاء اللغوية والمنهجية وتجويد الإنتاج.",
                    defaultTeacher = "يعرض شبكة التقويم ويصنف الأخطاء المشتركة ويشرف على التصحيح الجماعي والفردي.",
                    defaultContent = "<u>أنشطة التصحيح:</u> معايير شبكة التقويم وتصحيح الأخطاء الشائعة والدعم.",
                    defaultEval = "فحص مدى قدرة المتعلمين على اكتشاف أخطائهم وتصويبها وفق شبكة التقويم."
                )
            }
        }
    }

    private fun fillStageDefaults(
        stage: MutableMap<String, MutableList<String>>?,
        defaultAbil: String,
        defaultTeacher: String,
        defaultContent: String,
        defaultEval: String
    ) {
        if (stage == null) return
        if (stage["abil"]?.isEmpty() == true) stage["abil"]?.add(defaultAbil)
        if (stage["teacher"]?.isEmpty() == true) stage["teacher"]?.add(defaultTeacher)
        if (stage["content"]?.isEmpty() == true) stage["content"]?.add(defaultContent)
        if (stage["eval"]?.isEmpty() == true) stage["eval"]?.add(defaultEval)
    }
}
