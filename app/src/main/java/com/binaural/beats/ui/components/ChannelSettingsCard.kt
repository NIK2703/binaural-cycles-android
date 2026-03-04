package com.binaural.beats.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.binaural.core.audio.engine.SampleRate

/**
 * Компактная карточка настроек каналов
 */
@Composable
fun ChannelSettingsCard(
    channelSwapEnabled: Boolean,
    channelSwapIntervalSeconds: Int,
    channelSwapFadeEnabled: Boolean,
    channelSwapFadeDurationMs: Long,
    isChannelsSwapped: Boolean,
    volumeNormalizationEnabled: Boolean,
    volumeNormalizationStrength: Float,
    sampleRate: SampleRate,
    currentLeftFreq: Double,
    currentRightFreq: Double,
    onChannelSwapEnabledChange: (Boolean) -> Unit,
    onChannelSwapIntervalChange: (Int) -> Unit,
    onChannelSwapFadeEnabledChange: (Boolean) -> Unit,
    onChannelSwapFadeDurationChange: (Long) -> Unit,
    onVolumeNormalizationEnabledChange: (Boolean) -> Unit,
    onVolumeNormalizationStrengthChange: (Float) -> Unit,
    onSampleRateChange: (SampleRate) -> Unit
) {
    var showSwapIntervalDialog by remember { mutableStateOf(false) }
    var showFadeDurationDialog by remember { mutableStateOf(false) }
    var showSampleRateDialog by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            // Заголовок
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Настройки",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.weight(1f))
                
                // Качество аудио - всегда видно
                AssistChip(
                    onClick = { showSampleRateDialog = true },
                    label = { 
                        Text(
                            when (sampleRate) {
                                SampleRate.LOW -> "22kHz"
                                SampleRate.MEDIUM -> "44kHz"
                                SampleRate.HIGH -> "48kHz"
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    modifier = Modifier.height(24.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Перестановка каналов
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.SwapHoriz,
                        contentDescription = null,
                        tint = if (channelSwapEnabled) 
                            MaterialTheme.colorScheme.primary 
                        else 
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            "Авто-перестановка",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (channelSwapEnabled) {
                            Text(
                                text = "%s • %s".format(
                                    formatInterval(channelSwapIntervalSeconds),
                                    if (isChannelsSwapped) "Л↔П" else "Л-П"
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (channelSwapEnabled) {
                        TextButton(
                            onClick = { showSwapIntervalDialog = true },
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Text(
                                formatInterval(channelSwapIntervalSeconds),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    Switch(
                        checked = channelSwapEnabled,
                        onCheckedChange = onChannelSwapEnabledChange,
                        modifier = Modifier.height(24.dp)
                    )
                }
            }
            
            // Затухание при смене каналов (показываем только когда включена авто-перестановка)
            if (channelSwapEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Затухание",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (channelSwapFadeEnabled) {
                            TextButton(
                                onClick = { showFadeDurationDialog = true },
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Text(
                                    formatFadeDuration(channelSwapFadeDurationMs),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                    Switch(
                        checked = channelSwapFadeEnabled,
                        onCheckedChange = onChannelSwapFadeEnabledChange,
                        modifier = Modifier.height(20.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            // Нормализация громкости
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Equalizer,
                        contentDescription = null,
                        tint = if (volumeNormalizationEnabled) 
                            MaterialTheme.colorScheme.primary 
                        else 
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Нормализация",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = volumeNormalizationEnabled,
                    onCheckedChange = onVolumeNormalizationEnabledChange,
                    modifier = Modifier.height(24.dp)
                )
            }
            
            if (volumeNormalizationEnabled) {
                Slider(
                    value = volumeNormalizationStrength,
                    onValueChange = onVolumeNormalizationStrengthChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 22.dp),
                    valueRange = 0f..1f
                )
            }
        }
    }
    
    if (showSwapIntervalDialog) {
        SwapIntervalDialog(
            currentInterval = channelSwapIntervalSeconds,
            onDismiss = { showSwapIntervalDialog = false },
            onConfirm = { seconds -> 
                onChannelSwapIntervalChange(seconds)
                showSwapIntervalDialog = false
            }
        )
    }
    
    if (showSampleRateDialog) {
        SampleRateDialog(
            currentSampleRate = sampleRate,
            onDismiss = { showSampleRateDialog = false },
            onConfirm = { rate -> 
                onSampleRateChange(rate)
                showSampleRateDialog = false
            }
        )
    }
    
    if (showFadeDurationDialog) {
        FadeDurationDialog(
            currentDuration = channelSwapFadeDurationMs,
            onDismiss = { showFadeDurationDialog = false },
            onConfirm = { duration -> 
                onChannelSwapFadeDurationChange(duration)
                showFadeDurationDialog = false
            }
        )
    }
}

/**
 * Форматирование интервала
 */
fun formatInterval(seconds: Int): String {
    return when {
        seconds < 60 -> "$seconds сек"
        seconds < 3600 -> {
            val minutes = seconds / 60
            val secs = seconds % 60
            if (secs == 0) "$minutes мин" else "$minutes мин $secs сек"
        }
        else -> {
            val hours = seconds / 3600
            val minutes = (seconds % 3600) / 60
            if (minutes == 0) "$hours ч" else "$hours ч $minutes мин"
        }
    }
}

/**
 * Форматирование длительности затухания
 */
fun formatFadeDuration(ms: Long): String {
    val seconds = ms / 1000
    val millis = ms % 1000
    return when {
        ms < 1000 -> "$millis мс"
        millis == 0L -> "$seconds сек"
        else -> "$seconds.${millis / 100} сек"
    }
}

/**
 * Форматирование длительности затухания для отображения в UI
 */
fun formatFadeDurationLabel(ms: Long): String {
    val seconds = ms / 1000.0
    return "${seconds} сек"
}

@Composable
private fun SwapIntervalDialog(
    currentInterval: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var selectedInterval by remember { mutableStateOf(currentInterval) }
    val presetIntervals = listOf(
        30 to "30 сек", 
        60 to "1 мин", 
        120 to "2 мин", 
        300 to "5 мин", 
        600 to "10 мин", 
        900 to "15 мин", 
        1800 to "30 мин", 
        3600 to "1 час"
    )
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Интервал перестановки", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                presetIntervals.forEach { (seconds, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedInterval = seconds }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedInterval == seconds,
                            onClick = { selectedInterval = seconds }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { 
            TextButton(onClick = { onConfirm(selectedInterval) }) { Text("OK") } 
        },
        dismissButton = { 
            TextButton(onClick = onDismiss) { Text("Отмена") } 
        }
    )
}

@Composable
private fun SampleRateDialog(
    currentSampleRate: SampleRate,
    onDismiss: () -> Unit,
    onConfirm: (SampleRate) -> Unit
) {
    var selectedRate by remember { mutableStateOf(currentSampleRate) }
    val sampleRateOptions = listOf(
        SampleRate.LOW to "Низкое (22050 Гц) — экономия батареи",
        SampleRate.MEDIUM to "Стандарт (44100 Гц)",
        SampleRate.HIGH to "Высокое (48000 Гц)"
    )
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Качество аудио", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text(
                    "Более низкая частота снижает расход батареи.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                sampleRateOptions.forEach { (rate, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedRate = rate }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedRate == rate,
                            onClick = { selectedRate = rate }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { 
            TextButton(onClick = { onConfirm(selectedRate) }) { Text("OK") } 
        },
        dismissButton = { 
            TextButton(onClick = onDismiss) { Text("Отмена") } 
        }
    )
}

@Composable
private fun FadeDurationDialog(
    currentDuration: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    var selectedDuration by remember { mutableStateOf(currentDuration) }
    val presetDurations = listOf(
        250L to "0.25 сек",
        500L to "0.5 сек",
        1000L to "1 сек",
        1500L to "1.5 сек",
        2000L to "2 сек",
        3000L to "3 сек",
        5000L to "5 сек"
    )
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Длительность затухания", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text(
                    "Время плавного затухания и нарастания звука при смене каналов.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                presetDurations.forEach { (duration, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedDuration = duration }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedDuration == duration,
                            onClick = { selectedDuration = duration }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { 
            TextButton(onClick = { onConfirm(selectedDuration) }) { Text("OK") } 
        },
        dismissButton = { 
            TextButton(onClick = onDismiss) { Text("Отмена") } 
        }
    )
}
