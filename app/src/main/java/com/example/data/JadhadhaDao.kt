package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface JadhadhaDao {

    @Query("SELECT * FROM jadhadha_table ORDER BY updatedAt DESC")
    fun getAllPlans(): Flow<List<JadhadhaEntity>>

    @Query("SELECT * FROM jadhadha_table WHERE category = :category ORDER BY updatedAt DESC")
    fun getPlansByCategory(category: String): Flow<List<JadhadhaEntity>>

    @Query("SELECT DISTINCT category FROM jadhadha_table WHERE category IS NOT NULL AND category != '' ORDER BY category ASC")
    fun getAllCategories(): Flow<List<String>>

    @Query("SELECT * FROM jadhadha_table WHERE id = :id LIMIT 1")
    suspend fun getPlanById(id: String): JadhadhaEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(plan: JadhadhaEntity): Long

    @Update
    suspend fun updateEntity(plan: JadhadhaEntity)

    /**
     * Upserts a plan using INSERT OR IGNORE followed by UPDATE so that updating an existing
     * plan in `jadhadha_table` never triggers SQLite's `ON DELETE CASCADE` on `attachments_table`.
     */
    @Transaction
    suspend fun insertOrUpdate(plan: JadhadhaEntity) {
        val rowId = insertIfAbsent(plan)
        if (rowId == -1L) {
            updateEntity(plan)
        }
    }

    @Query(
        """
        UPDATE jadhadha_table SET
            topic = :topic,
            componentType = :componentType,
            category = :category,
            infoLevel = :infoLevel,
            infoUnit = :infoUnit,
            infoDomain = :infoDomain,
            infoComponent = :infoComponent,
            infoDuration = :infoDuration,
            infoMaterials = :infoMaterials,
            infoReference = :infoReference,
            infoTeacherName = :infoTeacherName,
            infoSchoolName = :infoSchoolName,
            communicativeComp = :communicativeComp,
            methodologicalComp = :methodologicalComp,
            culturalComp = :culturalComp,
            stagesJson = :stagesJson,
            updatedAt = :newUpdatedAt,
            isCustom = :isCustom,
            isReviewed = :isReviewed,
            attachmentsJson = :attachmentsJson
        WHERE id = :id AND updatedAt <= :expectedUpdatedAt
        """
    )
    suspend fun updateIfNotStale(
        id: String,
        topic: String,
        componentType: String,
        category: String,
        infoLevel: String,
        infoUnit: String,
        infoDomain: String,
        infoComponent: String,
        infoDuration: String,
        infoMaterials: String,
        infoReference: String,
        infoTeacherName: String,
        infoSchoolName: String,
        communicativeComp: String,
        methodologicalComp: String,
        culturalComp: String,
        stagesJson: String,
        newUpdatedAt: Long,
        isCustom: Boolean,
        isReviewed: Boolean,
        attachmentsJson: String,
        expectedUpdatedAt: Long
    ): Int

    @Transaction
    suspend fun upsertIfNotStale(entity: JadhadhaEntity, expectedUpdatedAt: Long): Boolean {
        val existing = getPlanById(entity.id)
        if (existing == null) {
            insertIfAbsent(entity)
            return true
        }
        if (existing.updatedAt > expectedUpdatedAt) {
            return false
        }
        val nextUpdatedAt = maxOf(entity.updatedAt, existing.updatedAt + 1L)
        val rows = updateIfNotStale(
            id = entity.id,
            topic = entity.topic,
            componentType = entity.componentType,
            category = entity.category,
            infoLevel = entity.infoLevel,
            infoUnit = entity.infoUnit,
            infoDomain = entity.infoDomain,
            infoComponent = entity.infoComponent,
            infoDuration = entity.infoDuration,
            infoMaterials = entity.infoMaterials,
            infoReference = entity.infoReference,
            infoTeacherName = entity.infoTeacherName,
            infoSchoolName = entity.infoSchoolName,
            communicativeComp = entity.communicativeComp,
            methodologicalComp = entity.methodologicalComp,
            culturalComp = entity.culturalComp,
            stagesJson = entity.stagesJson,
            newUpdatedAt = nextUpdatedAt,
            isCustom = entity.isCustom,
            isReviewed = entity.isReviewed,
            attachmentsJson = entity.attachmentsJson,
            expectedUpdatedAt = expectedUpdatedAt
        )
        return rows > 0
    }

    @Transaction
    suspend fun insertAll(plans: List<JadhadhaEntity>) {
        for (plan in plans) {
            insertOrUpdate(plan)
        }
    }

    @Query("DELETE FROM jadhadha_table WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM jadhadha_table")
    suspend fun getCount(): Int

    @Query("SELECT id FROM jadhadha_table")
    suspend fun getAllPlanIds(): List<String>

    @Query("SELECT * FROM jadhadha_table")
    suspend fun getAllPlansSnapshot(): List<JadhadhaEntity>

    @Query("DELETE FROM jadhadha_table")
    suspend fun deleteAllJadhadhat()

    @Query("DELETE FROM jadhadha_table")
    suspend fun deleteAll()
}
