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
        var topic = if (fallbackTopic.contains("جذاذة") || fallbackTopic.contains("المختار") || fallbackTopic.contains("3اع") || fallbackTopic.contains("3إع")) {
            DocumentFileReader.cleanTopicFromFilename(fallbackTopic).ifBlank { fallbackTopic }
        } else {
            fallbackTopic
        }

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
        var currentRowStageKey: String? = null
        var currentField = "content"
        var hasEnteredStages = false
        val stagesWithExplicitFields = mutableSetOf<String>()
        val unclassifiedLines = mutableListOf<UnclassifiedLine>()

        for ((lineNumber, line) in indexedNonEmptyLines) {
            val cleanLine = DocumentFileReader.stripFormattingTags(line).trim()
            if (cleanLine.isEmpty()) continue

            if (cleanLine == "[نهاية الجدول]") {
                currentStageKey = null
                currentRowStageKey = null
                continue
            }

            val strippedHeaderLine = cleanLine
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
            if ((cleanLine.startsWith("[مرحلة:") || cleanLine.startsWith("[حصة:")) && cleanLine.endsWith("]")) {
                val inner = cleanLine.substringAfter(":").removeSuffix("]").trim()
                // Ignore if the stage cell is actually a header metadata cell
                if (!hasEnteredStages && isHeaderIsolationLine(inner)) {
                    extractHeaderMetadataInto(
                        strippedHeaderLine = inner,
                        onTopic = { topic = it },
                        onLevel = { level = it },
                        onUnit = { unit = it },
                        onDomain = { domain = it },
                        onComponent = { component = it },
                        onDuration = { duration = it },
                        onMaterials = { materials = it },
                        onCommunicative = { communicative = it },
                        onMethodological = { methodological = it },
                        onCultural = { cultural = it }
                    )
                    continue
                }
                val matchedKey = matchStageName(inner, detectedComponent)
                    ?: detectIntraCellStageSwitch(inner, detectedComponent)
                if (matchedKey != null && stageMap.containsKey(matchedKey)) {
                    hasEnteredStages = true
                    currentStageKey = matchedKey
                    currentRowStageKey = matchedKey
                    currentField = "content"
                } else {
                    // Check if inner is a generic session header like "الحصة الأولى" or "الحصة الثانية"
                    val sessionStage = matchGenericSessionStage(inner, detectedComponent)
                    if (sessionStage != null && stageMap.containsKey(sessionStage)) {
                        hasEnteredStages = true
                        currentStageKey = sessionStage
                        currentRowStageKey = sessionStage
                        currentField = "content"
                    } else {
                        // Unrecognized stage row in table
                        currentStageKey = null
                        currentRowStageKey = null
                        if (inner.isNotBlank() && !isHeaderIsolationLine(inner)) {
                            unclassifiedLines.add(UnclassifiedLine(lineNumber, inner))
                        }
                    }
                }
                continue
            }

            // 2. Header Isolation (عزل الترويسة):
            // Any pre-lesson table or paragraphs containing header keywords are extracted ONLY to fill JadhadhaInfo
            // and strictly prevented from entering stages, activities, or unclassifiedLines.
            if (!hasEnteredStages || isStrictHeaderPrefix(strippedHeaderLine)) {
                val matchedHeader = extractHeaderMetadataInto(
                    strippedHeaderLine = strippedHeaderLine,
                    hasEnteredStages = hasEnteredStages,
                    onTopic = { topic = it },
                    onLevel = { level = it },
                    onUnit = { unit = it },
                    onDomain = { domain = it },
                    onComponent = { component = it },
                    onDuration = { duration = it },
                    onMaterials = { materials = it },
                    onCommunicative = { communicative = it },
                    onMethodological = { methodological = it },
                    onCultural = { cultural = it }
                )
                if (matchedHeader || (!hasEnteredStages && isHeaderIsolationLine(strippedHeaderLine))) {
                    continue
                }
            }

            // 3. Field Detection within Stage (Abilities, Teacher, Content/Learner, Evaluation)
            val detectedField = detectField(strippedHeaderLine)
            if (detectedField != null) {
                hasEnteredStages = true
                // When switching between explicit table columns (abil, teacher, content, eval) of a row,
                // anchor abil/teacher/eval to the row's base stage if one was set, while allowing content to split stages.
                val baseKey = if (detectedField in setOf("abil", "teacher", "eval") && currentRowStageKey != null) {
                    currentRowStageKey!!
                } else {
                    currentStageKey ?: currentRowStageKey ?: stageMetaList.first().key
                }
                currentStageKey = baseKey
                currentField = detectedField
                stagesWithExplicitFields.add(baseKey)
                val rest = extractAfterColon(strippedHeaderLine)
                if (rest.isNotBlank()) {
                    val splitKey = if (currentField == "content") {
                        detectIntraCellStageSwitch(rest, detectedComponent)
                    } else null
                    val effectiveStageKey = if (splitKey != null && stageMap.containsKey(splitKey)) {
                        currentStageKey = splitKey
                        stagesWithExplicitFields.add(splitKey)
                        splitKey
                    } else {
                        baseKey
                    }
                    addClassifiedItem(
                        stageMap = stageMap,
                        stageSources = stageSources,
                        stageKey = effectiveStageKey,
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

            // 4. Intra-Cell Stage Splitting (التقسيم المرحلي الداخلي):
            // While reading lines inside a content cell or unstructured stage block, inspect each line:
            // if it starts with a new stage heading (Stages 1..6), switch the active stage pointer immediately.
            if (hasEnteredStages && (currentField == "content" || currentStageKey !in stagesWithExplicitFields)) {
                val intraStageKey = detectIntraCellStageSwitch(cleanLine, detectedComponent)
                if (intraStageKey != null && stageMap.containsKey(intraStageKey)) {
                    val wasExplicit = currentStageKey != null && currentStageKey in stagesWithExplicitFields
                    currentStageKey = intraStageKey
                    if (wasExplicit) {
                        stagesWithExplicitFields.add(intraStageKey)
                    }
                    // If the line is purely a standalone stage title (without colon/details), don't duplicate the title as content
                    if (isPureStageHeadingOnly(cleanLine, detectedComponent)) {
                        continue
                    }
                }
            }

            // 4b. Check natural Stage Header in text (when before stages or in unstructured text outside explicit table fields)
            if (currentStageKey == null || currentStageKey !in stagesWithExplicitFields) {
                val stageMatch = detectStageWithRemainder(cleanLine, detectedComponent)
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
            }

            // 5. Classify into active stage or record as UnclassifiedLine
            val activeStageKey = currentStageKey
            if (activeStageKey == null) {
                if (isHeaderIsolationLine(strippedHeaderLine)) {
                    continue
                }
                // Line appears before any stage header or after table end
                unclassifiedLines.add(UnclassifiedLine(lineNumber = lineNumber, text = cleanLine))
                continue
            }

            val hasExplicitFields = activeStageKey in stagesWithExplicitFields
            if (!hasExplicitFields && isUnclassifiedInUnstructuredStage(cleanLine, stageMap[activeStageKey])) {
                unclassifiedLines.add(UnclassifiedLine(lineNumber = lineNumber, text = cleanLine))
                continue
            }

            addClassifiedItem(
                stageMap = stageMap,
                stageSources = stageSources,
                stageKey = activeStageKey,
                currentField = currentField,
                hasExplicitFields = hasExplicitFields,
                rawItem = cleanLine
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
        unclassifiedLines: List<UnclassifiedLine>,
        aiClassifiedLinesCount: Int = 0,
        aiRefinedFieldsCount: Int = 0,
        isAiDelegated: Boolean = false
    ): ParseReport {
        val stageMetaList = StageCatalog.getStagesForComponent(plan.componentType)
        val stageCounts = stageMetaList.associate { sm ->
            val stData = plan.stages[sm.key] ?: StageData()
            val fields = listOf("abil", "teacher", "content", "eval")
            val fileCount = fields.count { f -> stData.sourceFor(f) == FieldSource.FILE }
            val defaultCount = fields.count { f -> stData.sourceFor(f) == FieldSource.DEFAULT }
            val aiCount = fields.count { f -> stData.sourceFor(f) == FieldSource.AI }
            val userCount = fields.count { f -> stData.sourceFor(f) == FieldSource.USER }
            sm.key to StageSourceCount(
                stageKey = sm.key,
                stageTitle = sm.title,
                fileCount = fileCount,
                defaultCount = defaultCount,
                aiCount = aiCount,
                userCount = userCount
            )
        }
        return ParseReport(
            detectedComponent = plan.componentType,
            isComponentDefaulted = isComponentDefaulted,
            usedKeywordsForComponent = !isComponentDefaulted,
            stageSourceCounts = stageCounts,
            unclassifiedLines = unclassifiedLines,
            aiClassifiedLinesCount = aiClassifiedLinesCount,
            aiRefinedFieldsCount = aiRefinedFieldsCount,
            isAiDelegated = isAiDelegated
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
                s == "جذاذة تربوية" ||
                s.startsWith("جذاذة تربوية ") ||
                s.startsWith("جذاذة بيداغوجية") ||
                s.startsWith("وزارة التربية الوطنية") ||
                s.startsWith("الأكاديمية الجهوية") ||
                s.startsWith("المديرية الإقليمية") ||
                s.startsWith("الثانوية الإعدادية") ||
                s.startsWith("الثانوية التأهيلية") ||
                s.startsWith("السنة الدراسية") ||
                s.startsWith("بطاقة تخطيط درس") ||
                s.startsWith("بطاقة تقنية") ||
                s.startsWith("مادة: اللغة العربية") ||
                s.startsWith("المادة: اللغة العربية") ||
                (s.startsWith("مكون:") && s.contains("مادة:"))
    }

    /**
     * Header Isolation check: matches pre-lesson header lines/cells that contain
     * ("جذاذة تربوية", "الفئة المستهدفة", "المكون", "الموضوع", "الوسائل الديدكتيكية",
     * "الكفاية التواصلية", "الكفاية المنهجية", "الهدف") so they never leak into stages or unclassified lines.
     */
    private fun isHeaderIsolationLine(s: String): Boolean {
        val norm = normalizeHeaderToken(s)
        return norm.contains("جذاذه تربويه") ||
                norm.contains("الفئه المستهدفه") ||
                norm.startsWith("المكون ") || norm == "المكون" ||
                norm.startsWith("الموضوع ") || norm == "الموضوع" ||
                norm.startsWith("عنوان الدرس") || norm.startsWith("موضوع الدرس") ||
                norm.contains("الوسائل الديدكتيكيه") ||
                norm.contains("الوسائل الديداكتيكيه") ||
                norm.contains("الوسائل التعليميه") ||
                norm.contains("الكفايه التواصليه") ||
                norm.contains("الكفايه المنهجيه") ||
                norm.contains("الكفايه الثقافيه") ||
                norm.contains("الكفايات المتوخاه") ||
                norm.contains("الكفايات المستهدفه") ||
                norm.startsWith("الهدف ") || norm == "الهدف" ||
                norm.startsWith("الاهداف ") || norm == "الاهداف" ||
                norm.startsWith("الفئه ") ||
                norm.startsWith("المرجع ") ||
                norm.startsWith("الدعامات ") ||
                norm.startsWith("المده الزمنيه") ||
                norm.startsWith("الغلاف الزمني") ||
                norm.startsWith("الحصيص الزمني")
    }

    private fun extractHeaderMetadataInto(
        strippedHeaderLine: String,
        hasEnteredStages: Boolean = false,
        onTopic: (String) -> Unit,
        onLevel: (String) -> Unit,
        onUnit: (String) -> Unit,
        onDomain: (String) -> Unit,
        onComponent: (String) -> Unit,
        onDuration: (String) -> Unit,
        onMaterials: (String) -> Unit,
        onCommunicative: (String) -> Unit,
        onMethodological: (String) -> Unit,
        onCultural: (String) -> Unit
    ): Boolean {
        var matchedHeader = false

        if (strippedHeaderLine.startsWith("موضوع الدرس") ||
            strippedHeaderLine.startsWith("عنوان الدرس") ||
            strippedHeaderLine.startsWith("الموضوع:") ||
            strippedHeaderLine.startsWith("الموضوع :") ||
            strippedHeaderLine.startsWith("الموضوع-") ||
            strippedHeaderLine.startsWith("النص القرائي:") ||
            strippedHeaderLine.startsWith("الظاهرة اللغوية:") ||
            strippedHeaderLine.startsWith("المهارة المستهدفة") ||
            (!hasEnteredStages && strippedHeaderLine.startsWith("المهارة:"))
        ) {
            val extracted = extractAfterColon(strippedHeaderLine)
            if (extracted.isNotBlank() && extracted.length in 2..80) {
                onTopic(extracted.removeSurrounding("«", "»").removeSurrounding("\"", "\"").trim())
                matchedHeader = true
            }
        } else if (strippedHeaderLine.startsWith("المستوى") ||
            strippedHeaderLine.startsWith("السلك") ||
            strippedHeaderLine.startsWith("الفئة المستهدفة") ||
            strippedHeaderLine.startsWith("الفئة المستهدفه")
        ) {
            val extracted = extractAfterColon(strippedHeaderLine)
            if (extracted.isNotBlank()) {
                onLevel(extracted)
                matchedHeader = true
            }
        } else if (strippedHeaderLine.startsWith("الوحدة") || strippedHeaderLine.startsWith("الدورة")) {
            val colonIdx = strippedHeaderLine.indexOf(':')
            if (colonIdx > 0) {
                val beforeColon = strippedHeaderLine.substring(0, colonIdx).trim()
                val afterColon = strippedHeaderLine.substring(colonIdx + 1).trim()
                val unitWords = beforeColon.removePrefix("الوحدة").removePrefix("الدورة").trim()
                if (unitWords.isNotBlank() && afterColon.isNotBlank()) {
                    onUnit(beforeColon)
                    onDomain(afterColon.removePrefix("المجال:").removePrefix("المجال").trim().ifBlank { afterColon })
                    matchedHeader = true
                } else if (afterColon.isNotBlank()) {
                    onUnit(afterColon)
                    matchedHeader = true
                }
            }
        } else if (strippedHeaderLine.startsWith("المجال")) {
            val extracted = extractAfterColon(strippedHeaderLine)
            if (extracted.isNotBlank()) {
                onDomain(extracted)
                matchedHeader = true
            }
        } else if (strippedHeaderLine.startsWith("المكون") || strippedHeaderLine.startsWith("المكوّن")) {
            val extracted = extractAfterColon(strippedHeaderLine)
            if (extracted.isNotBlank()) {
                onComponent(extracted)
                matchedHeader = true
            }
        } else if (strippedHeaderLine.startsWith("المدة") ||
            strippedHeaderLine.startsWith("الغلاف الزمني") ||
            strippedHeaderLine.startsWith("الحصيص الزمني") ||
            strippedHeaderLine.startsWith("مدة الإنجاز")
        ) {
            val extracted = extractAfterColon(strippedHeaderLine)
            if (extracted.isNotBlank()) {
                onDuration(extracted)
                matchedHeader = true
            }
        } else if (strippedHeaderLine.startsWith("الوسائل") ||
            strippedHeaderLine.startsWith("الدعامات") ||
            strippedHeaderLine.startsWith("المرجع")
        ) {
            val extracted = extractAfterColon(strippedHeaderLine)
            if (extracted.isNotBlank()) {
                onMaterials(extracted)
                matchedHeader = true
            }
        } else if (strippedHeaderLine.contains("كفاية تواصلية") || strippedHeaderLine.startsWith("الكفاية التواصلية")) {
            val extracted = extractAfterColon(strippedHeaderLine)
            if (extracted.isNotBlank()) onCommunicative(extracted)
            matchedHeader = true
        } else if (strippedHeaderLine.contains("كفاية منهجية") || strippedHeaderLine.startsWith("الكفاية المنهجية")) {
            val extracted = extractAfterColon(strippedHeaderLine)
            if (extracted.isNotBlank()) onMethodological(extracted)
            matchedHeader = true
        } else if (strippedHeaderLine.contains("كفاية ثقافية") ||
            strippedHeaderLine.contains("كفاية معرفية") ||
            strippedHeaderLine.startsWith("الكفاية الثقافية") ||
            strippedHeaderLine.startsWith("الكفاية الاستراتيجية") ||
            strippedHeaderLine.startsWith("القدرة الخاصة بالدرس") ||
            (!hasEnteredStages && (strippedHeaderLine.startsWith("الهدف:") || strippedHeaderLine.startsWith("الهدف العام:") || strippedHeaderLine.startsWith("الأهداف:")))
        ) {
            val extracted = extractAfterColon(strippedHeaderLine)
            if (extracted.isNotBlank()) onCultural(extracted)
            matchedHeader = true
        }

        return matchedHeader
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
        // Bulleted items (-, •, +, *, 1.), underlined items, or nested table rows ([جدول: ...]) are structured content
        if (trimmed.startsWith("-") ||
            trimmed.startsWith("•") ||
            trimmed.startsWith("+") ||
            trimmed.startsWith("*") ||
            trimmed.startsWith("<u>") ||
            trimmed.startsWith("[جدول:") ||
            trimmed.matches(Regex("^\\d+[.\\-)].*"))
        ) {
            return false
        }

        // If it matches a known pedagogical verb/prefix for abil, teacher, learner/content, or eval, it's classified
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
     * into explicit stage and field blocks, inspecting the first two rows of the lesson table
     * together for split headers and isolating pre-lesson header tables.
     */
    private fun preprocessTabularText(raw: String): String {
        val rawLines = raw.lines()
        val tabLinesCount = rawLines.count { it.count { ch -> ch == '\t' } >= 2 }
        val pipeLinesCount = rawLines.count { line ->
            val trimmed = line.trim()
            !trimmed.startsWith("[جدول:") && trimmed.count { ch -> ch == '|' } >= 2
        }
        if (tabLinesCount < 2 && pipeLinesCount < 2) return raw

        val delimiter = if (tabLinesCount >= 2) '\t' else '|'
        val sb = StringBuilder()

        fun splitRowCols(line: String): List<String> {
            val trimmed = line.trim()
            return if (delimiter == '|') {
                trimmed.removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
            } else {
                line.split('\t').map { it.trim() }
            }
        }

        // Active column role mapping by column index: "stage", "abil", "teacher", "content", "eval"
        var colRoleMap: Map<Int, String>? = null
        var i = 0
        while (i < rawLines.size) {
            val line = rawLines[i]
            val trimmedLine = line.trim()
            if (trimmedLine.startsWith("[جدول:")) {
                sb.appendLine(line)
                i++
                continue
            }

            val rawCols = splitRowCols(line)
            if (rawCols.size >= 2) {
                // Skip markdown table separator rows like |---|---|---|
                if (rawCols.all { col -> col.isEmpty() || col.all { ch -> ch == '-' || ch == ':' || ch == ' ' } }) {
                    i++
                    continue
                }

                // 1. Check if this row (and optionally the next row) is the lesson table header (1 or 2 rows)
                val nextCols = if (i + 1 < rawLines.size) {
                    val nextLine = rawLines[i + 1].trim()
                    if (!nextLine.startsWith("[جدول:")) splitRowCols(rawLines[i + 1]) else null
                } else null

                val twoRowHeader = matchTwoRowHeaderColumns(rawCols, nextCols)
                if (twoRowHeader != null) {
                    colRoleMap = twoRowHeader.first
                    i += if (twoRowHeader.second) 2 else 1
                    continue
                }

                // 2. If we haven't entered the lesson table yet and this row looks like a pre-lesson header table row,
                // emit its cells as isolated header metadata lines rather than lesson stage rows.
                if (colRoleMap == null && rawCols.any { isHeaderIsolationLine(it) }) {
                    var cIdx = 0
                    while (cIdx < rawCols.size) {
                        val cell = rawCols[cIdx].trim()
                        if (cell.isNotEmpty()) {
                            if (!cell.contains(':') && cIdx + 1 < rawCols.size && rawCols[cIdx + 1].isNotBlank() && !rawCols[cIdx + 1].contains(':')) {
                                sb.appendLine("$cell: ${rawCols[cIdx + 1].trim()}")
                                cIdx += 2
                                continue
                            } else {
                                sb.appendLine(cell)
                            }
                        }
                        cIdx++
                    }
                    i++
                    continue
                }

                if (rawCols.size >= 3) {
                    val effectiveMap = colRoleMap ?: inferFallbackColumnMap(rawCols)
                    val stageIndices = effectiveMap.entries.filter { it.value == "stage" }.map { it.key }
                    val abilIndices = effectiveMap.entries.filter { it.value == "abil" }.map { it.key }
                    val teacherIndices = effectiveMap.entries.filter { it.value == "teacher" }.map { it.key }
                    val contentIndices = effectiveMap.entries.filter { it.value == "content" }.map { it.key }
                    val evalIndices = effectiveMap.entries.filter { it.value == "eval" }.map { it.key }

                    fun appendMergedCells(headerLabel: String, indices: List<Int>) {
                        val combinedLines = indices
                            .mapNotNull { idx -> rawCols.getOrNull(idx)?.takeIf { it.isNotBlank() } }
                            .flatMap { cellText -> cellText.lines().map { it.trim() }.filter { it.isNotEmpty() } }
                        if (combinedLines.isEmpty()) return
                        sb.appendLine(headerLabel)
                        combinedLines.forEach { cellLine ->
                            val formatted = if (
                                cellLine.startsWith("-") ||
                                cellLine.startsWith("+") ||
                                cellLine.startsWith("•") ||
                                cellLine.startsWith("*") ||
                                cellLine.startsWith("<u>") ||
                                cellLine.startsWith("[جدول:")
                            ) {
                                cellLine
                            } else {
                                "- $cellLine"
                            }
                            sb.appendLine(formatted)
                        }
                    }

                    val stageVal = stageIndices.mapNotNull { idx -> rawCols.getOrNull(idx)?.takeIf { it.isNotBlank() } }
                        .joinToString(" - ")
                    if (stageVal.isNotBlank()) {
                        sb.appendLine("[مرحلة: $stageVal]")
                    }
                    appendMergedCells("القدرات المستهدفة:", abilIndices)
                    appendMergedCells("أنشطة الأستاذ:", teacherIndices)
                    appendMergedCells("أنشطة المتعلم:", contentIndices)
                    appendMergedCells("التقويم:", evalIndices)
                    i++
                    continue
                }
            }
            sb.appendLine(line)
            i++
        }
        return sb.toString()
    }

    /**
     * Inspects row 1 and optionally row 2 of a table together to support split headers
     * (e.g. "الأنشطة التعليمية التعلمية" split into "أنشطة المدرس" and "أنشطة المتعلمين",
     * or "التقويم والدعم" split into "مؤشرات التقويم" and "الدعم") and returns
     * Pair(columnIndexToRoleMap, consumedSecondRow) or null if not a header row.
     */
    internal fun matchTwoRowHeaderColumns(
        row1: List<String>,
        row2: List<String>?
    ): Pair<Map<Int, String>, Boolean>? {
        val map1 = matchHeaderColumns(row1)
        val hasStageInRow1 = map1.containsValue("stage")
        val hasOtherInRow1 = map1.values.any { it != "stage" }
        if (map1.size < 2 || (!hasStageInRow1 && !hasOtherInRow1)) {
            return null
        }

        if (row2 != null && row2.size >= 2) {
            val map2 = matchHeaderColumns(row2)
            // Second row is a sub-header if it contains recognized column headers (like teacher/content/eval)
            // and is not a normal data row with long sentences
            val row2LooksLikeSubHeader = map2.isNotEmpty() &&
                row2.all { it.length <= 45 } &&
                (map2.containsValue("teacher") || map2.containsValue("content") || map2.containsValue("eval") || map2.containsValue("abil")) &&
                !looksLikeStageCell(row2.firstOrNull().orEmpty()) &&
                !looksLikeStageCell(row2.lastOrNull().orEmpty())

            if (row2LooksLikeSubHeader) {
                val maxCols = maxOf(row1.size, row2.size)
                val combined = mutableMapOf<Int, String>()
                for (c in 0 until maxCols) {
                    val r2Role = map2[c]
                    val r1Role = map1[c]
                    val chosen = r2Role ?: r1Role
                    if (chosen != null) {
                        combined[c] = chosen
                    }
                }
                if (combined.size >= 2) {
                    return combined to true
                }
            }
        }

        return if (map1.size >= 2 && (hasStageInRow1 || map1.size >= 3)) {
            map1 to false
        } else {
            null
        }
    }

    /**
     * Dynamically matches column header titles from any table format (3, 4, or 9 columns, RTL or LTR):
     * - عمود المحتوى: ("محتوى", "مضامين", "سير الدرس", "الأمثلة", "النصوص")
     * - عمود أنشطة المدرس: ("أنشطة المدرس", "أنشطة الأستاذ", "توجيهات", "مهام المدرس")
     * - عمود أنشطة المتعلم: ("أنشطة المتعلم", "أنشطة المتعلمين", "مهام المتعلم")
     * - عمود التقويم: ("التقويم", "الدعم", "مؤشرات التقويم") — supports multiple eval/indicator columns
     * - عمود القدرات: ("القدرات", "الأهداف", "المهارات")
     * - عمود المراحل: ("المراحل", "الخطوات", "خطوات القراءة", "المقاطع")
     */
    internal fun matchHeaderColumns(cols: List<String>): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        var hasStage = false

        for ((idx, rawHeader) in cols.withIndex()) {
            val norm = normalizeHeaderToken(rawHeader)
            if (norm.isEmpty() || norm.length > 60) continue

            val isCombinedActivities = (norm.contains("التعليميه") && norm.contains("التعلميه")) ||
                (norm.contains("المدرس") && norm.contains("المتعلم")) ||
                (norm.contains("الاستاذ") && norm.contains("المتعلم"))

            val role = when {
                // 1. عمود المراحل: ("المراحل", "الخطوات", "خطوات القراءة", "المقاطع")
                !hasStage && (
                    norm.contains("المراحل") || norm.contains("مراحل") ||
                        norm.contains("الخطوات") || norm.contains("خطوات القراءه") ||
                        norm.contains("المقاطع") || norm.contains("مقاطع") ||
                        norm == "الحصص" || norm == "الحصه"
                    ) -> {
                    hasStage = true
                    "stage"
                }

                // 2. عمود التقويم: ("التقويم", "الدعم", "مؤشرات التقويم") — multiple columns allowed and merged
                norm.contains("التقويم") || norm.contains("تقويم") ||
                    norm.contains("الدعم") || norm.contains("مؤشرات") -> "eval"

                // 3. عمود القدرات: ("القدرات", "الأهداف", "المهارات")
                norm.contains("القدرات") || norm.contains("قدرات") ||
                    norm.contains("الاهداف") || norm.contains("اهداف") ||
                    norm.contains("الكفايات") ||
                    norm.contains("المهارات") || norm.contains("مهارات") -> "abil"

                // 4. Combined activities column -> "content" (sub-lines inside will split teacher/learner by verb prefix)
                isCombinedActivities -> "content"

                // 5. عمود أنشطة المدرس: ("أنشطة المدرس", "أنشطة الأستاذ", "توجيهات", "مهام المدرس")
                norm.contains("انشطه المدرس") || norm.contains("انشطه الاستاذ") ||
                    norm.contains("توجيهات") || norm.contains("مهام المدرس") ||
                    norm.contains("دور المدرس") || norm.contains("دور الاستاذ") ||
                    norm.contains("الاستاذ") || norm.contains("المدرس") ||
                    (norm.contains("التعليميه") && !norm.contains("التعلميه")) -> "teacher"

                // 6. عمود أنشطة المتعلم & عمود المحتوى:
                // ("أنشطة المتعلم", "أنشطة المتعلمين", "مهام المتعلم", "محتوى", "مضامين", "سير الدرس", "الأمثلة", "النصوص")
                norm.contains("انشطه المتعلم") || norm.contains("انشطه المتعلمين") ||
                    norm.contains("مهام المتعلم") || norm.contains("دور المتعلم") ||
                    norm.contains("المتعلم") || norm.contains("التلميذ") ||
                    norm.contains("محتوي") || norm.contains("المحتوي") ||
                    norm.contains("مضامين") || norm.contains("المضامين") ||
                    norm.contains("سير الدرس") ||
                    norm.contains("الامثله") || norm.contains("امثله") ||
                    norm.contains("النصوص") || norm.contains("نصوص الانطلاق") ||
                    norm.contains("التعلميه") || norm.contains("التعلمات") -> "content"

                else -> null
            }
            if (role != null) {
                result[idx] = role
            }
        }
        return result
    }

    private fun inferFallbackColumnMap(cols: List<String>): Map<Int, String> {
        val n = cols.size
        if (n < 3) return emptyMap()
        // Check if last column is a stage name (LTR table without explicit header row)
        val firstLooksLikeStage = looksLikeStageCell(cols.first())
        val lastLooksLikeStage = looksLikeStageCell(cols.last())
        val isLtr = lastLooksLikeStage && !firstLooksLikeStage

        return if (!isLtr) {
            when {
                n >= 5 -> mapOf(0 to "stage", 1 to "abil", 2 to "teacher", 3 to "content", 4 to "eval")
                n == 4 -> mapOf(0 to "stage", 1 to "teacher", 2 to "content", 3 to "eval")
                else -> mapOf(0 to "stage", 1 to "content", 2 to "eval")
            }
        } else {
            when {
                n >= 5 -> mapOf(n - 1 to "stage", n - 2 to "abil", n - 3 to "teacher", n - 4 to "content", 0 to "eval")
                n == 4 -> mapOf(3 to "stage", 2 to "teacher", 1 to "content", 0 to "eval")
                else -> mapOf(2 to "stage", 1 to "content", 0 to "eval")
            }
        }
    }

    private fun looksLikeStageCell(text: String): Boolean {
        val norm = normalizeHeaderToken(text)
        if (norm.isEmpty() || norm.length > 45) return false
        return norm.contains("تشخيص") || norm.contains("تمهيد") ||
            norm.contains("ملاحظه") || norm.contains("تاطير") ||
            norm.contains("فهم") || norm.contains("تحليل") ||
            norm.contains("تركيب") || norm.contains("استثمار") ||
            norm.contains("استنتاج") || norm.contains("تطبيق") ||
            norm.contains("اكتساب") || norm.contains("انتاج") ||
            norm.contains("تصحيح")
    }

    private fun normalizeHeaderToken(s: String): String {
        return s.replace(Regex("[\u0640\u064B-\u065F\u0670]"), "")
            .replace(Regex("[أإآٱ]"), "ا")
            .replace("ى", "ي")
            .replace("ة", "ه")
            .replace(Regex("[^\\w\\p{L}]+"), " ")
            .trim()
    }

    private fun isStrictHeaderPrefix(s: String): Boolean {
        return s.startsWith("موضوع الدرس:") ||
                s.startsWith("عنوان الدرس:") ||
                s.startsWith("المهارة المستهدفة:") ||
                s.startsWith("المستوى الدراسي:") ||
                s.startsWith("المستوى:") ||
                s.startsWith("الفئة المستهدفة:") ||
                s.startsWith("الوحدة:") ||
                s.startsWith("المجال:") ||
                s.startsWith("المكوّن:") ||
                s.startsWith("المكون:") ||
                s.startsWith("الغلاف الزمني:") ||
                s.startsWith("المدة الزمنية:") ||
                s.startsWith("الحصيص الزمني:") ||
                s.startsWith("الوسائل الديداكتيكية:") ||
                s.startsWith("الوسائل الديدكتيكية:") ||
                s.startsWith("كفاية تواصلية:") ||
                s.startsWith("الكفاية التواصلية:") ||
                s.startsWith("كفاية منهجية:") ||
                s.startsWith("الكفاية المنهجية:") ||
                s.startsWith("كفاية ثقافية") ||
                s.startsWith("الكفاية الثقافية")
    }

    private fun isTableHeaderLine(s: String): Boolean {
        val norm = normalizeHeaderToken(s)
        val hasStage = norm.contains("المراحل") || norm.contains("المقاطع") || norm.contains("الخطوات")
        val hasOtherCol = norm.contains("القدرات") || norm.contains("الاستاذ") || norm.contains("المدرس") ||
            norm.contains("المتعلم") || norm.contains("التقويم") || norm.contains("المحتوي") || norm.contains("مضامين")
        return s == "المقاطع والمراحل" || s == "المراحل" || (hasStage && hasOtherCol)
    }

    private fun addClassifiedItem(
        stageMap: MutableMap<String, MutableMap<String, MutableList<String>>>,
        stageSources: MutableMap<String, MutableMap<String, FieldSource>>,
        stageKey: String,
        currentField: String,
        hasExplicitFields: Boolean,
        rawItem: String
    ) {
        val clean = cleanBullet(DocumentFileReader.stripFormattingTags(rawItem))
        if (clean.isBlank()) return

        val targetField = if (hasExplicitFields) {
            // Even when inside a "content" cell (e.g., 3-column table or combined activities column),
            // if a paragraph begins with an explicit teacher verb or ability prefix, route it semantically.
            if (currentField == "content") {
                val semanticRole = inferStrictSemanticRole(clean)
                if (semanticRole == "teacher" || semanticRole == "abil") semanticRole else currentField
            } else {
                currentField
            }
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
        "حالات الاسم", "حكمه الإعرابي", "الشاعر", "الكاتب",
        "الإيضاحات اللغوية", "دراسة العنوان", "الصورة المرفقة", "بداية النص", "بناء الفرضيات",
        "الفرضيات المصوغة", "تمحيص الفرضيات", "ملامح التفسير", "عناصر رسالة النص", "لغة النص",
        "خلاصة تركيبية", "الاستثمار والبحث", "امتدادات"
    )

    private fun formatContentSubLabel(clean: String): String {
        if (clean.contains("<u>") || clean.startsWith("[جدول:")) return clean
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

    // ────────────────────── Semantic Verb-Prefix Classification ──────────────────────

    private val TEACHER_VERB_PREFIXES = listOf(
        "يطالب", "يطرح", "يوجه", "يساعد", "يقرأ الأستاذ", "يدعم", "يحفز",
        "يقرأ المدرس", "يشرف", "يدعو", "يمهد", "يناقش", "يسجل", "يدون",
        "يستدرج", "يكلف", "يوزع", "يعرض الأستاذ", "يعرض المدرس", "يوضح",
        "يشرح", "ينظم", "يرسم", "يقدم", "يقترح", "يطلب من", "يدير حوار"
    )

    private val LEARNER_VERB_PREFIXES = listOf(
        "يعرضون", "يجيبون", "يؤطرون", "يصوغ", "يقرأ المتعلمون", "يستخرجون",
        "يفهمون", "يلخص", "ينجزون", "يمحصون",
        "يتأملون", "يصفون", "يلاحظون", "يستنتجون", "يقرأون", "يقرؤون",
        "يشاركون", "يبنون", "يصوغون", "يركبون", "يعربون", "يستحضر المتعلم",
        "يستحضرون", "يلخصون", "يتفاعل المتعلم", "يتابعون"
    )

    private val ABILITY_PREFIXES = listOf(
        "القدرة على", "قدرة المتعلم على", "تنمية مهارة", "استدعاء المتعلم",
        "أن يكون المتعلم", "أن يتعرف", "أن يتمكن", "أن يكتشف", "أن يستنتج",
        "أن يحلل", "أن يدرك", "أن يميز", "أن يوظف", "أن يصوغ", "أن يقرأ",
        "أن يستخلص", "أن يكتسب", "أن يستحضر", "تمكين المتعلم"
    )

    /**
     * Checks strict semantic prefixes (teacher verbs, learner verbs, ability prefixes)
     * as specified in the hybrid architecture.
     */
    private fun inferStrictSemanticRole(clean: String): String? {
        val plain = DocumentFileReader.stripFormattingTags(clean)
            .removePrefix("أنشطة المدرس:")
            .removePrefix("أنشطة الأستاذ:")
            .trim()
        if (clean.startsWith("أنشطة المتعلم:") || clean.startsWith("أنشطة المتعلمين:")) {
            return "content"
        }
        return when {
            ABILITY_PREFIXES.any { plain.startsWith(it) } -> "abil"
            LEARNER_VERB_PREFIXES.any { plain.startsWith(it) } -> "content"
            TEACHER_VERB_PREFIXES.any { plain.startsWith(it) } -> "teacher"
            else -> null
        }
    }

    private fun inferFieldFromUnstructuredLine(clean: String, fallbackField: String): String {
        val plain = DocumentFileReader.stripFormattingTags(clean).trim()
        val strict = inferStrictSemanticRole(plain)
        if (strict != null) return strict

        return when {
            plain.startsWith("يعرض ") -> "teacher"

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

    // ────────────────────── Intra-Cell Stage Splitting (Stages 1..6) ──────────────────────

    private val STAGE_1_KEYWORDS = listOf(
        "تشخيص", "تمهيد", "استحضار المكتسبات", "الإعدادات القبلية", "تقويم تشخيصي"
    )

    private val STAGE_2_KEYWORDS = listOf(
        "التأطير والملاحظة", "ملاحظة النص", "تأطير النص", "صاحب النص", "مصدر النص",
        "دراسة العنوان", "الصورة المرفقة", "بداية النص", "بناء الفرضيات",
        "الفرضيات المصوغة", "أمثلة الانطلاق", "نص الانطلاق"
    )

    private val STAGE_3_KEYWORDS = listOf(
        "فهم النص", "القراءة النموذجية", "القراءة التوجيهية", "الإيضاحات اللغوية",
        "الشروح اللغوية", "الفكرة العامة", "الأفكار الأساسية", "تمحيص الفرضيات"
    )

    private val STAGE_4_KEYWORDS = listOf(
        "تحليل النص", "القراءة التحليلية", "الحقول المعجمية", "الحقول الدلالية",
        "ملامح التفسير", "عناصر رسالة النص", "لغة النص", "القيم",
        "الملاحظة والوصف", "التحليل والمناقشة"
    )

    private val STAGE_5_KEYWORDS = listOf(
        "التركيب", "خلاصة تركيبية", "استنتاج", "القاعدة"
    )

    private val STAGE_6_KEYWORDS = listOf(
        "الاستثمار", "الاستثمار والبحث", "امتدادات", "تطبيقات", "الإنتاج"
    )

    /**
     * Strips leading bullets, numbering, and Arabic ordinals ("أولاً:", "ثانياً:", "1-", etc.)
     * so we can inspect if a line inside a content cell starts with a new stage heading.
     */
    private fun stripLeadingOrdinalsAndBullets(raw: String): String {
        var s = DocumentFileReader.stripFormattingTags(raw).trim()
        s = s.replace(Regex("^[•\\-+*▪▫◦]+\\s*"), "")
        s = s.replace(Regex("^[0-9٠-٩]+[.\\-):/]+\\s*"), "")
        s = s.replace(
            Regex("^(أولا|أولاً|ثانيا|ثانياً|ثالثا|ثالثاً|رابعا|رابعاً|خامسا|خامساً|سادسا|سادساً|سابعا|سابعاً)\\s*[:\\-–]?\\s*"),
            ""
        )
        s = s.replace(Regex("^(مرحلة|مقطع|خطوة|حصة)\\s+"), "")
        return s.trim()
    }

    private fun lineStartsWithStageKeyword(normalizedCandidate: String, keywords: List<String>): Boolean {
        for (kw in keywords) {
            val normKw = normalizeHeaderToken(kw)
            if (normKw.isEmpty()) continue
            val normCand = normalizeHeaderToken(normalizedCandidate)
            // Also check without leading "ال" on first word so "تمهيد" matches "التمهيد" and vice versa
            val normCandNoAl = normCand.removePrefix("ال")
            val normKwNoAl = normKw.removePrefix("ال")
            if (normCand == normKw ||
                normCand.startsWith("$normKw ") ||
                normCandNoAl == normKwNoAl ||
                normCandNoAl.startsWith("$normKwNoAl ")
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Inspects a line inside a learning content cell to determine if it starts with a new stage heading
     * (Stages 1..6) and returns the corresponding stage key for [component], or null if it does not.
     */
    internal fun detectIntraCellStageSwitch(line: String, component: PedagogicalComponent): String? {
        val candidate = stripLeadingOrdinalsAndBullets(line)
        if (candidate.isEmpty() || candidate.startsWith("[جدول:")) return null

        // Extract heading prefix (before colon/dash if present, or first 45 chars if standalone heading)
        val colonIdx = candidate.indexOf(':')
        val headerPrefix = when {
            colonIdx in 1..48 -> candidate.substring(0, colonIdx).trim()
            candidate.length <= 48 -> candidate
            else -> return null
        }

        val stageNumber = when {
            lineStartsWithStageKeyword(headerPrefix, STAGE_6_KEYWORDS) -> 6
            lineStartsWithStageKeyword(headerPrefix, STAGE_5_KEYWORDS) -> 5
            lineStartsWithStageKeyword(headerPrefix, STAGE_4_KEYWORDS) -> 4
            lineStartsWithStageKeyword(headerPrefix, STAGE_3_KEYWORDS) -> 3
            lineStartsWithStageKeyword(headerPrefix, STAGE_2_KEYWORDS) -> 2
            lineStartsWithStageKeyword(headerPrefix, STAGE_1_KEYWORDS) -> 1
            else -> null
        }

        if (stageNumber == null) return null

        return when (component) {
            PedagogicalComponent.READING -> when (stageNumber) {
                1 -> "diagnostic"
                2 -> "observation"
                3 -> "comprehension"
                4 -> "analysis"
                5 -> "synthesis"
                6 -> "investment"
                else -> null
            }
            PedagogicalComponent.LANGUAGE -> when (stageNumber) {
                1 -> "lang_diagnostic"
                2 -> "lang_observation"
                3 -> "lang_observation"
                4 -> "lang_analysis"
                5 -> "lang_synthesis"
                6 -> "lang_application"
                else -> null
            }
            PedagogicalComponent.EXPRESSION -> when (stageNumber) {
                1, 2, 3, 4 -> "insha_acquisition"
                5 -> if (normalizeHeaderToken(headerPrefix).contains("تطبيق")) "insha_application" else "insha_acquisition"
                6 -> when {
                    normalizeHeaderToken(headerPrefix).contains("انتاج") -> "insha_production"
                    normalizeHeaderToken(headerPrefix).contains("تطبيق") -> "insha_application"
                    else -> "insha_production"
                }
                else -> null
            }
        }
    }

    private fun isPureStageHeadingOnly(line: String, component: PedagogicalComponent): Boolean {
        val stripped = stripLeadingOrdinalsAndBullets(line)
        val colonIdx = stripped.indexOf(':')
        if (colonIdx >= 0 && colonIdx < stripped.length - 1) {
            // Has content after colon (e.g., "التركيب: خلاصة جامعة...") -> keep the line so its content is added
            return false
        }
        val cleanHeading = stripped.removeSuffix(":").trim()
        return cleanHeading.length <= 35 && (
            matchStageName(cleanHeading, component) != null ||
                detectIntraCellStageSwitch(cleanHeading, component) != null
            )
    }

    private fun matchGenericSessionStage(inner: String, component: PedagogicalComponent): String? {
        val norm = normalizeHeaderToken(inner)
        return when (component) {
            PedagogicalComponent.READING -> when {
                norm.contains("الحصه الاولي") || norm == "الحصه 1" -> "diagnostic"
                norm.contains("الحصه الثانيه") || norm == "الحصه 2" -> "analysis"
                else -> null
            }
            PedagogicalComponent.LANGUAGE -> when {
                norm.contains("الحصه الاولي") || norm == "الحصه 1" -> "lang_diagnostic"
                norm.contains("الحصه الثانيه") || norm == "الحصه 2" -> "lang_application"
                else -> null
            }
            PedagogicalComponent.EXPRESSION -> when {
                norm.contains("الحصه الاولي") || norm == "الحصه 1" -> "insha_acquisition"
                norm.contains("الحصه الثانيه") || norm == "الحصه 2" -> "insha_application"
                norm.contains("الحصه الثالثه") || norm == "الحصه 3" -> "insha_production"
                norm.contains("الحصه الرابعه") || norm == "الحصه 4" -> "insha_correction"
                else -> null
            }
        }
    }

    /**
     * Returns Pair(detectedComponent, matchedByKeywords).
     * If no explicit component keywords were found, returns (READING, false).
     */
    private fun detectComponentWithKeywords(raw: String, lines: List<String>): Pair<PedagogicalComponent, Boolean> {
        val sample = (lines.take(35).joinToString(" ") + " " + raw.take(1000)).lowercase()
        return when {
            sample.contains("درس لغوي") || sample.contains("الدرس اللغوي") || sample.contains("ظاهرة لغوية") ||
                    sample.contains("ملاحظة الظاهرة") || sample.contains("ملاحظة الأمثلة") || sample.contains("الوصف والتحليل") ||
                    sample.contains("اسم الفاعل") || sample.contains("صيغ المبالغة") || sample.contains("أسلوب الاختصاص") ||
                    sample.contains("اسم المفعول") || sample.contains("اسما الزمان والمكان") || sample.contains("اسم الزمان") ||
                    sample.contains("اسم المكان") || sample.contains("اسم الآلة") || sample.contains("اسم التفضيل") ||
                    sample.contains("الممنوع من الصرف") || sample.contains("الإضافة") || sample.contains("النسبة") ||
                    sample.contains("أسلوب التعجب") || sample.contains("أسلوب المدح") || sample.contains("أسلوب النداء") ||
                    sample.contains("أسلوب الاستفهام") || sample.contains("القاعدة التركيبية") ->
                PedagogicalComponent.LANGUAGE to true

            sample.contains("تعبير وإنشاء") || sample.contains("التعبير والإنشاء") || sample.contains("أنشطة الاكتساب") ||
                    sample.contains("أنشطة التطبيق") || sample.contains("أنشطة الإنتاج") || sample.contains("أنشطة التصحيح") ||
                    sample.contains("حصة الاكتساب") || sample.contains("كتابة حكاية") || sample.contains("كتابة يوميات") ||
                    sample.contains("وصف رحلة") || sample.contains("وصف شاخص") || sample.contains("إصدار حكم") ||
                    sample.contains("خطاب الحجاج") || sample.contains("السيرة الذاتية") ->
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

        // 2. Do NOT treat bulleted lines (-, +, •, *), underlined content items (<u>...</u>), table rows ([جدول:...]),
        // semantic ability/teacher/learner sentences, or pure field headers (e.g. التقويم والدعم:) as stage headers
        if (trimmed.startsWith("-") ||
            trimmed.startsWith("+") ||
            trimmed.startsWith("•") ||
            trimmed.startsWith("*") ||
            trimmed.startsWith("<u>") ||
            trimmed.startsWith("[جدول:") ||
            inferStrictSemanticRole(trimmed) != null ||
            (detectField(trimmed) != null && extractAfterColon(trimmed).isBlank())
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

        // Do NOT confuse content sub-labels (like الفكرة العامة:, الحقول المعجمية:, العنوان:) with standalone stage headers
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
        val t = text.replace(":", " ").replace("-", " ").replace(Regex("\\s+"), " ").trim()
        return when (component) {
            PedagogicalComponent.READING -> when {
                t.contains("تشخيص المكتسبات") || t.contains("تقويم تشخيصي") || t.contains("مراقبة الإعداد") ||
                        t.contains("استحضار المكتسبات") || t.contains("الإعدادات القبلية") ||
                        t == "التمهيد" || t == "تمهيد" || t.startsWith("تمهيد ") || t.contains("تشخيص") -> "diagnostic"
                t.contains("ملاحظة النص") || t.contains("تأطير النص") || t.contains("التأطير والملاحظة") ||
                        t.contains("القراءة الاستكشافية") || t.contains("عتبات النص") ||
                        t == "الملاحظة والتأطير" || t == "ملاحظة وتأطير" || t == "الملاحظة" || t == "التأطير" -> "observation"
                t.contains("فهم النص") || t.contains("القراءة التوجيهية") || t.contains("القراءة النموذجية") ||
                        t.contains("فهم المضامين") || t == "الفهم" -> "comprehension"
                t.contains("القراءة التحليلية") || t.contains("تحليل النص") || t.contains("الدراسة المعجمية") ||
                        t.contains("التحليل والمناقشة") || t == "التحليل" -> "analysis"
                t.contains("التركيب") || t.contains("الخلاصة التركيبية") || t.contains("خلاصة تركيبية") ||
                        t.contains("القراءة التركيبية") || t == "تركيب" -> "synthesis"
                t.contains("الاستثمار") || t.contains("الاستثمار والبحث") || t.contains("الأنشطة الاستثمارية") ||
                        t.contains("الامتداد") || t.contains("امتدادات") || t == "استثمار" -> "investment"
                else -> null
            }

            PedagogicalComponent.LANGUAGE -> when {
                t.contains("الوصف والتحليل") || t.contains("الملاحظة والوصف") || t.contains("البناء والتحليل") ||
                        t.contains("العرض والتحليل") || t.contains("التحليل والمناقشة") ||
                        t.contains("تحليل الأمثلة") || t.contains("تحليل الامثلة") || t.contains("تحليل الظاهرة") ||
                        t.contains("مناقشة الأمثلة") || t.contains("تفكيك الظاهرة") ||
                        t.contains("التحليل") || t == "الوصف" ||
                        (t.contains("المقطع الوسطي") && !t.contains("ملاحظة") && !t.contains("استنتاج")) -> "lang_analysis"
                t.contains("ملاحظة الظاهرة") || t.contains("ملاحظة الأمثلة") || t.contains("ملاحظة الامثلة") ||
                        t.contains("قراءة الأمثلة") || t.contains("عزل الظاهرة") || t.contains("أمثلة الانطلاق") ||
                        t.contains("امثلة الانطلاق") || t.contains("شواهد الانطلاق") || t == "الملاحظة" ||
                        t.contains("ملاحظة") -> "lang_observation"
                t.contains("تشخيص") || t.contains("تقويم تشخيصي") || t.contains("استحضار المكتسبات") ||
                        t == "تمهيد" || t == "التمهيد" || t.contains("المقطع التمهيدي") -> "lang_diagnostic"
                t.contains("الاستنتاج") || t.contains("استنتاج") || t.contains("القاعدة التركيبية") ||
                        t.contains("القاعدة") || t.contains("خلاصة") || t.contains("التركيب") -> "lang_synthesis"
                t.contains("التطبيقات") || t.contains("تطبيق") || t.contains("تقويم إجمالي") ||
                        t.contains("تمارين") || t.contains("المقطع النهائي") || t.contains("المقطع الختامي") ||
                        t.contains("استثمار") || t.contains("الاستثمار") -> "lang_application"
                else -> null
            }

            PedagogicalComponent.EXPRESSION -> when {
                t.contains("اكتساب") || t.contains("الحصة الأولى") || t.contains("التعريف بالمهارة") ||
                        t.contains("تقديم المهارة") || t.contains("نص الانطلاق") -> "insha_acquisition"
                t.contains("تطبيق") || t.contains("الحصة الثانية") || t.contains("التدرب على المهارة") -> "insha_application"
                t.contains("إنتاج") || t.contains("انتاج") || t.contains("الحصة الثالثة") || t.contains("التحرير") -> "insha_production"
                t.contains("تصحيح") || t.contains("الحصة الرابعة") || t.contains("التصحيح والدعم") || t.contains("معالجة وتصحيح") -> "insha_correction"
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
                    label == "مؤشرات التقويم والدعم" || label == "التقويم المرحلي" || label == "معايير التقويم" ||
                    label == "الدعم" -> "eval"

            label == "أنشطة المدرس" || label == "أنشطة الأستاذ" || label == "الأنشطة التعليمية" ||
                    label == "مهام المدرس" || label == "دور المدرس" || label == "توجيهات" -> "teacher"

            label == "القدرات المستهدفة" || label == "القدرات" || label == "الأهداف" || label == "الأهداف التعلمية" ||
                    label == "الأهداف التعليمية" || label == "الكفايات المستهدفة" || label == "المهارات" -> "abil"

            label == "المحتوى" || label == "المحتوى الديداكتيكي" || label == "الأنشطة التعلمية" ||
                    label == "الأنشطة التعليمية التعلمية" ||
                    label == "المحتوى الديداكتيكي والأنشطة التعلمية" || label == "أنشطة المتعلم" ||
                    label == "أنشطة المتعلمين" || label == "مهام المتعلم" || label == "المضامين" ||
                    label == "سير الدرس" || label == "الأمثلة" || label == "النصوص" -> "content"

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
