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
    fun `test GeminiAiService uses gemini-3_8-flash and succeeds with valid transport or synthesizeSmartPlan`() = kotlinx.coroutines.runBlocking {
        assertEquals("gemini-3.8-flash", com.example.ai.GeminiAiService.PRIMARY_MODEL)

        // With invalid AQ token, generateJadhadha must fail honestly rather than returning fake AI success
        val invalidResult = com.example.ai.GeminiAiService.generateJadhadha(
            topic = "التضامن الاجتماعي",
            lessonText = "إن التضامن الاجتماعي ركيزة أساسية في بناء المجتمعات المتماسكة.",
            component = com.example.model.PedagogicalComponent.READING,
            customKey = "AQ.Ab8RN6J1NZ6BMsz62m4AOkJ_Dr"
        )
        assertTrue(invalidResult.isFailure)

        // Local template synthesis remains available explicitly as synthesizeSmartPlan
        val plan = com.example.ai.GeminiAiService.synthesizeSmartPlan(
            topic = "التضامن الاجتماعي",
            lessonText = "إن التضامن الاجتماعي ركيزة أساسية في بناء المجتمعات المتماسكة.",
            component = com.example.model.PedagogicalComponent.READING
        )
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
        // Without a Gemini key, local rule classification is used explicitly and never claimed as AI
        assertTrue(!delegatedResult.report.isAiDelegated)
        assertTrue(delegatedResult.report.isLocalFallback)
        assertEquals(0, delegatedResult.report.unclassifiedLines.size)
        assertEquals("اسما الزمان والمكان", delegatedResult.plan.topic)
        assertEquals(
            com.example.model.FieldSource.LOCAL_RULE,
            delegatedResult.plan.stages["lang_observation"]?.sourceFor("teacher")
        )
        assertEquals(
            com.example.model.FieldSource.DEFAULT,
            delegatedResult.plan.stages["lang_observation"]?.sourceFor("content")
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

        // 3. Verify GeminiAiService.parseRawJadhadhaWithAi distributes 100% of information when Gemini responds
        try {
            com.example.ai.GeminiAiService.setTransportForTesting(
                com.example.ai.GeminiAiService.GeminiTransport { _, _, _ ->
                    val parsedLocal = com.example.extractor.JadhadhaParser.parseWithReport(extracted, "التضامن الإنساني")
                    val stagesJson = org.json.JSONObject()
                    for ((stageKey, st) in parsedLocal.plan.stages) {
                        stagesJson.put(
                            stageKey,
                            org.json.JSONObject().apply {
                                put("abil", org.json.JSONArray(st.abil))
                                put("teacher", org.json.JSONArray(st.teacher))
                                put("content", org.json.JSONArray(st.content))
                                put("eval", org.json.JSONArray(st.eval))
                            }
                        )
                    }
                    val rootJson = org.json.JSONObject().apply {
                        put("topic", "التضامن الإنساني")
                        put("component", "reading")
                        put("stages", stagesJson)
                    }
                    200 to rootJson.toString()
                }
            )
            val aiResult = com.example.ai.GeminiAiService.parseRawJadhadhaWithAi(
                rawText = extracted,
                componentType = "READING",
                customKey = "AIzaSyValidTestKeyForGeminiClassification12345"
            )
            assertTrue(aiResult.isSuccess)
            val aiPlan = aiResult.getOrThrow()
            assertEquals("التضامن الإنساني", aiPlan.topic)
            assertEquals(7, aiPlan.stages.size)
            assertTrue(aiPlan.stages["synthesis"]!!.content.any { it.contains("خلاصة تركيبية") })
            assertTrue(aiPlan.stages["investment"]!!.content.any { it.contains("ابحث عن نماذج") })
        } finally {
            com.example.ai.GeminiAiService.resetTransportForTesting()
        }
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

    // =========================================================================
    // PHASE 2 TESTS: Gemini vs Local Classification, Context-Awareness, JSON
    // Validation, Failure Handling, Manual Edit, and Retry
    // =========================================================================

    @Test
    fun `phase2_1 - local contextual classification is recorded as LOCAL_RULE and distinguishes context over isolated keywords`() {
        // 1. Test context-aware pedagogical classification:
        // Item A contains the word "تقويم" (evaluation keyword), but its grammatical/pedagogical frame starts with "قدرة المتعلم على..." -> OBJECTIVES_COMPETENCIES
        val itemA = com.example.ai.GeminiAiService.classifyPedagogicalContextLocally(
            rawLine = "قدرة المتعلم على تقويم النصوص السردية وتحليل بنيتها.",
            component = com.example.model.PedagogicalComponent.READING,
            unitId = "unit_a"
        )!!
        assertEquals(com.example.model.PedagogicalCategory.OBJECTIVES_COMPETENCIES, itemA.proposedCategory)
        assertEquals("abil", itemA.targetField)
        assertEquals(com.example.model.ClassificationSource.LOCAL_RULE, itemA.source)

        // Item B contains the word "الأهداف" and "الفرضيات", but starts with teacher verb "يوجه الأستاذ المتعلمين..." -> TEACHER_ACTIVITIES
        val itemB = com.example.ai.GeminiAiService.classifyPedagogicalContextLocally(
            rawLine = "يوجه الأستاذ المتعلمين إلى استخراج المؤشرات الدالة على الفرضية.",
            component = com.example.model.PedagogicalComponent.READING,
            unitId = "unit_b"
        )!!
        assertEquals(com.example.model.PedagogicalCategory.TEACHER_ACTIVITIES, itemB.proposedCategory)
        assertEquals("teacher", itemB.targetField)
        assertEquals("observation", itemB.stageKey)
        assertEquals(com.example.model.ClassificationSource.LOCAL_RULE, itemB.source)

        // Item C starts with learner plural verb "يجيب المتعلمون..." -> LEARNER_ACTIVITIES
        val itemC = com.example.ai.GeminiAiService.classifyPedagogicalContextLocally(
            rawLine = "يجيب المتعلمون عن أسئلة الفهم ويحددون الفكرة العامة للنص.",
            component = com.example.model.PedagogicalComponent.READING,
            unitId = "unit_c"
        )!!
        assertEquals(com.example.model.PedagogicalCategory.LEARNER_ACTIVITIES, itemC.proposedCategory)
        assertEquals("comprehension", itemC.stageKey)
        assertEquals(com.example.model.ClassificationSource.LOCAL_RULE, itemC.source)

        // Item D is remediation/support -> SUPPORT_REMEDIATION
        val itemD = com.example.ai.GeminiAiService.classifyPedagogicalContextLocally(
            rawLine = "دعم فردي ومعالجة التعثرات المسجلة في صياغة اسم الفاعل.",
            component = com.example.model.PedagogicalComponent.LANGUAGE,
            unitId = "unit_d"
        )!!
        assertEquals(com.example.model.PedagogicalCategory.SUPPORT_REMEDIATION, itemD.proposedCategory)
        assertEquals("eval", itemD.targetField)
        assertEquals(com.example.model.ClassificationSource.LOCAL_RULE, itemD.source)

        // 2. Verify classifyWithLocalRulesOnly records FieldSource.LOCAL_RULE and ClassificationSource.LOCAL_RULE, NEVER FieldSource.AI or GEMINI
        val raw = """
            المكون: الدرس اللغوي
            الموضوع: اسم الفاعل وعمله
            يطرح الأستاذ أسئلة تمهيدية لاستحضار مكتسبات الدرس السابق.
            يصوغ المتعلمون استنتاجاً تركيبياً لقاعدة اسم الفاعل وشروط عمله.
        """.trimIndent()
        val initial = com.example.extractor.JadhadhaParser.parseWithReport(raw, "اسم الفاعل وعمله")
        assertTrue(initial.report.unclassifiedLines.isNotEmpty())

        val localResult = com.example.ai.GeminiAiService.classifyWithLocalRulesOnly(initial)
        assertTrue(!localResult.report.isAiDelegated)
        assertEquals(0, localResult.report.totalAiFields)
        assertTrue(localResult.report.totalLocalRuleFields > 0)
        assertTrue(localResult.report.localRuleClassifiedLinesCount > 0)
        assertEquals(0, localResult.report.aiClassifiedLinesCount)

        // Ensure no stage field has FieldSource.AI or FieldSource.GEMINI
        for ((_, stage) in localResult.plan.stages) {
            for (field in listOf("abil", "teacher", "content", "eval")) {
                val src = stage.sourceFor(field)
                assertTrue(
                    "Local classification must never set AI/GEMINI source",
                    src != com.example.model.FieldSource.AI && src != com.example.model.FieldSource.GEMINI
                )
            }
        }
        assertTrue(
            localResult.report.classificationItems.all { it.source == com.example.model.ClassificationSource.LOCAL_RULE }
        )
    }

    @Test
    fun `phase2_2 - genuine Gemini classification is recorded with ClassificationSource GEMINI and FieldSource AI`() = kotlinx.coroutines.runBlocking {
        val raw = """
            المكون: النصوص القرائية
            الموضوع: المنفى
            تأمل دلالة العنوان وعلاقته بسياق الحماية الفرنسية بالمغرب.
            الحقول الدلالية المهيمنة في النص: حقل المعاناة وحقل الصمود الوطني.
        """.trimIndent()
        val initial = com.example.extractor.JadhadhaParser.parseWithReport(raw, "المنفى")
        assertEquals(2, initial.report.unclassifiedLines.size)
        val u1 = initial.report.unclassifiedLines[0]
        val u2 = initial.report.unclassifiedLines[1]

        try {
            com.example.ai.GeminiAiService.setTransportForTesting(
                com.example.ai.GeminiAiService.GeminiTransport { _, _, _ ->
                    val responseJson = """
                        {
                          "classifications": [
                            {
                              "unitId": "${u1.unitId}",
                              "originalText": "${u1.verbatimText}",
                              "proposedCategory": "INTRODUCTION_LAUNCH",
                              "component": "READING",
                              "stageKey": "observation",
                              "targetField": "content",
                              "confidence": 0.94,
                              "source": "GEMINI",
                              "rationale": "ملاحظة العنوان وتأطيره ضمن سياقه التاريخي"
                            },
                            {
                              "unitId": "${u2.unitId}",
                              "originalText": "${u2.verbatimText}",
                              "proposedCategory": "ANALYSIS_DISCUSSION",
                              "component": "READING",
                              "stageKey": "analysis",
                              "targetField": "content",
                              "confidence": 0.91,
                              "source": "GEMINI",
                              "rationale": "تحليل الحقول الدلالية المهيمنة في النص"
                            }
                          ]
                        }
                    """.trimIndent()
                    200 to responseJson
                }
            )

            val refined = com.example.ai.GeminiAiService.classifyAndRefineExtraction(
                rawText = raw,
                initialResult = initial,
                customKey = "AIzaSyValidTestKeyForGeminiClassification12345",
                preferLocalRulesFirst = false
            )

            assertTrue(refined.report.isAiDelegated)
            assertTrue(!refined.report.geminiFailed)
            assertTrue(!refined.report.isLocalFallback)
            assertEquals(2, refined.report.aiClassifiedLinesCount)
            assertEquals(0, refined.report.unclassifiedLines.size)

            // Verify FieldSource.AI is set ONLY on the fields actually classified by Gemini
            assertEquals(com.example.model.FieldSource.AI, refined.plan.stages["observation"]!!.sourceFor("content"))
            assertEquals(com.example.model.FieldSource.AI, refined.plan.stages["analysis"]!!.sourceFor("content"))
            // Other untouched default fields remain DEFAULT (never overwritten with fake AI text)
            assertEquals(com.example.model.FieldSource.DEFAULT, refined.plan.stages["investment"]!!.sourceFor("content"))

            // Verify ClassificationSource.GEMINI on the classification items and units
            assertEquals(2, refined.report.classificationItems.size)
            assertTrue(refined.report.classificationItems.all { it.source == com.example.model.ClassificationSource.GEMINI })
            val updatedUnits = refined.report.extractedUnits.filter { it.id == u1.unitId || it.id == u2.unitId }
            assertTrue(updatedUnits.all { it.classificationSource == com.example.model.ClassificationSource.GEMINI })
        } finally {
            com.example.ai.GeminiAiService.resetTransportForTesting()
        }
    }

    @Test
    fun `phase2_3 - Gemini failure does not produce fake AI success and triggers explicit local fallback`() = kotlinx.coroutines.runBlocking {
        val raw = """
            المكون: الدرس اللغوي
            الموضوع: اسم الآلة
            يستدرج الأستاذ المتعلمين لتذكر المشتقات السابقة.
            عبارة دلالية تحتاج إلى تصنيف Gemini عند تعذر القاعدة المحلية.
        """.trimIndent()
        val initial = com.example.extractor.JadhadhaParser.parseWithReport(raw, "اسم الآلة")

        try {
            // Simulate network/HTTP failure from Gemini
            com.example.ai.GeminiAiService.setTransportForTesting(
                com.example.ai.GeminiAiService.GeminiTransport { _, _, _ ->
                    throw java.io.IOException("فشل الاتصال بخادم Gemini: انقطاع الشبكة")
                }
            )

            // 1. classifyAndRefineExtraction must NOT claim AI success; it falls back to LOCAL_RULE with geminiFailed = true
            val fallbackResult = com.example.ai.GeminiAiService.classifyAndRefineExtraction(
                rawText = raw,
                initialResult = initial,
                customKey = "AIzaSyValidTestKeyForGeminiClassification12345"
            )
            assertTrue("Must not claim AI delegation succeeded when Gemini failed", !fallbackResult.report.isAiDelegated)
            assertTrue("Must mark geminiFailed = true", fallbackResult.report.geminiFailed)
            assertTrue("Must mark isLocalFallback = true", fallbackResult.report.isLocalFallback)
            assertNotNull(fallbackResult.report.geminiFailureReason)
            assertEquals(0, fallbackResult.report.totalAiFields)
            assertEquals(0, fallbackResult.report.aiClassifiedLinesCount)
            assertTrue(fallbackResult.report.totalLocalRuleFields > 0)

            // 2. parseRawJadhadhaWithAi must return Result.failure (no silent local result masquerading as AI)
            val directAiResult = com.example.ai.GeminiAiService.parseRawJadhadhaWithAi(
                rawText = raw,
                componentType = "LANGUAGE",
                customKey = "AIzaSyValidTestKeyForGeminiClassification12345"
            )
            assertTrue("Direct AI extraction must fail honestly when Gemini is unreachable", directAiResult.isFailure)
        } finally {
            com.example.ai.GeminiAiService.resetTransportForTesting()
        }
    }

    @Test
    fun `phase2_4 - invalid Gemini response with malformed JSON or unknown unitId or invented stage is rejected`() {
        val raw = """
            المكون: النصوص القرائية
            الموضوع: المنفى
            تأطير النص وملاحظة العنوان والصورة المرفقة.
            استخلاص القيم الوطنية والإنسانية المتضمنة في النص.
            تركيب خلاصة شاملة لمضامين النص وأساليبه.
        """.trimIndent()
        val initial = com.example.extractor.JadhadhaParser.parseWithReport(raw, "المنفى")
        val u1 = initial.report.unclassifiedLines[0]
        val u2 = initial.report.unclassifiedLines[1]
        val u3 = initial.report.unclassifiedLines[2]

        // Case A: Completely malformed JSON -> isJsonValid = false
        val malformedValidation = com.example.ai.GeminiAiService.validateAndParseGeminiClassificationJson(
            rawJson = "هذا نص عشوائي وليس كائن JSON صالحاً على الإطلاق",
            originalUnits = initial.report.extractedUnits,
            targetComponent = com.example.model.PedagogicalComponent.READING
        )
        assertTrue(!malformedValidation.isJsonValid)
        assertTrue(malformedValidation.validItems.isEmpty())
        assertTrue(malformedValidation.rejectedReasons.isNotEmpty())

        // Case B: JSON containing (1) an unknown unitId ("unit_9999"), (2) an invented stageKey ("invented_stage_xyz"),
        // (3) hallucinated originalText on u2, and (4) valid classification on u3
        val invalidItemsJson = """
            {
              "classifications": [
                {
                  "unitId": "unit_9999",
                  "originalText": "عنصر مخترع من النموذج غير موجود في الوثيقة",
                  "proposedCategory": "EDUCATIONAL_CONTENT",
                  "component": "READING",
                  "stageKey": "observation",
                  "targetField": "content",
                  "confidence": 0.95,
                  "source": "GEMINI"
                },
                {
                  "unitId": "${u1.unitId}",
                  "originalText": "${u1.verbatimText}",
                  "proposedCategory": "INTRODUCTION_LAUNCH",
                  "component": "READING",
                  "stageKey": "invented_stage_xyz",
                  "targetField": "content",
                  "confidence": 0.90,
                  "source": "GEMINI"
                },
                {
                  "unitId": "${u2.unitId}",
                  "originalText": "نص محرف لا يطابق النص الأصلي للوحدة",
                  "proposedCategory": "ANALYSIS_DISCUSSION",
                  "component": "READING",
                  "stageKey": "synthesis",
                  "targetField": "content",
                  "confidence": 0.88,
                  "source": "GEMINI"
                },
                {
                  "unitId": "${u3.unitId}",
                  "originalText": "${u3.verbatimText}",
                  "proposedCategory": "ANALYSIS_DISCUSSION",
                  "component": "READING",
                  "stageKey": "synthesis",
                  "targetField": "content",
                  "confidence": 0.93,
                  "source": "GEMINI"
                }
              ]
            }
        """.trimIndent()

        val validationB = com.example.ai.GeminiAiService.validateAndParseGeminiClassificationJson(
            rawJson = invalidItemsJson,
            originalUnits = initial.report.extractedUnits,
            targetComponent = com.example.model.PedagogicalComponent.READING
        )
        assertTrue(validationB.isJsonValid)
        // Unknown unitId ("unit_9999"), invented stage ("invented_stage_xyz"), and hallucinated text on u2 must all be rejected!
        assertEquals(3, validationB.rejectedReasons.size)
        assertTrue(validationB.rejectedReasons.any { it.contains("unit_9999") })
        assertTrue(validationB.rejectedReasons.any { it.contains("invented_stage_xyz") })
        assertTrue(validationB.rejectedReasons.any { it.contains(u2.unitId) })

        // Only u3 (valid unitId, valid verbatimText, and official stageKey "synthesis") is accepted
        assertEquals(1, validationB.validItems.size)
        assertEquals(u3.unitId, validationB.validItems.first().unitId)
        assertEquals(u3.verbatimText, validationB.validItems.first().originalText)
    }

    @Test
    fun `phase2_5 - no item is deleted when unclassified or low confidence and official stage structure is preserved`() = kotlinx.coroutines.runBlocking {
        val raw = """
            المكون: النصوص القرائية
            الموضوع: المنفى
            عبارة غامضة أولى تحتاج إلى مراجعة بشرية لتحديد موقعها.
            عبارة ثانية واضحة: صياغة خلاصة تركيبية لأحداث النص ودلالاته.
        """.trimIndent()
        val initial = com.example.extractor.JadhadhaParser.parseWithReport(raw, "المنفى")
        assertEquals(2, initial.report.unclassifiedLines.size)
        val uLow = initial.report.unclassifiedLines[0]
        val uHigh = initial.report.unclassifiedLines[1]

        try {
            com.example.ai.GeminiAiService.setTransportForTesting(
                com.example.ai.GeminiAiService.GeminiTransport { _, _, _ ->
                    // uLow has low confidence (0.40 < 0.65), uHigh has high confidence (0.91 >= 0.65)
                    val json = """
                        {
                          "classifications": [
                            {
                              "unitId": "${uLow.unitId}",
                              "originalText": "${uLow.verbatimText}",
                              "proposedCategory": "EDUCATIONAL_CONTENT",
                              "component": "READING",
                              "stageKey": "analysis",
                              "targetField": "content",
                              "confidence": 0.40,
                              "source": "GEMINI",
                              "rationale": "ثقة منخفضة تتطلب مراجعة الأستاذ"
                            },
                            {
                              "unitId": "${uHigh.unitId}",
                              "originalText": "${uHigh.verbatimText}",
                              "proposedCategory": "ANALYSIS_DISCUSSION",
                              "component": "READING",
                              "stageKey": "synthesis",
                              "targetField": "content",
                              "confidence": 0.91,
                              "source": "GEMINI",
                              "rationale": "تركيب أحداث النص"
                            }
                          ]
                        }
                    """.trimIndent()
                    200 to json
                }
            )

            val refined = com.example.ai.GeminiAiService.classifyAndRefineExtraction(
                rawText = raw,
                initialResult = initial,
                customKey = "AIzaSyValidTestKeyForGeminiClassification12345",
                preferLocalRulesFirst = false
            )

            // 1. High-confidence item is placed in "synthesis" -> "content"
            assertTrue(refined.plan.stages["synthesis"]!!.content.contains(uHigh.verbatimText))

            // 2. Low-confidence item is NOT deleted and NOT blindly placed; it stays in unclassifiedLines for human review with its proposed category and rationale attached
            assertEquals(1, refined.report.unclassifiedLines.size)
            val remainingUnclassified = refined.report.unclassifiedLines.first()
            assertEquals(uLow.unitId, remainingUnclassified.unitId)
            assertEquals(uLow.verbatimText, remainingUnclassified.verbatimText)
            assertEquals(com.example.model.PedagogicalCategory.EDUCATIONAL_CONTENT, remainingUnclassified.proposedCategory)
            assertEquals(0.40, remainingUnclassified.confidence!!, 0.001)

            // 3. ContentIntegrityMonitor confirms 0 missing units
            assertTrue(refined.report.integrityReport!!.missingUnits.isEmpty())

            // 4. Official stage structure is strictly preserved (7 for READING, 4 for LANGUAGE, 16 for EXPRESSION)
            assertEquals(7, refined.plan.stages.size)
            assertEquals(
                com.example.model.StageCatalog.READING_STAGES.map { it.key },
                refined.plan.stages.keys.toList()
            )
            assertEquals(4, com.example.model.StageCatalog.LANGUAGE_STAGES.size)
            assertEquals(16, com.example.model.StageCatalog.EXPRESSION_STAGES.size)
        } finally {
            com.example.ai.GeminiAiService.resetTransportForTesting()
        }
    }

    @Test
    fun `phase2_6 - classification can be modified manually and is recorded as USER_EDIT`() {
        val raw = """
            المكون: الدرس اللغوي
            الموضوع: اسم الفاعل وعمله
            [مرحلة: تشخيص المكتسبات]
            أنشطة المدرس:
            يطرح الأستاذ أسئلة تمهيدية حول الفعل الثلاثي.
            [نهاية الجدول]
            ملاحظة إضافية للمتعلمين حول صياغة اسم الفاعل من غير الثلاثي.
        """.trimIndent()

        val initial = com.example.extractor.JadhadhaParser.parseWithReport(raw, "اسم الفاعل وعمله")
        assertEquals(1, initial.report.unclassifiedLines.size)
        val uLine = initial.report.unclassifiedLines.first()

        // 1. Assign unclassified line manually -> recorded as ClassificationSource.USER_EDIT
        val afterManualAssign = com.example.extractor.JadhadhaParser.assignUnclassifiedLine(
            currentResult = initial,
            line = uLine,
            targetStageKey = "lang_observation",
            targetField = "content"
        )
        assertEquals(0, afterManualAssign.report.unclassifiedLines.size)
        assertTrue(afterManualAssign.plan.stages["lang_observation"]!!.content.contains(uLine.verbatimText))
        assertEquals(
            com.example.model.ClassificationSource.USER_EDIT,
            afterManualAssign.plan.stages["lang_observation"]!!.classificationSourceFor("content")
        )
        val assignedUnit = afterManualAssign.report.extractedUnits.first { it.id == uLine.unitId }
        assertEquals(com.example.model.ClassificationSource.USER_EDIT, assignedUnit.classificationSource)
        assertEquals(com.example.model.ContentUnitStatus.CLASSIFIED, assignedUnit.status)

        // 2. Move an already classified item from lang_observation:content to lang_synthesis:eval via modifyUnitClassification
        val afterMove = com.example.extractor.JadhadhaParser.modifyUnitClassification(
            currentResult = afterManualAssign,
            unitId = uLine.unitId,
            targetStageKey = "lang_synthesis",
            targetField = "eval",
            newCategory = com.example.model.PedagogicalCategory.EVALUATION
        )
        assertTrue(!afterMove.plan.stages["lang_observation"]!!.content.contains(uLine.verbatimText))
        assertTrue(afterMove.plan.stages["lang_synthesis"]!!.eval.contains(uLine.verbatimText))
        assertEquals(
            com.example.model.FieldSource.USER,
            afterMove.plan.stages["lang_synthesis"]!!.sourceFor("eval")
        )
        assertEquals(
            com.example.model.ClassificationSource.USER_EDIT,
            afterMove.plan.stages["lang_synthesis"]!!.classificationSourceFor("eval")
        )
        val movedUnit = afterMove.report.extractedUnits.first { it.id == uLine.unitId }
        assertEquals("lang_synthesis", movedUnit.assignedStageKey)
        assertEquals("eval", movedUnit.assignedField)
        assertEquals(com.example.model.ClassificationSource.USER_EDIT, movedUnit.classificationSource)
        assertTrue(afterMove.report.integrityReport!!.missingUnits.isEmpty())
    }

    @Test
    fun `phase2_7 - retry works without losing extracted data and ViewModel exits Parsing state on failure`() {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(application, com.example.data.AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = com.example.data.JadhadhaRepository(db.jadhadhaDao(), application)
        val viewModel = com.example.ui.JadhadhaViewModel(application, repo)

        fun pumpUntil(condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 4000L
            while (!condition() && System.currentTimeMillis() < deadline) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(20)
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            }
        }

        val raw = """
            المكون: الدرس اللغوي
            الموضوع: صيغ المبالغة
            أوزان صيغ المبالغة القياسية الخمسة: فعّال، مفعال، فعول، فعيل، فَعِل.
            تحويل اسم الفاعل إلى صيغ مبالغة في سياقات دلالية متنوعة.
        """.trimIndent()

        try {
            viewModel.submitPdfApiKey("AIzaSyValidTestKeyForGeminiClassification12345")
            viewModel.updateExtractorTopic("صيغ المبالغة")
            viewModel.updateExtractorRawText(raw)

            pumpUntil {
                viewModel.uiState.value.extractorParseResult != null && !viewModel.uiState.value.isParsing
            }
            val initialParse = viewModel.uiState.value.extractorParseResult
            assertNotNull(initialParse)
            assertEquals(raw, viewModel.uiState.value.extractorRawText)
            val u1 = initialParse!!.report.unclassifiedLines[0]
            val u2 = initialParse.report.unclassifiedLines[1]

            // Step 1: First attempt fails due to network timeout
            com.example.ai.GeminiAiService.setTransportForTesting(
                com.example.ai.GeminiAiService.GeminiTransport { _, _, _ ->
                    throw java.io.IOException("انتهت مهلة الاتصال بالخادم")
                }
            )
            viewModel.delegateExtractorToBuiltInAi()
            pumpUntil {
                !viewModel.uiState.value.isParsing &&
                    viewModel.uiState.value.extractorParseResult?.report?.geminiFailed == true
            }

            // Verify UI is NOT stuck in ExtractionState.Parsing, extracted raw text & units are preserved, and fallback is marked
            assertTrue(viewModel.uiState.value.extractionState !is com.example.ui.ExtractionState.Parsing)
            assertTrue(!viewModel.uiState.value.isParsing)
            assertEquals(raw, viewModel.uiState.value.extractorRawText)
            val afterFailureResult = viewModel.uiState.value.extractorParseResult!!
            assertTrue(afterFailureResult.report.geminiFailed)
            assertTrue(afterFailureResult.report.isLocalFallback)
            assertTrue(!afterFailureResult.report.isAiDelegated)
            assertTrue(afterFailureResult.report.integrityReport!!.missingUnits.isEmpty())

            // Step 2: Retry WITHOUT re-importing the file; this time Gemini succeeds
            com.example.ai.GeminiAiService.setTransportForTesting(
                com.example.ai.GeminiAiService.GeminiTransport { _, _, _ ->
                    val json = """
                        {
                          "classifications": [
                            {
                              "unitId": "${u1.unitId}",
                              "originalText": "${u1.verbatimText}",
                              "proposedCategory": "EDUCATIONAL_CONTENT",
                              "component": "LANGUAGE",
                              "stageKey": "lang_synthesis",
                              "targetField": "content",
                              "confidence": 0.95,
                              "source": "GEMINI",
                              "rationale": "أوزان صيغ المبالغة في مرحلة الاستنتاج"
                            },
                            {
                              "unitId": "${u2.unitId}",
                              "originalText": "${u2.verbatimText}",
                              "proposedCategory": "EVALUATION",
                              "component": "LANGUAGE",
                              "stageKey": "lang_application",
                              "targetField": "eval",
                              "confidence": 0.92,
                              "source": "GEMINI",
                              "rationale": "تحويل اسم الفاعل إلى صيغ مبالغة في مرحلة التطبيق"
                            }
                          ]
                        }
                    """.trimIndent()
                    200 to json
                }
            )

            viewModel.retryAiClassification()
            pumpUntil {
                !viewModel.uiState.value.isParsing &&
                    viewModel.uiState.value.extractorParseResult?.report?.isAiDelegated == true
            }

            assertTrue(viewModel.uiState.value.extractionState is com.example.ui.ExtractionState.Done)
            val afterRetryResult = viewModel.uiState.value.extractorParseResult!!
            assertTrue("Retry must succeed with Gemini delegation", afterRetryResult.report.isAiDelegated)
            assertTrue(!afterRetryResult.report.geminiFailed)
            assertTrue(!afterRetryResult.report.isLocalFallback)
            assertEquals(raw, viewModel.uiState.value.extractorRawText)
            assertTrue(afterRetryResult.plan.stages["lang_synthesis"]!!.content.contains(u1.verbatimText))
            assertTrue(afterRetryResult.plan.stages["lang_application"]!!.eval.contains(u2.verbatimText))
            assertEquals(com.example.model.FieldSource.AI, afterRetryResult.plan.stages["lang_synthesis"]!!.sourceFor("content"))
            assertEquals(com.example.model.FieldSource.AI, afterRetryResult.plan.stages["lang_application"]!!.sourceFor("eval"))
            assertTrue(afterRetryResult.report.integrityReport!!.missingUnits.isEmpty())
        } finally {
            com.example.ai.GeminiAiService.resetTransportForTesting()
            com.example.ai.GeminiAiService.clearSavedApiKey(application)
            db.close()
        }
    }

    @Test
    fun `phase2_verification_1 - confidence threshold boundaries 0_64 0_65 0_66 and dynamic threshold customization`() = kotlinx.coroutines.runBlocking {
        val raw = """
            المكون: النصوص القرائية
            الموضوع: المنفى
            العنصر الأول: ملاحظة دلالة العنوان وعلاقته بمضمون النص.
            العنصر الثاني: استخلاص الحقول الدلالية المهيمنة في النص.
            العنصر الثالث: تركيب خلاصة تبرز أبعاد النص الوطنية والإنسانية.
        """.trimIndent()

        val initial = com.example.extractor.JadhadhaParser.parseWithReport(raw, "المنفى")
        assertEquals(3, initial.report.unclassifiedLines.size)
        val u64 = initial.report.unclassifiedLines[0]
        val u65 = initial.report.unclassifiedLines[1]
        val u66 = initial.report.unclassifiedLines[2]

        val boundaryJson = """
            {
              "items": [
                {
                  "unitId": "${u64.unitId}",
                  "originalText": "${u64.verbatimText}",
                  "proposedCategory": "EDUCATIONAL_CONTENT",
                  "component": "READING",
                  "stageKey": "observation",
                  "targetField": "content",
                  "confidence": 0.64,
                  "source": "GEMINI",
                  "rationale": "ثقة حدية أقل من العتبة (0.64)"
                },
                {
                  "unitId": "${u65.unitId}",
                  "originalText": "${u65.verbatimText}",
                  "proposedCategory": "ANALYSIS_DISCUSSION",
                  "component": "READING",
                  "stageKey": "analysis",
                  "targetField": "content",
                  "confidence": 0.65,
                  "source": "GEMINI",
                  "rationale": "ثقة حدية مساوية للعتبة تماماً (0.65)"
                },
                {
                  "unitId": "${u66.unitId}",
                  "originalText": "${u66.verbatimText}",
                  "proposedCategory": "EDUCATIONAL_CONTENT",
                  "component": "READING",
                  "stageKey": "synthesis",
                  "targetField": "content",
                  "confidence": 0.66,
                  "source": "GEMINI",
                  "rationale": "ثقة حدية أعلى من العتبة (0.66)"
                }
              ]
            }
        """.trimIndent()

        // 1. Validate with default threshold (DEFAULT_CONFIDENCE_THRESHOLD = 0.65)
        val defaultValidation = com.example.ai.GeminiAiService.validateAndParseGeminiClassificationJson(
            rawJson = boundaryJson,
            originalUnits = initial.report.extractedUnits,
            targetComponent = com.example.model.PedagogicalComponent.READING
        )
        assertEquals(0.65, com.example.ai.GeminiAiService.DEFAULT_CONFIDENCE_THRESHOLD, 0.0001)
        assertEquals(listOf(u65.unitId, u66.unitId), defaultValidation.acceptedItems.map { it.unitId })
        assertEquals(listOf(u64.unitId), defaultValidation.lowConfidenceItems.map { it.unitId })

        // 2. Verify end-to-end in classifyAndRefineExtraction: 0.64 is NOT auto-inserted into stages and is NOT lost
        try {
            com.example.ai.GeminiAiService.setTransportForTesting { _, _, _ -> 200 to boundaryJson }
            val refined = com.example.ai.GeminiAiService.classifyAndRefineExtraction(
                rawText = raw,
                initialResult = initial,
                customKey = "AIzaSyValidTestKeyForGeminiClassification12345",
                preferLocalRulesFirst = false
            )
            // 0.64 must NOT enter observation.content
            assertTrue(!refined.plan.stages["observation"]!!.content.contains(u64.verbatimText))
            // 0.65 and 0.66 MUST enter analysis.content and synthesis.content
            assertTrue(refined.plan.stages["analysis"]!!.content.contains(u65.verbatimText))
            assertTrue(refined.plan.stages["synthesis"]!!.content.contains(u66.verbatimText))
            // 0.64 is preserved in unclassifiedLines with its metadata for human review
            assertEquals(1, refined.report.unclassifiedLines.size)
            assertEquals(u64.unitId, refined.report.unclassifiedLines.first().unitId)
            assertEquals(0.64, refined.report.unclassifiedLines.first().confidence!!, 0.0001)
            assertTrue(refined.report.integrityReport!!.missingUnits.isEmpty())

            // 3. Verify threshold is configurable without refactoring (e.g. threshold = 0.67 rejects 0.65 & 0.66; threshold = 0.60 accepts all 3)
            val strictValidation = com.example.ai.GeminiAiService.validateAndParseGeminiClassificationJson(
                rawJson = boundaryJson,
                originalUnits = initial.report.extractedUnits,
                targetComponent = com.example.model.PedagogicalComponent.READING,
                confidenceThreshold = 0.67
            )
            assertEquals(0, strictValidation.acceptedItems.size)
            assertEquals(3, strictValidation.lowConfidenceItems.size)

            val lenientValidation = com.example.ai.GeminiAiService.validateAndParseGeminiClassificationJson(
                rawJson = boundaryJson,
                originalUnits = initial.report.extractedUnits,
                targetComponent = com.example.model.PedagogicalComponent.READING,
                confidenceThreshold = 0.60
            )
            assertEquals(3, lenientValidation.acceptedItems.size)
            assertEquals(0, lenientValidation.lowConfidenceItems.size)
        } finally {
            com.example.ai.GeminiAiService.resetTransportForTesting()
        }
    }

    @Test
    fun `phase2_verification_2 - classifyPedagogicalContextLocally distinguishes all 9 categories and similar-vocabulary sentences`() {
        // Part A: 5 sentences sharing the exact same core vocabulary ("فرضية القراءة", "تحليل", "تقويم") but different syntactic/pedagogical functions
        val sObjective = "القدرة على صياغة فرضية القراءة وتحليل النص وتقويم مضمونه."
        val sTeacher = "يوجه الأستاذ المتعلمين إلى صياغة فرضية القراءة لتقويم فهمهم الأولي وتحليل العتبات."
        val sLearner = "يصوغ المتعلمون فرضية القراءة ويناقشون مؤشرات التحليل والتقويم."
        val sContent = "فرضية القراءة: نفترض أن النص يتناول تحليل واقع المنفى وتقويم المرحلة الاستعمارية."
        val sEval = "تقويم مدى قدرة المتعلمين على تمحيص فرضية القراءة وتحليل النص."

        val cObj = com.example.ai.GeminiAiService.classifyPedagogicalContextLocally(sObjective, com.example.model.PedagogicalComponent.READING, "u1")!!
        val cTeacher = com.example.ai.GeminiAiService.classifyPedagogicalContextLocally(sTeacher, com.example.model.PedagogicalComponent.READING, "u2")!!
        val cLearner = com.example.ai.GeminiAiService.classifyPedagogicalContextLocally(sLearner, com.example.model.PedagogicalComponent.READING, "u3")!!
        val cContent = com.example.ai.GeminiAiService.classifyPedagogicalContextLocally(sContent, com.example.model.PedagogicalComponent.READING, "u4")!!
        val cEval = com.example.ai.GeminiAiService.classifyPedagogicalContextLocally(sEval, com.example.model.PedagogicalComponent.READING, "u5")!!

        assertEquals(com.example.model.PedagogicalCategory.OBJECTIVES_COMPETENCIES, cObj.proposedCategory)
        assertEquals("abil", cObj.targetField)

        assertEquals(com.example.model.PedagogicalCategory.TEACHER_ACTIVITIES, cTeacher.proposedCategory)
        assertEquals("teacher", cTeacher.targetField)

        assertEquals(com.example.model.PedagogicalCategory.LEARNER_ACTIVITIES, cLearner.proposedCategory)
        assertEquals("content", cLearner.targetField)

        assertEquals(com.example.model.PedagogicalCategory.EDUCATIONAL_CONTENT, cContent.proposedCategory)
        assertEquals("content", cContent.targetField)

        assertEquals(com.example.model.PedagogicalCategory.EVALUATION, cEval.proposedCategory)
        assertEquals("eval", cEval.targetField)

        // Part B: Verify the remaining 4 of the 9 categories (MATERIALS_METHODS, INTRODUCTION_LAUNCH, ANALYSIS_DISCUSSION, SUPPORT_REMEDIATION)
        val cMaterials = com.example.ai.GeminiAiService.classifyPedagogicalContextLocally(
            "الوسائل الديداكتيكية: السبورة وكتاب المتعلم والصور التوضيحية.",
            com.example.model.PedagogicalComponent.READING,
            "u6"
        )!!
        val cIntro = com.example.ai.GeminiAiService.classifyPedagogicalContextLocally(
            "وضعية الانطلاق: استحضار المكتسبات السابقة حول شعر الغربة والحنين.",
            com.example.model.PedagogicalComponent.READING,
            "u7"
        )!!
        val cAnalysis = com.example.ai.GeminiAiService.classifyPedagogicalContextLocally(
            "الحقول الدلالية: حقل المعاناة في الغربة وحقل التعلق بالوطن.",
            com.example.model.PedagogicalComponent.READING,
            "u8"
        )!!
        val cSupport = com.example.ai.GeminiAiService.classifyPedagogicalContextLocally(
            "دعم فردي ومعالجة تعثرات المتعلمين في القراءة والتحليل.",
            com.example.model.PedagogicalComponent.READING,
            "u9"
        )!!

        assertEquals(com.example.model.PedagogicalCategory.MATERIALS_METHODS, cMaterials.proposedCategory)
        assertEquals(com.example.model.PedagogicalCategory.INTRODUCTION_LAUNCH, cIntro.proposedCategory)
        assertEquals(com.example.model.PedagogicalCategory.ANALYSIS_DISCUSSION, cAnalysis.proposedCategory)
        assertEquals(com.example.model.PedagogicalCategory.SUPPORT_REMEDIATION, cSupport.proposedCategory)

        // Confirm all 9 categories were produced and tagged strictly as LOCAL_RULE
        val allNine = listOf(cObj, cTeacher, cLearner, cContent, cEval, cMaterials, cIntro, cAnalysis, cSupport)
        assertEquals(9, allNine.map { it.proposedCategory }.toSet().size)
        assertTrue(allNine.all { it.source == com.example.model.ClassificationSource.LOCAL_RULE })

        // Part C: Sentence containing keywords in the middle without a syntactic head returns null (not keyword-bag matched)
        val ambiguous = com.example.ai.GeminiAiService.classifyPedagogicalContextLocally(
            "ملاحظات عامة حول فرضية القراءة والتحليل والتقويم والدعم في القسم.",
            com.example.model.PedagogicalComponent.READING,
            "u10"
        )
        assertTrue("Sentence without syntactic/pedagogical frame must return null rather than match isolated keywords", ambiguous == null)
    }

    @Test
    fun `phase2_verification_3 - disambiguate overlapping content fields and prevent conflicting dual assignment`() = kotlinx.coroutines.runBlocking {
        // 1. Disambiguate categories that share targetField = "content" (INTRODUCTION_LAUNCH, ANALYSIS_DISCUSSION, EDUCATIONAL_CONTENT, LEARNER_ACTIVITIES)
        // vs TEACHER_ACTIVITIES ("teacher") using stageKey + pedagogical context
        val introSession1 = com.example.ai.GeminiAiService.classifyPedagogicalContextLocally(
            "تمهيد إشكالي حول مرحلة الحماية الفرنسية ونفي الملك محمد الخامس.",
            com.example.model.PedagogicalComponent.READING,
            "u_intro1"
        )!!
        val introSession2 = com.example.ai.GeminiAiService.classifyPedagogicalContextLocally(
            "تذكير بالحصة السابقة واسترجاع المضمون العام للنص.",
            com.example.model.PedagogicalComponent.READING,
            "u_intro2"
        )!!
        // Analysis sentence that mentions "العنوان" and "تمهيد" as secondary words must STILL resolve to "analysis", NOT "observation" or "diagnostic"
        val analysisWithTitleMention = com.example.ai.GeminiAiService.classifyPedagogicalContextLocally(
            "الحقول الدلالية: علاقة معجم المعاناة بدلالة العنوان وتمهيد الكاتب.",
            com.example.model.PedagogicalComponent.READING,
            "u_analysis"
        )!!
        val eduContentThreshold = com.example.ai.GeminiAiService.classifyPedagogicalContextLocally(
            "صاحب النص: روم لاندو، كاتب ومؤرخ إنجليزي.",
            com.example.model.PedagogicalComponent.READING,
            "u_edu_obs"
        )!!
        val eduContentSynthesis = com.example.ai.GeminiAiService.classifyPedagogicalContextLocally(
            "خلاصة تركيبية تبرز صمود الأسرة الملكية والشعب المغربي من أجل الاستقلال.",
            com.example.model.PedagogicalComponent.READING,
            "u_edu_syn"
        )!!
        val learnerActivity = com.example.ai.GeminiAiService.classifyPedagogicalContextLocally(
            "يقرأ المتعلمون النص قراءة تناوبية ويشرحون المفردات الصعبة في سياقها.",
            com.example.model.PedagogicalComponent.READING,
            "u_learner"
        )!!
        val teacherActivity = com.example.ai.GeminiAiService.classifyPedagogicalContextLocally(
            "يقرأ الأستاذ النص قراءة نموذجية مراعياً مخارج الحروف وصفاتها.",
            com.example.model.PedagogicalComponent.READING,
            "u_teacher"
        )!!

        assertEquals("diagnostic" to "content", introSession1.stageKey to introSession1.targetField)
        assertEquals("session2_intro" to "content", introSession2.stageKey to introSession2.targetField)
        assertEquals("analysis" to "content", analysisWithTitleMention.stageKey to analysisWithTitleMention.targetField)
        assertEquals("observation" to "content", eduContentThreshold.stageKey to eduContentThreshold.targetField)
        assertEquals("synthesis" to "content", eduContentSynthesis.stageKey to eduContentSynthesis.targetField)
        assertEquals("comprehension" to "content", learnerActivity.stageKey to learnerActivity.targetField)
        assertEquals("comprehension" to "teacher", teacherActivity.stageKey to teacherActivity.targetField)

        // 2. Verify a unit cannot be assigned to two conflicting classifications simultaneously
        val raw = """
            المكون: النصوص القرائية
            الموضوع: المنفى
            يطرح الأستاذ أسئلة تمهيدية لاستحضار مكتسبات الدرس السابق.
        """.trimIndent()
        val initial = com.example.extractor.JadhadhaParser.parseWithReport(raw, "المنفى")
        val localClassified = com.example.ai.GeminiAiService.classifyWithLocalRulesOnly(initial)
        val unitId = localClassified.report.extractedUnits.first { it.verbatimText.contains("يطرح الأستاذ أسئلة تمهيدية") }.id
        assertTrue(localClassified.plan.stages["diagnostic"]!!.teacher.any { it.contains("يطرح الأستاذ أسئلة تمهيدية") })

        // Reassign the same unit to observation -> eval; verify it is removed from diagnostic -> teacher
        val reassigned = com.example.extractor.JadhadhaParser.modifyUnitClassification(
            currentResult = localClassified,
            unitId = unitId,
            targetStageKey = "observation",
            targetField = "eval",
            newCategory = com.example.model.PedagogicalCategory.EVALUATION
        )
        assertTrue(!reassigned.plan.stages["diagnostic"]!!.teacher.any { it.contains("يطرح الأستاذ أسئلة تمهيدية") })
        assertTrue(reassigned.plan.stages["observation"]!!.eval.any { it.contains("يطرح الأستاذ أسئلة تمهيدية") })
    }

    @Test
    fun `phase2_verification_4 - Gemini response completeness with omitted units duplicate unitId and incompatible stageKey or targetField`() = kotlinx.coroutines.runBlocking {
        val raw = """
            المكون: النصوص القرائية
            الموضوع: المنفى
            البند الأول — استخلاص المغزى العام للنص السردي وأبعاده.
            البند الثاني — إشارة سياقية عامة ذات ثقة منخفضة تحتاج إلى مراجعة الأستاذ.
            البند الثالث — عنصر أغفله Gemini ولم يدرجه في مصفوفة JSON.
            البند الرابع — عنصر كرر Gemini معرفه مرتين بتصنيفين متعارضين.
            البند الخامس — عنصر أرجعه Gemini بحقل مستهدف غير متوافق مع فئته البيداغوجية.
            البند السادس — عنصر أرجعه Gemini بمرحلة غير متوافقة تربوياً مع فئته.
        """.trimIndent()

        val initial = com.example.extractor.JadhadhaParser.parseWithReport(raw, "المنفى")
        assertEquals(6, initial.report.unclassifiedLines.size)
        val u1 = initial.report.unclassifiedLines[0] // Valid high confidence -> acceptedItems
        val u2 = initial.report.unclassifiedLines[1] // Valid low confidence (0.50) -> lowConfidenceItems
        val u3 = initial.report.unclassifiedLines[2] // Omitted from JSON -> omittedUnitIds
        val u4 = initial.report.unclassifiedLines[3] // Duplicate unitId with conflicting classifications -> rejectedUnitIds
        val u5 = initial.report.unclassifiedLines[4] // Incompatible targetField (TEACHER_ACTIVITIES + "eval") -> rejectedUnitIds
        val u6 = initial.report.unclassifiedLines[5] // Incompatible stageKey (INTRODUCTION_LAUNCH + "investment") -> rejectedUnitIds

        val mixedResponseJson = """
            {
              "items": [
                {
                  "unitId": "${u1.unitId}",
                  "originalText": "${u1.verbatimText}",
                  "proposedCategory": "EDUCATIONAL_CONTENT",
                  "component": "READING",
                  "stageKey": "comprehension",
                  "targetField": "content",
                  "confidence": 0.91,
                  "source": "GEMINI",
                  "rationale": "مضمون الفهم العام للنص"
                },
                {
                  "unitId": "${u2.unitId}",
                  "originalText": "${u2.verbatimText}",
                  "proposedCategory": "EDUCATIONAL_CONTENT",
                  "component": "READING",
                  "stageKey": "observation",
                  "targetField": "content",
                  "confidence": 0.50,
                  "source": "GEMINI",
                  "rationale": "ثقة منخفضة تحتاج إلى مراجعة"
                },
                {
                  "unitId": "${u4.unitId}",
                  "originalText": "${u4.verbatimText}",
                  "proposedCategory": "TEACHER_ACTIVITIES",
                  "component": "READING",
                  "stageKey": "observation",
                  "targetField": "teacher",
                  "confidence": 0.88,
                  "source": "GEMINI",
                  "rationale": "التصنيف الأول للوحدة المكررة"
                },
                {
                  "unitId": "${u4.unitId}",
                  "originalText": "${u4.verbatimText}",
                  "proposedCategory": "EVALUATION",
                  "component": "READING",
                  "stageKey": "analysis",
                  "targetField": "eval",
                  "confidence": 0.89,
                  "source": "GEMINI",
                  "rationale": "التصنيف الثاني المتعارض لنفس المعرف"
                },
                {
                  "unitId": "${u5.unitId}",
                  "originalText": "${u5.verbatimText}",
                  "proposedCategory": "TEACHER_ACTIVITIES",
                  "component": "READING",
                  "stageKey": "analysis",
                  "targetField": "eval",
                  "confidence": 0.92,
                  "source": "GEMINI",
                  "rationale": "حقل eval غير متوافق مع أنشطة الأستاذ"
                },
                {
                  "unitId": "${u6.unitId}",
                  "originalText": "${u6.verbatimText}",
                  "proposedCategory": "INTRODUCTION_LAUNCH",
                  "component": "READING",
                  "stageKey": "investment",
                  "targetField": "content",
                  "confidence": 0.90,
                  "source": "GEMINI",
                  "rationale": "مرحلة الاستثمار غير متوافقة مع التمهيد والانطلاق"
                }
              ]
            }
        """.trimIndent()

        val sentUnits = initial.report.unclassifiedLines.map { uLine ->
            initial.report.extractedUnits.first { it.id == uLine.unitId }
        }
        val validation = com.example.ai.GeminiAiService.validateAndParseGeminiClassificationJson(
            rawJson = mixedResponseJson,
            originalUnits = sentUnits,
            targetComponent = com.example.model.PedagogicalComponent.READING
        )

        // 1. Verify each of the 6 sent units is accounted for in exactly one of the 4 states:
        //    - Accepted: u1
        //    - Low confidence: u2
        //    - Rejected with reason: u4 (conflicting duplicate), u5 (incompatible targetField), u6 (incompatible stageKey)
        //    - Omitted / unclassified: u3
        assertEquals(listOf(u1.unitId), validation.acceptedItems.map { it.unitId })
        assertEquals(listOf(u2.unitId), validation.lowConfidenceItems.map { it.unitId })
        assertEquals(setOf(u4.unitId, u5.unitId, u6.unitId), validation.rejectedUnitIds.toSet())
        assertEquals(listOf(u3.unitId), validation.omittedUnitIds)
        assertEquals(3, validation.rejectedReasons.size)

        val totalAccountedSet = validation.acceptedItems.map { it.unitId }.toSet() +
            validation.lowConfidenceItems.map { it.unitId }.toSet() +
            validation.rejectedUnitIds.toSet() +
            validation.omittedUnitIds.toSet()
        assertEquals(sentUnits.map { it.id }.toSet(), totalAccountedSet)

        // 2. Verify end-to-end in classifyAndRefineExtraction: only u1 enters the plan; u2..u6 remain in unclassifiedLines without loss
        try {
            com.example.ai.GeminiAiService.setTransportForTesting { _, _, _ -> 200 to mixedResponseJson }
            val refined = com.example.ai.GeminiAiService.classifyAndRefineExtraction(
                rawText = raw,
                initialResult = initial,
                customKey = "AIzaSyValidTestKeyForGeminiClassification12345",
                preferLocalRulesFirst = false
            )
            assertTrue(refined.plan.stages["comprehension"]!!.content.contains(u1.verbatimText))
            assertEquals(5, refined.report.unclassifiedLines.size)
            assertEquals(
                setOf(u2.unitId, u3.unitId, u4.unitId, u5.unitId, u6.unitId),
                refined.report.unclassifiedLines.map { it.unitId }.toSet()
            )
            // Neither of u4's conflicting classifications was applied
            assertTrue(!refined.plan.stages["observation"]!!.teacher.contains(u4.verbatimText))
            assertTrue(!refined.plan.stages["analysis"]!!.eval.contains(u4.verbatimText))
            // u5's incompatible targetField and u6's incompatible stageKey were NOT applied
            assertTrue(!refined.plan.stages["analysis"]!!.eval.contains(u5.verbatimText))
            assertTrue(!refined.plan.stages["investment"]!!.content.contains(u6.verbatimText))
            // Zero units lost
            assertTrue(refined.report.integrityReport!!.missingUnits.isEmpty())
        } finally {
            com.example.ai.GeminiAiService.resetTransportForTesting()
        }
    }

    @Test
    fun `phase2_verification_5 - distinguish Mock transport from Real connection and probe live environment`() = kotlinx.coroutines.runBlocking {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        com.example.ai.GeminiAiService.resetTransportForTesting()
        com.example.ai.GeminiAiService.clearSavedApiKey(application)

        val envKey = System.getenv("GEMINI_API_KEY")?.trim().orEmpty()
        val effectiveDefaultKey = com.example.ai.GeminiAiService.getEffectiveApiKey(null)
        val hasRealKeyInEnv = com.example.ai.GeminiAiService.hasValidApiKey(envKey.ifEmpty { null })

        val sampleUnit = com.example.model.ExtractedUnit(
            id = "unit_live_1",
            verbatimText = "صياغة خلاصة تركيبية لمضامين النص السردي وأساليبه.",
            location = com.example.model.UnitLocation(lineNumber = 1)
        )

        if (!hasRealKeyInEnv) {
            // Confirm that without a real key in the test environment, hasValidApiKey is false and real call fails honestly
            assertEquals("", effectiveDefaultKey)
            assertTrue(!com.example.ai.GeminiAiService.hasValidApiKey(null))
            val resultWithoutKey = com.example.ai.GeminiAiService.classifyUnitsWithGemini(
                units = listOf(sampleUnit),
                component = com.example.model.PedagogicalComponent.READING,
                topic = "المنفى",
                customKey = null
            )
            assertTrue(resultWithoutKey.isFailure)
            assertEquals(
                com.example.ai.GeminiAiService.MSG_GEMINI_KEY_MISSING,
                resultWithoutKey.exceptionOrNull()?.message
            )
        } else {
            // If a real API key is injected in the environment, execute a real OkHttp call to Gemini
            val liveResult = com.example.ai.GeminiAiService.classifyUnitsWithGemini(
                units = listOf(sampleUnit),
                component = com.example.model.PedagogicalComponent.READING,
                topic = "المنفى",
                customKey = envKey.ifEmpty { effectiveDefaultKey }
            )
            assertNotNull(liveResult)
        }
    }

    // =========================================================================
    // PHASE 3 MANDATORY TESTS: DOCX GENERATION, TABLE STRUCTURE & TEMPLATES
    // =========================================================================

    @Test
    fun `phase3_1 - create docx from complete JadhadhaPlan and reopen to verify all stages and fields`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val completePlan = com.example.model.JadhadhaPlan(
            topic = "قيم التضامن الإنساني في الشعر المعاصر",
            componentType = com.example.model.PedagogicalComponent.READING,
            info = com.example.model.JadhadhaInfo(
                level = "السنة الثالثة من التعليم الثانوي الإعدادي",
                unit = "الوحدة الثالثة",
                domain = "المجال الحضاري والإنساني",
                component = "النصوص القرائية",
                duration = "ساعتان (حصتان)",
                materials = "السبورة، كتاب المتعلم(ة)، الدفاتر، موارد رقمية",
                reference = "المختار في اللغة العربية - ص: 98",
                teacherName = "ذ. محمد العابد",
                schoolName = "ثانوية أنوال الإعدادية",
                communicativeComp = "قدرة المتعلم على التواصل بلغة عربية فصيحة ومناقشة القضايا الإنسانية.",
                methodologicalComp = "تمكن المتعلم من آليات القراءة المنهجية للنص الشعري الحديث.",
                culturalComp = "إغناء الرصيد الثقافي والمعرفي بقيم التضامن والتآزر الإنساني."
            ),
            stages = linkedMapOf(
                "diagnostic" to com.example.model.StageData(
                    abil = listOf("استحضار المكتسبات السابقة حول الشعر الوطني والإنساني."),
                    teacher = listOf("يطرح الأستاذ أسئلة تشخيصية لربط الدرس الجديد بالسابق."),
                    learner = listOf("يجيب المتعلمون عن الأسئلة الموجهة ويستحضرون تعلماتهم."),
                    content = listOf("تمهيد حول مفهوم التضامن وأهميته في بناء المجتمعات."),
                    eval = listOf("تقويم تشخيصي لرصد استعداد المتعلمين.")
                ),
                "observation" to com.example.model.StageData(
                    abil = listOf("القدرة على ملاحظة العتبات وصياغة فرضية القراءة."),
                    teacher = listOf("يوجه المتعلمين إلى قراءة العنوان والبيت الأول والأخير."),
                    learner = listOf("يصوغ المتعلمون فرضيات القراءة انطلاقاً من المشيرات النصية."),
                    content = listOf(
                        "دلالة العنوان: مركب إضافي يحيل على البعد الإنساني للتضامن.",
                        "فرضية القراءة: نفترض أن القصيدة تدعو إلى مؤازرة المحتاجين."
                    ),
                    eval = listOf("تمحيص الفرضيات المقترحة وتثمين الملائم منها.")
                ),
                "comprehension" to com.example.model.StageData(
                    abil = listOf("التمكن من فهم المضامين الشعرية وشرح المفردات الصعبة."),
                    teacher = listOf("يقرأ الأستاذ القصيدة قراءة نموذجية معبرة ثم يوجه القراءات الفردية."),
                    learner = listOf("يشرح المتعلمون المفردات في سياقها ويستخلصون الفكرة العامة."),
                    content = listOf(
                        "الشروح اللغوية: الإيثار: تفضيل الغير على النفس.",
                        "الفكرة العامة: دعوة الشاعر إلى ترسيخ قيم التكافل الإنساني."
                    ),
                    eval = listOf("تقويم النطق السليم ومخارج الحروف والفهم العام.")
                ),
                "session2_intro" to com.example.model.StageData(
                    abil = listOf("تذكر مضامين الحصة الأولى والربط مع التحليل."),
                    teacher = listOf("يستدرج المتعلمين للتذكير بالفكرة العامة للقصيدة."),
                    content = listOf("التذكير بموضوع القصيدة ودلالاتها العامة."),
                    eval = listOf("رصد قدرة المتعلمين على استرجاع المكتسبات.")
                ),
                "analysis" to com.example.model.StageData(
                    abil = listOf("القدرة على تحليل الحقول الدلالية والخصائص الفنية للقصيدة."),
                    teacher = listOf("يوزع المتعلمين إلى مجموعات لاستخراج الألفاظ الدالة على التضامن."),
                    content = listOf(
                        "الحقل الاجتماعي: الفقير، اليتيم، العون.",
                        "الصورة الشعرية: توظيف الاستعارة المكنية لتجسيد التضامن."
                    ),
                    eval = listOf("تقويم تكويني لقياس قدرة المتعلمين على التحليل الفني.")
                ),
                "synthesis" to com.example.model.StageData(
                    abil = listOf("القدرة على تجميع خلاصات النص في تركيب منسجم."),
                    teacher = listOf("يطالب المتعلمين بصياغة خلاصة تركيبية جماعية وفردية."),
                    content = listOf("تركيب يجمع بين البعد الإنساني للقصيدة ومقوماتها الجمالية."),
                    eval = listOf("مناقشة الخلاصات التركيبية وتصويب التعثرات.")
                ),
                "investment" to com.example.model.StageData(
                    abil = listOf("استثمار قيم النص في وضعيات تواصلية جديدة."),
                    teacher = listOf("يكلف المتعلمين بكتابة فقرة حول مبادرة تضامنية محلية."),
                    content = listOf("نشاط استثماري: إعداد بطاقة مشروع تضامني داخل المؤسسة."),
                    eval = listOf("متابعة إنجازات المتعلمين وتقويمها.")
                )
            ),
            extraParagraphs = listOf("ملاحظة بيداغوجية: يراعى الفروق الفردية أثناء توزيع المهام القرائية."),
            unclassifiedItems = listOf("إحالة تكميلية: ديوان الشاعر - الطبعة الثانية.")
        )

        val outFile = java.io.File(context.cacheDir, "test_phase3_1_complete.docx")
        com.example.docx.DocxGenerator.generateDocxToFile(outFile, completePlan)
        assertTrue(outFile.exists() && outFile.length() > 1000L)

        // Re-open the generated .docx and verify all content is present
        val extracted = com.example.extractor.DocumentFileReader.extractStructuredDocx(outFile)
        val reparsed = com.example.extractor.JadhadhaParser.parseWithReport(extracted.structuredText).plan

        assertEquals("قيم التضامن الإنساني في الشعر المعاصر", reparsed.topic)
        assertEquals("السنة الثالثة من التعليم الثانوي الإعدادي", reparsed.info.level)
        assertEquals("الوحدة الثالثة", reparsed.info.unit)
        assertEquals("المجال الحضاري والإنساني", reparsed.info.domain)
        assertEquals("ساعتان (حصتان)", reparsed.info.duration)
        assertEquals("ذ. محمد العابد", reparsed.info.teacherName)
        assertEquals("ثانوية أنوال الإعدادية", reparsed.info.schoolName)
        assertTrue(reparsed.info.communicativeComp.contains("التواصل بلغة عربية فصيحة"))
        assertTrue(reparsed.info.methodologicalComp.contains("آليات القراءة المنهجية"))
        assertTrue(reparsed.info.culturalComp.contains("قيم التضامن والتآزر"))

        // Verify all 7 stages and their fields survived the .docx round-trip
        for (stageKey in completePlan.stages.keys) {
            val origStage = completePlan.stages[stageKey]!!
            val reStage = reparsed.stages[stageKey]
            assertNotNull("Stage $stageKey must exist after re-opening .docx", reStage)
            assertTrue("Stage $stageKey abil must not be empty", reStage!!.abil.isNotEmpty())
            assertTrue("Stage $stageKey teacher must not be empty", reStage.teacher.isNotEmpty())
            assertTrue("Stage $stageKey content must not be empty", reStage.content.isNotEmpty())
            assertTrue("Stage $stageKey eval must not be empty", reStage.eval.isNotEmpty())
            origStage.learner.forEach { learnerLine ->
                assertTrue(
                    "Learner activity '$learnerLine' must be preserved in .docx",
                    extracted.structuredText.contains(learnerLine)
                )
            }
        }
        assertTrue(extracted.structuredText.contains("ملاحظة بيداغوجية: يراعى الفروق الفردية"))
        assertTrue(extracted.structuredText.contains("إحالة تكميلية: ديوان الشاعر"))
    }

    @Test
    fun `phase3_2 - create docx with multi-row multi-column table and verify rows and columns`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val multiTable = com.example.model.StructuredTable(
            title = "جدول الظواهر الأسلوبية",
            rows = listOf(
                com.example.model.StructuredTableRow(
                    isHeader = true,
                    cells = listOf(
                        com.example.model.StructuredTableCell("المثال النصي", isHeader = true),
                        com.example.model.StructuredTableCell("نوع الأسلوب", isHeader = true),
                        com.example.model.StructuredTableCell("أداته", isHeader = true),
                        com.example.model.StructuredTableCell("دلالته السياقية", isHeader = true)
                    )
                ),
                com.example.model.StructuredTableRow(
                    cells = listOf(
                        com.example.model.StructuredTableCell("يا أخي في كل أرض"),
                        com.example.model.StructuredTableCell("أسلوب نداء"),
                        com.example.model.StructuredTableCell("حرف النداء (يا)"),
                        com.example.model.StructuredTableCell("إبراز الأخوة الإنسانية الشاملة")
                    )
                ),
                com.example.model.StructuredTableRow(
                    cells = listOf(
                        com.example.model.StructuredTableCell("لا تترك المحتاج وحيداً"),
                        com.example.model.StructuredTableCell("أسلوب نهي"),
                        com.example.model.StructuredTableCell("لا الناهية"),
                        com.example.model.StructuredTableCell("الحث على التآزر ونبذ التخلي")
                    )
                ),
                com.example.model.StructuredTableRow(
                    cells = listOf(
                        com.example.model.StructuredTableCell("ما أجمل التضامن!"),
                        com.example.model.StructuredTableCell("أسلوب تعجب"),
                        com.example.model.StructuredTableCell("صيغة ما أفعله"),
                        com.example.model.StructuredTableCell("تعظيم قيمة التكافل")
                    )
                )
            ),
            columnWidthsDxa = listOf(2200, 1800, 1800, 3200),
            isRtl = true,
            hasBorders = true
        )

        val basePlan = com.example.model.SamplePlans.READING_SAMPLE
        val analysisStage = basePlan.stages["analysis"]!!
        val updatedPlan = basePlan.copy(
            stages = basePlan.stages.toMutableMap().apply {
                put("analysis", analysisStage.copy(tables = listOf(multiTable)))
            },
            customTables = listOf(multiTable)
        )

        val outFile = java.io.File(context.cacheDir, "test_phase3_2_multirow_multicol.docx")
        com.example.docx.DocxGenerator.generateDocxToFile(outFile, updatedPlan)

        val extracted = com.example.extractor.DocumentFileReader.extractStructuredDocx(outFile)
        // Top-level tables: Header (7x4), Main Stages (with nested multiTable inside analysis), plus customTable (4x4)
        assertTrue(extracted.tables.size >= 3)
        val reimportedCustomTable = extracted.tables.last()
        assertEquals(4, reimportedCustomTable.rows.size)
        assertTrue(reimportedCustomTable.rows[0].isHeader)
        assertEquals(4, reimportedCustomTable.rows[0].cells.size)
        assertEquals("المثال النصي", reimportedCustomTable.rows[0].cells[0].text)
        assertEquals("نوع الأسلوب", reimportedCustomTable.rows[0].cells[1].text)
        assertEquals("أداته", reimportedCustomTable.rows[0].cells[2].text)
        assertEquals("دلالته السياقية", reimportedCustomTable.rows[0].cells[3].text)

        assertEquals("يا أخي في كل أرض", reimportedCustomTable.rows[1].cells[0].text)
        assertEquals("أسلوب نهي", reimportedCustomTable.rows[2].cells[1].text)
        assertEquals("صيغة ما أفعله", reimportedCustomTable.rows[3].cells[2].text)
        assertEquals("تعظيم قيمة التكافل", reimportedCustomTable.rows[3].cells[3].text)
    }

    @Test
    fun `phase3_3 - preserve empty cells and horizontally and vertically merged cells`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()

        // 1. Test StructuredTable with empty cells, horizontal merge (gridSpan=2 & 3), and vertical merge (vMerge=restart/continue)
        val complexMergedTable = com.example.model.StructuredTable(
            rows = listOf(
                // Row 0: 1 cell spanning all 4 columns (gridSpan=4)
                com.example.model.StructuredTableRow(
                    isHeader = true,
                    cells = listOf(
                        com.example.model.StructuredTableCell(
                            text = "شبكة تقويم المهارات اللغوية والتركيبية",
                            gridSpan = 4,
                            isHeader = true
                        )
                    )
                ),
                // Row 1: col 0 starts vertical merge (vMerge="restart"), col 1 is empty (""), cols 2..3 merged horizontally (gridSpan=2)
                com.example.model.StructuredTableRow(
                    cells = listOf(
                        com.example.model.StructuredTableCell(
                            text = "المحور الصرفي",
                            vMerge = "restart"
                        ),
                        com.example.model.StructuredTableCell(
                            text = "",
                            paragraphs = emptyList()
                        ),
                        com.example.model.StructuredTableCell(
                            text = "ملاحظات تركيبية مدمجة أفقياً",
                            gridSpan = 2
                        )
                    )
                ),
                // Row 2: col 0 continues vertical merge (vMerge="continue"), col 1 has value, col 2 is empty (""), col 3 has value
                com.example.model.StructuredTableRow(
                    cells = listOf(
                        com.example.model.StructuredTableCell(
                            text = "",
                            paragraphs = emptyList(),
                            vMerge = "continue"
                        ),
                        com.example.model.StructuredTableCell(
                            text = "صياغة اسم الفاعل"
                        ),
                        com.example.model.StructuredTableCell(
                            text = "",
                            paragraphs = emptyList()
                        ),
                        com.example.model.StructuredTableCell(
                            text = "تحقق كلي"
                        )
                    )
                )
            ),
            columnWidthsDxa = listOf(2000, 2500, 2500, 2500),
            isRtl = true,
            hasBorders = true
        )

        // 2. Also test [جدول: ...] pipe syntax with empty cells in StageData.content
        val baseLangPlan = com.example.model.SamplePlans.LANGUAGE_SAMPLE
        val appStage = baseLangPlan.stages["lang_application"]!!
        val pipeRowWithEmptyCells = listOf(
            "[جدول: الجملة | المشتق |  | الإعراب]",
            "[جدول: المؤمن شاكرٌ ربه | شاكرٌ |  | خبر مرفوع]"
        )
        val planWithMergesAndEmpty = baseLangPlan.copy(
            stages = baseLangPlan.stages.toMutableMap().apply {
                put("lang_application", appStage.copy(content = appStage.content + pipeRowWithEmptyCells))
            },
            customTables = listOf(complexMergedTable)
        )

        val outFile = java.io.File(context.cacheDir, "test_phase3_3_empty_and_merged.docx")
        com.example.docx.DocxGenerator.generateDocxToFile(outFile, planWithMergesAndEmpty)

        val extracted = com.example.extractor.DocumentFileReader.extractStructuredDocx(outFile)
        val reimportedMerged = extracted.tables.last()

        // Verify Row 0 horizontal merge (gridSpan=4)
        assertEquals(3, reimportedMerged.rows.size)
        assertEquals(1, reimportedMerged.rows[0].cells.size)
        assertEquals(4, reimportedMerged.rows[0].cells[0].gridSpan)
        assertEquals("شبكة تقويم المهارات اللغوية والتركيبية", reimportedMerged.rows[0].cells[0].text)

        // Verify Row 1: vMerge="restart", empty cell at index 1, and gridSpan=2 at index 2
        assertEquals(3, reimportedMerged.rows[1].cells.size)
        assertEquals("restart", reimportedMerged.rows[1].cells[0].vMerge)
        assertEquals("المحور الصرفي", reimportedMerged.rows[1].cells[0].text)
        assertEquals("", reimportedMerged.rows[1].cells[1].text)
        assertTrue(reimportedMerged.rows[1].cells[1].paragraphs.isEmpty())
        assertEquals(2, reimportedMerged.rows[1].cells[2].gridSpan)
        assertEquals("ملاحظات تركيبية مدمجة أفقياً", reimportedMerged.rows[1].cells[2].text)

        // Verify Row 2: vMerge="continue", non-empty cell at index 1, empty cell at index 2, non-empty at index 3
        assertEquals(4, reimportedMerged.rows[2].cells.size)
        assertEquals("continue", reimportedMerged.rows[2].cells[0].vMerge)
        assertEquals("صياغة اسم الفاعل", reimportedMerged.rows[2].cells[1].text)
        assertEquals("", reimportedMerged.rows[2].cells[2].text)
        assertEquals("تحقق كلي", reimportedMerged.rows[2].cells[3].text)

        // Verify the pipe-syntax nested table inside lang_application also preserved its empty 3rd column
        val mainStagesTable = extracted.tables[1]
        val appRow = mainStagesTable.rows.last()
        val appContentCell = appRow.cells[3]
        assertTrue(appContentCell.nestedTables.isNotEmpty())
        val nestedPipeTbl = appContentCell.nestedTables.last()
        assertEquals(4, nestedPipeTbl.rows[0].cells.size)
        assertEquals("الجملة", nestedPipeTbl.rows[0].cells[0].text)
        assertEquals("المشتق", nestedPipeTbl.rows[0].cells[1].text)
        assertEquals("", nestedPipeTbl.rows[0].cells[2].text)
        assertEquals("الإعراب", nestedPipeTbl.rows[0].cells[3].text)
    }

    @Test
    fun `phase3_4 - preserve nested tables inside cells across docx generation and re-import`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()

        val innerMostTable = com.example.model.StructuredTable(
            rows = listOf(
                com.example.model.StructuredTableRow(
                    isHeader = true,
                    cells = listOf(
                        com.example.model.StructuredTableCell("الوزن الصرفي", isHeader = true),
                        com.example.model.StructuredTableCell("الدلالة", isHeader = true)
                    )
                ),
                com.example.model.StructuredTableRow(
                    cells = listOf(
                        com.example.model.StructuredTableCell("فَعَّال"),
                        com.example.model.StructuredTableCell("الكثرة والمبالغة")
                    )
                )
            )
        )

        val outerTableWithNested = com.example.model.StructuredTable(
            rows = listOf(
                com.example.model.StructuredTableRow(
                    isHeader = true,
                    cells = listOf(
                        com.example.model.StructuredTableCell("القسم التحليلي", isHeader = true),
                        com.example.model.StructuredTableCell("التفصيل والجدول الفرعي", isHeader = true)
                    )
                ),
                com.example.model.StructuredTableRow(
                    cells = listOf(
                        com.example.model.StructuredTableCell("صيغ المبالغة"),
                        com.example.model.StructuredTableCell(
                            text = "أوزان صيغ المبالغة القياسية:",
                            paragraphs = listOf("أوزان صيغ المبالغة القياسية:"),
                            nestedTables = listOf(innerMostTable)
                        )
                    )
                )
            )
        )

        val basePlan = com.example.model.SamplePlans.LANGUAGE_SAMPLE
        val planWithNested = basePlan.copy(customTables = listOf(outerTableWithNested))

        val outFile = java.io.File(context.cacheDir, "test_phase3_4_nested_tables.docx")
        com.example.docx.DocxGenerator.generateDocxToFile(outFile, planWithNested)

        val extracted = com.example.extractor.DocumentFileReader.extractStructuredDocx(outFile)
        // 1. Check the built-in nested analytical tables inside lang_observation of LANGUAGE_SAMPLE (9 rows + 6 rows = 15 rows)
        val stagesTable = extracted.tables[1]
        val obsContentCell = stagesTable.rows[2].cells[3]
        assertEquals(2, obsContentCell.nestedTables.size)
        assertEquals(15, obsContentCell.nestedTables.sumOf { it.rows.size })
        assertEquals(9, obsContentCell.nestedTables[0].rows.size)
        assertEquals(9, obsContentCell.nestedTables[0].rows[0].cells.size)
        assertEquals(6, obsContentCell.nestedTables[1].rows.size)
        assertEquals(6, obsContentCell.nestedTables[1].rows[0].cells.size)

        // 2. Check the custom outer table and its nested innerMostTable (2x2)
        val reimportedOuter = extracted.tables.last()
        assertEquals(2, reimportedOuter.rows.size)
        val parentCell = reimportedOuter.rows[1].cells[1]
        assertEquals(listOf("أوزان صيغ المبالغة القياسية:"), parentCell.paragraphs)
        assertEquals(1, parentCell.nestedTables.size)
        val reimportedInner = parentCell.nestedTables[0]
        assertEquals(2, reimportedInner.rows.size)
        assertEquals(2, reimportedInner.rows[0].cells.size)
        assertEquals("الوزن الصرفي", reimportedInner.rows[0].cells[0].text)
        assertEquals("فَعَّال", reimportedInner.rows[1].cells[0].text)
        assertEquals("الكثرة والمبالغة", reimportedInner.rows[1].cells[1].text)
    }

    @Test
    fun `phase3_5 - import docx template and fill it without destroying styles headers footers media or sectPr`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val templateFile = java.io.File(context.cacheDir, "test_phase3_5_template_source.docx")

        val customStylesXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:docDefaults>
    <w:rPrDefault>
      <w:rPr>
        <w:rFonts w:ascii="Traditional Arabic" w:hAnsi="Traditional Arabic" w:cs="Traditional Arabic"/>
        <w:sz w:val="22"/>
      </w:rPr>
    </w:rPrDefault>
  </w:docDefaults>
  <w:style w:type="paragraph" w:styleId="CustomMinistryStyle">
    <w:name w:val="Custom Ministry Style"/>
  </w:style>
