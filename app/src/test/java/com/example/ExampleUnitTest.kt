package com.example

import com.example.extractor.DocumentFileReader
import com.example.extractor.JadhadhaParser
import com.example.python.PythonToolkit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun testParserExtractsSections() {
        val raw = """
            موضوع الدرس: مسيرة امرأة
            المستوى: الثالثة ثانوي إعدادي
            الوحدة: الرابعة
            المكون: النصوص القرائية
            تشخيص المكتسبات:
            - سؤال حول كفاح المرأة
            ملاحظة النص:
            العنوان: مسيرة امرأة
            الفهم:
            الفكرة العامة: كفاح المرأة من أجل الكرامة
            التحليل:
            الحقول المعجمية: حقل المعاناة وحقل النجاح
            التركيب:
            خلاصة تركيبية عامة
            الاستثمار:
            كتابة فقرة حول الموضوع
        """.trimIndent()

        val plan = JadhadhaParser.parseRawText(raw, "مسيرة امرأة")
        assertEquals("مسيرة امرأة", plan.topic)
        assertEquals("الثالثة ثانوي إعدادي", plan.info.level)
        assertTrue(plan.stages.containsKey("diagnostic"))
        assertTrue(plan.stages.containsKey("observation"))
        assertTrue(plan.stages.containsKey("comprehension"))
        assertTrue(plan.stages.containsKey("analysis"))
        assertTrue(plan.stages.containsKey("synthesis"))
        assertTrue(plan.stages.containsKey("investment"))
    }

    @Test
    fun testDocxXmlTextExtraction() {
        val sampleXml = """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>موضوع الدرس: المنفى</w:t></w:r></w:p>
                <w:p><w:r><w:t>المستوى: الثالثة ثانوي إعدادي</w:t></w:r></w:p>
                <w:p><w:r><w:t>تشخيص المكتسبات: تقويم قبلي</w:t></w:r></w:p>
              </w:body>
            </w:document>
        """.trimIndent()

        val extracted = DocumentFileReader.parseDocumentXml(sampleXml)
        assertTrue(extracted.contains("موضوع الدرس: المنفى"))
        assertTrue(extracted.contains("المستوى: الثالثة ثانوي إعدادي"))
        assertTrue(extracted.contains("تشخيص المكتسبات: تقويم قبلي"))
    }

    @Test
    fun testPythonScriptAvailable() {
        val script = PythonToolkit.GET_PYTHON_SCRIPT()
        assertTrue(script.contains("jadhadha_build.py"))
        assertTrue(script.contains("python-docx"))

        val langScript = PythonToolkit.getScriptForComponent(com.example.model.PedagogicalComponent.LANGUAGE)
        assertTrue(langScript.contains("build_jadhadha.py"))
        assertTrue(langScript.contains("الدرس اللغوي"))

        val inshaScript = PythonToolkit.getScriptForComponent(com.example.model.PedagogicalComponent.EXPRESSION)
        assertTrue(inshaScript.contains("build_insha.py"))
        assertTrue(inshaScript.contains("التعبير والإنشاء"))
    }

    @Test
    fun testLanguageLessonParsing() {
        val raw = """
            موضوع الدرس: أسلوب الاختصاص
            المكون: الدرس اللغوي
            المستوى: الثالثة ثانوي إعدادي
            تقويم تشخيصي:
            تذكير بأسلوب النداء
            ملاحظة الظاهرة اللغوية:
            أنا - المغربيَّ - أعتز بوطني
            الوصف والتحليل:
            الاسم المختص معرف بأل ومنصوب على الاختصاص
            الاستنتاج والقاعدة:
            الاختصاص تركيب يتقدمه ضمير متكلم
            التطبيقات والتقويم:
            أنشئ جملا تشتمل على اسم مختص
        """.trimIndent()

        val plan = JadhadhaParser.parseRawText(raw, "أسلوب الاختصاص", com.example.model.PedagogicalComponent.LANGUAGE)
        assertEquals("أسلوب الاختصاص", plan.topic)
        assertEquals(com.example.model.PedagogicalComponent.LANGUAGE, plan.componentType)
        assertTrue(plan.stages.containsKey("lang_diagnostic"))
        assertTrue(plan.stages.containsKey("lang_observation"))
        assertTrue(plan.stages.containsKey("lang_analysis"))
        assertTrue(plan.stages.containsKey("lang_synthesis"))
        assertTrue(plan.stages.containsKey("lang_application"))
    }

    @Test
    fun testExpressionLessonParsing() {
        val raw = """
            موضوع الدرس: مهارة كتابة سيرة ذاتية
            المكون: التعبير والإنشاء
            المستوى: الثالثة ثانوي إعدادي
            أنشطة الاكتساب:
            التعرف على خصائص السيرة الذاتية واستعمال ضمير المتكلم
            أنشطة التطبيق:
            كتابة فقرة عن مرحلة الطفولة
            أنشطة الإنتاج:
            إنجاز موضوع متكامل عن أول يوم في المدرسة
            أنشطة التصحيح والدعم:
            عرض الأعمال وتصحيح الأخطاء اللغوية
        """.trimIndent()

        val plan = JadhadhaParser.parseRawText(raw, "مهارة كتابة سيرة ذاتية", com.example.model.PedagogicalComponent.EXPRESSION)
        assertEquals("مهارة كتابة سيرة ذاتية", plan.topic)
        assertEquals(com.example.model.PedagogicalComponent.EXPRESSION, plan.componentType)
        assertTrue(plan.stages.containsKey("insha_acquisition"))
        assertTrue(plan.stages.containsKey("insha_application"))
        assertTrue(plan.stages.containsKey("insha_production"))
        assertTrue(plan.stages.containsKey("insha_correction"))
    }

    @Test
    fun testSuggestedObjectivesApplication() {
        val originalPlan = com.example.model.SamplePlans.MANFA_PLAN
        val suggestions = com.example.model.SuggestedObjectives(
            topic = "المنفى",
            component = com.example.model.PedagogicalComponent.READING,
            communicativeComp = "أن يقرأ المتعلم النص ويستوعب أبعاد النفي القسري والوفاء للوطن.",
            methodologicalComp = "أن يكتسب المتعلم مهارة تفكيك الحقول الدلالية ومقاربة العتبات النصية.",
            culturalComp = "أن يتشبع بقيم التضحية الوطنية واستحضار ملحمة الاستقلال.",
            cognitiveObjectives = listOf("أن يتعرف المتعلم على سياق النفي.", "أن يحدد الأفكار الأساسية."),
            skillObjectives = listOf("أن يحلل المعجم الدلالي.", "أن يركب خلاصة القراءة."),
            affectiveObjectives = listOf("أن يستشعر قيمة الحرية والكرامة."),
            stageAbilities = mapOf(
                "diagnostic" to listOf("القدرة على استحضار السياق التاريخي للنفي."),
                "observation" to listOf("القدرة على استنطاق مؤشرات العنوان والصورة.")
            )
        )

        assertEquals("المنفى", suggestions.topic)
        assertEquals(2, suggestions.cognitiveObjectives.size)
        assertEquals(2, suggestions.skillObjectives.size)
        assertEquals(1, suggestions.affectiveObjectives.size)
        assertEquals(2, suggestions.stageAbilities.size)

        // Verify applying to plan info
        val updatedPlan = originalPlan.copy(
            info = originalPlan.info.copy(
                communicativeComp = suggestions.communicativeComp,
                methodologicalComp = suggestions.methodologicalComp,
                culturalComp = suggestions.culturalComp
            )
        )
        assertEquals(suggestions.communicativeComp, updatedPlan.info.communicativeComp)
        assertEquals(suggestions.methodologicalComp, updatedPlan.info.methodologicalComp)
        assertEquals(suggestions.culturalComp, updatedPlan.info.culturalComp)
    }
}

