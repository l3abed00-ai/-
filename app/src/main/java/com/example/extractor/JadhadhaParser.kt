package com.example.extractor

import com.example.model.FieldSource
import com.example.model.JadhadhaCategories
import com.example.model.JadhadhaInfo
import com.example.model.JadhadhaPlan
import com.example.model.ParseReport
import com.example.model.ParseResult
import com.example.model.PedagogicalComponent
import com.example.model.StageCatalog
import com.example.model.StageData
import com.example.model.StageSourceCount
import com.example.model.UnclassifiedLine

object JadhadhaParser {

    /**
     * Parses raw pasted text or uploaded document content and extracts structured data into
     * the Moroccan pedagogical framework for Reading, Language, or Expression components.
     * Kept for backward compatibility; delegates to [parseWithReport].
     */
    fun parseRawText(
        raw: String,
        fallbackTopic: String = "درس جديد",
        forcedComponent: PedagogicalComponent? = null
    ): JadhadhaPlan {
        return parseWithReport(
            raw = raw,
            fallbackTopic = fallbackTopic,
            forcedComponent = forcedComponent
        ).plan
    }

    /**
     * Parses raw text or extracted .docx content and returns both the [JadhadhaPlan]
     * (with per-field [FieldSource] tags: FILE vs DEFAULT) and a detailed [ParseReport]
     * containing component detection method, FILE/DEFAULT counts per stage, and unclassified lines.
     */
    fun parseWithReport(
        raw: String,
        fallbackTopic: String = "درس جديد",
        forcedComponent: PedagogicalComponent? = null
    ): ParseResult {
        val normalizedRaw = preprocessTabularText(raw)
        val rawLines = normalizedRaw.lines()
        val indexedNonEmptyLines = rawLines.mapIndexedNotNull { idx, line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) (idx + 1) to trimmed else null
        }
        val plainLines = indexedNonEmptyLines.map { it.second }
        var topic = fallbackTopic

        // Detect component type and whether keywords were matched
        val (keywordDetectedComp, matchedByKeywords) = detectComponentWithKeywords(normalizedRaw, plainLines)
        val detectedComponent = forcedComponent ?: keywordDetectedComp
        val isComponentDefaulted = !matchedByKeywords &&
                (forcedComponent == null || forcedComponent == PedagogicalComponent.READING)

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
        val stageSources = mutableMapOf<String, MutableMap<String, FieldSource>>()
        stageMetaList.forEach { sm ->
            stageMap[sm.key] = mutableMapOf(
                "eval" to mutableListOf(),
                "content" to mutableListOf(),
                "teacher" to mutableListOf(),
                "abil" to mutableListOf()
            )
            stageSources[sm.key] = mutableMapOf()
        }

        var currentStageKey: String? = null
        var currentField = "content"
        var hasEnteredStages = false
        val stagesWithExplicitFields = mutableSetOf<String>()
        val unclassifiedLines = mutableListOf<UnclassifiedLine>()

