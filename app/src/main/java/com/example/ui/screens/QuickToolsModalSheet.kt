package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.GeminiAiService
import com.example.ui.AppTab
import com.example.ui.components.AppButton
import com.example.ui.components.ButtonLabel
import com.example.ui.components.ltr

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickToolsModalSheet(
    sheetState: SheetState,
    onDismissRequest: () -> Unit,
    onSelectTab: (AppTab) -> Unit,
    currentApiKey: String = "",
    onSaveApiKey: ((String) -> Unit)? = null,
    onClearAllStoredData: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val initialKey = remember(currentApiKey) {
        currentApiKey.ifBlank { GeminiAiService.getSavedApiKey(context) }
    }
    var apiKeyInput by remember(initialKey) { mutableStateOf(initialKey) }
    var isSavedConfirmationVisible by remember { mutableStateOf(false) }
    var isClearedConfirmationVisible by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = modifier.testTag("modal_quick_tools_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = "قائمة الأدوات والوظائف السريعة",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "اختر الأداة المطلوبة للعمل المباشر على الجذاذة النموذجية",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 2 Columns of Quick Action Cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                QuickToolItemCard(
                    title = "تفريغ وتحويل",
                    subtitle = "تحويل وثيقة ${ltr("Word")} أو نص خام",
                    icon = Icons.Default.SwapHoriz,
                    iconColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onDismissRequest()
                        onSelectTab(AppTab.EXTRACTOR)
                    }
                )

                QuickToolItemCard(
                    title = "الذكاء الاصطناعي",
                    subtitle = "توليد واقتراح بـ ${ltr("Gemini 3.8 Flash")}",
                    icon = Icons.Default.AutoAwesome,
                    iconColor = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onDismissRequest()
                        onSelectTab(AppTab.AI_GENERATOR)
                    }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                QuickToolItemCard(
                    title = "بنك النماذج",
                    subtitle = "نماذج وزارية جاهزة لكل مكون",
                    icon = Icons.Default.Book,
                    iconColor = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onDismissRequest()
                        onSelectTab(AppTab.SAMPLES)
                    }
                )

                QuickToolItemCard(
                    title = "أداة بايثون (${ltr(".py")})",
                    subtitle = "تصدير سكربت ${ltr("python-docx")} و ${ltr("JSON")}",
                    icon = Icons.Default.Code,
                    iconColor = Color(0xFF0284C7),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onDismissRequest()
                        onSelectTab(AppTab.PYTHON_TOOLKIT)
                    }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                QuickToolItemCard(
                    title = "تصدير ${ltr("Word DOCX")}",
                    subtitle = "إنشاء ومشاركة الملف النهائي",
                    icon = Icons.Default.FileDownload,
                    iconColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onDismissRequest()
                        onSelectTab(AppTab.EXPORT_DOCX)
                    }
                )

                QuickToolItemCard(
                    title = "معاينة ${ltr("Word")}",
                    subtitle = "عرض ورقة ${ltr("A4")} التفاعلية",
                    icon = Icons.Default.Visibility,
                    iconColor = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onDismissRequest()
                        onSelectTab(AppTab.WORD_PREVIEW)
                    }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(12.dp))

            // Manual Gemini API Key Entry & Local Persistence (SharedPreferences)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "إدخال مفتاح ${ltr("Gemini API")} يدوياً",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (isSavedConfirmationVisible) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "تم الحفظ محلياً ✓",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = {
                        apiKeyInput = it
                        isSavedConfirmationVisible = false
                    },
                    placeholder = {
                        Text(
                            text = ltr("AIzaSy..."),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("sheet_gemini_api_key_input")
                )

                AppButton(
                    onClick = {
                        val trimmed = apiKeyInput.trim()
                        GeminiAiService.saveApiKey(context, trimmed)
                        onSaveApiKey?.invoke(trimmed)
                        isSavedConfirmationVisible = true
                    },
                    modifier = Modifier.testTag("sheet_save_gemini_api_key_btn")
                ) {
                    ButtonLabel(
                        text = "حفظ",
                        icon = Icons.Default.Check,
                        iconSize = 16.dp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Red Button: حذف جميع الجذاذات المخزنة
            AppButton(
                onClick = {
                    onClearAllStoredData?.invoke()
                    isClearedConfirmationVisible = true
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sheet_clear_all_jadhadhat_btn")
            ) {
                ButtonLabel(
                    text = if (isClearedConfirmationVisible) "تم حذف جميع الجذاذات المخزنة ✓" else "حذف جميع الجذاذات المخزنة",
                    icon = Icons.Default.DeleteSweep,
                    iconSize = 18.dp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun QuickToolItemCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clickable { onClick() }
            .testTag("tool_card_${title}"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(10.dp),
                color = iconColor.copy(alpha = 0.15f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
