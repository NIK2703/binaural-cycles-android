package com.binauralcycles.ui.screens

import android.app.Activity
import android.content.Intent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.binauralcycles.ui.components.MiniFrequencyGraph
import com.binauralcycles.viewmodel.BinauralViewModel
import com.binaural.core.audio.model.FrequencyCurve
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import com.binauralcycles.R

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

    // Поднимаем состояние времени на уровень экрана - ОДНА корутина на весь список
    val currentTime = rememberCurrentTime(uiState.isPlaying)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.preset_list_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreatePreset,
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
                    contentPadding = PaddingValues(top = 8.dp)
                ) {
                    items(uiState.presets, key = { it.id }) { preset ->
                        PresetCard(
                            presetId = preset.id,
                            name = preset.name,
                            frequencyCurve = preset.frequencyCurve,
                            isActive = uiState.activePreset?.id == preset.id,
                            isPlaying = uiState.activePreset?.id == preset.id && uiState.isPlaying,
                            currentCarrierFrequency = uiState.currentCarrierFrequency,
                            currentBeatFrequency = uiState.currentBeatFrequency,
                            currentTime = currentTime.value, // Передаём время из родителя
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            onPlayClick = { onPresetClick(preset.id) },
                            onEditClick = { onEditPreset(preset.id) },
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
 */
@Composable
private fun rememberCurrentTime(isPlaying: Boolean): State<LocalTime> {
    val currentTime = remember { mutableStateOf(LocalTime.fromSecondOfDay(12 * 3600)) }
    
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (true) {
                val now = Clock.System.now()
                currentTime.value = now.toLocalDateTime(TimeZone.currentSystemDefault()).time
                kotlinx.coroutines.delay(5000)
            }
        } else {
            currentTime.value = LocalTime.fromSecondOfDay(12 * 3600)
        }
    }

    LaunchedEffect(Unit) {
        val now = Clock.System.now()
        currentTime.value = now.toLocalDateTime(TimeZone.currentSystemDefault()).time
    }
    
    return currentTime
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun PresetCard(
    presetId: String,
    name: String,
    frequencyCurve: FrequencyCurve,
    isActive: Boolean,
    isPlaying: Boolean,
    currentCarrierFrequency: Double,
    currentBeatFrequency: Double,
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

    // Удаляем внутреннюю корутину - время приходит от родителя (оптимизация)
    
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
                    .combinedClickable(
                        onClick = onPlayClick,
                        onLongClick = { showDropdownMenu = true }
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = if (isActive)
                        MaterialTheme.colorScheme.secondaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
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
                        currentBeatFrequency = currentBeatFrequency
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
        
        // Контекстное меню (сверху справа над карточкой)
        DropdownMenu(
            expanded = showDropdownMenu,
            onDismissRequest = { showDropdownMenu = false },
            modifier = Modifier.align(Alignment.TopEnd)
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