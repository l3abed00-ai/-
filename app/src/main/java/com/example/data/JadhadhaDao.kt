package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface JadhadhaDao {

    @Query("SELECT * FROM jadhadha_plans ORDER BY updatedAt DESC")
    fun getAllPlans(): Flow<List<JadhadhaEntity>>

    @Query("SELECT * FROM jadhadha_plans WHERE category = :category ORDER BY updatedAt DESC")
    fun getPlansByCategory(category: String): Flow<List<JadhadhaEntity>>

    @Query("SELECT DISTINCT category FROM jadhadha_plans WHERE category IS NOT NULL AND category != '' ORDER BY category ASC")
    fun getAllCategories(): Flow<List<String>>

    @Query("SELECT * FROM jadhadha_plans WHERE id = :id LIMIT 1")
    suspend fun getPlanById(id: String): JadhadhaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(plan: JadhadhaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(plans: List<JadhadhaEntity>)

    @Query("DELETE FROM jadhadha_plans WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM jadhadha_plans")
    suspend fun getCount(): Int

    @Query("DELETE FROM jadhadha_plans")
    suspend fun deleteAll()
}
