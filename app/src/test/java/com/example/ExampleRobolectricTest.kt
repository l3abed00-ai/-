package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.docx.DocxGenerator
import com.example.model.SamplePlans
import com.example.python.PythonToolkit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.flow.first
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("الجذاذة النموذجية", appName)
    }

    @Test
    fun `generate valid docx file`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = DocxGenerator.generateDocx(context, SamplePlans.MANFA_PLAN)
        assertTrue(file.exists())
        assertTrue(file.length() > 500)

        // Verify valid Zip archive with OOXML files
        val zip = ZipFile(file)
        val documentXml = zip.getEntry("word/document.xml")
        val contentTypes = zip.getEntry("[Content_Types].xml")
        assertTrue(documentXml != null)
        assertTrue(contentTypes != null)
        zip.close()
    }

    @Test
    fun `generate valid docx file for language lesson`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = DocxGenerator.generateDocx(context, SamplePlans.ISM_FAIL_PLAN)
        assertTrue(file.exists())
        assertTrue(file.length() > 500)

        val zip = ZipFile(file)
        val documentXml = zip.getEntry("word/document.xml")
        assertTrue(documentXml != null)
        zip.close()
    }

    @Test
    fun `generate valid docx file for expression lesson`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = DocxGenerator.generateDocx(context, SamplePlans.INSHA_HIKAYA_PLAN)
        assertTrue(file.exists())
        assertTrue(file.length() > 500)

        val zip = ZipFile(file)
        val documentXml = zip.getEntry("word/document.xml")
        assertTrue(documentXml != null)
        zip.close()
    }

    @Test
    fun `test json export serialization with robolectric`() {
        val plan = SamplePlans.MANFA_PLAN
        val jsonStr = PythonToolkit.planToJsonString(plan)
        assertNotNull(jsonStr)
        assertTrue(jsonStr.contains("المنفى"))
        assertTrue(jsonStr.contains("diagnostic"))
        assertTrue(jsonStr.contains("observation"))
    }

    @Test
    fun `test Room database save and retrieve plan`() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, com.example.data.AppDatabase::class.java).build()
        val repository = com.example.data.JadhadhaRepository(db.jadhadhaDao())

        val plan = SamplePlans.MANFA_PLAN.copy(
            id = "test_plan_1",
            topic = "درس اختباري",
            category = "نصوص قرائية"
        )
        repository.savePlan(plan)

        val retrieved = repository.getPlanById("test_plan_1")
        assertNotNull(retrieved)
        assertEquals("درس اختباري", retrieved?.topic)
        assertEquals(plan.componentType, retrieved?.componentType)
        assertEquals("نصوص قرائية", retrieved?.category)
        assertEquals(plan.info.level, retrieved?.info?.level)
        assertTrue(retrieved?.stages?.containsKey("diagnostic") == true)

        repository.deletePlan("test_plan_1")
        val afterDelete = repository.getPlanById("test_plan_1")
        assertTrue(afterDelete == null)
        db.close()
    }

    @Test
    fun `test Room category field filtering and query`() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, com.example.data.AppDatabase::class.java).build()
        val repository = com.example.data.JadhadhaRepository(db.jadhadhaDao())

        val planReading = SamplePlans.MANFA_PLAN.copy(id = "reading_1", topic = "نص قرائي", category = "نصوص قرائية")
        val planLanguage = SamplePlans.ISM_FAIL_PLAN.copy(id = "lang_1", topic = "درس الفاعل", category = "درس لغوي")
        val planExpression = SamplePlans.INSHA_HIKAYA_PLAN.copy(id = "insha_1", topic = "مهارة الحكاية", category = "تعبير وإنشاء")

        repository.savePlan(planReading)
        repository.savePlan(planLanguage)
        repository.savePlan(planExpression)

        // Query by specific category
        val langResults = repository.getPlansByCategory("درس لغوي").first()
        assertEquals(1, langResults.size)
        assertEquals("درس الفاعل", langResults[0].topic)
        assertEquals("درس لغوي", langResults[0].category)

        val readingResults = repository.getPlansByCategory("نصوص قرائية").first()
        assertEquals(1, readingResults.size)
        assertEquals("نص قرائي", readingResults[0].topic)
        assertEquals("نصوص قرائية", readingResults[0].category)

        val expressionResults = repository.getPlansByCategory("تعبير وإنشاء").first()
        assertEquals(1, expressionResults.size)
        assertEquals("مهارة الحكاية", expressionResults[0].topic)
        assertEquals("تعبير وإنشاء", expressionResults[0].category)

        val allCategories = repository.allCategories.first()
        assertTrue(allCategories.contains("نصوص قرائية"))
        assertTrue(allCategories.contains("درس لغوي"))
        assertTrue(allCategories.contains("تعبير وإنشاء"))

        db.close()
    }

    @Test
    fun `test JadhadhaViewModel instantiation with AndroidViewModelFactory`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(app)
        val vm = factory.create(com.example.ui.JadhadhaViewModel::class.java)
        assertNotNull(vm)
        assertNotNull(vm.uiState.value.currentPlan)
    }

    @Test
    fun `test docx roundtrip extraction preserves all stage cells without displacement or empty fields`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sourcePlans = listOf(
            SamplePlans.MANFA_PLAN,
            SamplePlans.ISM_FAIL_PLAN,
            SamplePlans.INSHA_HIKAYA_PLAN
        )

        for (sourcePlan in sourcePlans) {
            val docxFile = DocxGenerator.generateDocx(context, sourcePlan)
            val extractedText = java.io.FileInputStream(docxFile).use { stream ->
                com.example.extractor.DocumentFileReader.extractTextFromDocx(stream)
            }
            assertTrue("Extracted text should not be blank for ${sourcePlan.topic}", extractedText.isNotBlank())

            val parsedPlan = com.example.extractor.JadhadhaParser.parseRawText(
                raw = extractedText,
                fallbackTopic = sourcePlan.topic,
                forcedComponent = sourcePlan.componentType
            )

            assertEquals(sourcePlan.componentType, parsedPlan.componentType)
            assertEquals(sourcePlan.stages.size, parsedPlan.stages.size)

            for ((stageKey, sourceStage) in sourcePlan.stages) {
                val parsedStage = parsedPlan.stages[stageKey]
                assertNotNull("Stage $stageKey must exist", parsedStage)
                assertTrue("abil in $stageKey must not be empty", parsedStage!!.abil.isNotEmpty())
                assertTrue("teacher in $stageKey must not be empty", parsedStage.teacher.isNotEmpty())
                assertTrue("content in $stageKey must not be empty", parsedStage.content.isNotEmpty())
                assertTrue("eval in $stageKey must not be empty", parsedStage.eval.isNotEmpty())

                // Verify that first item of each column stays in its designated column
                val expectedAbilSample = sourceStage.abil.first().replace(Regex("</?u>"), "").trim()
                val expectedTeacherSample = sourceStage.teacher.first().replace(Regex("</?u>"), "").trim()
                val expectedEvalSample = sourceStage.eval.first().replace(Regex("</?u>"), "").trim()

                assertTrue(
                    "abil column in $stageKey should retain original abil text",
                    parsedStage.abil.any { it.contains(expectedAbilSample.take(20)) }
                )
                assertTrue(
                    "teacher column in $stageKey should retain original teacher text",
                    parsedStage.teacher.any { it.contains(expectedTeacherSample.take(20)) }
                )
                assertTrue(
                    "eval column in $stageKey should retain original eval text",
                    parsedStage.eval.any { it.contains(expectedEvalSample.take(20)) }
                )
            }
        }
    }

    @Test
    fun `test GeminiAiService uses gemini-3_8-flash and succeeds with AQ token or custom topic`() = kotlinx.coroutines.runBlocking {
        assertEquals("gemini-3.8-flash", com.example.ai.GeminiAiService.PRIMARY_MODEL)

        val result = com.example.ai.GeminiAiService.generateJadhadha(
            topic = "التضامن الاجتماعي",
            lessonText = "إن التضامن الاجتماعي ركيزة أساسية في بناء المجتمعات المتماسكة.",
            component = com.example.model.PedagogicalComponent.READING,
            customKey = "AQ.Ab8RN6J1NZ6BMsz62m4AOkJ_Dr"
        )
        assertTrue(result.isSuccess)
        val plan = result.getOrThrow()
        assertEquals("التضامن الاجتماعي", plan.topic)
        assertEquals(6, plan.stages.size)
        for ((_, stage) in plan.stages) {
            assertTrue(stage.abil.isNotEmpty())
            assertTrue(stage.teacher.isNotEmpty())
            assertTrue(stage.content.isNotEmpty())
            assertTrue(stage.eval.isNotEmpty())
        }
    }

    @Test
    fun `acceptance 1 - docx with single stage marks remaining stages as DEFAULT`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Create a plan where only 'diagnostic' has content and all other stages are omitted
        val singleStagePlan = SamplePlans.MANFA_PLAN.copy(
            topic = "درس بمرحلة واحدة",
            stages = mapOf(
                "diagnostic" to com.example.model.StageData(
                    abil = listOf("القدرة على تشخيص المكتسبات السابقة."),
                    teacher = listOf("يطرح أسئلة تشخيصية للتمهيد."),
                    content = listOf("مراجعة الدرس السابق حول الهجرة."),
                    eval = listOf("تقويم تشخيصي لقياس استعداد المتعلمين.")
                )
            )
        )

        // Generate a custom single-stage docx by writing a 1-stage raw text or docx
        val rawSingleStage = """
            المكون: النصوص القرائية
            الموضوع: درس بمرحلة واحدة
            [مرحلة: تشخيص المكتسبات]
            القدرات المستهدفة:
            - القدرة على تشخيص المكتسبات السابقة.
            أنشطة المدرس:
            - يطرح أسئلة تشخيصية للتمهيد.
            المحتوى الديداكتيكي:
            - مراجعة الدرس السابق حول الهجرة.
            التقويم والدعم:
            - تقويم تشخيصي لقياس استعداد المتعلمين.
        """.trimIndent()

        val parseResult = com.example.extractor.JadhadhaParser.parseWithReport(
            raw = rawSingleStage,
            fallbackTopic = singleStagePlan.topic,
            forcedComponent = null
        )

        val parsedPlan = parseResult.plan
        val report = parseResult.report

        // Diagnostic stage was extracted from the file -> all 4 fields are FILE
        val diagStage = parsedPlan.stages["diagnostic"]!!
        assertEquals(com.example.model.FieldSource.FILE, diagStage.sourceFor("abil"))
        assertEquals(com.example.model.FieldSource.FILE, diagStage.sourceFor("teacher"))
        assertEquals(com.example.model.FieldSource.FILE, diagStage.sourceFor("content"))
        assertEquals(com.example.model.FieldSource.FILE, diagStage.sourceFor("eval"))

        // Remaining 5 stages were filled by ensureDefaults -> all 4 fields in each are DEFAULT
        val remainingKeys = listOf("observation", "comprehension", "analysis", "synthesis", "investment")
        for (stageKey in remainingKeys) {
            val st = parsedPlan.stages[stageKey]!!
            assertEquals("Stage $stageKey abil should be DEFAULT", com.example.model.FieldSource.DEFAULT, st.sourceFor("abil"))
            assertEquals("Stage $stageKey teacher should be DEFAULT", com.example.model.FieldSource.DEFAULT, st.sourceFor("teacher"))
            assertEquals("Stage $stageKey content should be DEFAULT", com.example.model.FieldSource.DEFAULT, st.sourceFor("content"))
            assertEquals("Stage $stageKey eval should be DEFAULT", com.example.model.FieldSource.DEFAULT, st.sourceFor("eval"))
        }

        assertEquals(4, report.totalFileFields)
        assertEquals(20, report.totalDefaultFields)
    }

    @Test
    fun `acceptance 2 - unclassified line appears in report and becomes FILE after assignment`() {
        val rawWithUnclassified = """
            ملاحظة هامة خارج الجدول قبل بداية المراحل حول توزيع الفوج التربوي
            [مرحلة: تشخيص المكتسبات]
            المحتوى الديداكتيكي:
            - سؤال تمهيدي حول النص القرائي.
        """.trimIndent()

        val initialResult = com.example.extractor.JadhadhaParser.parseWithReport(
            raw = rawWithUnclassified,
            fallbackTopic = "درس اختبار المراجعة",
            forcedComponent = null
        )

        // Component was defaulted because no explicit component keywords were provided
        assertTrue(initialResult.report.isComponentDefaulted)
        // Unclassified line at line 1 should be captured
        assertEquals(1, initialResult.report.unclassifiedLines.size)
        val uLine = initialResult.report.unclassifiedLines.first()
        assertEquals(1, uLine.lineNumber)
        assertTrue(uLine.text.contains("ملاحظة هامة خارج الجدول"))

        // Before assignment, observation.content is DEFAULT
        assertEquals(
            com.example.model.FieldSource.DEFAULT,
            initialResult.plan.stages["observation"]?.sourceFor("content")
        )

        // Assign the unclassified line to observation -> content
        val updatedResult = com.example.extractor.JadhadhaParser.assignUnclassifiedLine(
            currentResult = initialResult,
            line = uLine,
            targetStageKey = "observation",
            targetField = "content"
        )

        // Unclassified lines list is now empty, and observation.content is now FILE and contains the assigned text
        assertEquals(0, updatedResult.report.unclassifiedLines.size)
        val obsStage = updatedResult.plan.stages["observation"]!!
        assertEquals(com.example.model.FieldSource.FILE, obsStage.sourceFor("content"))
        assertTrue(obsStage.content.any { it.contains("ملاحظة هامة خارج الجدول") })
    }

    @Test
    fun `acceptance 3 and 4 - FieldSource tags persist in Room and legacy plans without sources open cleanly`() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, com.example.data.AppDatabase::class.java).build()
        val dao = db.jadhadhaDao()
        val repository = com.example.data.JadhadhaRepository(dao)

        // Acceptance 3: Save a plan with FILE, DEFAULT, AI, and USER sources and verify persistence
        val parsed = com.example.extractor.JadhadhaParser.parseWithReport(
            raw = "[مرحلة: تشخيص المكتسبات]\nالمحتوى الديداكتيكي:\n- محتوى من الملف",
            fallbackTopic = "درس حفظ المصادر",
            forcedComponent = com.example.model.PedagogicalComponent.READING
        ).plan

        val diagWithUserEdit = parsed.stages["diagnostic"]!!
            .withFieldSource("teacher", com.example.model.FieldSource.USER)
            .withFieldSource("abil", com.example.model.FieldSource.AI)
        val updatedStages = parsed.stages.toMutableMap()
        updatedStages["diagnostic"] = diagWithUserEdit
        val planToSave = parsed.copy(id = "persisted_sources_plan", stages = updatedStages)

        repository.savePlan(planToSave, isCustom = true)

        val loaded = repository.getPlanById("persisted_sources_plan")
        assertNotNull(loaded)
        val loadedDiag = loaded!!.stages["diagnostic"]!!
        assertEquals(com.example.model.FieldSource.FILE, loadedDiag.sourceFor("content"))
        assertEquals(com.example.model.FieldSource.USER, loadedDiag.sourceFor("teacher"))
        assertEquals(com.example.model.FieldSource.AI, loadedDiag.sourceFor("abil"))
        assertEquals(com.example.model.FieldSource.DEFAULT, loadedDiag.sourceFor("eval"))
        assertEquals(com.example.model.FieldSource.DEFAULT, loaded.stages["observation"]!!.sourceFor("content"))

        // Acceptance 4: Insert a legacy JadhadhaEntity whose stagesJson has NO "sources" key
        val legacyStagesJson = """
            {
              "diagnostic": {
                "eval": ["تقويم قديم"],
                "content": ["محتوى قديم"],
                "teacher": ["نشاط قديم"],
                "abil": ["قدرة قديمة"]
              }
            }
        """.trimIndent()
        val legacyEntity = com.example.data.JadhadhaEntity(
            id = "legacy_plan_no_sources",
            topic = "جذاذة قديمة قبل التحديث",
            componentType = "READING",
            category = "نصوص قرائية",
            infoLevel = "الثالثة ثانوي إعدادي",
            infoUnit = "الأولى",
            infoDomain = "المجال القيمي",
            infoComponent = "النصوص القرائية",
            infoDuration = "ساعتان",
            infoMaterials = "الكتاب المدرسي",
            communicativeComp = "كفاية تواصلية",
            methodologicalComp = "كفاية منهجية",
            culturalComp = "كفاية ثقافية",
            stagesJson = legacyStagesJson,
            isCustom = true
        )
        dao.insertOrUpdate(legacyEntity)

        val loadedLegacy = repository.getPlanById("legacy_plan_no_sources")
        assertNotNull(loadedLegacy)
        assertEquals("جذاذة قديمة قبل التحديث", loadedLegacy!!.topic)
        assertEquals(listOf("محتوى قديم"), loadedLegacy.stages["diagnostic"]?.content)
        assertTrue(loadedLegacy.stages["diagnostic"]?.sources?.isEmpty() == true)

        db.close()
    }

    @Test
    fun `test Mokhtar docx with nested table in lang_analysis extracts cleanly and AI delegation refines all fields`() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Verify cleanTopicFromFilename on "جذاذة--(اسما الزمان والمكان)3اع- المختار.docx"
        val cleanedTitle = com.example.extractor.DocumentFileReader.cleanTopicFromFilename(
            "جذاذة--(اسما الزمان والمكان)3اع- المختار.docx"
        )
        assertEquals("اسما الزمان والمكان", cleanedTitle)

        // 2. Generate a language lesson docx containing a nested analytical table [جدول: ...] in lang_analysis
        val synthesized = com.example.ai.GeminiAiService.synthesizeSmartPlan(
            topic = "اسما الزمان والمكان",
            lessonText = "",
            component = com.example.model.PedagogicalComponent.LANGUAGE
        )
        val docxFile = DocxGenerator.generateDocx(context, synthesized, saveToDownloads = false)

        // Verify the generated Word XML has Sakkal Majalla 10pt Bold and a nested <w:tbl> inside lang_analysis
        val zip = ZipFile(docxFile)
        val docXml = zip.getInputStream(zip.getEntry("word/document.xml")).bufferedReader().readText()
        zip.close()
        assertTrue(docXml.contains("Sakkal Majalla"))
        assertTrue(docXml.contains("<w:sz w:val=\"20\"/>"))
        assertTrue(docXml.contains("<w:b/><w:bCs/>"))

        // 3. Extract text back from the docx with nested table and verify lang_analysis is NOT truncated
        val extractedText = java.io.FileInputStream(docxFile).use { stream ->
            com.example.extractor.DocumentFileReader.extractTextFromDocx(stream)
        }
        val parseResult = com.example.extractor.JadhadhaParser.parseWithReport(
            raw = extractedText,
            fallbackTopic = cleanedTitle,
            forcedComponent = null
        )
        assertEquals(com.example.model.PedagogicalComponent.LANGUAGE, parseResult.plan.componentType)
        assertEquals(0, parseResult.report.unclassifiedLines.size)
        val langAnalysis = parseResult.plan.stages["lang_analysis"]!!
        assertEquals(com.example.model.FieldSource.FILE, langAnalysis.sourceFor("content"))
        assertTrue(langAnalysis.content.any { it.startsWith("[جدول:") })

        // 4. Test AI delegation on a partial extraction with unclassified lines (like lines 141-142 in user screenshot)
        val partialRaw = """
            المكون: الدرس اللغوي
            الموضوع: جذاذة--(اسما الزمان والمكان)3اع- المختار
            + يتأملون الأمثلة، مع التركيز على الكلمات والحركات المكتوبة بلون مغاير.
            + يصفون الظاهرة اللغوية، ويكتشفون متغيراتها.
            [مرحلة: التقويم التشخيصي والتمهيد]
            المحتوى الديداكتيكي:
            - تذكير بالدرس السابق حول المشتقات.
        """.trimIndent()

        val initialPartial = com.example.extractor.JadhadhaParser.parseWithReport(
            raw = partialRaw,
            fallbackTopic = "جذاذة--(اسما الزمان والمكان)3اع- المختار",
            forcedComponent = null
        )
        assertTrue(initialPartial.report.unclassifiedLines.isNotEmpty())
        assertEquals(
            com.example.model.FieldSource.DEFAULT,
            initialPartial.plan.stages["lang_analysis"]?.sourceFor("content")
        )

        val delegatedResult = com.example.ai.GeminiAiService.classifyAndRefineExtraction(
            rawText = partialRaw,
            initialResult = initialPartial
        )
        assertTrue(delegatedResult.report.isAiDelegated)
        assertEquals(0, delegatedResult.report.unclassifiedLines.size)
        assertEquals("اسما الزمان والمكان", delegatedResult.plan.topic)
        assertEquals(
            com.example.model.FieldSource.FILE,
            delegatedResult.plan.stages["lang_analysis"]?.sourceFor("teacher")
        )
        assertEquals(
            com.example.model.FieldSource.AI,
            delegatedResult.plan.stages["lang_analysis"]?.sourceFor("content")
        )
    }
}


