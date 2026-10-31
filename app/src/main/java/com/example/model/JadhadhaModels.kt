package com.example.model

import java.util.UUID

enum class PedagogicalComponent(
    val id: String,
    val title: String,
    val shortName: String,
    val description: String,
    val defaultDuration: String,
    val defaultMaterials: String
) {
    READING(
        id = "reading",
        title = "النصوص القرائية",
        shortName = "القراءة",
        description = "دراسة وتأطير وفهم وتحليل وتركيب واستثمار النصوص القرائية (حصتان)",
        defaultDuration = "ساعتان (حصتان)",
        defaultMaterials = "السبورة - كتاب المتعلم (ة) – دفتر المتعلم (ة)."
    ),
    LANGUAGE(
        id = "language",
        title = "الدرس اللغوي",
        shortName = "الدرس اللغوي",
        description = "ملاحظة الأمثلة ووصفها وتحليلها واستنتاج القاعدة الضابطة وتطبيقها",
        defaultDuration = "ساعة واحدة",
        defaultMaterials = "السبورة - كتاب المتعلم (ة) – دفتر المتعلم (ة)."
    ),
    EXPRESSION(
        id = "expression",
        title = "التعبير والإنشاء",
        shortName = "التعبير والإنشاء",
        description = "التدرب على المهارات الإنشائية عبر 4 حصص: الاكتساب، التطبيق، الإنتاج، والتصحيح",
        defaultDuration = "ساعة واحدة",
        defaultMaterials = "السبورة - كتاب المتعلم (ة) – دفتر المتعلم (ة)."
    );

    companion object {
        fun fromString(text: String): PedagogicalComponent {
            val t = text.trim()
            return when {
                t.contains("لغوي") || t.contains("اللغة") || t.contains("قواعد") -> LANGUAGE
                t.contains("تعبير") || t.contains("إنشاء") || t.contains("انشاء") || t.contains("مهارة") -> EXPRESSION
                else -> READING
            }
        }
    }
}

data class JadhadhaInfo(
    val level: String = "السنة الثالثة إعدادي",
    val unit: String = "الثانية",
    val domain: String = "القيم الوطنية والإنسانية",
    val component: String = "النصوص القرائية",
    val duration: String = "ساعتان (حصتان)",
    val materials: String = "السبورة - كتاب المتعلم (ة) – دفتر المتعلم (ة).",
    val reference: String = "المختار في اللغة العربية",
    val teacherName: String = "محمد العابد",
    val schoolName: String = "مدرسة أنوال (السلك الإعدادي)",
    val communicativeComp: String = "يتمكن المتعلم من التواصل بلغة عربية سليمة، مستثمرا تعلماته الفصلية ومطالعاته الحرة.",
    val methodologicalComp: String = "تمكن المتعلم من استعمال أدوات القراءة المنهجية لفهم النص السردي وتحليله، وتركيب عناصره، واستثماره.",
    val culturalComp: String = "يغني المتعلم رصيده الثقافي بمعطيات تاريخية ووطنية تتصل بمرحلة الاستعمار والكفاح من أجل الاستقلال."
)

enum class FieldSource(val arabicLabel: String) {
    FILE("من الملف"),
    DEFAULT("افتراضي"),
    AI("ذكاء اصطناعي"),
    USER("معدّل يدوياً");

    companion object {
        fun fromString(value: String?): FieldSource? {
            if (value.isNullOrBlank()) return null
            return entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
        }
    }
}

data class StageData(
    val eval: List<String> = emptyList(),
    val content: List<String> = emptyList(),
    val teacher: List<String> = emptyList(),
    val abil: List<String> = emptyList(),
    val sources: Map<String, FieldSource> = emptyMap()
) {
    fun sourceFor(field: String): FieldSource? = sources[field]

    fun withFieldSource(field: String, source: FieldSource): StageData {
        val updated = sources.toMutableMap()
        updated[field] = source
        return copy(sources = updated)
    }
}

data class StageMetadata(
    val key: String,
    val title: String,
    val session: Int, // Session number (1..4)
    val sessionName: String,
    val description: String,
    val componentType: PedagogicalComponent
)

object StageCatalog {

