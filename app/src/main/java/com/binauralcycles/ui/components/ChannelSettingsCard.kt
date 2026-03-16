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
import com.binaural.core.audio.model.RelaxationMode
import com.binaural.core.audio.model.RelaxationModeSettings
import com.binaural.core.audio.model.VolumeNormalizationSettings
import com.binauralcycles.R

/**
 * Блок настроек интерполяции для пресета
 * Нормализация громкости вынесена в глобальные настройки приложения
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetSettingsCard(
    interpolationType: InterpolationType,
    onInterpolationTypeChange: (InterpolationType) -> Unit
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
            // Чипы интерполяции в две строки с уменьшенным расстоянием между строками
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy((-8).dp)
            ) {
                // Первая строка: Ступенчатая, Линейная
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = interpolationType == InterpolationType.STEP,
                        onClick = { onInterpolationTypeChange(InterpolationType.STEP) },
                        label = { 
                            Text(
                                text = stringResource(R.string.step),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            ) 
                        },
                        modifier = Modifier.weight(1f)
                    )
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
                }
                // Вторая строка: Монотонная, Кардинальная
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
        }
    }
}

/**
 * Блок глобальных настроек нормализации громкости
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VolumeNormalizationSettingsCard(
    volumeNormalizationSettings: VolumeNormalizationSettings,
    onVolumeNormalizationEnabledChange: (Boolean) -> Unit,
    onVolumeNormalizationStrengthChange: (Float) -> Unit,
    onTemporalNormalizationEnabledChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Нормализация громкости
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.volume_normalization),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            // Подсказка в зависимости от выбранного типа
            Text(
                text = when (volumeNormalizationSettings.type) {
                    NormalizationType.NONE -> stringResource(R.string.normalization_none_description)
                    NormalizationType.CHANNEL -> stringResource(R.string.normalization_channel_description)
                    NormalizationType.TEMPORAL -> stringResource(R.string.normalization_temporal_description)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
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
        }
    }
}

/**
 * Блок глобальных настроек перестановки каналов
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelSwapSettingsCard(
    channelSwapSettings: ChannelSwapSettings,
    isChannelsSwapped: Boolean,
    onChannelSwapEnabledChange: (Boolean) -> Unit,
    onChannelSwapIntervalChange: (Int) -> Unit,
    onChannelSwapFadeEnabledChange: (Boolean) -> Unit,
    onChannelSwapFadeDurationChange: (Long) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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
                    values = listOf(250L, 500L, 1000L, 1500L, 2000L, 3000L, 5000L),
                    valueLabel = formatFadeDurationLabel(channelSwapSettings.fadeDurationMs),
                    onValueChange = onChannelSwapFadeDurationChange,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }
}

/**
 * Блок настроек энергопотребления (интервал обновления, частота дискретизации)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerSettingsCard(
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
        
        HorizontalDivider()
        
        // Качество аудио - строка чипов
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.audio_quality),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Text(
                text = stringResource(R.string.audio_quality_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilterChip(
                    selected = sampleRate == SampleRate.ULTRA_LOW,
                    onClick = { onSampleRateChange(SampleRate.ULTRA_LOW) },
                    label = { 
                        Text(
                            "8kHz", 
                            maxLines = 1,
                            style = MaterialTheme.typography.labelSmall
                        ) 
                    },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = sampleRate == SampleRate.VERY_LOW,
                    onClick = { onSampleRateChange(SampleRate.VERY_LOW) },
                    label = { 
                        Text(
                            "16kHz", 
                            maxLines = 1,
                            style = MaterialTheme.typography.labelSmall
                        ) 
                    },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = sampleRate == SampleRate.LOW,
                    onClick = { onSampleRateChange(SampleRate.LOW) },
                    label = { 
                        Text(
                            "22kHz", 
                            maxLines = 1,
                            style = MaterialTheme.typography.labelSmall
                        ) 
                    },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = sampleRate == SampleRate.MEDIUM,
                    onClick = { onSampleRateChange(SampleRate.MEDIUM) },
                    label = { 
                        Text(
                            "44kHz", 
                            maxLines = 1,
                            style = MaterialTheme.typography.labelSmall
                        ) 
                    },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = sampleRate == SampleRate.HIGH,
                    onClick = { onSampleRateChange(SampleRate.HIGH) },
                    label = { 
                        Text(
                            "48kHz", 
                            maxLines = 1,
                            style = MaterialTheme.typography.labelSmall
                        ) 
                    },
                    modifier = Modifier.weight(1f)
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

/**
 * Блок настроек режима расслабления
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelaxationModeCard(
    relaxationModeSettings: RelaxationModeSettings,
    onRelaxationModeEnabledChange: (Boolean) -> Unit,
    onRelaxationModeChange: (RelaxationMode) -> Unit,
    onCarrierReductionChange: (Int) -> Unit,
    onBeatReductionChange: (Int) -> Unit,
    onRelaxationGapChange: (Int) -> Unit,
    onTransitionPeriodChange: (Int) -> Unit,
    onRelaxationDurationChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Выбор режима расслабления: 3 чипа в строке
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.relaxation_mode),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            // Описание текущего режима под заголовком
            Text(
                text = if (!relaxationModeSettings.enabled) {
                    stringResource(R.string.relaxation_mode_disabled_desc)
                } else if (relaxationModeSettings.mode == RelaxationMode.SIMPLE) {
                    stringResource(R.string.relaxation_mode_simple_desc)
                } else {
                    stringResource(R.string.relaxation_mode_advanced_desc)
                },
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
                    selected = !relaxationModeSettings.enabled,
                    onClick = { onRelaxationModeEnabledChange(false) },
                    label = { 
                        Text(
                            text = stringResource(R.string.relaxation_mode_disabled),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        ) 
                    },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = relaxationModeSettings.enabled && relaxationModeSettings.mode == RelaxationMode.SIMPLE,
                    onClick = { 
                        onRelaxationModeEnabledChange(true)
                        onRelaxationModeChange(RelaxationMode.SIMPLE)
                    },
                    label = { 
                        Text(
                            text = stringResource(R.string.relaxation_mode_simple),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        ) 
                    },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = relaxationModeSettings.enabled && relaxationModeSettings.mode == RelaxationMode.ADVANCED,
                    onClick = { 
                        onRelaxationModeEnabledChange(true)
                        onRelaxationModeChange(RelaxationMode.ADVANCED)
                    },
                    label = { 
                        Text(
                            text = stringResource(R.string.relaxation_mode_advanced),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        ) 
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        
        // Настройки режима (показываем только когда режим включен)
        if (relaxationModeSettings.enabled) {
            HorizontalDivider()
            
            // Настройки расширенного режима
            if (relaxationModeSettings.mode == RelaxationMode.ADVANCED) {
                
                // Интервал между периодами расслабления
                DiscreteSlider(
                    label = stringResource(R.string.gap_between_relaxation),
                    value = relaxationModeSettings.gapBetweenRelaxationMinutes,
                    values = listOf(10, 15, 20, 30, 45, 60, 90, 120),
                    valueLabel = stringResource(R.string.minutes_format, relaxationModeSettings.gapBetweenRelaxationMinutes),
                    onValueChange = onRelaxationGapChange,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                
                // Длительность расслабления
                DiscreteSlider(
                    label = stringResource(R.string.relaxation_duration),
                    value = relaxationModeSettings.relaxationDurationMinutes,
                    values = listOf(10, 15, 20, 30, 45, 60),
                    valueLabel = stringResource(R.string.minutes_format, relaxationModeSettings.relaxationDurationMinutes),
                    onValueChange = onRelaxationDurationChange,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                
                // Период перехода
                DiscreteSlider(
                    label = stringResource(R.string.transition_period),
                    value = relaxationModeSettings.transitionPeriodMinutes,
                    values = listOf(1, 2, 3, 5, 7, 10),
                    valueLabel = stringResource(R.string.minutes_format, relaxationModeSettings.transitionPeriodMinutes),
                    onValueChange = onTransitionPeriodChange,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            }
            
            // Слайдер снижения несущей частоты
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(R.string.carrier_reduction),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(R.string.reduction_percent_format, relaxationModeSettings.carrierReductionPercent),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = relaxationModeSettings.carrierReductionPercent.toFloat(),
                    onValueChange = { onCarrierReductionChange(it.toInt()) },
                    modifier = Modifier.fillMaxWidth(),
                    valueRange = 0f..50f
                )
            }
            
            // Слайдер снижения частоты биений
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(R.string.beat_reduction),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(R.string.reduction_percent_format, relaxationModeSettings.beatReductionPercent),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = relaxationModeSettings.beatReductionPercent.toFloat(),
                    onValueChange = { onBeatReductionChange(it.toInt()) },
                    modifier = Modifier.fillMaxWidth(),
                    valueRange = 0f..100f
                )
            }
        }
    }
}