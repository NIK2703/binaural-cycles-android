package com.binaural.beats.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.binaural.beats.ui.components.BottomPlaybackPanel
import com.binaural.beats.ui.screens.PresetEditScreen
import com.binaural.beats.ui.screens.PresetListScreen
import com.binaural.beats.viewmodel.BinauralViewModel

sealed class Screen(val route: String) {
    object PresetList : Screen("presets")
    object PresetEdit : Screen("preset/{presetId}") {
        fun createRoute(presetId: String) = "preset/$presetId"
    }
    object PresetNew : Screen("preset/new")
}

@Composable
fun BinauralNavigation(
    navController: NavHostController,
    viewModel: BinauralViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    
    // Панель отображается только когда есть активный пресет
    val showBottomPanel = uiState.activePreset != null
    
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
                    onPresetClick = { presetId ->
                        // При клике на пресет начинаем воспроизведение
                        viewModel.playPreset(presetId)
                    },
                    onEditPreset = { presetId ->
                        navController.navigate(Screen.PresetEdit.createRoute(presetId))
                    },
                    onCreatePreset = {
                        navController.navigate(Screen.PresetNew.route)
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
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            
            composable(Screen.PresetNew.route) {
                PresetEditScreen(
                    viewModel = viewModel,
                    presetId = null,
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}