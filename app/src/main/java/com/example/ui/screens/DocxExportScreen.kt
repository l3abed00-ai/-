package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.docx.DocxGenerator
import com.example.model.JadhadhaPlan
import com.example.ui.components.AppButton
import com.example.ui.components.AppOutlinedButton
import com.example.ui.components.AppTonalButton
import com.example.ui.components.ButtonLabel
import com.example.ui.components.ltr
import java.io.File

@Composable
fun DocxExportScreen(
    plan: JadhadhaPlan,
    lastExportedFile: File?,
    onExportDocx: (Context) -> File,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentFile by remember(lastExportedFile) { mutableStateOf(lastExportedFile) }
    val scrollState = rememberScrollState()

    val saveAsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(DocxGenerator.DOCX_MIME_TYPE)
    ) { targetUri: Uri? ->
        if (targetUri != null) {
            val fileToSave = currentFile ?: onExportDocx(context).also { currentFile = it }
            val ok = DocxGenerator.saveFileToUri(context, fileToSave, targetUri)
            if (ok) {
                Toast.makeText(context, "تم حفظ الجذاذة في المجلد المختار على هاتفك بنجاح!", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "تعذر حفظ الملف في المسار المختار.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Banner
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = "تصدير وحفظ ملف ${ltr("Word")} (${ltr(".docx")})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "يُحفظ تلقائياً في مجلد التنزيلات (${ltr("Downloads")}) بهاتفك",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // Document Details Card
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "تفاصيل الجذاذة الجاهزة للتصدير:",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(10.dp))

                DetailItem("المكوّن:", plan.componentType.title)
                DetailItem("موضوع الدرس / المهارة:", "«${plan.topic}»")
                DetailItem("المستوى والسلك:", plan.info.level)
                DetailItem("الوحدة والمجال:", "${plan.info.unit} — ${plan.info.domain}")
                DetailItem("هيكلة الجذاذة:", when (plan.componentType) {
                    com.example.model.PedagogicalComponent.READING -> "الحصتان الأولى والثانية (صفحتان)"
                    com.example.model.PedagogicalComponent.LANGUAGE -> "مراحل الدرس اللغوي الخمس مع الجداول التحليلية"
                    com.example.model.PedagogicalComponent.EXPRESSION -> "الحصص الأربع (الاكتساب، التطبيق، الإنتاج، التصحيح)"
                })
                DetailItem("مكان الحفظ بالهاتف:", "مجلد التنزيلات (${ltr("Downloads")} / التنزيلات)")
            }
        }

        // Primary Action: Generate / Export DOCX to Downloads
        AppButton(
            onClick = {
                val file = onExportDocx(context)
                currentFile = file
                Toast.makeText(
                    context,
                    "تم حفظ الجذاذة في مجلد التنزيلات (${ltr("Downloads")}): ${ltr(file.name)}",
                    Toast.LENGTH_LONG
                ).show()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("generate_docx_button"),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            ButtonLabel(
                text = "حفظ في مجلد التنزيلات (${ltr("Downloads")}) بصيغة ${ltr("Word")}",
                icon = Icons.Default.Download,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
        }

        // Secondary Action: Save As (Choose Folder on Phone)
        AppTonalButton(
            onClick = {
                val file = currentFile ?: onExportDocx(context).also { currentFile = it }
                saveAsLauncher.launch(file.name)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("save_as_docx_button")
        ) {
            ButtonLabel(
                text = "حفظ في الهاتف باسم... (اختيار مجلد آخر)",
                icon = Icons.Default.Folder,
                fontWeight = FontWeight.Bold
            )
        }

        // Actions if file is generated
        currentFile?.let { file ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Verified,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "الملف جاهز: ${ltr(file.name)}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "تجدها على هاتفك في: تطبيق الملفات ← التنزيلات (${ltr("Downloads")})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AppButton(
                            onClick = { openDocxFile(context, file) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("open_docx_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            ButtonLabel(
                                text = "فتح في ${ltr("Word")}",
                                icon = Icons.AutoMirrored.Filled.OpenInNew,
                                iconSize = 18.dp
                            )
                        }

                        AppOutlinedButton(
                            onClick = { shareDocxFile(context, file) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("share_docx_button")
                        ) {
                            ButtonLabel(
                                text = "مشاركة",
                                icon = Icons.Default.Share,
                                iconSize = 18.dp
                            )
                        }
                    }
                }
            }
        }

        // Features Checklist
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "أين تجد الجذاذات على هاتفك؟",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                FeatureCheckItem("تُحفظ ملفات ${ltr("Word")} تلقائياً في مجلد «التنزيلات / ${ltr("Downloads")}» على هاتفك.")
                FeatureCheckItem("يمكنك اختيار أي مجلد آخر في هاتفك عبر زر «حفظ في الهاتف باسم...».")
                FeatureCheckItem("يمكنك إرسال الجذاذة عبر واتساب أو تيليجرام أو ${ltr("Google Drive")} عبر زر «مشاركة».")
                FeatureCheckItem("في قسم «تفريغ الجذاذات» ستجد قائمة مباشرة بجميع الجذاذات المحفوظة لرفعها بضغطة واحدة.")
            }
        }

        // Bottom space so FloatingQuickBar never covers the last card
        Spacer(modifier = Modifier.height(88.dp))
    }
}

@Composable
private fun DetailItem(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun FeatureCheckItem(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
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
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

fun openDocxFile(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, DocxGenerator.DOCX_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "اختر تطبيقا لعرض الجذاذة"))
    } catch (e: Exception) {
        Toast.makeText(context, "تعذر فتح الملف مباشرة: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

fun shareDocxFile(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = DocxGenerator.DOCX_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "جذاذة ${ltr(file.name)}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "مشاركة الجذاذة عبر..."))
    } catch (e: Exception) {
        Toast.makeText(context, "تعذر مشاركة الملف: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
