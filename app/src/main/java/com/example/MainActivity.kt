package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.data.AppDatabase
import com.example.data.JadhadhaRepository
import com.example.ui.AppTab
import com.example.ui.JadhadhaViewModel
import com.example.ui.components.FloatingQuickBar
import com.example.ui.components.LiveMetricsBar
import com.example.ui.screens.AiGeneratorScreen
import com.example.ui.screens.ComponentsHubScreen
import com.example.ui.screens.DocxExportScreen
import com.example.ui.screens.ExtractorScreen
import com.example.ui.screens.LiveWordPreviewScreen
import com.example.ui.screens.ModelEditorScreen
import com.example.ui.screens.PythonToolkitScreen
import com.example.ui.screens.QuickToolsModalSheet
import com.example.ui.screens.SamplesScreen
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: JadhadhaViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val db = AppDatabase.getDatabase(applicationContext)
                val repository = JadhadhaRepository(db.jadhadhaDao())
                return JadhadhaViewModel(application, repository) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val uiState by viewModel.uiState.collectAsState()
            MyApplicationTheme(darkTheme = uiState.isDarkMode) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    JadhadhaMainApp(viewModel = viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JadhadhaMainApp(viewModel: JadhadhaViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showToolsSheet by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Handle Back Press: return to COMPONENTS_HUB if on another tab
    BackHandler(enabled = uiState.selectedTab != AppTab.COMPONENTS_HUB) {
        viewModel.selectTab(AppTab.COMPONENTS_HUB)
    }

    LaunchedEffect(uiState.successMessage) {
        uiState.successMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.statusBars,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Description,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "الجذاذة النموذجية",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.clickable {
                                        viewModel.selectTab(AppTab.COMPONENTS_HUB)
                                    }
                                ) {
                                    Text(
                                        text = "${uiState.activeComponent.title} ▾",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        // Night / Day Mode Toggle
                        IconButton(
                            onClick = { viewModel.toggleTheme() },
                            modifier = Modifier.testTag("action_toggle_theme")
                        ) {
                            Icon(
                                imageVector = if (uiState.isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = if (uiState.isDarkMode) "تفعيل النمط النهاري" else "تفعيل النمط الليلي",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        // Word Preview shortcut
                        IconButton(
                            onClick = { viewModel.selectTab(AppTab.WORD_PREVIEW) },
                            modifier = Modifier.testTag("top_action_preview")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Article,
                                contentDescription = "معاينة Word",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        // Direct Docx Export
                        IconButton(
                            onClick = { viewModel.exportToDocx(context) },
                            modifier = Modifier.testTag("top_action_export")
                        ) {
                            Icon(
                                imageVector = Icons.Default.FileDownload,
                                contentDescription = "تصدير Word",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )

                // Live Pedagogical Metrics Strip
                LiveMetricsBar(
                    completionPercentage = uiState.completionPercentage,
                    stagesFilled = uiState.stagesFilledCount,
                    totalStages = uiState.totalStagesRequiredCount,
                    wordCount = uiState.totalWordCount,
                    activeComponent = uiState.activeComponent,
                    onMetricsClick = { viewModel.selectTab(AppTab.WORD_PREVIEW) }
                )
            }
        },
        bottomBar = {
            // Modern Bottom Navigation Bar
            NavigationBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .testTag("bottom_nav_bar"),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                // 1. Hub
                NavigationBarItem(
                    selected = uiState.selectedTab == AppTab.COMPONENTS_HUB,
                    onClick = { viewModel.selectTab(AppTab.COMPONENTS_HUB) },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = "ركن المكونات",
                            modifier = Modifier.size(22.dp)
                        )
                    },
                    label = {
                        Text(
                            text = "المكونات",
                            fontWeight = if (uiState.selectedTab == AppTab.COMPONENTS_HUB) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 11.sp
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.testTag("nav_item_hub")
                )

                // 2. Model Editor
                NavigationBarItem(
                    selected = uiState.selectedTab == AppTab.MODEL_EDITOR,
                    onClick = { viewModel.selectTab(AppTab.MODEL_EDITOR) },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.TableChart,
                            contentDescription = "المحرر",
                            modifier = Modifier.size(22.dp)
                        )
                    },
                    label = {
                        Text(
                            text = "المحرر",
                            fontWeight = if (uiState.selectedTab == AppTab.MODEL_EDITOR) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 11.sp
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.testTag("nav_item_editor")
                )

                // 3. Word Preview
                NavigationBarItem(
                    selected = uiState.selectedTab == AppTab.WORD_PREVIEW,
                    onClick = { viewModel.selectTab(AppTab.WORD_PREVIEW) },
                    icon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Article,
                            contentDescription = "معاينة Word",
                            modifier = Modifier.size(22.dp)
                        )
                    },
                    label = {
                        Text(
                            text = "معاينة Word",
                            fontWeight = if (uiState.selectedTab == AppTab.WORD_PREVIEW) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 11.sp
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.testTag("nav_item_word_preview")
                )

                // 4. Quick Tools Sheet trigger
                val isSecondaryToolSelected = uiState.selectedTab in listOf(
                    AppTab.EXTRACTOR, AppTab.AI_GENERATOR, AppTab.EXPORT_DOCX, AppTab.PYTHON_TOOLKIT, AppTab.SAMPLES
                )
                NavigationBarItem(
                    selected = isSecondaryToolSelected,
                    onClick = { showToolsSheet = true },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Widgets,
                            contentDescription = "الأدوات",
                            modifier = Modifier.size(22.dp)
                        )
                    },
                    label = {
                        Text(
                            text = if (isSecondaryToolSelected) uiState.selectedTab.title else "الأدوات",
                            fontWeight = if (isSecondaryToolSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.testTag("nav_item_tools")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                when (uiState.selectedTab) {
                    AppTab.COMPONENTS_HUB -> ComponentsHubScreen(
                        activeComponent = uiState.activeComponent,
                        currentPlan = uiState.currentPlan,
                        savedPlans = uiState.savedPlans,
                        onSelectComponent = { comp -> viewModel.setActiveComponent(comp) },
                        onNavigateToTab = { tab -> viewModel.selectTab(tab) }
                    )

                    AppTab.MODEL_EDITOR -> ModelEditorScreen(
                        plan = uiState.currentPlan,
                        onUpdateTopic = { viewModel.updateTopic(it) },
                        onUpdateCategory = { viewModel.updateCategory(it) },
                        onUpdateInfo = { viewModel.updateInfo(it) },
                        onAddItem = { k, f, t -> viewModel.addStageItem(k, f, t) },
                        onEditItem = { k, f, idx, t -> viewModel.editStageItem(k, f, idx, t) },
                        onRemoveItem = { k, f, idx -> viewModel.removeStageItem(k, f, idx) },
                        onNavigateToTab = { viewModel.selectTab(it) },
                        onSavePlan = { viewModel.saveCurrentPlan() },
                        isSuggestingObjectives = uiState.isSuggestingContent,
                        suggestedObjectives = uiState.suggestedObjectives,
                        onSuggestObjectives = { topic -> viewModel.suggestObjectives(topic) },
                        onApplySuggestedObjectives = { suggestions, applyToStages ->
                            viewModel.applySuggestedObjectives(suggestions, applyToStages)
                        },
                        onDismissSuggestedObjectives = { viewModel.dismissSuggestedObjectives() }
                    )

                    AppTab.WORD_PREVIEW -> LiveWordPreviewScreen(
                        plan = uiState.currentPlan,
                        onExportDocx = { ctx -> viewModel.exportToDocx(ctx) }
                    )

                    AppTab.EXTRACTOR -> ExtractorScreen(
                        initialComponent = uiState.activeComponent,
                        onApplyExtractedPlan = { raw, topic, comp, preParsed ->
                            viewModel.parseAndApplyText(raw, topic, comp, preParsed)
                        }
                    )

                    AppTab.AI_GENERATOR -> AiGeneratorScreen(
                        isLoading = uiState.isAiLoading,
                        errorMessage = uiState.errorMessage,
                        customApiKey = uiState.customApiKey,
                        initialComponent = uiState.activeComponent,
                        onSaveApiKey = { viewModel.setApiKey(it) },
                        onGenerate = { topic, text, comp -> viewModel.generateWithAi(topic, text, comp) }
                    )

                    AppTab.EXPORT_DOCX -> DocxExportScreen(
                        plan = uiState.currentPlan,
                        lastExportedFile = uiState.lastGeneratedFile,
                        onExportDocx = { ctx -> viewModel.exportToDocx(ctx) }
                    )

                    AppTab.PYTHON_TOOLKIT -> PythonToolkitScreen(
                        plan = uiState.currentPlan,
                        onExportJson = { ctx -> viewModel.exportToJson(ctx) },
                        onExportPython = { ctx, comp -> viewModel.exportPythonScript(ctx, comp) }
                    )

                    AppTab.SAMPLES -> SamplesScreen(
                        savedPlans = uiState.savedPlans,
                        onSelectPlan = { viewModel.loadPlan(it) },
                        onDeletePlan = { viewModel.deleteSavedPlan(it) }
                    )
                }
            }

            // Floating Quick Action Bar (visible on Model Editor & Live Word Preview)
            FloatingQuickBar(
                plan = uiState.currentPlan,
                visible = uiState.selectedTab == AppTab.MODEL_EDITOR || uiState.selectedTab == AppTab.WORD_PREVIEW,
                onExportDocx = { ctx -> viewModel.exportToDocx(ctx) },
                onPreviewDoc = { viewModel.selectTab(AppTab.WORD_PREVIEW) },
                onSavePlan = { viewModel.saveCurrentPlan() },
                onShowToast = { msg ->
                    scope.launch { snackbarHostState.showSnackbar(msg) }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
            )
        }

        // Quick Tools Bottom Sheet
        if (showToolsSheet) {
            QuickToolsModalSheet(
                sheetState = sheetState,
                onDismissRequest = { showToolsSheet = false },
                onSelectTab = { tab ->
                    showToolsSheet = false
                    viewModel.selectTab(tab)
                }
            )
        }
    }
}
