package com.example.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.JadhadhaPlan
import com.example.model.StageCatalog
import com.example.ui.theme.AmiriFontFamily
import com.example.ui.theme.CairoFontFamily
import com.example.ui.theme.TajawalFontFamily
import com.example.ui.theme.AlmaraiFontFamily
import com.example.ui.theme.PaperBackground
import com.example.ui.theme.PaperBorder
import com.example.ui.theme.PaperHeaderBackground
import com.example.ui.theme.PaperTableRowAlt

enum class PreviewDocFont(val label: String, val fontFamily: FontFamily) {
    SAKKAL_MAJALLA("خط صقال مجلة النموذجي (10pt عريض)", AmiriFontFamily),
    AMIRI("خط النسخ الأميري", AmiriFontFamily),
    CAIRO("خط القاهرة الحديث", CairoFontFamily),
    TAJAWAL("خط تجوال المتزن", TajawalFontFamily),
    ALMARAI("خط المراعي التربوي", AlmaraiFontFamily)
}

@Composable
fun LiveWordPreviewScreen(
    plan: JadhadhaPlan,
    onExportDocx: (Context) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isFullWidthMode by remember { mutableStateOf(false) }
    var selectedDocFont by remember { mutableStateOf(PreviewDocFont.SAKKAL_MAJALLA) }
    var exportedFile by remember { mutableStateOf<java.io.File?>(null) }

    val saveAsLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument(com.example.docx.DocxGenerator.DOCX_MIME_TYPE)
    ) { targetUri ->
        if (targetUri != null) {
            val fileToSave = exportedFile ?: com.example.docx.DocxGenerator.generateDocx(context, plan, saveToDownloads = true).also {
                exportedFile = it
            }
            val ok = com.example.docx.DocxGenerator.saveFileToUri(context, fileToSave, targetUri)
            if (ok) {
                android.widget.Toast.makeText(context, "تم حفظ الجذاذة في المجلد المختار على هاتفك بنجاح!", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("screen_live_word_preview")
    ) {
        // Preview Header Bar
        Surface(
            tonalElevation = 2.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Top Row: Title, Subtitle and A4/FullWidth Mode Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    ) {
                        Text(
                            text = "معاينة وثيقة Word الرسمية",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "تُحفظ تلقائياً في: ملفات الهاتف ← التنزيلات (Downloads)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp
                        )
                    }

                    OutlinedButton(
                        onClick = { isFullWidthMode = !isFullWidthMode },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("btn_toggle_full_width")
                    ) {
                        Text(
                            text = if (isFullWidthMode) "هوامش A4" else "ملء العرض",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Wide Independent Export Button - Never squished on any screen size!
                Button(
                    onClick = {
                        onExportDocx(context)
                        exportedFile = com.example.docx.DocxGenerator.generateDocx(context, plan, saveToDownloads = true)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("btn_export_from_preview")
                ) {
                    Icon(
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "حفظ وثيقة Word في الهاتف (التنزيلات .docx)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Quick Save As / Open / Share Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val file = exportedFile ?: com.example.docx.DocxGenerator.generateDocx(context, plan, saveToDownloads = true).also {
                                exportedFile = it
                            }
                            saveAsLauncher.launch(file.name)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("حفظ باسم...", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            val file = exportedFile ?: com.example.docx.DocxGenerator.generateDocx(context, plan, saveToDownloads = true).also {
                                exportedFile = it
                            }
                            openDocxFile(context, file)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("فتح في Word", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            val file = exportedFile ?: com.example.docx.DocxGenerator.generateDocx(context, plan, saveToDownloads = true).also {
                                exportedFile = it
                            }
                            shareDocxFile(context, file)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("مشاركة", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Arabic Font Switcher Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "الخط العربي المعتمد:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    PreviewDocFont.values().forEach { fontOption ->
                        val isSelected = selectedDocFont == fontOption
                        Surface(
                            onClick = { selectedDocFont = fontOption },
                            shape = RoundedCornerShape(20.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
                            ),
                            modifier = Modifier.testTag("font_chip_${fontOption.name.lowercase()}")
                        ) {
                            Text(
                                text = fontOption.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = fontOption.fontFamily,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }

        // A4 Paper Document Container
        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .background(if (MaterialTheme.colorScheme.background == Color(0xFF0F172A)) Color(0xFF0B1120) else Color(0xFFE2E8F0))
                .verticalScroll(rememberScrollState())
                .padding(if (isFullWidthMode) 4.dp else 16.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            // Paper Sheet Card
            Surface(
                modifier = Modifier
                    .fillMaxWidth(if (isFullWidthMode) 1f else 0.96f)
                    .border(1.dp, PaperBorder, RoundedCornerShape(4.dp)),
                shape = RoundedCornerShape(4.dp),
                color = PaperBackground,
                shadowElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // 1. Kingdom Official Header
                    OfficialMinisterialHeader(fontFamily = selectedDocFont.fontFamily)

                    Spacer(modifier = Modifier.height(14.dp))

                    // 2. Document Title Banner
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.5.dp, Color(0xFF1E3A8A), RoundedCornerShape(4.dp))
                            .background(Color(0xFFF1F5F9))
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "بطاقة تخطيط درس تربوي نموذجية",
                                style = MaterialTheme.typography.titleMedium,
                                fontFamily = selectedDocFont.fontFamily,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1E3A8A),
                                fontSize = 17.sp
                            )
                            Text(
                                text = "مكون: ${plan.componentType.title} | مادة: اللغة العربية",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = selectedDocFont.fontFamily,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF047857),
                                fontSize = 13.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 3. Identification Table (Informational Matrix)
                    IdentificationTable(plan = plan, fontFamily = selectedDocFont.fontFamily)

                    Spacer(modifier = Modifier.height(12.dp))

                    // 4. Competencies Matrix
                    CompetenciesMatrix(plan = plan, fontFamily = selectedDocFont.fontFamily)

                    Spacer(modifier = Modifier.height(14.dp))

                    // 5. Full Didactic Matrix Table
                    Text(
                        text = "شبكة التخطيط الديداكتيكي والتدبير البيداغوجي للتعلمات",
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = selectedDocFont.fontFamily,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A),
                        modifier = Modifier.padding(bottom = 6.dp)
                    )

                    // Horizontally scrollable table to ensure clean viewing on mobile screens
                    Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        DidacticMatrixTable(plan = plan, fontFamily = selectedDocFont.fontFamily)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Footer notes
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "إمضاء الأستاذ(ة): ........................",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = selectedDocFont.fontFamily,
                            color = Color(0xFF475569),
                            fontSize = 12.sp
                        )
                        Text(
                            text = "ملاحظات وتأشيرة المفتش(ة) التربوي(ة): ........................",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = selectedDocFont.fontFamily,
                            color = Color(0xFF475569),
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(72.dp))
                }
            }
        }
    }
}

@Composable
private fun OfficialMinisterialHeader(fontFamily: FontFamily) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        // Right Side: Ministry & Kingdom
        Column(horizontalAlignment = Alignment.Start) {
            Text(
                text = "المملكة المغربية",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0F172A),
                fontSize = 11.sp
            )
            Text(
                text = "وزارة التربية الوطنية والتعليم الأولي والرياضة",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = fontFamily,
                color = Color(0xFF1E3A8A),
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp
            )
            Text(
                text = "الأكاديمية الجهوية للتربية والتكوين",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = fontFamily,
                color = Color(0xFF475569),
                fontSize = 10.sp
            )
        }

        // Center: Pedagogical Crest / Emblem Symbol
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "﷽",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = fontFamily,
                color = Color(0xFF047857),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }

        // Left Side: Regional Directorate & School Info
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "المديرية الإقليمية: ................",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = fontFamily,
                color = Color(0xFF475569),
                fontSize = 10.sp
            )
            Text(
                text = "الثانوية الإعدادية: ................",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = fontFamily,
                color = Color(0xFF475569),
                fontSize = 10.sp
            )
            Text(
                text = "السنة الدراسية: 2025 / 2026",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = fontFamily,
                color = Color(0xFF475569),
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun IdentificationTable(plan: JadhadhaPlan, fontFamily: FontFamily) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF94A3B8))
    ) {
        // Row 1: Topic & Level
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF8FAFC))
                .border(0.5.dp, Color(0xFFCBD5E1))
        ) {
            TableCell(title = "موضوع الدرس", value = plan.topic, weight = 1.2f, isPrimary = true, fontFamily = fontFamily)
            TableCell(title = "المستوى الدراسي", value = plan.info.level, weight = 1f, fontFamily = fontFamily)
            TableCell(title = "المكون", value = plan.componentType.title, weight = 1f, fontFamily = fontFamily)
        }

        // Row 2: Field, Unit, Duration
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(0.5.dp, Color(0xFFCBD5E1))
        ) {
            TableCell(title = "المجال", value = plan.info.domain, weight = 1.2f, fontFamily = fontFamily)
            TableCell(title = "الوحدة", value = plan.info.unit, weight = 1f, fontFamily = fontFamily)
            TableCell(title = "الحصة والمدة", value = plan.info.duration, weight = 1f, fontFamily = fontFamily)
        }

        // Row 3: Reference & Materials
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF8FAFC))
                .border(0.5.dp, Color(0xFFCBD5E1))
        ) {
            TableCell(title = "المرجع المعتمد", value = "المختار في اللغة العربية", weight = 1.2f, fontFamily = fontFamily)
            TableCell(title = "الوسائل الديداكتيكية", value = plan.info.materials, weight = 2f, fontFamily = fontFamily)
        }
    }
}

