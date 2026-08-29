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
import com.binaural.core.audio.model.ChannelSwapMode
import com.binaural.core.audio.model.ChannelSwapSettings
import com.binaural.core.audio.model.ChannelSwapTrendPoints
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
    onChannelSwapSelect: (ChannelSwapMode?) -> Unit,
    onChannelSwapIntervalChange: (Int) -> Unit,
    onChannelSwapFadeDurationChange: (Long) -> Unit,
    onChannelSwapPauseDurationChange: (Long) -> Unit,
    onChannelSwapTrendPointsChange: (ChannelSwapTrendPoints) -> Unit,
) {
    // null = выключено; иначе включено с выбранным режимом
    val selection = when {
        !channelSwapSettings.enabled -> null
        channelSwapSettings.mode == ChannelSwapMode.TREND -> ChannelSwapMode.TREND
        else -> ChannelSwapMode.TIMER
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Смена каналов: один ряд из трёх чипов (как у нормализации)
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.auto_channel_swap),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            // Подсказка в зависимости от выбора
            Text(
                text = stringResource(
                    when (selection) {
                        null -> R.string.swap_mode_off_description
                        ChannelSwapMode.TIMER -> R.string.swap_mode_timer_description
                        ChannelSwapMode.TREND -> R.string.swap_mode_trend_description
                    }
                ),
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
                    selected = selection == null,
                    onClick = { onChannelSwapSelect(null) },
                    label = {
                        Text(
                            text = stringResource(R.string.channel_swap_disabled),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = selection == ChannelSwapMode.TIMER,
                    onClick = { onChannelSwapSelect(ChannelSwapMode.TIMER) },
                    label = {
                        Text(
                            text = stringResource(R.string.swap_mode_timer),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = selection == ChannelSwapMode.TREND,
                    onClick = { onChannelSwapSelect(ChannelSwapMode.TREND) },
                    label = {
                        Text(
                            text = stringResource(R.string.swap_mode_trend),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (channelSwapSettings.enabled) {
            // Слайдер интервала перестановки: только в TIMER-режиме
            // (в TREND интервал не участвует — частота смен определяется
            // мёртвой зоной производной кривой)
            if (channelSwapSettings.mode == ChannelSwapMode.TIMER) {
                DiscreteSlider(
                    label = stringResource(R.string.swap_interval),
                    value = channelSwapSettings.intervalSeconds,
                    values = listOf(30, 60, 120, 300, 600, 900, 1800, 3600),
                    formatValue = { seconds -> formatInterval(seconds) },
                    onValueChange = onChannelSwapIntervalChange,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            
            // Точки графика для перестановки: только в TREND-режиме
            // (в TIMER интервал не участвует — смены привязаны к экстремумам кривой)
            if (channelSwapSettings.mode == ChannelSwapMode.TREND) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.swap_points),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 8.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                    ) {
                        FilterChip(
                            selected = channelSwapSettings.trendPoints == ChannelSwapTrendPoints.BOTH,
                            onClick = { onChannelSwapTrendPointsChange(ChannelSwapTrendPoints.BOTH) },
                            label = {
                                Text(
                                    text = stringResource(R.string.swap_points_both),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = channelSwapSettings.trendPoints == ChannelSwapTrendPoints.PEAKS,
                            onClick = { onChannelSwapTrendPointsChange(ChannelSwapTrendPoints.PEAKS) },
                            label = {
                                Text(
                                    text = stringResource(R.string.swap_points_peaks),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = channelSwapSettings.trendPoints == ChannelSwapTrendPoints.TROUGHS,
                            onClick = { onChannelSwapTrendPointsChange(ChannelSwapTrendPoints.TROUGHS) },
                            label = {
                                Text(
                                    text = stringResource(R.string.swap_points_troughs),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            
            // Слайдер длительности затухания (всегда показываем, т.к. fadeEnabled всегда true)
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                var localSeconds by remember(channelSwapSettings.fadeDurationMs) {
                    mutableStateOf(channelSwapSettings.fadeDurationMs / 1000f)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.fade_duration),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = formatFadeDurationLabel(localSeconds.toLong() * 1000L),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = localSeconds,
                    onValueChange = { localSeconds = (it + 0.5f).toInt().toFloat() },
                    onValueChangeFinished = {
                        onChannelSwapFadeDurationChange(localSeconds.toInt().toLong() * 1000L)
                    },
                    valueRange = 1f..15f,
                    steps = 0,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            
            // Слайдер длительности паузы при переключении (расширенный диапазон до 1 минуты)
            DiscreteSliderLong(
                label = stringResource(R.string.pause_on_switch),
                value = channelSwapSettings.pauseDurationMs,
                values = listOf(0L, 1000L, 2000L, 3000L, 5000L, 10000L, 20000L, 30000L, 60000L),
                formatValue = { ms -> formatPauseDurationLabel(ms) },
                onValueChange = onChannelSwapPauseDurationChange,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

/**
 * Шкала интервала генерации буфера: от 1 минуты до 1 часа.
 * Всегда полная, независимо от частоты дискретизации: если выбранный
 * интервал больше ёмкости direct-буфера, движок урежет длину пакета
 * по фактической ёмкости, а воспроизведение продолжится.
 */
private val BUFFER_MINUTE_STOPS = listOf(1, 2, 5, 10, 15, 20, 30, 45, 60)

/**
 * Блок настроек энергопотребления (интервал генерации буфера, частота дискретизации)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerSettingsCard(
    sampleRate: SampleRate,
    bufferGenerationMinutes: Int,
    onSampleRateChange: (SampleRate) -> Unit,
    onBufferGenerationMinutesChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Интервал генерации буфера в минутах - слайдер (от 1 минуты до 1 часа)
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = stringResource(R.string.buffer_generation_minutes),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(R.string.buffer_generation_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            DiscreteSlider(
                label = "",
                value = bufferGenerationMinutes,
                values = BUFFER_MINUTE_STOPS,  // От 1 минуты до 1 часа
                formatValue = { mins -> formatBufferInterval(mins) },
                onValueChange = onBufferGenerationMinutesChange,
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
 * Использует локальное состояние для мгновенного отклика UI и сохраняет при отпускании
 */
@Composable
fun DiscreteSlider(
    label: String,
    value: Int,
    values: List<Int>,
    formatValue: @Composable (Int) -> String,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // Локальное состояние для мгновенного отклика UI
    var localIndex by remember(value) { mutableIntStateOf(values.indexOf(value).coerceAtLeast(0)) }

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
                formatValue(values[localIndex]),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = localIndex.toFloat(),
            onValueChange = { index ->
                localIndex = index.toInt().coerceIn(0, values.lastIndex)
            },
            onValueChangeFinished = {
                onValueChange(values[localIndex])
            },
            modifier = Modifier.fillMaxWidth(),
            valueRange = 0f..(values.size - 1).toFloat(),
            steps = values.size - 2
        )
    }
}

/**
 * Дискретный слайдер для Long значений
 * Использует локальное состояние для мгновенного отклика UI и сохраняет при отпускании
 */
@Composable
fun DiscreteSliderLong(
    label: String,
    value: Long,
    values: List<Long>,
    formatValue: @Composable (Long) -> String,
    onValueChange: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    // Локальное состояние для мгновенного отклика UI
    var localIndex by remember(value) { mutableIntStateOf(values.indexOf(value).coerceAtLeast(0)) }

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
                formatValue(values[localIndex]),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = localIndex.toFloat(),
            onValueChange = { index ->
                localIndex = index.toInt().coerceIn(0, values.lastIndex)
            },
            onValueChangeFinished = {
                onValueChange(values[localIndex])
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
    val seconds = ms / 1000
    val secFull = stringResource(R.string.seconds_full)
    return "$seconds $secFull"
}

/**
 * Форматирование длительности паузы для отображения в UI (до 1 минуты)
 */
@Composable
fun formatPauseDurationLabel(ms: Long): String {
    if (ms == 0L) {
        return stringResource(R.string.no_pause)
    }
    val secFull = stringResource(R.string.seconds_full)
    val minShort = stringResource(R.string.minutes_short)
    
    return when {
        ms < 1000 -> "$ms ${stringResource(R.string.milliseconds_short)}"
        ms < 60000 -> {
            val seconds = ms / 1000
            "$seconds $secFull"
        }
        else -> {
            val minutes = ms / 60000
            "$minutes $minShort"
        }
    }
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
 * Форматирование интервала генерации буфера (в минутах)
 */
@Composable
fun formatBufferInterval(minutes: Int): String {
    val minShort = stringResource(R.string.minutes_short)
    val hourShort = stringResource(R.string.hours_short)
    
    return when {
        minutes < 60 -> "$minutes $minShort"
        else -> {
            val hours = minutes / 60
            val mins = minutes % 60
            if (mins == 0) "$hours $hourShort" else "$hours $hourShort $mins $minShort"
        }
    }
}

/**
 * Дискретный слайдер для размера таблицы волн
 * Использует локальное состояние для мгновенного отклика UI и сохраняет при отпускании
 */
@Composable
fun DiscreteSliderWavetableSize(
    label: String,
    value: Int,
    values: List<Int>,
    formatValue: @Composable (Int) -> String,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // Локальное состояние для мгновенного отклика UI
    var localIndex by remember(value) { mutableIntStateOf(values.indexOf(value).coerceAtLeast(0)) }

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
                formatValue(values[localIndex]),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = localIndex.toFloat(),
            onValueChange = { index ->
                localIndex = index.toInt().coerceIn(0, values.lastIndex)
            },
            onValueChangeFinished = {
                onValueChange(values[localIndex])
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
    return stringResource(R.string.wavetable_size_samples, size)
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
    onRelaxationDurationChange: (Int) -> Unit,
    onSmoothIntervalChange: (Int) -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Выбор режима расслабления: 3 чипа в одной строке
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.relaxation_mode),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            // Описание текущего режима под заголовком
            Text(
                text = when {
                    !relaxationModeSettings.enabled -> stringResource(R.string.relaxation_mode_disabled_desc)
                    relaxationModeSettings.mode == RelaxationMode.STEP -> stringResource(R.string.relaxation_mode_step_desc)
                    else -> stringResource(R.string.relaxation_mode_smooth_desc)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            // Одна строка: Выкл, Расширенный, Плавный
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
                    selected = relaxationModeSettings.enabled && relaxationModeSettings.mode == RelaxationMode.STEP,
                    onClick = { 
                        onRelaxationModeEnabledChange(true)
                        onRelaxationModeChange(RelaxationMode.STEP)
                    },
                    label = { 
                        Text(
                            text = stringResource(R.string.relaxation_mode_step),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        ) 
                    },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = relaxationModeSettings.enabled && relaxationModeSettings.mode == RelaxationMode.SMOOTH,
                    onClick = { 
                        onRelaxationModeEnabledChange(true)
                        onRelaxationModeChange(RelaxationMode.SMOOTH)
                    },
                    label = { 
                        Text(
                            text = stringResource(R.string.relaxation_mode_smooth),
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
            
            // Настройки ступенчатого режима
            if (relaxationModeSettings.mode == RelaxationMode.STEP) {
                
                // Интервал между периодами расслабления
                DiscreteSlider(
                    label = stringResource(R.string.gap_between_relaxation),
                    value = relaxationModeSettings.gapBetweenRelaxationMinutes,
                    values = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120),
                    formatValue = { mins -> stringResource(R.string.minutes_format, mins) },
                    onValueChange = onRelaxationGapChange,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                
                // Длительность расслабления
                DiscreteSlider(
                    label = stringResource(R.string.relaxation_duration),
                    value = relaxationModeSettings.relaxationDurationMinutes,
                    values = listOf(5, 10, 15, 20, 30, 45, 60),
                    formatValue = { mins -> stringResource(R.string.minutes_format, mins) },
                    onValueChange = onRelaxationDurationChange,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                
                // Период перехода
                DiscreteSlider(
                    label = stringResource(R.string.transition_period),
                    value = relaxationModeSettings.transitionPeriodMinutes,
                    values = listOf(1, 2, 3, 5, 7, 10),
                    formatValue = { mins -> stringResource(R.string.minutes_format, mins) },
                    onValueChange = onTransitionPeriodChange,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            }
            
            // Настройки плавного режима
            if (relaxationModeSettings.mode == RelaxationMode.SMOOTH) {
                // Интервал между точками
                DiscreteSlider(
                    label = stringResource(R.string.smooth_interval),
                    value = relaxationModeSettings.smoothIntervalMinutes,
                    values = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120),
                    formatValue = { mins -> stringResource(R.string.minutes_format, mins) },
                    onValueChange = onSmoothIntervalChange,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            }
            
            // Слайдер снижения несущей частоты
            // Локальное состояние для мгновенного отклика UI
            var localCarrierReduction by remember(relaxationModeSettings.carrierReductionPercent) {
                mutableIntStateOf(relaxationModeSettings.carrierReductionPercent)
            }
            
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
                        stringResource(R.string.reduction_percent_format, localCarrierReduction),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = localCarrierReduction.toFloat(),
                    onValueChange = { localCarrierReduction = it.toInt() },
                    onValueChangeFinished = {
                        onCarrierReductionChange(localCarrierReduction)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    valueRange = 0f..RelaxationModeSettings.MAX_CARRIER_REDUCTION_PERCENT.toFloat()
                )
            }
            
            // Слайдер снижения частоты биений
            // Локальное состояние для мгновенного отклика UI
            var localBeatReduction by remember(relaxationModeSettings.beatReductionPercent) {
                mutableIntStateOf(relaxationModeSettings.beatReductionPercent)
            }
            
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
                        stringResource(R.string.reduction_percent_format, localBeatReduction),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = localBeatReduction.toFloat(),
                    onValueChange = { localBeatReduction = it.toInt() },
                    onValueChangeFinished = {
                        onBeatReductionChange(localBeatReduction)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    valueRange = 0f..RelaxationModeSettings.MAX_BEAT_REDUCTION_PERCENT.toFloat()
                )
            }
        }
    }
}