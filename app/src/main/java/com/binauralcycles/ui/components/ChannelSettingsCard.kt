package com.binauralcycles.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.binaural.core.audio.engine.SampleRate
import com.binaural.core.audio.model.ChannelSwapSettings
import com.binaural.core.audio.model.InterpolationType
import com.binaural.core.audio.model.NormalizationType
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
    splineTension: Float,
    isChannelsSwapped: Boolean,
    currentLeftFreq: Double,
    currentRightFreq: Double,
    onChannelSwapEnabledChange: (Boolean) -> Unit,
    onChannelSwapIntervalChange: (Int) -> Unit,
    onChannelSwapFadeEnabledChange: (Boolean) -> Unit,
    onChannelSwapFadeDurationChange: (Long) -> Unit,
    onVolumeNormalizationEnabledChange: (Boolean) -> Unit,
    onVolumeNormalizationStrengthChange: (Float) -> Unit,
    onTemporalNormalizationEnabledChange: (Boolean) -> Unit,
    onInterpolationTypeChange: (InterpolationType) -> Unit,
    onSplineTensionChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Интерполяция по точкам
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.point_interpolation),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Text(
                text = stringResource(R.string.interpolation_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                FilterChip(
                    selected = interpolationType == InterpolationType.LINEAR,
                    onClick = { onInterpolationTypeChange(InterpolationType.LINEAR) },
                    label = { 
                        Text(
                            text = stringResource(R.string.linear),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        ) 
                    },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = interpolationType == InterpolationType.MONOTONE,
                    onClick = { onInterpolationTypeChange(InterpolationType.MONOTONE) },
                    label = { 
                        Text(
                            text = stringResource(R.string.monotone),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        ) 
                    },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = interpolationType == InterpolationType.CARDINAL,
                    onClick = { onInterpolationTypeChange(InterpolationType.CARDINAL) },
                    label = { 
                        Text(
                            text = stringResource(R.string.cardinal),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        ) 
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        
        // Слайдер натяжения для кардинального сплайна
        if (interpolationType == InterpolationType.CARDINAL) {
            var localTension by remember(splineTension) { mutableFloatStateOf(splineTension) }
            
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(R.string.spline_tension),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        String.format("%.2f", localTension),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = localTension,
                    onValueChange = { localTension = it },
                    onValueChangeFinished = {
                        onSplineTensionChange(localTension)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    valueRange = 0f..1f
                )
                Text(
                    stringResource(R.string.spline_tension_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        HorizontalDivider()
        
        // Нормализация громкости
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.volume_normalization),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Text(
                text = stringResource(R.string.volume_normalization_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                FilterChip(
                    selected = volumeNormalizationSettings.type == NormalizationType.NONE,
                    onClick = { onVolumeNormalizationEnabledChange(false) },
                    label = { 
                        Text(
                            text = stringResource(R.string.normalization_none),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        ) 
                    },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = volumeNormalizationSettings.type == NormalizationType.CHANNEL,
                    onClick = { onVolumeNormalizationEnabledChange(true) },
                    label = { 
                        Text(
                            text = stringResource(R.string.normalization_channel),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        ) 
                    },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = volumeNormalizationSettings.type == NormalizationType.TEMPORAL,
                    onClick = { onTemporalNormalizationEnabledChange(true) },
                    label = { 
                        Text(
                            text = stringResource(R.string.normalization_temporal),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        ) 
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        
        // Слайдер силы нормализации (показываем для CHANNEL и TEMPORAL)
        if (volumeNormalizationSettings.type != NormalizationType.NONE) {
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
                    valueRange = 0f..2f
                )
            }
            
            // Описание типа нормализации
            Text(
                text = when (volumeNormalizationSettings.type) {
                    NormalizationType.CHANNEL -> stringResource(R.string.normalization_channel_description)
                    NormalizationType.TEMPORAL -> stringResource(R.string.normalization_temporal_description)
                    NormalizationType.NONE -> ""
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        
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
    wavetableOptimizationEnabled: Boolean,
    wavetableSize: Int,
    onSampleRateChange: (SampleRate) -> Unit,
    onFrequencyUpdateIntervalChange: (Int) -> Unit,
    onWavetableOptimizationChange: (Boolean) -> Unit,
    onWavetableSizeChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Wavetable оптимизация
        ListItem(
            headlineContent = { Text(stringResource(R.string.wavetable_optimization)) },
            supportingContent = {
                Text(
                    if (wavetableOptimizationEnabled) stringResource(R.string.wavetable_optimization_enabled_desc)
                    else stringResource(R.string.wavetable_optimization_disabled_desc)
                )
            },
            trailingContent = {
                Switch(
                    checked = wavetableOptimizationEnabled,
                    onCheckedChange = onWavetableOptimizationChange
                )
            }
        )

        // Размер таблицы волн (показываем только когда оптимизация включена)
        if (wavetableOptimizationEnabled) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                DiscreteSliderWavetableSize(
                    label = stringResource(R.string.wavetable_size),
                    value = wavetableSize,
                    values = listOf(512, 1024, 2048, 4096),
                    valueLabel = formatWavetableSize(wavetableSize),
                    onValueChange = onWavetableSizeChange,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.wavetable_size_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider()

        // Интервал обновления частот - слайдер (от 1 сек до 1 мин)
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = stringResource(R.string.update_interval),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(R.string.update_interval_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            DiscreteSlider(
                label = "",
                value = frequencyUpdateIntervalMs,
                values = listOf(1000, 2000, 5000, 10000, 15000, 30000, 60000),
                valueLabel = formatUpdateInterval(frequencyUpdateIntervalMs),
                onValueChange = onFrequencyUpdateIntervalChange,
                modifier = Modifier.fillMaxWidth()
            )
        }
        
        // Качество аудио - раскрывающийся список
        var sampleRateExpanded by remember { mutableStateOf(false) }
        
        ListItem(
            headlineContent = { Text(stringResource(R.string.audio_quality)) },
            supportingContent = {
                Text(stringResource(R.string.audio_quality_description))
            }
        )
        
        ExposedDropdownMenuBox(
            expanded = sampleRateExpanded,
            onExpandedChange = { sampleRateExpanded = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = when (sampleRate) {
                    SampleRate.ULTRA_LOW -> stringResource(R.string.quality_ultra_low)
                    SampleRate.VERY_LOW -> stringResource(R.string.quality_very_low)
                    SampleRate.LOW -> stringResource(R.string.quality_low)
                    SampleRate.MEDIUM -> stringResource(R.string.quality_standard)
                    SampleRate.HIGH -> stringResource(R.string.quality_high)
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
                    text = { Text(stringResource(R.string.quality_ultra_low)) },
                    onClick = {
                        onSampleRateChange(SampleRate.ULTRA_LOW)
                        sampleRateExpanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.quality_very_low)) },
                    onClick = {
                        onSampleRateChange(SampleRate.VERY_LOW)
                        sampleRateExpanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.quality_low)) },
                    onClick = {
                        onSampleRateChange(SampleRate.LOW)
                        sampleRateExpanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.quality_standard)) },
                    onClick = {
                        onSampleRateChange(SampleRate.MEDIUM)
                        sampleRateExpanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.quality_high)) },
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

/**
 * Дискретный слайдер для размера таблицы волн
 */
@Composable
fun DiscreteSliderWavetableSize(
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
 * Форматирование размера таблицы волн
 */
@Composable
fun formatWavetableSize(size: Int): String {
    return "$size samples"
}