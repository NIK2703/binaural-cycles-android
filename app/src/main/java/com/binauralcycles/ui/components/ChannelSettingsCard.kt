package com.binauralcycles.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.binaural.core.audio.engine.SampleRate
import com.binaural.core.audio.model.ChannelSwapSettings
import com.binaural.core.audio.model.InterpolationType
import com.binaural.core.audio.model.VolumeNormalizationSettings
import com.binauralcycles.R

/**
 * Блок настроек пресета (перестановка каналов, нормализация, интерполяция)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetSettingsCard(
    channelSwapSettings: ChannelSwapSettings,
    volumeNormalizationSettings: VolumeNormalizationSettings,
    interpolationType: InterpolationType,
    isChannelsSwapped: Boolean,
    currentLeftFreq: Double,
    currentRightFreq: Double,
    onChannelSwapEnabledChange: (Boolean) -> Unit,
    onChannelSwapIntervalChange: (Int) -> Unit,
    onChannelSwapFadeEnabledChange: (Boolean) -> Unit,
    onChannelSwapFadeDurationChange: (Long) -> Unit,
    onVolumeNormalizationEnabledChange: (Boolean) -> Unit,
    onVolumeNormalizationStrengthChange: (Float) -> Unit,
    onInterpolationTypeChange: (InterpolationType) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Интерполяция по точкам
        ListItem(
            headlineContent = { Text(stringResource(R.string.point_interpolation)) },
            trailingContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = interpolationType == InterpolationType.LINEAR,
                        onClick = { onInterpolationTypeChange(InterpolationType.LINEAR) },
                        label = { Text(stringResource(R.string.linear)) }
                    )
                    FilterChip(
                        selected = interpolationType == InterpolationType.CUBIC_SPLINE,
                        onClick = { onInterpolationTypeChange(InterpolationType.CUBIC_SPLINE) },
                        label = { Text(stringResource(R.string.cubic)) }
                    )
                }
            }
        )
        
        HorizontalDivider()
        
        // Авто-перестановка каналов
        ListItem(
            headlineContent = { Text(stringResource(R.string.auto_channel_swap)) },
            supportingContent = { 
                Text(if (channelSwapSettings.enabled) stringResource(R.string.channel_swap_description) else stringResource(R.string.channel_swap_disabled))
            },
            trailingContent = {
                Switch(
                    checked = channelSwapSettings.enabled,
                    onCheckedChange = onChannelSwapEnabledChange
                )
            }
        )
        
        // Слайдер интервала перестановки (показываем только когда включено)
        if (channelSwapSettings.enabled) {
            DiscreteSlider(
                label = stringResource(R.string.swap_interval),
                value = channelSwapSettings.intervalSeconds,
                values = listOf(30, 60, 120, 300, 600, 900, 1800, 3600),
                valueLabel = formatInterval(channelSwapSettings.intervalSeconds),
                onValueChange = onChannelSwapIntervalChange,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            
            // Затухание при смене каналов
            ListItem(
                headlineContent = { Text(stringResource(R.string.smooth_fade)) },
                supportingContent = { 
                    Text(if (channelSwapSettings.fadeEnabled) stringResource(R.string.smooth_fade_description) else stringResource(R.string.instant_switch))
                },
                trailingContent = {
                    Switch(
                        checked = channelSwapSettings.fadeEnabled,
                        onCheckedChange = onChannelSwapFadeEnabledChange
                    )
                }
            )
            
            // Слайдер длительности затухания
            if (channelSwapSettings.fadeEnabled) {
                DiscreteSliderLong(
                    label = stringResource(R.string.fade_duration),
                    value = channelSwapSettings.fadeDurationMs,
                    values = listOf(250, 500, 1000, 1500, 2000, 3000, 5000),
                    valueLabel = formatFadeDurationLabel(channelSwapSettings.fadeDurationMs),
                    onValueChange = onChannelSwapFadeDurationChange,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
        
        HorizontalDivider()
        
        // Нормализация громкости
        ListItem(
            headlineContent = { Text(stringResource(R.string.volume_normalization)) },
            supportingContent = { 
                Text(stringResource(R.string.volume_normalization_description))
            },
            trailingContent = {
                Switch(
                    checked = volumeNormalizationSettings.enabled,
                    onCheckedChange = onVolumeNormalizationEnabledChange
                )
            }
        )
        
        // Слайдер силы нормализации
        if (volumeNormalizationSettings.enabled) {
            // Локальное состояние для мгновенного отклика UI
            var localStrength by remember(volumeNormalizationSettings.strength) { 
                mutableFloatStateOf(volumeNormalizationSettings.strength) 
            }
            
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(R.string.normalization_strength),
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
}

/**
 * Блок общих настроек приложения (качество звука, интервал обновления)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsCard(
    sampleRate: SampleRate,
    frequencyUpdateIntervalMs: Int,
    onSampleRateChange: (SampleRate) -> Unit,
    onFrequencyUpdateIntervalChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Интервал обновления частот - слайдер (от 1 сек до 1 мин)
        DiscreteSlider(
            label = stringResource(R.string.update_interval),
            value = frequencyUpdateIntervalMs,
            values = listOf(1000, 2000, 5000, 10000, 15000, 30000, 60000),
            valueLabel = formatUpdateInterval(frequencyUpdateIntervalMs),
            onValueChange = onFrequencyUpdateIntervalChange,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        
        // Качество аудио - раскрывающийся список
        var sampleRateExpanded by remember { mutableStateOf(false) }
        
        ListItem(
            headlineContent = { Text(stringResource(R.string.audio_quality)) },
            supportingContent = {
                Column {
                    Text(
                        when (sampleRate) {
                            SampleRate.ULTRA_LOW -> stringResource(R.string.quality_ultra_low)
                            SampleRate.VERY_LOW -> stringResource(R.string.quality_very_low)
                            SampleRate.LOW -> stringResource(R.string.quality_low)
                            SampleRate.MEDIUM -> stringResource(R.string.quality_standard)
                            SampleRate.HIGH -> stringResource(R.string.quality_high)
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ExposedDropdownMenuBox(
                        expanded = sampleRateExpanded,
                        onExpandedChange = { sampleRateExpanded = it },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = when (sampleRate) {
                                SampleRate.ULTRA_LOW -> "8 kHz"
                                SampleRate.VERY_LOW -> "16 kHz"
                                SampleRate.LOW -> "22 kHz"
                                SampleRate.MEDIUM -> "44 kHz"
                                SampleRate.HIGH -> "48 kHz"
                            },
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = sampleRateExpanded)
                            },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )
                        
                        ExposedDropdownMenu(
                            expanded = sampleRateExpanded,
                            onDismissRequest = { sampleRateExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("8 kHz") },
                                onClick = {
                                    onSampleRateChange(SampleRate.ULTRA_LOW)
                                    sampleRateExpanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                            DropdownMenuItem(
                                text = { Text("16 kHz") },
                                onClick = {
                                    onSampleRateChange(SampleRate.VERY_LOW)
                                    sampleRateExpanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                            DropdownMenuItem(
                                text = { Text("22 kHz") },
                                onClick = {
                                    onSampleRateChange(SampleRate.LOW)
                                    sampleRateExpanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                            DropdownMenuItem(
                                text = { Text("44 kHz") },
                                onClick = {
                                    onSampleRateChange(SampleRate.MEDIUM)
                                    sampleRateExpanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                            DropdownMenuItem(
                                text = { Text("48 kHz") },
                                onClick = {
                                    onSampleRateChange(SampleRate.HIGH)
                                    sampleRateExpanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                        }
                    }
                }
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
@Composable
fun formatInterval(seconds: Int): String {
    val secShort = stringResource(R.string.seconds_short)
    val minShort = stringResource(R.string.minutes_short)
    val hourShort = stringResource(R.string.hours_short)
    
    return when {
        seconds < 60 -> "$seconds $secShort"
        seconds < 3600 -> {
            val minutes = seconds / 60
            val secs = seconds % 60
            if (secs == 0) "$minutes $minShort" else "$minutes $minShort $secs $secShort"
        }
        else -> {
            val hours = seconds / 3600
            val minutes = (seconds % 3600) / 60
            if (minutes == 0) "$hours $hourShort" else "$hours $hourShort $minutes $minShort"
        }
    }
}

/**
 * Форматирование длительности затухания
 */
@Composable
fun formatFadeDuration(ms: Long): String {
    val seconds = ms / 1000
    val millis = ms % 1000
    val msShort = stringResource(R.string.milliseconds_short)
    val secFull = stringResource(R.string.seconds_full)
    
    return when {
        ms < 1000 -> "$millis $msShort"
        millis == 0L -> "$seconds $secFull"
        else -> "$seconds.${millis / 100} $secFull"
    }
}

/**
 * Форматирование длительности затухания для отображения в UI
 */
@Composable
fun formatFadeDurationLabel(ms: Long): String {
    val seconds = ms / 1000.0
    val secFull = stringResource(R.string.seconds_full)
    return "${seconds} $secFull"
}

/**
 * Форматирование интервала обновления частот
 */
@Composable
fun formatUpdateInterval(ms: Int): String {
    val msShort = stringResource(R.string.milliseconds_short)
    val secFull = stringResource(R.string.seconds_full)
    
    return when {
        ms < 1000 -> "$ms $msShort"
        else -> "${ms / 1000.0} $secFull"
    }
}