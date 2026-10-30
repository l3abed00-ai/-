package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.model.FieldSource
import com.example.model.JadhadhaCategories
import com.example.model.JadhadhaInfo
import com.example.model.JadhadhaPlan
import com.example.model.PedagogicalComponent
import com.example.model.StageCatalog
import com.example.model.StageData
import com.example.model.SuggestedObjectives
import com.example.ui.AppTab
import com.example.ui.components.AppButton
import com.example.ui.components.AppOutlinedButton
import com.example.ui.components.AppTextButton
import com.example.ui.components.AppTonalButton
import com.example.ui.components.ButtonLabel
import com.example.ui.components.SectionHeader
import com.example.ui.components.ltr

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ModelEditorScreen(
    plan: JadhadhaPlan,
    onUpdateTopic: (String) -> Unit,
    onUpdateCategory: (String) -> Unit = {},
    onUpdateInfo: (JadhadhaInfo) -> Unit,
    onAddItem: (stageKey: String, field: String, text: String) -> Unit,
    onEditItem: (stageKey: String, field: String, index: Int, newText: String) -> Unit = { _, _, _, _ -> },
    onRemoveItem: (stageKey: String, field: String, index: Int) -> Unit,
    onNavigateToTab: (AppTab) -> Unit,
    onSavePlan: () -> Unit = {},
    isSuggestingObjectives: Boolean = false,
    suggestedObjectives: SuggestedObjectives? = null,
    onSuggestObjectives: (String) -> Unit = {},
    onApplySuggestedObjectives: (SuggestedObjectives, Boolean) -> Unit = { _, _ -> },
    onDismissSuggestedObjectives: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showEditInfoDialog by remember { mutableStateOf(false) }
    var showSuggestObjectivesDialog by remember { mutableStateOf(false) }
    var selectedSessionFilter by remember { mutableStateOf<Int?>(null) }


    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Banner / Actions
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = plan.componentType.title,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Category,
                                            contentDescription = null,
                                            modifier = Modifier.size(13.dp),
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "تصنيف: ${plan.category}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "«${plan.topic}»",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            AppTonalButton(
                                onClick = { showSuggestObjectivesDialog = true },
                                modifier = Modifier.testTag("btn_suggest_content_header")
                            ) {
                                ButtonLabel(
                                    text = "اقتراح محتوى (${ltr("Gemini 3.8 Flash")})",
                                    icon = Icons.Default.AutoAwesome,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }

                        IconButton(
                            onClick = { showEditInfoDialog = true },
                            modifier = Modifier
                                .size(48.dp)
                                .minimumInteractiveComponentSize()
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface)
                                .testTag("edit_header_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "تعديل الترويسة",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        InfoBadge(text = plan.info.level)
                        InfoBadge(text = plan.info.unit)
                        InfoBadge(text = plan.info.domain)
                        InfoBadge(text = plan.info.duration)
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AppButton(
                            onClick = onSavePlan,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_save_plan_db"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            ButtonLabel(
                                text = "حفظ الجذاذة",
                                icon = Icons.Default.Save
                            )
                        }

                        AppTonalButton(
                            onClick = { onNavigateToTab(AppTab.EXPORT_DOCX) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("export_docx_button")
                        ) {
                            ButtonLabel(
                                text = "تصدير ${ltr("Word")}",
                                icon = Icons.Default.FileDownload
                            )
                        }

                        AppOutlinedButton(
                            onClick = { onNavigateToTab(AppTab.COMPONENTS_HUB) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("change_component_button")
                        ) {
                            ButtonLabel(
                                text = "المكونات",
                                icon = Icons.Default.Layers,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        // Filter chips according to component sessions
        item {
            when (plan.componentType) {
                PedagogicalComponent.READING -> {
                    Column {
                        Text(
                            text = "عرض الحصص:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            FilterChip(
                                selected = selectedSessionFilter == null,
                                onClick = { selectedSessionFilter = null },
                                label = {
                                    Text(
                                        text = "كامل الجذاذة (الحصتان)",
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                            FilterChip(
                                selected = selectedSessionFilter == 1,
                                onClick = { selectedSessionFilter = 1 },
                                label = {
                                    Text(
                                        text = "الحصة 1",
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                            FilterChip(
                                selected = selectedSessionFilter == 2,
                                onClick = { selectedSessionFilter = 2 },
                                label = {
                                    Text(
                                        text = "الحصة 2",
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                        }
                    }
                }

                PedagogicalComponent.LANGUAGE -> {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "مراحل الدرس اللغوي: تقويم تشخيصي، ملاحظة الأمثلة ووصفها وتحليلها، الاستنتاج، وتطبيق",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                PedagogicalComponent.EXPRESSION -> {
                    Column {
                        Text(
                            text = "تصفية حصص التعبير والإنشاء (4 حصص):",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            FilterChip(
                                selected = selectedSessionFilter == null,
                                onClick = { selectedSessionFilter = null },
                                label = {
                                    Text(
                                        text = "الكل (4 حصص)",
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                            FilterChip(
                                selected = selectedSessionFilter == 1,
                                onClick = { selectedSessionFilter = 1 },
                                label = {
                                    Text(
                                        text = "ح1: الاكتساب",
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                            FilterChip(
                                selected = selectedSessionFilter == 2,
                                onClick = { selectedSessionFilter = 2 },
                                label = {
                                    Text(
                                        text = "ح2: التطبيق",
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                            FilterChip(
                                selected = selectedSessionFilter == 3,
                                onClick = { selectedSessionFilter = 3 },
                                label = {
                                    Text(
                                        text = "ح3: الإنتاج",
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                            FilterChip(
                                selected = selectedSessionFilter == 4,
                                onClick = { selectedSessionFilter = 4 },
                                label = {
                                    Text(
                                        text = "ح4: التصحيح",
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }

        // Header Table Inspection Card
        item {
            HeaderSummaryCard(
                info = plan.info,
                onEditClick = { showEditInfoDialog = true }
            )
        }

        // Stages List for the active component
        val componentStages = StageCatalog.getStagesForComponent(plan.componentType)
        val filteredStages = componentStages.filter {
            selectedSessionFilter == null || it.session == selectedSessionFilter
        }

        items(filteredStages, key = { it.key }) { stageMeta ->
            val stageData = plan.stages[stageMeta.key] ?: StageData()
            StageCard(
                meta = stageMeta,
                data = stageData,
                onAddItem = { field, text -> onAddItem(stageMeta.key, field, text) },
                onEditItem = { field, idx, newText -> onEditItem(stageMeta.key, field, idx, newText) },
                onRemoveItem = { field, idx -> onRemoveItem(stageMeta.key, field, idx) }
            )
        }

        item {
            Spacer(modifier = Modifier.height(84.dp))
        }
    }

    if (showEditInfoDialog) {
        EditInfoDialog(
            currentTopic = plan.topic,
            currentCategory = plan.category,
            currentInfo = plan.info,
            isSuggestingObjectives = isSuggestingObjectives,
            suggestedObjectives = suggestedObjectives,
            onSuggestObjectives = onSuggestObjectives,
            onDismiss = { showEditInfoDialog = false },
            onConfirm = { newTopic, newCategory, newInfo ->
                onUpdateTopic(newTopic)
                onUpdateCategory(newCategory)
                onUpdateInfo(newInfo)
                showEditInfoDialog = false
            }
        )
    }

    if (showSuggestObjectivesDialog) {
        SuggestObjectivesDialog(
            initialTopic = plan.topic,
            component = plan.componentType,
            level = plan.info.level,
            isSuggesting = isSuggestingObjectives,
            suggested = suggestedObjectives,
            onDismiss = {
                showSuggestObjectivesDialog = false
                onDismissSuggestedObjectives()
            },
            onRequestSuggest = { topic ->
                onSuggestObjectives(topic)
            },
            onApply = { suggestions, applyToStages ->
                onApplySuggestedObjectives(suggestions, applyToStages)
                showSuggestObjectivesDialog = false
            }
        )
    }
}

@Composable
fun InfoBadge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun HeaderSummaryCard(
    info: JadhadhaInfo,
    onEditClick: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.School,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "جدول الترويسة والكفايات المستهدفة",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier
                        .size(48.dp)
                        .minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "طي" else "توسيع"
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    HeaderInfoRow("المجال:", info.domain)
                    HeaderInfoRow("المرجع:", info.reference)
                    HeaderInfoRow("الأستاذ:", info.teacherName)
                    HeaderInfoRow("المؤسسة:", info.schoolName)
                    HeaderInfoRow("الوسائل التعليمية:", info.materials)
                    HeaderInfoRow("كفاية تواصلية:", info.communicativeComp)
                    HeaderInfoRow("كفاية منهجية:", info.methodologicalComp)
                    HeaderInfoRow("كفاية معرفية:", info.culturalComp)

                    AppTextButton(
                        onClick = onEditClick,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        ButtonLabel(
                            text = "تعديل معطيات الترويسة",
                            icon = Icons.Default.Edit
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(130.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StageCard(
    meta: com.example.model.StageMetadata,
    data: StageData,
    onAddItem: (field: String, text: String) -> Unit,
    onEditItem: (field: String, index: Int, newText: String) -> Unit = { _, _, _ -> },
    onRemoveItem: (field: String, index: Int) -> Unit
) {
    var selectedField by remember { mutableStateOf("content") }
    var newItemText by remember { mutableStateOf("") }
    var isAdding by remember { mutableStateOf(false) }

    val allFields = listOf("content", "teacher", "abil", "eval")
    val isWholeStageDefault = allFields.all { data.sourceFor(it) == FieldSource.DEFAULT }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("stage_card_${meta.key}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Stage Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(
                                when (meta.session) {
                                    1 -> MaterialTheme.colorScheme.primaryContainer
                                    2 -> MaterialTheme.colorScheme.secondaryContainer
                                    3 -> MaterialTheme.colorScheme.tertiaryContainer
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "ح${meta.session}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = when (meta.session) {
                                1 -> MaterialTheme.colorScheme.onPrimaryContainer
                                2 -> MaterialTheme.colorScheme.onSecondaryContainer
                                3 -> MaterialTheme.colorScheme.onTertiaryContainer
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = meta.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (isWholeStageDefault) {
                                FieldSourceBadge(
                                    source = FieldSource.DEFAULT,
                                    testTag = "stage_default_badge_${meta.key}"
                                )
                            }
                        }
                        Text(
                            text = meta.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Sub-field Tabs with source indicator
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val fields = listOf(
                    "content" to "المحتوى (${data.content.size})",
                    "teacher" to "أنشطة المدرس (${data.teacher.size})",
                    "abil" to "القدرات (${data.abil.size})",
                    "eval" to "التقويم (${data.eval.size})"
                )

                fields.forEach { (fieldKey, label) ->
                    val fieldSource = data.sourceFor(fieldKey)
                    FilterChip(
                        selected = selectedField == fieldKey,
                        onClick = { selectedField = fieldKey },
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (fieldSource != null) {
                                    FieldSourceBadge(
                                        source = fieldSource,
                                        compact = true,
                                        testTag = "tab_source_badge_${meta.key}_$fieldKey"
                                    )
                                }
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Active Field Source Strip
            val activeFieldSource = data.sourceFor(selectedField)
            if (activeFieldSource != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "مصدر بيانات هذه الخانة:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FieldSourceBadge(
                            source = activeFieldSource,
                            testTag = "active_source_badge_${meta.key}_$selectedField"
                        )
                    }
                    if (activeFieldSource == FieldSource.DEFAULT) {
                        Text(
                            text = "تزول شارة «افتراضي» تلقائياً عند التعديل",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFB45309)
                        )
                    }
                }
            }

            // List of items for selected field
            val itemsToShow = when (selectedField) {
                "content" -> data.content
                "teacher" -> data.teacher
                "abil" -> data.abil
                "eval" -> data.eval
                else -> emptyList()
            }

            if (itemsToShow.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "لا توجد عناصر مضافة في هذا القسم بعد.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsToShow.forEachIndexed { idx, itemText ->
                        ItemRow(
                            text = itemText,
                            prefix = when (selectedField) {
                                "teacher", "eval" -> "+ "
                                "abil" -> "- "
                                else -> "• "
                            },
                            onEdit = { updatedText ->
                                onEditItem(selectedField, idx, updatedText)
                            },
                            onDelete = { onRemoveItem(selectedField, idx) }
                        )
                    }
                }
            }

            // Quick Add Row
            Spacer(modifier = Modifier.height(8.dp))
            if (isAdding) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newItemText,
                        onValueChange = { newItemText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                when (selectedField) {
                                    "teacher" -> "يطرح سؤالا حول..."
                                    "abil" -> "القدرة على..."
                                    "content" -> "عنوان: تفصيل..."
                                    else -> "مؤشر تقويم..."
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        singleLine = false,
                        maxLines = 2
                    )

                    AppButton(
                        onClick = {
                            if (newItemText.isNotBlank()) {
                                onAddItem(selectedField, newItemText.trim())
                                newItemText = ""
                                isAdding = false
                            }
                        }
                    ) {
                        ButtonLabel(text = "إضافة")
                    }

                    IconButton(
                        onClick = { isAdding = false },
                        modifier = Modifier
                            .size(48.dp)
                            .minimumInteractiveComponentSize()
                    ) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "إلغاء")
                    }
                }
            } else {
                AppTextButton(
                    onClick = { isAdding = true },
                    modifier = Modifier.align(Alignment.Start)
                ) {
                    ButtonLabel(
                        text = "إضافة عنصر جديد في ${
                            when (selectedField) {
                                "content" -> "المحتوى"
                                "teacher" -> "أنشطة المدرس"
                                "abil" -> "القدرات"
                                else -> "التقويم"
                            }
                        }",
                        icon = Icons.Default.Add
                    )
                }
            }
        }
    }
}

@Composable
fun FieldSourceBadge(
    source: FieldSource,
    compact: Boolean = false,
    testTag: String? = null
) {
    val (bgColor, textColor, borderColor) = when (source) {
        FieldSource.DEFAULT -> Triple(Color(0xFFFEF3C7), Color(0xFF92400E), Color(0xFFD97706))
        FieldSource.FILE -> Triple(Color(0xFFD1FAE5), Color(0xFF065F46), Color(0xFF059669))
        FieldSource.AI -> Triple(Color(0xFFEDE9FE), Color(0xFF5B21B6), Color(0xFF7C3AED))
        FieldSource.USER -> Triple(Color(0xFFDBEAFE), Color(0xFF1E40AF), Color(0xFF2563EB))
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier
            .border(0.75.dp, borderColor.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
    ) {
        Text(
            text = source.arabicLabel,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(
                horizontal = if (compact) 5.dp else 8.dp,
                vertical = if (compact) 1.dp else 2.dp
            )
        )
    }
}

@Composable
fun ItemRow(
    text: String,
    prefix: String,
    onEdit: (String) -> Unit = {},
    onDelete: () -> Unit
) {
    var isEditing by remember(text) { mutableStateOf(false) }
    var editedText by remember(text) { mutableStateOf(text) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(8.dp)
    ) {
        if (isEditing) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = editedText,
                    onValueChange = { editedText = it },
                    modifier = Modifier.weight(1f),
                    singleLine = false,
                    maxLines = 3
                )
                IconButton(
                    onClick = {
                        if (editedText.isNotBlank()) {
                            onEdit(editedText.trim())
                            isEditing = false
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "حفظ التعديل",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = {
                        editedText = text
                        isEditing = false
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "إلغاء التعديل",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = prefix,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { isEditing = true },
                    modifier = Modifier
                        .size(48.dp)
                        .minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "تعديل",
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(48.dp)
                        .minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "حذف",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun EditInfoDialog(
    currentTopic: String,
    currentCategory: String = JadhadhaCategories.READING,
    currentInfo: JadhadhaInfo,
    onDismiss: () -> Unit,
    onConfirm: (String, String, JadhadhaInfo) -> Unit,
    isSuggestingObjectives: Boolean = false,
    suggestedObjectives: SuggestedObjectives? = null,
    onSuggestObjectives: (String) -> Unit = {}
) {
    var topic by remember { mutableStateOf(currentTopic) }
    var category by remember { mutableStateOf(currentCategory) }
    var level by remember { mutableStateOf(currentInfo.level) }
    var unit by remember { mutableStateOf(currentInfo.unit) }
    var domain by remember { mutableStateOf(currentInfo.domain) }
    var component by remember { mutableStateOf(currentInfo.component) }
    var duration by remember { mutableStateOf(currentInfo.duration) }
    var materials by remember { mutableStateOf(currentInfo.materials) }
    var reference by remember { mutableStateOf(currentInfo.reference) }
    var teacherName by remember { mutableStateOf(currentInfo.teacherName) }
    var schoolName by remember { mutableStateOf(currentInfo.schoolName) }
    var commComp by remember { mutableStateOf(currentInfo.communicativeComp) }
    var methComp by remember { mutableStateOf(currentInfo.methodologicalComp) }
    var cultComp by remember { mutableStateOf(currentInfo.culturalComp) }

    // Auto-update competencies when suggestions arrive
    LaunchedEffect(suggestedObjectives) {
        suggestedObjectives?.let { suggestions ->
            if (suggestions.communicativeComp.isNotBlank()) commComp = suggestions.communicativeComp
            if (suggestions.methodologicalComp.isNotBlank()) methComp = suggestions.methodologicalComp
            if (suggestions.culturalComp.isNotBlank()) cultComp = suggestions.culturalComp
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = "تعديل معطيات الجذاذة النموذجية",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = topic,
                                onValueChange = { topic = it },
                                label = { Text("موضوع الدرس / المهارة *") },
                                placeholder = { Text("أدخل عنوان أو موضوع الدرس...") },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("dialog_topic_field")
                            )

                            // Dedicated "Suggest Content" button
                            AppButton(
                                onClick = { onSuggestObjectives(topic) },
                                enabled = topic.isNotBlank() && !isSuggestingObjectives,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.tertiary,
                                    contentColor = MaterialTheme.colorScheme.onTertiary
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("dialog_btn_suggest_content")
                            ) {
                                if (isSuggestingObjectives) {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.onTertiary,
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "جارٍ توليد الأهداف عبر ${ltr("Gemini 3.8 Flash")}...",
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                } else {
                                    ButtonLabel(
                                        text = "اقتراح محتوى (توليد الأهداف بـ ${ltr("Gemini 3.8 Flash")})",
                                        icon = Icons.Default.AutoAwesome,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }

                            if (suggestedObjectives != null) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "تم تحديث الكفايات والأهداف المقترحة بنجاح!",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "تصنيف الجذاذة في قاعدة البيانات (${ltr("Category")}) *",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            @OptIn(ExperimentalLayoutApi::class)
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                JadhadhaCategories.ALL.forEach { catOption ->
                                    FilterChip(
                                        selected = category == catOption,
                                        onClick = {
                                            category = catOption
                                            if (component.isBlank() || JadhadhaCategories.ALL.contains(component)) {
                                                component = catOption
                                            }
                                        },
                                        label = {
                                            Text(
                                                text = catOption,
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1,
                                                softWrap = false,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = category,
                                onValueChange = { category = it },
                                label = { Text("اسم التصنيف (تخصيص)") },
                                placeholder = { Text("مثال: نصوص قرائية، درس لغوي، تعبير وإنشاء...") },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("dialog_category_field")
                            )
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = component,
                            onValueChange = { component = it },
                            label = { Text("المكوّن (النصوص القرائية / الدرس اللغوي / التعبير والإنشاء)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = level,
                            onValueChange = { level = it },
                            label = { Text("المستوى الدراسي") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = unit,
                            onValueChange = { unit = it },
                            label = { Text("الوحدة") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = domain,
                            onValueChange = { domain = it },
                            label = { Text("المجال") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = duration,
                            onValueChange = { duration = it },
                            label = { Text("الغلاف الزمني") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = materials,
                            onValueChange = { materials = it },
                            label = { Text("الوسائل التعليمية") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = reference,
                            onValueChange = { reference = it },
                            label = { Text("المرجع المعتمد") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = teacherName,
                            onValueChange = { teacherName = it },
                            label = { Text("الأستاذ(ة)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = schoolName,
                            onValueChange = { schoolName = it },
                            label = { Text("المؤسسة") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = commComp,
                            onValueChange = { commComp = it },
                            label = { Text("كفاية تواصلية") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = methComp,
                            onValueChange = { methComp = it },
                            label = { Text("كفاية منهجية") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = cultComp,
                            onValueChange = { cultComp = it },
                            label = { Text("كفاية ثقافية / معرفية") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    AppTextButton(onClick = onDismiss) {
                        ButtonLabel(text = "إلغاء", fontWeight = FontWeight.Medium)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    AppButton(
                        onClick = {
                            val newInfo = currentInfo.copy(
                                level = level.trim(),
                                unit = unit.trim(),
                                domain = domain.trim(),
                                component = component.trim(),
                                duration = duration.trim(),
                                materials = materials.trim(),
                                reference = reference.trim(),
                                teacherName = teacherName.trim(),
                                schoolName = schoolName.trim(),
                                communicativeComp = commComp.trim(),
                                methodologicalComp = methComp.trim(),
                                culturalComp = cultComp.trim()
                            )
                            val finalCat = category.trim().ifBlank { JadhadhaCategories.READING }
                            onConfirm(topic.trim(), finalCat, newInfo)
                        }
                    ) {
                        ButtonLabel(text = "حفظ التعديلات")
                    }
                }
            }
        }
    }
}

@Composable
fun SuggestObjectivesDialog(
    initialTopic: String,
    component: PedagogicalComponent,
    level: String,
    isSuggesting: Boolean,
    suggested: SuggestedObjectives?,
    onDismiss: () -> Unit,
    onRequestSuggest: (String) -> Unit,
    onApply: (SuggestedObjectives, Boolean) -> Unit
) {
    var topicInput by remember { mutableStateOf(initialTopic) }
    var applyToStages by remember { mutableStateOf(true) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "اقتراح محتوى وأهداف تعليمية",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "مدعوم بـ ${ltr("Gemini 3.8 Flash")}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .minimumInteractiveComponentSize()
                    ) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "إغلاق")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Topic Input Field
                OutlinedTextField(
                    value = topicInput,
                    onValueChange = { topicInput = it },
                    label = { Text("موضوع الدرس / المهارة المستهدفة") },
                    placeholder = { Text("مثال: اسم الفاعل وعمله، المنفى، مهارة الوصف...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("suggest_topic_input"),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Action to Generate
                AppButton(
                    onClick = { onRequestSuggest(topicInput) },
                    enabled = topicInput.isNotBlank() && !isSuggesting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("btn_trigger_suggest_objectives")
                ) {
                    if (isSuggesting) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onTertiary,
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "جارٍ توليد الأهداف عبر ${ltr("Gemini 3.8 Flash")}...",
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        ButtonLabel(
                            text = if (suggested == null) "توليد واقتراح الأهداف الآن" else "إعادة اقتراح الأهداف بـ ${ltr("Gemini 3.8 Flash")}",
                            icon = Icons.Default.AutoAwesome
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Content Area
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (suggested == null && !isSuggesting) {
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Psychology,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "يقوم نموذج ${ltr("Gemini 3.8 Flash")} بدراسة عنوان وموضوع الدرس ويقترح:",
                                        style = MaterialTheme.typography.bodySmall,
                                        textAlign = TextAlign.Center,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "• الكفايات الأساسية (تواصلية، منهجية، ثقافية)\n• الأهداف الإجرائية (معرفية، مهارية، وجدانية)\n• القدرات المرحلية الخاصة بكل مرحلة ديداكتيكية",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    if (suggested != null) {
                        item {
                            // Section: الكفايات المستهدفة
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "🎯 الكفايات المستهدفة الرسمية:",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    ObjectiveItem(label = "الكفاية التواصلية", text = suggested.communicativeComp)
                                    ObjectiveItem(label = "الكفاية المنهجية", text = suggested.methodologicalComp)
                                    ObjectiveItem(label = "الكفاية الثقافية", text = suggested.culturalComp)
                                }
                            }
                        }

                        if (suggested.cognitiveObjectives.isNotEmpty() || suggested.skillObjectives.isNotEmpty() || suggested.affectiveObjectives.isNotEmpty()) {
                            item {
                                // Section: الأهداف التعليمية الإجرائية
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = "📋 الأهداف التعليمية الإجرائية المقترحة:",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                        suggested.cognitiveObjectives.forEach {
                                            BulletObjectiveItem(badge = "معرفي", text = it)
                                        }
                                        suggested.skillObjectives.forEach {
                                            BulletObjectiveItem(badge = "مهاري", text = it)
                                        }
                                        suggested.affectiveObjectives.forEach {
                                            BulletObjectiveItem(badge = "وجداني", text = it)
                                        }
                                    }
                                }
                            }
                        }

                        if (suggested.stageAbilities.isNotEmpty()) {
                            item {
                                // Section: القدرات المرحلية
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = "⚡ القدرات المرحلية المقترحة (${suggested.stageAbilities.size} مراحل):",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.tertiary
                                        )
                                        suggested.stageAbilities.forEach { (stageKey, abilities) ->
                                            val stageTitle = StageCatalog.getByKey(stageKey)?.title ?: stageKey
                                            Text(
                                                text = "• $stageTitle: ${abilities.joinToString(" / ")}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Checkbox(
                                    checked = applyToStages,
                                    onCheckedChange = { applyToStages = it }
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "تضمين وتحديث القدرات المرحلية في بطاقات مراحل الجذاذة",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Bottom Dialog Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppOutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        ButtonLabel(text = "إلغاء", fontWeight = FontWeight.Medium)
                    }

                    AppButton(
                        onClick = {
                            suggested?.let {
                                onApply(it, applyToStages)
                            }
                        },
                        enabled = suggested != null,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("btn_apply_suggested_objectives")
                    ) {
                        ButtonLabel(
                            text = "تطبيق على الجذاذة",
                            icon = Icons.Default.Check
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ObjectiveItem(label: String, text: String) {
    Column {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun BulletObjectiveItem(badge: String, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
            shape = RoundedCornerShape(4.dp)
        ) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