    // 1. مراحل النصوص القرائية (Reading Stages - 2 Sessions, 7 Stages including Session 2 Intro)
    val READING_STAGES = listOf(
        StageMetadata(
            key = "diagnostic",
            title = "تشخيص المكتسبات",
            session = 1,
            sessionName = "الحصة الأولى: تأطير النص والقراءة التوجيهية",
            description = "تقويم قبلي لتشخيص المعارف والمكتسبات السابقة وربطها بالدرس الجديد",
            componentType = PedagogicalComponent.READING
        ),
        StageMetadata(
            key = "observation",
            title = "ملاحظة النص",
            session = 1,
            sessionName = "الحصة الأولى: تأطير النص والقراءة التوجيهية",
            description = "دراسة العتبات النصية (صاحب النص، مصدره، نوعيته، العنوان، البداية والنهاية) وصياغة الفرضية",
            componentType = PedagogicalComponent.READING
        ),
        StageMetadata(
            key = "comprehension",
            title = "فهم النص",
            session = 1,
            sessionName = "الحصة الأولى: تأطير النص والقراءة التوجيهية",
            description = "القراءة النموذجية والتناوبية وإضاءة النص والشروح اللغوية والمضمون العام للنص",
            componentType = PedagogicalComponent.READING
        ),
        StageMetadata(
            key = "session2_intro",
            title = "تمهيد",
            session = 2,
            sessionName = "الحصة الثانية: القراءة التحليلية والتركيبية",
            description = "تذكير بالحصة السابقة واستدراج المتعلمين لاسترجاع مكتسبات الحصة الأولى",
            componentType = PedagogicalComponent.READING
        ),
        StageMetadata(
            key = "analysis",
            title = "القراءة التحليلية",
            session = 2,
            sessionName = "الحصة الثانية: القراءة التحليلية والتركيبية",
            description = "تحليل أحداث النص وشخصياته والزمان والمكان والسارد والرؤية السردية وأنماط الحكي واللغة والقيم",
            componentType = PedagogicalComponent.READING
        ),
        StageMetadata(
            key = "synthesis",
            title = "التركيب",
            session = 2,
            sessionName = "الحصة الثانية: القراءة التحليلية والتركيبية",
            description = "تركيب ما تم التطرق إليه في الفهم والتحليل في خلاصة تبرز صحة الفرضية وأبعاد النص",
            componentType = PedagogicalComponent.READING
        ),
        StageMetadata(
            key = "investment",
            title = "الاستثمار",
            session = 2,
            sessionName = "الحصة الثانية: القراءة التحليلية والتركيبية",
            description = "إنجاز أنشطة الاستثمار والبحث بالكتاب المدرسي لتقويم التعلمات وتحويل المكتسبات",
            componentType = PedagogicalComponent.READING
        )
    )

    // 2. مراحل الدرس اللغوي (Language / Grammar Stages - 4 Official Exemplar Stages)
    val LANGUAGE_STAGES = listOf(
        StageMetadata(
            key = "lang_diagnostic",
            title = "تقويم تشخيصي",
            session = 1,
            sessionName = "حصة الدرس اللغوي",
            description = "استحضار المكتسبات والظواهر اللغوية السابقة ذات الصلة بموضوع الدرس والتمهيد له",
            componentType = PedagogicalComponent.LANGUAGE
        ),
        StageMetadata(
            key = "lang_observation",
            title = "ملاحظة الأمثلة ووصفها وتحليلها",
            session = 1,
            sessionName = "حصة الدرس اللغوي",
            description = "ملاحظة أمثلة الانطلاق وتحليل الظاهرة اللغوية عبر الجداول التوضيحية والاستنتاجات الجزئية",
            componentType = PedagogicalComponent.LANGUAGE
        ),
        StageMetadata(
            key = "lang_synthesis",
            title = "الاستنتاج",
            session = 1,
            sessionName = "حصة الدرس اللغوي",
            description = "صياغة الاستنتاج العام للدرس اللغوي مع نموذج إعرابي تطبيقي",
            componentType = PedagogicalComponent.LANGUAGE
        ),
        StageMetadata(
            key = "lang_application",
            title = "تطبيق",
            session = 1,
            sessionName = "حصة الدرس اللغوي",
            description = "إنجاز أنشطة «أطبق» بالكتاب المدرسي وتصحيحها جماعياً وفردياً على السبورة والدفاتر",
            componentType = PedagogicalComponent.LANGUAGE
        )
    )

