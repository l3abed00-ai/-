package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(plan: JadhadhaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(plans: List<JadhadhaEntity>)

    @Query("DELETE FROM jadhadha_table WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM jadhadha_table")
    suspend fun getCount(): Int

    @Query("DELETE FROM jadhadha_table")
    suspend fun deleteAllJadhadhat()

    @Query("DELETE FROM jadhadha_table")
    suspend fun deleteAll()
}
