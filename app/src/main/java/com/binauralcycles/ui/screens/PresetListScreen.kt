package com.binauralcycles.ui.screens

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.binauralcycles.ui.components.ListPopupDefaults
import com.binauralcycles.ui.components.PressPointMenuPositionProvider
import com.binauralcycles.ui.components.MiniFrequencyGraph
import com.binauralcycles.ui.components.PresetMenuRow
import com.binauralcycles.viewmodel.BinauralViewModel
import com.binaural.core.audio.model.FrequencyCurve
import com.binaural.core.audio.model.RelaxationModeSettings
import kotlinx.datetime.LocalTime
import com.binauralcycles.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

// Время блокировки навигации после перехода на экран (для защиты от "пробивания" касаний)
private const val NAVIGATION_BLOCK_DURATION_MS = 500L

@OptIn(ExperimentalSharedTransitionApi::class)
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

    // Время последней навигации для защиты от быстрых повторных нажатий
    var lastNavigationTime by remember { mutableStateOf(0L) }

    fun canNavigate(): Boolean {
        val now = System.currentTimeMillis()
        return now - lastNavigationTime > NAVIGATION_BLOCK_DURATION_MS
    }

    fun recordNavigation() {
        lastNavigationTime = System.currentTimeMillis()
    }

    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.preset_list_title),
                scrollBehavior = scrollBehavior,
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
                }
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
                        style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .scrollEndHaptic()
                        .overScrollVertical()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp)
                ) {
                    items(uiState.presets, key = { it.id }) { preset ->
                        val isActivePreset = uiState.activePreset?.id == preset.id
                        val (lowerFreq, upperFreq) = preset.getChannelFrequenciesAt(uiState.currentTime)
                        val carrierFreq = (lowerFreq + upperFreq) / 2.0f
                        val beatFreq = upperFreq - lowerFreq

                        PresetCard(
                            presetId = preset.id,
                            name = preset.name,
                            frequencyCurve = preset.frequencyCurve,
                            relaxationModeSettings = preset.relaxationModeSettings,
                            isActive = isActivePreset,
                            isPlaying = isActivePreset && uiState.isPlaying,
                            currentCarrierFrequency = carrierFreq,
                            currentBeatFrequency = beatFreq,
                            currentTime = uiState.currentTime,
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

@OptIn(ExperimentalSharedTransitionApi::class)
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
    currentTime: LocalTime,
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

    // Точка зажатия пальца относительно левого края карточки
    var longPressX by remember { mutableStateOf(0f) }

    var cardWidth by remember { mutableStateOf(0) }

    val density = LocalDensity.current

    // Меню открывается горизонтально в месте зажатия пальца
    val pressPointProvider = remember { PressPointMenuPositionProvider { longPressX } }

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
                    )
                    .onGloballyPositioned { coordinates ->
                        cardWidth = coordinates.size.width
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onPlayClick() },
                            onLongPress = { offset ->
                                longPressX = offset.x
                                showDropdownMenu = true
                            }
                        )
                    },
                colors = CardDefaults.defaultColors(
                    color = if (isActive)
                        colorScheme.secondaryContainer
                    else
                        colorScheme.surfaceVariant.copy(alpha = 0.7f)
                )
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Акцентный цвет графика - только у активного пресета,
                    // у остальных - нейтральный серый
                    val graphInkColor = if (isActive)
                        colorScheme.primary
                    else
                        colorScheme.onSurfaceSecondary

                    MiniFrequencyGraph(
                        frequencyCurve = frequencyCurve,
                        modifier = Modifier.fillMaxSize(),
                        primaryColor = graphInkColor,
                        indicatorColor = if (isActive) colorScheme.error else colorScheme.onSurfaceSecondary,
                        relaxationColor = if (isActive) colorScheme.tertiaryContainer else colorScheme.onSurfaceSecondary,
                        isPlaying = isPlaying,
                        currentTime = currentTime,
                        currentCarrierFrequency = currentCarrierFrequency,
                        currentBeatFrequency = currentBeatFrequency,
                        relaxationModeSettings = relaxationModeSettings
                    )

                    Text(
                        text = name,
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
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

        OverlayListPopup(
            show = showDropdownMenu,
            popupPositionProvider = pressPointProvider,
            alignment = PopupPositionProvider.Align.TopStart,
            onDismissRequest = { showDropdownMenu = false },
            content = {
                ListPopupColumn {
                    PresetMenuRow(
                        text = stringResource(R.string.edit),
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            showDropdownMenu = false
                            onEditClick()
                        }
                    )
                    PresetMenuRow(
                        text = stringResource(R.string.duplicate),
                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                        onClick = {
                            showDropdownMenu = false
                            onDuplicateClick()
                        }
                    )
                    PresetMenuRow(
                        text = stringResource(R.string.export),
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                        onClick = {
                            showDropdownMenu = false
                            onExportClick()
                        }
                    )
                    HorizontalDivider()
                    PresetMenuRow(
                        text = stringResource(R.string.delete),
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = colorScheme.error
                            )
                        },
                        onClick = {
                            showDropdownMenu = false
                            showDeleteDialog = true
                        }
                    )
                }
            }
        )
    }

    if (showDeleteDialog) {
        WindowDialog(
            show = showDeleteDialog,
            title = stringResource(R.string.delete_preset_title),
            onDismissRequest = { showDeleteDialog = false }
        ) {
            Text(
                text = stringResource(R.string.delete_preset_message, name),
                color = colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = { showDeleteDialog = false }
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    text = stringResource(R.string.delete),
                    onClick = {
                        showDeleteDialog = false
                        onDeleteClick()
                    }
                )
            }
        }
    }
}
