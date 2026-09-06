package com.binauralcycles.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
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
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round
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
 * Подъём тела контекстного окна над графиком. Одной величиной задаются и
 * тень ([Surface.shadowElevation]), и тон ([Surface.tonalElevation]):
 * окно и приподнято, и тонировано одинаково.
 *
 * Обнуление — единственный выключатель тени: с ним окно ложится на график
 * вплотную (см. также [POINT_POPUP_SHADOW_PAD]).
 */
val POINT_POPUP_ELEVATION = 6.dp

/**
 * Запас под тень вокруг окна: на столько ВО ВСЕ СТОРОНЫ расширяется
 * анимируемый слой окна (см. `graphicsLayer` в FrequencyGraph).
 *
 * Зачем: тень тела рисуется ВНЕ прямоугольника окна — под ним, слева и
 * справа, — а слой анимации (graphicsLayer с alpha и translationY) рисует
 * содержимое в отдельный буфер и обрезает рисование по своим границам.
 * Пока границы слоя в точности повторяют окно, вся эта тень срезается, и
 * на экране остаются только клочки тени в скруглённых углах — там, где
 * тень случайно попадает ВНУТРЬ прямоугольника окна. Расширенный слой
 * вмещает тень целиком, и она едет вместе с окном.
 *
 * Величина взята с запасом против [POINT_POPUP_ELEVATION]: у материал-тени
 * и размытие, и сдвиг вниз кратны высоте подъёма, так что 24 dp накрывают
 * вылет подъёма в 6 dp целиком. Плата — лишь чуть больший буфер слоя.
 */
val POINT_POPUP_SHADOW_PAD = 24.dp

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
 * Сколько пикселей вертикального свайпа приходится на одно «деление» значения
 * в полях контекстного окна.
 *
 * 16 dp — компромисс между точностью и скоростью: за взмах пальца на высоту
 * экрана (~500 dp) проходится около 30 делений. Часы (24 деления на весь
 * диапазон) и минуты (12) перебираются одним движением, у несущей и биений
 * остаётся разумная точность для подстройки.
 */
private val VALUE_SWIPE_STEP = 16.dp

/**
 * Внутренние поля [BasicTextField] в [FrequencyField] и [TimeField].
 *
 * Вынесены в константу потому, что от них зависит вторая величина: тап,
 * перехваченный свайпом, ищет позицию курсора по раскладке текста, а
 * раскладка считает от ВНУТРЕННЕЙ границы поля, тогда как точка касания
 * приходит в координатах обёртки. Два числа, обязанные совпадать, живут
 * рядом — иначе курсор уедет на символ в сторону.
 */
private val FIELD_INNER_PADDING = 4.dp

/**
 * Вертикальный свайп, меняющий значение «делениями»: каждые [stepPx] пикселей
 * хода пальца — одно деление. Свайп ВВЕРХ увеличивает значение.
 *
 * Обычный тап работает штатно: касание НЕ поглощается и доходит до вложенного
 * `BasicTextField` — тот сам ставит курсор, показывает ручку перемещения и
 * открывает клавиатуру, как любое текстовое поле.
 *
 * Жест забирает себе только вертикальную ПРОТЯЖКУ. Пока палец не ушёл за
 * порог скролла, события не поглощаются и доходят до поля — значит, короткое
 * касание остаётся тапом. Как только движение становится свайпом, все
 * последующие события поглощаются, чтобы поле не тащило курсор/выделение, а
 * значение меняется «делениями». Клавиатуру и фокус прячет вызывающий
 * ([onStep] на первом делении), так что под пальцем во время свайпа нет ни
 * клавиатуры, ни мигающего курсора.
 *
 * @param onStep по одному вызову на деление; аргумент — направление (+1 вверх,
 *        −1 вниз). Возвращает true, если значение реально изменилось: у
 *        границы диапазона деление может ничего не сдвинуть, и тогда
 *        тактильный отклик только сбивал бы с толку.
 * @param onGestureEnd конец жеста — и после свайпа, и после тапа, и при
 *        отмене. Здесь вызывающий применяет накопленное значение: деления
 *        сами по себе наружу не уходят (см. [ValueSwipeBox]).
 */