    // 3. مراحل التعبير والإنشاء (Expression & Composition Stages - 4 Sessions, 16 Sub-Stages)
    val EXPRESSION_STAGES = listOf(
        // الحصة الأولى: حصة الاكتساب
        StageMetadata(
            key = "insha_acq_diagnostic",
            title = "تقويم تشخيصي",
            session = 1,
            sessionName = "حصة الاكتساب",
            description = "ربط الدرس الجديد بالمكتسبات السابقة واستدراج المتعلمين لتحديد مفهوم المهارة",
            componentType = PedagogicalComponent.EXPRESSION
        ),
        StageMetadata(
            key = "insha_acq_reading",
            title = "قراءة نص الانطلاق وفهمه",
            session = 1,
            sessionName = "حصة الاكتساب",
            description = "قراءة نص الانطلاق قراءة متأنية وفهم مضمونه العام وأحداثه",
            componentType = PedagogicalComponent.EXPRESSION
        ),
        StageMetadata(
            key = "insha_acq_elements",
            title = "استخراج عناصر الدرس",
            session = 1,
            sessionName = "حصة الاكتساب",
            description = "تحليل نص الانطلاق واستخراج عناصر المهارة وخصائصها البنائية واللغوية",
            componentType = PedagogicalComponent.EXPRESSION
        ),
        StageMetadata(
            key = "insha_acq_synthesis",
            title = "الاستنتاج",
            session = 1,
            sessionName = "حصة الاكتساب",
            description = "صياغة الاستنتاج العام المنظم لعناصر المهارة وخطواتها",
            componentType = PedagogicalComponent.EXPRESSION
        ),
        StageMetadata(
            key = "insha_acq_investment",
            title = "الاستثمار",
            session = 1,
            sessionName = "حصة الاكتساب",
            description = "تكليف المتعلمين بنشاط استثماري منزلي لتطبيق خطوات المهارة المكتسبة",
            componentType = PedagogicalComponent.EXPRESSION
        ),

        // الحصة الثانية: حصة التطبيق
        StageMetadata(
            key = "insha_app_diagnostic",
            title = "تقويم تشخيصي (تمهيد)",
            session = 2,
            sessionName = "حصة التطبيق",
            description = "استحضار عناصر المهارة المكتسبة في الحصة السابقة",
            componentType = PedagogicalComponent.EXPRESSION
        ),
        StageMetadata(
            key = "insha_app_presentation",
            title = "تقديم الدرس",
            session = 2,
            sessionName = "حصة التطبيق",
            description = "كتابة نص الموضوع التطبيقي على السبورة وقراءته واستخراج عناصره ومطلوبه",
            componentType = PedagogicalComponent.EXPRESSION
        ),
        StageMetadata(
            key = "insha_app_execution",
            title = "إنجاز المهارة",
            session = 2,
            sessionName = "حصة التطبيق",
            description = "تطبيق خطوات المهارة لإنجاز المطلوب جماعياً وفردياً",
            componentType = PedagogicalComponent.EXPRESSION
        ),
        StageMetadata(
            key = "insha_app_correction",
            title = "تصحيح",
            session = 2,
            sessionName = "حصة التطبيق",
            description = "قراءة الإنتاجات التطبيقية وتصحيحها جماعياً وتدوين النموذج على السبورة",
            componentType = PedagogicalComponent.EXPRESSION
        ),

        // الحصة الثالثة: حصة الإنتاج
        StageMetadata(
            key = "insha_prod_diagnostic",
            title = "تقويم تشخيصي (تمهيد)",
            session = 3,
            sessionName = "حصة الإنتاج",
            description = "استحضار مكتسبات حصتي الاكتساب والتطبيق وخطوات المهارة",
            componentType = PedagogicalComponent.EXPRESSION
        ),
        StageMetadata(
            key = "insha_prod_presentation",
            title = "تقديم الدرس",
            session = 3,
            sessionName = "حصة الإنتاج",
            description = "كتابة نص موضوع الإنتاج على السبورة وشرح المطلوب وعناصر التصميم",
            componentType = PedagogicalComponent.EXPRESSION
        ),
        StageMetadata(
            key = "insha_prod_execution",
            title = "إنجاز المهارة",
            session = 3,
            sessionName = "حصة الإنتاج",
            description = "التحرير الفردي للموضوع الإنشائي وفق التصميم المنهجي والضوابط اللغوية",
            componentType = PedagogicalComponent.EXPRESSION
        ),
        StageMetadata(
            key = "insha_prod_correction",
            title = "تصحيح",
            session = 3,
            sessionName = "حصة الإنتاج",
            description = "جمع أوراق التحرير أو الدفاتر قصد التصحيح والتقويم",
            componentType = PedagogicalComponent.EXPRESSION
        ),

        // الحصة الرابعة: حصة التصحيح
        StageMetadata(
            key = "insha_corr_diagnostic",
            title = "تقويم تشخيصي (تمهيد)",
            session = 4,
            sessionName = "حصة التصحيح",
            description = "استحضار خطوات المهارة وعناصرها الأساسية والضوابط المنهجية",
            componentType = PedagogicalComponent.EXPRESSION
        ),
        StageMetadata(
            key = "insha_corr_diagnosis",
            title = "تشخيص أخطاء المتعلمين وتصنيفها",
            session = 4,
            sessionName = "حصة التصحيح",
            description = "سطر شبكة تشخيص الأخطاء المشتركة وتصنيفها (إملائي، تركيبي، معجمي) وتصويبها مع التعليل",
            componentType = PedagogicalComponent.EXPRESSION
        ),
        StageMetadata(
            key = "insha_corr_support",
            title = "التصحيح الفردي والدعم والتعزيز",
            session = 4,
            sessionName = "حصة التصحيح",
            description = "توزيع أوراق التحرير للتصحيح الذاتي وأنشطة التثبيت وإغناء المعجم وقراءة النماذج المتميزة",
            componentType = PedagogicalComponent.EXPRESSION
        )
    )

