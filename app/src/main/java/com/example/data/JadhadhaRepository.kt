package com.example.data

import android.content.Context
import com.example.model.AttachmentInfo
import com.example.model.JadhadhaPlan
import com.example.model.SamplePlans
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class JadhadhaRepository(
    private val jadhadhaDao: JadhadhaDao,
    private val context: Context? = null,
    private val attachmentDao: AttachmentDao? = null
) {

    private val writeMutex = Mutex()

    private val planFileManager: PlanFileManager? = context?.let { PlanFileManager(it) }

    val allPlans: Flow<List<JadhadhaPlan>> = jadhadhaDao.getAllPlans().map { entities ->
        entities.map { it.toPlan() }
    }

    fun getPlansByCategory(category: String): Flow<List<JadhadhaPlan>> =
        jadhadhaDao.getPlansByCategory(category).map { entities ->
            entities.map { it.toPlan() }
        }

    val allCategories: Flow<List<String>> = jadhadhaDao.getAllCategories()

    suspend fun savePlan(plan: JadhadhaPlan, isCustom: Boolean = true): Unit = writeMutex.withLock {
        val existing = jadhadhaDao.getPlanById(plan.id)
        // Prevent stale write: if existing entity in DB was updated after this plan's timestamp, do not blindly overwrite
        if (existing != null && existing.updatedAt > plan.updatedAt) {
            return@withLock
        }
        val planToSave = plan.copy(updatedAt = System.currentTimeMillis())
        val entity = JadhadhaEntity.fromPlan(planToSave, isCustom = isCustom)
        jadhadhaDao.insertOrUpdate(entity)
    }

    suspend fun deletePlan(id: String): Unit = writeMutex.withLock {
        // 1. Physically delete all files and directory belonging to this plan
        planFileManager?.deletePlanDirectory(id)

        // 2. Delete database attachment rows for this plan
        attachmentDao?.deleteByPlanId(id)

        // 3. Delete the plan entity from the database
        jadhadhaDao.deleteById(id)
    }

    suspend fun addAttachment(
        planId: String,
        fileName: String,
        bytes: ByteArray,
        mimeType: String = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    ): Result<AttachmentInfo> = writeMutex.withLock {
        val manager = planFileManager
            ?: return Result.failure(IllegalStateException("Context is required for attachment storage"))

        val saveResult = manager.saveAttachment(planId, fileName, bytes, mimeType)
        val attInfo = saveResult.getOrThrow()

        // Insert into attachment table
        attachmentDao?.insert(
            AttachmentEntity(
                id = attInfo.id,
                planId = planId,
                fileName = attInfo.fileName,
                relativePath = attInfo.relativePath,
                mimeType = attInfo.mimeType,
                fileSize = attInfo.fileSize,
                createdAt = attInfo.createdAt
            )
        )

        // Update plan entity with attachment
        val existing = jadhadhaDao.getPlanById(planId)?.toPlan()
        if (existing != null) {
            val updatedAttachments = existing.attachments.filterNot { it.id == attInfo.id } + attInfo
            val updatedPlan = existing.copy(
                attachments = updatedAttachments,
                updatedAt = System.currentTimeMillis()
            )
            jadhadhaDao.insertOrUpdate(JadhadhaEntity.fromPlan(updatedPlan))
        }

        Result.success(attInfo)
    }

    suspend fun deleteAttachment(planId: String, attachmentId: String): Boolean = writeMutex.withLock {
        val existing = jadhadhaDao.getPlanById(planId)?.toPlan()
        val att = attachmentDao?.getAttachmentById(attachmentId)
        val fileName = att?.fileName
            ?: existing?.attachments?.firstOrNull { it.id == attachmentId || it.fileName == attachmentId }?.fileName
            ?: attachmentId

        planFileManager?.deleteAttachmentFile(planId, fileName)
        attachmentDao?.deleteById(attachmentId)

        if (existing != null) {
            val updatedAttachments = existing.attachments.filterNot { it.id == attachmentId || it.fileName == attachmentId }
            val updatedPlan = existing.copy(
                attachments = updatedAttachments,
                updatedAt = System.currentTimeMillis()
            )
            jadhadhaDao.insertOrUpdate(JadhadhaEntity.fromPlan(updatedPlan))
        }
        true
    }

    suspend fun cleanupOrphanFiles(): Int = writeMutex.withLock {
        val manager = planFileManager ?: return 0
        val allIds = jadhadhaDao.getAllPlanIds().toSet()
        manager.cleanupOrphanFiles(allIds)
    }

    suspend fun clearAllStoredData(): Unit = writeMutex.withLock {
        // Delete all attachment rows
        attachmentDao?.getAllAttachments()?.forEach { att ->
            attachmentDao.deleteById(att.id)
        }

        // Delete all plan rows
        val allPlanIds = jadhadhaDao.getAllPlanIds()
        allPlanIds.forEach { id ->
            planFileManager?.deletePlanDirectory(id)
        }
        jadhadhaDao.deleteAllJadhadhat()

        context?.let { ctx ->
            runCatching {
                ctx.cacheDir?.listFiles()?.forEach { file ->
                    if (file.isFile && (
                            file.name.endsWith(".docx") ||
                                file.name.endsWith(".json") ||
                                file.name.endsWith(".py") ||
                                file.name.endsWith(".tmp")
                            )
                    ) {
                        file.delete()
                    }
                }
            }
        }
    }

    suspend fun getPlanById(id: String): JadhadhaPlan? {
        val plan = jadhadhaDao.getPlanById(id)?.toPlan() ?: return null
        val dbAttachments = attachmentDao?.getAttachmentsListForPlan(id)?.map {
            AttachmentInfo(
                id = it.id,
                planId = it.planId,
                fileName = it.fileName,
                relativePath = it.relativePath,
                mimeType = it.mimeType,
                fileSize = it.fileSize,
                createdAt = it.createdAt
            )
        }.orEmpty()

        return if (dbAttachments.isNotEmpty()) {
            val combined = (plan.attachments + dbAttachments).distinctBy { it.id }
            plan.copy(attachments = combined)
        } else {
            plan
        }
    }

    /**
     * Seeds sample plans strictly once upon fresh install.
     * If the user deletes sample plans or deletes all plans, the flag remains TRUE,
     * ensuring deleted sample plans are NEVER resurrected on app restart.
     */
    suspend fun prepopulateSamplesIfEmpty(): Unit = writeMutex.withLock {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isSeeded = prefs?.getBoolean(KEY_SAMPLE_DATA_SEEDED, false) ?: false

        if (!isSeeded) {
            if (jadhadhaDao.getCount() == 0) {
                val entities = SamplePlans.ALL_SAMPLES.map {
                    JadhadhaEntity.fromPlan(it, isCustom = false)
                }
                jadhadhaDao.insertAll(entities)
            }
            prefs?.edit()?.putBoolean(KEY_SAMPLE_DATA_SEEDED, true)?.apply()
        }
    }

    /**
     * Explicit manual reset/restore of sample plans requested by the user.
     */
    suspend fun restoreDefaultSamples(): Unit = writeMutex.withLock {
        val entities = SamplePlans.ALL_SAMPLES.map {
            JadhadhaEntity.fromPlan(it, isCustom = false)
        }
        jadhadhaDao.insertAll(entities)
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(KEY_SAMPLE_DATA_SEEDED, true)
            ?.apply()
    }

    companion object {
        private const val PREFS_NAME = "jadhadha_repository_prefs"
        private const val KEY_SAMPLE_DATA_SEEDED = "has_seeded_sample_data"
    }
}