@Composable
private fun Modifier.verticalValueSwipe(
    stepPx: Float,
    onStep: (direction: Int) -> Boolean,
    onGestureEnd: () -> Unit
): Modifier {
    // Тактильный отклик — через View, а не через LocalHapticFeedback:
    // CLOCK_TICK — это ровно тот «тик барабанчика», который нужен для
    // делений, а обёртки Compose для него нет.
    val view = LocalView.current
    // Колбэки читаются через rememberUpdatedState, а ключ pointerInput — только
    // stepPx: иначе жест перезапускался бы на каждой перекомпозиции, а она
    // случается на каждое же деление (значение уходит наверх).
    val onStepState = rememberUpdatedState(onStep)
    val onGestureEndState = rememberUpdatedState(onGestureEnd)

    return pointerInput(stepPx) {
        val slop = viewConfiguration.touchSlop
        awaitPointerEventScope {
            while (true) {
                val down = awaitFirstDown(pass = PointerEventPass.Initial)
                // Касание намеренно НЕ поглощаем: поле получает его и ставит
                // курсор стандартным образом. Честный тап так и остаётся тапом.
                var travel = 0f     // вертикальный ход пальца от точки касания
                var notches = 0     // сколько делений отдано наверх
                var swiping = false
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: break            // палец пропал — жест отменён
                        // Сдвиг читаем ДО consume(): positionChange() обнуляется,
                        // как только сдвиг помечен использованным — иначе ход
                        // пальца всегда равен нулю и жест не сдвинет значение.
                        // IgnoreConsumed страхует и от чужого consume() на этом
                        // же событии.
                        val deltaY = change.positionChangeIgnoreConsumed().y
                        travel += deltaY

                        // До порога жест ещё может оказаться тапом — не
                        // поглощаем события, отпускание отдаём полю.
                        if (!swiping) {
                            if (abs(travel) <= slop) {
                                if (!change.pressed) break   // честный тап
                                continue
                            }
                            swiping = true
                            // Отсчёт ведём ОТ порога: иначе первое деление
                            // стоило бы на slop больше остальных, и свайп
                            // начинался бы рывком.
                            travel -= if (travel > 0f) slop else -slop
                        }
                        // Дальше — только свайп: гасим события, чтобы поле не
                        // реагировало на протяжку (не тащило курсор/выделение).
                        if (swiping) change.consume()

                        // Целое число делений, уложившихся в ход. Ось Y на
                        // экране смотрит вниз, поэтому у свайпа вверх сдвиг
                        // отрицательный — отсюда минус.
                        val target = -(travel / stepPx).toInt()
                        while (notches != target) {
                            val direction = if (target > notches) 1 else -1
                            notches += direction
                            if (onStepState.value(direction)) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                        }

                        if (!change.pressed) break
                    }
                } finally {
                    onGestureEndState.value()
                }
            }
        }
    }
}

/**
 * Сдвиг к [gridValue], который никогда не уводит значение «назад».
 *
 * Текущее значение может стоять ВНЕ сетки: его набрали руками (несущая 5 Гц
 * при нижней границе 20 Гц) или его зажало при изменении несущей (частота
 * биений у предела). Ближайший узел сетки тогда лежит в стороне,
 * ПРОТИВОПОЛОЖНОЙ свайпу, и без этой проверки жест «уменьшить» внезапно
 * увеличил бы значение.
 *
 * @return [gridValue], если он сдвинут в сторону свайпа, иначе [current]
 *         — то есть «сдвига нет» (и вызывающий не даст отклик).
 */
private fun stepWithinGrid(gridValue: Float, current: Float, delta: Int): Float =
    if (delta > 0) maxOf(gridValue, current) else minOf(gridValue, current)