@Composable
private fun RowScope.TableCell(
    title: String,
    value: String,
    weight: Float,
    isPrimary: Boolean = false,
    fontFamily: FontFamily
) {
    Row(
        modifier = Modifier
            .weight(weight)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$title: ",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = fontFamily,
            fontWeight = FontWeight.Bold,
            color = if (isPrimary) Color(0xFF047857) else Color(0xFF1E3A8A),
            fontSize = 11.sp
        )
        Text(
            text = value.ifBlank { "-" },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = fontFamily,
            color = Color(0xFF0F172A),
            fontWeight = if (isPrimary) FontWeight.Bold else FontWeight.Normal,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun CompetenciesMatrix(plan: JadhadhaPlan, fontFamily: FontFamily) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF94A3B8))
            .background(Color(0xFFFEF9C3).copy(alpha = 0.35f))
            .padding(8.dp)
    ) {
        Text(
            text = "الكفايات المستهدفة:",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = fontFamily,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF92400E),
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f).padding(end = 4.dp)) {
                Text(
                    text = "• كفاية تواصلية: ${plan.info.communicativeComp.ifBlank { "القدرة على التعبير والتفاعل السليم" }}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = fontFamily,
                    color = Color(0xFF1F2937),
                    fontSize = 10.5.sp
                )
                Text(
                    text = "• كفاية منهجية: ${plan.info.methodologicalComp.ifBlank { "اكتساب آليات الملاحظة والتحليل والتركيب" }}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = fontFamily,
                    color = Color(0xFF1F2937),
                    fontSize = 10.5.sp
                )
            }
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    text = "• كفاية ثقافية: ${plan.info.culturalComp.ifBlank { "إغناء الرصيد المعرفي والقيمي لدى المتعلم" }}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = fontFamily,
                    color = Color(0xFF1F2937),
                    fontSize = 10.5.sp
                )
                Text(
                    text = "• كفاية استراتيجية: ترسيخ الهوية الوطنية والانفتاح الإيجابي",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = fontFamily,
                    color = Color(0xFF1F2937),
                    fontSize = 10.5.sp
                )
            }
        }
    }
}

