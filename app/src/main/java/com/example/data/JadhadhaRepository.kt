package com.example.data

import com.example.model.JadhadhaPlan
import com.example.model.SamplePlans
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class JadhadhaRepository(private val dao: JadhadhaDao) {

    val allPlans: Flow<List<JadhadhaPlan>> = dao.getAllPlans().map { entities ->
        entities.map { it.toPlan() }
    }

    fun getPlansByCategory(category: String): Flow<List<JadhadhaPlan>> =
        dao.getPlansByCategory(category).map { entities ->
            entities.map { it.toPlan() }
        }

    val allCategories: Flow<List<String>> = dao.getAllCategories()

    suspend fun savePlan(plan: JadhadhaPlan, isCustom: Boolean = true) {
        val entity = JadhadhaEntity.fromPlan(plan, isCustom = isCustom)
        dao.insertOrUpdate(entity)
    }

    suspend fun deletePlan(id: String) {
        dao.deleteById(id)
    }

    suspend fun getPlanById(id: String): JadhadhaPlan? {
        return dao.getPlanById(id)?.toPlan()
    }

    suspend fun prepopulateSamplesIfEmpty() {
        if (dao.getCount() == 0) {
            val entities = SamplePlans.ALL_SAMPLES.map {
                JadhadhaEntity.fromPlan(it, isCustom = false)
            }
            dao.insertAll(entities)
        }
    }
}
