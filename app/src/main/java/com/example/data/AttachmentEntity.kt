package com.example.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "attachments_table",
    foreignKeys = [
        ForeignKey(
            entity = JadhadhaEntity::class,
            parentColumns = ["id"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["planId"])]
)
data class AttachmentEntity(
    @PrimaryKey
    val id: String,
    val planId: String,
    val fileName: String,
    val relativePath: String,
    val mimeType: String,
    val fileSize: Long,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface AttachmentDao {

    @Query("SELECT * FROM attachments_table WHERE planId = :planId ORDER BY createdAt ASC")
    fun getAttachmentsForPlan(planId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments_table WHERE planId = :planId ORDER BY createdAt ASC")
    suspend fun getAttachmentsListForPlan(planId: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachments_table WHERE id = :id LIMIT 1")
    suspend fun getAttachmentById(id: String): AttachmentEntity?

    @Query("SELECT * FROM attachments_table")
    suspend fun getAllAttachments(): List<AttachmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attachment: AttachmentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(attachments: List<AttachmentEntity>)

    @Query("DELETE FROM attachments_table WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM attachments_table WHERE planId = :planId")
    suspend fun deleteByPlanId(planId: String)
}
