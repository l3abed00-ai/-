package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.model.FieldSource
import com.example.model.JadhadhaCategories
import com.example.model.JadhadhaInfo
import com.example.model.JadhadhaPlan
import com.example.model.PedagogicalComponent
import com.example.model.StageData
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "jadhadha_plans")
data class JadhadhaEntity(
    @PrimaryKey
    val id: String,
    val topic: String,
    val componentType: String,
    val category: String = "نصوص قرائية",
    val infoLevel: String,
    val infoUnit: String,
    val infoDomain: String,
    val infoComponent: String,
    val infoDuration: String,
    val infoMaterials: String,
    val communicativeComp: String,
    val methodologicalComp: String,
    val culturalComp: String,
    val stagesJson: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isCustom: Boolean = true
) {
    fun toPlan(): JadhadhaPlan {
        val comp = when (componentType) {
            PedagogicalComponent.LANGUAGE.id, "language" -> PedagogicalComponent.LANGUAGE
            PedagogicalComponent.EXPRESSION.id, "expression" -> PedagogicalComponent.EXPRESSION
            else -> PedagogicalComponent.READING
        }

        val stagesMap = mutableMapOf<String, StageData>()
        if (stagesJson.isNotBlank()) {
            runCatching {
                val json = JSONObject(stagesJson)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val obj = json.optJSONObject(k) ?: continue
                    val sourcesObj = obj.optJSONObject("sources")
                    val parsedSources = mutableMapOf<String, FieldSource>()
                    if (sourcesObj != null) {
                        val srcKeys = sourcesObj.keys()
                        while (srcKeys.hasNext()) {
                            val fieldKey = srcKeys.next()
                            val srcVal = sourcesObj.optString(fieldKey, "")
                            FieldSource.fromString(srcVal)?.let { parsedSources[fieldKey] = it }
                        }
                    }
                    stagesMap[k] = StageData(
                        eval = jsonArrayToList(obj.optJSONArray("eval")),
                        content = jsonArrayToList(obj.optJSONArray("content")),
                        teacher = jsonArrayToList(obj.optJSONArray("teacher")),
                        abil = jsonArrayToList(obj.optJSONArray("abil")),
                        sources = parsedSources
                    )
                }
            }
        }

        return JadhadhaPlan(
            id = id,
            topic = topic,
            componentType = comp,
            category = if (category.isNotBlank()) category else JadhadhaCategories.forComponent(comp),
            info = JadhadhaInfo(
                level = infoLevel,
                unit = infoUnit,
                domain = infoDomain,
                component = infoComponent,
                duration = infoDuration,
                materials = infoMaterials,
                communicativeComp = communicativeComp,
                methodologicalComp = methodologicalComp,
                culturalComp = culturalComp
            ),
            stages = stagesMap,
            createdAt = createdAt
        )
    }

    companion object {
        fun fromPlan(plan: JadhadhaPlan, isCustom: Boolean = true): JadhadhaEntity {
            val stagesObj = JSONObject()
            plan.stages.forEach { (key, data) ->
                val stageJson = JSONObject().apply {
                    put("eval", JSONArray(data.eval))
                    put("content", JSONArray(data.content))
                    put("teacher", JSONArray(data.teacher))
                    put("abil", JSONArray(data.abil))
                    if (data.sources.isNotEmpty()) {
                        val sourcesJson = JSONObject()
                        data.sources.forEach { (fieldKey, source) ->
                            sourcesJson.put(fieldKey, source.name)
                        }
                        put("sources", sourcesJson)
                    }
                }
                stagesObj.put(key, stageJson)
            }

            val cat = if (plan.category.isNotBlank()) {
                plan.category
            } else {
                JadhadhaCategories.forComponent(plan.componentType)
            }

            return JadhadhaEntity(
                id = plan.id,
                topic = plan.topic,
                componentType = plan.componentType.id,
                category = cat,
                infoLevel = plan.info.level,
                infoUnit = plan.info.unit,
                infoDomain = plan.info.domain,
                infoComponent = plan.info.component,
                infoDuration = plan.info.duration,
                infoMaterials = plan.info.materials,
                communicativeComp = plan.info.communicativeComp,
                methodologicalComp = plan.info.methodologicalComp,
                culturalComp = plan.info.culturalComp,
                stagesJson = stagesObj.toString(),
                createdAt = plan.createdAt,
                updatedAt = System.currentTimeMillis(),
                isCustom = isCustom
            )
        }

        private fun jsonArrayToList(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val str = arr.optString(i, "").trim()
                if (str.isNotBlank()) list.add(str)
            }
            return list
        }
    }
}
