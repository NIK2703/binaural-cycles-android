package com.binaural.beats.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.binaural.core.audio.engine.SampleRate
import com.binaural.core.audio.model.InterpolationType

/**
 * Блок настроек с дискретными слайдерами
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
    frequencyUpdateIntervalMs: Int,
    currentLeftFreq: Double,
    currentRightFreq: Double,
    interpolationType: InterpolationType,
    onChannelSwapEnabledChange: (Boolean) -> Unit,
    onChannelSwapIntervalChange: (Int) -> Unit,
    onChannelSwapFadeEnabledChange: (Boolean) -> Unit,
    onChannelSwapFadeDurationChange: (Long) -> Unit,
    onVolumeNormalizationEnabledChange: (Boolean) -> Unit,
    onVolumeNormalizationStrengthChange: (Float) -> Unit,
    onSampleRateChange: (SampleRate) -> Unit,
    onFrequencyUpdateIntervalChange: (Int) -> Unit,
    onInterpolationTypeChange: (InterpolationType) -> Unit
) {
    var showSampleRateDialog by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Интерполяция по точкам
        ListItem(
            headlineContent = { Text("Интерполяция по точкам") },
            supportingContent = { 
                Text(when (interpolationType) {
                    InterpolationType.LINEAR -> "Прямые линии между точками"
                    InterpolationType.CUBIC_SPLINE -> "Плавные кривые Catmull-Rom"
                })
            },
            trailingContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = interpolationType == InterpolationType.LINEAR,
                        onClick = { onInterpolationTypeChange(InterpolationType.LINEAR) },
                        label = { Text("Линейная") }
                    )
                    FilterChip(
                        selected = interpolationType == InterpolationType.CUBIC_SPLINE,
                        onClick = { onInterpolationTypeChange(InterpolationType.CUBIC_SPLINE) },
                        label = { Text("Кубическая") }
                    )
                }
            }
        )
        
        HorizontalDivider()
        
        // Интервал обновления частот - слайдер
        DiscreteSlider(
            label = "Интервал обновления",
            value = frequencyUpdateIntervalMs,
            values = listOf(100, 250, 500, 1000, 2000, 5000),
            valueLabel = formatUpdateInterval(frequencyUpdateIntervalMs),
            onValueChange = onFrequencyUpdateIntervalChange,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        
        // Качество аудио
        ListItem(
            headlineContent = { Text("Качество аудио") },
            supportingContent = { 
                Text(when (sampleRate) {
                    SampleRate.LOW -> "Низкое (22 kHz) — экономия батареи"
                    SampleRate.MEDIUM -> "Стандарт (44 kHz)"
                    SampleRate.HIGH -> "Высокое (48 kHz)"
                })
            },
            trailingContent = {
                FilledTonalButton(
                    onClick = { showSampleRateDialog = true },
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Text(when (sampleRate) {
                        SampleRate.LOW -> "22kHz"
                        SampleRate.MEDIUM -> "44kHz"
                        SampleRate.HIGH -> "48kHz"
                    })
                }
            }
        )
        
        HorizontalDivider()
        
        // Авто-перестановка каналов
        ListItem(
            headlineContent = { Text("Авто-перестановка каналов") },
            supportingContent = { 
                Text(if (channelSwapEnabled) "Каналы меняются местами для равномерной нагрузки" else "Выключено")
            },
            trailingContent = {
                Switch(
                    checked = channelSwapEnabled,
                    onCheckedChange = onChannelSwapEnabledChange
                )
            }
        )
        
        // Слайдер интервала перестановки (показываем только когда включено)
        if (channelSwapEnabled) {
            DiscreteSlider(
                label = "Интервал перестановки",
                value = channelSwapIntervalSeconds,
                values = listOf(30, 60, 120, 300, 600, 900, 1800, 3600),
                valueLabel = formatInterval(channelSwapIntervalSeconds),
                onValueChange = onChannelSwapIntervalChange,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            
            // Затухание при смене каналов
            ListItem(
                headlineContent = { Text("Плавное затухание") },
                supportingContent = { 
                    Text(if (channelSwapFadeEnabled) "Плавный переход при смене каналов" else "Мгновенное переключение")
                },
                trailingContent = {
                    Switch(
                        checked = channelSwapFadeEnabled,
                        onCheckedChange = onChannelSwapFadeEnabledChange
                    )
                }
            )
            
            // Слайдер длительности затухания
            if (channelSwapFadeEnabled) {
                DiscreteSliderLong(
                    label = "Время затухания",
                    value = channelSwapFadeDurationMs,
                    values = listOf(250, 500, 1000, 1500, 2000, 3000, 5000),
                    valueLabel = formatFadeDurationLabel(channelSwapFadeDurationMs),
                    onValueChange = onChannelSwapFadeDurationChange,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
        
        HorizontalDivider()
        
        // Нормализация громкости
        ListItem(
            headlineContent = { Text("Нормализация громкости") },
            supportingContent = { 
                Text("Компенсация изменений громкости при изменении частоты")
            },
            trailingContent = {
                Switch(
                    checked = volumeNormalizationEnabled,
                    onCheckedChange = onVolumeNormalizationEnabledChange
                )
            }
        )
        
        // Слайдер силы нормализации
        if (volumeNormalizationEnabled) {
            // Локальное состояние для мгновенного отклика UI
            var localStrength by remember(volumeNormalizationStrength) { 
                mutableFloatStateOf(volumeNormalizationStrength) 
            }
            
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Сила нормализации",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "${(localStrength * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = localStrength,
                    onValueChange = { localStrength = it },
                    onValueChangeFinished = { 
                        onVolumeNormalizationStrengthChange(localStrength) 
                    },
                    modifier = Modifier.fillMaxWidth(),
                    valueRange = 0f..1f
                )
            }
        }
    }
    
    // Диалог выбора частоты дискретизации
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
}

/**
 * Дискретный слайдер для Int значений
 */
@Composable
fun DiscreteSlider(
    label: String,
    value: Int,
    values: List<Int>,
    valueLabel: String,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                valueLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = values.indexOf(value).toFloat(),
            onValueChange = { index ->
                val newIndex = index.toInt().coerceIn(0, values.lastIndex)
                onValueChange(values[newIndex])
            },
            modifier = Modifier.fillMaxWidth(),
            valueRange = 0f..(values.size - 1).toFloat(),
            steps = values.size - 2
        )
    }
}

/**
 * Дискретный слайдер для Long значений
 */
@Composable
fun DiscreteSliderLong(
    label: String,
    value: Long,
    values: List<Long>,
    valueLabel: String,
    onValueChange: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                valueLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = values.indexOf(value).toFloat(),
            onValueChange = { index ->
                val newIndex = index.toInt().coerceIn(0, values.lastIndex)
                onValueChange(values[newIndex])
            },
            modifier = Modifier.fillMaxWidth(),
            valueRange = 0f..(values.size - 1).toFloat(),
            steps = values.size - 2
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

/**
 * Форматирование интервала обновления частот
 */
fun formatUpdateInterval(ms: Int): String {
    return when {
        ms < 1000 -> "$ms мс"
        else -> "${ms / 1000.0} с"
    }
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
        title = { Text("Качество аудио") },
        text = {
            Column {
                Text(
                    "Более низкая частота снижает расход батареи.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                sampleRateOptions.forEach { (rate, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedRate == rate,
                            onClick = { selectedRate = rate }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
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