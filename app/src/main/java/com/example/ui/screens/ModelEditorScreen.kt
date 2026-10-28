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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.model.JadhadhaInfo
import com.example.model.JadhadhaPlan
import com.example.model.PedagogicalComponent
import com.example.model.StageCatalog
import com.example.model.StageData
import com.example.ui.AppTab
import com.example.ui.components.SectionHeader

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ModelEditorScreen(
    plan: JadhadhaPlan,
    onUpdateTopic: (String) -> Unit,
    onUpdateInfo: (JadhadhaInfo) -> Unit,
    onAddItem: (stageKey: String, field: String, text: String) -> Unit,
    onRemoveItem: (stageKey: String, field: String, index: Int) -> Unit,
    onNavigateToTab: (AppTab) -> Unit,
    modifier: Modifier = Modifier
) {
    var showEditInfoDialog by remember { mutableStateOf(false) }
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
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
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "الجذاذة النموذجية الرسمية",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "«${plan.topic}»",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }

                        IconButton(
                            onClick = { showEditInfoDialog = true },
                            modifier = Modifier
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
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onNavigateToTab(AppTab.EXPORT_DOCX) },
                            modifier = Modifier
                                .weight(1.2f)
                                .testTag("export_docx_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(imageVector = Icons.Default.FileDownload, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("تصدير Word (.docx)", fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = { onNavigateToTab(AppTab.COMPONENTS_HUB) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("change_component_button")
                        ) {
                            Icon(imageVector = Icons.Default.Layers, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("ركن المكونات")
                        }
                    }
                }
            }
        }

        // Filter chips according to component sessions
        item {
            when (plan.componentType) {
                PedagogicalComponent.READING -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "عرض الحصص:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        FilterChip(
                            selected = selectedSessionFilter == null,
                            onClick = { selectedSessionFilter = null },
                            label = { Text("كامل الجذاذة (الحصتان)") }
                        )
                        FilterChip(
                            selected = selectedSessionFilter == 1,
                            onClick = { selectedSessionFilter = 1 },
                            label = { Text("الحصة 1") }
                        )
                        FilterChip(
                            selected = selectedSessionFilter == 2,
                            onClick = { selectedSessionFilter = 2 },
                            label = { Text("الحصة 2") }
                        )
                    }
                }

                PedagogicalComponent.LANGUAGE -> {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "مراحل الدرس اللغوي: التقويم التشخيصي، الملاحظة، الوصف والتحليل، الاستنتاج، والتطبيقات",
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
                                label = { Text("الكل (4 حصص)") }
                            )
                            FilterChip(
                                selected = selectedSessionFilter == 1,
                                onClick = { selectedSessionFilter = 1 },
                                label = { Text("ح1: الاكتساب") }
                            )
                            FilterChip(
                                selected = selectedSessionFilter == 2,
                                onClick = { selectedSessionFilter = 2 },
                                label = { Text("ح2: التطبيق") }
                            )
                            FilterChip(
                                selected = selectedSessionFilter == 3,
                                onClick = { selectedSessionFilter = 3 },
                                label = { Text("ح3: الإنتاج") }
                            )
                            FilterChip(
                                selected = selectedSessionFilter == 4,
                                onClick = { selectedSessionFilter = 4 },
                                label = { Text("ح4: التصحيح") }
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
            currentInfo = plan.info,
            onDismiss = { showEditInfoDialog = false },
            onConfirm = { newTopic, newInfo ->
                onUpdateTopic(newTopic)
                onUpdateInfo(newInfo)
                showEditInfoDialog = false
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

                IconButton(onClick = { expanded = !expanded }) {
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
                    HeaderInfoRow("الوسائل الديداكتيكية:", info.materials)
                    HeaderInfoRow("كفاية تواصلية:", info.communicativeComp)
                    HeaderInfoRow("كفاية منهجية:", info.methodologicalComp)
                    HeaderInfoRow("كفاية ثقافية / معرفية:", info.culturalComp)

                    TextButton(
                        onClick = onEditClick,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(imageVector = Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("تعديل معطيات الترويسة")
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StageCard(
    meta: com.example.model.StageMetadata,
    data: StageData,
    onAddItem: (field: String, text: String) -> Unit,
    onRemoveItem: (field: String, index: Int) -> Unit
) {
    var selectedField by remember { mutableStateOf("content") }
    var newItemText by remember { mutableStateOf("") }
    var isAdding by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
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
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                        Text(
                            text = meta.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = meta.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Sub-field Tabs
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val fields = listOf(
                    "content" to "المحتوى (${data.content.size})",
                    "teacher" to "أنشطة المدرس (${data.teacher.size})",
                    "abil" to "القدرات (${data.abil.size})",
                    "eval" to "التقويم (${data.eval.size})"
                )

                fields.forEach { (fieldKey, label) ->
                    FilterChip(
                        selected = selectedField == fieldKey,
                        onClick = { selectedField = fieldKey },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

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

                    Button(
                        onClick = {
                            if (newItemText.isNotBlank()) {
                                onAddItem(selectedField, newItemText.trim())
                                newItemText = ""
                                isAdding = false
                            }
                        }
                    ) {
                        Text("إضافة")
                    }

                    IconButton(onClick = { isAdding = false }) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "إلغاء")
                    }
                }
            } else {
                TextButton(
                    onClick = { isAdding = true },
                    modifier = Modifier.align(Alignment.Start)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "إضافة عنصر جديد في ${
                            when (selectedField) {
                                "content" -> "المحتوى"
                                "teacher" -> "أنشطة المدرس"
                                "abil" -> "القدرات"
                                else -> "التقويم"
                            }
                        }"
                    )
                }
            }
        }
    }
}

@Composable
fun ItemRow(
    text: String,
    prefix: String,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
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
                onClick = onDelete,
                modifier = Modifier.size(24.dp)
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

@Composable
fun EditInfoDialog(
    currentTopic: String,
    currentInfo: JadhadhaInfo,
    onDismiss: () -> Unit,
    onConfirm: (String, JadhadhaInfo) -> Unit
) {
    var topic by remember { mutableStateOf(currentTopic) }
    var level by remember { mutableStateOf(currentInfo.level) }
    var unit by remember { mutableStateOf(currentInfo.unit) }
    var domain by remember { mutableStateOf(currentInfo.domain) }
    var component by remember { mutableStateOf(currentInfo.component) }
    var duration by remember { mutableStateOf(currentInfo.duration) }
    var materials by remember { mutableStateOf(currentInfo.materials) }
    var commComp by remember { mutableStateOf(currentInfo.communicativeComp) }
    var methComp by remember { mutableStateOf(currentInfo.methodologicalComp) }
    var cultComp by remember { mutableStateOf(currentInfo.culturalComp) }

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
                        OutlinedTextField(
                            value = topic,
                            onValueChange = { topic = it },
                            label = { Text("موضوع الدرس / المهارة") },
                            modifier = Modifier.fillMaxWidth()
                        )
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
                            label = { Text("الوسائل الديداكتيكية / المرجع") },
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
                    TextButton(onClick = onDismiss) {
                        Text("إلغاء")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val newInfo = currentInfo.copy(
                                level = level.trim(),
                                unit = unit.trim(),
                                domain = domain.trim(),
                                component = component.trim(),
                                duration = duration.trim(),
                                materials = materials.trim(),
                                communicativeComp = commComp.trim(),
                                methodologicalComp = methComp.trim(),
                                culturalComp = cultComp.trim()
                            )
                            onConfirm(topic.trim(), newInfo)
                        }
                    ) {
                        Text("حفظ التعديلات")
                    }
                }
            }
        }
    }
}
