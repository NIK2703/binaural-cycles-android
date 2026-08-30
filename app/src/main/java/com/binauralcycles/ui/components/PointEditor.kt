package com.binauralcycles.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.binaural.core.audio.model.FrequencyMath
import com.binaural.core.audio.model.FrequencyPoint
import com.binaural.core.audio.model.FrequencyRange
import com.binauralcycles.R
import kotlinx.datetime.LocalTime
import java.util.Locale
import kotlin.math.roundToInt

private const val MIN_AUDIBLE_FREQUENCY = 20.0f

/** Ширина «хвостика» контекстного окна точки у его основания. */
val POINT_POPUP_ARROW_WIDTH = 14.dp

/** Высота «хвостика» контекстного окна точки. */
val POINT_POPUP_ARROW_HEIGHT = 7.dp

/** Расстояние от центра маркера точки до кончика хвостика. */
val POINT_POPUP_ANCHOR_GAP = 16.dp

/** Ширина контекстного окна точки, включая внутренние поля. */
val POINT_POPUP_WIDTH = 116.dp

/**
 * Желаемый отступ окна от края ЭКРАНА, когда выбрана крайняя точка графика.
 * Окно намеренно выносится за боковые границы области графика — иначе у
 * крайних точек оно упиралось бы в край и хвостик переставал смотреть на
 * точку. Раньше окно не выходило за область графика, и до края экрана
 * оставалось 16 dp полей карточки + 16 dp полей экрана = 32 dp.
 */
val POINT_POPUP_SCREEN_MARGIN = 10.dp

/** Горизонтальные поля вокруг области графика: карточка + экран. */
val POINT_POPUP_OUTER_PADDING = 32.dp

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
 *
 * @param allowNegative разрешить ведущий минус. Нужно для ЧАСТОТЫ БИЕНИЙ,
 *        которая величина знаковая (beat = right − left; знак задаёт раскладку
 *        каналов). Для несущей частоты минус бессмысленен и отбрасывается.
 */
private fun limitFrequencyInput(value: String, allowNegative: Boolean = false): String {
    // Знак: минус учитывается, только если он ВЕДУЩИЙ и разрешён.
    // "-" в середине строки — это не знак числа, а мусор: отбрасываем.
    val sign = if (allowNegative && value.trimStart().startsWith('-')) "-" else ""

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
        // "-0" не имеет смысла — знак теряется (минус пропадёт при вводе цифр после "-0")
        if (digits.isEmpty() || digits.all { it == '0' }) sign else sign + digits.trimLeadingZeros()
    } else {
        // Есть разделитель - разбиваем на целую и дробную части
        val integerPart = value.substring(0, separatorIndex).filter { it.isDigit() }.take(4)
        val decimalPart = value.substring(separatorIndex + 1).filter { it.isDigit() }.take(2)

        // Убираем ведущие нули в целой части, но оставляем один ноль для чисел вида "0.xxx"
        val normalizedInteger = integerPart.trimLeadingZeros()

        // Собираем результат с точкой как разделителем
        // Всегда сохраняем точку, даже если дробная часть пуста (пользователь продолжает ввод)
        if (decimalPart.isEmpty()) {
            "$sign$normalizedInteger."
        } else {
            "$sign$normalizedInteger.$decimalPart"
        }
    }
}

/**
 * Снимок значений, набранных в полях окна, но ещё не ушедших наружу.
 *
 * Поля уходят наверх при потере фокуса и по Done. Но если пользователь набрал
 * значение и сразу перешёл к другой точке (или закрыл окно), фокус с поля не
 * снимается — Compose сам его не сбрасывает при касании нефокусируемого
 * элемента — и набранное молча пропадало бы. Поэтому снимок снимается после
 * каждой композиции и применяется при уходе окна с этой точки.
 *
 * Снимаются именно СТРОКИ и текущие значения точки, а не [androidx.compose.runtime.State]:
 * к моменту `onDispose` remember-состояния полей уже пересозданы под новую
 * точку, и чтение их дало бы значения НОВОЙ точки.
 */
