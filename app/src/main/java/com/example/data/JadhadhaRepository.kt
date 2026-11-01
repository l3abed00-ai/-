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
        entities.map { entity -> resolvePlanAttachments(entity) }
    }

    fun getPlansByCategory(category: String): Flow<List<JadhadhaPlan>> =
        jadhadhaDao.getPlansByCategory(category).map { entities ->
            entities.map { entity -> resolvePlanAttachments(entity) }
        }

    val allCategories: Flow<List<String>> = jadhadhaDao.getAllCategories()

    /**
     * Resolves a plan's attachments using [AttachmentDao] (`attachments_table`) as the canonical
     * Single Source of Truth when available. If a legacy record still has attachments only in
     * `attachmentsJson`, migrates them transparently into `attachments_table` so zero data is lost.
     */
    private suspend fun resolvePlanAttachments(entity: JadhadhaEntity): JadhadhaPlan {
        val basePlan = entity.toPlan()
        val dao = attachmentDao ?: return basePlan

        var dbAttachments = dao.getAttachmentsListForPlan(entity.id)
        if (dbAttachments.isEmpty() && basePlan.attachments.isNotEmpty()) {
            // Transparent one-way migration from legacy attachmentsJson to attachments_table
            val migrated = basePlan.attachments.map { att ->
                AttachmentEntity(
                    id = att.id,
                    planId = entity.id,
                    fileName = att.fileName,
                    relativePath = att.relativePath,
                    mimeType = att.mimeType,
                    fileSize = att.fileSize,
                    createdAt = att.createdAt
                )
            }
            runCatching { dao.insertAll(migrated) }
            dbAttachments = dao.getAttachmentsListForPlan(entity.id)
        }

        val canonicalAttachments = dbAttachments.map {
            AttachmentInfo(
                id = it.id,
                planId = it.planId,
                fileName = it.fileName,
                relativePath = it.relativePath,
                mimeType = it.mimeType,
                fileSize = it.fileSize,
                createdAt = it.createdAt
            )
        }
        return basePlan.copy(attachments = canonicalAttachments)
    }

    suspend fun savePlan(plan: JadhadhaPlan, isCustom: Boolean = true): Unit = writeMutex.withLock {
        val existing = jadhadhaDao.getPlanById(plan.id)
        // Prevent stale write: if existing entity in DB was updated after this plan's timestamp, reject write
        if (existing != null && existing.updatedAt > plan.updatedAt) {
            return@withLock
        }

        val canonicalAttachments = if (attachmentDao != null) {
            val existingDbAtts = attachmentDao.getAttachmentsListForPlan(plan.id)
            if (plan.attachments.isNotEmpty() && existingDbAtts.isEmpty()) {
                attachmentDao.insertAll(
                    plan.attachments.map { att ->
                        AttachmentEntity(
                            id = att.id,
                            planId = plan.id,
                            fileName = att.fileName,
                            relativePath = att.relativePath,
                            mimeType = att.mimeType,
                            fileSize = att.fileSize,
                            createdAt = att.createdAt
                        )
                    }
                )
                plan.attachments
            } else {
                existingDbAtts.map {
                    AttachmentInfo(
                        id = it.id,
                        planId = it.planId,
                        fileName = it.fileName,
                        relativePath = it.relativePath,
                        mimeType = it.mimeType,
                        fileSize = it.fileSize,
                        createdAt = it.createdAt
                    )
                }
            }
        } else {
            plan.attachments
        }

        val nextTimestamp = if (existing != null) {
            maxOf(System.currentTimeMillis(), existing.updatedAt + 1L)
        } else {
            System.currentTimeMillis()
        }

        val planToSave = plan.copy(
            updatedAt = nextTimestamp,
            attachments = canonicalAttachments
        )
        val entity = JadhadhaEntity.fromPlan(planToSave, isCustom = isCustom)
        jadhadhaDao.upsertIfNotStale(entity, expectedUpdatedAt = plan.updatedAt)
    }

    /**
     * Deletes a plan with crash-resilient consistency between Room and the filesystem:
     * 1. Records [id] in the persistent pending-deletion log (`KEY_PENDING_DELETE_IDS`).
     * 2. Deletes the database rows first (`attachments_table` + `jadhadha_table`).
     *    - If the DB deletion throws an exception, removes [id] from pending-deletions and rethrows
     *      so the physical directory remains untouched and consistent with the surviving DB record.
     * 3. Deletes the physical plan directory (`deletePlanDirectory(id)`).
     *    - Only removes [id] from `KEY_PENDING_DELETE_IDS` if the directory is completely deleted.
     *    - If directory deletion fails (or the process crashes between step 2 and step 3),
     *      [reconcileStorage] / [cleanupOrphanFiles] will complete the cleanup on next run.
     */
    suspend fun deletePlan(id: String): Unit = writeMutex.withLock {
        markPlanPendingDeletion(id)
        try {
            attachmentDao?.deleteByPlanId(id)
            jadhadhaDao.deleteById(id)
        } catch (t: Throwable) {
            // DB deletion failed: unmark pending deletion so physical files are preserved for the surviving DB row
            unmarkPlanPendingDeletion(id)
            throw t
        }

        val dirDeleted = runCatching {
            planFileManager?.deletePlanDirectory(id) ?: true
        }.getOrDefault(false)

        if (dirDeleted) {
            unmarkPlanPendingDeletion(id)
        }
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
        val attInfo = saveResult.getOrElse { return Result.failure(it) }

        try {
            // 1. Insert into canonical attachments_table (Single Source of Truth)
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

            // 2. Keep plan entity metadata synchronized
            val existingEntity = jadhadhaDao.getPlanById(planId)
            if (existingEntity != null) {
                val existingPlan = resolvePlanAttachments(existingEntity)
                val updatedAttachments = if (attachmentDao != null) {
                    existingPlan.attachments
                } else {
                    existingPlan.attachments.filterNot { it.id == attInfo.id } + attInfo
                }
                val nextTimestamp = maxOf(System.currentTimeMillis(), existingEntity.updatedAt + 1L)
                val updatedPlan = existingPlan.copy(
                    attachments = updatedAttachments,
                    updatedAt = nextTimestamp
                )
                jadhadhaDao.insertOrUpdate(JadhadhaEntity.fromPlan(updatedPlan, isCustom = existingEntity.isCustom))
            }

            Result.success(attInfo)
        } catch (t: Throwable) {
            // Roll back physical file if DB insertion failed so no orphan file remains on disk
            runCatching { manager.deleteAttachmentFile(planId, attInfo.fileName) }
            Result.failure(t)
        }
    }

    suspend fun deleteAttachment(planId: String, attachmentId: String): Boolean = writeMutex.withLock {
        val existingEntity = jadhadhaDao.getPlanById(planId)
        val existingPlan = existingEntity?.toPlan()
        val att = attachmentDao?.getAttachmentById(attachmentId)
        val fileName = att?.fileName
            ?: existingPlan?.attachments?.firstOrNull { it.id == attachmentId || it.fileName == attachmentId }?.fileName
            ?: attachmentId

        // 1. Delete from canonical attachments_table first
        if (att != null) {
            attachmentDao?.deleteById(att.id)
        } else {
            attachmentDao?.deleteById(attachmentId)
        }

        // 2. Synchronize jadhadha_table metadata
        if (existingEntity != null && existingPlan != null) {
            val remainingAttachments = if (attachmentDao != null) {
                attachmentDao.getAttachmentsListForPlan(planId).map {
                    AttachmentInfo(
                        id = it.id,
                        planId = it.planId,
                        fileName = it.fileName,
                        relativePath = it.relativePath,
                        mimeType = it.mimeType,
                        fileSize = it.fileSize,
                        createdAt = it.createdAt
                    )
                }
            } else {
                existingPlan.attachments.filterNot { it.id == attachmentId || it.fileName == attachmentId }
            }
            val nextTimestamp = maxOf(System.currentTimeMillis(), existingEntity.updatedAt + 1L)
            val updatedPlan = existingPlan.copy(
                attachments = remainingAttachments,
                updatedAt = nextTimestamp
            )
            jadhadhaDao.insertOrUpdate(JadhadhaEntity.fromPlan(updatedPlan, isCustom = existingEntity.isCustom))
        }

        // 3. Delete physical file (even if physical delete fails, DB is consistent and reconcileStorage can clean orphans)
        val fileDeleted = runCatching {
            planFileManager?.deleteAttachmentFile(planId, fileName) ?: true
        }.getOrDefault(false)

        fileDeleted
    }

    /**
     * Performs full startup/on-demand storage reconciliation between Room and the filesystem:
     * 1. Retries pending directory deletions recorded in `KEY_PENDING_DELETE_IDS`.
     * 2. Deletes any leftover `*.tmp` files from interrupted atomic writes.
     * 3. Removes orphan plan directories on disk that have no matching row in `jadhadha_table`.
     * 4. Migrates any legacy `attachmentsJson` entries to `attachments_table` and removes orphan files
     *    inside existing plan attachment folders that are not registered in `attachments_table`.
     */
    suspend fun reconcileStorage(): Int = writeMutex.withLock {
        val manager = planFileManager ?: return 0
        var totalCleaned = 0

        // 1. Complete any pending plan directory deletions
        val pendingIds = getPendingDeletionPlanIds().toSet()
        for (pendingId in pendingIds) {
            val stillInDb = jadhadhaDao.getPlanById(pendingId) != null
            if (stillInDb) {
                unmarkPlanPendingDeletion(pendingId)
            } else if (manager.deletePlanDirectory(pendingId)) {
                unmarkPlanPendingDeletion(pendingId)
                totalCleaned++
            }
        }

        // 2. Clean up leftover .tmp files from interrupted writes
        totalCleaned += manager.cleanupTempFiles()

        // 3. Clean up orphan plan directories not in jadhadha_table
        val validPlanIds = jadhadhaDao.getAllPlanIds().toSet()
        totalCleaned += manager.cleanupOrphanFiles(validPlanIds)

        // 4. Reconcile attachments_table with legacy attachmentsJson and physical attachment files
        if (attachmentDao != null) {
            val allEntities = runCatching { jadhadhaDao.getAllPlansSnapshot() }.getOrDefault(emptyList())
            for (entity in allEntities) {
                val resolved = resolvePlanAttachments(entity)
                val validFileNames = resolved.attachments.map { manager.sanitizeFileName(it.fileName) }.toSet()
                val attDir = manager.getPlanAttachmentsDir(entity.id, createIfMissing = false)
                if (attDir.exists() && attDir.isDirectory) {
                    attDir.listFiles()?.forEach { file ->
                        if (file.isFile && !file.name.endsWith(".tmp") && file.name !in validFileNames) {
                            if (file.delete()) {
                                totalCleaned++
                            }
                        }
                    }
                }
            }
        }

        totalCleaned
    }

    suspend fun cleanupOrphanFiles(): Int {
        return reconcileStorage()
    }

    fun getPendingDeletionPlanIds(): Set<String> {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return emptySet()
        return prefs.getStringSet(KEY_PENDING_DELETE_IDS, emptySet())?.toSet().orEmpty()
    }

    private fun markPlanPendingDeletion(planId: String) {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        val updated = getPendingDeletionPlanIds().toMutableSet().apply { add(planId) }
        prefs.edit().putStringSet(KEY_PENDING_DELETE_IDS, updated).commit()
    }

    private fun unmarkPlanPendingDeletion(planId: String) {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        val current = getPendingDeletionPlanIds()
        if (planId in current) {
            val updated = current.toMutableSet().apply { remove(planId) }
            prefs.edit().putStringSet(KEY_PENDING_DELETE_IDS, updated).commit()
        }
    }

    suspend fun clearAllStoredData(): Unit = writeMutex.withLock {
        val allPlanIds = jadhadhaDao.getAllPlanIds()
        allPlanIds.forEach { id ->
            markPlanPendingDeletion(id)
        }

        // Delete all attachment rows
        attachmentDao?.getAllAttachments()?.forEach { att ->
            attachmentDao.deleteById(att.id)
        }

        // Delete all plan rows
        jadhadhaDao.deleteAllJadhadhat()

        // Delete physical plan directories
        allPlanIds.forEach { id ->
            if (planFileManager?.deletePlanDirectory(id) != false) {
                unmarkPlanPendingDeletion(id)
            }
        }

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
        val entity = jadhadhaDao.getPlanById(id) ?: return null
        return resolvePlanAttachments(entity)
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
        private const val KEY_PENDING_DELETE_IDS = "pending_delete_plan_ids"
    }
}