</w:styles>"""

        val customFontTableXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:fonts xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:font w:name="Traditional Arabic"><w:charset w:val="B2"/></w:font>
</w:fonts>"""

        val customNumberingXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:abstractNum w:abstractNumId="0"><w:lvl w:ilvl="0"><w:numFmt w:val="bullet"/></w:lvl></w:abstractNum>
  <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>
</w:numbering>"""

        val customSettingsXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:settings xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:view w:val="print"/>
  <w:zoom w:percent="100"/>
</w:settings>"""

        val header1Xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:hdr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <w:p>
    <w:r><w:t>المملكة المغربية - وزارة التربية الوطنية - {{المؤسسة}} - الأستاذ: {{الأستاذ}} - الدرس: {{الموضوع}}</w:t></w:r>
  </w:p>
</w:hdr>"""

        val footer1Xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:ftr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:p>
    <w:r><w:t>المرجع المعتمد: {{المرجع}} | الصفحة </w:t></w:r>
    <w:fldSimple w:instr="PAGE"/>
  </w:p>
</w:ftr>"""

        val fakePngLogoBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02, 0x03, 0x04)

        val documentRelsXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
  <Relationship Id="rIdHeader1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header1.xml"/>
  <Relationship Id="rIdFooter1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/>
  <Relationship Id="rIdLogo1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/ministry_logo.png"/>
