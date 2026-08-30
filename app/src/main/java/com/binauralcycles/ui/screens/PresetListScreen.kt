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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import com.binauralcycles.ui.components.MiniFrequencyGraph
import com.binauralcycles.viewmodel.BinauralViewModel
import com.binaural.core.audio.model.FrequencyCurve
import com.binaural.core.audio.model.RelaxationModeSettings
import kotlinx.datetime.LocalTime
import com.binauralcycles.R

// Время блокировки навигации после перехода на экран (для защиты от "пробивания" касаний)
private const val NAVIGATION_BLOCK_DURATION_MS = 500L

// Константа времени для неактивных карточек списка (U1): передаём её вместо
// «живого» telemetry.currentTime, чтобы Compose пропускал перекомпозицию
// неактивных карточек при каждом тике телеметрии (их индикатор всё равно скрыт).
private val INACTIVE_CARD_TIME = LocalTime(12, 0)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun PresetListScreen(
    viewModel: BinauralViewModel,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onPresetClick: (String) -> Unit,
    onEditPreset: (String) -> Unit,
    onCreatePreset: () -> Unit,
    onExportPreset: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    // Отдельный поток телеметрии: текущее время и флаг воспроизведения меняются
    // каждую секунду и не должны перекомпоновывать весь список пресетов.
    val telemetry by viewModel.telemetry.collectAsState()
    
    // Время последней навигации для защиты от быстрых повторных нажатий
    var lastNavigationTime by remember { mutableStateOf(0L) }
    
    // Функция проверки можно ли выполнять навигацию
    fun canNavigate(): Boolean {
        val now = System.currentTimeMillis()
        return now - lastNavigationTime > NAVIGATION_BLOCK_DURATION_MS
    }
    
    // Обновляем время навигации при выполнении действия
    fun recordNavigation() {
        lastNavigationTime = System.currentTimeMillis()
    }

    Scaffold(
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
                        // U1: только активная карточка получает «живое» время и частоты.
                        // Остальные получают константу, поэтому при каждом тике телеметрии
                        // Compose пропускает их перекомпозицию (параметры не меняются).
                        // У неактивных карточек индикатор всё равно скрыт (isPlaying = false),
                        // так что график визуально идентичен — экономим 6 из 7 перерисовок.
                        val cardTime = if (isActivePreset) telemetry.currentTime else INACTIVE_CARD_TIME
                        val (lowerFreq, upperFreq) = if (isActivePreset)
                            preset.getChannelFrequenciesAt(telemetry.currentTime)
                        else
                            (0.0f to 0.0f)
                        val carrierFreq = (lowerFreq + upperFreq) / 2.0f
                        val beatFreq = upperFreq - lowerFreq

                        PresetCard(
                            presetId = preset.id,
                            name = preset.name,
                            frequencyCurve = preset.frequencyCurve,
                            relaxationModeSettings = preset.relaxationModeSettings,
                            isActive = isActivePreset,
                            isPlaying = isActivePreset && telemetry.isPlaying,
                            currentCarrierFrequency = if (isActivePreset) carrierFreq else 0.0f,
                            currentBeatFrequency = if (isActivePreset) beatFreq else 0.0f,
                            currentTime = cardTime,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            onPlayClick = { onPresetClick(preset.id) },
                            onEditClick = {
                                if (canNavigate()) {
                                    recordNavigation()
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
                    
                    // Название пресета поверх графика (сверху слева).
                    // Без sharedBounds: раньше здесь висел общий элемент
                    // "preset-name-$presetId", но у экрана редактирования нет
                    // пары (название там только в TopAppBar), и односторонний
                    // shared-элемент лишь впустую поднимал вложенный слой в
                    // оверлей на каждом кадре перехода.
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
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