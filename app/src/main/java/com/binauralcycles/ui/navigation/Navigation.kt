package com.binauralcycles.ui.navigation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.binauralcycles.ui.components.BottomPlaybackPanel
import com.binauralcycles.ui.screens.PresetEditScreen
import com.binauralcycles.ui.screens.PresetListScreen
import com.binauralcycles.ui.screens.SettingsScreen
import com.binauralcycles.viewmodel.PresetListViewModel
import com.binauralcycles.viewmodel.PresetEditViewModel
import com.binauralcycles.viewmodel.SettingsViewModel
import com.binauralcycles.viewmodel.PlaybackViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * Type-safe навигационные destinations.
 * Используют Kotlin Serialization для безопасной навигации.
 */
sealed interface Destination {
    
    /**
     * Список пресетов (главный экран)
     */
    @Serializable
    data object PresetList : Destination
    
    /**
     * Редактирование существующего пресета
     * @param presetId ID пресета для редактирования
     */
    @Serializable
    data class PresetEdit(val presetId: String) : Destination
    
    /**
     * Создание нового пресета
     */
    @Serializable
    data object PresetNew : Destination
    
    /**
     * Настройки приложения
     */
    @Serializable
    data object Settings : Destination
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun BinauralNavigation(
    navController: NavHostController,
    presetListViewModel: PresetListViewModel = hiltViewModel(),
    playbackViewModel: PlaybackViewModel = hiltViewModel()
) {
    val presetListUiState by presetListViewModel.uiState.collectAsState()
    val playbackUiState by playbackViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Панель отображается только когда есть активный пресет
    val showBottomPanel = presetListUiState.activePreset != null
    
    // Сохраняем ID пресета для экспорта (используется внутри лаунчера)
    var currentExportPresetId by remember { mutableStateOf<String?>(null) }
    
    // Лаунчер для экспорта пресета (создание файла)
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { exportUri ->
            currentExportPresetId?.let { presetId ->
                scope.launch {
                    // Получаем JSON и записываем в файл
                    presetListViewModel.exportPresetToJson(presetId)?.let { json ->
                        context.contentResolver.openOutputStream(exportUri)?.use { outputStream ->
                            outputStream.bufferedWriter().use { writer ->
                                writer.write(json)
                            }
                        }
                    }
                }
                currentExportPresetId = null
            }
        }
    }
    
    // Лаунчер для импорта пресета (открытие файла)
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importUri ->
            presetListViewModel.importPresetFromUri(importUri)
            // После успешного импорта возвращаемся к списку
            navController.popBackStack()
        }
    }
    
    // Высота нижней панели воспроизведения (для компенсации в контенте)
    val bottomPanelHeight = 60.dp
    
    Box(modifier = Modifier.fillMaxSize()) {
        SharedTransitionLayout {
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0)
            ) { paddingValues ->
                NavHost(
                    navController = navController,
                    startDestination = Destination.PresetList,
                    modifier = Modifier
                        .padding(paddingValues)
                        // Добавляем снизу место для панели воспроизведения
                        .padding(bottom = if (showBottomPanel) bottomPanelHeight else 0.dp)
                        // Добавляем padding для navigation bar
                        .navigationBarsPadding()
                ) {
                composable<Destination.PresetList> {
                    PresetListScreen(
                        viewModel = presetListViewModel,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@composable,
                        onPresetClick = { presetId ->
                            // При клике на пресет начинаем воспроизведение
                            presetListViewModel.playPreset(presetId)
                        },
                        onEditPreset = { presetId ->
                            navController.navigate(Destination.PresetEdit(presetId))
                        },
                        onCreatePreset = {
                            navController.navigate(Destination.PresetNew)
                        },
                        onExportPreset = { presetId ->
                            val preset = presetListViewModel.getPresetForExport(presetId)
                            preset?.let {
                                currentExportPresetId = presetId
                                val fileName = "${it.name.replace(" ", "_")}.json"
                                exportLauncher.launch(fileName)
                            }
                        },
                        onOpenSettings = {
                            navController.navigate(Destination.Settings)
                        }
                    )
                }
                
                composable<Destination.PresetEdit> { backStackEntry ->
                    val route: Destination.PresetEdit = backStackEntry.toRoute()
                    val presetId = route.presetId
                    val editViewModel: PresetEditViewModel = hiltViewModel()
                    PresetEditScreen(
                        viewModel = editViewModel,
                        presetId = presetId,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@composable,
                        onNavigateBack = {
                            navController.popBackStack()
                        }
                    )
                }
                
                composable<Destination.PresetNew> {
                    val editViewModel: PresetEditViewModel = hiltViewModel()
                    PresetEditScreen(
                        viewModel = editViewModel,
                        presetId = null,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@composable,
                        onNavigateBack = {
                            navController.popBackStack()
                        },
                        onImportPreset = {
                            importLauncher.launch(arrayOf("application/json"))
                        }
                    )
                }
                
                composable<Destination.Settings> {
                    val settingsViewModel: SettingsViewModel = hiltViewModel()
                    SettingsScreen(
                        viewModel = settingsViewModel,
                        onNavigateBack = {
                            navController.popBackStack()
                        }
                    )
                }
                }
            }
        }
        
        // Нижняя панель воспроизведения поверх контента
        // navigationBarsPadding применяется внутри BottomPlaybackPanel только к контенту
        // чтобы фон Surface заходил под navigation bar
        if (showBottomPanel) {
            BottomPlaybackPanel(
                presetName = presetListUiState.activePreset?.name,
                beatFrequency = presetListUiState.currentBeatFrequency,
                carrierFrequency = presetListUiState.currentCarrierFrequency,
                isPlaying = playbackUiState.isPlaying,
                volume = playbackUiState.volume,
                onPlayClick = { playbackViewModel.togglePlayback() },
                onVolumeChange = { playbackViewModel.setVolumeImmediate(it) },
                onVolumeSave = { /* Volume saved in SettingsViewModel */ },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}