private data class PendingPointValues(
    val carrierText: String,
    val beatText: String,
    val hours: String,
    val minutes: String,
    /** Предел МОДУЛЯ частоты биений для текущего значения несущей в поле. */
    val maxBeatMagnitude: Float,
    /** Значения точки на момент снимка — чтобы не перезаписывать тем же. */
    val currentCarrier: Float,
    val currentBeat: Float,
    val currentTime: LocalTime
)

/**
 * Применяет набранное в полях, если оно валидно и реально отличается.
 *
 * Проверка «отличается» здесь не для оптимизации: обновление несущей внутри
 * подтягивает частоту биений (`adjustBeatForCarrier`), поэтому слепая запись
 * несущей могла бы затереть только что изменённые биения.
 */
private fun applyPendingValues(
    pending: PendingPointValues,
    onCarrierFrequencyChange: (Float) -> Unit,
    onBeatFrequencyChange: (Float) -> Unit,
    onTimeChange: (LocalTime) -> Unit
) {
    // Несущая
    val carrier = parseFrequency(pending.carrierText)
    if (carrier != null && carrier >= MIN_AUDIBLE_FREQUENCY && carrier <= 2000.0f &&
        carrier != pending.currentCarrier
    ) {
        onCarrierFrequencyChange(carrier)
    }

    // Биения: предел по МОДУЛЮ — beat знаковая величина (знак = раскладка
    // каналов), поэтому проверяем abs, а не сам диапазон.
    val beat = parseFrequency(pending.beatText)
    if (beat != null && kotlin.math.abs(beat) <= pending.maxBeatMagnitude &&
        beat != pending.currentBeat
    ) {
        onBeatFrequencyChange(beat)
    }

    // Время: неполный ввод (например, одна цифра) дополняем текущим значением,
    // как это делает ручной коммит по потере фокуса.
    val hours = pending.hours.toIntOrNull()?.coerceIn(0, 23) ?: pending.currentTime.hour
    val minutes = pending.minutes.toIntOrNull()?.coerceIn(0, 59) ?: pending.currentTime.minute
    if (hours != pending.currentTime.hour || minutes != pending.currentTime.minute) {
        onTimeChange(LocalTime(hours, minutes))
    }
}

/**
 * Убирает ведущие нули из строки цифр, но оставляет один ноль если строка пустая или состоит только из нулей
 */
private fun String.trimLeadingZeros(): String {
    val trimmed = this.trimStart('0')
    return if (trimmed.isEmpty()) "0" else trimmed
}

/**
 * Уголок контекстного окна: треугольник, указывающий на редактируемую точку.
 *
 * Окно всегда стоит ПОД точкой, поэтому уголок всегда один и тот же:
 * основание примыкает к телу окна сверху, а остриё смотрит вверх, на точку.
 * Между уголком и телом окна нет зазора — треугольник дорисован вплотную.
 */
private fun DrawScope.drawPopupArrow(color: Color) {
    val path = Path().apply {
        moveTo(0f, size.height)
        lineTo(size.width, size.height)
        lineTo(size.width / 2f, 0f)
        close()
    }
    drawPath(path, color)
}

/**
 * Компактное контекстное окно редактирования точки графика.
 *
 * Всплывает слоем поверх графика прямо ПОД маркером выбранной точки, а не
 * живёт отдельным разделом в списке опций: так сразу видно, какую именно
 * точку правим, а экран редактора не разъезжается при выделении. Уголок на
 * ВЕРХНЕЙ стороне окна смотрит вверх и указывает на точку — то есть окно
 * подходит к точке СНИЗУ, а не сверху.
 *
 * Слайдеров нет — только поля ввода. Слайдеры не оставили бы окно компактным
 * ни по ширине, ни по высоте, а частота всё равно правится перетаскиванием
 * самой точки по графику.
 *
 * @param arrowOffsetX где внутри окна стоит уголок, в ПИКСЕЛЯХ от левого края
 *        (считается по центру уголка). Задаётся вызывающим: окно прижимается
 *        к краям графика, а уголок всё равно должен смотреть на точку.
 *        ФУНКЦИЯ, а не готовое число: во время переезда к другой точке
 *        значение меняется каждый кадр, и считается оно на фазе раскладки —
 *        так уголок едет вместе с окном, а содержимое окна при этом не
 *        перекомпонуется ни разу.
 * @param pointIndex индекс редактируемой точки — НЕ сама точка. Нужен как ключ
 *        [DisposableEffect]: точка пересоздаётся на каждое изменение значений,
 *        а индекс меняется только при переходе к другой точке. Благодаря этому
 *        набранное в полях применяется при переключении и не применяется
 *        (не откатывает только что применённое) на каждом нажатии клавиши.
 */
