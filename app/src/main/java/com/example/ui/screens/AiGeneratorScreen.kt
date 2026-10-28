package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.PedagogicalComponent

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiGeneratorScreen(
    isLoading: Boolean,
    errorMessage: String?,
    customApiKey: String,
    initialComponent: PedagogicalComponent = PedagogicalComponent.READING,
    onSaveApiKey: (String) -> Unit,
    onGenerate: (topic: String, lessonText: String, component: PedagogicalComponent) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedComponent by remember { mutableStateOf(initialComponent) }
    var topic by remember { mutableStateOf("") }
    var lessonText by remember { mutableStateOf("") }
    var apiKeyInput by remember { mutableStateOf(customApiKey) }
    var showApiKeySettings by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    val quickTopics = when (selectedComponent) {
        PedagogicalComponent.READING -> listOf(
            "مسيرة امرأة",
            "المنفى",
            "أنا والمدينة",
            "قيم إسلامية",
            "التلوث البيئي",
            "في سبيل الوطن"
        )
        PedagogicalComponent.LANGUAGE -> listOf(
            "اسم الفاعل وعمله",
            "صيغ المبالغة وعملها",
            "اسم المفعول وعمله",
            "أسلوب الاختصاص",
            "أسلوب التعجب",
            "أسلوب المدح والذم"
        )
        PedagogicalComponent.EXPRESSION -> listOf(
            "كتابة حكاية عجيبة أو من الخيال العلمي",
            "التدرب على كتابة اليوميات",
            "مهارة كتابة سيرة ذاتية أو غيرية",
            "مهارة الوصف: وصف شخوص وأمكنة",
            "مهارة النقد والحكم"
        )
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
                    Icon(
                        imageVector = Icons.Default.Psychology,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "التوليد البيداغوجي بالذكاء الاصطناعي",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "توليد جذاذة تربوية كاملة ومفصلة بواسطة أحدث نموذج ذكاء اصطناعي (Gemini 3.8 Flash) وفق التوجيهات الرسمية لوزارة التربية الوطنية المغربية لمكونات النصوص القرائية، الدرس اللغوي، والتعبير والإنشاء.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
            }
        }

        // Component Selection
        Column {
            Text(
                text = "اختر المكون المراد توليد جذاذته:",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PedagogicalComponent.entries.forEach { comp ->
                    FilterChip(
                        selected = selectedComponent == comp,
                        onClick = { selectedComponent = comp },
                        label = { Text(comp.title) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Quick Topic Chips
        Column {
            Text(
                text = "اقتراحات دروس ومهارات (${selectedComponent.title}):",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                quickTopics.forEach { t ->
                    SuggestionChip(
                        onClick = { topic = t },
                        label = { Text(t) }
                    )
                }
            }
        }

        // Inputs
        OutlinedTextField(
            value = topic,
            onValueChange = { topic = it },
            label = {
                Text(
                    when (selectedComponent) {
                        PedagogicalComponent.EXPRESSION -> "المهارة المستهدفة *"
                        PedagogicalComponent.LANGUAGE -> "عنوان الدرس اللغوي *"
                        PedagogicalComponent.READING -> "عنوان النص القرائي *"
                    }
                )
            },
            placeholder = { Text("مثال: ${quickTopics.firstOrNull() ?: "عنوان الدرس"}") },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ai_topic_input"),
            singleLine = true
        )

        OutlinedTextField(
            value = lessonText,
            onValueChange = { lessonText = it },
            label = {
                Text(
                    when (selectedComponent) {
                        PedagogicalComponent.EXPRESSION -> "نص الانطلاق أو معطيات المهارة (اختياري)"
                        PedagogicalComponent.LANGUAGE -> "شواهد وأمثلة الانطلاق (اختياري)"
                        PedagogicalComponent.READING -> "نص الدرس القرائي (اختياري، يوصى به)"
                    }
                )
            },
            placeholder = {
                Text("الصق هنا نصوص أو أمثلة الانطلاق ليعتمد عليها الذكاء الاصطناعي بدقة...")
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .testTag("ai_text_input")
        )

        // API Key Settings Toggle
        OutlinedButton(
            onClick = { showApiKeySettings = !showApiKeySettings },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(imageVector = Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (showApiKeySettings) "إخفاء إعدادات مفتاح Gemini" else "إعدادات مفتاح Gemini API")
        }

        AnimatedVisibility(visible = showApiKeySettings) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "مفتاح Gemini API الخاص بك:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "يمكنك لصق مفتاح Gemini هنا (من aistudio.google.com/apikey). كما يدعم التطبيق المفتاح المحقون تلقائيا من لوحة Secrets.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = { apiKeyInput = it },
                            placeholder = { Text("AIzaSy...") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        Button(
                            onClick = { onSaveApiKey(apiKeyInput) }
                        ) {
                            Text("حفظ")
                        }
                    }
                }
            }
        }

        // Error Display
        if (errorMessage != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }

        // Generate Action Button
        Button(
            onClick = { onGenerate(topic, lessonText, selectedComponent) },
            enabled = !isLoading && topic.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("ai_generate_button"),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text("جارٍ التوليد بنموذج Gemini 3.8 Flash...")
            } else {
                Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "توليد جذاذة (${selectedComponent.shortName}) بـ Gemini 3.8 Flash",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}