/**
 * Обёртка над полем ввода контекстного окна: вертикальный свайп по полю
 * меняет значение «делениями», а обычный тап оставляет полю — оно само
 * открывает клавиатуру и ставит курсор, как стандартное текстовое поле.
 *
 * Значение применяется к кривой ОДИН РАЗ НА ЖЕСТ — по отпускании пальца,
 * а не на каждое деление. Раньше деление уходило сразу наверх, в точку, и
 * перестраивало всю кривую: новый список точек, новая `FrequencyCurve`,
 * кривые статического слоя графика и, если редактируется активный пресет,
 * ещё и обновление нативного движка. Взмах на высоту экрана — это около
 * тридцати делений, то есть тридцать полных перестроек ради одного итогового
 * значения. Теперь деление меняет только текст в поле ([preview]) — дешёвую
 * перекомпозицию самого окна — а к кривой ([commit]) уходит итог жеста,
 * и только если он действительно отличается от исходного.
 *
 * @param stepPx пикселей на одно деление
 * @param read текущее значение: набранное в поле, а если оно не число — из точки
 * @param shift значение на [delta] делений выше/ниже, приведённое к сетке
 *        и зажатое границами
 * @param preview показать промежуточное значение: пишет его в поле, не трогая
 *        кривую. Должно быть дешёвым — вызывается на каждое деление.
 * @param commit применить итоговое значение к точке и кривой. Один вызов на
 *        жест, и только если значение реально изменилось.
 */
@Composable
private fun ValueSwipeBox(
    stepPx: Float,
    read: () -> Float,
    shift: (current: Float, delta: Int) -> Float,
    preview: (Float) -> Unit,
    commit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val readState = rememberUpdatedState(read)
    val shiftState = rememberUpdatedState(shift)
    val previewState = rememberUpdatedState(preview)
    val commitState = rememberUpdatedState(commit)
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // Значение, которым управляет текущий свайп. Держатель нужен потому, что
    // за один кадр может прийти несколько делений, а результат [preview] дойдёт
    // до [read] только после перекомпозиции: без держателя все деления кадра
    // считались бы от одной точки и слились бы в одно.
    var swiped by remember { mutableStateOf<Float?>(null) }
    // Значение, с которого жест начался. Без него наружу уходила бы лишняя
    // перестройка кривой: если деления всё вернули на исходное (или сразу
    // упёрлись в границу диапазона), применять нечего.
    var gestureStart by remember { mutableStateOf<Float?>(null) }

    Box(
        modifier = modifier.verticalValueSwipe(
            stepPx = stepPx,
            onStep = { delta ->
                val from = swiped ?: run {
                    // Свайп — это не ввод с клавиатуры: прячем клавиатуру и
                    // снимаем фокус с поля, чтобы под пальцем не вскакивала
                    // клавиатура и не мелькал курсор. Стандартный тап этого не
                    // задевает — фокус и клавиатура появляются только по нему.
                    keyboardController?.hide()
                    focusManager.clearFocus()
                    readState.value().also { gestureStart = it }
                }
                val next = shiftState.value(from, delta)
                swiped = next
                previewState.value(next)
                next != from
            },
            // Отпускание пальца: итог жеста уходит к кривой. Сюда же попадают
            // отмена жеста и честный тап — но при тапе [swiped] пуст, и
            // применять нечего.
            onGestureEnd = {
                val start = gestureStart
                val end = swiped
                if (start != null && end != null && end != start) {
                    commitState.value(end)
                }
                gestureStart = null
                swiped = null
            }
        )
    ) { content() }
}

/**
 * Парсит строку в Float, принимая как точку, так и запятую как разделитель
 */
private fun parseFrequency(value: String): Float? {
    return value.replace(',', '.').toFloatOrNull()
}

/**
 * Региональный десятичный разделитель локали системы: точка или запятая.
 * Поля частот принимают ВВОД в обоих вариантах (клавиатура Decimal даёт ту
 * клавишу, что привычна пользователю), а показывают значения региональным —
 * поэтому набранное с клавиатуры и подставленное свайпом выглядит одинаково.
 */
private val decimalSeparator: Char =
    java.text.DecimalFormatSymbols.getInstance().decimalSeparator