@Composable
fun PointEditorPopup(
    point: FrequencyPoint,
    pointIndex: Int,
    carrierRange: FrequencyRange,
    autoExpandGraphRange: Boolean,
    arrowOffsetX: () -> Float,
    onCarrierFrequencyChange: (Float) -> Unit,
    onBeatFrequencyChange: (Float) -> Unit,
    onTimeChange: (LocalTime) -> Unit,
    onRemove: () -> Unit
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

    val isCarrierValid = carrierValue != null && carrierValue >= MIN_AUDIBLE_FREQUENCY && carrierValue <= 2000.0f

    // Границы частоты биений СИММЕТРИЧНЫ по модулю (beat — знаковая величина,
    // beat = right − left; знак задаёт раскладку каналов, |beat| — пульсация):
    //   1. |beat| <= 2*(carrier − 20 Гц):    обе боковые остаются >= 20 Гц;
    //   2. |beat| <= 2*(2000 Гц − carrier):  обе боковые остаются <= 2000 Гц;
    //   3. если autoExpandGraphRange = false, дополнительно границами графика:
    //      |beat| <= 2*(carrier − carrierRange.min) и 2*(carrierRange.max − carrier).
    // Иначе говоря, предел — это УДВОЕННОЕ РАССТОЯНИЕ ОТ НЕСУЩЕЙ ВЫБРАННОЙ
    // ТОЧКИ ДО БЛИЖАЙШЕЙ ГРАНИЦЫ. Хранимый в пресете beatRange здесь НЕ
    // участвует: это масштаб для размера маркера на графике, а не разрешённый
    // предел, поэтому старый потолок 1000 Гц больше не режет выбор.

    // Максимальный МОДУЛЬ частоты биений вычисляется от текущего значения
    // в текстовом поле (для валидации) или от значения точки (для проверки
    // при потере фокуса). Пол 1 Гц — чтобы точка, стоящая ровно на границе
    // графика (там геометрический предел равен нулю), оставалась редактируемой.
    val maxBeatMagnitudeForValidation = run {
        val carrierForLimit = if (carrierValue != null && isCarrierValid) carrierValue
                              else point.carrierFrequency
        val range = if (autoExpandGraphRange) null else carrierRange
        FrequencyMath.maxBeatMagnitude(carrierForLimit, range).coerceAtLeast(1.0f)
    }

    // Валидация частоты биений: проверяем МОДУЛЬ (знак разрешён всегда),
    // относительно текущего значения несущей в поле ввода
    val isBeatValid = beatValue != null &&
        beatValue >= -maxBeatMagnitudeForValidation &&
        beatValue <= maxBeatMagnitudeForValidation

    // Управление фокусом и клавиатурой
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // Локализованные строки
    val deleteLabel = stringResource(R.string.delete)
    val carrierLabel = stringResource(R.string.carrier)
    val beatsLabel = stringResource(R.string.beats)
    val hzLabel = stringResource(R.string.hz)

    // Состояние для редактирования времени
    var tempHours by remember(point.time.hour) { mutableStateOf(point.time.hour.toString().padStart(2, '0')) }
    var tempMinutes by remember(point.time.minute) { mutableStateOf(point.time.minute.toString().padStart(2, '0')) }
    var hoursWasFocused by remember { mutableStateOf(false) }
    var minutesWasFocused by remember { mutableStateOf(false) }

    fun validateAndSaveTime() {
        val hours = tempHours.toIntOrNull()?.coerceIn(0, 23) ?: point.time.hour
        val minutes = tempMinutes.toIntOrNull()?.coerceIn(0, 59) ?: point.time.minute
        onTimeChange(LocalTime(hours, minutes))
    }

    // Снимок набранного. Держатель переживает перекомпозиции (remember), а
    // обновляется в SideEffect — то есть ПОСЛЕ успешной композиции. Это важно:
    // при уходе окна с точки Compose сначала выполняет onDispose
    // (dispatchRememberObservers) и только потом side-эффекты новой
    // композиции, поэтому в onDispose держатель ещё хранит Прежние строки.
    val pendingValues = remember { mutableStateOf<PendingPointValues?>(null) }
    SideEffect {
        pendingValues.value = PendingPointValues(
            carrierText = tempCarrierFrequency.text,
            beatText = tempBeatFrequency.text,
            hours = tempHours,
            minutes = tempMinutes,
            maxBeatMagnitude = maxBeatMagnitudeForValidation,
            currentCarrier = point.carrierFrequency,
            currentBeat = point.beatFrequency,
            currentTime = point.time
        )
    }

    // Набранное применяется, когда окно уходит с ЭТОЙ точки: переключение на
    // другую точку или закрытие окна. Ключ — индекс, а не сама точка.
    DisposableEffect(pointIndex) {
        onDispose {
            pendingValues.value?.let {
                applyPendingValues(it, onCarrierFrequencyChange, onBeatFrequencyChange, onTimeChange)
            }
        }
    }

    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val errorColor = MaterialTheme.colorScheme.error
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(12.dp)

    // Тело окна и уголок раскладываются вручную: уголок стоит в заданной
    // точке по X (смотрит на точку), а тело — под ним.
    Layout(
        content = {
            Box(
                modifier = Modifier
                    .size(POINT_POPUP_ARROW_WIDTH, POINT_POPUP_ARROW_HEIGHT)
                    .drawBehind { drawPopupArrow(surfaceColor) }
            )
            Surface(
                color = surfaceColor,
                shape = shape,
                shadowElevation = 6.dp,
                tonalElevation = 6.dp
            ) {
                // Ширина фиксирована: иначе строки с weight(1f) растянули бы
                // окно на весь график.
                Column(modifier = Modifier.width(POINT_POPUP_WIDTH).padding(horizontal = 6.dp, vertical = 6.dp)) {
                    // Строка 1: время и удаление точки. Поля времени тянутся
                    // (weight), кнопка удаления — впритык к правому краю:
                    // в строке нет ни среднего, ни концевого отступа.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TimeField(
                            modifier = Modifier.weight(1f),
                            value = tempHours,
                            onValueChange = { input ->
                                tempHours = input.filter { it.isDigit() }.take(2)
                                if (tempHours.length == 2) validateAndSaveTime()
                            },
                            wasFocused = hoursWasFocused,
                            onFocusChanged = { hoursWasFocused = it },
                            onCommit = {
                                validateAndSaveTime()
                                tempHours = point.time.hour.toString().padStart(2, '0')
                            }
                        )
                        Text(
                            ":",
                            style = MaterialTheme.typography.titleSmall,
                            color = onSurfaceColor
                        )
                        TimeField(
                            modifier = Modifier.weight(1f),
                            value = tempMinutes,
                            onValueChange = { input ->
                                tempMinutes = input.filter { it.isDigit() }.take(2)
                                if (tempMinutes.length == 2) validateAndSaveTime()
                            },
                            wasFocused = minutesWasFocused,
                            onFocusChanged = { minutesWasFocused = it },
                            onCommit = {
                                validateAndSaveTime()
                                tempMinutes = point.time.minute.toString().padStart(2, '0')
                            }
                        )
                        // Не IconButton: тот тянет за собой минимальный тач-таргет
                        // 48 dp и отбирает ширину у полей часов и минут.
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .clickable { onRemove() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = deleteLabel,
                                tint = errorColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Строка 2: подпись несущей
                    Text(
                        "$carrierLabel:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))

                    // Строка 3: поле несущей и Гц. Поле тянется до конца строки,
                    // «Гц» прижато к правому краю — пустого хвоста нет.
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        FrequencyField(
                            modifier = Modifier.weight(1f),
                            value = tempCarrierFrequency,
                            isValid = isCarrierValid,
                            onValueChange = { newValue ->
                                val limited = limitFrequencyInput(newValue.text)
                                tempCarrierFrequency = if (limited != newValue.text) {
                                    TextFieldValue(limited, selection = TextRange(limited.length))
                                } else {
                                    newValue
                                }
                            },
                            onCommit = { commit ->
                                val value = parseFrequency(tempCarrierFrequency.text)
                                if (value != null && value >= MIN_AUDIBLE_FREQUENCY && value <= 2000.0f) {
                                    onCarrierFrequencyChange(value)
                                } else {
                                    tempCarrierFrequency = TextFieldValue(formatFrequency(point.carrierFrequency))
                                }
                                if (commit) {
                                    keyboardController?.hide()
                                    focusManager.clearFocus()
                                }
                            }
                        )
                        Text(
                            text = hzLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Строка 4: подпись биений
                    Text(
                        "$beatsLabel:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))

                    // Строка 5: поле биений и Гц. Так же тянется до конца строки.
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        FrequencyField(
                            modifier = Modifier.weight(1f),
                            value = tempBeatFrequency,
                            isValid = isBeatValid,
                            onValueChange = { newValue ->
                                // allowNegative=true: частота биений знаковая
                                val limited = limitFrequencyInput(newValue.text, allowNegative = true)
                                tempBeatFrequency = if (limited != newValue.text) {
                                    TextFieldValue(limited, selection = TextRange(limited.length))
                                } else {
                                    newValue
                                }
                            },
                            onCommit = { commit ->
                                val value = parseFrequency(tempBeatFrequency.text)
                                if (value != null &&
                                    value >= -maxBeatMagnitudeForValidation &&
                                    value <= maxBeatMagnitudeForValidation
                                ) {
                                    onBeatFrequencyChange(value)
                                } else {
                                    tempBeatFrequency = TextFieldValue(formatFrequency(point.beatFrequency))
                                }
                                if (commit) {
                                    keyboardController?.hide()
                                    focusManager.clearFocus()
                                }
                            }
                        )
                        Text(
                            text = hzLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }
        }
    ) { measurables, constraints ->
        val arrow = measurables[0].measure(Constraints())
        val body = measurables[1].measure(
            constraints.copy(minWidth = 0, maxWidth = constraints.maxWidth)
        )
        // Ширина окна не меньше ширины уголка, иначе он не поместится.
        val width = maxOf(body.width, arrow.width)
        val height = body.height + arrow.height
        // Уголок не должен выходить за боковые стенки окна.
        val arrowHalf = arrow.width / 2
        val arrowX = (arrowOffsetX().roundToInt() - arrowHalf)
            .coerceIn(0, (width - arrow.width).coerceAtLeast(0))
        layout(width, height) {
            // Окно всегда ПОД точкой: уголок сверху смотрит вверх, на точку.
            arrow.place(arrowX, 0)
            body.place(0, arrow.height)
        }
    }
}

/**
 * Поле ввода частоты контекстного окна точки: узкое, без слайдера.
 *
 * Значение уходит наверх при потере фокуса и по действию Done на клавиатуре.
 */
@Composable
private fun FrequencyField(
    value: TextFieldValue,
    isValid: Boolean,
    onValueChange: (TextFieldValue) -> Unit,
    onCommit: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    var wasFocused by remember { mutableStateOf(false) }

    BasicTextField(
        value = value,
        onValueChange = { onValueChange(it) },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { onCommit(true) }),
        singleLine = true,
        modifier = modifier
            .widthIn(min = 56.dp)
            .focusRequester(focusRequester)
            .onFocusEvent { focusState ->
                if (focusState.isFocused) {
                    wasFocused = true
                } else if (wasFocused) {
                    wasFocused = false
                    onCommit(false)
                }
            }
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = if (!isValid && value.text.isNotEmpty()) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.outline,
                shape = MaterialTheme.shapes.small
            )
            .padding(horizontal = 4.dp, vertical = 4.dp),
        textStyle = MaterialTheme.typography.titleSmall.copy(
            textAlign = TextAlign.End,
            color = if (!isValid && value.text.isNotEmpty()) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
    )
}

/**
 * Поле ввода часов или минут контекстного окна точки.
 */
@Composable
private fun TimeField(
    value: String,
    wasFocused: Boolean,
    onValueChange: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { onCommit() }),
        singleLine = true,
        modifier = modifier
            // 36 dp — историческая ширина полей часов и минут: уже не сдавлены.
            .widthIn(min = 36.dp)
            .focusRequester(focusRequester)
            .onFocusEvent { focusState ->
                if (focusState.isFocused) {
                    onFocusChanged(true)
                } else if (wasFocused) {
                    onFocusChanged(false)
                    onCommit()
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
