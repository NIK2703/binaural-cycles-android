package com.binauralcycles.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.binauralcycles.ui.components.*
import com.binauralcycles.viewmodel.BinauralViewModel
import com.binauralcycles.R
import androidx.compose.ui.input.nestedscroll.nestedScroll
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.window.WindowDialog

@OptIn(ExperimentalSharedTransitionApi::class)
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

    // Флаг для предотвращения повторных навигаций (локальный debounce)
    var isNavigating by remember { mutableStateOf(false) }

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
        uiState.editingRelaxationModeSettings != preset.relaxationModeSettings
    } ?: (presetName != newPresetName || uiState.editingFrequencyCurve != null)

    fun saveAndNavigateBack() {
        // Предотвращаем повторный вызов во время навигации
        if (isNavigating) return
        isNavigating = true

        val curve = uiState.editingFrequencyCurve ?: return
        if (presetId == null) {
            viewModel.createPreset(
                name = presetName,
                curve = curve,
                relaxationModeSettings = uiState.editingRelaxationModeSettings
            )
        } else {
            viewModel.saveEditingPreset(
                presetId = presetId,
                name = presetName,
                curve = curve,
                relaxationModeSettings = uiState.editingRelaxationModeSettings
            )
        }
        viewModel.finishEditingWithoutClear()
        onNavigateBack()
    }

    fun navigateBackWithCheck() {
        if (isNavigating) return
        isNavigating = true

        if (hasChanges) {
            showUnsavedDialog = true
            isNavigating = false
        } else {
            viewModel.cancelEditingInService()
            onNavigateBack()
        }
    }

    val focusManager = LocalFocusManager.current

    BackHandler(enabled = true) {
        focusManager.clearFocus()
        navigateBackWithCheck()
    }

    with(sharedTransitionScope) {
        val scrollBehavior = MiuixScrollBehavior()
        Scaffold(
            topBar = {
                TopAppBar(
                    title = if (presetId == null) stringResource(R.string.new_preset) else stringResource(R.string.edit_preset),
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = {
                            focusManager.clearFocus()
                            navigateBackWithCheck()
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    actions = {
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
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
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
                TextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = stringResource(R.string.preset_name),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // График частот (используем редактируемую кривую)
                val editingCurve = uiState.editingFrequencyCurve
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
                        isPlaying = isEditingActivePreset && uiState.isPlaying,
                        relaxationModeSettings = uiState.editingRelaxationModeSettings,
                        externalCurrentTime = uiState.currentTime,
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

                // Настройки пресета: интерполяция + режим расслабления (одна карточка)
                Card(modifier = Modifier.fillMaxWidth()) {
                    PresetSettingsCard(
                        interpolationType = editingCurve?.interpolationType ?: com.binaural.core.audio.model.InterpolationType.LINEAR,
                        onInterpolationTypeChange = { viewModel.setInterpolationType(it) }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

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
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Диалог несохранённых изменений
    if (showUnsavedDialog) {
        WindowDialog(
            show = showUnsavedDialog,
            title = stringResource(R.string.unsaved_changes_title),
            onDismissRequest = { showUnsavedDialog = false }
        ) {
            Text(
                text = stringResource(R.string.unsaved_changes_message),
                color = colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    text = stringResource(R.string.do_not_save),
                    onClick = {
                        if (isNavigating) return@TextButton
                        isNavigating = true

                        showUnsavedDialog = false
                        viewModel.cancelEditingInService()
                        onNavigateBack()
                    },
                    enabled = !isNavigating
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    text = stringResource(R.string.save),
                    onClick = {
                        showUnsavedDialog = false
                        saveAndNavigateBack()
                    },
                    enabled = !isNavigating
                )
            }
        }
    }
}
