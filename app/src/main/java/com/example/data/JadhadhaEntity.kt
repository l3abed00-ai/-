package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.model.FieldSource
import com.example.model.JadhadhaCategories
import com.example.model.JadhadhaInfo
import com.example.model.JadhadhaPlan
import com.example.model.PedagogicalComponent
import com.example.model.StageData
import com.example.model.StructuredTable
import com.example.model.StructuredTableCell
import com.example.model.StructuredTableRow
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "jadhadha_table")
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
    val infoReference: String = "المختار في اللغة العربية",
    val infoTeacherName: String = "محمد العابد",
    val infoSchoolName: String = "مدرسة أنوال (السلك الإعدادي)",
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
        var customTables = emptyList<StructuredTable>()
        var extraParagraphs = emptyList<String>()
        var unclassifiedItems = emptyList<String>()

        if (stagesJson.isNotBlank()) {
            runCatching {
                val json = JSONObject(stagesJson)
                customTables = jsonArrayToStructuredTables(json.optJSONArray("__meta_custom_tables__"))
                extraParagraphs = jsonArrayToList(json.optJSONArray("__meta_extra_paragraphs__"))
                unclassifiedItems = jsonArrayToList(json.optJSONArray("__meta_unclassified_items__"))

                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k.startsWith("__meta_")) continue
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
                        sources = parsedSources,
                        tables = jsonArrayToStructuredTables(obj.optJSONArray("tables"))
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
                reference = infoReference.ifBlank { "المختار في اللغة العربية" },
                teacherName = infoTeacherName.ifBlank { "محمد العابد" },
                schoolName = infoSchoolName.ifBlank { "مدرسة أنوال (السلك الإعدادي)" },
                communicativeComp = communicativeComp,
                methodologicalComp = methodologicalComp,
                culturalComp = culturalComp
            ),
            stages = stagesMap,
            createdAt = createdAt,
            customTables = customTables,
            extraParagraphs = extraParagraphs,
            unclassifiedItems = unclassifiedItems
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
                    if (data.tables.isNotEmpty()) {
                        put("tables", structuredTablesToJsonArray(data.tables))
                    }
                }
                stagesObj.put(key, stageJson)
            }
            if (plan.customTables.isNotEmpty()) {
                stagesObj.put("__meta_custom_tables__", structuredTablesToJsonArray(plan.customTables))
            }
            if (plan.extraParagraphs.isNotEmpty()) {
                stagesObj.put("__meta_extra_paragraphs__", JSONArray(plan.extraParagraphs))
            }
            if (plan.unclassifiedItems.isNotEmpty()) {
                stagesObj.put("__meta_unclassified_items__", JSONArray(plan.unclassifiedItems))
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
                infoReference = plan.info.reference,
                infoTeacherName = plan.info.teacherName,
                infoSchoolName = plan.info.schoolName,
                communicativeComp = plan.info.communicativeComp,
                methodologicalComp = plan.info.methodologicalComp,
                culturalComp = plan.info.culturalComp,
                stagesJson = stagesObj.toString(),
                createdAt = plan.createdAt,
                updatedAt = System.currentTimeMillis(),
                isCustom = isCustom
            )
        }

        private fun structuredTablesToJsonArray(tables: List<StructuredTable>): JSONArray {
            val arr = JSONArray()
            for (tbl in tables) {
                arr.put(structuredTableToJson(tbl))
            }
            return arr
        }

        private fun structuredTableToJson(tbl: StructuredTable): JSONObject {
            return JSONObject().apply {
                put("id", tbl.id)
                put("title", tbl.title)
                put("isRtl", tbl.isRtl)
                put("hasBorders", tbl.hasBorders)
                put("borderColorHex", tbl.borderColorHex)
                put("borderSizeEighthsPt", tbl.borderSizeEighthsPt)
                tbl.sourceStageKey?.let { put("sourceStageKey", it) }
                put("sourceField", tbl.sourceField)
                put("columnWidthsDxa", JSONArray(tbl.columnWidthsDxa))
                val rowsArr = JSONArray()
                for (row in tbl.rows) {
                    val rowObj = JSONObject().apply {
                        put("isHeader", row.isHeader)
                        put("cantSplit", row.cantSplit)
                        row.heightDxa?.let { put("heightDxa", it) }
                        val cellsArr = JSONArray()
                        for (cell in row.cells) {
                            val cellObj = JSONObject().apply {
                                put("paragraphs", JSONArray(cell.paragraphs))
                                put("gridSpan", cell.gridSpan)
                                cell.vMerge?.let { put("vMerge", it) }
                                cell.widthDxa?.let { put("widthDxa", it) }
                                cell.backgroundHex?.let { put("backgroundHex", it) }
                                cell.horizontalAlignment?.let { put("horizontalAlignment", it) }
                                cell.verticalAlignment?.let { put("verticalAlignment", it) }
                                put("isHeader", cell.isHeader)
                                put("bold", cell.bold)
                                if (cell.nestedTables.isNotEmpty()) {
                                    put("nestedTables", structuredTablesToJsonArray(cell.nestedTables))
                                }
                            }
                            cellsArr.put(cellObj)
                        }
                        put("cells", cellsArr)
                    }
                    rowsArr.put(rowObj)
                }
                put("rows", rowsArr)
            }
        }

        private fun jsonArrayToStructuredTables(arr: JSONArray?): List<StructuredTable> {
            if (arr == null) return emptyList()
            val list = mutableListOf<StructuredTable>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                list.add(jsonToStructuredTable(obj))
            }
            return list
        }

        private fun jsonToStructuredTable(obj: JSONObject): StructuredTable {
            val widthsArr = obj.optJSONArray("columnWidthsDxa")
            val widths = mutableListOf<Int>()
            if (widthsArr != null) {
                for (i in 0 until widthsArr.length()) {
                    widths.add(widthsArr.optInt(i, 0))
                }
            }
            val rowsArr = obj.optJSONArray("rows")
            val rows = mutableListOf<StructuredTableRow>()
            if (rowsArr != null) {
                for (r in 0 until rowsArr.length()) {
                    val rObj = rowsArr.optJSONObject(r) ?: continue
                    val cellsArr = rObj.optJSONArray("cells")
                    val cells = mutableListOf<StructuredTableCell>()
                    if (cellsArr != null) {
                        for (c in 0 until cellsArr.length()) {
                            val cObj = cellsArr.optJSONObject(c) ?: continue
                            val pArr = cObj.optJSONArray("paragraphs")
                            val paras = mutableListOf<String>()
                            if (pArr != null) {
                                for (p in 0 until pArr.length()) {
                                    paras.add(pArr.optString(p, ""))
                                }
                            }
                            cells.add(
                                StructuredTableCell(
                                    paragraphs = paras,
                                    gridSpan = cObj.optInt("gridSpan", 1).coerceAtLeast(1),
                                    vMerge = cObj.optString("vMerge", "").takeIf { it.isNotEmpty() },
                                    widthDxa = if (cObj.has("widthDxa")) cObj.optInt("widthDxa") else null,
                                    backgroundHex = cObj.optString("backgroundHex", "").takeIf { it.isNotEmpty() },
                                    horizontalAlignment = cObj.optString("horizontalAlignment", "").takeIf { it.isNotEmpty() },
                                    verticalAlignment = cObj.optString("verticalAlignment", "").takeIf { it.isNotEmpty() },
                                    isHeader = cObj.optBoolean("isHeader", false),
                                    bold = cObj.optBoolean("bold", true),
                                    nestedTables = jsonArrayToStructuredTables(cObj.optJSONArray("nestedTables"))
                                )
                            )
                        }
                    }
                    rows.add(
                        StructuredTableRow(
                            cells = cells,
                            isHeader = rObj.optBoolean("isHeader", false),
                            cantSplit = rObj.optBoolean("cantSplit", false),
                            heightDxa = if (rObj.has("heightDxa")) rObj.optInt("heightDxa") else null
                        )
                    )
                }
            }
            return StructuredTable(
                id = obj.optString("id", "").ifBlank { java.util.UUID.randomUUID().toString() },
                title = obj.optString("title", ""),
                rows = rows,
                columnWidthsDxa = widths,
                isRtl = obj.optBoolean("isRtl", true),
                hasBorders = obj.optBoolean("hasBorders", true),
                borderColorHex = obj.optString("borderColorHex", "000000"),
                borderSizeEighthsPt = obj.optInt("borderSizeEighthsPt", 4),
                sourceStageKey = obj.optString("sourceStageKey", "").takeIf { it.isNotEmpty() },
                sourceField = obj.optString("sourceField", "content")
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