    val ALL_STAGES = READING_STAGES + LANGUAGE_STAGES + EXPRESSION_STAGES

    fun getStagesForComponent(component: PedagogicalComponent): List<StageMetadata> {
        return when (component) {
            PedagogicalComponent.READING -> READING_STAGES
            PedagogicalComponent.LANGUAGE -> LANGUAGE_STAGES
            PedagogicalComponent.EXPRESSION -> EXPRESSION_STAGES
        }
    }

    fun getByKey(key: String): StageMetadata? = ALL_STAGES.firstOrNull { it.key == key }
}

object JadhadhaCategories {
    const val READING = "نصوص قرائية"
    const val LANGUAGE = "درس لغوي"
    const val EXPRESSION = "تعبير وإنشاء"

    val ALL = listOf(READING, LANGUAGE, EXPRESSION)

    fun forComponent(comp: PedagogicalComponent): String = when (comp) {
        PedagogicalComponent.READING -> READING
        PedagogicalComponent.LANGUAGE -> LANGUAGE
        PedagogicalComponent.EXPRESSION -> EXPRESSION
    }
}

data class JadhadhaPlan(
    val id: String = UUID.randomUUID().toString(),
    val topic: String,
    val componentType: PedagogicalComponent = PedagogicalComponent.READING,
    val category: String = JadhadhaCategories.forComponent(componentType),
    val info: JadhadhaInfo = JadhadhaInfo(),
    val stages: Map<String, StageData> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis()
)

data class SuggestedObjectives(
    val topic: String,
    val component: PedagogicalComponent,
    val communicativeComp: String,
    val methodologicalComp: String,
    val culturalComp: String,
    val cognitiveObjectives: List<String> = emptyList(),
    val skillObjectives: List<String> = emptyList(),
    val affectiveObjectives: List<String> = emptyList(),
    val stageAbilities: Map<String, List<String>> = emptyMap(),
    val source: FieldSource = FieldSource.AI
)

enum class DocumentElementType(val arabicLabel: String) {
    PARAGRAPH("فقرة نصية"),
    TABLE_CELL("خلية جدول"),
    HEADER_FOOTER("ترويسة/تذييل"),
    PLAIN_LINE("سطر نصي")
}

enum class ContentUnitStatus(val arabicLabel: String) {
    EXTRACTED("مستخرج"),
    CLASSIFIED("مصنّف"),
    NEEDS_REVIEW("يحتاج إلى مراجعة"),
    UNEXTRACTABLE("تعذر استخراجه")
}

