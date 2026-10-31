package com.example.extractor

import com.example.model.ContentIntegrityReport
import com.example.model.ContentUnitStatus
import com.example.model.DocumentElementType
import com.example.model.ExtractedUnit
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
import com.example.model.TextModificationRecord
import com.example.model.UnclassifiedLine
import com.example.model.UnitLocation

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
     * containing:
     * - Verbatim copy of the original extracted text ([ParseReport.originalRawText])
     * - Independent traceable content units ([ParseReport.extractedUnits]) with unique IDs and [UnitLocation]
     * - Unclassified lines preserved for user review ([ParseReport.unclassifiedLines])
     * - Comprehensive [ContentIntegrityReport] ([ParseReport.integrityReport])
     */
    fun parseWithReport(
        raw: String,
        fallbackTopic: String = "درس جديد",
        forcedComponent: PedagogicalComponent? = null
    ): ParseResult {
        // 1. Retain verbatim copy of original extracted text and traceable units before any preprocessing
        val recordedExtraction = DocumentFileReader.getRecordedExtraction(raw)
        val originalRawText = recordedExtraction?.rawOriginalText?.takeIf { it.isNotEmpty() } ?: raw
        val initialUnits = recordedExtraction?.units?.takeIf { it.isNotEmpty() } ?: buildTraceableUnitsFromRaw(raw)
        val unextractableSegments = recordedExtraction?.unextractableSegments.orEmpty()

        val mutableUnits = initialUnits.toMutableList()
        val consumedUnitIds = mutableSetOf<String>()

        fun claimMatchingUnit(
            text: String,
            fallbackLineNumber: Int,
            newStatus: ContentUnitStatus,
            stageKey: String? = null,
            fieldKey: String? = null,
            finalText: String? = null
        ): ExtractedUnit {
            val cleanTarget = DocumentFileReader.stripFormattingTags(text).trim()
            // First try exact verbatim match among unconsumed units by unique unit ID
            var matchIdx = -1
            for (i in mutableUnits.indices) {
                val u = mutableUnits[i]
                if (u.id !in consumedUnitIds && u.verbatimText == cleanTarget) {
                    matchIdx = i
                    break
                }
            }
            if (matchIdx < 0) {
                for (i in mutableUnits.indices) {
                    val u = mutableUnits[i]
                    if (u.id !in consumedUnitIds && u.verbatimText.trim() == cleanTarget) {
                        matchIdx = i
                        break
                    }
                }
            }
            // Next try normalized bullet/prefix match or substring match (e.g., when a single line had "Header: Remainder")
            if (matchIdx < 0 && cleanTarget.isNotEmpty()) {
                for (i in mutableUnits.indices) {
                    val u = mutableUnits[i]
                    if (u.id !in consumedUnitIds) {
                        val uText = u.verbatimText.trim()
                        if (cleanBullet(uText) == cleanBullet(cleanTarget) ||
                            uText.endsWith(cleanTarget) ||
                            cleanTarget.endsWith(uText)
                        ) {
                            matchIdx = i
                            break
                        }
                    }
                }
            }
            if (matchIdx >= 0) {
                val existing = mutableUnits[matchIdx]
                consumedUnitIds.add(existing.id)
                val updated = existing.copy(
                    status = newStatus,
                    assignedStageKey = stageKey ?: existing.assignedStageKey,
                    assignedField = fieldKey ?: existing.assignedField,
                    finalClassifiedText = finalText ?: existing.finalClassifiedText
                )
                mutableUnits[matchIdx] = updated
                return updated
            }
            // Synthesize a traceable unit if not already in initialUnits
            val newId = "unit_${mutableUnits.size + 1}"
            consumedUnitIds.add(newId)
            val created = ExtractedUnit(
                id = newId,
                verbatimText = cleanTarget,
                elementType = DocumentElementType.PARAGRAPH,
                location = UnitLocation(
                    sourcePart = "raw_text",
                    elementIndex = mutableUnits.size,
                    paragraphIndex = mutableUnits.size,
                    lineNumber = fallbackLineNumber
                ),
                status = newStatus,
                assignedStageKey = stageKey,
                assignedField = fieldKey,
                finalClassifiedText = finalText
            )
            mutableUnits.add(created)
            return created
        }

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

        var level = "السنة الثالثة إعدادي"
        var unit = "الأول"
        var domain = when (detectedComponent) {
            PedagogicalComponent.LANGUAGE -> "القيم الإسلامية"
            PedagogicalComponent.EXPRESSION -> "القيم الإسلامية"
            PedagogicalComponent.READING -> "القيم الوطنية والإنسانية"
        }
        var component = detectedComponent.title
        var duration = detectedComponent.defaultDuration
        var materials = detectedComponent.defaultMaterials
        var reference = "المختار في اللغة العربية"
        var teacherName = "محمد العابد"
        var schoolName = "مدرسة أنوال (السلك الإعدادي)"
        var communicative = when (detectedComponent) {
            PedagogicalComponent.LANGUAGE -> "أن يكون المتعلم قادراً على توظيف الظاهرة اللغوية في سياقات تواصلية مختلفة تعبيراً وفهماً."
            PedagogicalComponent.EXPRESSION -> "تمكين المتعلم من التعبير والتواصل بلغة عربية سليمة عبر توظيف خطوات المهارة."
            PedagogicalComponent.READING -> "تمكين المتعلم من التواصل بلغة عربية سليمة، مستثمراً تعلمات الفصل ومطالعاته الحرة."
        }
        var methodological = when (detectedComponent) {
            PedagogicalComponent.LANGUAGE -> "تمكين المتعلم من ملاحظة الظاهرة اللغوية ووصفها وتحليلها واستنتاج القاعدة الضابطة لها."
            PedagogicalComponent.EXPRESSION -> "تمكين المتعلم من آليات تصميم الموضوع وتنظيم عناصره وفق خطوات منهجية متدرجة."
            PedagogicalComponent.READING -> "تمكين المتعلم من استعمال أدوات القراءة المنهجية لفهم النص السردي وتحليله، وتركيب عناصره، واستثماره."
        }
        var cultural = when (detectedComponent) {
            PedagogicalComponent.LANGUAGE -> "إغناء الرصيد اللغوي والمعرفي للمتعلم بالظواهر الصرفية والتركيبية وتطبيقاتها."
            PedagogicalComponent.EXPRESSION -> "تنمية الرصيد الثقافي والقيمي للمتعلم للتعبير عن تجاربه اليومية ومشاعره الذاتية."
            PedagogicalComponent.READING -> "يغني المتعلم رصيده الثقافي بمعطيات تاريخية ووطنية تتصل بمرحلة الاستعمار والكفاح من أجل الاستقلال."
        }

        // Available stages for this component
        val stageMetaList = StageCatalog.getStagesForComponent(detectedComponent)
        val stageMap = mutableMapOf<String, MutableMap<String, MutableList<String>>>()
        val stageUnitMap = mutableMapOf<String, MutableMap<String, MutableList<ExtractedUnit>>>()
        val stageSources = mutableMapOf<String, MutableMap<String, FieldSource>>()
        stageMetaList.forEach { sm ->
            stageMap[sm.key] = mutableMapOf(
                "eval" to mutableListOf(),
                "content" to mutableListOf(),
                "teacher" to mutableListOf(),
                "abil" to mutableListOf()
            )
            stageUnitMap[sm.key] = mutableMapOf(
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
        var activeSessionNumber = 1
        val stagesWithExplicitFields = mutableSetOf<String>()
        val unclassifiedLines = mutableListOf<UnclassifiedLine>()
        val modifications = mutableListOf<TextModificationRecord>()
        val duplicateUnits = mutableListOf<ExtractedUnit>()
        val duplicateItems = mutableListOf<String>()

        // Track any standalone natural stage header so if no content follows for that stage, it is never swallowed
        var pendingNaturalStageHeader: Pair<Int, String>? = null
        var pendingNaturalStageKey: String? = null
        var pendingNaturalStageItemCount = 0

        fun flushPendingNaturalStageHeaderIfEmpty() {
            val pending = pendingNaturalStageHeader
            if (pending != null && pendingNaturalStageItemCount == 0) {
                val (pLineNum, pText) = pending
                val uUnit = claimMatchingUnit(
                    text = pText,
                    fallbackLineNumber = pLineNum,
                    newStatus = ContentUnitStatus.NEEDS_REVIEW
                )
                unclassifiedLines.add(
                    UnclassifiedLine(
                        lineNumber = uUnit.location.lineNumber.takeIf { it > 0 } ?: pLineNum,
                        text = uUnit.verbatimText,
                        unitId = uUnit.id,
                        verbatimText = uUnit.verbatimText,
                        location = uUnit.location
                    )
                )
            }
            pendingNaturalStageHeader = null
            pendingNaturalStageKey = null
            pendingNaturalStageItemCount = 0
        }

        fun recordUnclassifiedLine(lineNumber: Int, rawLineText: String) {
            val uUnit = claimMatchingUnit(
                text = rawLineText,
                fallbackLineNumber = lineNumber,
                newStatus = ContentUnitStatus.NEEDS_REVIEW
            )
            unclassifiedLines.add(
                UnclassifiedLine(
                    lineNumber = uUnit.location.lineNumber.takeIf { it > 0 } ?: lineNumber,
                    text = rawLineText,
                    unitId = uUnit.id,
                    verbatimText = uUnit.verbatimText,
                    location = uUnit.location
                )
            )
        }

        fun recordClassifiedLine(
            lineNumber: Int,
            stageKey: String,
            currentFieldName: String,
            hasExplicitFields: Boolean,
            rawItem: String,
            originalFullLine: String = rawItem
        ) {
            val verbatimClean = DocumentFileReader.stripFormattingTags(rawItem).trim()
            val strippedForCheck = cleanBullet(verbatimClean)
            if (verbatimClean.isBlank() || isDurationOrSegmentNoise(strippedForCheck)) return

            val targetField = if (hasExplicitFields) {
                if (currentFieldName == "content") {
                    val semanticRole = inferStrictSemanticRole(strippedForCheck)
                    if (semanticRole == "teacher" || semanticRole == "abil") semanticRole else currentFieldName
                } else {
                    currentFieldName
                }
            } else {
                inferFieldFromUnstructuredLine(strippedForCheck, currentFieldName)
            }

            // Preserve original text without unjustified modification
            val formattedItem = if (targetField == "content") {
                formatContentSubLabel(verbatimClean)
            } else {
                verbatimClean
            }

            val claimedUnit = claimMatchingUnit(
                text = originalFullLine,
                fallbackLineNumber = lineNumber,
                newStatus = ContentUnitStatus.CLASSIFIED,
                stageKey = stageKey,
                fieldKey = targetField,
                finalText = formattedItem
            )

            if (claimedUnit.verbatimText != formattedItem && originalFullLine.trim() == rawItem.trim()) {
                modifications.add(
                    TextModificationRecord(
                        unitId = claimedUnit.id,
                        originalText = claimedUnit.verbatimText,
                        finalText = formattedItem,
                        location = claimedUnit.location
                    )
                )
            }

            val targetList = stageMap[stageKey]?.get(targetField)
            val targetUnitList = stageUnitMap[stageKey]?.get(targetField)
            if (targetList != null && targetUnitList != null) {
                val matchingPrevUnits = targetUnitList.filter {
                    (it.finalClassifiedText ?: it.verbatimText) == formattedItem
                }
                val isUnintendedDuplicate = matchingPrevUnits.any { prevUnit ->
                    isUnintendedSameLocationDuplicate(prevUnit, claimedUnit)
                }
                if (isUnintendedDuplicate) {
                    duplicateUnits.add(claimedUnit)
                    if (!duplicateItems.contains(formattedItem)) {
                        duplicateItems.add(formattedItem)
                    }
                } else {
                    targetList.add(formattedItem)
                    targetUnitList.add(claimedUnit)
                }
            }
            stageSources[stageKey]?.put(targetField, FieldSource.FILE)

            if (pendingNaturalStageKey == stageKey) {
                pendingNaturalStageItemCount++
            }
        }

        for ((lineNumber, line) in indexedNonEmptyLines) {
            val cleanLine = DocumentFileReader.stripFormattingTags(line).trim()
            if (cleanLine.isEmpty()) continue

            if (cleanLine == "[نهاية الجدول]") {
                flushPendingNaturalStageHeaderIfEmpty()
                currentStageKey = null
                currentRowStageKey = null
                continue
            }

            // Detect session transitions from any line or header
            detectSessionTransition(cleanLine)?.let { newSession ->
                activeSessionNumber = newSession
            }

            val strippedHeaderLine = cleanLine
                .removePrefix("•")
                .removePrefix("-")
                .removePrefix("+")
                .removePrefix("*")
                .trim()

            // Skip official administrative header banners or standalone duration/segment noise
            if (isAdministrativeBannerLine(strippedHeaderLine) || isDurationOrSegmentNoise(strippedHeaderLine)) {
                claimMatchingUnit(
                    text = cleanLine,
                    fallbackLineNumber = lineNumber,
                    newStatus = ContentUnitStatus.CLASSIFIED,
                    fieldKey = "administrative_or_duration"
                )
                continue
            }

            // 1. Check explicit Stage Marker [مرحلة: ...] or [حصة: ...]
            if ((cleanLine.startsWith("[مرحلة:") || cleanLine.startsWith("[حصة:")) && cleanLine.endsWith("]")) {
                flushPendingNaturalStageHeaderIfEmpty()
                val inner = cleanLine.substringAfter(":").removeSuffix("]").trim()
                detectSessionTransition(inner)?.let { newSession ->
                    activeSessionNumber = newSession
                }
                if (isDurationOrSegmentNoise(inner)) {
                    claimMatchingUnit(inner, lineNumber, ContentUnitStatus.CLASSIFIED, fieldKey = "duration_noise")
                    continue
                }
                // Ignore if the stage cell is actually a header metadata cell
                if (!hasEnteredStages) {
                    val matchedMeta = extractHeaderMetadataInto(
                        strippedHeaderLine = inner,
                        onTopic = { topic = it },
                        onLevel = { level = it },
                        onUnit = { unit = it },
                        onDomain = { domain = it },
                        onComponent = { component = it },
                        onDuration = { duration = it },
                        onMaterials = { materials = it },
                        onReference = { reference = it },
                        onTeacherName = { teacherName = it },
                        onSchoolName = { schoolName = it },
                        onCommunicative = { communicative = it },
                        onMethodological = { methodological = it },
                        onCultural = { cultural = it }
                    )
                    if (matchedMeta || isStandaloneHeaderLabelOnly(inner)) {
                        claimMatchingUnit(inner, lineNumber, ContentUnitStatus.CLASSIFIED, fieldKey = "header_metadata")
                        continue
                    }
                }
                val hasComprehension = stageMap["comprehension"]?.values?.any { it.isNotEmpty() } == true
                val matchedKey = matchStageName(inner, detectedComponent, activeSessionNumber, hasComprehension)
                    ?: detectIntraCellStageSwitch(inner, detectedComponent, activeSessionNumber, hasComprehension)
                if (matchedKey != null && stageMap.containsKey(matchedKey)) {
                    hasEnteredStages = true
                    currentStageKey = matchedKey
                    currentRowStageKey = matchedKey
                    currentField = "content"
                    claimMatchingUnit(inner, lineNumber, ContentUnitStatus.CLASSIFIED, stageKey = matchedKey, fieldKey = "stage_header")
                } else {
                    // Check if inner is a generic session header like "الحصة الأولى" or "الحصة الثانية"
                    val sessionStage = matchGenericSessionStage(inner, detectedComponent)
                    if (sessionStage != null && stageMap.containsKey(sessionStage)) {
                        hasEnteredStages = true
                        currentStageKey = sessionStage
                        currentRowStageKey = sessionStage
                        currentField = "content"
                        claimMatchingUnit(inner, lineNumber, ContentUnitStatus.CLASSIFIED, stageKey = sessionStage, fieldKey = "session_header")
                    } else if (!cleanLine.startsWith("[حصة:")) {
                        // Unrecognized stage row in table -> preserve in unclassifiedLines
                        currentStageKey = null
                        currentRowStageKey = null
                        if (inner.isNotBlank() && !isStandaloneHeaderLabelOnly(inner)) {
                            recordUnclassifiedLine(lineNumber, inner)
                        }
                    } else {
                        claimMatchingUnit(inner, lineNumber, ContentUnitStatus.CLASSIFIED, fieldKey = "session_banner")
                    }
                }
                continue
            }

            // 2. Header Isolation (عزل الترويسة):
            // Pre-lesson metadata lines with valid values or standalone header labels fill JadhadhaInfo,
            // while any non-matching line is NEVER dropped and proceeds to unclassifiedLines.
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
                    onReference = { reference = it },
                    onTeacherName = { teacherName = it },
                    onSchoolName = { schoolName = it },
                    onCommunicative = { communicative = it },
                    onMethodological = { methodological = it },
                    onCultural = { cultural = it }
                )
                if (matchedHeader || (!hasEnteredStages && isStandaloneHeaderLabelOnly(strippedHeaderLine))) {
                    claimMatchingUnit(cleanLine, lineNumber, ContentUnitStatus.CLASSIFIED, fieldKey = "header_metadata")
                    continue
                }
            }

            // 3. Field Detection within Stage (Abilities, Teacher, Content/Learner, Evaluation)
            val detectedField = detectField(strippedHeaderLine)
            if (detectedField != null) {
                hasEnteredStages = true
                val baseKey = if (detectedField in setOf("abil", "teacher", "eval") && currentRowStageKey != null) {
                    currentRowStageKey!!
                } else {
                    currentStageKey ?: currentRowStageKey ?: stageMetaList.first().key
                }
                currentStageKey = baseKey
                currentField = detectedField
                stagesWithExplicitFields.add(baseKey)
                val rest = extractAfterColon(strippedHeaderLine)
                if (rest.isNotBlank() && !isDurationOrSegmentNoise(rest)) {
                    val hasComprehension = stageMap["comprehension"]?.values?.any { it.isNotEmpty() } == true
                    val splitKey = if (currentField == "content") {
                        detectIntraCellStageSwitch(rest, detectedComponent, activeSessionNumber, hasComprehension)
                    } else null
                    val effectiveStageKey = if (splitKey != null && stageMap.containsKey(splitKey)) {
                        currentStageKey = splitKey
                        stagesWithExplicitFields.add(splitKey)
                        splitKey
                    } else {
                        baseKey
                    }
                    recordClassifiedLine(
                        lineNumber = lineNumber,
                        stageKey = effectiveStageKey,
                        currentFieldName = currentField,
                        hasExplicitFields = true,
                        rawItem = rest,
                        originalFullLine = cleanLine
                    )
                }
                continue
            }

            // Skip raw table header lines if pasted directly
            if (isTableHeaderLine(strippedHeaderLine)) {
                claimMatchingUnit(cleanLine, lineNumber, ContentUnitStatus.CLASSIFIED, fieldKey = "table_header")
                continue
            }

            // 4. Intra-Cell Stage Splitting (التقسيم المرحلي الداخلي):
            val hasComprehensionNow = stageMap["comprehension"]?.values?.any { it.isNotEmpty() } == true
            if (hasEnteredStages && (currentField == "content" || currentStageKey !in stagesWithExplicitFields)) {
                val intraStageKey = detectIntraCellStageSwitch(cleanLine, detectedComponent, activeSessionNumber, hasComprehensionNow)
                if (intraStageKey != null && stageMap.containsKey(intraStageKey)) {
                    flushPendingNaturalStageHeaderIfEmpty()
                    val wasExplicit = currentStageKey != null && currentStageKey in stagesWithExplicitFields
                    currentStageKey = intraStageKey
                    if (wasExplicit) {
                        stagesWithExplicitFields.add(intraStageKey)
                    }
                    // If the line is purely a standalone stage title (without colon/details), mark unit as stage_header
                    if (isPureStageHeadingOnly(cleanLine, detectedComponent, activeSessionNumber, hasComprehensionNow)) {
                        if (!wasExplicit) {
                            pendingNaturalStageHeader = lineNumber to cleanLine
                            pendingNaturalStageKey = intraStageKey
                            pendingNaturalStageItemCount = stageMap[intraStageKey]?.values?.sumOf { it.size } ?: 0
                        } else {
                            claimMatchingUnit(cleanLine, lineNumber, ContentUnitStatus.CLASSIFIED, stageKey = intraStageKey, fieldKey = "stage_header")
                        }
                        continue
                    }
                }
            }

            // 4b. Check natural Stage Header in text (when before stages or in unstructured text outside explicit table fields)
            if (currentStageKey == null || currentStageKey !in stagesWithExplicitFields) {
                val stageMatch = detectStageWithRemainder(cleanLine, detectedComponent, activeSessionNumber, hasComprehensionNow)
                if (stageMatch != null && stageMap.containsKey(stageMatch.first)) {
                    flushPendingNaturalStageHeaderIfEmpty()
                    hasEnteredStages = true
                    currentStageKey = stageMatch.first
                    currentField = "content"
                    val remainder = stageMatch.second
                    if (!remainder.isNullOrBlank() && !isDurationOrSegmentNoise(remainder)) {
                        recordClassifiedLine(
                            lineNumber = lineNumber,
                            stageKey = stageMatch.first,
                            currentFieldName = currentField,
                            hasExplicitFields = stageMatch.first in stagesWithExplicitFields,
                            rawItem = remainder,
                            originalFullLine = cleanLine
                        )
                    } else {
                        pendingNaturalStageHeader = lineNumber to cleanLine
                        pendingNaturalStageKey = stageMatch.first
                        pendingNaturalStageItemCount = stageMap[stageMatch.first]?.values?.sumOf { it.size } ?: 0
                    }
                    continue
                }
            }

            // 5. Classify into active stage or record as UnclassifiedLine
            val activeStageKey = currentStageKey
            if (activeStageKey == null) {
                if (isStandaloneHeaderLabelOnly(strippedHeaderLine)) {
                    claimMatchingUnit(cleanLine, lineNumber, ContentUnitStatus.CLASSIFIED, fieldKey = "header_metadata")
                    continue
                }
                // Line appears before any stage header or after table end -> NEVER drop it!
                recordUnclassifiedLine(lineNumber, cleanLine)
                continue
            }

            val hasExplicitFields = activeStageKey in stagesWithExplicitFields
            if (!hasExplicitFields && isUnclassifiedInUnstructuredStage(cleanLine, stageMap[activeStageKey])) {
                recordUnclassifiedLine(lineNumber, cleanLine)
                continue
            }

            recordClassifiedLine(
                lineNumber = lineNumber,
                stageKey = activeStageKey,
                currentFieldName = currentField,
                hasExplicitFields = hasExplicitFields,
                rawItem = cleanLine,
                originalFullLine = cleanLine
            )
        }

        // If a trailing standalone stage heading had no content items at all, flush it so no short line is ever lost
        flushPendingNaturalStageHeaderIfEmpty()
        if (pendingNaturalStageHeader != null && pendingNaturalStageItemCount > 0) {
            claimMatchingUnit(
                text = pendingNaturalStageHeader!!.second,
                fallbackLineNumber = pendingNaturalStageHeader!!.first,
                newStatus = ContentUnitStatus.CLASSIFIED,
                stageKey = pendingNaturalStageKey,
                fieldKey = "stage_header"
            )
            pendingNaturalStageHeader = null
        }

        // Mark any remaining unconsumed header/table-header units
        for (i in mutableUnits.indices) {
            val u = mutableUnits[i]
            if (u.id !in consumedUnitIds) {
                val stripped = u.verbatimText.removePrefix("•").removePrefix("-").removePrefix("+").removePrefix("*").trim()
                if (isAdministrativeBannerLine(stripped) ||
                    isDurationOrSegmentNoise(stripped) ||
                    isTableHeaderLine(stripped) ||
                    isStandaloneHeaderLabelOnly(stripped) ||
                    isKnownColumnHeaderCell(stripped)
                ) {
                    consumedUnitIds.add(u.id)
                    mutableUnits[i] = u.copy(
                        status = ContentUnitStatus.CLASSIFIED,
                        assignedField = "structural_header"
                    )
                }
            }
        }

        // Fill any missing fields across all stages with DEFAULT items and tag their source as DEFAULT
        ensureDefaults(stageMap, stageSources, detectedComponent, topic)

        val builtStages = stageMetaList.associate { sm ->
            val fieldMap = stageMap[sm.key] ?: emptyMap()
            val sourcesForStage = stageSources[sm.key]?.toMap() ?: emptyMap()
            sm.key to StageData(
                eval = fieldMap["eval"]?.toList() ?: emptyList(),
                content = fieldMap["content"]?.toList() ?: emptyList(),
                teacher = fieldMap["teacher"]?.toList() ?: emptyList(),
                abil = fieldMap["abil"]?.toList() ?: emptyList(),
                sources = sourcesForStage
            )
        }.toMutableMap()

        // Populate legacy alias keys for backward compatibility if any caller accesses them directly
        if (detectedComponent == PedagogicalComponent.LANGUAGE) {
            builtStages["lang_observation"]?.let { obsData ->
                builtStages.putIfAbsent("lang_analysis", obsData)
            }
        } else if (detectedComponent == PedagogicalComponent.EXPRESSION) {
            fun mergeSessionStages(sessionNum: Int): StageData {
                val keys = stageMetaList.filter { it.session == sessionNum }.map { it.key }
                val evals = keys.flatMap { builtStages[it]?.eval.orEmpty() }.distinct()
                val contents = keys.flatMap { builtStages[it]?.content.orEmpty() }.distinct()
                val teachers = keys.flatMap { builtStages[it]?.teacher.orEmpty() }.distinct()
                val abils = keys.flatMap { builtStages[it]?.abil.orEmpty() }.distinct()
                val hasFile = keys.any { k -> builtStages[k]?.sources?.values?.contains(FieldSource.FILE) == true }
                val src = if (hasFile) FieldSource.FILE else FieldSource.DEFAULT
                return StageData(
                    eval = evals,
                    content = contents,
                    teacher = teachers,
                    abil = abils,
                    sources = mapOf("eval" to src, "content" to src, "teacher" to src, "abil" to src)
                )
            }
            builtStages.putIfAbsent("insha_acquisition", mergeSessionStages(1))
            builtStages.putIfAbsent("insha_application", mergeSessionStages(2))
            builtStages.putIfAbsent("insha_production", mergeSessionStages(3))
            builtStages.putIfAbsent("insha_correction", mergeSessionStages(4))
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
                reference = reference,
                teacherName = teacherName,
                schoolName = schoolName,
                communicativeComp = communicative,
                methodologicalComp = methodological,
                culturalComp = cultural
            ),
            stages = builtStages
        )

        val report = buildReport(
            plan = plan,
            isComponentDefaulted = isComponentDefaulted,
            unclassifiedLines = unclassifiedLines,
            originalRawText = originalRawText,
            extractedUnits = mutableUnits,
            unextractableItems = unextractableSegments,
            recordedModifications = modifications,
            recordedDuplicateUnits = duplicateUnits,
            recordedDuplicateItems = duplicateItems
        )

        return ParseResult(plan = plan, report = report)
    }

    /**
     * Builds traceable [ExtractedUnit]s from raw text (supporting both plain paragraphs and
     * pipe/tab-separated tables) before [preprocessTabularText] runs.
     */
    internal fun buildTraceableUnitsFromRaw(raw: String): List<ExtractedUnit> {
        if (raw.isBlank()) return emptyList()
        val rawLines = raw.lines()
        val tabLinesCount = rawLines.count { it.count { ch -> ch == '\t' } >= 2 }
        val pipeLinesCount = rawLines.count { line ->
            val trimmed = line.trim()
            !trimmed.startsWith("[جدول:") && trimmed.count { ch -> ch == '|' } >= 2
        }
        val isTabular = tabLinesCount >= 2 || pipeLinesCount >= 2
        val delimiter = if (tabLinesCount >= 2) '\t' else '|'

        val units = mutableListOf<ExtractedUnit>()
        var nextUnitId = 1
        var paraIdx = 0
        var tableRowIdx = 0

        for ((lineIdx, line) in rawLines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed == "[نهاية الجدول]") continue

            if ((trimmed.startsWith("[مرحلة:") || trimmed.startsWith("[حصة:")) && trimmed.endsWith("]")) {
                val inner = trimmed.substringAfter(":").removeSuffix("]").trim()
                if (inner.isNotEmpty()) {
                    units.add(
                        ExtractedUnit(
                            id = "unit_${nextUnitId++}",
                            verbatimText = inner,
                            elementType = DocumentElementType.TABLE_CELL,
                            location = UnitLocation(
                                sourcePart = "raw_text",
                                elementIndex = lineIdx,
                                tableIndex = 0,
                                rowIndex = tableRowIdx++,
                                colIndex = 0,
                                lineNumber = lineIdx + 1
                            ),
                            status = ContentUnitStatus.EXTRACTED
                        )
                    )
                }
                continue
            }

            if (detectField(trimmed) != null && extractAfterColon(trimmed).isBlank()) {
                continue
            }

            if (isTabular && !trimmed.startsWith("[جدول:")) {
                val cols = if (delimiter == '\t') {
                    line.split('\t').map { it.trim() }
                } else {
                    trimmed.removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
                }
                if (cols.size >= 2) {
                    val isSeparator = cols.all { c -> c.isEmpty() || c.all { ch -> ch == '-' || ch == ':' || ch == ' ' } }
                    if (isSeparator) continue
                    val currentRow = tableRowIdx++
                    for ((colIdx, cell) in cols.withIndex()) {
                        if (cell.isNotEmpty()) {
                            units.add(
                                ExtractedUnit(
                                    id = "unit_${nextUnitId++}",
                                    verbatimText = cell,
                                    elementType = DocumentElementType.TABLE_CELL,
                                    location = UnitLocation(
                                        sourcePart = "raw_text",
                                        elementIndex = lineIdx,
                                        tableIndex = 0,
                                        rowIndex = currentRow,
                                        colIndex = colIdx,
                                        lineNumber = lineIdx + 1
                                    ),
                                    status = ContentUnitStatus.EXTRACTED
                                )
                            )
                        }
                    }
                    continue
                }
            }

            val clean = DocumentFileReader.stripFormattingTags(trimmed).trim()
            if (clean.isNotEmpty()) {
                units.add(
                    ExtractedUnit(
                        id = "unit_${nextUnitId++}",
                        verbatimText = clean,
                        elementType = DocumentElementType.PARAGRAPH,
                        location = UnitLocation(
                            sourcePart = "raw_text",
                            elementIndex = lineIdx,
                            paragraphIndex = paraIdx++,
                            lineNumber = lineIdx + 1
                        ),
                        status = ContentUnitStatus.EXTRACTED
                    )
                )
            }
        }
        return units
    }

    /**
     * Distinguishes unintended same-location duplication from legitimate repetition across different
     * locations in the document by comparing [ExtractedUnit.id] and [UnitLocation].
     */
    internal fun isUnintendedSameLocationDuplicate(
        prevUnit: ExtractedUnit,
        currentUnit: ExtractedUnit
    ): Boolean {
        if (prevUnit.id == currentUnit.id) return true
        val locA = prevUnit.location
        val locB = currentUnit.location
        if (locA.sourcePart != locB.sourcePart) return false

        // Both inside tables: unintended duplicate only if inside the exact same table cell (same table, row, and col)
        if (locA.tableIndex != null && locB.tableIndex != null) {
            return locA.tableIndex == locB.tableIndex &&
                locA.rowIndex == locB.rowIndex &&
                locA.colIndex == locB.colIndex
        }

        // One in a table and one outside a table -> distinct locations (legitimate repetition)
        if (locA.tableIndex != locB.tableIndex) return false

        // Both are non-table paragraphs: unintended duplicate only if immediately adjacent in the same block
        val paraA = locA.paragraphIndex ?: locA.elementIndex
        val paraB = locB.paragraphIndex ?: locB.elementIndex
        val isAdjacentParagraph = kotlin.math.abs(paraB - paraA) <= 1
        val isAdjacentLine = kotlin.math.abs(locB.lineNumber - locA.lineNumber) <= 1
        return isAdjacentParagraph && isAdjacentLine
    }

    private data class FinalPlanEntry(
        val stageKey: String,
        val fieldKey: String,
        val text: String,
        var consumedByUnitId: String? = null
    )

    /**
     * Content Integrity Monitor (مراقبة سلامة المحتوى - المهمة الثالثة):
     * Compares:
     * 1. المحتوى الأصلي ([originalRawText])
     * 2. المحتوى المستخرج ([extractedUnits])
     * 3. المحتوى المصنف (classified units)
     * 4. المحتوى النهائي المستخدم لإنشاء الجذاذة ([plan] non-default items)
     *
     * Relies on [ExtractedUnit.id] and [UnitLocation] so that identical text in two different document
     * locations is treated as two distinct traceable units rather than collapsed into one.
     */
    fun verifyContentIntegrity(
        originalRawText: String,
        extractedUnits: List<ExtractedUnit>,
        plan: JadhadhaPlan,
        unclassifiedLines: List<UnclassifiedLine>,
        unextractableItems: List<String> = emptyList(),
        recordedModifications: List<TextModificationRecord> = emptyList(),
        recordedDuplicateUnits: List<ExtractedUnit> = emptyList(),
        recordedDuplicateItems: List<String> = emptyList()
    ): ContentIntegrityReport {
        val stageMetaList = StageCatalog.getStagesForComponent(plan.componentType)
        val fields = listOf("abil", "teacher", "content", "eval")

        // Collect all non-default final items used in the JadhadhaPlan as individual occurrences
        val finalUsedItems = mutableListOf<String>()
        val finalPlanEntries = mutableListOf<FinalPlanEntry>()
        val duplicateItemsInPlan = recordedDuplicateItems.toMutableList()
        val duplicateUnitsList = recordedDuplicateUnits.toMutableList()
        val recordedDuplicateUnitIds = recordedDuplicateUnits.map { it.id }.toSet()
        var totalRequiredStageFields = 0
        var verifiedNonDefaultStageFields = 0

        for (sm in stageMetaList) {
            val stData = plan.stages[sm.key] ?: StageData()
            for (f in fields) {
                totalRequiredStageFields++
                val src = stData.sourceFor(f)
                val items = when (f) {
                    "abil" -> stData.abil
                    "teacher" -> stData.teacher
                    "content" -> stData.content
                    "eval" -> stData.eval
                    else -> emptyList()
                }
                if (src != FieldSource.DEFAULT && items.any { it.isNotBlank() }) {
                    verifiedNonDefaultStageFields++
                    items.filter { it.isNotBlank() }.forEach { item ->
                        val trimmed = item.trim()
                        finalUsedItems.add(trimmed)
                        finalPlanEntries.add(
                            FinalPlanEntry(
                                stageKey = sm.key,
                                fieldKey = f,
                                text = trimmed
                            )
                        )
                    }
                }
            }
        }

        val unclassifiedUnitIds = unclassifiedLines.mapNotNull { it.unitId.takeIf { id -> id.isNotBlank() } }.toSet()

        val classifiedUnits = mutableListOf<ExtractedUnit>()
        val needsReviewUnits = mutableListOf<ExtractedUnit>()
        val unextractableUnits = mutableListOf<ExtractedUnit>()
        val missingUnits = mutableListOf<ExtractedUnit>()
        val modifiedUnits = recordedModifications.toMutableList()

        // Include any unextractable segments as UNEXTRACTABLE units
        unextractableItems.forEachIndexed { idx, seg ->
            unextractableUnits.add(
                ExtractedUnit(
                    id = "unextractable_${idx + 1}",
                    verbatimText = seg,
                    elementType = DocumentElementType.PARAGRAPH,
                    location = UnitLocation(sourcePart = "corrupted_xml", elementIndex = idx, lineNumber = idx + 1),
                    status = ContentUnitStatus.UNEXTRACTABLE
                )
            )
        }

        fun matchesEntryText(unitText: String, unitFinalText: String?, entryText: String): Boolean {
            if (unitFinalText != null && unitFinalText.trim() == entryText) return true
            return unitText == entryText ||
                unitText.endsWith(entryText) ||
                entryText.endsWith(unitText)
        }

        for (u in extractedUnits) {
            if (u.status == ContentUnitStatus.UNEXTRACTABLE) {
                unextractableUnits.add(u)
                continue
            }

            // Match unclassified status strictly by unit ID or exact UnitLocation (never by text alone)
            val isUnclassifiedByIdOrLocation = u.id in unclassifiedUnitIds ||
                u.status == ContentUnitStatus.NEEDS_REVIEW ||
                unclassifiedLines.any { uLine ->
                    uLine.unitId.isBlank() &&
                        uLine.location == u.location &&
                        (uLine.verbatimText.trim() == u.verbatimText.trim() || uLine.text.trim() == u.verbatimText.trim())
                }
            if (isUnclassifiedByIdOrLocation) {
                needsReviewUnits.add(u.copy(status = ContentUnitStatus.NEEDS_REVIEW))
                continue
            }

            val isStructural = u.assignedField in setOf(
                "header_metadata",
                "stage_header",
                "session_header",
                "session_banner",
                "table_header",
                "structural_header",
                "administrative_or_duration",
                "duration_noise"
            )
            if (isStructural) {
                classifiedUnits.add(u.copy(status = ContentUnitStatus.CLASSIFIED))
                continue
            }

            // If this unit was explicitly recorded as an unintended same-location duplicate, verify its text is still in finalUsedItems
            val vText = u.verbatimText.trim()
            val expectedText = u.finalClassifiedText?.trim() ?: vText
            if (u.id in recordedDuplicateUnitIds) {
                val stillPresent = finalPlanEntries.any { matchesEntryText(vText, u.finalClassifiedText, it.text) }
                if (stillPresent) {
                    classifiedUnits.add(u.copy(status = ContentUnitStatus.CLASSIFIED))
                } else {
                    missingUnits.add(u)
                }
                continue
            }

            // Each distinct-location ExtractedUnit (by u.id and u.location) must claim its own unconsumed FinalPlanEntry
            val matchedEntry = finalPlanEntries.firstOrNull { entry ->
                entry.consumedByUnitId == null &&
                    (u.assignedStageKey == null || entry.stageKey == u.assignedStageKey) &&
                    (u.assignedField == null || entry.fieldKey == u.assignedField) &&
                    matchesEntryText(vText, u.finalClassifiedText, entry.text)
            } ?: finalPlanEntries.firstOrNull { entry ->
                entry.consumedByUnitId == null &&
                    matchesEntryText(vText, u.finalClassifiedText, entry.text)
            }

            if (matchedEntry != null) {
                matchedEntry.consumedByUnitId = u.id
                classifiedUnits.add(
                    u.copy(
                        status = ContentUnitStatus.CLASSIFIED,
                        assignedStageKey = u.assignedStageKey ?: matchedEntry.stageKey,
                        assignedField = u.assignedField ?: matchedEntry.fieldKey,
                        finalClassifiedText = matchedEntry.text
                    )
                )
                if (vText != matchedEntry.text &&
                    !vText.endsWith(matchedEntry.text) &&
                    !matchedEntry.text.endsWith(vText) &&
                    modifiedUnits.none { it.unitId == u.id }
                ) {
                    modifiedUnits.add(
                        TextModificationRecord(
                            unitId = u.id,
                            originalText = vText,
                            finalText = matchedEntry.text,
                            location = u.location
                        )
                    )
                }
            } else {
                // Check if this unit was modified into an unconsumed entry in the target stage/field
                val cleanV = cleanBullet(expectedText)
                val modifiedEntry = finalPlanEntries.firstOrNull { entry ->
                    entry.consumedByUnitId == null &&
                        (u.assignedStageKey == null || entry.stageKey == u.assignedStageKey) &&
                        (u.assignedField == null || entry.fieldKey == u.assignedField) &&
                        (
                            cleanBullet(entry.text) == cleanV ||
                                (cleanV.length >= 10 && entry.text.contains(cleanV.take(15))) ||
                                (entry.text.length >= 10 && cleanV.contains(entry.text.take(15)))
                            )
                } ?: finalPlanEntries.firstOrNull { entry ->
                    entry.consumedByUnitId == null &&
                        (
                            cleanBullet(entry.text) == cleanV ||
                                (cleanV.length >= 10 && entry.text.contains(cleanV.take(15))) ||
                                (entry.text.length >= 10 && cleanV.contains(entry.text.take(15)))
                            )
                }

                if (modifiedEntry != null) {
                    modifiedEntry.consumedByUnitId = u.id
                    classifiedUnits.add(
                        u.copy(
                            status = ContentUnitStatus.CLASSIFIED,
                            assignedStageKey = u.assignedStageKey ?: modifiedEntry.stageKey,
                            assignedField = u.assignedField ?: modifiedEntry.fieldKey,
                            finalClassifiedText = modifiedEntry.text
                        )
                    )
                    if (modifiedUnits.none { it.unitId == u.id }) {
                        modifiedUnits.add(
                            TextModificationRecord(
                                unitId = u.id,
                                originalText = vText,
                                finalText = modifiedEntry.text,
                                location = u.location
                            )
                        )
                    }
                } else {
                    // No unconsumed entry in the final plan matches this unit (by ID & location) -> MISSING!
                    missingUnits.add(u)
                }
            }
        }

        // Detect any unbacked duplicate entries in the final plan (entries repeated inside a stage field without a distinct-location source unit)
        val entriesByStageField = finalPlanEntries.groupBy { it.stageKey to it.fieldKey }
        for ((_, entries) in entriesByStageField) {
            val seenConsumedTexts = mutableSetOf<String>()
            for (entry in entries) {
                if (entry.consumedByUnitId == null && entry.text in seenConsumedTexts) {
                    if (!duplicateItemsInPlan.contains(entry.text)) {
                        duplicateItemsInPlan.add(entry.text)
                    }
                } else {
                    seenConsumedTexts.add(entry.text)
                }
            }
        }

        // Also check if any non-structural line in originalRawText was completely missed when extractedUnits is empty
        if (originalRawText.isNotBlank() && extractedUnits.isEmpty()) {
            val finalUsedSet = finalUsedItems.toSet()
            val unclassifiedTextSet = unclassifiedLines.flatMap { listOf(it.text.trim(), it.verbatimText.trim()) }.toSet()
            originalRawText.lines().forEachIndexed { idx, rawLine ->
                val trimmed = rawLine.trim()
                if (trimmed.isNotEmpty() && trimmed !in finalUsedSet && trimmed !in unclassifiedTextSet) {
                    missingUnits.add(
                        ExtractedUnit(
                            id = "missing_raw_${idx + 1}",
                            verbatimText = trimmed,
                            elementType = DocumentElementType.PARAGRAPH,
                            location = UnitLocation(sourcePart = "original_raw", elementIndex = idx, lineNumber = idx + 1),
                            status = ContentUnitStatus.EXTRACTED
                        )
                    )
                }
            }
        }

        // Calculate realistic completion percentage based on actual verification
        val fieldCoverageRatio = if (totalRequiredStageFields > 0) {
            verifiedNonDefaultStageFields.toDouble() / totalRequiredStageFields.toDouble()
        } else {
            0.0
        }

        val totalTrackedUnits = classifiedUnits.size + needsReviewUnits.size + missingUnits.size + unextractableUnits.size
        val intactClassifiedCount = (classifiedUnits.size - modifiedUnits.size).coerceAtLeast(0)
        val unitIntegrityRatio = if (totalTrackedUnits > 0) {
            intactClassifiedCount.toDouble() / totalTrackedUnits.toDouble()
        } else if (originalRawText.isBlank()) {
            0.0
        } else {
            1.0
        }

        val rawPercentage = ((fieldCoverageRatio * unitIntegrityRatio) * 100.0).toInt().coerceIn(0, 100)
        val hasAnyDeficiency = verifiedNonDefaultStageFields < totalRequiredStageFields ||
            needsReviewUnits.isNotEmpty() ||
            missingUnits.isNotEmpty() ||
            unextractableUnits.isNotEmpty() ||
            modifiedUnits.isNotEmpty() ||
            totalTrackedUnits == 0

        val actualCompletionPercentage = if (hasAnyDeficiency) {
            rawPercentage.coerceAtMost(99)
        } else {
            100
        }

        return ContentIntegrityReport(
            originalRawText = originalRawText,
            extractedUnits = extractedUnits,
            classifiedUnits = classifiedUnits,
            unclassifiedUnits = needsReviewUnits,
            unextractableUnits = unextractableUnits,
            finalUsedItems = finalUsedItems,
            missingUnits = missingUnits,
            duplicateUnits = duplicateUnitsList,
            duplicateItems = duplicateItemsInPlan,
            modifiedUnits = modifiedUnits,
            actualCompletionPercentage = actualCompletionPercentage
        )
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
        isAiDelegated: Boolean = false,
        originalRawText: String = "",
        extractedUnits: List<ExtractedUnit> = emptyList(),
        unextractableItems: List<String> = emptyList(),
        recordedModifications: List<TextModificationRecord> = emptyList(),
        recordedDuplicateUnits: List<ExtractedUnit> = emptyList(),
        recordedDuplicateItems: List<String> = emptyList()
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

        val integrity = verifyContentIntegrity(
            originalRawText = originalRawText,
            extractedUnits = extractedUnits,
            plan = plan,
            unclassifiedLines = unclassifiedLines,
            unextractableItems = unextractableItems,
            recordedModifications = recordedModifications,
            recordedDuplicateUnits = recordedDuplicateUnits,
            recordedDuplicateItems = recordedDuplicateItems
        )

        return ParseReport(
            detectedComponent = plan.componentType,
            isComponentDefaulted = isComponentDefaulted,
            usedKeywordsForComponent = !isComponentDefaulted,
            stageSourceCounts = stageCounts,
            unclassifiedLines = unclassifiedLines,
            aiClassifiedLinesCount = aiClassifiedLinesCount,
            aiRefinedFieldsCount = aiRefinedFieldsCount,
            isAiDelegated = isAiDelegated,
            originalRawText = originalRawText,
            extractedUnits = extractedUnits,
            unextractableItems = unextractableItems,
            integrityReport = integrity
        )
    }

    /**
     * Assigns an [UnclassifiedLine] to a specific [targetStageKey] and [targetField] ("abil", "teacher", "content", "eval").
     * Preserves the verbatim text of the unit without unjustified modification, updates that field's source
     * to [FieldSource.FILE], removes any DEFAULT placeholder in that cell, removes the line from
     * [ParseReport.unclassifiedLines], and updates the integrity report.
     */
    fun assignUnclassifiedLine(
        currentResult: ParseResult,
        line: UnclassifiedLine,
        targetStageKey: String,
        targetField: String = "content"
    ): ParseResult {
        val plan = currentResult.plan
        val currentStage = plan.stages[targetStageKey] ?: StageData()
        val verbatimItem = line.verbatimText.ifBlank { line.text }.let {
            DocumentFileReader.stripFormattingTags(it).trim()
        }
        val formattedItem = if (targetField == "content") {
            formatContentSubLabel(verbatimItem)
        } else {
            verbatimItem
        }

        val wasDefault = currentStage.sourceFor(targetField) == FieldSource.DEFAULT
        val updatedStage = when (targetField) {
            "abil" -> currentStage.copy(
                abil = if (wasDefault) listOf(formattedItem) else (currentStage.abil + formattedItem),
                sources = currentStage.sources + ("abil" to FieldSource.FILE)
            )
            "teacher" -> currentStage.copy(
                teacher = if (wasDefault) listOf(formattedItem) else (currentStage.teacher + formattedItem),
                sources = currentStage.sources + ("teacher" to FieldSource.FILE)
            )
            "eval" -> currentStage.copy(
                eval = if (wasDefault) listOf(formattedItem) else (currentStage.eval + formattedItem),
                sources = currentStage.sources + ("eval" to FieldSource.FILE)
            )
            else -> currentStage.copy(
                content = if (wasDefault) listOf(formattedItem) else (currentStage.content + formattedItem),
                sources = currentStage.sources + ("content" to FieldSource.FILE)
            )
        }

        val updatedStages = plan.stages.toMutableMap().apply {
            put(targetStageKey, updatedStage)
        }
        val updatedPlan = plan.copy(stages = updatedStages)
        val remainingUnclassified = currentResult.report.unclassifiedLines.filterNot {
            (line.unitId.isNotBlank() && it.unitId == line.unitId) ||
                (it.lineNumber == line.lineNumber && it.text == line.text)
        }
        val updatedUnits = currentResult.report.extractedUnits.map { u ->
            if ((line.unitId.isNotBlank() && u.id == line.unitId) ||
                (u.location.lineNumber == line.lineNumber && u.verbatimText == verbatimItem)
            ) {
                u.copy(
                    status = ContentUnitStatus.CLASSIFIED,
                    assignedStageKey = targetStageKey,
                    assignedField = targetField,
                    finalClassifiedText = formattedItem
                )
            } else {
                u
            }
        }
        val updatedReport = buildReport(
            plan = updatedPlan,
            isComponentDefaulted = currentResult.report.isComponentDefaulted,
            unclassifiedLines = remainingUnclassified,
            aiClassifiedLinesCount = currentResult.report.aiClassifiedLinesCount,
            aiRefinedFieldsCount = currentResult.report.aiRefinedFieldsCount,
            isAiDelegated = currentResult.report.isAiDelegated,
            originalRawText = currentResult.report.originalRawText,
            extractedUnits = updatedUnits,
            unextractableItems = currentResult.report.unextractableItems,
            recordedModifications = currentResult.report.integrityReport?.modifiedUnits.orEmpty(),
            recordedDuplicateUnits = currentResult.report.integrityReport?.duplicateUnits.orEmpty(),
            recordedDuplicateItems = currentResult.report.integrityReport?.duplicateItems.orEmpty()
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
            (line.unitId.isNotBlank() && it.unitId == line.unitId) ||
                (it.lineNumber == line.lineNumber && it.text == line.text)
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

    internal fun isDurationOrSegmentNoise(s: String): Boolean {
        val norm = normalizeHeaderToken(s)
        if (norm.isEmpty()) return true
        // Ignore generic segment headers ("المقطع التمهيدي", "المقطع الوسيطي", "المقطع النهائي")
        if (norm == "المقطع التمهيدي" || norm == "مقطع تمهيدي" ||
            norm == "المقطع الوسيطي" || norm == "المقطع الوسطي" || norm == "مقطع وسيطي" || norm == "مقطع وسطي" ||
            norm == "المقطع النهائي" || norm == "المقطع الختامي" || norm == "مقطع نهائي" || norm == "مقطع ختامي"
        ) {
            return true
        }
        // Ignore standalone time durations like "5 د", "10 د", "15 د", "20 د", "35 د", "10 دقائق", "15 دقيقة"
        val compact = s.trim()
        if (compact.matches(Regex("^[0-9٠-٩]+\\s*(د|دقيقة|دقائق|دقيقتان)\\.?$"))) {
            return true
        }
        return false
    }

    private fun detectSessionTransition(s: String): Int? {
        val trimmed = s.trim()
        if (trimmed.startsWith("-") || trimmed.startsWith("+") || trimmed.startsWith("•") || trimmed.startsWith("*")) {
            return null
        }
        val inner = if ((trimmed.startsWith("[مرحلة:") || trimmed.startsWith("[حصة:")) && trimmed.endsWith("]")) {
            trimmed.substringAfter(":").removeSuffix("]").trim()
        } else {
            trimmed
        }
        if (inner.length > 60 && !inner.startsWith("المكون") && !inner.startsWith("المكوّن")) {
            return null
        }
        val norm = normalizeHeaderToken(inner)
        return when {
            norm.contains("الحصه الاولي") || norm.contains("حصه الاكتساب") || norm.contains("انشطه الاكتساب") -> 1
            norm.contains("الحصه الثانيه") || norm.contains("حصه التطبيق") || norm.contains("انشطه التطبيق") -> 2
            norm.contains("الحصه الثالثه") || norm.contains("حصه الانتاج") || norm.contains("انشطه الانتاج") -> 3
            norm.contains("الحصه الرابعه") || norm.contains("حصه التصحيح") || norm.contains("انشطه التصحيح") -> 4
            else -> null
        }
    }

    /**
     * Matches ONLY standalone header labels (without sentence content) so normal paragraphs
     * or short sentences starting with words like "الهدف" or "الموضوع" are never swallowed.
     */
    private fun isStandaloneHeaderLabelOnly(s: String): Boolean {
        val norm = normalizeHeaderToken(s)
        return norm in setOf(
            "جذاذه تربويه",
            "الفئه المستهدفه",
            "المكون",
            "الموضوع",
            "عنوان الدرس",
            "موضوع الدرس",
            "الوسائل الديدكتيكيه",
            "الوسائل الديداكتيكيه",
            "الوسائل التعليميه",
            "الكفايه التواصليه",
            "الكفايه المنهجيه",
            "الكفايه الثقافيه",
            "الكفايه المعرفيه",
            "الكفايات المتوخاه",
            "الكفايات المستهدفه",
            "الاهداف التعليميه",
            "الاهداف التعلميه",
            "الهدف",
            "الاهداف",
            "المرجع",
            "الاستاذ",
            "المؤسسه",
            "الوحده",
            "الدوره",
            "المجال",
            "مده الانجاز",
            "المده الزمنيه",
            "الغلاف الزمني",
            "الحصيص الزمني"
        )
    }

    /**
     * Used when inspecting tabular cells in [preprocessTabularText] to identify pre-lesson header tables.
     */
    private fun isHeaderIsolationLine(s: String): Boolean {
        if (isStandaloneHeaderLabelOnly(s) || isStrictHeaderPrefix(s)) return true
        val norm = normalizeHeaderToken(s)
        return norm.contains("جذاذه تربويه") ||
            norm.contains("الفئه المستهدفه") ||
            norm.contains("الوسائل الديدكتيكيه") ||
            norm.contains("الوسائل الديداكتيكيه") ||
            norm.contains("الوسائل التعليميه") ||
            norm.contains("الكفايه التواصليه") ||
            norm.contains("الكفايه المنهجيه") ||
            norm.contains("الكفايه الثقافيه") ||
            norm.contains("الكفايه المعرفيه") ||
            norm.contains("الكفايات المتوخاه") ||
            norm.contains("الكفايات المستهدفه")
    }

    private fun isKnownColumnHeaderCell(s: String): Boolean {
        val norm = normalizeHeaderToken(s)
        if (norm.isEmpty() || norm.length > 40) return false
        return norm in setOf(
            "المراحل", "المقاطع", "مقاطع الدرس", "المقاطع والمراحل", "الخطوات", "خطوات القراءه",
            "الحصيص الزمني", "المده الزمنيه",
            "القدرات والمهارات", "القدرات المستهدفه", "القدرات", "المهارات", "الاهداف",
            "الانشطه التعليميه التعلميه", "الانشطه التعليميه", "انشطه المدرس", "انشطه الاستاذ",
            "انشطه المتعلم", "انشطه المتعلمين", "المحتوي", "المحتوي الديداكتيكي", "المضامين",
            "التقويم والدعم", "تقويم ودعم", "التقويم", "مؤشرات التقويم", "الدعم"
        )
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
        onReference: (String) -> Unit = {},
        onTeacherName: (String) -> Unit = {},
        onSchoolName: (String) -> Unit = {},
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
        } else if (strippedHeaderLine.startsWith("المرجع")) {
            val extracted = extractAfterColon(strippedHeaderLine)
            if (extracted.isNotBlank()) {
                onReference(extracted)
                matchedHeader = true
            }
        } else if (strippedHeaderLine.startsWith("الأستاذ") || strippedHeaderLine.startsWith("الاستاذ")) {
            val extracted = extractAfterColon(strippedHeaderLine)
            if (extracted.isNotBlank()) {
                onTeacherName(extracted)
                matchedHeader = true
            }
        } else if (strippedHeaderLine.startsWith("المؤسسة") || strippedHeaderLine.startsWith("المؤسسه")) {
            val extracted = extractAfterColon(strippedHeaderLine)
            if (extracted.isNotBlank()) {
                onSchoolName(extracted)
                matchedHeader = true
            }
        } else if (strippedHeaderLine.startsWith("الوسائل") ||
            strippedHeaderLine.startsWith("الدعامات")
        ) {
            val extracted = extractAfterColon(strippedHeaderLine)
            if (extracted.isNotBlank()) {
                onMaterials(extracted)
                matchedHeader = true
            }
        } else if (strippedHeaderLine.contains("كفاية تواصلية") || strippedHeaderLine.startsWith("الكفاية التواصلية")) {
            val extracted = extractAfterColon(strippedHeaderLine)
            if (extracted.isNotBlank()) {
                onCommunicative(extracted)
                matchedHeader = true
            } else if (isStandaloneHeaderLabelOnly(strippedHeaderLine)) {
                matchedHeader = true
            }
        } else if (strippedHeaderLine.contains("كفاية منهجية") || strippedHeaderLine.startsWith("الكفاية المنهجية")) {
            val extracted = extractAfterColon(strippedHeaderLine)
            if (extracted.isNotBlank()) {
                onMethodological(extracted)
                matchedHeader = true
            } else if (isStandaloneHeaderLabelOnly(strippedHeaderLine)) {
                matchedHeader = true
            }
        } else if (strippedHeaderLine.contains("كفاية ثقافية") ||
            strippedHeaderLine.contains("كفاية معرفية") ||
            strippedHeaderLine.startsWith("الكفاية الثقافية") ||
            strippedHeaderLine.startsWith("الكفاية المعرفية") ||
            strippedHeaderLine.startsWith("الكفاية الاستراتيجية") ||
            strippedHeaderLine.startsWith("القدرة الخاصة بالدرس") ||
            (!hasEnteredStages && (strippedHeaderLine.startsWith("الهدف:") || strippedHeaderLine.startsWith("الهدف العام:") || strippedHeaderLine.startsWith("الأهداف:")))
        ) {
            val extracted = extractAfterColon(strippedHeaderLine)
            if (extracted.isNotBlank()) {
                onCultural(extracted)
                matchedHeader = true
            } else if (isStandaloneHeaderLabelOnly(strippedHeaderLine)) {
                matchedHeader = true
            }
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
        val inferred = inferFieldFromUnstructuredLine(cleanBullet(trimmed), "unknown")
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
     * together for split headers and isolating pre-lesson header tables without altering cell text.
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
            if (delimiter != '|') {
                return line.split('\t').map { it.trim() }
            }
            val rawRow = trimmed.removePrefix("|").removeSuffix("|")
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            var bracketDepth = 0
            for (ch in rawRow) {
                when (ch) {
                    '[' -> {
                        bracketDepth++
                        current.append(ch)
                    }
                    ']' -> {
                        bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                        current.append(ch)
                    }
                    '|' -> {
                        if (bracketDepth == 0) {
                            parts.add(current.toString().trim())
                            current.setLength(0)
                        } else {
                            current.append(ch)
                        }
                    }
                    else -> current.append(ch)
                }
            }
            parts.add(current.toString().trim())
            return parts
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
                            sb.appendLine(cellLine)
                        }
                    }

                    val stageVal = stageIndices.mapNotNull { idx -> rawCols.getOrNull(idx)?.takeIf { it.isNotBlank() } }
                        .filter { !isDurationOrSegmentNoise(it) }
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
        if (row1.any { looksLikeStageCell(it) || isDurationOrSegmentNoise(it) }) {
            return null
        }
        val map1 = matchHeaderColumns(row1)
        val hasStageInRow1 = map1.containsValue("stage")
        val hasOtherInRow1 = map1.values.any { it != "stage" }
        if (map1.size < 2 || !hasStageInRow1 || !hasOtherInRow1) {
            return null
        }

        if (row2 != null && row2.size >= 2) {
            val map2 = matchHeaderColumns(row2)
            val row2LooksLikeSubHeader = map2.isNotEmpty() &&
                row2.all { it.length <= 45 } &&
                (map2.containsValue("teacher") || map2.containsValue("content") || map2.containsValue("eval") || map2.containsValue("abil")) &&
                !row2.any { looksLikeStageCell(it) || isDurationOrSegmentNoise(it) }

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

        return map1 to false
    }

    /**
     * Dynamically matches column header titles from any table format (3, 4, 8, or 9 columns, RTL or LTR).
     */
    internal fun matchHeaderColumns(cols: List<String>): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        var hasStage = false
        val hasExplicitStageCol = cols.any { h ->
            val n = normalizeHeaderToken(h)
            n.contains("المراحل") || n.contains("مراحل") || n.contains("الخطوات") || n.contains("خطوات القراءه")
        }

        for ((idx, rawHeader) in cols.withIndex()) {
            val norm = normalizeHeaderToken(rawHeader)
            if (norm.isEmpty() || norm.length > 60) continue

            // Ignore time/duration columns ("الحصيص الزمني", "المدة الزمنية")
            if (norm.contains("الحصيص") || norm.contains("الزمني") || norm.contains("المده")) {
                continue
            }

            val isCombinedActivities = (norm.contains("التعليميه") && norm.contains("التعلميه")) ||
                (norm.contains("المدرس") && norm.contains("المتعلم")) ||
                (norm.contains("الاستاذ") && norm.contains("المتعلم"))

            val role = when {
                !hasStage && (
                    norm.contains("المراحل") || norm.contains("مراحل") ||
                        norm.contains("الخطوات") || norm.contains("خطوات القراءه") ||
                        (!hasExplicitStageCol && (norm.contains("المقاطع") || norm.contains("مقاطع") || norm == "الحصص" || norm == "الحصه"))
                    ) -> {
                    hasStage = true
                    "stage"
                }

                norm.contains("التقويم") || norm.contains("تقويم") ||
                    norm.contains("الدعم") || norm.contains("مؤشرات") -> "eval"

                norm.contains("القدرات") || norm.contains("قدرات") ||
                    norm.contains("الاهداف") || norm.contains("اهداف") ||
                    norm.contains("الكفايات") ||
                    norm.contains("المهارات") || norm.contains("مهارات") -> "abil"

                isCombinedActivities -> "content"

                norm.contains("انشطه المدرس") || norm.contains("انشطه الاستاذ") ||
                    norm.contains("توجيهات") || norm.contains("مهام المدرس") ||
                    norm.contains("دور المدرس") || norm.contains("دور الاستاذ") ||
                    norm.contains("الاستاذ") || norm.contains("المدرس") ||
                    (norm.contains("التعليميه") && !norm.contains("التعلميه")) -> "teacher"

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
                s.startsWith("مدة الإنجاز:") ||
                s.startsWith("المرجع:") ||
                s.startsWith("الأستاذ:") ||
                s.startsWith("المؤسسة:") ||
                s.startsWith("الوسائل التعليمية:") ||
                s.startsWith("الوسائل الديداكتيكية:") ||
                s.startsWith("الوسائل الديدكتيكية:") ||
                s.startsWith("كفاية تواصلية:") ||
                s.startsWith("الكفاية التواصلية:") ||
                s.startsWith("كفاية منهجية:") ||
                s.startsWith("الكفاية المنهجية:") ||
                s.startsWith("كفاية معرفية") ||
                s.startsWith("كفاية ثقافية") ||
                s.startsWith("الكفاية الثقافية")
    }

    private fun isTableHeaderLine(s: String): Boolean {
        val norm = normalizeHeaderToken(s)
        val hasStage = norm.contains("المراحل") || norm.contains("المقاطع") || norm.contains("الخطوات")
        val hasOtherCol = norm.contains("القدرات") || norm.contains("الاستاذ") || norm.contains("المدرس") ||
            norm.contains("المتعلم") || norm.contains("التقويم") || norm.contains("المحتوي") || norm.contains("مضامين")
        return s == "المقاطع والمراحل" || s == "المراحل" || s == "المقاطع" || s == "مقاطع الدرس" || (hasStage && hasOtherCol)
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
        "خلاصة تركيبية", "الاستثمار والبحث", "امتدادات", "المضمون العام للنص", "أحداث النص",
        "شخصيات النص وأوصافها", "الزمان والمكان", "السارد والرؤية السردية", "أنماط الحكي",
        "استنتاج جزئي", "نموذج إعرابي", "شبكة تشخيص أخطاء المتعلمين وتصحيحها"
    )

    private fun formatContentSubLabel(clean: String): String {
        return DocumentFileReader.stripFormattingTags(clean).trim()
    }

    // ────────────────────── Semantic Verb-Prefix Classification ──────────────────────

    private val TEACHER_VERB_PREFIXES = listOf(
        "يطالب", "يطرح", "يوجه", "يساعد", "يقرأ الأستاذ", "يدعم", "يحفز",
        "يقرأ المدرس", "يشرف", "يدعو", "يمهد", "يناقش", "يسجل", "يدون",
        "يستدرج", "يكلف", "يوزع", "يعرض الأستاذ", "يعرض المدرس", "يوضح",
        "يشرح", "ينظم", "يرسم", "يقدم", "يقترح", "يطلب من", "يدير حوار",
        "يسطر الأستاذ", "يدرج أخطاء", "يركز على الأخطاء", "ينتقي الأستاذ"
    )

    private val LEARNER_VERB_PREFIXES = listOf(
        "يعرضون", "يجيبون", "يؤطرون", "يصوغ", "يقرأ المتعلمون", "يستخرجون",
        "يفهمون", "يلخص", "ينجزون", "يمحصون",
        "يتأملون", "يصفون", "يلاحظون", "يستنتجون", "يقرأون", "يقرؤون",
        "يشاركون", "يبنون", "يصوغون", "يركبون", "يعربون", "يستحضر المتعلم",
        "يستحضرون", "يلخصون", "يتفاعل المتعلم", "يتابعون"
    )

    private val ABILITY_PREFIXES = listOf(
        "القدرة على", "قدرة المتعلم على", "قدرة التلاميذ على", "تنمية مهارة", "تنمية قدرة",
        "استدعاء المتعلم", "أن يكون المتعلم", "أن يتعرف", "أن يتمكن", "أن يكتشف",
        "أن يستنتج", "أن يحلل", "أن يدرك", "أن يميز", "أن يوظف", "أن يصوغ", "أن يقرأ",
        "أن يستخلص", "أن يكتسب", "أن يستحضر", "تمكين المتعلم", "مهارة الملاحظة",
        "مهارة القراءة", "مهارة التعبير", "مهارة تفكيك", "مهارة التعليق", "اكتساب مهارتي"
    )

    /**
     * Checks strict semantic prefixes (teacher verbs, learner verbs, ability prefixes)
     * as specified in the hybrid architecture.
     */
    private fun inferStrictSemanticRole(clean: String): String? {
        val plain = cleanBullet(DocumentFileReader.stripFormattingTags(clean))
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
        val plain = cleanBullet(DocumentFileReader.stripFormattingTags(clean)).trim()
        val strict = inferStrictSemanticRole(plain)
        if (strict != null) return strict

        return when {
            plain.startsWith("يعرض ") -> "teacher"

            plain.startsWith("تقويم ") ||
                    plain.startsWith("فحص ") ||
                    plain.startsWith("مدى ") ||
                    plain.startsWith("ملاحظة مدى") ||
                    plain.startsWith("رصد تعثرات") ||
                    plain.startsWith("رصد قدرة") ||
                    plain.startsWith("رصد إمكانات") ||
                    plain.startsWith("مراقبة ") ||
                    plain.startsWith("دعم الفئات") ||
                    plain.startsWith("تقديم الدعم") ||
                    plain.startsWith("تصحيح الإنجازات") -> "eval"

            else -> fallbackField
        }
    }

    // ────────────────────── Intra-Cell Stage Splitting ──────────────────────

    private val STAGE_1_KEYWORDS = listOf(
        "تشخيص", "تمهيد", "استحضار المكتسبات", "الإعدادات القبلية", "تقويم تشخيصي"
    )

    private val STAGE_2_KEYWORDS = listOf(
        "التأطير والملاحظة", "ملاحظة النص", "تأطير النص", "صاحب النص", "مصدر النص",
        "دراسة العنوان", "الصورة المرفقة", "بداية النص", "بناء الفرضيات",
        "الفرضيات المصوغة", "أمثلة الانطلاق", "نص الانطلاق", "قراءة نص الانطلاق"
    )

    private val STAGE_3_KEYWORDS = listOf(
        "فهم النص", "القراءة النموذجية", "القراءة التوجيهية", "الإيضاحات اللغوية",
        "الشروح اللغوية", "الفكرة العامة", "الأفكار الأساسية", "تمحيص الفرضيات"
    )

    private val STAGE_4_KEYWORDS = listOf(
        "تحليل النص", "القراءة التحليلية", "الحقول المعجمية", "الحقول الدلالية",
        "ملامح التفسير", "عناصر رسالة النص", "لغة النص", "القيم",
        "الملاحظة والوصف", "التحليل والمناقشة", "استخراج عناصر الدرس"
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
     * and returns the corresponding stage key for [component], or null if it does not.
     */
    internal fun detectIntraCellStageSwitch(
        line: String,
        component: PedagogicalComponent,
        activeSessionNumber: Int = 1,
        hasComprehension: Boolean = false
    ): String? {
        val candidate = stripLeadingOrdinalsAndBullets(line)
        if (candidate.isEmpty() || candidate.startsWith("[جدول:")) return null

        val colonIdx = candidate.indexOf(':')
        val headerPrefix = when {
            colonIdx in 1..48 -> candidate.substring(0, colonIdx).trim()
            candidate.length <= 48 -> candidate
            else -> return null
        }

        if (component == PedagogicalComponent.EXPRESSION) {
            val directMatch = matchStageName(headerPrefix, component, activeSessionNumber, hasComprehension)
            if (directMatch != null) return directMatch
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
                1 -> if (activeSessionNumber == 2 || hasComprehension) "session2_intro" else "diagnostic"
                2 -> "observation"
                3 -> "comprehension"
                4 -> "analysis"
                5 -> "synthesis"
                6 -> "investment"
                else -> null
            }
            PedagogicalComponent.LANGUAGE -> when (stageNumber) {
                1 -> "lang_diagnostic"
                2, 3, 4 -> "lang_observation"
                5 -> "lang_synthesis"
                6 -> "lang_application"
                else -> null
            }
            PedagogicalComponent.EXPRESSION -> when (stageNumber) {
                1 -> when (activeSessionNumber) {
                    2 -> "insha_app_diagnostic"
                    3 -> "insha_prod_diagnostic"
                    4 -> "insha_corr_diagnostic"
                    else -> "insha_acq_diagnostic"
                }
                2, 3 -> "insha_acq_reading"
                4 -> "insha_acq_elements"
                5 -> "insha_acq_synthesis"
                6 -> when {
                    normalizeHeaderToken(headerPrefix).contains("انتاج") -> "insha_prod_execution"
                    normalizeHeaderToken(headerPrefix).contains("تطبيق") -> "insha_app_execution"
                    else -> "insha_acq_investment"
                }
                else -> null
            }
        }
    }

    private fun isPureStageHeadingOnly(
        line: String,
        component: PedagogicalComponent,
        activeSessionNumber: Int = 1,
        hasComprehension: Boolean = false
    ): Boolean {
        val stripped = stripLeadingOrdinalsAndBullets(line)
        val colonIdx = stripped.indexOf(':')
        if (colonIdx >= 0 && colonIdx < stripped.length - 1) {
            return false
        }
        val cleanHeading = stripped.removeSuffix(":").trim()
        return cleanHeading.length <= 35 && (
            matchStageName(cleanHeading, component, activeSessionNumber, hasComprehension) != null ||
                detectIntraCellStageSwitch(cleanHeading, component, activeSessionNumber, hasComprehension) != null
            )
    }

    private fun matchGenericSessionStage(inner: String, component: PedagogicalComponent): String? {
        val norm = normalizeHeaderToken(inner)
        return when (component) {
            PedagogicalComponent.READING -> when {
                norm.contains("الحصه الاولي") || norm == "الحصه 1" -> "diagnostic"
                norm.contains("الحصه الثانيه") || norm == "الحصه 2" -> "session2_intro"
                else -> null
            }
            PedagogicalComponent.LANGUAGE -> when {
                norm.contains("الحصه الاولي") || norm == "الحصه 1" -> "lang_diagnostic"
                norm.contains("الحصه الثانيه") || norm == "الحصه 2" -> "lang_application"
                else -> null
            }
            PedagogicalComponent.EXPRESSION -> when {
                norm.contains("الحصه الاولي") || norm == "الحصه 1" || norm.contains("انشطه الاكتساب") || norm.contains("حصه الاكتساب") -> "insha_acq_elements"
                norm.contains("الحصه الثانيه") || norm == "الحصه 2" || norm.contains("انشطه التطبيق") || norm.contains("حصه التطبيق") -> "insha_app_presentation"
                norm.contains("الحصه الثالثه") || norm == "الحصه 3" || norm.contains("انشطه الانتاج") || norm.contains("حصه الانتاج") -> "insha_prod_presentation"
                norm.contains("الحصه الرابعه") || norm == "الحصه 4" || norm.contains("انشطه التصحيح") || norm.contains("حصه التصحيح") -> "insha_corr_diagnosis"
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
    private fun detectStageWithRemainder(
        line: String,
        component: PedagogicalComponent,
        activeSessionNumber: Int = 1,
        hasComprehension: Boolean = false
    ): Pair<String, String?>? {
        val trimmed = line.trim()

        // 1. Explicit marker from DocumentFileReader: [مرحلة: ...] or [حصة: ...]
        if ((trimmed.startsWith("[مرحلة:") || trimmed.startsWith("[حصة:")) && trimmed.endsWith("]")) {
            val inner = trimmed.substringAfter(":").removeSuffix("]").trim()
            val key = matchStageName(inner, component, activeSessionNumber, hasComprehension)
            if (key != null) return key to null
            return null
        }

        // 2. Do NOT treat bulleted lines (-, +, •, *), table rows ([جدول:...]),
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
        if (headerPart.length > 42) return null

        // Do NOT confuse content sub-labels (like الفكرة العامة:, الحقول المعجمية:, العنوان:) with standalone stage headers
        if (KNOWN_SUB_LABELS.any { headerPart == it || headerPart.startsWith(it) }) {
            return null
        }

        // Special case: "التمهيد: ..." inside diagnostic stage is a content sub-label when remainder is a full sentence
        if (headerPart == "التمهيد" && !remainderPart.isNullOrBlank() && remainderPart.length > 15) {
            val diagKey = if (component == PedagogicalComponent.READING && (activeSessionNumber == 2 || hasComprehension)) {
                "session2_intro"
            } else {
                "diagnostic"
            }
            return diagKey to "التمهيد: $remainderPart"
        }

        val stageKey = matchNaturalStageHeading(headerPart, component, activeSessionNumber, hasComprehension) ?: return null

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

    /**
     * Strict natural stage heading matcher for plain paragraphs outside `[مرحلة: ...]`, preventing
     * arbitrary short sentences containing words like "ملاحظة" or "خلاصة" or "تشخيص" from being
     * mistaken for stage headings.
     */
    private fun matchNaturalStageHeading(
        headerPart: String,
        component: PedagogicalComponent,
        activeSessionNumber: Int,
        hasComprehension: Boolean
    ): String? {
        val stripped = stripLeadingOrdinalsAndBullets(headerPart).removeSuffix(":").trim()
        val norm = normalizeHeaderToken(stripped)
        if (norm.isEmpty() || norm.split(" ").size > 5) return null

        val isKnownStageTitle = norm in setOf(
            "تمهيد", "التمهيد", "تشخيص المكتسبات", "تقويم تشخيصي", "تشخيص المكتسبات والتمهيد",
            "التقويم التشخيصي والتمهيد", "استحضار المكتسبات", "مراقبه الاعداد القبلي",
            "ملاحظه النص", "تاطير النص", "ملاحظه النص وتاطيره", "التاطير والملاحظه", "الملاحظه والتاطير",
            "القراءه الاستكشافيه", "عتبات النص",
            "فهم النص", "القراءه التوجيهيه", "القراءه النموذجيه", "فهم المضامين", "الفهم",
            "تحليل النص", "القراءه التحليليه", "الدراسه المعجميه", "التحليل والمناقشه", "التحليل",
            "التركيب", "الخلاصه التركيبيه", "خلاصه تركيبيه", "القراءه التركيبيه", "التركيب والتقويم",
            "الاستثمار", "الاستثمار والبحث", "الانشطه الاستثماريه", "الامتداد", "امتدادات",
            "الوصف والتحليل", "الملاحظه والوصف", "البناء والتحليل", "العرض والتحليل",
            "ملاحظه الظاهره", "ملاحظه الامثله", "قراءه الامثله", "امثله الانطلاق",
            "الاستنتاج", "استنتاج", "القاعده", "القاعده التركيبيه",
            "التطبيقات", "تطبيقات", "تقويم اجمالي",
            "قراءه نص الانطلاق", "نص الانطلاق", "استخراج عناصر الدرس", "عناصر المهاره", "خطوات المهاره",
            "تقديم الدرس", "تقديم الموضوع", "انجاز المهاره", "التدرب علي المهاره",
            "تشخيص اخطاء المتعلمين", "تشخيص الاخطاء", "التصحيح الفردي", "الدعم والتعزيز", "التصحيح والدعم"
        ) || headerPart.trim().startsWith("مرحلة ") || headerPart.trim().startsWith("مقطع ")

        return if (isKnownStageTitle) {
            matchStageName(stripped, component, activeSessionNumber, hasComprehension)
        } else {
            null
        }
    }

    private fun matchStageName(
        text: String,
        component: PedagogicalComponent,
        activeSessionNumber: Int = 1,
        hasComprehension: Boolean = false
    ): String? {
        val t = text.replace(":", " ").replace("-", " ").replace(Regex("\\s+"), " ").trim()
        if (isDurationOrSegmentNoise(t)) return null

        return when (component) {
            PedagogicalComponent.READING -> when {
                (t == "تمهيد" || t == "التمهيد" || t.startsWith("تمهيد ")) && (activeSessionNumber == 2 || hasComprehension) ->
                    "session2_intro"
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
                t.contains("تشخيص") || t.contains("تقويم تشخيصي") || t.contains("استحضار المكتسبات") ||
                        t == "تمهيد" || t == "التمهيد" -> "lang_diagnostic"
                t.contains("الاستنتاج") || t.contains("استنتاج") || t.contains("القاعدة التركيبية") ||
                        t.contains("القاعدة") || t.contains("خلاصة") || t.contains("التركيب") -> "lang_synthesis"
                t.contains("التطبيقات") || t.contains("تطبيق") || t.contains("تقويم إجمالي") ||
                        t.contains("تمارين") || t.contains("استثمار") || t.contains("الاستثمار") -> "lang_application"
                t.contains("الوصف والتحليل") || t.contains("الملاحظة والوصف") || t.contains("البناء والتحليل") ||
                        t.contains("العرض والتحليل") || t.contains("التحليل والمناقشة") ||
                        t.contains("تحليل الأمثلة") || t.contains("تحليل الامثلة") || t.contains("تحليل الظاهرة") ||
                        t.contains("مناقشة الأمثلة") || t.contains("تفكيك الظاهرة") ||
                        t.contains("التحليل") || t == "الوصف" ||
                        t.contains("ملاحظة الظاهرة") || t.contains("ملاحظة الأمثلة") || t.contains("ملاحظة الامثلة") ||
                        t.contains("قراءة الأمثلة") || t.contains("عزل الظاهرة") || t.contains("أمثلة الانطلاق") ||
                        t.contains("امثلة الانطلاق") || t.contains("شواهد الانطلاق") || t == "الملاحظة" ||
                        t.contains("ملاحظة") -> "lang_observation"
                else -> null
            }

            PedagogicalComponent.EXPRESSION -> when {
                t.contains("تشخيص أخطاء") || t.contains("تشخيص الأخطاء") || t.contains("شبكة تشخيص") -> "insha_corr_diagnosis"
                t.contains("التصحيح الفردي") || t.contains("الدعم والتعزيز") -> "insha_corr_support"
                t.contains("تقويم تشخيصي") || t == "تمهيد" || t == "التمهيد" || t.startsWith("تقويم تشخيصي") -> when (activeSessionNumber) {
                    2 -> "insha_app_diagnostic"
                    3 -> "insha_prod_diagnostic"
                    4 -> "insha_corr_diagnostic"
                    else -> "insha_acq_diagnostic"
                }
                t.contains("قراءة نص الانطلاق") || t.contains("نص الانطلاق") -> "insha_acq_reading"
                t.contains("استخراج عناصر الدرس") || t.contains("عناصر المهارة") || t.contains("خطوات المهارة") ||
                        t.contains("التعريف بالمهارة") || t == "عناصر الدرس" -> "insha_acq_elements"
                t == "الاستنتاج" || t == "استنتاج" || t.contains("القاعدة") || t.contains("خلاصة المهارة") -> "insha_acq_synthesis"
                t == "الاستثمار" || t == "استثمار" || t.contains("امتداد") -> "insha_acq_investment"
                t.contains("تقديم الدرس") || t.contains("تقديم الموضوع") || t.contains("نص الموضوع") || t.contains("وضعية الانطلاق") -> when (activeSessionNumber) {
                    3 -> "insha_prod_presentation"
                    2 -> "insha_app_presentation"
                    else -> "insha_app_presentation"
                }
                t.contains("إنجاز المهارة") || t.contains("انجاز المهارة") || t.contains("التدرب على المهارة") || t.contains("التحرير") -> when (activeSessionNumber) {
                    3 -> "insha_prod_execution"
                    2 -> "insha_app_execution"
                    else -> "insha_app_execution"
                }
                t == "تصحيح" || t == "التصحيح" -> when (activeSessionNumber) {
                    2 -> "insha_app_correction"
                    3 -> "insha_prod_correction"
                    4 -> "insha_corr_support"
                    else -> "insha_app_correction"
                }
                t.contains("اكتساب") || t.contains("الحصة الأولى") || t.contains("تقديم المهارة") -> "insha_acq_elements"
                t.contains("تطبيق") || t.contains("الحصة الثانية") -> "insha_app_presentation"
                t.contains("إنتاج") || t.contains("انتاج") || t.contains("الحصة الثالثة") -> "insha_prod_presentation"
                t.contains("الحصة الرابعة") || t.contains("التصحيح والدعم") || t.contains("معالجة وتصحيح") || t.contains("أنشطة التصحيح") -> "insha_corr_diagnosis"
                else -> null
            }
        }
    }

    /**
     * Requires a colon `:` for single-word labels so short 1-word paragraphs (like "الأمثلة", "توجيهات",
     * "النصوص", "المهارات") without colons are never swallowed as empty field switches.
     */
    private fun detectField(line: String): String? {
        val t = line.trim()
        val colonIdx = t.indexOf(':')
        val hasColon = colonIdx >= 0
        val label = if (hasColon) t.substring(0, colonIdx).trim() else t
        if (label.length > 45) return null

        return when {
            label == "التقويم والدعم" || label == "تقويم ودعم" || label == "مؤشرات التقويم" ||
                label == "مؤشرات التقويم والدعم" || label == "التقويم المرحلي" || label == "معايير التقويم" ||
                (hasColon && (label == "التقويم" || label == "الدعم")) -> "eval"

            label == "أنشطة المدرس" || label == "أنشطة الأستاذ" || label == "الأنشطة التعليمية" ||
                label == "الأنشطة التعليمية التعلمية" ||
                label == "مهام المدرس" || label == "دور المدرس" ||
                (hasColon && label == "توجيهات") -> "teacher"

            label == "القدرات المستهدفة" || label == "القدرات والمهارات" ||
                label == "الأهداف التعلمية" || label == "الأهداف التعليمية" || label == "الكفايات المستهدفة" ||
                (hasColon && (label == "القدرات" || label == "الأهداف" || label == "المهارات")) -> "abil"

            label == "المحتوى الديداكتيكي" || label == "الأنشطة التعلمية" ||
                label == "المحتوى الديداكتيكي والأنشطة التعلمية" || label == "أنشطة المتعلم" ||
                label == "أنشطة المتعلمين" || label == "مهام المتعلم" || label == "سير الدرس" ||
                (hasColon && (label == "المحتوى" || label == "المضامين" || label == "الأمثلة" || label == "النصوص")) -> "content"

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
                    defaultAbil = "قدرة التلاميذ على استحضار مكتسباتهم السابقة التي تخدم الدرس قيد الإنجاز.",
                    defaultTeacher = "يطرح أسئلة تشخيصية لاستحضار مكتسبات المتعلمين القبلية وربطها بموضوع «$topic».",
                    defaultContent = "معطيات تمهيدية مرتبطة بمجال الدرس وموضوع «$topic».",
                    defaultEval = "يدعم الأستاذ جوانب التعثر ويعزز الإجابات السليمة."
                )
                fillStageDefaults(
                    stage = map["observation"],
                    stageSources = sources["observation"],
                    defaultAbil = "القدرة على استخراج القرائن والمؤشرات المساعدة على صياغة الفرضية.",
                    defaultTeacher = "يوجه المتعلمين إلى ملاحظة العنوان والمؤشرات الخارجية وبناء فرضيات القراءة.",
                    defaultContent = "1- الملاحظة والتأطير: دلالة العنوان وصاحب النص ونوعيته وصياغة فرضية القراءة لنص «$topic».",
                    defaultEval = "تصحيح إجابات المتعلمين وتدعيمها، وكل الفرضيات مقبولة شرط تمحيصها."
                )
                fillStageDefaults(
                    stage = map["comprehension"],
                    stageSources = sources["comprehension"],
                    defaultAbil = "مهارة القراءة الفاحصة والتمكن من فهم النص القرائي في سياقه العام وصياغة الفكرة العامة.",
                    defaultTeacher = "القراءة النموذجية للنص، والقراءة التناوبية للمتعلمين مع إضاءة النص وتذليل المعجم واستدراج المتعلمين لاستخلاص المضمون بالأسئلة.",
                    defaultContent = "الشروح اللغوية والمضمون العام للنص وأفكاره الأساسية مع تمحيص الفرضية.",
                    defaultEval = "تقويم النطق الصحيح ومخارج الحروف للمتعلمين وتقويم نمو الفهم."
                )
                fillStageDefaults(
                    stage = map["session2_intro"],
                    stageSources = sources["session2_intro"],
                    defaultAbil = "تذكير بالحصة السابقة.",
                    defaultTeacher = "استدراج المتعلمين لاسترجاع مكتسبات الحصة الأولى والتذكير بمضمون النص ودلالاته.",
                    defaultContent = "تذكير بالمضمون العام لنص «$topic» وأبرز أفكاره المستخلصة في الحصة الأولى.",
                    defaultEval = "رصد قدرة المتعلم(ة) على تذكر مكتسباته."
                )
                fillStageDefaults(
                    stage = map["analysis"],
                    stageSources = sources["analysis"],
                    defaultAbil = "يحدد المتعلم(ة) معجم النص ويستخرج المفردات الدالة عليه والأساليب والقيم التي بني عليها النص.",
                    defaultTeacher = "قراءة النص ومساعدة المتعلمين على جرد المعجم واستخراج عناصر التحليل والأساليب والقيم.",
                    defaultContent = "المعجم الدلالي والخصائص البنائية والأسلوبية والقيم المتضمنة في نص «$topic».",
                    defaultEval = "رصد إمكانات المتعلمين التحليلية وتعزيزها وتقويتها من خلال تفكيك النص."
                )
                fillStageDefaults(
                    stage = map["synthesis"],
                    stageSources = sources["synthesis"],
                    defaultAbil = "اكتساب مهارتي التركيب والإنتاج.",
                    defaultTeacher = "يطلب الأستاذ من المتعلمين تركيب ما تم التطرق إليه في الفهم والتحليل في خلاصات يثبتون أو ينفون فيها صحة الفرضية.",
                    defaultContent = "خلاصة تركيبية جامعة لمضامين نص «$topic» ومقاصده التربوية والفنية.",
                    defaultEval = "اختبار قدرة المتعلم(ة) على الإنتاج (إعادة بناء النص)."
                )
                fillStageDefaults(
                    stage = map["investment"],
                    stageSources = sources["investment"],
                    defaultAbil = "تنمية قدرة المتعلمين على التعبير واستثمار مكتسبات الدرس.",
                    defaultTeacher = "يطالب المتعلمين بإنجاز أنشطة البحث والاستثمار بالكتاب المدرسي لتقويم تعلماتهم.",
                    defaultContent = "تكليف المتعلمين بإنجاز أنشطة الاستثمار والبحث المرتبطة بموضوع «$topic».",
                    defaultEval = "تقويم قدرة المتعلم(ة) على تحويل المكتسبات لإنجاز أنشطة أخرى في وضعيات جديدة."
                )
            }

            PedagogicalComponent.LANGUAGE -> {
                fillStageDefaults(
                    stage = map["lang_diagnostic"],
                    stageSources = sources["lang_diagnostic"],
                    defaultAbil = "استحضار المتعلم مكتسباته السابقة الضرورية لبناء المفهوم اللغوي الجديد.",
                    defaultTeacher = "يطرح الأستاذ أسئلة مركزة لتشخيص المكتسبات القبلية وربطها بدرس «$topic».",
                    defaultContent = "إجابات المتعلمين تستثمر للربط بالدرس الجديد «$topic».",
                    defaultEval = "تقويم قدرة المتعلم على تذكر القواعد السابقة واستثمارها."
                )
                fillStageDefaults(
                    stage = map["lang_observation"],
                    stageSources = sources["lang_observation"],
                    defaultAbil = "ملاحظة الظاهرة اللغوية وتعرف سياقها والتمكن من وصفها وتفكيك عناصرها الصرفية والتركيبية.",
                    defaultTeacher = "يدون الأستاذ أمثلة الانطلاق على السبورة، ويرسم جدولاً تفاعلياً ويوجه المتعلمين بأسئلة ديدكتيكية لملئه واستخلاص الاستنتاجات الجزئية.",
                    defaultContent = "أمثلة الانطلاق وجدول الوصف والتحليل والاستنتاجات الجزئية ونموذج إعرابي لدرس «$topic».",
                    defaultEval = "مراقبة سلامة القراءة وتقويم مدى قدرة المتعلمين على استنباط خصائص الظاهرة اللغوية وشروطها."
                )
                fillStageDefaults(
                    stage = map["lang_synthesis"],
                    stageSources = sources["lang_synthesis"],
                    defaultAbil = "القدرة على تجميع الاستنتاجات الجزئية وبناء القاعدة العامة للدرس.",
                    defaultTeacher = "يطرح الأستاذ أسئلة تجميعية لمساعدة المتعلمين على صياغة القاعدة الكلية للدرس.",
                    defaultContent = "صياغة القاعدة العامة الشاملة لدرس «$topic».",
                    defaultEval = "تثبيت المكتسبات عبر قراءة القاعدة قراءة واعية والتمثيل لها."
                )
                fillStageDefaults(
                    stage = map["lang_application"],
                    stageSources = sources["lang_application"],
                    defaultAbil = "توظيف المتعلم تعلمه الجديد في سياقات لغوية تطبيقية وإعرابية مختلفة.",
                    defaultTeacher = "يسجل الأستاذ تطبيقات متنوعة على السبورة، ويطالب المتعلمين بإنجازها لتقويم فهمهم، ثم ينظم التصحيح الجماعي والفردي.",
                    defaultContent = "تمارين تطبيقية وإعرابية لترسيخ ظاهرة «$topic».",
                    defaultEval = "التصحيح الجماعي على السبورة، وتوجيه المتعلمين لتصحيح أخطائهم ذاتياً وتقديم الدعم الفوري للمتعثرين."
                )
            }

            PedagogicalComponent.EXPRESSION -> {
                val allExpStages = StageCatalog.EXPRESSION_STAGES
                for (meta in allExpStages) {
                    fillStageDefaults(
                        stage = map[meta.key],
                        stageSources = sources[meta.key],
                        defaultAbil = meta.description,
                        defaultTeacher = "يوجه الأستاذ المتعلمين خلال مرحلة «${meta.title}» ضمن «${meta.sessionName}» لمهارة «$topic».",
                        defaultContent = "${meta.title}: عناصر ومضامين «$topic» الخاصة بـ «${meta.sessionName}».",
                        defaultEval = "تقويم مدى تفاعل المتعلمين وتحقق أهداف مرحلة «${meta.title}»."
                    )
                }
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
