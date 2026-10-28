package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.JadhadhaPlan

@Composable
fun FloatingQuickBar(
    plan: JadhadhaPlan,
    onExportDocx: (Context) -> Unit,
    onPreviewDoc: () -> Unit,
    onShowToast: (String) -> Unit,
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
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Primary Action: Export DOCX
                Button(
                    onClick = { onExportDocx(context) },
                    modifier = Modifier
                        .weight(1.3f)
                        .testTag("quick_action_export_docx"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "تصدير Word",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Secondary Action: Word Preview
                FilledTonalButton(
                    onClick = onPreviewDoc,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("quick_action_preview"),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "معاينة",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Tertiary Action: Copy Summary
                ElevatedButton(
                    onClick = {
                        val summary = buildSummaryText(plan)
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Jadhadha Summary", summary)
                        clipboard.setPrimaryClip(clip)
                        onShowToast("تم نسخ ملخص الجذاذة إلى الحافظة بنجاح ✓")
                    },
                    modifier = Modifier.testTag("quick_action_copy"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "نسخ الملخص",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
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