data class UnitLocation(
    val sourcePart: String = "word/document.xml",
    val elementIndex: Int = 0,
    val paragraphIndex: Int = 0,
    val tableIndex: Int? = null,
    val rowIndex: Int? = null,
    val colIndex: Int? = null,
    val lineNumber: Int = 0
)

data class ExtractedUnit(
    val id: String,
    val verbatimText: String,
    val elementType: DocumentElementType = DocumentElementType.PLAIN_LINE,
    val location: UnitLocation = UnitLocation(),
    val status: ContentUnitStatus = ContentUnitStatus.EXTRACTED,
    val assignedStageKey: String? = null,
    val assignedField: String? = null,
    val finalClassifiedText: String? = null
)

data class TextModificationRecord(
    val unitId: String,
    val originalText: String,
    val finalText: String,
    val location: UnitLocation = UnitLocation()
)

data class ContentIntegrityReport(
    val originalRawText: String,
    val extractedUnits: List<ExtractedUnit> = emptyList(),
    val classifiedUnits: List<ExtractedUnit> = emptyList(),
    val unclassifiedUnits: List<ExtractedUnit> = emptyList(),
    val unextractableUnits: List<ExtractedUnit> = emptyList(),
    val unextractableItems: List<String> = emptyList(),
    val finalUsedItems: List<String> = emptyList(),
    val missingUnits: List<ExtractedUnit> = emptyList(),
    val duplicateUnits: List<ExtractedUnit> = emptyList(),
    val duplicateItems: List<String> = emptyList(),
    val modifiedUnits: List<TextModificationRecord> = emptyList(),
    val actualCompletionPercentage: Int = 0,
    val extractionFidelityPercentage: Int = 100
) {
    val hasIssues: Boolean
        get() = missingUnits.isNotEmpty() ||
            duplicateUnits.isNotEmpty() ||
            duplicateItems.isNotEmpty() ||
            modifiedUnits.isNotEmpty() ||
            unextractableItems.isNotEmpty() ||
            unclassifiedUnits.isNotEmpty()
}

data class UnclassifiedLine(
    val lineNumber: Int,
    val text: String,
    val unitId: String = "unit_$lineNumber",
    val verbatimText: String = text,
    val location: UnitLocation = UnitLocation(lineNumber = lineNumber)
)

data class StageSourceCount(
    val stageKey: String,
    val stageTitle: String,
    val fileCount: Int,
    val defaultCount: Int,
    val aiCount: Int = 0,
    val userCount: Int = 0
)

data class ParseReport(
    val detectedComponent: PedagogicalComponent,
    val isComponentDefaulted: Boolean,
    val usedKeywordsForComponent: Boolean = !isComponentDefaulted,
    val stageSourceCounts: Map<String, StageSourceCount> = emptyMap(),
    val unclassifiedLines: List<UnclassifiedLine> = emptyList(),
    val aiClassifiedLinesCount: Int = 0,
    val aiRefinedFieldsCount: Int = 0,
    val isAiDelegated: Boolean = false,
    val originalRawText: String = "",
    val extractedUnits: List<ExtractedUnit> = emptyList(),
    val unextractableItems: List<String> = emptyList(),
    val integrityReport: ContentIntegrityReport? = null
) {
    val totalFileFields: Int
        get() = stageSourceCounts.values.sumOf { it.fileCount }

    val totalDefaultFields: Int
        get() = stageSourceCounts.values.sumOf { it.defaultCount }

    val totalAiFields: Int
        get() = stageSourceCounts.values.sumOf { it.aiCount }

    val totalUserFields: Int
        get() = stageSourceCounts.values.sumOf { it.userCount }

    val verificationCompletionPercentage: Int
        get() = integrityReport?.actualCompletionPercentage ?: run {
            val total = totalFileFields + totalDefaultFields + totalAiFields + totalUserFields
            if (total == 0) 0
            else {
                val verifiedFields = totalFileFields + totalUserFields
                val fieldRatio = verifiedFields.toFloat() / total.toFloat()
                val penalty = if (unclassifiedLines.isNotEmpty() || unextractableItems.isNotEmpty()) 0.85f else 1.0f
                (fieldRatio * penalty * 100f).toInt().coerceIn(0, if (unclassifiedLines.isNotEmpty() || totalDefaultFields > 0) 99 else 100)
            }
        }
}

data class ParseResult(
    val plan: JadhadhaPlan,
    val report: ParseReport
)

