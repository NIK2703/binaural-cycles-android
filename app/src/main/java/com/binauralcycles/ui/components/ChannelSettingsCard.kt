package com.binauralcycles.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.binaural.core.audio.engine.SampleRate
import com.binaural.core.audio.model.ChannelSwapMode
import com.binaural.core.audio.model.ChannelSwapSettings
import com.binaural.core.audio.model.InterpolationType
import com.binaural.core.audio.model.NormalizationType
import com.binaural.core.audio.model.RelaxationMode
import com.binaural.core.audio.model.RelaxationModeSettings
import com.binaural.core.audio.model.VolumeNormalizationSettings
import com.binauralcycles.R
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * Блок настроек интерполяции для пресета
 * Нормализация громкости вынесена в глобальные настройки приложения
 */
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
            // Стандартный раскрывающийся список интерполяции
            OverlayDropdownPreference(
                items = listOf(
                    stringResource(R.string.step),
                    stringResource(R.string.linear),
                    stringResource(R.string.monotone),
                    stringResource(R.string.cardinal)
                ),
                selectedIndex = when (interpolationType) {
                    InterpolationType.STEP -> 0
                    InterpolationType.LINEAR -> 1
                    InterpolationType.MONOTONE -> 2
                    InterpolationType.CARDINAL -> 3
                },
                title = stringResource(R.string.point_interpolation),
                summary = stringResource(R.string.interpolation_description),
                onSelectedIndexChange = { index ->
                    onInterpolationTypeChange(
                        when (index) {
                            0 -> InterpolationType.STEP
                            1 -> InterpolationType.LINEAR
                            2 -> InterpolationType.MONOTONE
                            else -> InterpolationType.CARDINAL
                        }
                    )
                }
            )
        }
    }
}