/**
 * Ограничивает ввод частоты: максимум 4 знака в целой части и 4 в дробной.
 * Разделителем служит ПЕРВЫЙ введённый из точки и запятой — как набрал
 * пользователь, так и остаётся (региональная привычка: запятая в ряде
 * локалей не переписывается точкой и наоборот); второй разделитель и всё
 * после него отбрасываются как мусор.
 * Убирает ведущие нули в целой части (кроме случая "0.xxx").
 * Строка из ОДНИХ нулей сохраняется как есть ("000" → "000"): нули набраны
 * или оставлены после удаления ненулевых цифр намеренно, и стирать их —
 * значит удалять больше, чем удалил пользователь.
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
        when {
            // Поле без цифр — остаётся только знак (набор минуса до цифр).
            digits.isEmpty() -> sign
            // Одни нули НЕ трогаем: пользователь удалил ненулевые цифры
            // и оставил нули ("1000" → "000"), и поле обязано показать
            // ровно то, что он оставил — иначе удаление одной цифры
            // стирало всё поле целиком. Ноль как значение отсечёт
            // валидация при commit (у несущей 0 ниже порога слышимости —
            // значение откатится к прежнему).
            digits.all { it == '0' } -> sign + digits
            // Обычный ввод: ведущие нули схлопываем ("0100" → "100")
            else -> sign + digits.trimLeadingZeros()
        }
    } else {
        // Есть разделитель - разбиваем на целую и дробную части
        val integerPart = value.substring(0, separatorIndex).filter { it.isDigit() }.take(4)
        val decimalPart = value.substring(separatorIndex + 1).filter { it.isDigit() }.take(4)

        // Убираем ведущие нули в целой части, но оставляем один ноль для чисел вида "0.xxx"
        val normalizedInteger = integerPart.trimLeadingZeros()

        // Разделитель сохраняем РОВНО как набрал пользователь (точку или
        // запятую) — parseFrequency понимает оба, а переписывание знака
        // под пальцем выглядело бы как порча ввода.
        // Разделитель держим, даже если дробная часть пуста (пользователь
        // продолжает ввод).
        val separator = value[separatorIndex]
        if (decimalPart.isEmpty()) {
            "$sign$normalizedInteger$separator"
        } else {
            "$sign$normalizedInteger$separator$decimalPart"
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
    onRemove: () -> Unit,
    /**
     * Можно ли удалить точку. false, когда в кривой осталась последняя
     * (единственная) точка — кнопка удаления неактивна и не кликабельна.
     */
    canRemove: Boolean = true
) {
    // Функция форматирования: показывает до 4-х ненулевых знаков после
    // разделителя — столько же, сколько принимает ввод, иначе набранное
    // вручную значение при следующем открытии окна отражалось бы урезанным.
    // Числа без дробной части — целыми. Разделитель — региональный
    // ([decimalSeparator]): форматируем в Locale.US и меняем точку на
    // разделитель локали, чтобы вид поля совпадал с тем, что даёт клавиатура.
    fun formatFrequency(value: Float): String {
        val formatted = if (value == kotlin.math.floor(value)) {
            value.toInt().toString()
        } else {
            // Форматируем с 4 знаками после разделителя и убираем trailing нули
            "%.4f".format(Locale.US, value).trimEnd('0').trimEnd('.')
        }
        return if (decimalSeparator != '.') formatted.replace('.', decimalSeparator) else formatted
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
    //   3. если autoExpandGraphRange = false, каналы не должны выходить и за
    //      вертикальные границы графика.
    // Хранимый в пресете beatRange здесь НЕ участвует: это масштаб для
    // размера маркера на графике, а не разрешённый предел, поэтому старый
    // потолок 1000 Гц больше не режет выбор.
    //
    // Но предел считается ПО-РАЗНОМУ в двух режимах, и это принципиально:
    // - при autoExpandGraphRange границы едут за пульсацией, поэтому предел —
    //   удвоенное расстояние от несущей до ближайшей ФИЗИЧЕСКОЙ границы;
    // - при заданных границах несущая сама отодвигается от границы под
    //   пульсацию (см. FrequencyMath.fitBeatWithCarrierShift), поэтому предел
    //   от несущей больше не зависит — это ПОТОЛОК, ширина диапазона: выше
    //   него разнос каналов не влезет ни при какой несущей.

    // Пол 1 Гц — чтобы точка, у которой предел равен нулю, оставалась
    // редактируемой (например, несущая стоит ровно на границе диапазона).
    val maxBeatMagnitudeForValidation = (
        if (autoExpandGraphRange) {
            // От текущего значения несущей: из текстового поля (для валидации
            // по мере ввода) или из значения точки (при потере фокуса).
            val carrierForLimit = if (carrierValue != null && isCarrierValid) carrierValue
                                  else point.carrierFrequency
            FrequencyMath.maxBeatMagnitude(carrierForLimit, null)
        } else {
            FrequencyMath.maxFittableBeatMagnitude(carrierRange)
        }
    ).coerceAtLeast(1.0f)

    // Валидация частоты биений: проверяем МОДУЛЬ (знак разрешён всегда).
    val isBeatValid = beatValue != null &&
        beatValue >= -maxBeatMagnitudeForValidation &&
        beatValue <= maxBeatMagnitudeForValidation

    // Управление фокусом и клавиатурой (нужны свайпу, чтобы прятать их во время
    // протяжки — см. ValueSwipeBox). Обычный тап поле обрабатывает само.
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // Пикселей на одно деление свайпа.
    val valueStepPx = with(LocalDensity.current) { VALUE_SWIPE_STEP.toPx() }

    // Локализованные строки
    val deleteLabel = stringResource(R.string.delete)
    val carrierLabel = stringResource(R.string.carrier)
    val beatsLabel = stringResource(R.string.beats)
    val hzLabel = stringResource(R.string.hz)

    // Состояние для редактирования времени
    var tempHours by remember(point.time.hour) {
        mutableStateOf(TextFieldValue(point.time.hour.toString().padStart(2, '0')))
    }
    var tempMinutes by remember(point.time.minute) {
        mutableStateOf(TextFieldValue(point.time.minute.toString().padStart(2, '0')))
    }
    var hoursWasFocused by remember { mutableStateOf(false) }
    var minutesWasFocused by remember { mutableStateOf(false) }

    // Текущее время «как видит его пользователь»: набранное в полях, а где
    // набрано не число — фактическое значение точки. Отсюда же отсчитываются
    // деления свайпа, поэтому и сохранение по потере фокуса, и свайп всегда
    // спорят об одном и том же значении.
    fun currentHours(): Int = tempHours.text.toIntOrNull()?.coerceIn(0, 23) ?: point.time.hour
    fun currentMinutes(): Int = tempMinutes.text.toIntOrNull()?.coerceIn(0, 59) ?: point.time.minute

    fun validateAndSaveTime() {
        onTimeChange(LocalTime(currentHours(), currentMinutes()))
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
            hours = tempHours.text,
            minutes = tempMinutes.text,
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
                shadowElevation = POINT_POPUP_ELEVATION,
                tonalElevation = POINT_POPUP_ELEVATION
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
                        ValueSwipeBox(
                            modifier = Modifier.weight(1f),
                            stepPx = valueStepPx,
                            read = { currentHours().toFloat() },
                            // Деление — 1 час. Сетка целая, но значение могло
                            // быть набрано руками и стоять вне её, поэтому
                            // сдвиг страхуется от «увода назад».
                            shift = { current, delta ->
                                stepWithinGrid((round(current) + delta).coerceIn(0f, 23f), current, delta)
                            },
                            // Пока палец идёт, меняется только текст в поле;
                            // к кривой значение уйдёт по отпускании.
                            preview = { hours ->
                                val text = hours.roundToInt().toString().padStart(2, '0')
                                tempHours = TextFieldValue(text, selection = TextRange(text.length))
                            },
                            commit = { hours ->
                                onTimeChange(LocalTime(hours.roundToInt(), currentMinutes()))
                            },
                        ) {
                            TimeField(
                                modifier = Modifier.fillMaxWidth(),
                                value = tempHours,
                                onValueChange = { input ->
                                    val digits = input.text.filter { it.isDigit() }.take(2)
                                    // Текст не изменился (движение каретки, выделение) —
                                    // сохраняем TextFieldValue целиком: пересборка с
                                    // кареткой в конце прижимала курсор к правому краю,
                                    // и сдвинуть его было невозможно.
                                    tempHours = if (digits == input.text) {
                                        input
                                    } else {
                                        TextFieldValue(digits, selection = TextRange(digits.length))
                                    }
                                    if (digits.length == 2) validateAndSaveTime()
                                },
                                wasFocused = hoursWasFocused,
                                onFocusChanged = { hoursWasFocused = it },
                                onCommit = {
                                    validateAndSaveTime()
                                    tempHours = TextFieldValue(point.time.hour.toString().padStart(2, '0'))
                                }
                            )
                        }
                        Text(
                            ":",
                            style = MaterialTheme.typography.titleSmall,
                            color = onSurfaceColor
                        )
                        ValueSwipeBox(
                            modifier = Modifier.weight(1f),
                            stepPx = valueStepPx,
                            read = { currentMinutes().toFloat() },
                            // Деление — 5 минут, сетка кратна пяти и идёт СКВОЗЬ
                            // сутки: докручивание за 55 перекатывает час вперёд
                            // (минуты в 00), ниже нуля — назад (минуты в 55).
                            // Внутри часа верхний узел — 55: 59 минут сетке
                            // недоступны, как и при перетаскивании по графику.
                            // Сетка считается от ПОЛНЫХ минут суток, поэтому
                            // переход через границу часа (и границу суток —
                            // 00:00 / 23:55, дальше деление ничего не сдвигает)
                            // работает сам собой. Перекат часа пишется прямо
                            // в поле часов — как это делает preview, — чтобы
                            // часы тикали вместе с минутами, а commit собрал
                            // итог по currentHours().
                            shift = { current, delta ->
                                val hour = currentHours()
                                val dayMinutes = 23 * 60 + 55
                                val total = hour * 60 + round(current).toInt()
                                val stepped = stepWithinGrid(
                                    ((round(total / 5f).toInt() + delta) * 5f)
                                        .coerceIn(0f, dayMinutes.toFloat()),
                                    total.toFloat(),
                                    delta
                                ).toInt()
                                val newHour = stepped / 60
                                if (newHour != hour) {
                                    val text = newHour.toString().padStart(2, '0')
                                    tempHours = TextFieldValue(text, selection = TextRange(text.length))
                                }
                                (stepped % 60).toFloat()
                            },
                            // Пока палец идёт, меняется только текст в поле;
                            // к кривой значение уйдёт по отпускании.
                            preview = { minutes ->
                                val text = minutes.roundToInt().toString().padStart(2, '0')
                                tempMinutes = TextFieldValue(text, selection = TextRange(text.length))
                            },
                            commit = { minutes ->
                                onTimeChange(LocalTime(currentHours(), minutes.roundToInt()))
                            },
                        ) {
                            TimeField(
                                modifier = Modifier.fillMaxWidth(),
                                value = tempMinutes,
                                onValueChange = { input ->
                                    val digits = input.text.filter { it.isDigit() }.take(2)
                                    // Сохраняем ввод как есть при неизменном тексте —
                                    // иначе каретка прижималась к концу (см. поле часов).
                                    tempMinutes = if (digits == input.text) {
                                        input
                                    } else {
                                        TextFieldValue(digits, selection = TextRange(digits.length))
                                    }
                                    if (digits.length == 2) validateAndSaveTime()
                                },
                                wasFocused = minutesWasFocused,
                                onFocusChanged = { minutesWasFocused = it },
                                onCommit = {
                                    validateAndSaveTime()
                                    tempMinutes = TextFieldValue(point.time.minute.toString().padStart(2, '0'))
                                }
                            )
                        }
                        // Не IconButton: тот тянет за собой минимальный тач-таргет
                        // 48 dp и отбирает ширину у полей часов и минут.
                        // Кнопка неактивна, когда осталась последняя точка
                        // (canRemove == false): не кликабельна и приглушена.
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .clickable(enabled = canRemove) { onRemove() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = deleteLabel,
                                tint = if (canRemove) errorColor
                                else errorColor.copy(alpha = 0.38f),
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
                        ValueSwipeBox(
                            modifier = Modifier.weight(1f),
                            stepPx = valueStepPx,
                            read = { parseFrequency(tempCarrierFrequency.text) ?: point.carrierFrequency },
                            // Шаг и сетка — те же, что при перетаскивании точки
                            // по графику: 1% разницы границ, округлённый вверх.
                            // Иначе два жеста давали бы разные значения на
                            // одной и той же высоте.
                            shift = { current, delta ->
                                stepWithinGrid(
                                    quantizeCarrier(
                                        current + delta * carrierStep(carrierRange),
                                        carrierRange
                                    ),
                                    current,
                                    delta
                                )
                            },
                            // Пока палец идёт, меняется только текст в поле:
                            // перестройка кривой — одна, по отпускании.
                            preview = { tempCarrierFrequency = TextFieldValue(formatFrequency(it)) },
                            commit = { onCarrierFrequencyChange(it) },
                        ) {
                            FrequencyField(
                                modifier = Modifier.fillMaxWidth(),
                                value = tempCarrierFrequency,
                                isValid = isCarrierValid,
                                onValueChange = { newValue ->
                                    val limited = limitFrequencyInput(newValue.text)
                                    tempCarrierFrequency = if (limited != newValue.text) {
                                        // Текст переписан фильтром (второй
                                        // разделитель, срез ведущих нулей, обрезка
                                        // длины): каретку
                                        // переносим из СТАРОЙ позиции, зажатой в новый
                                        // текст, а не жёстко в конец — иначе каждое
                                        // нормализующее изменение уводило курсор из-под
                                        // пальца и прижимало его к правому краю.
                                        newValue.copy(
                                            text = limited,
                                            selection = TextRange(
                                                newValue.selection.min.coerceAtMost(limited.length),
                                                newValue.selection.max.coerceAtMost(limited.length)
                                            ),
                                            composition = null
                                        )
                                    } else {
                                        // Текст не изменился (движение каретки,
                                        // выделение) — сохраняем всё как есть.
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
                        }
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
                        ValueSwipeBox(
                            modifier = Modifier.weight(1f),
                            stepPx = valueStepPx,
                            read = { parseFrequency(tempBeatFrequency.text) ?: point.beatFrequency },
                            // Деление — 1 Гц. Сетка целая по МОДУЛЮ: знак
                            // задаёт раскладку каналов и свайпом не меняется.
                            // Предел берётся ЦЕЛОЙ частью, иначе последний
                            // узел упёрся бы в дробную границу и значение
                            // залипло бы на ней.
                            shift = { current, delta ->
                                val limit = floor(maxBeatMagnitudeForValidation).coerceAtLeast(1f)
                                stepWithinGrid(
                                    (round(current) + delta).coerceIn(-limit, limit),
                                    current,
                                    delta
                                )
                            },
                            // Пока палец идёт, меняется только текст в поле:
                            // перестройка кривой — одна, по отпускании.
                            preview = { tempBeatFrequency = TextFieldValue(formatFrequency(it)) },
                            commit = { onBeatFrequencyChange(it) },
                        ) {
                            FrequencyField(
                                modifier = Modifier.fillMaxWidth(),
                                value = tempBeatFrequency,
                                isValid = isBeatValid,
                                onValueChange = { newValue ->
                                    // allowNegative=true: частота биений знаковая
                                    val limited = limitFrequencyInput(newValue.text, allowNegative = true)
                                    tempBeatFrequency = if (limited != newValue.text) {
                                        // Каретка из старой позиции, зажатая в новый
                                        // текст (см. поле несущей), — не жёстко в конец.
                                        newValue.copy(
                                            text = limited,
                                            selection = TextRange(
                                                newValue.selection.min.coerceAtMost(limited.length),
                                                newValue.selection.max.coerceAtMost(limited.length)
                                            ),
                                            composition = null
                                        )
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
                        }
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
            .padding(FIELD_INNER_PADDING),
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
    value: TextFieldValue,
    wasFocused: Boolean,
    onValueChange: (TextFieldValue) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier
) {
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
            .padding(FIELD_INNER_PADDING),
        textStyle = MaterialTheme.typography.titleSmall.copy(
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
    )
}
