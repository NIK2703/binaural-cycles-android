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
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Бинауральные циклы") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreatePreset,
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(Icons.Default.Add, contentDescription = "Добавить пресет")
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
                        text = "Нет сохранённых пресетов.\nНажмите + для создания нового.",
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
    
    // Текущее время для отображения позиции воспроизведения
    val currentTime = remember { mutableStateOf(LocalTime(12, 0)) }
    
    // Обновляем текущее время при воспроизведении
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (true) {
                val now = Clock.System.now()
                currentTime.value = now.toLocalDateTime(TimeZone.currentSystemDefault()).time
                kotlinx.coroutines.delay(5000)
            }
        }
    }
    
    // Устанавливаем начальное время
    LaunchedEffect(Unit) {
        val now = Clock.System.now()
        currentTime.value = now.toLocalDateTime(TimeZone.currentSystemDefault()).time
    }
    
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
                        currentTime = currentTime.value,
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
                text = { Text("Редактировать") },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = {
                    showDropdownMenu = false
                    onEditClick()
                }
            )
            DropdownMenuItem(
                text = { Text("Дублировать") },
                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                onClick = {
                    showDropdownMenu = false
                    onDuplicateClick()
                }
            )
            DropdownMenuItem(
                text = { Text("Экспортировать") },
                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                onClick = {
                    showDropdownMenu = false
                    onExportClick()
                }
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Удалить") },
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
            title = { Text("Удалить пресет?") },
            text = { Text("Пресет \"$name\" будет удалён безвозвратно.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDeleteClick()
                    }
                ) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}