package com.example.data

import android.content.Context
import com.example.model.JadhadhaPlan
import com.example.model.SamplePlans
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class JadhadhaRepository(
    private val jadhadhaDao: JadhadhaDao,
    private val context: Context? = null
) {

    val allPlans: Flow<List<JadhadhaPlan>> = jadhadhaDao.getAllPlans().map { entities ->
        entities.map { it.toPlan() }
    }

    fun getPlansByCategory(category: String): Flow<List<JadhadhaPlan>> =
        jadhadhaDao.getPlansByCategory(category).map { entities ->
            entities.map { it.toPlan() }
        }

    val allCategories: Flow<List<String>> = jadhadhaDao.getAllCategories()

    suspend fun savePlan(plan: JadhadhaPlan, isCustom: Boolean = true) {
        val entity = JadhadhaEntity.fromPlan(plan, isCustom = isCustom)
        jadhadhaDao.insertOrUpdate(entity)
    }

    suspend fun deletePlan(id: String) {
        jadhadhaDao.deleteById(id)
    }

    suspend fun clearAllStoredData() {
        jadhadhaDao.deleteAllJadhadhat()
        // إذا كانت هناك تفضيلات أو ملفات مؤقتة مسجلة محلياً يمكن مسحها هنا أيضاً
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
        return jadhadhaDao.getPlanById(id)?.toPlan()
    }

    suspend fun prepopulateSamplesIfEmpty() {
        if (jadhadhaDao.getCount() == 0) {
            val entities = SamplePlans.ALL_SAMPLES.map {
                JadhadhaEntity.fromPlan(it, isCustom = false)
            }
            jadhadhaDao.insertAll(entities)
        }
    }
}
