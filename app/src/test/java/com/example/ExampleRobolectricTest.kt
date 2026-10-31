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
@Config(sdk = [36])
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
            assertTrue(
                "Parsed plan stages (${parsedPlan.stages.keys}) must contain all source stages (${sourcePlan.stages.keys})",
                parsedPlan.stages.keys.containsAll(sourcePlan.stages.keys)
            )

            for ((stageKey, sourceStage) in sourcePlan.stages) {
                val parsedStage = parsedPlan.stages[stageKey]
                assertNotNull("Stage $stageKey must exist", parsedStage)
                assertTrue("abil in $stageKey must not be empty", parsedStage!!.abil.isNotEmpty())
                assertTrue("teacher in $stageKey must not be empty", parsedStage.teacher.isNotEmpty())
                assertTrue("content in $stageKey must not be empty", parsedStage.content.isNotEmpty())
                assertTrue("eval in $stageKey must not be empty", parsedStage.eval.isNotEmpty())

                // Verify that items of each column stay in their designated column
                fun stripSampleBullet(s: String): String {
                    return s.replace(Regex("</?u>"), "")
                        .trim()
                        .removePrefix("-")
                        .removePrefix("+")
                        .removePrefix("•")
                        .removePrefix("*")
                        .replace(Regex("^\\d+[.\\-)]\\s*"), "")
                        .trim()
                }
                val expectedAbilSample = stripSampleBullet(sourceStage.abil.first())
                val expectedTeacherSample = stripSampleBullet(sourceStage.teacher.last())
                val expectedEvalSample = stripSampleBullet(sourceStage.eval.first())

                assertTrue(
                    "abil column in $stageKey should retain original abil text",
                    parsedStage.abil.any { it.contains(expectedAbilSample.take(18)) }
                )
                assertTrue(
                    "teacher column in $stageKey should retain original teacher text",
                    parsedStage.teacher.any { it.contains(expectedTeacherSample.take(18)) }
                )
                assertTrue(
                    "eval column in $stageKey should retain original eval text",
                    parsedStage.eval.any { it.contains(expectedEvalSample.take(18)) }
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
        assertEquals(7, plan.stages.size)
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

        // Remaining 6 stages were filled by ensureDefaults -> all 4 fields in each are DEFAULT
        val remainingKeys = listOf("observation", "comprehension", "session2_intro", "analysis", "synthesis", "investment")
        for (stageKey in remainingKeys) {
            val st = parsedPlan.stages[stageKey]!!
            assertEquals("Stage $stageKey abil should be DEFAULT", com.example.model.FieldSource.DEFAULT, st.sourceFor("abil"))
            assertEquals("Stage $stageKey teacher should be DEFAULT", com.example.model.FieldSource.DEFAULT, st.sourceFor("teacher"))
            assertEquals("Stage $stageKey content should be DEFAULT", com.example.model.FieldSource.DEFAULT, st.sourceFor("content"))
            assertEquals("Stage $stageKey eval should be DEFAULT", com.example.model.FieldSource.DEFAULT, st.sourceFor("eval"))
        }

        assertEquals(4, report.totalFileFields)
        assertEquals(24, report.totalDefaultFields)
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

        // 2. Generate a language lesson docx containing a nested analytical table [جدول: ...] in lang_observation / lang_analysis
        val synthesized = com.example.ai.GeminiAiService.synthesizeSmartPlan(
            topic = "اسما الزمان والمكان",
            lessonText = "",
            component = com.example.model.PedagogicalComponent.LANGUAGE
        )
        val docxFile = DocxGenerator.generateDocx(context, synthesized, saveToDownloads = false)

        // Verify the generated Word XML has Sakkal Majalla 10pt Bold and a nested <w:tbl> inside lang_observation
        val zip = ZipFile(docxFile)
        val docXml = zip.getInputStream(zip.getEntry("word/document.xml")).bufferedReader().readText()
        zip.close()
        assertTrue(docXml.contains("Sakkal Majalla"))
        assertTrue(docXml.contains("<w:sz w:val=\"20\"/>"))
        assertTrue(docXml.contains("<w:b/><w:bCs/>"))

        // 3. Extract text back from the docx with nested table and verify lang_observation / lang_analysis is NOT truncated
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
        val langAnalysis = parseResult.plan.stages["lang_observation"]!!
        assertEquals(com.example.model.FieldSource.FILE, langAnalysis.sourceFor("content"))
        assertTrue(langAnalysis.content.any { it.contains("اسم الزمان / المكان") })
        assertTrue(langAnalysis.content.any { it.contains("طريقة الصياغة") })

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

    @Test
    fun `test content-based file detection docx without extension and doc rejection and binary rejection`() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1 & 2. Generate a valid .docx, copy it to a file WITHOUT any extension, and read via readUploadedFile
        val generatedDocx = DocxGenerator.generateDocx(context, SamplePlans.ISM_FAIL_PLAN, saveToDownloads = false)
        val extensionlessFile = java.io.File(context.cacheDir, "ملف_جذاذة_بدون_امتداد")
        generatedDocx.copyTo(extensionlessFile, overwrite = true)

        val info = com.example.extractor.DocumentFileReader.readUploadedFile(context, android.net.Uri.fromFile(extensionlessFile))
        assertEquals(com.example.extractor.DetectedFileType.DOCX, info.detectedType)
        assertTrue("Extensionless .docx should extract Arabic text", info.textContent.contains("اسم الفاعل"))

        // 3. Legacy .doc file (magic bytes D0 CF 11 E0) -> throws exact Arabic message
        val docFile = java.io.File(context.cacheDir, "legacy_file.doc")
        docFile.writeBytes(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte()))
        val docError = runCatching {
            com.example.extractor.DocumentFileReader.readUploadedFile(context, android.net.Uri.fromFile(docFile))
        }.exceptionOrNull()
        assertNotNull("Legacy .doc must be rejected", docError)
        assertTrue(docError is com.example.extractor.DocumentReadException)
        assertEquals(
            "صيغة .doc القديمة غير مدعومة؛ افتحه في Word أو WPS واحفظه بصيغة .docx",
            (docError as com.example.extractor.DocumentReadException).arabicMessage
        )

        // 4. Binary file disguised as .txt -> rejected instead of garbled text
        val binaryFile = java.io.File(context.cacheDir, "fake_text.txt")
        binaryFile.writeBytes(byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x00.toByte(), 0x01.toByte(), 0x02.toByte(), 0x03.toByte()))
        val binError = runCatching {
            com.example.extractor.DocumentFileReader.readUploadedFile(context, android.net.Uri.fromFile(binaryFile))
        }.exceptionOrNull()
        assertNotNull("Binary file must be rejected", binError)
        assertTrue(binError is com.example.extractor.DocumentReadException)

        // 5. File > 25 MB -> rejected with clear Arabic message
        val oversizedError = runCatching {
            com.example.extractor.DocumentFileReader.inspectAndReadStream(
                inputStream = java.io.ByteArrayInputStream("نص عربي".toByteArray()),
                fileName = "huge.docx",
                declaredSize = 26L * 1024 * 1024
            )
        }.exceptionOrNull()
        assertNotNull("File > 25 MB must be rejected", oversizedError)
        assertEquals(
            com.example.extractor.DocumentFileReader.MSG_FILE_TOO_LARGE,
            (oversizedError as com.example.extractor.DocumentReadException).arabicMessage
        )
    }

    @Test
    fun `test pdf detection and transcribeDocument without key and ViewModel extraction state`() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // PDF magic bytes %PDF-1.5
        val pdfBytes = "%PDF-1.5\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF".toByteArray()
        val pdfFile = java.io.File(context.cacheDir, "lesson_plan.pdf")
        pdfFile.writeBytes(pdfBytes)

        val pdfInfo = com.example.extractor.DocumentFileReader.readUploadedFile(context, android.net.Uri.fromFile(pdfFile))
        assertEquals(com.example.extractor.DetectedFileType.PDF, pdfInfo.detectedType)
        assertNotNull(pdfInfo.pdfBytes)

        // Calling transcribeDocument without a valid key returns the exact Arabic message
        val resultNoKey = com.example.ai.GeminiAiService.transcribeDocument(
            bytes = pdfBytes,
            mimeType = "application/pdf",
            customKey = ""
        )
        assertTrue(resultNoKey.isFailure)
        assertEquals(
            "تفريغ PDF يحتاج مفتاح Gemini؛ أدخله أو حوّل الملف إلى docx",
            resultNoKey.exceptionOrNull()?.message
        )
    }

    @Test
    fun `test XmlPullParser docx extraction covers all 6 acceptance criteria`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Criterion 1: Normal .docx -> output identical to legacy parser
        val normalDocx = DocxGenerator.generateDocx(context, SamplePlans.ISM_FAIL_PLAN, saveToDownloads = false)
        val normalDocXml = ZipFile(normalDocx).use { zip ->
            zip.getInputStream(zip.getEntry("word/document.xml")).bufferedReader().readText()
        }
        val pullOutput = com.example.extractor.DocumentFileReader.parseDocumentXml(normalDocXml)
        val legacyOutput = com.example.extractor.DocumentFileReader.parseDocumentXmlLegacy(normalDocXml)
        assertEquals("Normal docx output must match legacy output", legacyOutput, pullOutput)

        // Criterion 2: Nested table -> no truncated or duplicated rows; nested cell paragraphs added in order to parent cell
        val nestedTableXml = """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:tbl>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>المراحل</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>المحتوى الديداكتيكي</w:t></w:r></w:p></w:tc>
                  </w:tr>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>الوصف والتحليل</w:t></w:r></w:p></w:tc>
                    <w:tc>
                      <w:p><w:r><w:t>تمهيد التحليل</w:t></w:r></w:p>
                      <w:tbl>
                        <w:tr>
                          <w:tc><w:p><w:r><w:t>الظاهرة الأولى</w:t></w:r></w:p></w:tc>
                          <w:tc><w:p><w:r><w:t>حكمها الإعرابي</w:t></w:r></w:p></w:tc>
                        </w:tr>
                      </w:tbl>
                      <w:p><w:r><w:t>خلاصة التحليل</w:t></w:r></w:p>
                    </w:tc>
                  </w:tr>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>الاستنتاج</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>نص القاعدة</w:t></w:r></w:p></w:tc>
                  </w:tr>
                </w:tbl>
              </w:body>
            </w:document>
        """.trimIndent()
        val nestedResult = com.example.extractor.DocumentFileReader.parseDocumentXml(nestedTableXml)
        val nestedLines = nestedResult.lines()
        assertEquals(1, nestedLines.count { it == "[مرحلة: الوصف والتحليل]" })
        assertEquals(1, nestedLines.count { it == "[مرحلة: الاستنتاج]" })
        val idxIntro = nestedLines.indexOf("تمهيد التحليل")
        val idxCell1 = nestedLines.indexOf("الظاهرة الأولى")
        val idxCell2 = nestedLines.indexOf("حكمها الإعرابي")
        val idxOutro = nestedLines.indexOf("خلاصة التحليل")
        assertTrue(idxIntro in 0 until idxCell1 && idxCell1 < idxCell2 && idxCell2 < idxOutro)

        // Criterion 3: Cell with Shift+Enter (<w:br/> and <w:cr/>), tab, noBreakHyphen, del/ins, and underline rules
        val shiftEnterXml = """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p>
                  <w:r>
                    <w:rPr><w:u w:val="single"/></w:rPr>
                    <w:t>عنوان مسطر:</w:t>
                  </w:r>
                  <w:r>
                    <w:rPr><w:u w:val="none"/></w:rPr>
                    <w:t>غير مسطر: </w:t>
                  </w:r>
                  <w:r>
                    <w:t>كلمة1</w:t><w:tab/><w:t>كلمة2</w:t><w:noBreakHyphen/><w:t>مركب</w:t>
                  </w:r>
                  <w:del><w:r><w:delText>محذوف1</w:delText></w:r></w:del>
                  <w:ins><w:r><w:t> مضاف</w:t></w:r></w:ins>
                </w:p>
                <w:tbl>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>المراحل</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>المحتوى الديداكتيكي</w:t></w:r></w:p></w:tc>
                  </w:tr>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>التطبيقات</w:t></w:r></w:p></w:tc>
                    <w:tc>
                      <w:p>
                        <w:r>
                          <w:t>السطر الأول في الخلية</w:t>
                          <w:br/>
                          <w:t>السطر الثاني بعد Shift+Enter</w:t>
                          <w:cr/>
                          <w:t>السطر الثالث بعد cr</w:t>
                        </w:r>
                      </w:p>
                    </w:tc>
                  </w:tr>
                </w:tbl>
              </w:body>
            </w:document>
        """.trimIndent()
        val shiftEnterLines = com.example.extractor.DocumentFileReader.parseDocumentXml(shiftEnterXml).lines()
        assertTrue(shiftEnterLines.contains("عنوان مسطر: غير مسطر: كلمة1 كلمة2-مركب مضاف"))
        assertTrue(!shiftEnterLines.any { it.contains("<u>") || it.contains("<b>") })
        assertTrue(!shiftEnterLines.any { it.contains("محذوف1") })
        assertTrue(shiftEnterLines.contains("السطر الأول في الخلية"))
        assertTrue(shiftEnterLines.contains("السطر الثاني بعد Shift+Enter"))
        assertTrue(shiftEnterLines.contains("السطر الثالث بعد cr"))

        // Criterion 4: Text in word/header1.xml & word/footer1.xml -> appears at the END of the output
        val zipBytesOut = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(zipBytesOut).use { zos ->
            // Place header1.xml BEFORE document.xml to test arbitrary zip entry order
            zos.putNextEntry(java.util.zip.ZipEntry("word/header1.xml"))
            zos.write(
                """
                <w:hdr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:p><w:r><w:t>الأكاديمية الجهوية للتربية والتكوين</w:t></w:r></w:p>
                </w:hdr>
                """.trimIndent().toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            )
            zos.closeEntry()

            zos.putNextEntry(java.util.zip.ZipEntry("word/document.xml"))
            zos.write(
                """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:r><w:t>المكون: الدرس اللغوي</w:t></w:r></w:p>
                    <w:p><w:r><w:t>الموضوع: اسم الفاعل</w:t></w:r></w:p>
                  </w:body>
                </w:document>
                """.trimIndent().toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            )
            zos.closeEntry()

            zos.putNextEntry(java.util.zip.ZipEntry("word/footer1.xml"))
            zos.write(
                """
                <w:ftr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:p><w:r><w:t>السنة الدراسية 2025/2026</w:t></w:r></w:p>
                </w:ftr>
                """.trimIndent().toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            )
            zos.closeEntry()
        }
        val headerFooterText = com.example.extractor.DocumentFileReader.extractTextFromDocx(
            java.io.ByteArrayInputStream(zipBytesOut.toByteArray())
        )
        val hfLines = headerFooterText.lines()
        assertEquals("المكون: الدرس اللغوي", hfLines[0])
        assertEquals("الموضوع: اسم الفاعل", hfLines[1])
        assertEquals("الأكاديمية الجهوية للتربية والتكوين", hfLines[2])
        assertEquals("السنة الدراسية 2025/2026", hfLines[3])

        // Criterion 5: Text box (mc:AlternateContent with Choice and Fallback) -> no duplication
        val textBoxXml = """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                        xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"
                        xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
                        xmlns:v="urn:schemas-microsoft-com:vml">
              <w:body>
                <w:p>
                  <w:r>
                    <mc:AlternateContent>
                      <mc:Choice Requires="wps">
                        <wps:txbx>
                          <w:txbxContent>
                            <w:p><w:r><w:t>نص داخل مربع النص لا يتكرر</w:t></w:r></w:p>
                          </w:txbxContent>
                        </wps:txbx>
                      </mc:Choice>
                      <mc:Fallback>
                        <v:textbox>
                          <w:txbxContent>
                            <w:p><w:r><w:t>نص داخل مربع النص لا يتكرر</w:t></w:r></w:p>
                          </w:txbxContent>
                        </v:textbox>
                      </mc:Fallback>
                    </mc:AlternateContent>
                  </w:r>
                </w:p>
              </w:body>
            </w:document>
        """.trimIndent()
        val textBoxLines = com.example.extractor.DocumentFileReader.parseDocumentXml(textBoxXml).lines()
        assertEquals(listOf("نص داخل مربع النص لا يتكرر"), textBoxLines)

        // Criterion 6: Corrupted XML -> does not crash, falls back to parseDocumentXmlLegacy
        val corruptedXml = "<w:document><w:body><w:p><w:r><w:t>سطر سليم قبل التلف</w:t></w:r></w:p><w:p><w:r><w:t>سطر تالف غير مغلق"
        val fallbackResult = com.example.extractor.DocumentFileReader.parseDocumentXml(corruptedXml)
        assertEquals("سطر سليم قبل التلف", fallbackResult)
    }

    @Test
    fun `test full Arabic fonts AppFont AmiriFont AppTypography and PreviewDocFont`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Verify all 6 font resources open and are > 200 KB (> 204,800 bytes)
        val requiredFontResIds = listOf(
            com.example.R.font.ibm_plex_sans_arabic_regular,
            com.example.R.font.ibm_plex_sans_arabic_medium,
            com.example.R.font.ibm_plex_sans_arabic_semibold,
            com.example.R.font.ibm_plex_sans_arabic_bold,
            com.example.R.font.amiri_regular,
            com.example.R.font.amiri_bold
        )
        for (resId in requiredFontResIds) {
            val bytes = context.resources.openRawResource(resId).use { it.readBytes() }
            assertTrue(
                "Font resource $resId must be > 200 KB (was ${bytes.size} bytes)",
                bytes.size > 200 * 1024
            )
        }

        // 2. Verify all 15 Typography slots use AppFont, letterSpacing = 0.sp, and required lineHeight ratios
        val typo = com.example.ui.theme.AppTypography
        val headingStyles = listOf(
            "displayLarge" to typo.displayLarge,
            "displayMedium" to typo.displayMedium,
            "displaySmall" to typo.displaySmall,
            "headlineLarge" to typo.headlineLarge,
            "headlineMedium" to typo.headlineMedium,
            "headlineSmall" to typo.headlineSmall,
            "titleLarge" to typo.titleLarge,
            "titleMedium" to typo.titleMedium,
            "titleSmall" to typo.titleSmall
        )
        val bodyAndLabelStyles = listOf(
            "bodyLarge" to typo.bodyLarge,
            "bodyMedium" to typo.bodyMedium,
            "bodySmall" to typo.bodySmall,
            "labelLarge" to typo.labelLarge,
            "labelMedium" to typo.labelMedium,
            "labelSmall" to typo.labelSmall
        )

        for ((name, style) in headingStyles) {
            assertEquals("$name must use AppFont", com.example.ui.theme.AppFont, style.fontFamily)
            assertEquals("$name must have 0.sp letterSpacing", 0f, style.letterSpacing.value, 0.001f)
            assertTrue(
                "$name lineHeight (${style.lineHeight.value}) must be >= 1.35 * fontSize (${style.fontSize.value})",
                style.lineHeight.value >= style.fontSize.value * 1.35f - 0.01f
            )
        }

        for ((name, style) in bodyAndLabelStyles) {
            assertEquals("$name must use AppFont", com.example.ui.theme.AppFont, style.fontFamily)
            assertEquals("$name must have 0.sp letterSpacing", 0f, style.letterSpacing.value, 0.001f)
            assertTrue(
                "$name lineHeight (${style.lineHeight.value}) must be >= 1.5 * fontSize (${style.fontSize.value})",
                style.lineHeight.value >= style.fontSize.value * 1.5f - 0.01f
            )
        }

        // 3. Verify PreviewDocFont has exactly 2 options: AMIRI (default) and PLEX
        val previewFonts = com.example.ui.screens.PreviewDocFont.entries
        assertEquals(2, previewFonts.size)
        assertEquals("الكلاسيكي — الأميري", com.example.ui.screens.PreviewDocFont.AMIRI.label)
        assertEquals(com.example.ui.theme.AmiriFont, com.example.ui.screens.PreviewDocFont.AMIRI.fontFamily)
        assertEquals("الحديث — Plex", com.example.ui.screens.PreviewDocFont.PLEX.label)
        assertEquals(com.example.ui.theme.AppFont, com.example.ui.screens.PreviewDocFont.PLEX.fontFamily)
    }

    @Test
    fun `test ltr BiDi isolate helper and AppButtonDefaultContentPadding`() {
        assertEquals("\u2066Word\u2069", com.example.ui.components.ltr("Word"))
        assertEquals("\u2066.docx\u2069", com.example.ui.components.ltr(".docx"))
        val buttonText = "حفظ وثيقة ${com.example.ui.components.ltr("Word")} في الهاتف (التنزيلات ${com.example.ui.components.ltr(".docx")})"
        assertTrue(buttonText.contains("\u2066Word\u2069"))
        assertTrue(buttonText.contains("\u2066.docx\u2069"))

        val padding = com.example.ui.components.AppButtonDefaultContentPadding
        assertEquals(
            12f,
            padding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Rtl).value,
            0.01f
        )
        assertEquals(
            12f,
            padding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Rtl).value,
            0.01f
        )
        assertEquals(8f, padding.calculateTopPadding().value, 0.01f)
        assertEquals(8f, padding.calculateBottomPadding().value, 0.01f)
    }

    @Test
    fun `test DocxGenerator bidiVisual bidi 5-column widths 12-18-25-30-15 and F2F2F2 header shading`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = DocxGenerator.generateDocx(context, SamplePlans.MANFA_PLAN, saveToDownloads = false)
        val docXml = ZipFile(file).use { zip ->
            zip.getInputStream(zip.getEntry("word/document.xml")).bufferedReader().readText()
        }

        // Verify <w:bidiVisual/> inside <w:tblPr>, <w:bidi/> inside <w:pPr> and <w:rPr>
        assertTrue(docXml.contains("<w:bidiVisual/>"))
        assertTrue(docXml.contains("<w:bidi/>"))
        assertTrue(docXml.contains("<w:pPr>\n              <w:bidi/>"))
        assertTrue(docXml.contains("<w:bidi/>\n                <w:rtl/>"))

        // Verify 5-column RTL widths (12% = 1333, 18% = 1999, 25% = 2776, 30% = 3332, 15% = 1666 dxa = 11106 dxa)
        assertTrue(docXml.contains("<w:gridCol w:w=\"1333\"/>"))
        assertTrue(docXml.contains("<w:gridCol w:w=\"1999\"/>"))
        assertTrue(docXml.contains("<w:gridCol w:w=\"2776\"/>"))
        assertTrue(docXml.contains("<w:gridCol w:w=\"3332\"/>"))
        assertTrue(docXml.contains("<w:gridCol w:w=\"1666\"/>"))

        // Verify header background #F2F2F2 and black text #000000
        assertTrue(docXml.contains("w:fill=\"F2F2F2\""))
        assertTrue(docXml.contains("<w:color w:val=\"000000\"/>"))

        // Verify Expression generates 4 independent session tables
        val exprFile = DocxGenerator.generateDocx(context, SamplePlans.INSHA_YAWMIYAT_PLAN, saveToDownloads = false)
        val exprXml = ZipFile(exprFile).use { zip ->
            zip.getInputStream(zip.getEntry("word/document.xml")).bufferedReader().readText()
        }
        assertTrue(exprXml.contains("التعبير والإنشاء – حصة الاكتساب"))
        assertTrue(exprXml.contains("التعبير والإنشاء – حصة التطبيق"))
        assertTrue(exprXml.contains("التعبير والإنشاء – حصة الإنتاج"))
        assertTrue(exprXml.contains("التعبير والإنشاء – حصة التصحيح"))
        assertTrue(exprXml.contains("شبكة تشخيص أخطاء المتعلمين وتصحيحها:"))
    }

    @Test
    fun `test JadhadhaParser extracts 9-column and 8-column and 2-column tables ignoring duration and segment noise`() {
        val rtl9ColTable = """
            المكون: الدرس اللغوي
            الموضوع: اسم الفاعل وعمله
            المقاطع | الحصيص الزمني | المراحل | القدرات والمهارات | أنشطة الأستاذ | أنشطة المتعلم | المحتوى الديداكتيكي | التقويم | مؤشرات التقويم
            المقطع التمهيدي | 5 د | تقويم تشخيصي | تذكر الدرس السابق | يطرح أسئلة تذكيرية | يستحضر المتعلم مكتسباته | تصريف الفعل الثلاثي | تقويم تشخيصي | إجابات صحيحة
            المقطع الوسيطي | 25 د | ملاحظة الأمثلة ووصفها وتحليلها | قدرة المتعلم على استخراج الظاهرة | يسطر جدول التحليل على السبورة | يملأ المتعلمون خانات الجدول | [جدول: التراكيب | اسم الفاعل | وزنه || المؤمن شاكر | شاكر | فاعل] | تقويم تكويني | مشاركة المتعلمين
            المقطع النهائي | 15 د | الاستنتاج | بناء القاعدة | يوجههم لصياغة القاعدة | يصوغون القاعدة | يصاغ اسم الفاعل من الثلاثي على وزن فاعل | تقويم إجمالي | استيعاب القاعدة
        """.trimIndent()

        val parsed9Col = com.example.extractor.JadhadhaParser.parseRawText(rtl9ColTable, "اسم الفاعل وعمله")
        assertEquals(com.example.model.PedagogicalComponent.LANGUAGE, parsed9Col.componentType)
        val diag = parsed9Col.stages["lang_diagnostic"]!!
        assertTrue(diag.abil.any { it.contains("تذكر الدرس السابق") })
        assertTrue(diag.teacher.any { it.contains("يطرح أسئلة تذكيرية") })
        assertTrue(diag.content.any { it.contains("تصريف الفعل الثلاثي") })
        assertTrue(diag.eval.any { it.contains("تقويم تشخيصي") })
        // Verify duration ("5 د", "25 د") and segment noise ("المقطع التمهيدي") were discarded
        assertTrue(parsed9Col.stages.values.none { st ->
            (st.abil + st.teacher + st.content + st.eval).any { it == "5 د" || it == "25 د" || it.contains("المقطع التمهيدي") }
        })

        // Verify RTL and LTR 4-col table parity
        val rtlTable = """
            المكون: النصوص القرائية
            الموضوع: حب الوطن
            المراحل | أنشطة الأستاذ | أنشطة المتعلم | التقويم
            تشخيص المكتسبات | يطرح أسئلة تمهيدية حول الوطن | يستحضر المتعلم مكتسباته السابقة | تقويم تشخيصي للمكتسبات
            ملاحظة النص | يوجه المتعلمين لملاحظة العنوان | يصوغ المتعلم فرضيات القراءة | فحص وجاهة الفرضيات
        """.trimIndent()

        val ltrTable = """
            المكون: النصوص القرائية
            الموضوع: حب الوطن
            التقويم | أنشطة المتعلم | أنشطة الأستاذ | المراحل
            تقويم تشخيصي للمكتسبات | يستحضر المتعلم مكتسباته السابقة | يطرح أسئلة تمهيدية حول الوطن | تشخيص المكتسبات
            فحص وجاهة الفرضيات | يصوغ المتعلم فرضيات القراءة | يوجه المتعلمين لملاحظة العنوان | ملاحظة النص
        """.trimIndent()

        val rtlPlan = com.example.extractor.JadhadhaParser.parseRawText(rtlTable, "حب الوطن")
        val ltrPlan = com.example.extractor.JadhadhaParser.parseRawText(ltrTable, "حب الوطن")

        assertEquals(rtlPlan.stages["diagnostic"]?.teacher, ltrPlan.stages["diagnostic"]?.teacher)
        assertEquals(rtlPlan.stages["diagnostic"]?.content, ltrPlan.stages["diagnostic"]?.content)
        assertEquals(rtlPlan.stages["diagnostic"]?.eval, ltrPlan.stages["diagnostic"]?.eval)
    }

    @Test
    fun `test GeminiAiService saves and retrieves API key in SharedPreferences`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        com.example.ai.GeminiAiService.clearSavedApiKey(context)
        assertEquals("", com.example.ai.GeminiAiService.getSavedApiKey(context))

        com.example.ai.GeminiAiService.saveApiKey(context, "AIzaSyTestKey12345")
        assertEquals("AIzaSyTestKey12345", com.example.ai.GeminiAiService.getSavedApiKey(context))
        assertEquals("AIzaSyTestKey12345", com.example.ai.GeminiAiService.getEffectiveApiKey(null))

        com.example.ai.GeminiAiService.clearSavedApiKey(context)
        assertEquals("", com.example.ai.GeminiAiService.getSavedApiKey(context))
    }

    @Test
    fun `test hybrid architecture - gridSpan vMerge split headers intra-cell stage splitting semantic verbs and AI extraction`() = kotlinx.coroutines.runBlocking {
        // 1. DocumentFileReader: 2-row split header with gridSpan + vMerge + dual evaluation columns + pre-lesson header table
        val complexDocxXml = """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:tbl>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>جذاذة تربوية</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>الفئة المستهدفة: الثالثة ثانوي إعدادي</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>المكون: النصوص القرائية</w:t></w:r></w:p></w:tc>
                  </w:tr>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>الموضوع: التضامن الإنساني</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>الوسائل الديدكتيكية: الكتاب المدرسي والسبورة</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>الكفاية التواصلية: التواصل السليم باللغة العربية</w:t></w:r></w:p></w:tc>
                  </w:tr>
                </w:tbl>
                <w:tbl>
                  <w:tr>
                    <w:tc><w:tcPr><w:vMerge w:val="restart"/></w:tcPr><w:p><w:r><w:t>المراحل</w:t></w:r></w:p></w:tc>
                    <w:tc><w:tcPr><w:vMerge w:val="restart"/></w:tcPr><w:p><w:r><w:t>القدرات</w:t></w:r></w:p></w:tc>
                    <w:tc><w:tcPr><w:gridSpan w:val="2"/></w:tcPr><w:p><w:r><w:t>الأنشطة التعليمية التعلمية</w:t></w:r></w:p></w:tc>
                    <w:tc><w:tcPr><w:gridSpan w:val="2"/></w:tcPr><w:p><w:r><w:t>التقويم والدعم</w:t></w:r></w:p></w:tc>
                  </w:tr>
                  <w:tr>
                    <w:tc><w:tcPr><w:vMerge/></w:tcPr><w:p/></w:tc>
                    <w:tc><w:tcPr><w:vMerge/></w:tcPr><w:p/></w:tc>
                    <w:tc><w:p><w:r><w:t>أنشطة المدرس</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>أنشطة المتعلمين</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>التقويم</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>مؤشرات التقويم</w:t></w:r></w:p></w:tc>
                  </w:tr>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>الحصة الثانية: القراءة التحليلية</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>القدرة على تحليل الحقول الدلالية وتركيب خلاصة النص</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>يساعد المتعلمين على جرد المعجم المهيمن</w:t></w:r></w:p></w:tc>
                    <w:tc>
                      <w:p><w:r><w:t>الحقول الدلالية: حقل التضامن وحقل المجتمع</w:t></w:r></w:p>
                      <w:p><w:r><w:t>التركيب: خلاصة تركيبية تبرز قيمة التضامن الإنساني</w:t></w:r></w:p>
                      <w:p><w:r><w:t>الاستثمار: ابحث عن نماذج للعمل الجمعوي التضامني</w:t></w:r></w:p>
                    </w:tc>
                    <w:tc><w:p><w:r><w:t>تقويم تكويني وإجمالي</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>مشاركة أغلب المتعلمين في التحليل والتركيب</w:t></w:r></w:p></w:tc>
                  </w:tr>
                </w:tbl>
              </w:body>
            </w:document>
        """.trimIndent()

        val extracted = com.example.extractor.DocumentFileReader.parseDocumentXml(complexDocxXml)
        assertTrue(!extracted.contains("<u>") && !extracted.contains("<b>"))

        val parseResult = com.example.extractor.JadhadhaParser.parseWithReport(extracted, "درس افتراضي")
        val plan = parseResult.plan

        // Verify Header Isolation populated JadhadhaInfo and did NOT leak into unclassifiedLines
        assertEquals("التضامن الإنساني", plan.topic)
        assertEquals("الثالثة ثانوي إعدادي", plan.info.level)
        assertEquals("الكتاب المدرسي والسبورة", plan.info.materials)
        assertEquals("التواصل السليم باللغة العربية", plan.info.communicativeComp)
        assertEquals(0, parseResult.report.unclassifiedLines.size)

        // Verify Dual Evaluation Columns merged into analysis.eval
        val analysisStage = plan.stages["analysis"]!!
        assertTrue(analysisStage.eval.any { it.contains("تقويم تكويني وإجمالي") })
        assertTrue(analysisStage.eval.any { it.contains("مشاركة أغلب المتعلمين") })

        // Verify Intra-Cell Stage Splitting distributed lines across analysis, synthesis, and investment
        assertTrue(analysisStage.content.any { it.contains("حقل التضامن وحقل المجتمع") })
        assertTrue(plan.stages["synthesis"]!!.content.any { it.contains("خلاصة تركيبية تبرز قيمة التضامن الإنساني") })
        assertTrue(plan.stages["investment"]!!.content.any { it.contains("ابحث عن نماذج للعمل الجمعوي التضامني") })

        // 2. Verify Semantic Verb-Prefix Fallback on unstructured stage paragraphs
        val semanticRaw = """
            المكون: النصوص القرائية
            الموضوع: القيم الوطنية
            ملاحظة النص وتأطيره
            قدرة المتعلم على تأطير النص وصياغة فرضياته
            يطالب المتعلمين بملاحظة العنوان والصورة المرفقة
            يؤطرون النص انطلاقاً من العتبات الخارجية
            يصوغ المتعلمون فرضيات القراءة
        """.trimIndent()
        val semanticPlan = com.example.extractor.JadhadhaParser.parseRawText(semanticRaw, "القيم الوطنية")
        val obsStage = semanticPlan.stages["observation"]!!
        assertTrue(obsStage.abil.any { it.contains("قدرة المتعلم على تأطير النص") })
        assertTrue(obsStage.teacher.any { it.contains("يطالب المتعلمين بملاحظة العنوان") })
        assertTrue(obsStage.content.any { it.contains("يؤطرون النص") })
        assertTrue(obsStage.content.any { it.contains("يصوغ المتعلمون فرضيات القراءة") })

        // 3. Verify GeminiAiService.parseRawJadhadhaWithAi distributes 100% of information
        val aiResult = com.example.ai.GeminiAiService.parseRawJadhadhaWithAi(
            rawText = extracted,
            componentType = "READING"
        )
        assertTrue(aiResult.isSuccess)
        val aiPlan = aiResult.getOrThrow()
        assertEquals("التضامن الإنساني", aiPlan.topic)
        assertTrue(aiPlan.stages.size >= 7)
        assertTrue(aiPlan.stages["synthesis"]!!.content.any { it.contains("خلاصة تركيبية") })
        assertTrue(aiPlan.stages["investment"]!!.content.any { it.contains("ابحث عن نماذج") })
    }

    @Test
    fun `test deleteAllJadhadhat and clearAllStoredData clear database and temp files`() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, com.example.data.AppDatabase::class.java).build()
        val dao = db.jadhadhaDao()
        val repository = com.example.data.JadhadhaRepository(dao, context)

        repository.prepopulateSamplesIfEmpty()
        assertEquals(3, dao.getCount())

        val tempDocx = java.io.File(context.cacheDir, "temp_test_jadhadha.docx").apply {
            writeText("dummy")
        }
        assertTrue(tempDocx.exists())

        repository.clearAllStoredData()
        assertEquals(0, dao.getCount())
        assertTrue(repository.allPlans.first().isEmpty())
        assertTrue(!tempDocx.exists())

        db.close()
    }

    @Test
    fun `task 4_1 - extract all paragraphs of Word docx file with unique IDs and original text copy`() {
        val paragraphs = listOf(
            "المكون: النصوص القرائية",
            "الموضوع: التسامح والسلام",
            "الفقرة الأولى: مدخل تمهيدي عام حول قيم التعايش الإنساني.",
            "الفقرة الثانية: ملاحظة خارجية حول سياق النص التاريخي والاجتماعي.",
            "الفقرة الثالثة: إشارة مرجعية لكتاب المختار في اللغة العربية ص 52.",
            "الفقرة الرابعة: توصية تربوية خاصة بتدبير النقاش التفاعلي.",
            "الفقرة الخامسة: خاتمة وتوجيهات عامة لأنشطة البحث المنزلي."
        )
        val zipOut = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(zipOut).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("word/document.xml"))
            val xmlBuilder = StringBuilder()
            xmlBuilder.append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>")
            for (p in paragraphs) {
                xmlBuilder.append("<w:p><w:r><w:t>").append(p).append("</w:t></w:r></w:p>")
            }
            xmlBuilder.append("</w:body></w:document>")
            zos.write(xmlBuilder.toString().toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            zos.closeEntry()
        }

        val structured = com.example.extractor.DocumentFileReader.extractStructuredDocx(
            java.io.ByteArrayInputStream(zipOut.toByteArray())
        )

        // 1. Original raw text is retained verbatim before processing
        assertEquals(paragraphs.joinToString("\n"), structured.rawOriginalText)

        // 2. All 7 paragraphs are extracted as independent traceable units with unique IDs
        assertEquals(7, structured.units.size)
        val uniqueIds = structured.units.map { it.id }.toSet()
        assertEquals(7, uniqueIds.size)
        for ((idx, expectedPara) in paragraphs.withIndex()) {
            assertEquals("unit_${idx + 1}", structured.units[idx].id)
            assertEquals(expectedPara, structured.units[idx].verbatimText)
            assertEquals(com.example.model.DocumentElementType.PARAGRAPH, structured.units[idx].elementType)
            assertEquals(idx, structured.units[idx].location.paragraphIndex)
        }

        // 3. Parse and verify zero missing units in integrity report
        val parseResult = com.example.extractor.JadhadhaParser.parseWithReport(structured.structuredText)
        val integrity = parseResult.report.integrityReport
        assertNotNull(integrity)
        assertEquals(paragraphs.joinToString("\n"), integrity!!.originalRawText)
        assertEquals(7, integrity.extractedUnits.size)
        assertTrue("No paragraph may be lost during parsing", integrity.missingUnits.isEmpty())
    }

    @Test
    fun `task 4_2 - preserve short lines without dropping`() {
        val docXml = """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>همزة</w:t></w:r></w:p>
                <w:p><w:r><w:t>أ ب</w:t></w:r></w:p>
                <w:p><w:r><w:t>الأمثلة</w:t></w:r></w:p>
                <w:p><w:r><w:t>توجيهات</w:t></w:r></w:p>
                <w:p><w:r><w:t>تنبيه!</w:t></w:r></w:p>
                <w:p><w:r><w:t>تمهيد</w:t></w:r></w:p>
              </w:body>
            </w:document>
        """.trimIndent()

        val extracted = com.example.extractor.DocumentFileReader.parseDocumentXml(docXml)
        val parseResult = com.example.extractor.JadhadhaParser.parseWithReport(extracted, "درس قصير")
        val report = parseResult.report
        val integrity = report.integrityReport!!

        val expectedShortLines = listOf("همزة", "أ ب", "الأمثلة", "توجيهات", "تنبيه!", "تمهيد")
        assertEquals(6, integrity.extractedUnits.size)
        for (shortLine in expectedShortLines) {
            assertTrue(
                "Short line '$shortLine' must be extracted",
                integrity.extractedUnits.any { it.verbatimText == shortLine }
            )
            assertTrue(
                "Short line '$shortLine' must be preserved in unclassifiedLines or finalUsedItems without being dropped",
                report.unclassifiedLines.any { it.verbatimText == shortLine || it.text == shortLine } ||
                    integrity.finalUsedItems.contains(shortLine)
            )
        }
        assertTrue("No short line should be reported missing", integrity.missingUnits.isEmpty())
    }

    @Test
    fun `task 4_3 - never delete unclassified lines and allow manual classification`() {
        val rawText = """
            المكون: الدرس اللغوي
            الموضوع: اسم الفاعل وعمله
            ملاحظة تربوية أولى غير مصنفة قبل الجدول
            الهدف من هذا التمرين تعميق الفهم الصرفي
            [مرحلة: تشخيص المكتسبات]
            المحتوى الديداكتيكي:
            مراجعة الفعل الثلاثي وغير الثلاثي.
            [نهاية الجدول]
            توصية ختامية بعد الجدول للمراجعة المنزلية
        """.trimIndent()

        val initialResult = com.example.extractor.JadhadhaParser.parseWithReport(rawText, "اسم الفاعل وعمله")
        val unclassified = initialResult.report.unclassifiedLines

        // Verify all 3 unclassified lines (before table, starting with "الهدف من...", and after table) are preserved
        assertEquals(3, unclassified.size)
        assertTrue(unclassified.any { it.text.contains("ملاحظة تربوية أولى غير مصنفة") })
        assertTrue(unclassified.any { it.text.contains("الهدف من هذا التمرين تعميق الفهم الصرفي") })
        assertTrue(unclassified.any { it.text.contains("توصية ختامية بعد الجدول") })

        // Verify each unclassified line has a unique unitId, verbatimText, and location
        unclassified.forEach { uLine ->
            assertTrue(uLine.unitId.startsWith("unit_"))
            assertTrue(uLine.verbatimText.isNotBlank())
            assertTrue(uLine.location.lineNumber > 0)
        }

        // Verify completion is NOT 100% while unclassified lines and DEFAULT fields exist
        assertTrue(initialResult.report.verificationCompletionPercentage < 100)

        // Manually classify the first unclassified line into lang_observation -> teacher
        val firstLine = unclassified.first { it.text.contains("ملاحظة تربوية أولى") }
        val afterFirstAssign = com.example.extractor.JadhadhaParser.assignUnclassifiedLine(
            currentResult = initialResult,
            line = firstLine,
            targetStageKey = "lang_observation",
            targetField = "teacher"
        )
        assertEquals(2, afterFirstAssign.report.unclassifiedLines.size)
        assertTrue(afterFirstAssign.plan.stages["lang_observation"]!!.teacher.contains(firstLine.verbatimText))
        assertEquals(
            com.example.model.FieldSource.FILE,
            afterFirstAssign.plan.stages["lang_observation"]!!.sourceFor("teacher")
        )
    }

    @Test
    fun `task 4_4 - no content duplication and detect duplicates in integrity report`() {
        // Part A: Unintended adjacent duplication inside the same cell/block -> deduplicated and recorded in duplicateUnits
        val rawWithUnintendedDuplicates = """
            المكون: النصوص القرائية
            الموضوع: القيم الإنسانية
            [مرحلة: تشخيص المكتسبات]
            المحتوى الديداكتيكي:
            عنصر مكرر في نفس الخانة لا ينبغي أن يتكرر في المخرج النهائي.
            عنصر مكرر في نفس الخانة لا ينبغي أن يتكرر في المخرج النهائي.
            عنصر فريد ثانٍ في الخانة.
        """.trimIndent()

        val result = com.example.extractor.JadhadhaParser.parseWithReport(rawWithUnintendedDuplicates, "القيم الإنسانية")
        val diagContent = result.plan.stages["diagnostic"]!!.content

        // 1. Final stage content has NO unintended duplicate entries
        assertEquals(
            1,
            diagContent.count { it == "عنصر مكرر في نفس الخانة لا ينبغي أن يتكرر في المخرج النهائي." }
        )
        assertEquals(2, diagContent.size)

        // 2. Integrity report recorded the unintended duplicate unit/item from the input
        val integrity = result.report.integrityReport!!
        assertTrue(integrity.duplicateItems.contains("عنصر مكرر في نفس الخانة لا ينبغي أن يتكرر في المخرج النهائي."))
        assertTrue(integrity.duplicateUnits.isNotEmpty())

        // Part B: Legitimate repetition of identical text in two different document locations (e.g., Row 1 vs Row 2 of a table)
        val repeatedSentence = "تقويم تكويني مرحلي لقياس استيعاب المتعلمين للظاهرة."
        val docxWithLegitimateRepetitionInTwoRows = """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>المكون: الدرس اللغوي</w:t></w:r></w:p>
                <w:p><w:r><w:t>الموضوع: اسم الفاعل وعمله</w:t></w:r></w:p>
                <w:tbl>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>المراحل</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>المحتوى الديداكتيكي</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>التقويم والدعم</w:t></w:r></w:p></w:tc>
                  </w:tr>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>ملاحظة الظاهرة ووصفها</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>المجموعة الأولى: صياغة اسم الفاعل من الفعل الثلاثي.</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>$repeatedSentence</w:t></w:r></w:p></w:tc>
                  </w:tr>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>ملاحظة الظاهرة ووصفها</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>المجموعة الثانية: صياغة اسم الفاعل من غير الثلاثي.</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>$repeatedSentence</w:t></w:r></w:p></w:tc>
                  </w:tr>
                </w:tbl>
              </w:body>
            </w:document>
        """.trimIndent()

        val structuredLegit = com.example.extractor.DocumentFileReader.extractStructuredXml(docxWithLegitimateRepetitionInTwoRows)
        val legitResult = com.example.extractor.JadhadhaParser.parseWithReport(structuredLegit.structuredText, "اسم الفاعل وعمله")
        val legitIntegrity = legitResult.report.integrityReport!!

        // Verify two distinct ExtractedUnits exist with distinct IDs and distinct UnitLocations (rowIndex = 1 vs rowIndex = 2)
        val repeatedUnits = legitIntegrity.extractedUnits.filter { it.verbatimText == repeatedSentence }
        assertEquals(2, repeatedUnits.size)
        assertTrue(repeatedUnits[0].id != repeatedUnits[1].id)
        assertEquals(1, repeatedUnits[0].location.rowIndex)
        assertEquals(2, repeatedUnits[1].location.rowIndex)

        // Verify legitimate repetition across two different table rows is NOT deleted and NOT flagged as an unintended duplicate
        val obsEval = legitResult.plan.stages["lang_observation"]!!.eval
        assertEquals(2, obsEval.count { it == repeatedSentence })
        assertTrue(legitIntegrity.duplicateUnits.none { it.verbatimText == repeatedSentence })
        assertTrue(legitIntegrity.missingUnits.isEmpty())

        // Verify ContentIntegrityMonitor compares units by ID and location (not text Set):
        // If one of the two legitimate occurrences is dropped from the final plan, the second unit is flagged in missingUnits!
        val stagesWithOneDropped = legitResult.plan.stages.toMutableMap()
        stagesWithOneDropped["lang_observation"] = legitResult.plan.stages["lang_observation"]!!.copy(
            eval = listOf(repeatedSentence) // Only 1 occurrence left instead of 2
        )
        val integrityAfterDroppingSecondOccurrence = com.example.extractor.JadhadhaParser.verifyContentIntegrity(
            originalRawText = legitResult.report.originalRawText,
            extractedUnits = legitResult.report.extractedUnits,
            plan = legitResult.plan.copy(stages = stagesWithOneDropped),
            unclassifiedLines = legitResult.report.unclassifiedLines
        )
        assertEquals(1, integrityAfterDroppingSecondOccurrence.missingUnits.size)
        assertEquals(repeatedUnits[1].id, integrityAfterDroppingSecondOccurrence.missingUnits.first().id)
        assertEquals(2, integrityAfterDroppingSecondOccurrence.missingUnits.first().location.rowIndex)
    }

    @Test
    fun `task 4_5 - do not alter original text without reason and detect modifications in integrity report`() {
        val originalLine1 = "1- الملاحظة والتأطير: دلالة العنوان وصاحب النص (ص: 44)."
        val originalLine2 = "- يطرح الأستاذ أسئلة تمهيدية؛ لاستحضار المكتسبات القبلية!"
        val raw = """
            المكون: النصوص القرائية
            الموضوع: المنفى
            [مرحلة: ملاحظة النص]
            أنشطة المدرس:
            $originalLine2
            المحتوى الديداكتيكي:
            $originalLine1
        """.trimIndent()

        val result = com.example.extractor.JadhadhaParser.parseWithReport(raw, "المنفى")
        val obsStage = result.plan.stages["observation"]!!

        // 1. Original text (including "1- " numbering and "- " bullet) is preserved verbatim without alteration
        assertTrue("Exact numbered line must be preserved verbatim", obsStage.content.contains(originalLine1))
        assertTrue("Exact bulleted line must be preserved verbatim", obsStage.teacher.contains(originalLine2))
        assertTrue(
            "No unjustified text modification should occur during normal parsing",
            result.report.integrityReport!!.modifiedUnits.isEmpty()
        )

        // 2. If a caller alters the text in the final plan, verifyContentIntegrity detects and reports it
        val alteredStages = result.plan.stages.toMutableMap()
        alteredStages["observation"] = obsStage.copy(
            content = listOf("الملاحظة والتأطير: دلالة العنوان وصاحب النص (نسخة معدلة)")
        )
        val alteredPlan = result.plan.copy(stages = alteredStages)
        val integrityAfterAlteration = com.example.extractor.JadhadhaParser.verifyContentIntegrity(
            originalRawText = result.plan.let { result.report.originalRawText },
            extractedUnits = result.report.extractedUnits,
            plan = alteredPlan,
            unclassifiedLines = result.report.unclassifiedLines
        )
        assertTrue("Altered text must be flagged in modifiedUnits", integrityAfterAlteration.modifiedUnits.isNotEmpty())
        assertEquals(originalLine1, integrityAfterAlteration.modifiedUnits.first().originalText)
    }

    @Test
    fun `task 4_6 - handle files with mixed tables and paragraphs preserving source locations`() {
        val mixedXml = """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>تمهيد عام خارج الجداول حول منهجية تدريس المكون</w:t></w:r></w:p>
                <w:tbl>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>المكون: الدرس اللغوي</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>الموضوع: اسم الآلة</w:t></w:r></w:p></w:tc>
                  </w:tr>
                </w:tbl>
                <w:p><w:r><w:t>فقرة انتقالية بين جدول الترويسة وجدول سير الدرس</w:t></w:r></w:p>
                <w:tbl>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>المراحل</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>المحتوى الديداكتيكي</w:t></w:r></w:p></w:tc>
                  </w:tr>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>تشخيص المكتسبات</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>تذكير بالمشتقات السابقة: اسم الفاعل واسم المفعول.</w:t></w:r></w:p></w:tc>
                  </w:tr>
                </w:tbl>
                <w:p><w:r><w:t>ملحق ختامي بعد الجداول يتضمن تمارين منزلية إضافية</w:t></w:r></w:p>
              </w:body>
            </w:document>
        """.trimIndent()

        val structured = com.example.extractor.DocumentFileReader.extractStructuredXml(mixedXml)
        val parseResult = com.example.extractor.JadhadhaParser.parseWithReport(structured.structuredText)
        val report = parseResult.report
        val integrity = report.integrityReport!!

        // Verify paragraphs and table cells are all extracted with accurate locations
        val introParaUnit = integrity.extractedUnits.first { it.verbatimText.contains("تمهيد عام خارج الجداول") }
        assertEquals(com.example.model.DocumentElementType.PARAGRAPH, introParaUnit.elementType)
        assertEquals(0, introParaUnit.location.paragraphIndex)

        val midParaUnit = integrity.extractedUnits.first { it.verbatimText.contains("فقرة انتقالية بين جدول الترويسة") }
        assertEquals(com.example.model.DocumentElementType.PARAGRAPH, midParaUnit.elementType)
        assertEquals(1, midParaUnit.location.paragraphIndex)

        val lessonCellUnit = integrity.extractedUnits.first { it.verbatimText.contains("تذكير بالمشتقات السابقة") }
        assertEquals(com.example.model.DocumentElementType.TABLE_CELL, lessonCellUnit.elementType)
        assertEquals(1, lessonCellUnit.location.tableIndex)
        assertEquals(1, lessonCellUnit.location.rowIndex)
        assertEquals(1, lessonCellUnit.location.colIndex)

        val outroParaUnit = integrity.extractedUnits.first { it.verbatimText.contains("ملحق ختامي بعد الجداول") }
        assertEquals(com.example.model.DocumentElementType.PARAGRAPH, outroParaUnit.elementType)
        assertEquals(2, outroParaUnit.location.paragraphIndex)

        // Verify all 3 free paragraphs outside the lesson stages are preserved in unclassifiedLines and nothing is lost
        assertEquals(3, report.unclassifiedLines.size)
        assertTrue(parseResult.plan.stages["lang_diagnostic"]!!.content.any { it.contains("تذكير بالمشتقات السابقة") })
        assertTrue("Zero units may be missing in mixed document", integrity.missingUnits.isEmpty())
    }

    @Test
    fun `task 4_7 - preserve Arabic text tatweel diacritics and punctuation marks`() {
        val arabicRichText = "قَالَ الشَّاعِرُ فِي قَصِيدَتِهِ «المَنْفَى» — بَعْدَ التَّأَمُّلِ —: أَلَا يَا طَائِرَ البَانِ، مَتَى العَوْدَةُ؟! (ص: ٤٤؛ فَقْرَة: ١-أ) مَعَ تَطْوِيلٍ: اللـهـمَّ احْفَظِ الوَطَنَ..."
        val docXml = """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>المكون: النصوص القرائية</w:t></w:r></w:p>
                <w:p><w:r><w:t>الموضوع: المنفى</w:t></w:r></w:p>
                <w:tbl>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>المراحل</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>المحتوى الديداكتيكي</w:t></w:r></w:p></w:tc>
                  </w:tr>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>فهم النص</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>$arabicRichText</w:t></w:r></w:p></w:tc>
                  </w:tr>
                </w:tbl>
              </w:body>
            </w:document>
        """.trimIndent()

        val extracted = com.example.extractor.DocumentFileReader.parseDocumentXml(docXml)
        val parseResult = com.example.extractor.JadhadhaParser.parseWithReport(extracted, "المنفى")
        val compContent = parseResult.plan.stages["comprehension"]!!.content

        // Verify exact preservation of diacritics (حركات التشكيل), tatweel (\u0640), guillemets («»), punctuation (، ؛ ؟ ! : — ...), and Eastern Arabic numerals (٤٤، ١)
        assertTrue("Rich Arabic text with diacritics, tatweel, and punctuation must match verbatim", compContent.contains(arabicRichText))
        assertTrue(compContent.first().contains("\u0640"))
        assertTrue(compContent.first().contains("«المَنْفَى»"))
        assertTrue(compContent.first().contains("؟!"))
        assertTrue(compContent.first().contains("؛"))
        assertTrue(parseResult.report.integrityReport!!.modifiedUnits.isEmpty())
        assertTrue(parseResult.report.integrityReport!!.missingUnits.isEmpty())
    }

    @Test
    fun `task 4_8 - rawOriginalText contains all units across document body tables headers and footers`() {
        val headerXml = """
            <w:hdr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:p><w:r><w:t>الأستاذ: محمد العابد</w:t></w:r></w:p>
              <w:p><w:r><w:t>المؤسسة: مدرسة أنوال الإعدادية</w:t></w:r></w:p>
            </w:hdr>
        """.trimIndent()
        val documentXml = """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>المكون: الدرس اللغوي</w:t></w:r></w:p>
                <w:p><w:r><w:t>الموضوع: اسم المفعول وعمله</w:t></w:r></w:p>
                <w:tbl>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>المراحل</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>المحتوى الديداكتيكي</w:t></w:r></w:p></w:tc>
                  </w:tr>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>تشخيص المكتسبات</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>يصاغ اسم المفعول من الفعل المبني للمجهول.</w:t></w:r></w:p></w:tc>
                  </w:tr>
                </w:tbl>
              </w:body>
            </w:document>
        """.trimIndent()
        val footerXml = """
            <w:ftr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:p><w:r><w:t>المرجع: المختار في اللغة العربية - السنة الثالثة إعدادي</w:t></w:r></w:p>
            </w:ftr>
        """.trimIndent()

        val zipOut = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(zipOut).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("word/header1.xml"))
            zos.write(headerXml.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(java.util.zip.ZipEntry("word/document.xml"))
            zos.write(documentXml.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(java.util.zip.ZipEntry("word/footer1.xml"))
            zos.write(footerXml.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            zos.closeEntry()
        }

        val extraction = com.example.extractor.DocumentFileReader.extractStructuredDocx(
            java.io.ByteArrayInputStream(zipOut.toByteArray())
        )

        // Verify units from body paragraphs, table cells, header1.xml, and footer1.xml are all present in extraction.units
        assertTrue(extraction.units.any { it.location.sourcePart == "word/document.xml" && it.elementType == com.example.model.DocumentElementType.PARAGRAPH })
        assertTrue(extraction.units.any { it.location.sourcePart == "word/document.xml" && it.elementType == com.example.model.DocumentElementType.TABLE_CELL })
        assertTrue(extraction.units.any { it.location.sourcePart == "word/header1.xml" && it.elementType == com.example.model.DocumentElementType.HEADER_FOOTER })
        assertTrue(extraction.units.any { it.location.sourcePart == "word/footer1.xml" && it.elementType == com.example.model.DocumentElementType.HEADER_FOOTER })

        // Verify EVERY SINGLE ExtractedUnit in extraction.units appears verbatim in rawOriginalText
        val rawLines = extraction.rawOriginalText.lines()
        for (unit in extraction.units) {
            assertTrue(
                "Unit ${unit.id} ('${unit.verbatimText}') from ${unit.location.sourcePart} must appear in rawOriginalText",
                rawLines.contains(unit.verbatimText)
            )
        }
    }
}


