package com.binauralcycles.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.binaural.core.audio.model.FrequencyPoint
import com.binaural.core.audio.model.FrequencyRange
import com.binaural.core.ui.theme.BeatFrequencyColor
import com.binaural.core.ui.theme.CarrierFrequencyColor
import com.binauralcycles.R
import java.util.Locale

private const val MIN_AUDIBLE_FREQUENCY = 20.0

/**
 * Парсит строку в Double, принимая как точку, так и запятую как разделитель
 */
private fun parseFrequency(value: String): Double? {
    return value.replace(',', '.').toDoubleOrNull()
}

@Composable
fun PointEditor(
    point: FrequencyPoint,
    carrierRange: FrequencyRange,
    beatRange: FrequencyRange = FrequencyRange.DEFAULT_BEAT,
    onCarrierFrequencyChange: (Double) -> Unit,
    onBeatFrequencyChange: (Double) -> Unit,
    onRemove: () -> Unit,
    onDeselect: () -> Unit
) {
    // Функция форматирования: показывает до 2-х ненулевых знаков после запятой
    // Всегда использует точку как разделитель (Locale.US)
    fun formatFrequency(value: Double): String {
        return if (value == kotlin.math.floor(value)) {
            value.toInt().toString()
        } else {
            // Форматируем с 2 знаками после запятой и убираем trailing нули
            "%.2f".format(Locale.US, value).trimEnd('0').trimEnd('.')
        }
    }
    
    // Отображаем частоты с ненулевыми десятичными знаками
    var tempCarrierFrequency by remember(point.carrierFrequency) { mutableStateOf(formatFrequency(point.carrierFrequency)) }
    var tempBeatFrequency by remember(point.beatFrequency) { mutableStateOf(formatFrequency(point.beatFrequency)) }
    
    val carrierValue = parseFrequency(tempCarrierFrequency)
    val beatValue = parseFrequency(tempBeatFrequency)
    
    val isCarrierValid = carrierValue != null && carrierValue >= MIN_AUDIBLE_FREQUENCY && carrierValue <= 2000.0
    
    // Максимальная частота биений для слайдера ограничена двумя условиями:
    // 1. Нижняя боковая частота >= 20 Гц: carrier - beat/2 >= 20 → beat <= 2*(carrier - 20)
    // 2. Верхняя боковая частота <= 2000 Гц: carrier + beat/2 <= 2000 → beat <= 2*(2000 - carrier)
    // Максимальная частота биений вычисляется от текущего значения в текстовом поле (для валидации)
    // или от значения точки (для слайдера)
    val maxBeatFrequencyForValidation = if (carrierValue != null && isCarrierValid) {
        minOf(
            (carrierValue - MIN_AUDIBLE_FREQUENCY) * 2,  // нижняя боковая >= 20 Гц
            (2000.0 - carrierValue) * 2  // верхняя боковая <= 2000 Гц
        ).coerceAtLeast(1.0)
    } else {
        minOf(
            (point.carrierFrequency - MIN_AUDIBLE_FREQUENCY) * 2,
            (2000.0 - point.carrierFrequency) * 2
        ).coerceAtLeast(1.0)
    }
    val maxBeatFrequencyForSlider = minOf(
        (point.carrierFrequency - MIN_AUDIBLE_FREQUENCY) * 2,
        (2000.0 - point.carrierFrequency) * 2
    ).coerceAtLeast(1.0)
    
    // Валидация частоты биений - проверяем относительно текущего значения несущей в поле ввода
    val isBeatValid = beatValue != null && beatValue >= beatRange.min && beatValue <= maxBeatFrequencyForValidation
    
    var sliderCarrier by remember(point.carrierFrequency) { mutableStateOf(point.carrierFrequency.toFloat()) }
    var sliderBeat by remember(point.beatFrequency) { mutableStateOf(point.beatFrequency.toFloat()) }
    
    // Управление фокусом и клавиатурой
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val carrierFocusRequester = remember { FocusRequester() }
    val beatFocusRequester = remember { FocusRequester() }
    
    // Отслеживание предыдущего состояния фокуса для определения потери фокуса
    var carrierWasFocused by remember { mutableStateOf(false) }
    var beatWasFocused by remember { mutableStateOf(false) }
    
    // Локализованные строки
    val pointLabel = stringResource(R.string.point)
    val deleteLabel = stringResource(R.string.delete)
    val closeLabel = stringResource(R.string.close)
    val carrierLabel = stringResource(R.string.carrier_tone_frequency)
    val beatsLabel = stringResource(R.string.beat_frequency_full)
    val hzLabel = stringResource(R.string.hz)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Заголовок с временем и кнопками
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$pointLabel: %02d:%02d".format(point.time.hour, point.time.minute),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                
                Row {
                    IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = deleteLabel,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onDeselect, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = closeLabel,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Несущая частота - подпись
            Text(
                "$carrierLabel:",
                style = MaterialTheme.typography.bodySmall,
                color = CarrierFrequencyColor
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            // Несущая частота - поле ввода слева, слайдер справа
            Row(
                modifier = Modifier.fillMaxWidth().height(32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = tempCarrierFrequency,
                    onValueChange = { newValue ->
                        tempCarrierFrequency = newValue
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            // Сохраняем значение при нажатии Done
                            val value = parseFrequency(tempCarrierFrequency)
                            if (value != null && value >= MIN_AUDIBLE_FREQUENCY && value <= 2000.0) {
                                sliderCarrier = value.toFloat()
                                onCarrierFrequencyChange(value)
                            } else {
                                // Восстанавливаем предыдущее значение при ошибке
                                tempCarrierFrequency = formatFrequency(point.carrierFrequency)
                            }
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        }
                    ),
                    singleLine = true,
                    modifier = Modifier
                        .width(48.dp)
                        .focusRequester(carrierFocusRequester)
                        .onFocusEvent { focusState ->
                            if (focusState.isFocused) {
                                carrierWasFocused = true
                            } else if (carrierWasFocused) {
                                // Фокус был потерян после того как был получен - сохраняем
                                carrierWasFocused = false
                                val value = parseFrequency(tempCarrierFrequency)
                                if (value != null && value >= MIN_AUDIBLE_FREQUENCY && value <= 2000.0) {
                                    sliderCarrier = value.toFloat()
                                    onCarrierFrequencyChange(value)
                                } else {
                                    // Восстанавливаем предыдущее значение при ошибке
                                    tempCarrierFrequency = formatFrequency(point.carrierFrequency)
                                }
                            }
                        }
                        .clip(MaterialTheme.shapes.small)
                        .background(
                            if (!isCarrierValid && tempCarrierFrequency.isNotEmpty())
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                            else
                                MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    textStyle = MaterialTheme.typography.labelSmall.copy(
                        textAlign = TextAlign.End,
                        color = if (!isCarrierValid && tempCarrierFrequency.isNotEmpty())
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                )
                
                Text(
                    text = hzLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )
                
                Slider(
                    value = sliderCarrier,
                    onValueChange = {
                        val rounded = kotlin.math.round(it)
                        sliderCarrier = rounded
                        tempCarrierFrequency = formatFrequency(rounded.toDouble())
                    },
                    onValueChangeFinished = { onCarrierFrequencyChange(kotlin.math.round(sliderCarrier).toDouble()) },
                    valueRange = carrierRange.min.toFloat()..carrierRange.max.toFloat(),
                    modifier = Modifier.weight(1f).padding(start = 4.dp).height(24.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = CarrierFrequencyColor,
                        activeTrackColor = CarrierFrequencyColor
                    )
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Частота биений - подпись
            Text(
                "$beatsLabel:",
                style = MaterialTheme.typography.bodySmall,
                color = BeatFrequencyColor
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            // Частота биений - поле ввода слева, слайдер справа
            Row(
                modifier = Modifier.fillMaxWidth().height(32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = tempBeatFrequency,
                    onValueChange = { newValue ->
                        tempBeatFrequency = newValue
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            // Сохраняем значение при нажатии Done
                            val value = parseFrequency(tempBeatFrequency)
                            if (value != null && value >= beatRange.min && value <= maxBeatFrequencyForValidation) {
                                onBeatFrequencyChange(value)
                            } else {
                                // Восстанавливаем предыдущее значение при ошибке
                                tempBeatFrequency = formatFrequency(point.beatFrequency)
                            }
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        }
                    ),
                    singleLine = true,
                    modifier = Modifier
                        .width(48.dp)
                        .focusRequester(beatFocusRequester)
                        .onFocusEvent { focusState ->
                            if (focusState.isFocused) {
                                beatWasFocused = true
                            } else if (beatWasFocused) {
                                // Фокус был потерян после того как был получен - сохраняем
                                beatWasFocused = false
                                val value = parseFrequency(tempBeatFrequency)
                                if (value != null && value >= beatRange.min && value <= maxBeatFrequencyForValidation) {
                                    sliderBeat = value.toFloat()
                                    onBeatFrequencyChange(value)
                                } else {
                                    // Восстанавливаем предыдущее значение при ошибке
                                    tempBeatFrequency = formatFrequency(point.beatFrequency)
                                }
                            }
                        }
                        .clip(MaterialTheme.shapes.small)
                        .background(
                            if (!isBeatValid && tempBeatFrequency.isNotEmpty())
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                            else
                                MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    textStyle = MaterialTheme.typography.labelSmall.copy(
                        textAlign = TextAlign.End,
                        color = if (!isBeatValid && tempBeatFrequency.isNotEmpty())
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                )
                
                Text(
                    text = hzLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )
                
                val minBeatForSlider = beatRange.min.toFloat().coerceAtLeast(0.0f)
                val maxBeatForSlider = maxBeatFrequencyForSlider.toFloat().coerceAtLeast(minBeatForSlider)
                Slider(
                    value = sliderBeat.coerceIn(minBeatForSlider, maxBeatForSlider),
                    onValueChange = {
                        val rounded = kotlin.math.round(it).coerceIn(minBeatForSlider, maxBeatForSlider)
                        sliderBeat = rounded
                        tempBeatFrequency = formatFrequency(rounded.toDouble())
                    },
                    onValueChangeFinished = { onBeatFrequencyChange(kotlin.math.round(sliderBeat).toDouble()) },
                    valueRange = minBeatForSlider..maxBeatForSlider,
                    modifier = Modifier.weight(1f).padding(start = 4.dp).height(24.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = BeatFrequencyColor,
                        activeTrackColor = BeatFrequencyColor
                    )
                )
            }
        }
    }
}