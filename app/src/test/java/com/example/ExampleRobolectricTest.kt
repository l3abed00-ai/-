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
}