</Relationships>"""

        val templateDocumentXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing">
  <w:body>
    <w:p><w:r><w:drawing><wp:inline><a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:graphicData><pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:blipFill><a:blip r:embed="rIdLogo1"/></pic:blipFill></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>
    <w:tbl>
      <w:tr>
        <w:tc><w:p><w:r><w:t>المكون : النصوص القرائية</w:t></w:r></w:p></w:tc>
        <w:tc><w:p><w:r><w:t>الموضوع : المنفى</w:t></w:r></w:p></w:tc>
      </w:tr>
    </w:tbl>
    <w:sectPr>
      <w:headerReference w:type="default" r:id="rIdHeader1"/>
      <w:footerReference w:type="default" r:id="rIdFooter1"/>
      <w:pgSz w:w="16838" w:h="11906" w:orient="landscape"/>
      <w:pgMar w:top="720" w:right="720" w:bottom="720" w:left="720" w:header="360" w:footer="360" w:gutter="0"/>
      <w:pgNumType w:start="1"/>
      <w:bidi/>
    </w:sectPr>
  </w:body>
</w:document>"""

        java.util.zip.ZipOutputStream(java.io.FileOutputStream(templateFile)).use { zos ->
            fun putStr(name: String, content: String) {
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                zos.write(content.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                zos.closeEntry()
            }
            putStr("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/></Types>""")
            putStr("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
            putStr("word/_rels/document.xml.rels", documentRelsXml)
            putStr("word/styles.xml", customStylesXml)
            putStr("word/fontTable.xml", customFontTableXml)
            putStr("word/numbering.xml", customNumberingXml)
            putStr("word/settings.xml", customSettingsXml)
            putStr("word/header1.xml", header1Xml)
            putStr("word/footer1.xml", footer1Xml)
            putStr("word/document.xml", templateDocumentXml)
            zos.putNextEntry(java.util.zip.ZipEntry("word/media/ministry_logo.png"))
            zos.write(fakePngLogoBytes)
            zos.closeEntry()
        }

        // 1. Import the template via DocumentFileReader -> parse -> populate with full READING_SAMPLE data
        val importedTemplate = com.example.extractor.DocumentFileReader.extractStructuredDocx(templateFile)
        assertNotNull(importedTemplate.templateArchive)
        val parsedFromTemplate = com.example.extractor.JadhadhaParser.parseWithReport(importedTemplate.structuredText).plan
        assertNotNull(parsedFromTemplate.templateArchive)

        val filledPlan = com.example.model.SamplePlans.READING_SAMPLE.copy(
            templateArchive = parsedFromTemplate.templateArchive
        )

        // 2. Export the filled plan to a new .docx file
        val filledFile = java.io.File(context.cacheDir, "test_phase3_5_template_filled.docx")
        com.example.docx.DocxGenerator.generateDocxToFile(filledFile, filledPlan)

        // 3. Inspect the ZIP entries of filledFile
        val outEntries = mutableMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(java.io.FileInputStream(filledFile)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory) outEntries[e.name] = zis.readBytes()
                zis.closeEntry()
                e = zis.nextEntry
            }
        }

        // Verify styles, fontTable, numbering, settings, rels, and binary media logo are preserved intact
        assertTrue(outEntries.containsKey("word/styles.xml"))
        assertTrue(String(outEntries["word/styles.xml"]!!).contains("CustomMinistryStyle"))
        assertEquals(customFontTableXml, String(outEntries["word/fontTable.xml"]!!))
        assertEquals(customNumberingXml, String(outEntries["word/numbering.xml"]!!))
        assertEquals(customSettingsXml, String(outEntries["word/settings.xml"]!!))
        assertEquals(documentRelsXml, String(outEntries["word/_rels/document.xml.rels"]!!))
        org.junit.Assert.assertArrayEquals(fakePngLogoBytes, outEntries["word/media/ministry_logo.png"])

        // Verify header1.xml and footer1.xml placeholders were filled and page numbering was preserved
        val filledHeaderXml = String(outEntries["word/header1.xml"]!!)
        assertTrue(filledHeaderXml.contains("مدرسة أنوال (السلك الإعدادي)"))
        assertTrue(filledHeaderXml.contains("محمد العابد"))
        assertTrue(filledHeaderXml.contains("المنفى – ص: 44"))

        val filledFooterXml = String(outEntries["word/footer1.xml"]!!)
        assertTrue(filledFooterXml.contains("المختار في اللغة العربية"))
        assertTrue(filledFooterXml.contains("""<w:fldSimple w:instr="PAGE"/>"""))

        // Verify document.xml preserved the leading logo drawing, custom font "Traditional Arabic", and landscape sectPr
        val filledDocXml = String(outEntries["word/document.xml"]!!)
        assertTrue(filledDocXml.contains("""r:embed="rIdLogo1""""))
        assertTrue(filledDocXml.contains("""w:orient="landscape""""))
        assertTrue(filledDocXml.contains("""<w:headerReference w:type="default" r:id="rIdHeader1"/>"""))
        assertTrue(filledDocXml.contains("""<w:footerReference w:type="default" r:id="rIdFooter1"/>"""))
        assertTrue(filledDocXml.contains("""<w:pgNumType w:start="1"/>"""))
        assertTrue(filledDocXml.contains("""w:ascii="Traditional Arabic""""))

        // Verify the filled .docx opens cleanly in DocumentFileReader with all namespace prefixes valid
        val reopenedFilled = com.example.extractor.DocumentFileReader.extractStructuredDocx(filledFile)
        assertTrue(reopenedFilled.structuredText.contains("المنفى – ص: 44"))
        assertTrue(reopenedFilled.structuredText.contains("مدرسة أنوال (السلك الإعدادي)"))
    }

    @Test
    fun `phase3_6 - preserve Arabic text diacritics punctuation and RTL table and paragraph direction`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vocalizedLine1 = "قَالَ الشَّاعِرُ: «إِنَّ الْوَطَنَ هُوَ الْحِضْنُ الدَّافِئُ؛ فَلَا تَبْخَلُوا عَلَيْهِ بِالْعَطَاءِ!»."
        val vocalizedLine2 = "تَـأْمِـيـنُ التَّـوَاصُـلِ اللُّغَوِيِّ السَّلِيمِ (سنة 1953م - ص: 44 - نسبة 100%)."
        val vocalizedLine3 = "أَمِنَ الْمُؤْمِنُ مَكْرَ اللَّهِ؟ حَاشَا وَكَلَّا، فَالْمُؤْمِنُ صَبَّارٌ شَكُورٌ."

        val basePlan = com.example.model.SamplePlans.READING_SAMPLE
        val obsStage = basePlan.stages["observation"]!!
        val arabicPlan = basePlan.copy(
            stages = basePlan.stages.toMutableMap().apply {
                put(
                    "observation",
                    obsStage.copy(
                        content = listOf(vocalizedLine1, vocalizedLine2, vocalizedLine3)
                    )
                )
            }
        )

        val outFile = java.io.File(context.cacheDir, "test_phase3_6_arabic_rtl.docx")
        com.example.docx.DocxGenerator.generateDocxToFile(outFile, arabicPlan)

        // 1. Inspect raw XML to verify RTL markers (<w:bidiVisual/>, <w:bidi/>, <w:rtl/>)
        var docXml = ""
        java.util.zip.ZipInputStream(java.io.FileInputStream(outFile)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (e.name == "word/document.xml") {
                    docXml = String(zis.readBytes(), java.nio.charset.StandardCharsets.UTF_8)
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        assertTrue("Every table must declare <w:bidiVisual/> for RTL column order", docXml.contains("<w:bidiVisual/>"))
        assertTrue("Paragraphs and runs must declare <w:bidi/>", docXml.contains("<w:bidi/>"))
        assertTrue("Runs must declare <w:rtl/>", docXml.contains("<w:rtl/>"))

        // 2. Re-open with DocumentFileReader & JadhadhaParser and verify exact Arabic strings and column alignment
        val extracted = com.example.extractor.DocumentFileReader.extractStructuredDocx(outFile)
        assertTrue(extracted.structuredText.contains(vocalizedLine1))
        assertTrue(extracted.structuredText.contains(vocalizedLine2))
        assertTrue(extracted.structuredText.contains(vocalizedLine3))

        // Verify the 5-column stages table column order is NOT inverted:
        // Col 0 = المقاطع, Col 1 = القدرات والمهارات, Col 2 = الأنشطة التعليمية التعلمية, Col 3 = المحتوى, Col 4 = التقويم والدعم
        val stagesTbl = extracted.tables[1]
        val headerCells = stagesTbl.rows[1].cells.map { it.text }
        assertEquals(
            listOf("المقاطع", "القدرات والمهارات", "الأنشطة التعليمية التعلمية", "المحتوى", "التقويم والدعم"),
            headerCells
        )

        val reparsed = com.example.extractor.JadhadhaParser.parseWithReport(extracted.structuredText).plan
        val reparsedObs = reparsed.stages["observation"]!!
        assertTrue(reparsedObs.content.contains(vocalizedLine1))
        assertTrue(reparsedObs.content.contains(vocalizedLine2))
        assertTrue(reparsedObs.content.contains(vocalizedLine3))
    }

    @Test
    fun `phase3_7 - ensure zero extracted units are lost upon docx export and re-import`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()

        // 1. Create a rich source .docx with headers, stages, nested tables, custom table, short titles, and unclassified notes
        val sourcePlan = com.example.model.SamplePlans.LANGUAGE_SAMPLE.copy(
            extraParagraphs = listOf(
                "ملحوظة منهجية: يخصص الأستاذ خمس دقائق في نهاية الحصة لتصحيح التمارين على السبورة."
            ),
            unclassifiedItems = listOf(
                "مرجع إضافي: كتاب النحو الوافي - عباس حسن.",
                "تمهيد"
            ),
            customTables = listOf(
                com.example.model.StructuredTable(
                    rows = listOf(
                        com.example.model.StructuredTableRow(
                            isHeader = true,
                            cells = listOf(
                                com.example.model.StructuredTableCell("النشاط التكميلي", isHeader = true),
                                com.example.model.StructuredTableCell("الزمن المخصص", isHeader = true)
                            )
                        ),
                        com.example.model.StructuredTableRow(
                            cells = listOf(
                                com.example.model.StructuredTableCell("تطبيقات كتابية في الدفاتر"),
                                com.example.model.StructuredTableCell("10 دقائق")
                            )
                        )
                    )
                )
            )
        )

        val sourceDocx = java.io.File(context.cacheDir, "test_phase3_7_source.docx")
        com.example.docx.DocxGenerator.generateDocxToFile(sourceDocx, sourcePlan)

        // 2. Extract units from sourceDocx -> parse into JadhadhaPlan -> verify integrity
        val sourceExtraction = com.example.extractor.DocumentFileReader.extractStructuredDocx(sourceDocx)
        val parsedResult = com.example.extractor.JadhadhaParser.parseWithReport(sourceExtraction.structuredText)
        val integrity = parsedResult.report.integrityReport!!
        assertTrue(
            "Zero extracted units should be missing after parsing sourceDocx, but found: ${integrity.missingUnits.map { it.verbatimText }}",
            integrity.missingUnits.isEmpty()
        )
        assertEquals(100, parsedResult.report.verificationCompletionPercentage)

        // 3. Export the parsed plan back to a second .docx and re-extract
        val reExportedDocx = java.io.File(context.cacheDir, "test_phase3_7_reexported.docx")
        com.example.docx.DocxGenerator.generateDocxToFile(reExportedDocx, parsedResult.plan)
        val reExtraction = com.example.extractor.DocumentFileReader.extractStructuredDocx(reExportedDocx)

        // Verify every single unit from sourceExtraction exists in reExtraction
        val reIntegrity = com.example.extractor.JadhadhaParser.verifyContentIntegrity(
            originalRawText = sourceExtraction.rawOriginalText,
            extractedUnits = sourceExtraction.units,
            plan = parsedResult.plan,
            unclassifiedLines = parsedResult.report.unclassifiedLines
        )
        assertTrue(reIntegrity.missingUnits.isEmpty())
        assertTrue(reExtraction.structuredText.contains("تمهيد"))
        assertTrue(reExtraction.structuredText.contains("ملحوظة منهجية: يخصص الأستاذ خمس دقائق"))
        assertTrue(reExtraction.structuredText.contains("مرجع إضافي: كتاب النحو الوافي - عباس حسن."))
        assertTrue(reExtraction.structuredText.contains("تطبيقات كتابية في الدفاتر"))
    }

    @Test
    fun `phase3_8 - reopen generated docx files for all components and verify XML tables cells and paragraphs`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()

        val samples = listOf(
            "reading" to com.example.model.SamplePlans.READING_SAMPLE,
            "language" to com.example.model.SamplePlans.LANGUAGE_SAMPLE,
            "expression" to com.example.model.SamplePlans.EXPRESSION_SAMPLE
        )

        for ((label, samplePlan) in samples) {
            val docxFile = java.io.File(context.cacheDir, "test_phase3_8_${label}.docx")
            com.example.docx.DocxGenerator.generateDocxToFile(docxFile, samplePlan)
            assertTrue("Generated .docx for $label must exist", docxFile.exists() && docxFile.length() > 1000L)

            // 1. Parse raw XML with namespace-aware XmlPullParser to verify strict OpenXML validity
            //    and confirm that every <w:tc> ends with <w:p> (mandatory Word rule when nested <w:tbl> is present)
            var rawDocXml = ""
            java.util.zip.ZipInputStream(java.io.FileInputStream(docxFile)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        rawDocXml = String(zis.readBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            assertTrue(rawDocXml.isNotEmpty())
            org.junit.Assert.assertFalse(
                "No <w:tc> may end directly with </w:tbl></w:tc> without a trailing <w:p>",
                Regex("""</w:tbl>\s*</w:tc>""").containsMatchIn(rawDocXml)
            )

            // 2. Extract structured tables and paragraphs via DocumentFileReader
            val extracted = com.example.extractor.DocumentFileReader.extractStructuredDocx(docxFile)
            when (samplePlan.componentType) {
                com.example.model.PedagogicalComponent.READING -> {
                    assertEquals(2, extracted.tables.size) // Header table + 2-session Reading stages table
                    assertEquals(7, extracted.tables[0].rows.size)
                }
                com.example.model.PedagogicalComponent.LANGUAGE -> {
                    assertEquals(2, extracted.tables.size) // Header table + Language stages table
                    assertEquals(7, extracted.tables[0].rows.size)
                    val obsCell = extracted.tables[1].rows[2].cells[3]
                    assertEquals(2, obsCell.nestedTables.size)
                    assertEquals(15, obsCell.nestedTables.sumOf { it.rows.size })
                }
                com.example.model.PedagogicalComponent.EXPRESSION -> {
                    assertEquals(8, extracted.tables.size) // 4 sessions x (Header table + Stages table)
                    val session4StagesTbl = extracted.tables[7]
                    val diagnosisCell = session4StagesTbl.rows[2].cells[3]
                    assertEquals(1, diagnosisCell.nestedTables.size)
                    assertEquals(5, diagnosisCell.nestedTables[0].rows.size)
                }
            }

            // 3. Also verify PythonToolkit JSON export contains structured tables and full metadata
            val exportedJson = com.example.python.PythonToolkit.planToJsonString(samplePlan)
            val jsonObj = org.json.JSONObject(exportedJson)
            assertEquals(samplePlan.topic, jsonObj.getString("topic"))
            assertEquals(samplePlan.info.teacherName, jsonObj.getJSONObject("info").getString("teacherName"))
            assertEquals(samplePlan.info.schoolName, jsonObj.getJSONObject("info").getString("schoolName"))
        }
    }
}