/**
 * Блок глобальных настроек нормализации громкости
 */
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
            // Стандартный раскрывающийся список типа нормализации
            OverlayDropdownPreference(
                items = listOf(
                    stringResource(R.string.normalization_none),
                    stringResource(R.string.normalization_channel),
                    stringResource(R.string.normalization_temporal)
                ),
                selectedIndex = when (volumeNormalizationSettings.type) {
                    NormalizationType.NONE -> 0
                    NormalizationType.CHANNEL -> 1
                    NormalizationType.TEMPORAL -> 2
                },
                title = stringResource(R.string.volume_normalization),
                summary = when (volumeNormalizationSettings.type) {
                    NormalizationType.NONE -> stringResource(R.string.normalization_none_description)
                    NormalizationType.CHANNEL -> stringResource(R.string.normalization_channel_description)
                    NormalizationType.TEMPORAL -> stringResource(R.string.normalization_temporal_description)
                },
                onSelectedIndexChange = { index ->
                    when (index) {
                        0 -> onVolumeNormalizationEnabledChange(false)
                        1 -> onVolumeNormalizationEnabledChange(true)
                        else -> onTemporalNormalizationEnabledChange(true)
                    }
                }
            )
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
                        color = colorScheme.primary
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
@Composable
fun ChannelSwapSettingsCard(
    channelSwapSettings: ChannelSwapSettings,
    isChannelsSwapped: Boolean,
    onChannelSwapSelect: (ChannelSwapMode?) -> Unit,
    onChannelSwapIntervalChange: (Int) -> Unit,
    onChannelSwapFadeDurationChange: (Long) -> Unit,
    onChannelSwapPauseDurationChange: (Long) -> Unit
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
        // Авто-перестановка каналов
        Column(modifier = Modifier.fillMaxWidth()) {
            // Стандартный раскрывающийся список режима перестановки
            OverlayDropdownPreference(
                items = listOf(
                    stringResource(R.string.channel_swap_disabled),
                    stringResource(R.string.swap_mode_timer),
                    stringResource(R.string.swap_mode_trend)
                ),
                selectedIndex = when (selection) {
                    null -> 0
                    ChannelSwapMode.TIMER -> 1
                    ChannelSwapMode.TREND -> 2
                },
                title = stringResource(R.string.auto_channel_swap),
                summary = when (selection) {
                    null -> stringResource(R.string.swap_mode_off_description)
                    ChannelSwapMode.TIMER -> stringResource(R.string.swap_mode_timer_description)
                    ChannelSwapMode.TREND -> stringResource(R.string.swap_mode_trend_description)
                },
                onSelectedIndexChange = { index ->
                    when (index) {
                        0 -> onChannelSwapSelect(null)
                        1 -> onChannelSwapSelect(ChannelSwapMode.TIMER)
                        else -> onChannelSwapSelect(ChannelSwapMode.TREND)
                    }
                }
            )
        }

        if (channelSwapSettings.enabled) {
            // Слайдер интервала перестановки: только в TIMER-режиме
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

            // Слайдер длительности затухания (без рисок на дорожке)
            DiscreteSliderLong(
                label = stringResource(R.string.fade_duration),
                value = channelSwapSettings.fadeDurationMs,
                values = listOf(1000L, 2000L, 3000L, 4000L, 5000L, 6000L, 7000L, 8000L, 9000L, 10000L, 11000L, 12000L, 13000L, 14000L, 15000L),
                formatValue = { ms -> formatFadeDurationLabel(ms) },
                onValueChange = onChannelSwapFadeDurationChange,
                modifier = Modifier.padding(horizontal = 16.dp),
                showKeyPoints = false
            )

            // Слайдер длительности паузы при переключении
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
 * Блок настроек энергопотребления (интервал генерации буфера, частота дискретизации)
 */
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
                color = colorScheme.onSurfaceSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            DiscreteSlider(
                label = "",
                value = bufferGenerationMinutes,
                values = listOf(1, 2, 5, 10, 15, 20, 30, 45, 60),  // От 1 минуты до 1 часа
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
                color = colorScheme.onSurfaceSecondary,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            // Стандартный раскрывающийся список качества аудио
            OverlayDropdownPreference(
                items = listOf("8kHz", "16kHz", "22kHz", "44kHz", "48kHz"),
                selectedIndex = when (sampleRate) {
                    SampleRate.ULTRA_LOW -> 0
                    SampleRate.VERY_LOW -> 1
                    SampleRate.LOW -> 2
                    SampleRate.MEDIUM -> 3
                    SampleRate.HIGH -> 4
                },
                title = stringResource(R.string.audio_quality),
                summary = stringResource(R.string.audio_quality_description),
                onSelectedIndexChange = { index ->
                    onSampleRateChange(
                        when (index) {
                            0 -> SampleRate.ULTRA_LOW
                            1 -> SampleRate.VERY_LOW
                            2 -> SampleRate.LOW
                            3 -> SampleRate.MEDIUM
                            else -> SampleRate.HIGH
                        }
                    )
                }
            )
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

    val keyPoints = remember(values) { values.indices.map { it.toFloat() } }

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
                color = colorScheme.primary
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
            showKeyPoints = true,
            keyPoints = keyPoints,
            magnetThreshold = if (values.size > 1) 0.4f / (values.size - 1) else 0f
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
    modifier: Modifier = Modifier,
    showKeyPoints: Boolean = true
) {
    // Локальное состояние для мгновенного отклика UI
    var localIndex by remember(value) { mutableIntStateOf(values.indexOf(value).coerceAtLeast(0)) }

    val keyPoints = remember(values) { values.indices.map { it.toFloat() } }

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
                color = colorScheme.primary
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
            showKeyPoints = showKeyPoints,
            keyPoints = keyPoints,
            magnetThreshold = if (values.size > 1) 0.4f / (values.size - 1) else 0f
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

    val keyPoints = remember(values) { values.indices.map { it.toFloat() } }

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
                color = colorScheme.primary
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
            showKeyPoints = true,
            keyPoints = keyPoints,
            magnetThreshold = if (values.size > 1) 0.4f / (values.size - 1) else 0f
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
        // Режим расслабления
        Column(modifier = Modifier.fillMaxWidth()) {
            // Стандартный раскрывающийся список режима расслабления
            OverlayDropdownPreference(
                items = listOf(
                    stringResource(R.string.relaxation_mode_disabled),
                    stringResource(R.string.relaxation_mode_step),
                    stringResource(R.string.relaxation_mode_smooth)
                ),
                selectedIndex = when {
                    !relaxationModeSettings.enabled -> 0
                    relaxationModeSettings.mode == RelaxationMode.STEP -> 1
                    else -> 2
                },
                title = stringResource(R.string.relaxation_mode),
                summary = when {
                    !relaxationModeSettings.enabled -> stringResource(R.string.relaxation_mode_disabled_desc)
                    relaxationModeSettings.mode == RelaxationMode.STEP -> stringResource(R.string.relaxation_mode_step_desc)
                    else -> stringResource(R.string.relaxation_mode_smooth_desc)
                },
                onSelectedIndexChange = { index ->
                    when (index) {
                        0 -> onRelaxationModeEnabledChange(false)
                        1 -> {
                            onRelaxationModeEnabledChange(true)
                            onRelaxationModeChange(RelaxationMode.STEP)
                        }
                        else -> {
                            onRelaxationModeEnabledChange(true)
                            onRelaxationModeChange(RelaxationMode.SMOOTH)
                        }
                    }
                }
            )
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
                        color = colorScheme.primary
                    )
                }
                Slider(
                    value = localCarrierReduction.toFloat(),
                    onValueChange = { localCarrierReduction = it.toInt() },
                    onValueChangeFinished = {
                        onCarrierReductionChange(localCarrierReduction)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    valueRange = 0f..50f
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
                        color = colorScheme.primary
                    )
                }
                Slider(
                    value = localBeatReduction.toFloat(),
                    onValueChange = { localBeatReduction = it.toInt() },
                    onValueChangeFinished = {
                        onBeatReductionChange(localBeatReduction)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    valueRange = 0f..100f
                )
            }
        }
    }
}
