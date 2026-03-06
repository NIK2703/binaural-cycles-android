package com.binauralcycles.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.binaural.core.audio.engine.SampleRate
import com.binaural.core.audio.model.ChannelSwapSettings
import com.binaural.core.audio.model.InterpolationType
import com.binaural.core.audio.model.VolumeNormalizationSettings

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
            headlineContent = { Text("Интерполяция по точкам") },
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
        
        // Авто-перестановка каналов
        ListItem(
            headlineContent = { Text("Авто-перестановка каналов") },
            supportingContent = { 
                Text(if (channelSwapSettings.enabled) "Каналы меняются местами для равномерной нагрузки" else "Выключено")
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
                label = "Интервал перестановки",
                value = channelSwapSettings.intervalSeconds,
                values = listOf(30, 60, 120, 300, 600, 900, 1800, 3600),
                valueLabel = formatInterval(channelSwapSettings.intervalSeconds),
                onValueChange = onChannelSwapIntervalChange,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            
            // Затухание при смене каналов
            ListItem(
                headlineContent = { Text("Плавное затухание") },
                supportingContent = { 
                    Text(if (channelSwapSettings.fadeEnabled) "Плавный переход при смене каналов" else "Мгновенное переключение")
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
                    label = "Время затухания",
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
            headlineContent = { Text("Нормализация громкости") },
            supportingContent = { 
                Text("Выравнивание громкости между каналами по соотношению частот их тонов")
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
            label = "Интервал обновления",
            value = frequencyUpdateIntervalMs,
            values = listOf(1000, 2000, 5000, 10000, 15000, 30000, 60000),
            valueLabel = formatUpdateInterval(frequencyUpdateIntervalMs),
            onValueChange = onFrequencyUpdateIntervalChange,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        
        // Качество аудио - раскрывающийся список
        var sampleRateExpanded by remember { mutableStateOf(false) }
        
        ListItem(
            headlineContent = { Text("Качество звука") },
            supportingContent = {
                Column {
                    Text(
                        when (sampleRate) {
                            SampleRate.ULTRA_LOW -> "Ультра-низкое (8 kHz) — максимальная экономия"
                            SampleRate.VERY_LOW -> "Очень низкое (16 kHz)"
                            SampleRate.LOW -> "Низкое (22 kHz) — экономия батареи"
                            SampleRate.MEDIUM -> "Стандарт (44 kHz)"
                            SampleRate.HIGH -> "Высокое (48 kHz)"
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
                                text = { Text("8 kHz — Ультра-низкое") },
                                onClick = {
                                    onSampleRateChange(SampleRate.ULTRA_LOW)
                                    sampleRateExpanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                            DropdownMenuItem(
                                text = { Text("16 kHz — Очень низкое") },
                                onClick = {
                                    onSampleRateChange(SampleRate.VERY_LOW)
                                    sampleRateExpanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                            DropdownMenuItem(
                                text = { Text("22 kHz — Низкое") },
                                onClick = {
                                    onSampleRateChange(SampleRate.LOW)
                                    sampleRateExpanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                            DropdownMenuItem(
                                text = { Text("44 kHz — Стандарт") },
                                onClick = {
                                    onSampleRateChange(SampleRate.MEDIUM)
                                    sampleRateExpanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                            DropdownMenuItem(
                                text = { Text("48 kHz — Высокое") },
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