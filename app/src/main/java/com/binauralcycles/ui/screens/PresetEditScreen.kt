package com.binauralcycles.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.binauralcycles.ui.components.*
import com.binauralcycles.viewmodel.BinauralViewModel
import com.binauralcycles.R

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun PresetEditScreen(
    viewModel: BinauralViewModel,
    presetId: String?,  // null для нового пресета
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onNavigateBack: () -> Unit,
    onImportPreset: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val newPresetName = stringResource(R.string.new_preset)
    
    // Находим пресет для редактирования
    val editingPreset = remember(presetId, uiState.presets) {
        if (presetId == null) null
        else uiState.presets.find { it.id == presetId }
    }
    
    // Локальное состояние для редактирования
    var presetName by remember(editingPreset) { 
        mutableStateOf(editingPreset?.name ?: newPresetName) 
    }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var hasChanges by remember { mutableStateOf(false) }
    
    // Инициализируем редактируемый пресет в ViewModel
    LaunchedEffect(presetId) {
        if (presetId != null) {
            viewModel.startEditingPreset(presetId)
        } else {
            viewModel.startNewPreset()
        }
    }
    
    // Проверяем наличие изменений
    hasChanges = editingPreset?.let { preset ->
        preset.name != presetName || 
        uiState.editingFrequencyCurve != preset.frequencyCurve ||
        uiState.editingChannelSwapSettings != preset.channelSwapSettings ||
        uiState.editingVolumeNormalizationSettings != preset.volumeNormalizationSettings
    } ?: (presetName != newPresetName || uiState.editingFrequencyCurve != null)
    
    fun saveAndNavigateBack() {
        val curve = uiState.editingFrequencyCurve ?: return
        if (presetId == null) {
            // Создаём новый пресет
            viewModel.createPreset(
                name = presetName,
                curve = curve,
                channelSwapSettings = uiState.editingChannelSwapSettings,
                volumeNormalizationSettings = uiState.editingVolumeNormalizationSettings
            )
        } else {
            // Обновляем существующий
            viewModel.saveEditingPreset(
                presetId = presetId,
                name = presetName,
                curve = curve,
                channelSwapSettings = uiState.editingChannelSwapSettings,
                volumeNormalizationSettings = uiState.editingVolumeNormalizationSettings
            )
        }
        // Завершаем редактирование без очистки состояния для плавной анимации
        viewModel.finishEditingWithoutClear()
        onNavigateBack()
    }
    
    fun navigateBackWithCheck() {
        if (hasChanges) {
            showUnsavedDialog = true
        } else {
            // Восстанавливаем кривую активного пресета в сервисе (если нужно)
            // Но не очищаем editingFrequencyCurve - это позволит анимации работать плавно
            viewModel.cancelEditingInService()
            onNavigateBack()
        }
    }
    
    // Очистка фокуса при скрытии клавиатуры
    val focusManager = LocalFocusManager.current
    
    // Обработка системной кнопки "назад"
    BackHandler(enabled = true) {
        focusManager.clearFocus()
        navigateBackWithCheck()
    }
    
    with(sharedTransitionScope) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { 
                        Text(if (presetId == null) stringResource(R.string.new_preset) else stringResource(R.string.edit_preset)) 
                    },
                    navigationIcon = {
                        IconButton(onClick = { 
                            focusManager.clearFocus()
                            navigateBackWithCheck() 
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    actions = {
                        // Кнопка импорта показывается только для нового пресета
                        if (presetId == null) {
                            IconButton(onClick = onImportPreset) {
                                Icon(Icons.Default.FileDownload, contentDescription = stringResource(R.string.import_preset))
                            }
                        }
                        IconButton(
                            onClick = { saveAndNavigateBack() },
                            enabled = presetName.isNotBlank()
                        ) {
                            Icon(Icons.Default.Save, contentDescription = stringResource(R.string.save))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .sharedBounds(
                        sharedContentState = rememberSharedContentState(
                            key = "preset-$presetId"
                        ),
                        animatedVisibilityScope = animatedVisibilityScope
                    )
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = {
                            focusManager.clearFocus()
                        })
                    }
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Название пресета
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = { Text(stringResource(R.string.preset_name)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    singleLine = true
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // График частот (используем редактируемую кривую)
                val editingCurve = uiState.editingFrequencyCurve
                // Показываем указатель текущей частоты только если редактируется активный пресет
                val isEditingActivePreset = presetId != null && uiState.activePreset?.id == presetId
                if (editingCurve != null) {
                    FrequencyGraph(
                        points = editingCurve.points,
                        selectedPointIndex = uiState.selectedPointIndex,
                        currentCarrierFrequency = uiState.currentCarrierFrequency,
                        currentBeatFrequency = uiState.currentBeatFrequency,
                        carrierRange = editingCurve.carrierRange,
                        beatRange = editingCurve.beatRange,
                        interpolationType = editingCurve.interpolationType,
                        splineTension = editingCurve.splineTension,
                        // Показываем указатель только если редактируется активный пресет
                        isPlaying = isEditingActivePreset && uiState.isPlaying,
                        onPointSelected = { viewModel.selectPoint(it) },
                        onPointTimeChanged = { index, newTime ->
                            viewModel.updateEditingPointTimeDirect(index, newTime)
                        },
                        onPointCarrierChanged = { index, newCarrier ->
                            viewModel.updateEditingPointCarrierFrequencyDirect(index, newCarrier)
                        },
                        onPointBeatChanged = { index, newBeat ->
                            viewModel.updateEditingPointBeatFrequencyDirect(index, newBeat)
                        },
                        onAddPoint = { time, carrier, beat ->
                            viewModel.addEditingPoint(time, carrier, beat)
                        },
                        onCarrierRangeChange = { min, max -> 
                            viewModel.updateEditingCarrierRange(min, max) 
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp, max = 300.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Редактирование выбранной точки
                if (uiState.selectedPointIndex != null && editingCurve != null) {
                    val points = editingCurve.points
                    val selectedIndex = uiState.selectedPointIndex
                    
                    if (selectedIndex != null && selectedIndex in points.indices) {
                        val selectedPoint = points[selectedIndex]
                        PointEditor(
                            point = selectedPoint,
                            carrierRange = editingCurve.carrierRange,
                            beatRange = editingCurve.beatRange,
                            autoExpandGraphRange = uiState.autoExpandGraphRange,
                            onCarrierFrequencyChange = { viewModel.updateEditingPointCarrierFrequency(it) },
                            onBeatFrequencyChange = { viewModel.updateEditingPointBeatFrequency(it) },
                            onTimeChange = { time -> 
                                selectedIndex?.let { viewModel.updateEditingPointTimeDirect(it, time) }
                            },
                            onRemove = { 
                                selectedIndex?.let { viewModel.removeEditingPoint(it) }
                            },
                            onDeselect = { viewModel.deselectPoint() }
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                // Настройки пресета
                PresetSettingsCard(
                    channelSwapSettings = uiState.editingChannelSwapSettings,
                    volumeNormalizationSettings = uiState.editingVolumeNormalizationSettings,
                    interpolationType = editingCurve?.interpolationType ?: com.binaural.core.audio.model.InterpolationType.LINEAR,
                    isChannelsSwapped = uiState.isChannelsSwapped,
                    currentLeftFreq = uiState.currentCarrierFrequency - uiState.currentBeatFrequency / 2.0,
                    currentRightFreq = uiState.currentCarrierFrequency + uiState.currentBeatFrequency / 2.0,
                    onChannelSwapEnabledChange = { viewModel.setEditingChannelSwapEnabled(it) },
                    onChannelSwapIntervalChange = { viewModel.setEditingChannelSwapInterval(it) },
                    onChannelSwapFadeEnabledChange = { viewModel.setEditingChannelSwapFadeEnabled(it) },
                    onChannelSwapFadeDurationChange = { viewModel.setEditingChannelSwapFadeDuration(it) },
                    onVolumeNormalizationEnabledChange = { viewModel.setEditingVolumeNormalizationEnabled(it) },
                    onVolumeNormalizationStrengthChange = { viewModel.setEditingVolumeNormalizationStrength(it) },
                    onTemporalNormalizationEnabledChange = { viewModel.setEditingTemporalNormalizationEnabled(it) },
                    onInterpolationTypeChange = { viewModel.setInterpolationType(it) }
                )
                
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
    
    // Диалог несохранённых изменений
    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text(stringResource(R.string.unsaved_changes_title)) },
            text = { Text(stringResource(R.string.unsaved_changes_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    saveAndNavigateBack()
                }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    // Восстанавливаем кривую активного пресета в сервисе
                    // Но не очищаем editingFrequencyCurve - это произойдёт после анимации
                    viewModel.cancelEditingInService()
                    onNavigateBack()
                }) {
                    Text(stringResource(R.string.do_not_save))
                }
            }
        )
    }
}