package com.binauralcycles.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.binaural.core.domain.model.FrequencyPoint
import com.binaural.core.domain.model.FrequencyRange
import com.binauralcycles.R
import com.binauralcycles.ui.theme.AudioConstants
import kotlinx.datetime.LocalTime
import java.util.Locale

/**
 * Парсит строку в Float, принимая как точку, так и запятую как разделитель
 */
private fun parseFrequency(value: String): Float? {
    return value.replace(',', '.').toFloatOrNull()
}

/**
 * Ограничивает ввод частоты: максимум 4 знака в целой части и 2 в дробной
 * Разрешает только одну точку или запятую как разделитель
 * Убирает ведущие нули в целой части (кроме случая "0.xxx")
 */
private fun limitFrequencyInput(value: String): String {
    // Находим позицию первого разделителя (точки или запятой)
    val firstDotIndex = value.indexOf('.')
    val firstCommaIndex = value.indexOf(',')
    
    // Определяем позицию первого разделителя
    val separatorIndex = when {
        firstDotIndex == -1 && firstCommaIndex == -1 -> -1
        firstDotIndex == -1 -> firstCommaIndex
        firstCommaIndex == -1 -> firstDotIndex
        else -> minOf(firstDotIndex, firstCommaIndex)
    }
    
    return if (separatorIndex == -1) {
        // Нет разделителя - только целая часть, максимум 4 цифры
        val digits = value.filter { it.isDigit() }.take(4)
        // Убираем ведущие нули, но оставляем один ноль если всё число состоит из нулей
        digits.trimLeadingZeros()
    } else {
        // Есть разделитель - разбиваем на целую и дробную части
        val integerPart = value.substring(0, separatorIndex).filter { it.isDigit() }.take(4)
        val decimalPart = value.substring(separatorIndex + 1).filter { it.isDigit() }.take(2)
        
        // Убираем ведущие нули в целой части, но оставляем один ноль для чисел вида "0.xxx"
        val normalizedInteger = integerPart.trimLeadingZeros()
        
        // Собираем результат с точкой как разделителем
        // Всегда сохраняем точку, даже если дробная часть пуста (пользователь продолжает ввод)
        if (decimalPart.isEmpty()) {
            "$normalizedInteger."
        } else {
            "$normalizedInteger.$decimalPart"
        }
    }
}

/**
 * Убирает ведущие нули из строки цифр, но оставляет один ноль если строка пустая или состоит только из нулей
 */
private fun String.trimLeadingZeros(): String {
    val trimmed = this.trimStart('0')
    return if (trimmed.isEmpty()) "0" else trimmed
}

