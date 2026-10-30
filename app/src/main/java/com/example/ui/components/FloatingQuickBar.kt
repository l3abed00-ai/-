package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.JadhadhaPlan

@Composable
fun FloatingQuickBar(
    plan: JadhadhaPlan,
    onExportDocx: (Context) -> Unit,
    onPreviewDoc: () -> Unit,
    onShowToast: (String) -> Unit,
    onSavePlan: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    visible: Boolean = true
) {
    val context = LocalContext.current

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .testTag("floating_quick_bar"),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                val fontScale = LocalDensity.current.fontScale
                val isStacked = maxWidth < 340.dp || fontScale > 1.15f
                val btnPadding = if (isStacked) {
                    PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                } else {
                    AppButtonDefaultContentPadding
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // 1. Primary Action: Export DOCX (equal weight 1f)
                    AppButton(
                        onClick = { onExportDocx(context) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("quick_action_export_docx"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = btnPadding,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        QuickBarButtonContent(
                            text = "تصدير ${ltr("Word")}",
                            icon = Icons.Default.FileDownload,
                            isStacked = isStacked
                        )
                    }

                    // 2. Secondary Action: Word Preview (equal weight 1f)
                    AppTonalButton(
                        onClick = onPreviewDoc,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("quick_action_preview"),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        contentPadding = btnPadding,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        QuickBarButtonContent(
                            text = "معاينة",
                            icon = Icons.Default.Visibility,
                            isStacked = isStacked
                        )
                    }

                    // 3. Save Action (equal weight 1f)
                    if (onSavePlan != null) {
                        AppTonalButton(
                            onClick = onSavePlan,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("quick_action_save"),
                            contentPadding = btnPadding,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            QuickBarButtonContent(
                                text = "حفظ",
                                icon = Icons.Default.Save,
                                isStacked = isStacked
                            )
                        }
                    }

                    // 4. Tertiary Action: Copy Summary (IconButton 48dp with contentDescription)
                    IconButton(
                        onClick = {
                            val summary = buildSummaryText(plan)
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Jadhadha Summary", summary)
                            clipboard.setPrimaryClip(clip)
                            onShowToast("تم نسخ ملخص الجذاذة إلى الحافظة بنجاح ✓")
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .minimumInteractiveComponentSize()
                            .testTag("quick_action_copy")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "نسخ الملخص",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickBarButtonContent(
    text: String,
    icon: ImageVector,
    isStacked: Boolean
) {
    if (isStacked) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    } else {
        ButtonLabel(
            text = text,
            icon = icon,
            iconSize = 16.dp,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
    }
}

private fun buildSummaryText(plan: JadhadhaPlan): String {
    val sb = StringBuilder()
    sb.appendLine("═══════════════════════════════════")
    sb.appendLine("جذاذة تربوية: ${plan.topic}")
    sb.appendLine("المكون: ${plan.componentType.title} | المستوى: ${plan.info.level}")
    sb.appendLine("المجال: ${plan.info.domain} | الوحدة: ${plan.info.unit}")
    sb.appendLine("المدة: ${plan.info.duration} | الوسائل: ${plan.info.materials}")
    sb.appendLine("═══════════════════════════════════")
    sb.appendLine("الكفايات المستهدفة:")
    if (plan.info.communicativeComp.isNotBlank()) sb.appendLine("- تواصلية: ${plan.info.communicativeComp}")
    if (plan.info.methodologicalComp.isNotBlank()) sb.appendLine("- منهجية: ${plan.info.methodologicalComp}")
    if (plan.info.culturalComp.isNotBlank()) sb.appendLine("- ثقافية: ${plan.info.culturalComp}")
    sb.appendLine("───────────────────────────────────")
    sb.appendLine("المراحل والأنشطة الديداكتيكية:")
    plan.stages.forEach { (key, stage) ->
        sb.appendLine("■ المرحلة ($key):")
        if (stage.eval.isNotEmpty()) sb.appendLine("  • تقويم ودعم: ${stage.eval.joinToString("؛ ")}")
        if (stage.content.isNotEmpty()) sb.appendLine("  • محتوى التعلمات: ${stage.content.joinToString("؛ ")}")
        if (stage.teacher.isNotEmpty()) sb.appendLine("  • أنشطة المدرس: ${stage.teacher.joinToString("؛ ")}")
    }
    sb.appendLine("═══════════════════════════════════")
    return sb.toString()
}