@Composable
private fun DidacticMatrixTable(plan: JadhadhaPlan, fontFamily: FontFamily) {
    val stages = StageCatalog.getStagesForComponent(plan.componentType)

    Column(
        modifier = Modifier
            .width(820.dp)
            .border(1.dp, Color(0xFF64748B))
    ) {
        // Table Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E3A8A))
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HeaderCell(text = "المراحل والمقاطع التعليمية", width = 130.dp, fontFamily = fontFamily)
            HeaderCell(text = "القدرات والأهداف", width = 130.dp, fontFamily = fontFamily)
            HeaderCell(text = "الأنشطة الديداكتيكية (المدرس / المتعلم)", width = 230.dp, fontFamily = fontFamily)
            HeaderCell(text = "محتوى التعلمات والدعامات", width = 180.dp, fontFamily = fontFamily)
            HeaderCell(text = "التقويم والدعم", width = 150.dp, fontFamily = fontFamily)
        }

        // Table Rows
        stages.forEachIndexed { index, stageMeta ->
            val stageData = plan.stages[stageMeta.key]
            val isEven = index % 2 == 0
            val rowBg = if (isEven) Color(0xFFFFFFFF) else Color(0xFFF8FAFC)

            val abilIsDefault = stageData?.sourceFor("abil") == com.example.model.FieldSource.DEFAULT
            val teacherIsDefault = stageData?.sourceFor("teacher") == com.example.model.FieldSource.DEFAULT
            val contentIsDefault = stageData?.sourceFor("content") == com.example.model.FieldSource.DEFAULT
            val evalIsDefault = stageData?.sourceFor("eval") == com.example.model.FieldSource.DEFAULT
            val stageAllDefault = abilIsDefault && teacherIsDefault && contentIsDefault && evalIsDefault

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(rowBg)
                    .border(0.5.dp, Color(0xFFCBD5E1)),
                verticalAlignment = Alignment.Top
            ) {
                // Column 1: Stage Name
                BodyCell(
                    width = 130.dp,
                    title = stageMeta.title,
                    subtitle = stageMeta.sessionName,
                    isStageHeader = true,
                    isDefaultSource = stageAllDefault,
                    fontFamily = fontFamily
                )

                // Column 2: Abilities & Objectives
                BodyCell(
                    width = 130.dp,
                    items = if (stageData?.abil.isNullOrEmpty()) listOf(stageMeta.description) else stageData!!.abil,
                    isDefaultSource = abilIsDefault,
                    fontFamily = fontFamily
                )

                // Column 3: Didactic Activities (Teacher & Student)
                BodyActivitiesCell(
                    width = 230.dp,
                    teacherActivities = stageData?.teacher ?: emptyList(),
                    fallbackActivities = listOf(stageMeta.description),
                    isDefaultSource = teacherIsDefault,
                    fontFamily = fontFamily
                )

                // Column 4: Learning Content
                BodyCell(
                    width = 180.dp,
                    items = stageData?.content ?: emptyList(),
                    isDefaultSource = contentIsDefault,
                    fontFamily = fontFamily
                )

                // Column 5: Evaluation & Support
                BodyCell(
                    width = 150.dp,
                    items = stageData?.eval ?: emptyList(),
                    isEval = true,
                    isDefaultSource = evalIsDefault,
                    fontFamily = fontFamily
                )
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, width: androidx.compose.ui.unit.Dp, fontFamily: FontFamily) {
    Text(
        text = text,
        modifier = Modifier
            .width(width)
            .padding(horizontal = 6.dp),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = fontFamily,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        textAlign = TextAlign.Center,
        fontSize = 11.sp
    )
}

@Composable
private fun BodyCell(
    width: androidx.compose.ui.unit.Dp,
    title: String? = null,
    subtitle: String? = null,
    items: List<String> = emptyList(),
    isStageHeader: Boolean = false,
    isEval: Boolean = false,
    isDefaultSource: Boolean = false,
    fontFamily: FontFamily
) {
    Column(
        modifier = Modifier
            .width(width)
            .padding(6.dp)
    ) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold,
                color = if (isStageHeader) Color(0xFF047857) else Color(0xFF0F172A),
                fontSize = 11.5.sp
            )
        }
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF475569),
                fontSize = 10.sp
            )
        }

        val pendingTableRows = mutableListOf<List<String>>()
        val nonBlankItems = items.filter { it.isNotBlank() }

        nonBlankItems.forEachIndexed { idx, rawItem ->
            val trimmed = rawItem.trim()
            if (trimmed.startsWith("[جدول:") && trimmed.endsWith("]")) {
                val cols = trimmed.removePrefix("[جدول:").removeSuffix("]")
                    .split("|")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                if (cols.isNotEmpty()) {
                    pendingTableRows.add(cols)
                }
                val nextIsTable = nonBlankItems.getOrNull(idx + 1)?.trim()?.startsWith("[جدول:") == true
                if (!nextIsTable && pendingTableRows.isNotEmpty()) {
                    NestedPreviewTable(rows = pendingTableRows.toList(), fontFamily = fontFamily)
                    pendingTableRows.clear()
                }
            } else {
                val cleanText = trimmed.replace("<u>", "").replace("</u>", "")
                Text(
                    text = "• $cleanText",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Bold,
                    color = if (isEval) Color(0xFF9A3412) else Color(0xFF0F172A),
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }

        if (title == null && nonBlankItems.isEmpty()) {
            Text(
                text = "-",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF94A3B8),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun NestedPreviewTable(rows: List<List<String>>, fontFamily: FontFamily) {
    val colCount = rows.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: 1
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(1.dp, Color(0xFF1E3A8A), RoundedCornerShape(2.dp))
    ) {
        rows.forEachIndexed { rIdx, row ->
            val isHeader = rIdx == 0
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isHeader) Color(0xFFD9E2F3) else Color.White)
                    .border(0.5.dp, Color(0xFF94A3B8)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (cIdx in 0 until colCount) {
                    val cellText = row.getOrNull(cIdx).orEmpty()
                    Text(
                        text = cellText,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = fontFamily,
                        fontWeight = FontWeight.Bold,
                        color = if (isHeader) Color(0xFF1E3A8A) else Color(0xFF0F172A),
                        textAlign = TextAlign.Center,
                        fontSize = 8.5.sp,
                        lineHeight = 11.sp,
                        modifier = Modifier
                            .weight(1f)
                            .padding(3.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun BodyActivitiesCell(
    width: androidx.compose.ui.unit.Dp,
    teacherActivities: List<String>,
    fallbackActivities: List<String>,
    isDefaultSource: Boolean = false,
    fontFamily: FontFamily
) {
    Column(
        modifier = Modifier
            .width(width)
            .padding(6.dp)
    ) {
        Text(
            text = "أنشطة المدرس والمتعلم:",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = fontFamily,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1E3A8A),
            fontSize = 10.sp,
            modifier = Modifier.padding(bottom = 2.dp)
        )
        val listToDisplay = if (teacherActivities.isNotEmpty()) teacherActivities else fallbackActivities
        listToDisplay.forEach { act ->
            if (act.isNotBlank()) {
                Text(
                    text = "• $act",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1E293B),
                    fontSize = 10.sp,
                    lineHeight = 13.5.sp,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
    }
}
