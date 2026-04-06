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
import com.binauralcycles.viewmodel.PresetEditViewModel
import com.binauralcycles.viewmodel.state.PresetEditUiState
import com.binauralcycles.viewmodel.events.PresetEditEvent
import com.binauralcycles.R
import com.binaural.core.domain.model.InterpolationType
import kotlinx.coroutines.flow.receiveAsFlow

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun PresetEditScreen(
    viewModel: PresetEditViewModel,
    presetId: String?,  // null для нового пресета
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onNavigateBack: () -> Unit,
    onImportPreset: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val newPresetName = stringResource(R.string.new_preset)
    
    // Локальное состояние для имени пресета
    var presetName by remember(uiState.editingPresetName) { 
        mutableStateOf(uiState.editingPresetName.ifEmpty { newPresetName }) 
    }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var hasChanges by remember { mutableStateOf(false) }
    
    // Флаг для предотвращения повторных навигаций (локальный debounce)
    var isNavigating by remember { mutableStateOf(false) }
    
    // Состояние для Snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    
    // Инициализируем редактируемый пресет в ViewModel
    LaunchedEffect(presetId) {
        if (presetId != null) {
            viewModel.startEditingPreset(presetId)
        } else {
            viewModel.startNewPreset()
        }
    }
    
    // Обработка событий от ViewModel
    LaunchedEffect(Unit) {
        viewModel.events.receiveAsFlow().collect { event ->
            when (event) {
                is PresetEditEvent.NavigateBack -> {
                    onNavigateBack()
                }
                is PresetEditEvent.PresetCreated -> {
                    // Завершаем редактирование без очистки состояния для плавной анимации
                    viewModel.finishEditingWithoutClear()
                }
                is PresetEditEvent.PresetSaved -> {
                    // Завершаем редактирование без очистки состояния для плавной анимации
                    viewModel.finishEditingWithoutClear()
                }
                is PresetEditEvent.ShowError -> {
                    snackbarHostState.showSnackbar(
                        message = event.message,
                        duration = SnackbarDuration.Short
                    )
                    isNavigating = false
                }
                is PresetEditEvent.ShowValidationErrors -> {
                    snackbarHostState.showSnackbar(
                        message = event.errors.joinToString("\n"),
                        duration = SnackbarDuration.Long
                    )
                    isNavigating = false
                }
            }
        }
    }
    
    // Проверяем наличие изменений
    hasChanges = uiState.originalPreset?.let { preset ->
        // Для существующего пресета - сравниваем с оригиналом
        preset.name != presetName || 
        uiState.editingFrequencyCurve != preset.frequencyCurve ||
        uiState.editingRelaxationModeSettings != preset.relaxationModeSettings
    } ?: if (uiState.editingPresetId != null) {
        // Оригинальный пресет ещё загружается - считаем что изменений нет
        false
    } else {
        // Для нового пресета - проверяем что имя изменено или есть кривая
        presetName != newPresetName || uiState.editingFrequencyCurve != null
    }
    
    fun saveAndNavigateBack() {
        // Предотвращаем повторный вызов во время навигации
        if (isNavigating) return
        isNavigating = true
        
        viewModel.savePreset(presetName)
        // Навигация произойдёт через event
    }
    
    fun navigateBackWithCheck() {
        // Предотвращаем повторный вызов во время навигации
        if (isNavigating) return
        isNavigating = true
        
        if (hasChanges) {
            showUnsavedDialog = true
            isNavigating = false  // Сбрасываем если показываем диалог
        } else {
            viewModel.cancelEditing()
            // Навигация произойдёт через event
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
            snackbarHost = {
                SnackbarHost(hostState = snackbarHostState)
            },
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
                            enabled = presetName.isNotBlank() && !uiState.isSaving
                        ) {
                            if (uiState.isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.Save, contentDescription = stringResource(R.string.save))
                            }
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
                        isPlaying = uiState.isActivePreset && uiState.isPlaying,
                        relaxationModeSettings = uiState.editingRelaxationModeSettings,
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
                
                // Настройки интерполяции пресета
                PresetSettingsCard(
                    interpolationType = editingCurve?.interpolationType ?: InterpolationType.LINEAR,
                    onInterpolationTypeChange = { viewModel.setInterpolationType(it) }
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Режим расслабления
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                RelaxationModeCard(
                    relaxationModeSettings = uiState.editingRelaxationModeSettings,
                    onRelaxationModeEnabledChange = { viewModel.setEditingRelaxationModeEnabled(it) },
                    onRelaxationModeChange = { viewModel.setEditingRelaxationMode(it) },
                    onCarrierReductionChange = { viewModel.setEditingCarrierReductionPercent(it) },
                    onBeatReductionChange = { viewModel.setEditingBeatReductionPercent(it) },
                    onRelaxationGapChange = { viewModel.setEditingRelaxationGapMinutes(it) },
                    onTransitionPeriodChange = { viewModel.setEditingTransitionPeriodMinutes(it) },
                    onRelaxationDurationChange = { viewModel.setEditingRelaxationDurationMinutes(it) },
                    onSmoothIntervalChange = { viewModel.setEditingSmoothIntervalMinutes(it) }
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
                TextButton(
                    onClick = {
                        showUnsavedDialog = false
                        saveAndNavigateBack()
                    },
                    enabled = !isNavigating && !uiState.isSaving
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        // Предотвращаем повторный вызов
                        if (isNavigating) return@TextButton
                        isNavigating = true
                        
                        showUnsavedDialog = false
                        viewModel.cancelEditing()
                        // Навигация произойдёт через event
                    },
                    enabled = !isNavigating
                ) {
                    Text(stringResource(R.string.do_not_save))
                }
            }
        )
    }
}