        for ((lineNumber, line) in indexedNonEmptyLines) {
            if (line == "[نهاية الجدول]") {
                currentStageKey = null
                continue
            }

            val strippedHeaderLine = line
                .removePrefix("•")
                .removePrefix("-")
                .removePrefix("+")
                .removePrefix("*")
                .trim()

            // Skip official administrative header banners from .docx header tables
            if (isAdministrativeBannerLine(strippedHeaderLine)) {
                continue
            }

            // 1. Check explicit Stage Marker [مرحلة: ...] or [حصة: ...]
            if ((line.startsWith("[مرحلة:") || line.startsWith("[حصة:")) && line.endsWith("]")) {
                val inner = line.substringAfter(":").removeSuffix("]").trim()
                val matchedKey = matchStageName(inner, detectedComponent)
                if (matchedKey != null && stageMap.containsKey(matchedKey)) {
                    hasEnteredStages = true
                    currentStageKey = matchedKey
                    currentField = "content"
                } else {
                    // Unrecognized stage row in table
                    currentStageKey = null
                    if (inner.isNotBlank()) {
                        unclassifiedLines.add(UnclassifiedLine(lineNumber, inner))
                    }
                }
                continue
            }

            // 1b. Check natural Stage Header in text
            val stageMatch = detectStageWithRemainder(line, detectedComponent)
            if (stageMatch != null && stageMap.containsKey(stageMatch.first)) {
                hasEnteredStages = true
                currentStageKey = stageMatch.first
                currentField = "content"
                val remainder = stageMatch.second
                if (!remainder.isNullOrBlank()) {
                    addClassifiedItem(
                        stageMap = stageMap,
                        stageSources = stageSources,
                        stageKey = stageMatch.first,
                        currentField = currentField,
                        hasExplicitFields = stageMatch.first in stagesWithExplicitFields,
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
                val activeKey = currentStageKey ?: stageMetaList.first().key.also { currentStageKey = it }
                currentField = detectedField
                stagesWithExplicitFields.add(activeKey)
                val rest = extractAfterColon(strippedHeaderLine)
                if (rest.isNotBlank()) {
                    addClassifiedItem(
                        stageMap = stageMap,
                        stageSources = stageSources,
                        stageKey = activeKey,
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

            // 4. Classify into active stage or record as UnclassifiedLine
            val activeStageKey = currentStageKey
            if (activeStageKey == null) {
                // Line appears before any stage header or after table end
                unclassifiedLines.add(UnclassifiedLine(lineNumber = lineNumber, text = line))
                continue
            }

            val hasExplicitFields = activeStageKey in stagesWithExplicitFields
            if (!hasExplicitFields && isUnclassifiedInUnstructuredStage(line, stageMap[activeStageKey])) {
                unclassifiedLines.add(UnclassifiedLine(lineNumber = lineNumber, text = line))
                continue
            }

            addClassifiedItem(
                stageMap = stageMap,
                stageSources = stageSources,
                stageKey = activeStageKey,
                currentField = currentField,
                hasExplicitFields = hasExplicitFields,
                rawItem = line
            )
        }

        // Fill any missing fields across all stages with DEFAULT items and tag their source as DEFAULT
        ensureDefaults(stageMap, stageSources, detectedComponent, topic)

        val builtStages = stageMetaList.associate { sm ->
            val fieldMap = stageMap[sm.key] ?: emptyMap()
            val sourcesForStage = stageSources[sm.key]?.toMap() ?: emptyMap()
            sm.key to StageData(
                eval = fieldMap["eval"]?.distinct() ?: emptyList(),
                content = fieldMap["content"]?.distinct() ?: emptyList(),
                teacher = fieldMap["teacher"]?.distinct() ?: emptyList(),
                abil = fieldMap["abil"]?.distinct() ?: emptyList(),
                sources = sourcesForStage
            )
        }

        val plan = JadhadhaPlan(
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

        val report = buildReport(
            plan = plan,
            isComponentDefaulted = isComponentDefaulted,
            unclassifiedLines = unclassifiedLines
        )

        return ParseResult(plan = plan, report = report)
    }

    /**
     * Recomputes the [ParseReport] for a given [JadhadhaPlan] and remaining [UnclassifiedLine]s.
     */
    fun buildReport(
        plan: JadhadhaPlan,
        isComponentDefaulted: Boolean,
        unclassifiedLines: List<UnclassifiedLine>
    ): ParseReport {
        val stageMetaList = StageCatalog.getStagesForComponent(plan.componentType)
        val stageCounts = stageMetaList.associate { sm ->
            val stData = plan.stages[sm.key] ?: StageData()
            val fields = listOf("abil", "teacher", "content", "eval")
            val fileCount = fields.count { f -> stData.sourceFor(f) == FieldSource.FILE }
            val defaultCount = fields.count { f -> stData.sourceFor(f) == FieldSource.DEFAULT }
            sm.key to StageSourceCount(
                stageKey = sm.key,
                stageTitle = sm.title,
                fileCount = fileCount,
                defaultCount = defaultCount
            )
        }
        return ParseReport(
            detectedComponent = plan.componentType,
            isComponentDefaulted = isComponentDefaulted,
            usedKeywordsForComponent = !isComponentDefaulted,
            stageSourceCounts = stageCounts,
            unclassifiedLines = unclassifiedLines
        )
    }

    /**
     * Assigns an [UnclassifiedLine] to a specific [targetStageKey] and [targetField] ("abil", "teacher", "content", "eval").
     * Updates that field's source to [FieldSource.FILE], removes any DEFAULT placeholder in that cell,
     * removes the line from [ParseReport.unclassifiedLines], and updates the report counts.
     */
    fun assignUnclassifiedLine(
        currentResult: ParseResult,
        line: UnclassifiedLine,
        targetStageKey: String,
        targetField: String = "content"
    ): ParseResult {
        val plan = currentResult.plan
        val currentStage = plan.stages[targetStageKey] ?: StageData()
        val cleanText = cleanBullet(line.text).ifBlank { line.text.trim() }
        val formattedItem = if (targetField == "content") {
            formatContentSubLabel(cleanText)
        } else {
            cleanText.replace("<u>", "").replace("</u>", "").trim()
        }

        val wasDefault = currentStage.sourceFor(targetField) == FieldSource.DEFAULT
        val updatedStage = when (targetField) {
            "abil" -> currentStage.copy(
                abil = if (wasDefault) listOf(formattedItem) else (currentStage.abil + formattedItem).distinct(),
                sources = currentStage.sources + ("abil" to FieldSource.FILE)
            )
            "teacher" -> currentStage.copy(
                teacher = if (wasDefault) listOf(formattedItem) else (currentStage.teacher + formattedItem).distinct(),
                sources = currentStage.sources + ("teacher" to FieldSource.FILE)
            )
            "eval" -> currentStage.copy(
                eval = if (wasDefault) listOf(formattedItem) else (currentStage.eval + formattedItem).distinct(),
                sources = currentStage.sources + ("eval" to FieldSource.FILE)
            )
            else -> currentStage.copy(
                content = if (wasDefault) listOf(formattedItem) else (currentStage.content + formattedItem).distinct(),
                sources = currentStage.sources + ("content" to FieldSource.FILE)
            )
        }

        val updatedStages = plan.stages.toMutableMap().apply {
            put(targetStageKey, updatedStage)
        }
        val updatedPlan = plan.copy(stages = updatedStages)
        val remainingUnclassified = currentResult.report.unclassifiedLines.filterNot {
            it.lineNumber == line.lineNumber && it.text == line.text
        }
        val updatedReport = buildReport(
            plan = updatedPlan,
            isComponentDefaulted = currentResult.report.isComponentDefaulted,
            unclassifiedLines = remainingUnclassified
        )
        return ParseResult(plan = updatedPlan, report = updatedReport)
    }

    /**
     * Ignores/dismisses an [UnclassifiedLine] from the review list.
     */
    fun ignoreUnclassifiedLine(
        currentResult: ParseResult,
        line: UnclassifiedLine
    ): ParseResult {
        val remaining = currentResult.report.unclassifiedLines.filterNot {
            it.lineNumber == line.lineNumber && it.text == line.text
        }
        return currentResult.copy(
            report = currentResult.report.copy(unclassifiedLines = remaining)
        )
    }

    private fun isAdministrativeBannerLine(s: String): Boolean {
        return s == "المملكة المغربية" ||
                s.startsWith("وزارة التربية الوطنية") ||
                s.startsWith("الأكاديمية الجهوية") ||
                s.startsWith("المديرية الإقليمية") ||
                s.startsWith("الثانوية الإعدادية") ||
                s.startsWith("السنة الدراسية") ||
                s.startsWith("بطاقة تخطيط درس") ||
                s.startsWith("مادة: اللغة العربية") ||
                (s.startsWith("مكون:") && s.contains("مادة:"))
    }

    /**
     * Determines if a line inside an unstructured stage (one without explicit field headers like "المحتوى الديداكتيكي:")
     * is ambiguous/unclassified rather than a stage item.
     */
    private fun isUnclassifiedInUnstructuredStage(
        rawLine: String,
        stageFields: MutableMap<String, MutableList<String>>?
    ): Boolean {
        val trimmed = rawLine.trim()
        // Bulleted items (-, •, +, *, 1.) or underlined items are structured content
        if (trimmed.startsWith("-") ||
            trimmed.startsWith("•") ||
            trimmed.startsWith("+") ||
            trimmed.startsWith("*") ||
            trimmed.startsWith("<u>") ||
            trimmed.matches(Regex("^\\d+[.\\-)].*"))
        ) {
            return false
        }

        // If it matches a known pedagogical verb/prefix for abil, teacher, or eval, it's classified
        val inferred = inferFieldFromUnstructuredLine(trimmed, "unknown")
        if (inferred != "unknown") {
            return false
        }

        // If it has a colon and matches known content sub-labels, it's classified
        val colonIdx = trimmed.indexOf(':')
        if (colonIdx in 2..35) {
            val label = trimmed.substring(0, colonIdx).trim()
            if (KNOWN_SUB_LABELS.any { label.contains(it) }) {
                return false
            }
            // Unknown label with colon in unstructured text -> unclassified
            return true
        }

        // If the stage has zero content items so far and the line starts with known summary words, allow it
        val existingContentCount = stageFields?.get("content")?.size ?: 0
        if (existingContentCount == 0 && (
                    trimmed.startsWith("تعتبر ") ||
                            trimmed.startsWith("تعالج ") ||
                            trimmed.startsWith("اكتب ") ||
                            trimmed.startsWith("عين ") ||
                            trimmed.startsWith("أعرب ") ||
                            trimmed.startsWith("استخرج ")
                    )
        ) {
            return false
        }

        // Otherwise, a bare unbulleted, unlabeled sentence in an unstructured block is treated as unclassified for teacher review
        return true
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
        stageSources: MutableMap<String, MutableMap<String, FieldSource>>,
        stageKey: String,
        currentField: String,
        hasExplicitFields: Boolean,
        rawItem: String
    ) {
        val clean = cleanBullet(rawItem)
        if (clean.isBlank()) return

        val targetField = if (hasExplicitFields) {
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
        stageSources[stageKey]?.put(targetField, FieldSource.FILE)
    }

    private val KNOWN_SUB_LABELS = listOf(
        "مكتسبات قبلية", "التمهيد", "العنوان", "الصورة", "صاحب النص", "مصدر النص",
        "نوعية النص", "مجال النص", "فرضية القراءة", "الفرضيات", "الشروح اللغوية",
        "الفكرة العامة", "الأفكار الأساسية", "الحقول الدلالية", "الحقول المعجمية",
        "الأساليب", "أساليب النص", "القيم المبثوثة", "قيم النص", "القيم المتضمنة", "التركيب", "الاستثمار",
        "أمثلة الانطلاق", "الملاحظة والوصف", "الاستنتاج", "القاعدة", "تطبيقات",
        "التعريف بالمهارة", "خطوات المهارة", "عناصر المهارة", "سيرورة السرد", "نص الموضوع",
        "وضعية الانطلاق", "توجيهات الإنجاز", "شبكة التقويم", "شبكة التقويم الذاتي",
        "حالات الاسم", "حكمه الإعرابي", "الشاعر", "الكاتب"
    )

    private fun formatContentSubLabel(clean: String): String {
        if (clean.contains("<u>")) return clean
        val colonIdx = clean.indexOf(':')
        if (colonIdx in 2..32 && !clean.startsWith("http")) {
            val label = clean.substring(0, colonIdx).trim()
            val rest = clean.substring(colonIdx + 1).trim()
            if (rest.isNotEmpty() && !label.contains(" ")) {
                return "<u>$label:</u> $rest"
            }
            if (rest.isNotEmpty() && KNOWN_SUB_LABELS.any { label.contains(it) }) {
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
                    plain.startsWith("يرسم") ||
                    plain.startsWith("يقدم") ||
                    plain.startsWith("يقترح") ||
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

    /**
     * Returns Pair(detectedComponent, matchedByKeywords).
     * If no explicit component keywords were found, returns (READING, false).
     */
    private fun detectComponentWithKeywords(raw: String, lines: List<String>): Pair<PedagogicalComponent, Boolean> {
        val sample = (lines.take(30).joinToString(" ") + " " + raw.take(800)).lowercase()
        return when {
            sample.contains("درس لغوي") || sample.contains("الدرس اللغوي") || sample.contains("ظاهرة لغوية") ||
                    sample.contains("ملاحظة الظاهرة") || sample.contains("الوصف والتحليل") ||
                    sample.contains("اسم الفاعل") || sample.contains("صيغ المبالغة") || sample.contains("أسلوب الاختصاص") ||
                    sample.contains("اسم المفعول") || sample.contains("القاعدة التركيبية") ->
                PedagogicalComponent.LANGUAGE to true

            sample.contains("تعبير وإنشاء") || sample.contains("التعبير والإنشاء") || sample.contains("أنشطة الاكتساب") ||
                    sample.contains("أنشطة التطبيق") || sample.contains("أنشطة الإنتاج") || sample.contains("أنشطة التصحيح") ||
                    sample.contains("حصة الاكتساب") || sample.contains("كتابة حكاية") || sample.contains("كتابة يوميات") ->
                PedagogicalComponent.EXPRESSION to true

            sample.contains("نصوص قرائية") || sample.contains("النصوص القرائية") || sample.contains("نص قرائي") ||
                    sample.contains("ملاحظة النص") || sample.contains("فهم النص") || sample.contains("القراءة التحليلية") ||
                    sample.contains("القراءة التوجيهية") || sample.contains("فرضية القراءة") || sample.contains("فرضيات القراءة") ||
                    sample.contains("صاحب النص") || sample.contains("الحقول الدلالية") || sample.contains("الحقول المعجمية") ->
                PedagogicalComponent.READING to true

            else -> PedagogicalComponent.READING to false
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
            return null
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
        if (KNOWN_SUB_LABELS.any { headerPart == it || headerPart.startsWith(it) }) {
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
     * (abil, teacher, content, eval) and tags any newly filled cell as [FieldSource.DEFAULT].
     */
    private fun ensureDefaults(
        map: MutableMap<String, MutableMap<String, MutableList<String>>>,
        sources: MutableMap<String, MutableMap<String, FieldSource>>,
        component: PedagogicalComponent,
        topic: String
    ) {
        when (component) {
            PedagogicalComponent.READING -> {
                fillStageDefaults(
                    stage = map["diagnostic"],
                    stageSources = sources["diagnostic"],
                    defaultAbil = "القدرة على استحضار المكتسبات القبلية وربطها بموضوع الدرس «$topic».",
                    defaultTeacher = "يطرح أسئلة تشخيصية لاستحضار مكتسبات المتعلمين القبلية وربطها بالدرس الجديد.",
                    defaultContent = "<u>التمهيد:</u> استحضار المكتسبات القبلية المتعلقة بموضوع «$topic».",
                    defaultEval = "تقويم مدى قدرة المتعلمين على استحضار المكتسبات السابقة والمشاركة في التمهيد."
                )
                fillStageDefaults(
                    stage = map["observation"],
                    stageSources = sources["observation"],
                    defaultAbil = "القدرة على ملاحظة العتبات النصية وصياغة فرضيات القراءة.",
                    defaultTeacher = "يوجه المتعلمين إلى ملاحظة العنوان والمؤشرات الخارجية وبناء فرضيات القراءة.",
                    defaultContent = "<u>العنوان:</u> دلالة العنوان وصياغة فرضيات القراءة لنص «$topic».",
                    defaultEval = "فحص وجاهة الفرضيات المصوغة ومدى دقة ملاحظة العتبات النصية."
                )
                fillStageDefaults(
                    stage = map["comprehension"],
                    stageSources = sources["comprehension"],
                    defaultAbil = "القدرة على قراءة النص قراءة سليمة واستخلاص مضمونه العام وأفكاره الأساسية.",
                    defaultTeacher = "يقرأ النص قراءة نموذجية، ويتخلل قراءات المتعلمين بشرح المفردات وطرح أسئلة الفهم.",
                    defaultContent = "<u>الفكرة العامة:</u> المعنى الإجمالي للنص وأفكاره الأساسية.",
                    defaultEval = "تقويم سلامة القراءة ومدى استيعاب المعاني الصريحة والضمنية للنص."
                )
                fillStageDefaults(
                    stage = map["analysis"],
                    stageSources = sources["analysis"],
                    defaultAbil = "القدرة على تحليل الحقول الدلالية والخصائص الفنية والقيم المتضمنة في النص.",
                    defaultTeacher = "يساعد المتعلمين على جرد الحقول المعجمية واستخراج الأساليب والصور الفنية وقيم النص.",
                    defaultContent = "<u>الحقول المعجمية والأساليب:</u> المعجم الدلالي والخصائص الفنية وقيم النص.",
                    defaultEval = "مدى قدرة المتعلمين على تصنيف المعجم واستخراج الأساليب والقيم بدقة."
                )
                fillStageDefaults(
                    stage = map["synthesis"],
                    stageSources = sources["synthesis"],
                    defaultAbil = "القدرة على تجميع معطيات النص في خلاصة تركيبية منسجمة وتمحيص الفرضيات.",
                    defaultTeacher = "يطالب المتعلمين بصياغة خلاصة تركيبية لمضامين النص وربطها بفرضيات الانطلاق.",
                    defaultContent = "<u>التركيب:</u> خلاصة جامعة لمضامين النص ومقاصده التربوية والفنية.",
                    defaultEval = "فحص جودة الخلاصة التركيبية ومدى تحقق فرضيات القراءة."
                )
                fillStageDefaults(
                    stage = map["investment"],
                    stageSources = sources["investment"],
                    defaultAbil = "القدرة على استثمار مكتسبات النص في وضعيات تواصلية وإنتاجية جديدة.",
                    defaultTeacher = "يكلف المتعلمين بإنجاز نشاط استثماري يربط النص بواقعهم المعيش.",
                    defaultContent = "<u>الاستثمار:</u> نشاط تعبيري أو بحثي يوظف مكتسبات درس «$topic».",
                    defaultEval = "تقويم الإنجازات الاستثمارية للمتعلمين وتصويب التعثرات."
                )
            }

            PedagogicalComponent.LANGUAGE -> {
                fillStageDefaults(
                    stage = map["lang_diagnostic"],
                    stageSources = sources["lang_diagnostic"],
                    defaultAbil = "القدرة على استحضار المكتسبات اللغوية السابقة ذات الصلة بالظاهرة.",
                    defaultTeacher = "يطرح أسئلة تقويمية لتشخيص المكتسبات اللغوية السابقة والتمهيد للدرس.",
                    defaultContent = "<u>التقويم التشخيصي:</u> استحضار القواعد الصرفية والتركيبية السابقة ذات الصلة.",
                    defaultEval = "تقويم مدى تذكر المتعلمين للقواعد اللغوية المكتسبة سابقاً."
                )
                fillStageDefaults(
                    stage = map["lang_observation"],
                    stageSources = sources["lang_observation"],
                    defaultAbil = "القدرة على ملاحظة أمثلة الانطلاق ورصد الظاهرة اللغوية وعزلها.",
                    defaultTeacher = "يدون أمثلة الانطلاق على السبورة ويطالب المتعلمين بقراءتها وملاحظة الكلمات المكتوبة بلون مغاير.",
                    defaultContent = "<u>أمثلة الانطلاق:</u> رصد الشواهد اللغوية لظاهرة «$topic».",
                    defaultEval = "فحص قدرة المتعلمين على تحديد الظاهرة اللغوية المستهدفة داخل الشواهد."
                )
                fillStageDefaults(
                    stage = map["lang_analysis"],
                    stageSources = sources["lang_analysis"],
                    defaultAbil = "القدرة على وصف الظاهرة اللغوية وتحليل خصائصها واستنباط أحكامها.",
                    defaultTeacher = "يستدرج المتعلمين عبر جدول توضيحي لوصف الظاهرة وتحليل بنيتها وشروطها.",
                    defaultContent = "<u>الوصف والتحليل:</u> دراسة خصائص ظاهرة «$topic» واستنباط القواعد الجزئية.",
                    defaultEval = "مراقبة مدى مشاركة المتعلمين في ملء الجدول التحليلي واستخلاص الاستنتاجات الجزئية."
                )
                fillStageDefaults(
                    stage = map["lang_synthesis"],
                    stageSources = sources["lang_synthesis"],
                    defaultAbil = "القدرة على تجميع الاستنتاجات الجزئية وصياغة القاعدة العامة للدرس.",
                    defaultTeacher = "يوجه المتعلمين إلى تجميع الاستنتاجات الجزئية لبناء القاعدة التركيبية الشاملة.",
                    defaultContent = "<u>الاستنتاج:</u> صياغة القاعدة العامة الشاملة لدرس «$topic».",
                    defaultEval = "تقويم قدرة المتعلمين على استيعاب القاعدة والتمثيل لها بجمل مفيدة."
                )
                fillStageDefaults(
                    stage = map["lang_application"],
                    stageSources = sources["lang_application"],
                    defaultAbil = "القدرة على توظيف القاعدة اللغوية في سياقات تطبيقية وإعرابية متنوعة.",
                    defaultTeacher = "يقترح تمارين تطبيقية وإعرابية ويشرف على التصحيح الجماعي والفردي.",
                    defaultContent = "<u>التطبيقات:</u> تمارين إعرابية وتحويلية لترسيخ ظاهرة «$topic».",
                    defaultEval = "فحص نسبة التحكم في الظاهرة اللغوية ودعم المتعثرين."
                )
            }

            PedagogicalComponent.EXPRESSION -> {
                fillStageDefaults(
                    stage = map["insha_acquisition"],
                    stageSources = sources["insha_acquisition"],
                    defaultAbil = "القدرة على تعرف المهارة الإنشائية وتحديد خصائصها وخطواتها المنهجية.",
                    defaultTeacher = "يعرض نص الانطلاق ويوجه المتعلمين لاستخلاص تعريف المهارة وخطواتها الإجرائية.",
                    defaultContent = "<u>أنشطة الاكتساب:</u> التعريف بمهارة «$topic» واستخلاص خطواتها المنهجية.",
                    defaultEval = "تقويم مدى استيعاب المتعلمين لعناصر المهارة وتصميمها المنهجي."
                )
                fillStageDefaults(
                    stage = map["insha_application"],
                    stageSources = sources["insha_application"],
                    defaultAbil = "القدرة على تطبيق خطوات المهارة الإنشائية جماعياً وثنائياً على نموذج تطبيقي.",
                    defaultTeacher = "يكتب نص الموضوع التطبيقي على السبورة ويشرف على التدريب الجماعي لتطبيق خطوات المهارة.",
                    defaultContent = "<u>أنشطة التطبيق:</u> نص الموضوع والتدرب على إنجاز مهارة «$topic».",
                    defaultEval = "مراقبة مدى التزام المتعلمين بخطوات المهارة أثناء التدريب التطبيقي."
                )
                fillStageDefaults(
                    stage = map["insha_production"],
                    stageSources = sources["insha_production"],
                    defaultAbil = "القدرة على الإنتاج الفردي المستقل وفق معايير المهارة الإنشائية.",
                    defaultTeacher = "يقدم وضعية الانطلاق ويوضح المطلوب ويرافق المتعلمين أثناء التحرير الفردي في المسودة.",
                    defaultContent = "<u>أنشطة الإنتاج:</u> وضعية الانطلاق والتحرير الفردي للموضوع في مسودة.",
                    defaultEval = "تقويم مدى قدرة المتعلم على التخطيط والربط والتحرير السليم."
                )
                fillStageDefaults(
                    stage = map["insha_correction"],
                    stageSources = sources["insha_correction"],
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
        stageSources: MutableMap<String, FieldSource>?,
        defaultAbil: String,
        defaultTeacher: String,
        defaultContent: String,
        defaultEval: String
    ) {
        if (stage == null) return
        if (stage["abil"]?.isEmpty() == true) {
            stage["abil"]?.add(defaultAbil)
            stageSources?.put("abil", FieldSource.DEFAULT)
        }
        if (stage["teacher"]?.isEmpty() == true) {
            stage["teacher"]?.add(defaultTeacher)
            stageSources?.put("teacher", FieldSource.DEFAULT)
        }
        if (stage["content"]?.isEmpty() == true) {
            stage["content"]?.add(defaultContent)
            stageSources?.put("content", FieldSource.DEFAULT)
        }
        if (stage["eval"]?.isEmpty() == true) {
            stage["eval"]?.add(defaultEval)
            stageSources?.put("eval", FieldSource.DEFAULT)
        }
    }
}
