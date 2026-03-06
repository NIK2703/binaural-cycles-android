package com.binauralcycles.ui.navigation

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.binauralcycles.ui.components.BottomPlaybackPanel
import com.binauralcycles.ui.screens.PresetEditScreen
import com.binauralcycles.ui.screens.PresetListScreen
import com.binauralcycles.ui.screens.SettingsScreen
import com.binauralcycles.viewmodel.BinauralViewModel
import kotlinx.coroutines.launch
import java.io.File

sealed class Screen(val route: String) {
    object PresetList : Screen("presets")
    object PresetEdit : Screen("preset/{presetId}") {
        fun createRoute(presetId: String) = "preset/$presetId"
    }
    object PresetNew : Screen("preset/new")
    object Settings : Screen("settings")
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun BinauralNavigation(
    navController: NavHostController,
    viewModel: BinauralViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Панель отображается только когда есть активный пресет
    val showBottomPanel = uiState.activePreset != null
    
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
                    viewModel.exportPresetToJson(presetId)?.let { json ->
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
            viewModel.importPresetFromUri(importUri)
            // После успешного импорта возвращаемся к списку
            navController.popBackStack()
        }
    }
    
    SharedTransitionLayout {
        Scaffold(
            bottomBar = {
                if (showBottomPanel) {
                    BottomPlaybackPanel(
                        presetName = uiState.activePreset?.name,
                        beatFrequency = uiState.currentBeatFrequency,
                        carrierFrequency = uiState.currentCarrierFrequency,
                        isPlaying = uiState.isPlaying,
                        volume = uiState.volume,
                        onPlayClick = { viewModel.togglePlayback() },
                        onVolumeChange = { viewModel.setVolume(it) }
                    )
                }
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { paddingValues ->
            NavHost(
                navController = navController,
                startDestination = Screen.PresetList.route,
                modifier = Modifier.padding(paddingValues)
            ) {
                composable(Screen.PresetList.route) {
                    PresetListScreen(
                        viewModel = viewModel,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@composable,
                        onPresetClick = { presetId ->
                            // При клике на пресет начинаем воспроизведение
                            viewModel.playPreset(presetId)
                        },
                        onEditPreset = { presetId ->
                            navController.navigate(Screen.PresetEdit.createRoute(presetId))
                        },
                        onCreatePreset = {
                            navController.navigate(Screen.PresetNew.route)
                        },
                        onExportPreset = { presetId ->
                            val preset = viewModel.getPresetForExport(presetId)
                            preset?.let {
                                currentExportPresetId = presetId
                                val fileName = "${it.name.replace(" ", "_")}.json"
                                exportLauncher.launch(fileName)
                            }
                        },
                        onOpenSettings = {
                            navController.navigate(Screen.Settings.route)
                        }
                    )
                }
                
                composable(
                    route = Screen.PresetEdit.route,
                    arguments = listOf(
                        navArgument("presetId") { type = NavType.StringType }
                    )
                ) { backStackEntry ->
                    val presetId = backStackEntry.arguments?.getString("presetId") ?: ""
                    PresetEditScreen(
                        viewModel = viewModel,
                        presetId = presetId,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@composable,
                        onNavigateBack = {
                            navController.popBackStack()
                        }
                    )
                }
                
                composable(Screen.PresetNew.route) {
                    PresetEditScreen(
                        viewModel = viewModel,
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
                
                composable(Screen.Settings.route) {
                    SettingsScreen(
                        viewModel = viewModel,
                        onNavigateBack = {
                            navController.popBackStack()
                        }
                    )
                }
            }
        }
    }
}