@Composable
fun PointEditor(
    point: FrequencyPoint,
    carrierRange: FrequencyRange,
    beatRange: FrequencyRange,
    autoExpandGraphRange: Boolean,
    onCarrierFrequencyChange: (Float) -> Unit,
    onBeatFrequencyChange: (Float) -> Unit,
    onTimeChange: (LocalTime) -> Unit,
    onRemove: () -> Unit,
    onDeselect: () -> Unit
) {
    // Функция форматирования: показывает до 2-х ненулевых знаков после запятой
    // Всегда использует точку как разделитель (Locale.US)
    fun formatFrequency(value: Float): String {
        return if (value == kotlin.math.floor(value)) {
            value.toInt().toString()
        } else {
            // Форматируем с 2 знаками после запятой и убираем trailing нули
            "%.2f".format(Locale.US, value).trimEnd('0').trimEnd('.')
        }
    }
    
    // Отображаем частоты с ненулевыми десятичными знаками
    var tempCarrierFrequency by remember(point.carrierFrequency) { 
        mutableStateOf(TextFieldValue(formatFrequency(point.carrierFrequency))) 
    }
    var tempBeatFrequency by remember(point.beatFrequency) { 
        mutableStateOf(TextFieldValue(formatFrequency(point.beatFrequency))) 
    }
    
    val carrierValue = parseFrequency(tempCarrierFrequency.text)
    val beatValue = parseFrequency(tempBeatFrequency.text)
    
    val isCarrierValid = carrierValue != null && carrierValue >= AudioConstants.MIN_AUDIBLE_FREQUENCY && carrierValue <= AudioConstants.MAX_FREQUENCY
    
    // Максимальная частота биений для слайдера ограничена условиями:
    // 1. Нижняя боковая частота >= 20 Гц: carrier - beat/2 >= 20 → beat <= 2*(carrier - 20)
    // 2. Верхняя боковая частота <= 2000 Гц: carrier + beat/2 <= 2000 → beat <= 2*(2000 - carrier)
    // 3. Если autoExpandGraphRange = false, дополнительно ограничиваем границами графика:
    //    - Нижняя боковая >= carrierRange.min: beat <= 2*(carrier - carrierRange.min)
    //    - Верхняя боковая <= carrierRange.max: beat <= 2*(carrierRange.max - carrier)
    
    // Максимальная частота биений вычисляется от текущего значения в текстовом поле (для валидации)
    // или от значения точки (для слайдера)
    val maxBeatFrequencyForValidation = if (carrierValue != null && isCarrierValid) {
        val globalMax = minOf(
            (carrierValue - AudioConstants.MIN_AUDIBLE_FREQUENCY) * 2,  // нижняя боковая >= 20 Гц
            (AudioConstants.MAX_FREQUENCY - carrierValue) * 2  // верхняя боковая <= 2000 Гц
        )
        if (autoExpandGraphRange) {
            globalMax.coerceAtLeast(1.0f)
        } else {
            // Дополнительно ограничиваем границами графика
            val rangeMax = minOf(
                (carrierValue - carrierRange.min) * 2,  // нижняя боковая >= carrierRange.min
                (carrierRange.max - carrierValue) * 2   // верхняя боковая <= carrierRange.max
            )
            minOf(globalMax, rangeMax).coerceAtLeast(1.0f)
        }
    } else {
        val globalMax = minOf(
            (point.carrierFrequency - AudioConstants.MIN_AUDIBLE_FREQUENCY) * 2,
            (AudioConstants.MAX_FREQUENCY - point.carrierFrequency) * 2
        )
        if (autoExpandGraphRange) {
            globalMax.coerceAtLeast(1.0f)
        } else {
            val rangeMax = minOf(
                (point.carrierFrequency - carrierRange.min) * 2,
                (carrierRange.max - point.carrierFrequency) * 2
            )
            minOf(globalMax, rangeMax).coerceAtLeast(1.0f)
        }
    }
    
    val maxBeatFrequencyForSlider = run {
        val globalMax = minOf(
            (point.carrierFrequency - AudioConstants.MIN_AUDIBLE_FREQUENCY) * 2,
            (AudioConstants.MAX_FREQUENCY - point.carrierFrequency) * 2
        )
        if (autoExpandGraphRange) {
            globalMax.coerceAtLeast(1.0f)
        } else {
            val rangeMax = minOf(
                (point.carrierFrequency - carrierRange.min) * 2,
                (carrierRange.max - point.carrierFrequency) * 2
            )
            minOf(globalMax, rangeMax).coerceAtLeast(1.0f)
        }
    }
    
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
    
    // Состояние для редактирования времени
    var tempHours by remember(point.time.hour) { mutableStateOf(point.time.hour.toString().padStart(2, '0')) }
    var tempMinutes by remember(point.time.minute) { mutableStateOf(point.time.minute.toString().padStart(2, '0')) }
    
    // Focus requesters для полей времени
    val hoursFocusRequester = remember { FocusRequester() }
    val minutesFocusRequester = remember { FocusRequester() }
    var hoursWasFocused by remember { mutableStateOf(false) }
    var minutesWasFocused by remember { mutableStateOf(false) }
    
    // Функция валидации и сохранения времени
    fun validateAndSaveTime() {
        val hours = tempHours.toIntOrNull()?.coerceIn(0, 23) ?: point.time.hour
        val minutes = tempMinutes.toIntOrNull()?.coerceIn(0, 59) ?: point.time.minute
        onTimeChange(LocalTime(hours, minutes))
    }
    
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            // Заголовок с временем и кнопками
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Текст "Точка:" и поля ввода времени
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$pointLabel:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    
                    // Поле ввода часов
                    BasicTextField(
                        value = tempHours,
                        onValueChange = { newValue ->
                            val filtered = newValue.filter { it.isDigit() }.take(2)
                            tempHours = filtered
                            // Автопереход на минуты при вводе 2 цифр
                            if (filtered.length == 2) {
                                minutesFocusRequester.requestFocus()
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { minutesFocusRequester.requestFocus() }
                        ),
                        singleLine = true,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .width(36.dp)
                            .focusRequester(hoursFocusRequester)
                            .onFocusEvent { focusState ->
                                if (focusState.isFocused) {
                                    hoursWasFocused = true
                                } else if (hoursWasFocused) {
                                    hoursWasFocused = false
                                    // Валидация при потере фокуса
                                    val hours = tempHours.toIntOrNull()
                                    if (hours == null || hours !in 0..23) {
                                        tempHours = point.time.hour.toString().padStart(2, '0')
                                    } else {
                                        tempHours = hours.toString().padStart(2, '0')
                                    }
                                    validateAndSaveTime()
                                }
                            }
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surface)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline,
                                shape = MaterialTheme.shapes.small
                            )
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        textStyle = MaterialTheme.typography.titleSmall.copy(
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                    )
                    
                    Text(
                        text = ":",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 2.dp)
                    )
                    
                    // Поле ввода минут
                    BasicTextField(
                        value = tempMinutes,
                        onValueChange = { newValue ->
                            tempMinutes = newValue.filter { it.isDigit() }.take(2)
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                val minutes = tempMinutes.toIntOrNull()
                                if (minutes == null || minutes !in 0..59) {
                                    tempMinutes = point.time.minute.toString().padStart(2, '0')
                                } else {
                                    tempMinutes = minutes.toString().padStart(2, '0')
                                }
                                validateAndSaveTime()
                                keyboardController?.hide()
                                focusManager.clearFocus()
                            }
                        ),
                        singleLine = true,
                        modifier = Modifier
                            .width(36.dp)
                            .focusRequester(minutesFocusRequester)
                            .onFocusEvent { focusState ->
                                if (focusState.isFocused) {
                                    minutesWasFocused = true
                                } else if (minutesWasFocused) {
                                    minutesWasFocused = false
                                    // Валидация при потере фокуса
                                    val minutes = tempMinutes.toIntOrNull()
                                    if (minutes == null || minutes !in 0..59) {
                                        tempMinutes = point.time.minute.toString().padStart(2, '0')
                                    } else {
                                        tempMinutes = minutes.toString().padStart(2, '0')
                                    }
                                    validateAndSaveTime()
                                }
                            }
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surface)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline,
                                shape = MaterialTheme.shapes.small
                            )
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        textStyle = MaterialTheme.typography.titleSmall.copy(
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                    )
                }
                
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
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
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
                        val limited = limitFrequencyInput(newValue.text)
                        // Если при удалении появился "0", выделяем его полностью
                        if (limited == "0" && tempCarrierFrequency.text.length > 1) {
                            tempCarrierFrequency = TextFieldValue(limited, selection = TextRange(0, 1))
                        } else if (limited != newValue.text) {
                            // Текст был изменён (ограничен), сохраняем курсор в конце
                            tempCarrierFrequency = TextFieldValue(limited, selection = TextRange(limited.length))
                        } else {
                            // Текст не изменился, сохраняем оригинальный selection
                            tempCarrierFrequency = newValue.copy(text = limited)
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            // Сохраняем значение при нажатии Done
                            val value = parseFrequency(tempCarrierFrequency.text)
                            if (value != null && value >= AudioConstants.MIN_AUDIBLE_FREQUENCY && value <= AudioConstants.MAX_FREQUENCY) {
                                sliderCarrier = value.toFloat()
                                onCarrierFrequencyChange(value)
                            } else {
                                // Восстанавливаем предыдущее значение при ошибке
                                tempCarrierFrequency = TextFieldValue(formatFrequency(point.carrierFrequency))
                            }
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        }
                    ),
                    singleLine = true,
                    modifier = Modifier
                        .width(64.dp)
                        .focusRequester(carrierFocusRequester)
                        .onFocusEvent { focusState ->
                            if (focusState.isFocused) {
                                carrierWasFocused = true
                            } else if (carrierWasFocused) {
                                // Фокус был потерян после того как был получен - сохраняем
                                carrierWasFocused = false
                                val value = parseFrequency(tempCarrierFrequency.text)
                                if (value != null && value >= AudioConstants.MIN_AUDIBLE_FREQUENCY && value <= AudioConstants.MAX_FREQUENCY) {
                                    sliderCarrier = value.toFloat()
                                    onCarrierFrequencyChange(value)
                                } else {
                                    // Восстанавливаем предыдущее значение при ошибке
                                    tempCarrierFrequency = TextFieldValue(formatFrequency(point.carrierFrequency))
                                }
                            }
                        }
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(
                            width = 1.dp,
                            color = if (!isCarrierValid && tempCarrierFrequency.text.isNotEmpty())
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.outline,
                            shape = MaterialTheme.shapes.small
                        )
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    textStyle = MaterialTheme.typography.titleSmall.copy(
                        textAlign = TextAlign.End,
                        color = if (!isCarrierValid && tempCarrierFrequency.text.isNotEmpty())
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                )
                
                Text(
                    text = hzLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )
                
                Slider(
                    value = sliderCarrier,
                    onValueChange = {
                        val rounded = kotlin.math.round(it)
                        sliderCarrier = rounded
                        tempCarrierFrequency = TextFieldValue(formatFrequency(rounded.toFloat()))
                    },
                    onValueChangeFinished = { onCarrierFrequencyChange(kotlin.math.round(sliderCarrier).toFloat()) },
                    valueRange = carrierRange.min.toFloat()..carrierRange.max.toFloat(),
                    modifier = Modifier.weight(1f).padding(start = 4.dp).height(24.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Частота биений - подпись
            Text(
                "$beatsLabel:",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
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
                        val limited = limitFrequencyInput(newValue.text)
                        // Если при удалении появился "0", выделяем его полностью
                        if (limited == "0" && tempBeatFrequency.text.length > 1) {
                            tempBeatFrequency = TextFieldValue(limited, selection = TextRange(0, 1))
                        } else if (limited != newValue.text) {
                            // Текст был изменён (ограничен), сохраняем курсор в конце
                            tempBeatFrequency = TextFieldValue(limited, selection = TextRange(limited.length))
                        } else {
                            // Текст не изменился, сохраняем оригинальный selection
                            tempBeatFrequency = newValue.copy(text = limited)
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            // Сохраняем значение при нажатии Done
                            val value = parseFrequency(tempBeatFrequency.text)
                            if (value != null && value >= beatRange.min && value <= maxBeatFrequencyForValidation) {
                                onBeatFrequencyChange(value)
                            } else {
                                // Восстанавливаем предыдущее значение при ошибке
                                tempBeatFrequency = TextFieldValue(formatFrequency(point.beatFrequency))
                            }
                            keyboardController?.hide()
                            focusManager.clearFocus()
                        }
                    ),
                    singleLine = true,
                    modifier = Modifier
                        .width(64.dp)
                        .focusRequester(beatFocusRequester)
                        .onFocusEvent { focusState ->
                            if (focusState.isFocused) {
                                beatWasFocused = true
                            } else if (beatWasFocused) {
                                // Фокус был потерян после того как был получен - сохраняем
                                beatWasFocused = false
                                val value = parseFrequency(tempBeatFrequency.text)
                                if (value != null && value >= beatRange.min && value <= maxBeatFrequencyForValidation) {
                                    sliderBeat = value.toFloat()
                                    onBeatFrequencyChange(value)
                                } else {
                                    // Восстанавливаем предыдущее значение при ошибке
                                    tempBeatFrequency = TextFieldValue(formatFrequency(point.beatFrequency))
                                }
                            }
                        }
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(
                            width = 1.dp,
                            color = if (!isBeatValid && tempBeatFrequency.text.isNotEmpty())
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.outline,
                            shape = MaterialTheme.shapes.small
                        )
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    textStyle = MaterialTheme.typography.titleSmall.copy(
                        textAlign = TextAlign.End,
                        color = if (!isBeatValid && tempBeatFrequency.text.isNotEmpty())
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                )
                
                Text(
                    text = hzLabel,
                    style = MaterialTheme.typography.titleSmall,
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
                        tempBeatFrequency = TextFieldValue(formatFrequency(rounded.toFloat()))
                    },
                    onValueChangeFinished = { onBeatFrequencyChange(kotlin.math.round(sliderBeat).toFloat()) },
                    valueRange = minBeatForSlider..maxBeatForSlider,
                    modifier = Modifier.weight(1f).padding(start = 4.dp).height(24.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }
    }
}