package com.binaural.beats.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.binaural.beats.ui.components.*
import com.binaural.beats.viewmodel.BinauralViewModel
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetEditScreen(
    viewModel: BinauralViewModel,
    presetId: String?,  // null для нового пресета
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    
    // Находим пресет для редактирования
    val editingPreset = remember(presetId, uiState.presets) {
        if (presetId == null) null
        else uiState.presets.find { it.id == presetId }
    }
    
    // Локальное состояние для редактирования
    var presetName by remember(editingPreset) { 
        mutableStateOf(editingPreset?.name ?: "Новый пресет") 
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
        uiState.editingFrequencyCurve != preset.frequencyCurve
    } ?: (presetName != "Новый пресет" || uiState.editingFrequencyCurve != null)
    
    fun saveAndNavigateBack() {
        val curve = uiState.editingFrequencyCurve ?: return
        if (presetId == null) {
            // Создаём новый пресет
            viewModel.createPreset(presetName, curve)
        } else {
            // Обновляем существующий
            viewModel.updatePresetName(presetId, presetName)
            viewModel.saveEditingPreset(presetId, presetName, curve)
        }
        onNavigateBack()
    }
    
    fun navigateBackWithCheck() {
        if (hasChanges) {
            showUnsavedDialog = true
        } else {
            onNavigateBack()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(if (presetId == null) "Новый пресет" else "Редактирование") 
                },
                navigationIcon = {
                    IconButton(onClick = { navigateBackWithCheck() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { saveAndNavigateBack() },
                        enabled = presetName.isNotBlank()
                    ) {
                        Icon(Icons.Default.Save, contentDescription = "Сохранить")
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Название пресета
            OutlinedTextField(
                value = presetName,
                onValueChange = { presetName = it },
                label = { Text("Название пресета") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                singleLine = true
            )
            
            // Компактный индикатор текущих частот
            CurrentFrequenciesCard(
                beatFrequency = uiState.currentBeatFrequency,
                carrierFrequency = uiState.currentCarrierFrequency,
                isPlaying = uiState.isPlaying
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // График частот (используем редактируемую кривую)
            val editingCurve = uiState.editingFrequencyCurve
            if (editingCurve != null) {
                FrequencyGraph(
                    points = editingCurve.points,
                    selectedPointIndex = uiState.selectedPointIndex,
                    currentCarrierFrequency = uiState.currentCarrierFrequency,
                    currentBeatFrequency = uiState.currentBeatFrequency,
                    carrierRange = editingCurve.carrierRange,
                    beatRange = editingCurve.beatRange,
                    isPlaying = uiState.isPlaying,
                    onPointSelected = { viewModel.selectPoint(it) },
                    onPointTimeChanged = { index, newTime ->
                        viewModel.updateEditingPointTimeDirect(index, newTime)
                    },
                    onPointCarrierChanged = { index, newCarrier ->
                        viewModel.updateEditingPointCarrierFrequencyDirect(index, newCarrier)
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
                
                if (uiState.selectedPointIndex!! in points.indices) {
                    val selectedPoint = points[uiState.selectedPointIndex!!]
                    PointEditor(
                        point = selectedPoint,
                        carrierRange = editingCurve.carrierRange,
                        onCarrierFrequencyChange = { viewModel.updateEditingPointCarrierFrequency(it) },
                        onBeatFrequencyChange = { viewModel.updateEditingPointBeatFrequency(it) },
                        onRemove = { viewModel.removeEditingPoint(uiState.selectedPointIndex!!) },
                        onDeselect = { viewModel.deselectPoint() }
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            // Настройки каналов (компактные)
            ChannelSettingsCard(
                channelSwapEnabled = uiState.channelSwapEnabled,
                channelSwapIntervalSeconds = uiState.channelSwapIntervalSeconds,
                channelSwapFadeEnabled = uiState.channelSwapFadeEnabled,
                channelSwapFadeDurationMs = uiState.channelSwapFadeDurationMs,
                isChannelsSwapped = uiState.isChannelsSwapped,
                volumeNormalizationEnabled = uiState.volumeNormalizationEnabled,
                volumeNormalizationStrength = uiState.volumeNormalizationStrength,
                sampleRate = uiState.sampleRate,
                currentLeftFreq = uiState.currentCarrierFrequency - uiState.currentBeatFrequency / 2.0,
                currentRightFreq = uiState.currentCarrierFrequency + uiState.currentBeatFrequency / 2.0,
                onChannelSwapEnabledChange = { viewModel.setChannelSwapEnabled(it) },
                onChannelSwapIntervalChange = { viewModel.setChannelSwapInterval(it) },
                onChannelSwapFadeEnabledChange = { viewModel.setChannelSwapFadeEnabled(it) },
                onChannelSwapFadeDurationChange = { viewModel.setChannelSwapFadeDuration(it) },
                onVolumeNormalizationEnabledChange = { viewModel.setVolumeNormalizationEnabled(it) },
                onVolumeNormalizationStrengthChange = { viewModel.setVolumeNormalizationStrength(it) },
                onSampleRateChange = { viewModel.setSampleRate(it) }
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Громкость и воспроизведение в одну строку
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                VolumeSlider(
                    volume = uiState.volume,
                    onVolumeChange = { viewModel.setVolume(it) },
                    modifier = Modifier.weight(1f)
                )
                
                Spacer(modifier = Modifier.width(16.dp))
                
                PlayButton(
                    isPlaying = uiState.isPlaying,
                    onClick = { viewModel.togglePlayback() }
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
    
    // Диалог несохранённых изменений
    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text("Несохранённые изменения") },
            text = { Text("У вас есть несохранённые изменения. Сохранить перед выходом?") },
            confirmButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    saveAndNavigateBack()
                }) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    onNavigateBack()
                }) {
                    Text("Не сохранять")
                }
            }
        )
    }
}