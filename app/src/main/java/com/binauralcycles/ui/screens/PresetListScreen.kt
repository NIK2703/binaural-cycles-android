package com.binauralcycles.ui.screens

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import com.binauralcycles.ui.components.MiniFrequencyGraph
import com.binauralcycles.viewmodel.PresetListViewModel
import com.binauralcycles.viewmodel.events.PresetListEvent
import com.binaural.core.domain.model.FrequencyCurve
import com.binaural.core.domain.model.RelaxationModeSettings
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import com.binauralcycles.R
import com.binauralcycles.ui.theme.AudioConstants
import kotlinx.coroutines.flow.receiveAsFlow

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun PresetListScreen(
    viewModel: PresetListViewModel,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onPresetClick: (String) -> Unit,
    onEditPreset: (String) -> Unit,
    onCreatePreset: () -> Unit,
    onExportPreset: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    // Поднимаем состояние времени на уровень экрана - ОДНА корутина на весь список
    val currentTime = rememberCurrentTime(uiState.isPlaying)
    
    // Время последней навигации для защиты от быстрых повторных нажатий
    var lastNavigationTime by remember { mutableStateOf(0L) }
    
    // Состояние для Snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Получаем строки заранее для использования в LaunchedEffect
    val presetDeletedStr = stringResource(R.string.preset_deleted)
    val presetExportedStr = stringResource(R.string.preset_exported)
    val presetImportedStr = stringResource(R.string.preset_imported)
    val presetDuplicatedStr = stringResource(R.string.preset_duplicated)
    
    // Функция проверки можно ли выполнять навигацию
    fun canNavigate(): Boolean {
        val now = System.currentTimeMillis()
        return now - lastNavigationTime > AudioConstants.NAVIGATION_BLOCK_DURATION_MS
    }
    
    // Обновляем время навигации при выполнении действия
    fun recordNavigation() {
        lastNavigationTime = System.currentTimeMillis()
    }
    
    // Обработка событий от ViewModel
    LaunchedEffect(Unit) {
        viewModel.events.receiveAsFlow().collect { event ->
            when (event) {
                is PresetListEvent.ShowError -> {
                    snackbarHostState.showSnackbar(
                        message = event.message,
                        duration = SnackbarDuration.Long
                    )
                }
                is PresetListEvent.NavigateToEdit -> {
                    if (canNavigate()) {
                        recordNavigation()
                        onEditPreset(event.presetId)
                    }
                }
                is PresetListEvent.NavigateToNewPreset -> {
                    if (canNavigate()) {
                        recordNavigation()
                        onCreatePreset()
                    }
                }
                is PresetListEvent.PresetDeleted -> {
                    snackbarHostState.showSnackbar(
                        message = presetDeletedStr.format(event.presetName),
                        duration = SnackbarDuration.Short
                    )
                }
                is PresetListEvent.ShowExportSuccess -> {
                    snackbarHostState.showSnackbar(
                        message = presetExportedStr.format(event.fileName),
                        duration = SnackbarDuration.Short
                    )
                }
                is PresetListEvent.ShowImportSuccess -> {
                    snackbarHostState.showSnackbar(
                        message = presetImportedStr.format(event.presetName),
                        duration = SnackbarDuration.Short
                    )
                }
                is PresetListEvent.ShowDuplicateSuccess -> {
                    snackbarHostState.showSnackbar(
                        message = presetDuplicatedStr.format(event.presetName),
                        duration = SnackbarDuration.Short
                    )
                }
                is PresetListEvent.NavigateBack -> {
                    // Обработка навигации назад, если нужна
                }
            }
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.preset_list_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    IconButton(onClick = {
                        if (canNavigate()) {
                            recordNavigation()
                            onOpenSettings()
                        }
                    }) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (canNavigate()) {
                        recordNavigation()
                        viewModel.prepareNewPreset()
                        onCreatePreset()
                    }
                },
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_preset))
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            if (uiState.presets.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.no_presets_message),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp)
                ) {
                    items(uiState.presets, key = { it.id }) { preset ->
                        val isActivePreset = uiState.activePreset?.id == preset.id
                        val isEditingPreset = uiState.editingPresetId == preset.id
                        
                        // Если это редактируемый пресет и есть editingFrequencyCurve, используем её для анимации
                        val displayCurve = if (isEditingPreset && uiState.editingFrequencyCurve != null) {
                            uiState.editingFrequencyCurve!!
                        } else {
                            preset.frequencyCurve
                        }
                        
                        // Используем методы BinauralPreset для учёта виртуальных точек расслабления
                        val carrierFreq = preset.getCarrierFrequencyAt(currentTime.value)
                        val beatFreq = preset.getBeatFrequencyAt(currentTime.value)
                        
                        PresetCard(
                            presetId = preset.id,
                            name = preset.name,
                            frequencyCurve = displayCurve,
                            relaxationModeSettings = preset.relaxationModeSettings,
                            isActive = isActivePreset,
                            isPlaying = isActivePreset && uiState.isPlaying,
                            currentCarrierFrequency = carrierFreq,
                            currentBeatFrequency = beatFreq,
                            currentTime = currentTime.value, // Передаём время из родителя
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            onPlayClick = { onPresetClick(preset.id) },
                            onEditClick = { 
                                if (canNavigate()) {
                                    recordNavigation()
                                    viewModel.prepareEditingPreset(preset.id)
                                    onEditPreset(preset.id)
                                }
                            },
                            onExportClick = { onExportPreset(preset.id) },
                            onDuplicateClick = { viewModel.duplicatePreset(preset.id) },
                            onDeleteClick = { viewModel.deletePreset(preset.id) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Возвращает StateFlow с текущим временем, обновляемым каждые 5 секунд при воспроизведении
 * Оптимизация: одна корутина на весь экран вместо по одной на каждую карточку
 * 
 * Время всегда показывается текущее (не сбрасывается в 12:00 при паузе),
 * чтобы указатель на графике сразу появлялся в правильной позиции при выборе пресета.
 */
@Composable
private fun rememberCurrentTime(isPlaying: Boolean): State<LocalTime> {
    val currentTime = remember { mutableStateOf(LocalTime.fromSecondOfDay(12 * 3600)) }
    
    // Инициализируем текущим временем сразу при первом отображении
    LaunchedEffect(Unit) {
        val now = Clock.System.now()
        currentTime.value = now.toLocalDateTime(TimeZone.currentSystemDefault()).time
    }
    
    // Обновляем время каждые 5 секунд при воспроизведении
    // При паузе не обновляем, но и не сбрасываем в 12:00 - оставляем текущее время
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (true) {
                val now = Clock.System.now()
                currentTime.value = now.toLocalDateTime(TimeZone.currentSystemDefault()).time
                kotlinx.coroutines.delay(AudioConstants.TIME_UPDATE_INTERVAL_MS)
            }
        }
        // При isPlaying = false НЕ сбрасываем время в 12:00
        // Указатель остаётся на текущей позиции
    }
    
    return currentTime
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun PresetCard(
    presetId: String,
    name: String,
    frequencyCurve: FrequencyCurve,
    relaxationModeSettings: RelaxationModeSettings = RelaxationModeSettings(),
    isActive: Boolean,
    isPlaying: Boolean,
    currentCarrierFrequency: Float,
    currentBeatFrequency: Float,
    currentTime: LocalTime, // Получаем от родителя
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onPlayClick: () -> Unit,
    onEditClick: () -> Unit,
    onExportClick: () -> Unit = {},
    onDuplicateClick: () -> Unit = {},
    onDeleteClick: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showDropdownMenu by remember { mutableStateOf(false) }
    
    // Позиция долгого нажатия для центрирования меню
    var longPressOffset by remember { mutableStateOf(Offset.Zero) }
    
    // Ширина карточки и меню для расчёта смещения
    var cardWidth by remember { mutableStateOf(0) }
    var menuWidth by remember { mutableStateOf(0) }
    
    val density = LocalDensity.current

    Box(
        modifier = Modifier.fillMaxWidth()
    ) {
        with(sharedTransitionScope) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .sharedBounds(
                        sharedContentState = rememberSharedContentState(
                            key = "preset-$presetId"
                        ),
                        animatedVisibilityScope = animatedVisibilityScope,
                        clipInOverlayDuringTransition = OverlayClip(MaterialTheme.shapes.large)
                    )
                    .onGloballyPositioned { coordinates ->
                        cardWidth = coordinates.size.width
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onPlayClick() },
                            onLongPress = { offset ->
                                longPressOffset = offset
                                showDropdownMenu = true
                            }
                        )
                    },
                colors = CardDefaults.cardColors(
                    containerColor = if (isActive)
                        MaterialTheme.colorScheme.secondaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                )
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // График на весь размер карточки
                    MiniFrequencyGraph(
                        frequencyCurve = frequencyCurve,
                        modifier = Modifier.fillMaxSize(),
                        isPlaying = isPlaying,
                        currentTime = currentTime,
                        currentCarrierFrequency = currentCarrierFrequency,
                        currentBeatFrequency = currentBeatFrequency,
                        relaxationModeSettings = relaxationModeSettings
                    )
                    
                    // Название пресета поверх графика (сверху слева)
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .sharedBounds(
                                sharedContentState = rememberSharedContentState(
                                    key = "preset-name-$presetId"
                                ),
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                    )
                }
            }
        }
        
        // Контекстное меню (по центру от позиции долгого нажатия)
        // Рассчитываем смещение по горизонтали, чтобы меню было центрировано относительно позиции нажатия
        val menuOffsetX = if (cardWidth > 0 && menuWidth > 0) {
            with(density) {
                // Позиция нажатия относительно левого края карточки минус половина ширины меню
                // DropdownMenu anchor находится слева, поэтому смещение = x - menuWidth/2
                (longPressOffset.x - menuWidth / 2f).toInt()
            }
        } else 0
        
        DropdownMenu(
            expanded = showDropdownMenu,
            onDismissRequest = { showDropdownMenu = false },
            modifier = Modifier.onGloballyPositioned { coordinates ->
                menuWidth = coordinates.size.width
            },
            offset = DpOffset(with(density) { menuOffsetX.toDp() }, 0.dp)
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.edit)) },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = {
                    showDropdownMenu = false
                    onEditClick()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.duplicate)) },
                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                onClick = {
                    showDropdownMenu = false
                    onDuplicateClick()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.export)) },
                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                onClick = {
                    showDropdownMenu = false
                    onExportClick()
                }
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete)) },
                leadingIcon = { 
                    Icon(
                        Icons.Default.Delete, 
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    ) 
                },
                onClick = {
                    showDropdownMenu = false
                    showDeleteDialog = true
                }
            )
        }
    }
    
    // Диалог подтверждения удаления
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_preset_title)) },
            text = { Text(stringResource(R.string.delete_preset_message, name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDeleteClick()
                    }
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}