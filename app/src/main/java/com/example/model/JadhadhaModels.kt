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
        defaultMaterials = "الكتاب المدرسي (المختار في اللغة العربية)، السبورة، خطاطات، مسلاط"
    ),
    LANGUAGE(
        id = "language",
        title = "الدرس اللغوي",
        shortName = "الدرس اللغوي",
        description = "ملاحظة الظاهرة اللغوية وعزلها ووصفها وتحليلها وصياغة القواعد والتطبيق",
        defaultDuration = "ساعة واحدة",
        defaultMaterials = "الكتاب المدرسي، السبورة، جداول الظاهرة اللغوية، مسلاط"
    ),
    EXPRESSION(
        id = "expression",
        title = "التعبير والإنشاء",
        shortName = "التعبير والإنشاء",
        description = "التدرب على المهارات الإنشائية عبر 4 حصص: الاكتساب، التطبيق، الإنتاج، والتصحيح",
        defaultDuration = "4 ساعات (أربع حصص مبرمجة)",
        defaultMaterials = "الكتاب المدرسي، السبورة، نماذج المهارة، شبكات التقويم والتصحيح الذاتي"
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
    val level: String = "الثالثة ثانوي إعدادي",
    val unit: String = "الوحدة الأولى: القيم الإسلامية والوطنية",
    val domain: String = "مجال القيم الإسلامية والوطنية",
    val component: String = "النصوص القرائية",
    val duration: String = "ساعتان (حصتان)",
    val materials: String = "الكتاب المدرسي، السبورة، خطاطات، مسلاط",
    val communicativeComp: String = "أن يقرأ المتعلم النص قراءة مسترسلة معبرة، ويفهم معانيه وأبعاده الدلالية، ويتواصل بمضامينه.",
    val methodologicalComp: String = "أن يوظف المتعلم آليات القراءة المنهجية (الملاحظة، الفهم، التحليل، التركيب، والاستثمار).",
    val culturalComp: String = "أن يغني المتعلم رصيده المعرفي بقيم النص ومقاصده الإنسانية والوطنية."
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

    // 1. مراحل النصوص القرائية (Reading Stages)
    val READING_STAGES = listOf(
        StageMetadata(
            key = "diagnostic",
            title = "تشخيص المكتسبات والتمهيد",
            session = 1,
            sessionName = "الحصة الأولى: القراءة الاستكشافية والتوجيهية",
            description = "تقويم قبلي لتشخيص المعارف والمكتسبات السابقة وربطها بالدرس الجديد",
            componentType = PedagogicalComponent.READING
        ),
        StageMetadata(
            key = "observation",
            title = "ملاحظة النص وتأطيره",
            session = 1,
            sessionName = "الحصة الأولى: القراءة الاستكشافية والتوجيهية",
            description = "دراسة العتبات النصية (العنوان، الصورة، البداية والنهاية، صاحب النص) وبناء الفرضيات",
            componentType = PedagogicalComponent.READING
        ),
        StageMetadata(
            key = "comprehension",
            title = "فهم النص",
            session = 1,
            sessionName = "الحصة الأولى: القراءة الاستكشافية والتوجيهية",
            description = "القراءة النموذجية وشرح المفردات واستخلاص الفكرة العامة والأفكار الأساسية",
            componentType = PedagogicalComponent.READING
        ),
        StageMetadata(
            key = "analysis",
            title = "القراءة التحليلية",
            session = 2,
            sessionName = "الحصة الثانية: التحليل والتركيب والاستثمار",
            description = "دراسة المعجم والحقول الدلالية وأساليب النص والضمائر والقيم التربوية",
            componentType = PedagogicalComponent.READING
        ),
        StageMetadata(
            key = "synthesis",
            title = "التركيب",
            session = 2,
            sessionName = "الحصة الثانية: التحليل والتركيب والاستثمار",
            description = "تركيب نتائج القراءة وصياغة خلاصة عامة لمضامين النص ومقاصده الفنية",
            componentType = PedagogicalComponent.READING
        ),
        StageMetadata(
            key = "investment",
            title = "الاستثمار والامتداد",
            session = 2,
            sessionName = "الحصة الثانية: التحليل والتركيب والاستثمار",
            description = "أنشطة استثمارية وتطبيقات منزلية ووضعيات لإعادة توظيف التعلمات",
            componentType = PedagogicalComponent.READING
        )
    )

    // 2. مراحل الدرس اللغوي (Language / Grammar Stages)
    val LANGUAGE_STAGES = listOf(
        StageMetadata(
            key = "lang_diagnostic",
            title = "التقويم التشخيصي والتمهيد",
            session = 1,
            sessionName = "حصة الدرس اللغوي",
            description = "استحضار المكتسبات والظواهر اللغوية السابقة ذات الصلة بموضوع الدرس",
            componentType = PedagogicalComponent.LANGUAGE
        ),
        StageMetadata(
            key = "lang_observation",
            title = "ملاحظة الظاهرة اللغوية وعزلها",
            session = 1,
            sessionName = "حصة الدرس اللغوي",
            description = "قراءة أمثلة الانطلاق وتحديد الشواهد اللغوية المتضمنة للظاهرة وعزلها",
            componentType = PedagogicalComponent.LANGUAGE
        ),
        StageMetadata(
            key = "lang_analysis",
            title = "الوصف والتحليل",
            session = 1,
            sessionName = "حصة الدرس اللغوي",
            description = "تفكيك عناصر الظاهرة اللغوية عبر الجدول التحليلي وبناء القواعد الجزئية",
            componentType = PedagogicalComponent.LANGUAGE
        ),
        StageMetadata(
            key = "lang_synthesis",
            title = "الاستنتاج والقاعدة التركيبية",
            session = 1,
            sessionName = "حصة الدرس اللغوي",
            description = "تجميع القواعد الجزئية وصياغة القاعدة العامة للظاهرة اللغوية",
            componentType = PedagogicalComponent.LANGUAGE
        ),
        StageMetadata(
            key = "lang_application",
            title = "التطبيقات والتقويم الإجمالي",
            session = 1,
            sessionName = "حصة الدرس اللغوي",
            description = "تمارين تطبيقية فورية وأنشطة إعرابية لتثبيت المكتسبات وقياس مدى تحقق الهدف",
            componentType = PedagogicalComponent.LANGUAGE
        )
    )

    // 3. مراحل التعبير والإنشاء (Expression & Composition Stages - 4 Sessions)
    val EXPRESSION_STAGES = listOf(
        StageMetadata(
            key = "insha_acquisition",
            title = "حصة الاكتساب (أنشطة الاكتساب)",
            session = 1,
            sessionName = "الحصة الأولى: أنشطة الاكتساب",
            description = "التعريف بالمهارة المستهدفة وقراءة نص الانطلاق واستخلاص خطواتها وإجراءاتها المنهجية",
            componentType = PedagogicalComponent.EXPRESSION
        ),
        StageMetadata(
            key = "insha_application",
            title = "حصة التطبيق (أنشطة التطبيق)",
            session = 2,
            sessionName = "الحصة الثانية: أنشطة التطبيق",
            description = "التذكير بخطوات المهارة، وتطبيقها عملياً على نص الموضوع المقترح جماعياً وفردياً",
            componentType = PedagogicalComponent.EXPRESSION
        ),
        StageMetadata(
            key = "insha_production",
            title = "حصة الإنتاج (أنشطة الإنتاج والربط)",
            session = 3,
            sessionName = "الحصة الثالثة: أنشطة الإنتاج والربط",
            description = "عرض وضعية الانطلاق وتخطيط التصميم وتحرير مسودة الإنتاج الإنشائي الفردي",
            componentType = PedagogicalComponent.EXPRESSION
        ),
        StageMetadata(
            key = "insha_correction",
            title = "حصة التصحيح (أنشطة التصحيح والدعم)",
            session = 4,
            sessionName = "الحصة الرابعة: أنشطة التصحيح والدعم",
            description = "عرض شبكة التقويم وتصنيف الأخطاء المشتركة وتصحيح الإنتاجات ودعم التعثرات",
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

data class UnclassifiedLine(
    val lineNumber: Int,
    val text: String
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
    val isAiDelegated: Boolean = false
) {
    val totalFileFields: Int
        get() = stageSourceCounts.values.sumOf { it.fileCount }

    val totalDefaultFields: Int
        get() = stageSourceCounts.values.sumOf { it.defaultCount }

    val totalAiFields: Int
        get() = stageSourceCounts.values.sumOf { it.aiCount }
}

data class ParseResult(
    val plan: JadhadhaPlan,
    val report: ParseReport
)



