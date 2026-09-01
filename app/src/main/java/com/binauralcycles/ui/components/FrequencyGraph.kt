package com.binauralcycles.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.binaural.core.audio.model.CardinalTension
import com.binauralcycles.ui.theme.Spacing
import com.binaural.core.audio.model.FrequencyMath
import com.binaural.core.audio.model.FrequencyPoint
import com.binaural.core.audio.model.FrequencyRange
import com.binaural.core.audio.model.Interpolation
import com.binaural.core.audio.model.InterpolationType
import com.binaural.core.audio.model.RelaxationMode
import com.binaural.core.audio.model.RelaxationModeSettings
import com.binauralcycles.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.roundToInt

// Порог для определения направления перетаскивания
private const val DRAG_DIRECTION_THRESHOLD = 10f

// Минимальная слышимая частота
private const val MIN_AUDIBLE_FREQUENCY = 20.0f

// Максимальная частота для графика
private const val MAX_FREQUENCY = 2000.0f

// Шаг, с которым ручки диалога двигают границы диапазона несущей (Гц).
private const val CARRIER_RANGE_STEP = 10.0f

// Минимальный зазор между нижней и верхней границей диапазона (Гц). Меньше
// его ручки не свести: диапазон из двух почти слипшихся границ всё равно
// нечитаем — на нём вся кривая превращается в горизонтальную полосу.
private const val CARRIER_RANGE_MIN_SPAN = 100.0f

// Пределы, внутри которых ходят ручки диалога. Зазор [CARRIER_RANGE_MIN_SPAN]
// заведомо меньше всей шкалы (2000 - 20), поэтому обеим ручкам всегда есть
// куда отодвинуться, и «упирание» одной в другую не заклинивает жест.
private val CARRIER_RANGE_BOUNDS = MIN_AUDIBLE_FREQUENCY..MAX_FREQUENCY

// Число промежуточных делений RangeSlider: всего узлов на шкале
// (2000 - 20) / 10 + 1 = 199, а параметр steps не считает два конца.
private val CARRIER_RANGE_STEPS =
    ((MAX_FREQUENCY - MIN_AUDIBLE_FREQUENCY) / CARRIER_RANGE_STEP).roundToInt() - 1

// Порог «ручка сдвинулась». Со слайдера значения приходят уже с шагом 10 Гц,
// поэтому настоящее движение заметно больше, а пол-герца отсекает дребезг
// округления и повторные вызовы с неизменившимся значением.
private const val CARRIER_RANGE_EPSILON = 0.5f

// Диаметр маркера точки на графике. Вынесен в константу, чтобы размер в
// расчёте разъезда меток оси Y совпадал с размером нарисованного круга —
// иначе метка уезжала бы от воображаемой точки, а не от настоящей.
private val POINT_MARKER_SIZE = 24.dp
private val POINT_MARKER_SELECTED_SIZE = 30.dp

// Вынос метки диапазона за область графика: метка стоит у ПРАВОГО края
// и торчит наружу на эту величину.
private val Y_AXIS_LABEL_OUTSET = 8.dp

// Анимация контекстного окна точки. Появление — слайд СНИЗУ ВВЕРХ,
// скрытие — слайд СВЕРХУ ВНИЗ (окно всегда стоит под точкой, поэтому
// уезжает туда, откуда приехало).
private const val POPUP_ENTER_MS = 220
private const val POPUP_EXIT_MS = 160

// Переезд окна от одной выбранной точки к другой. Чуть медленнее
// появления: на большом расстоянии короткий тween читается как рывок.
private const val POPUP_MOVE_MS = 260

/** На сколько пикселей окно сдвинуто вниз в начале появления и в конце скрытия. */
private val POPUP_SLIDE_DISTANCE = 24.dp

/**
 * Привязка контекстного окна к точке графика: индекс точки и её положение
 * в пикселях области графика.
 *
 * Индекс нужен внутри, потому что пока окно доигрывает анимацию скрытия,
 * выделение уже снято (`selectedPointIndex == null`), а колбэки окна всё
 * ещё должны относиться к своей точке.
 */
private data class PopupAnchor(val index: Int, val x: Float, val y: Float)

/**
 * Направление перетаскивания
 */
enum class DragDirection {
    NONE, HORIZONTAL, VERTICAL
}

/**
 * Состояние перетаскивания точки
 *
 * Частоты биений здесь НАМЕРЕННО нет. Раньше жест сам обрезал beat под
 * новую несущую и отправлял результат отдельным колбэком — получалось две
 * правки подряд (несущая, затем биения), причём вторая ЗАТИРАЛА то, что
 * пользователь задавал раньше: обрезанное у границы значение попадало в
 * модель как «новое», и при отодвигании точки восстанавливать было нечего.
 *
 * Теперь жест сообщает только время и несущую, а частота биений целиком
 * выводится в ViewModel из желаемого значения ([PointIntentMemory]).
 */
private data class PointDragState(
    val direction: DragDirection = DragDirection.NONE,
    val startIndex: Int = -1,
    val startTime: LocalTime? = null,
    val startCarrier: Float = 0.0f,
    val currentTime: LocalTime? = null,
    val currentCarrier: Float = 0.0f
)

/**
 * Класс для хранения параметров графика
 */
private data class GraphParams(
    val widthPx: Int,
    val heightPx: Int,
    val carrierRange: FrequencyRange,
    val beatRange: FrequencyRange
) {
    val carrierRangeSize: Float get() = (carrierRange.max - carrierRange.min).coerceAtLeast(50.0f)

    /**
     * Максимальный МОДУЛЬ частоты биений на графике.
     *
     * Диапазон биений может быть несимметричным (например, у старых пресeтов
     * (0; 1000), у новых — (-1000; 1000)), поэтому в качестве масштаба
     * берётся наибольший модуль границы: это делает размер маркера точки
     * одинаковым для +beat и −beat. Пол ещё отсекает деление на ноль.
     */
    val maxBeat: Float get() = maxOf(beatRange.max, -beatRange.min).coerceAtLeast(1.0f)
    
    fun timeToX(time: LocalTime): Float {
        val seconds = time.toSecondOfDay()
        return (seconds / (24.0 * 3600) * widthPx).toFloat()
    }
    
    fun carrierToY(carrier: Float): Float {
        return heightPx - ((carrier - carrierRange.min) / carrierRangeSize * heightPx)
    }
    
    fun xToTime(x: Float): LocalTime {
        val seconds = (x / widthPx * 24 * 3600).toInt().coerceIn(0, 86399)
        return LocalTime.fromSecondOfDay(seconds)
    }
    
    fun yToCarrier(y: Float): Float {
        val carrier = carrierRange.min + (1.0f - y / heightPx) * carrierRangeSize
        return carrierRange.clamp(kotlin.math.round(carrier))
    }
    
    /**
     * Вычисляет Y-координату «верхней» границы области биений.
     * Граница соответствует ПРАВОМУ каналу: carrier + beat/2.
     *
     * При ОТРИЦАТЕЛЬНОЙ частоте биений правый канал звучит НИЖЕ левого, и
     * эта координата окажется ниже [beatLowerY]. Вызывающий код рисует
     * полосу между ними, поэтому порядок не важен — важно, что обе границы
     * считаются по одной и той же формуле каналов.
     */
    fun beatUpperY(carrier: Float, beat: Float): Float {
        val upperFrequency = FrequencyMath.rightChannelFrequency(carrier, beat)
        return carrierToY(upperFrequency)
    }

    /**
     * Вычисляет Y-координату «нижней» границы области биений.
     * Граница соответствует ЛЕВОМУ каналу: carrier − beat/2 (см. [beatUpperY]).
     */
    fun beatLowerY(carrier: Float, beat: Float): Float {
        val lowerFrequency = FrequencyMath.leftChannelFrequency(carrier, beat)
        return carrierToY(lowerFrequency)
    }
}

/**
 * Генерирует виртуальные точки режима расслабления.
 * Единая реализация в core-модели (RelaxationModeSettings.generateVirtualPoints).
 *
 * [carrierRange] обязателен: его минимум ограничивает частоту КАНАЛА
 * виртуальных точек, поэтому диапазон должен приходить из редактируемой кривой.
 */
fun generateRelaxationVirtualPoints(
    points: List<FrequencyPoint>,
    relaxationModeSettings: RelaxationModeSettings,
    interpolationType: InterpolationType = InterpolationType.LINEAR,
    splineTension: Float = 0.0f,
    carrierRange: FrequencyRange = FrequencyRange.DEFAULT_CARRIER
): List<FrequencyPoint> {
    return relaxationModeSettings.generateVirtualPoints(points, interpolationType, splineTension, carrierRange)
}

@Composable
fun FrequencyGraph(
    points: List<FrequencyPoint>,
    selectedPointIndex: Int?,
    currentCarrierFrequency: Float,
    currentBeatFrequency: Float,
    carrierRange: FrequencyRange,
    beatRange: FrequencyRange,
    interpolationType: InterpolationType = InterpolationType.LINEAR,
    splineTension: Float = 0.0f,
    isPlaying: Boolean,
    relaxationModeSettings: RelaxationModeSettings = RelaxationModeSettings(),
    onPointSelected: (Int) -> Unit,
    onPointTimeChanged: (Int, LocalTime) -> Unit,
    onPointCarrierChanged: (Int, Float) -> Unit,
    onPointBeatChanged: (Int, Float) -> Unit = { _, _ -> },
    onAddPoint: (LocalTime, Float, Float) -> Unit,
    onCarrierRangeChange: (Float, Float) -> Unit,
    // Контекстное окно точки: показывается слоем поверх графика, поэтому
    // графу нужны те же входы, что были у отдельного раздела редактора.
    autoExpandGraphRange: Boolean = false,
    onRemovePoint: (Int) -> Unit = {},
    // Касание вне контекстного окна точки закрывает его.
    onDismissPopup: () -> Unit = {},
    // НОВОЕ: внешнее время (например, виртуальное из uiState). null => свои часы.
    externalCurrentTime: LocalTime? = null,
    modifier: Modifier = Modifier
) {
    // УДАЛЕНО: `val sortedPoints = points.sortedBy { ... }` вычислялось на каждой
    // рекомпозиции (3-4 раза в секунду при воспроизведении) и нигде не
    // использовалось — в drawBehind передаётся allPoints. Отсортированный список
    // берётся из remember ниже (displayPoints).
    var dragState by remember { mutableStateOf(PointDragState()) }
    // Диалог единой метки диапазона: обе границы правятся в нём одними ручками.
    var showRangeDialog by remember { mutableStateOf(false) }

    // Локализованный формат Гц - объявляем здесь для использования во всём компоненте
    val hzFormat = stringResource(R.string.hz_value_format)
    // Единая метка диапазона оси Y: «20-2000 Гц».
    val rangeFormat = stringResource(R.string.carrier_range_format)

    // Единое время приходит из PlaybackTelemetry (StateFlow сервиса);
    // приватный тикер удалён - график живёт тем же потоком данных, что и карточки
    val currentLocalTime = externalCurrentTime
        ?: Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).time
    val density = LocalDensity.current

    // Используем кэшированные sortedPoints если доступны (оптимизация)
    val displayPoints = remember(points) { points.sortedBy { it.time.toSecondOfDay() } }
    
    // Генерируем виртуальные точки режима расслабления с учётом типа интерполяции.
    // ВАЖНО: carrierRange в ключе — его минимум задаёт пол частоты канала
    // виртуальных точек, поэтому смена минимума частот обязана пересчитывать
    // точки (иначе график показывает устаревшие значения до следующей правки).
    val virtualPoints = remember(
        points, relaxationModeSettings, interpolationType, splineTension,
        carrierRange.min, carrierRange.max
    ) {
        generateRelaxationVirtualPoints(
            points, relaxationModeSettings, interpolationType, splineTension, carrierRange
        )
    }

    // Фон карточки рисуем сами в drawBehind, а не модификатором background,
    // чтобы он гарантированно лёг ДО содержимого (контекстное окно точки
    // выступает за нижний край графика и не должно перекрываться фоном).
    // Внешнюю рамку намеренно не рисуем — на экране редактирования пресета
    // график показывается без границы.
    val cardSurfaceColor = MaterialTheme.colorScheme.surface

    Column(
        // zIndex: окно точки может выступать за нижний край графика, и тогда
        // его перекрыли бы следующие за графиком карточки, которые рисуются
        // позже. Поднимаем весь граф выше соседей по экрану редактора.
        modifier = modifier
            .zIndex(1f)
            .fillMaxWidth()
            .drawBehind {
                val corner = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                drawRoundRect(color = cardSurfaceColor, cornerRadius = corner)
            }
            .padding(16.dp)
    ) {
        BoxWithConstraints(
            // zIndex: контекстное окно точки — последний ребёнок этого Box и
            // может выступать за нижний край, поэтому весь Box рисуется выше
            // оси X, объявленной следом в колонке.
            modifier = Modifier.weight(1f).fillMaxWidth().zIndex(1f)
        ) {
            val widthPx = with(density) { maxWidth.roundToPx() }
            val heightPx = with(density) { maxHeight.roundToPx() }
            val graphParams = remember(widthPx, heightPx, carrierRange.min, carrierRange.max, beatRange.min, beatRange.max) {
                GraphParams(widthPx, heightPx, carrierRange, beatRange)
            }

            val primaryColor = MaterialTheme.colorScheme.primary
            val errorColor = MaterialTheme.colorScheme.error

            // Границы контекстного окна точки в координатах области графика.
            // Нужны, чтобы касание по самому окну его не закрывало.
            var popupRect by remember { mutableStateOf<Rect?>(null) }

            // Объединяем реальные и виртуальные точки для отрисовки
            // В ADVANCED и SMOOTH режимах используем только виртуальные точки (кривая проходит только через них)
            val allPoints = remember(displayPoints, virtualPoints, relaxationModeSettings) {
                when {
                    relaxationModeSettings.enabled && virtualPoints.isNotEmpty() -> {
                        virtualPoints  // Только виртуальные точки для ADVANCED и SMOOTH режимов
                    }
                    else -> displayPoints
                }
            }
            
            // Веса касательных кардинального сплайна (регуляция overshoot).
            //
            // Считаются ОДИН РАЗ на смену кривой/параметров, а не на сэмпл:
            // внутри buildBeatPaths интерполяция вызывается ~1000 раз, и
            // пересчёт весов на каждом вызове дал бы O(n²) на кадр.
            //
            // ДВА набора, потому что кривых тоже две: allPoints — та, что
            // РИСУЕТСЯ (в режиме расслабления — виртуальные точки),
            // displayPoints — базовая, по ней идёт пунктирная линия.
            val beatWeights = remember(
                allPoints, interpolationType, splineTension, carrierRange
            ) {
                CardinalTension.forPoints(
                    allPoints, interpolationType, splineTension, carrierRange, presorted = true
                )
            }
            val baseWeights = remember(
                displayPoints, interpolationType, splineTension, carrierRange
            ) {
                CardinalTension.forPoints(
                    displayPoints, interpolationType, splineTension, carrierRange, presorted = true
                )
            }

            // Статичная геометрия графика (сетка + кривые) строится ТОЛЬКО при
            // изменении точек/параметров. Тиканье телеметрии (время, частоты)
            // перерисовывает лишь указатель — интерполяция кривых не запускается.
            val staticPaths = remember(
                allPoints,
                displayPoints,
                graphParams,
                interpolationType,
                splineTension,
                relaxationModeSettings
            ) {
                buildGraphStaticPaths(
                    sortedPoints = allPoints,
                    realPoints = displayPoints,
                    params = graphParams,
                    interpolationType = interpolationType,
                    splineTension = splineTension,
                    relaxationModeSettings = relaxationModeSettings,
                    beatWeights = beatWeights,
                    baseWeights = baseWeights
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawGrid(primaryColor)
                        drawGraphPaths(staticPaths, primaryColor)
                    }
                    // Отдельный слой под динамичный указатель: он единственный
                    // зависит от времени и частот, и он же самый дешёвый.
                    .drawBehind {
                        if (isPlaying) {
                            drawCurrentTimeIndicator(
                                graphParams = graphParams,
                                currentLocalTime = currentLocalTime,
                                currentCarrierFrequency = currentCarrierFrequency,
                                currentBeatFrequency = currentBeatFrequency,
                                indicatorColor = errorColor
                            )
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { offset ->
                                // Добавляем точку при двойном нажатии
                                // Снап к той же сетке 5 минут, что и при drag точки
                                val stepSeconds = TIME_STEP_MINUTES * 60
                                val snappedSeconds = (graphParams.xToTime(offset.x).toSecondOfDay() / stepSeconds) * stepSeconds
                                val time = LocalTime.fromSecondOfDay(snappedSeconds)
                                val carrier = graphParams.yToCarrier(offset.y)
                                // Оценка частоты биений по канальным кривым (как в движке):
                                // beat = right − left — величина ЗНАКОВАЯ.
                                //
                                // Значение передаётся НЕОБРЕЗАННЫМ: обрезку под
                                // геометрию и границы графика делает
                                // addEditingPoint — там же единственная точка
                                // истины по клампу. А необрезанное значение
                                // уходит в память желаемых биений, поэтому
                                // точка, появившаяся у самой границы, наберёт
                                // свою пульсацию, когда её отодвинут от края.
                                val interpolatedBeat = if (displayPoints.size >= 2) {
                                    val (leftFreq, rightFreq) = Interpolation.interpolateChannels(
                                        displayPoints, time, interpolationType, splineTension,
                                        presorted = true, weights = baseWeights
                                    )
                                    kotlin.math.round(rightFreq - leftFreq)
                                } else {
                                    // 0 или 1 точка: берём частоту биений единственной точки
                                    displayPoints.firstOrNull()?.beatFrequency ?: 0.0f
                                }
                                onAddPoint(time, carrier, interpolatedBeat)
                            },
                            onTap = { offset ->
                                // Касание по графику, но мимо контекстного
                                // окна точки, закрывает это окно. По самому
                                // окну касание съедает его собственный
                                // clickable, сюда оно не доходит.
                                val rect = popupRect
                                if (rect != null && !rect.contains(offset)) {
                                    onDismissPopup()
                                }
                            }
                        )
                    }
                    // Закрываем окно по ЛЮБОМУ касанию мимо него, а не только
                    // по «чистому» тапу: свайп и прокрутка тоже считаются.
                    // Смотрим на DOWN в проходе Final — к этому моменту все
                    // потомки (маркеры точек, само окно) уже успели съесть
                    // событие, если касались их. Сами ничего не съедаем,
                    // поэтому жест продолжает работать как раньше — в отличие
                    // от detectDragGestures, который отобрал бы прокрутку
                    // у внешнего verticalScroll.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                val down = event.changes.firstOrNull { it.changedToDown() }
                                    ?: continue
                                if (down.isConsumed) continue
                                val rect = popupRect
                                if (rect != null && !rect.contains(down.position)) {
                                    onDismissPopup()
                                }
                            }
                        }
                    }
            ) {
                displayPoints.forEachIndexed { sortedIndex, point ->
                    val originalIndex = points.indexOf(point)
                    val isSelected = selectedPointIndex == originalIndex
                    
                    val prevPoint = displayPoints.getOrNull(sortedIndex - 1)
                    val nextPoint = displayPoints.getOrNull(sortedIndex + 1)
                    
                    // Минимум: соседняя точка + 5 минут (шаг перемещения)
                    val minTimeSeconds = prevPoint?.time?.toSecondOfDay()?.plus(TIME_STEP_MINUTES * 60) ?: 0
                    // Максимум: соседняя точка - 5 минут, или 23:55 (последнее значение с шагом 5 минут)
                    val maxTimeSeconds = nextPoint?.time?.toSecondOfDay()?.minus(TIME_STEP_MINUTES * 60) 
                        ?: (23 * 3600 + 55 * 60) // 23:55
                    
                    val displayTime = if (dragState.startIndex == originalIndex && dragState.currentTime != null) {
                        dragState.currentTime!!
                    } else point.time
                    
                    val displayCarrier = if (dragState.startIndex == originalIndex &&
                        (dragState.direction == DragDirection.VERTICAL || dragState.direction == DragDirection.NONE)) {
                        dragState.currentCarrier
                    } else point.carrierFrequency
                    
                    val xPx = graphParams.timeToX(displayTime)
                    val yPx = graphParams.carrierToY(displayCarrier)
                    
                    DraggablePoint(
                        xPx = xPx,
                        yPx = yPx,
                        isSelected = isSelected,
                        point = point,
                        maxBeat = graphParams.maxBeat,
                        originalIndex = originalIndex,
                        carrierRange = carrierRange,
                        minTimeSeconds = minTimeSeconds,
                        maxTimeSeconds = maxTimeSeconds,
                        graphWidthPx = widthPx,
                        graphHeightPx = heightPx,
                        primaryColor = primaryColor,
                        onPointSelected = onPointSelected,
                        onDragStart = { index, time, carrier ->
                            dragState = PointDragState(
                                direction = DragDirection.NONE,
                                startIndex = index,
                                startTime = time,
                                startCarrier = carrier,
                                currentTime = time,
                                currentCarrier = carrier
                            )
                        },
                        onDragUpdate = { _index, newTime, newCarrier, direction ->
                            dragState = dragState.copy(
                                direction = direction,
                                currentTime = newTime,
                                currentCarrier = newCarrier
                            )
                        },
                        onDragEnd = { index, newTime, newCarrier, direction ->
                            // Частота биений здесь НЕ передаётся: её выводит
                            // ViewModel из желаемого значения точки. Отдельный
                            // колбэк записал бы в неё обрезанное у границы
                            // значение и уничтожил бы то, что надо восстановить.
                            if (direction == DragDirection.HORIZONTAL) {
                                onPointTimeChanged(index, newTime)
                            } else if (direction == DragDirection.VERTICAL) {
                                onPointCarrierChanged(index, newCarrier)
                            }
                            dragState = PointDragState()
                        }
                    )
                }
                
                // Виртуальные точки режима расслабления скрыты - кривая проходит через них
                // В SMOOTH и ADVANCED режимах виртуальные точки не отображаются отдельно
                
                if (dragState.startIndex >= 0 && dragState.currentTime != null && dragState.direction != DragDirection.NONE) {
                    val previewXPx = graphParams.timeToX(dragState.currentTime!!)
                    val previewYPx = graphParams.carrierToY(dragState.currentCarrier)
                    
                    Box(modifier = Modifier.offset { IntOffset(previewXPx.toInt() - 50, previewYPx.toInt() - 160) }) {
                        Surface(color = MaterialTheme.colorScheme.inverseSurface, shape = RoundedCornerShape(8.dp)) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = when (dragState.direction) {
                                        DragDirection.HORIZONTAL -> "%02d:%02d".format(dragState.currentTime!!.hour, dragState.currentTime!!.minute)
                                        DragDirection.VERTICAL -> hzFormat.format(dragState.currentCarrier)
                                        DragDirection.NONE -> ""
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.inverseOnSurface
                                )
                            }
                        }
                    }
                }

            }

            // Ось Y: одна метка на весь диапазон вместо двух отдельных
            // меток границ. Стоит в ПРАВОМ ВЕРХНЕМ углу графика — фиксировано,
            // без разъезда с маркерами точек, чтобы её положение не «прыгало»
            // при добавлении/выделении точек. Нажатие открывает диалог, где
            // обе границы правятся двумя ручками.
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = primaryColor.copy(alpha = 0.1f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = Y_AXIS_LABEL_OUTSET)
                    .clickable { showRangeDialog = true }
            ) {
                Text(
                    rangeFormat.format(carrierRange.min, carrierRange.max),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = primaryColor,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }

            // Контекстное окно редактирования точки — всплывающий слой
            // поверх графика, а не отдельный раздел в списке опций. Окно
            // ВСЕГДА ставится ПОД маркером точки: уголок на его верхней
            // стороне смотрит вверх, то есть подходит к точке СНИЗУ.
            // Индекс в points (как его хранит экран) -> индекс в
            // отсортированном displayPoints, по которому считаны xPx/yPx.
            val selectedDisplayIndex = if (selectedPointIndex != null) {
                displayPoints.indexOfFirst { points.indexOf(it) == selectedPointIndex }
            } else {
                -1
            }
            val popupVisible = selectedPointIndex != null && selectedDisplayIndex >= 0

            // Точка и её привязка, переживающие снятие выделения: выделение
            // снимается мгновенно, а окно ещё [POPUP_EXIT_MS] миллисекунд
            // уезжает вниз — и ему нужны и содержимое, и позиция. Обычный
            // remember с ключом здесь не годится: при null он бы вернул null,
            // и уезжающее окно осталось бы пустым.
            val popupPoint = rememberLastNotNull(
                if (popupVisible) displayPoints[selectedDisplayIndex] else null
            )
            val popupAnchorNow: PopupAnchor? = run {
                val index = selectedPointIndex ?: return@run null
                if (!popupVisible) return@run null
                val point = displayPoints[selectedDisplayIndex]
                PopupAnchor(
                    index = index,
                    x = graphParams.timeToX(point.time),
                    y = graphParams.carrierToY(point.carrierFrequency)
                )
            }
            val popupAnchor = rememberLastNotNull(popupAnchorNow)

            // Жизнь окна: в дереве оно остаётся и после снятия выделения —
            // пока не доиграет анимацию скрытия. Иначе уезжающее окно
            // исчезало бы рывком, не доехав до конца.
            var popupAlive by remember { mutableStateOf(false) }
            // Окно уже поставлено под точку — дальше смена точки это ПЕРЕЕЗД,
            // а не новая постановка. Сбрасывается, когда окно убрано из
            // дерева: иначе следующее окно приезжало бы издалека вместо того,
            // чтобы сразу встать под свою точку.
            var popupPlaced by remember { mutableStateOf(false) }

            val popupProgress = remember { Animatable(0f) }
            val popupAnchorX = remember { Animatable(0f) }
            val popupAnchorY = remember { Animatable(0f) }

            // Появление и скрытие. Ключ — САМ ФАКТ выбора точки, а не её
            // индекс: при переходе на другую точку окно не переоткрывается,
            // а просто едет к новому месту.
            LaunchedEffect(popupVisible) {
                if (popupVisible) {
                    popupAlive = true
                    popupProgress.animateTo(
                        1f, tween(POPUP_ENTER_MS, easing = FastOutSlowInEasing)
                    )
                } else {
                    popupProgress.animateTo(
                        0f, tween(POPUP_EXIT_MS, easing = FastOutLinearInEasing)
                    )
                    popupAlive = false
                    popupPlaced = false
                }
            }

            // Переезд к точке. К первой точке окно встаёт мгновенно (иначе
            // приезжало бы из левого верхнего угла области графика), между
            // выбранными точками — плавно.
            LaunchedEffect(popupAnchor, popupVisible) {
                val anchor = popupAnchor ?: return@LaunchedEffect
                if (!popupVisible) return@LaunchedEffect
                if (!popupPlaced) {
                    popupAnchorX.snapTo(anchor.x)
                    popupAnchorY.snapTo(anchor.y)
                    popupPlaced = true
                } else {
                    // По обеим осям одновременно и одним спеком: иначе окно
                    // шло бы зигзагом, доезжая по X и по Y в разное время.
                    coroutineScope {
                        launch {
                            popupAnchorX.animateTo(
                                anchor.x, tween(POPUP_MOVE_MS, easing = FastOutSlowInEasing)
                            )
                        }
                        launch {
                            popupAnchorY.animateTo(
                                anchor.y, tween(POPUP_MOVE_MS, easing = FastOutSlowInEasing)
                            )
                        }
                    }
                }
            }

            // Размер окна известен только после первой раскладки.
            var popupSize by remember { mutableStateOf(IntSize.Zero) }
            val gapPx = with(density) { POINT_POPUP_ANCHOR_GAP.roundToPx() }
            // Вынос за боковые границы области графика: у крайних
            // точек окно иначе упиралось бы в край, и до края экрана
            // оставалось бы 32 dp (16 карточки + 16 экрана).
            val overhangPx = with(density) {
                (POINT_POPUP_OUTER_PADDING - POINT_POPUP_SCREEN_MARGIN).roundToPx()
            }
            val popupSlidePx = with(density) { POPUP_SLIDE_DISTANCE.roundToPx() }.toFloat()

            // Левый край окна по положению точки: центр окна идёт за точкой,
            // а у крайних точек окно выносится за границы области графика.
            // Окно всегда ПОД точкой — без переворота вверх.
            fun popupLeftPx(anchorX: Float, popupWidth: Int): Float {
                val minLeftPx = -overhangPx.toFloat()
                val maxLeftPx = (widthPx - popupWidth).toFloat() + overhangPx
                return (anchorX - popupWidth / 2f)
                    .coerceIn(minLeftPx, maxLeftPx.coerceAtLeast(minLeftPx))
            }

            // Границы окна в координатах области графика: касание по самому
            // окну не должно его закрывать. Считаются по КОНЕЧНОМУ положению
            // окна, а не по анимационному: попасть пальцем в окно посреди
            // двухсотмиллисекундной анимации всё равно не выйдет.
            SideEffect {
                val anchor = popupAnchorNow
                popupRect = if (anchor == null || popupSize == IntSize.Zero) null else {
                    val leftPx = popupLeftPx(anchor.x, popupSize.width)
                    val topPx = anchor.y + gapPx
                    Rect(
                        left = leftPx,
                        top = topPx,
                        right = leftPx + popupSize.width,
                        bottom = topPx + popupSize.height
                    )
                }
            }

            // Окно на экране, пока выбрана точка ИЛИ пока доигрывает
            // анимацию скрытия.
            val shownPoint = popupPoint
            val shownAnchor = popupAnchor
            if ((popupVisible || popupAlive) && shownPoint != null && shownAnchor != null) {
                val anchorIndex = shownAnchor.index
                Box(
                    modifier = Modifier
                        // Положение читается на фазе раскладки, поэтому
                        // переезд не перекомпонуется ни разу.
                        .offset {
                            IntOffset(
                                popupLeftPx(popupAnchorX.value, popupSize.width).roundToInt(),
                                (popupAnchorY.value + gapPx).roundToInt()
                            )
                        }
                        // Слайд: при появлении окно приезжает снизу вверх,
                        // при скрытии уезжает вниз. Слой, а не модификатор
                        // композиции: кадры анимации не трогают композицию.
                        .graphicsLayer {
                            alpha = popupProgress.value
                            translationY = (1f - popupProgress.value) * popupSlidePx
                        }
                        .onSizeChanged { popupSize = it }
                        // Касания по самому окну не должны уходить на график
                        // и закрывать его: съедаем их, без ripple.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { }
                        )
                ) {
                    PointEditorPopup(
                        point = shownPoint,
                        pointIndex = anchorIndex,
                        carrierRange = carrierRange,
                        autoExpandGraphRange = autoExpandGraphRange,
                        // Уголок смотрит на точку, поэтому его смещение внутри
                        // окна считается на фазе раскладки — по тому же
                        // анимационному положению, что и само окно. Значение,
                        // а не состояние: иначе каждый кадр переезда
                        // перекомпоновывал бы всё окно.
                        arrowOffsetX = {
                            val x = popupAnchorX.value
                            x - popupLeftPx(x, popupSize.width)
                        },
                        onCarrierFrequencyChange = { onPointCarrierChanged(anchorIndex, it) },
                        onBeatFrequencyChange = { onPointBeatChanged(anchorIndex, it) },
                        onTimeChange = { onPointTimeChanged(anchorIndex, it) },
                        onRemove = { onRemovePoint(anchorIndex) }
                    )
                }
            }

            // Ось X - отметки каждые 3 часа, внутри графика снизу (не снаружи).
            // Каждая метка — маленький «ярлык» на полупрозрачном фоне карточки,
            // чтобы оставаться читаемой поверх кривой и сетки.
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(bottom = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val hourLabelModifier = Modifier
                    .background(cardSurfaceColor.copy(alpha = 0.75f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 2.dp)
                Text("0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = hourLabelModifier)
                Text("3", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = hourLabelModifier)
                Text("6", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = hourLabelModifier)
                Text("9", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = hourLabelModifier)
                Text("12", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = hourLabelModifier)
                Text("15", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = hourLabelModifier)
                Text("18", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = hourLabelModifier)
                Text("21", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = hourLabelModifier)
                Text("24", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = hourLabelModifier)
            }
        }
        
    }
    
    val rangeTitle = stringResource(R.string.carrier_range_title)
    val closeLabel = stringResource(R.string.close)

    if (showRangeDialog) {
        // Своё значение диапазона, а не carrierRange напрямую: смена границ
        // пересобирает ВСЕ точки кривой и уходит в сервис воспроизведения,
        // поэтому наружу отдаём только по отпусканию ручки, а не на каждом
        // кадре перетаскивания — так же, как DiscreteSlider в настройках.
        var sliderRange by remember(carrierRange.min, carrierRange.max) {
            mutableStateOf(carrierRange.min..carrierRange.max)
        }

        AlertDialog(
            onDismissRequest = { showRangeDialog = false },
            title = { Text(rangeTitle) },
            text = {
                Column {
                    // Живая подпись: пока ручка едет, график за диалогом
                    // замирает, и значение видно только здесь.
                    Text(
                        rangeFormat.format(sliderRange.start, sliderRange.endInclusive),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(Spacing.md))
                    RangeSlider(
                        value = sliderRange,
                        onValueChange = { candidate ->
                            sliderRange = applyRangeGap(candidate, sliderRange)
                        },
                        // Отпустили ручку — теперь диапазон можно отдать
                        // наружу один раз, а не на каждое деление шкалы.
                        onValueChangeFinished = {
                            onCarrierRangeChange(sliderRange.start, sliderRange.endInclusive)
                        },
                        valueRange = CARRIER_RANGE_BOUNDS,
                        steps = CARRIER_RANGE_STEPS,
                        modifier = Modifier.fillMaxWidth()
                    )
                    // Концы шкалы: видно, в каких пределах ходят ручки.
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            hzFormat.format(MIN_AUDIBLE_FREQUENCY),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            hzFormat.format(MAX_FREQUENCY),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            // Значение уже применено по отпусканию ручки, поэтому кнопка не
            // «применяет», а закрывает — отмены здесь быть не может.
            confirmButton = {
                TextButton(onClick = { showRangeDialog = false }) { Text(closeLabel) }
            }
        )
    }
}

private typealias DrawScope = androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Последнее НЕНУЛЕВОЕ значение.
 *
 * Обычный `remember(value)` здесь не годится: как только ключ становится
 * null, он возвращает null. Контекстному окну точки это ломает анимацию
 * скрытия — выделение снимается сразу, а окно ещё живёт в дереве и всё это
 * время должно показывать свою точку и стоять на своём месте.
 *
 * Запись в состояние прямо в композиции — тот же приём, что использует
 * сам Compose в `rememberUpdatedState`: значение сходится (равное не
 * инвалидирует чтение), поэтому бесконечной перекомпозиции не возникает.
 */
@Composable
private fun <T : Any> rememberLastNotNull(value: T?): T? {
    val holder = remember { mutableStateOf<T?>(null) }
    if (value != null) holder.value = value
    return holder.value
}

/**
 * Геометрия статичного слоя графика: сетка и кривые зависят ТОЛЬКО от точек
 * пресета и параметров шкалы — но не от текущего времени и частот.
 *
 * Раньше эти пути строились прямо внутри `drawBehind`, а лямбда захватывала
 * `currentLocalTime`/`currentCarrierFrequency`/`currentBeatFrequency`. Из-за
 * этого любое тиканье телеметрии (1–2 раза в секунду) запускало полную
 * перерисовку: ~1000 вызовов интерполяции на каждый кадр. Теперь пути строятся
 * один раз в `remember` и переиспользуются — фаза отрисовки сводится к
 * нескольким `drawPath`, а интерполяция выполняется только при изменении
 * самой кривой.
 */
private data class GraphStaticPaths(
    val beatCombined: Path? = null,
    val beatUpper: Path? = null,
    val beatLower: Path? = null,
    val dashedBase: Path? = null
)

/**
 * Строит статичную геометрию графика. Чистая функция без DrawScope:
 * все размеры берутся из [GraphParams], что позволяет кэшировать результат.
 */
private fun buildGraphStaticPaths(
    sortedPoints: List<FrequencyPoint>,
    realPoints: List<FrequencyPoint>,
    params: GraphParams,
    interpolationType: InterpolationType,
    splineTension: Float,
    relaxationModeSettings: RelaxationModeSettings,
    /** Веса касательных для [sortedPoints] — той кривой, что рисуется. */
    beatWeights: FloatArray? = null,
    /** Веса касательных для [realPoints] — базовой кривой пунктира. */
    baseWeights: FloatArray? = null
): GraphStaticPaths {
    val beatPaths = if (sortedPoints.size >= 2) {
        buildBeatPaths(sortedPoints, params, interpolationType, splineTension, beatWeights)
    } else {
        null
    }

    // В режимах STEP и SMOOTH — пунктирная линия базовой кривой (через основные точки)
    val showDashedBase = relaxationModeSettings.enabled &&
        (relaxationModeSettings.mode == RelaxationMode.STEP ||
            relaxationModeSettings.mode == RelaxationMode.SMOOTH) &&
        relaxationModeSettings.carrierReductionPercent > 0 &&
        realPoints.size >= 2
    val dashedBase = if (showDashedBase) {
        buildDashedBaseCurvePath(realPoints, params, interpolationType, splineTension, baseWeights)
    } else {
        null
    }

    return GraphStaticPaths(
        beatCombined = beatPaths?.combined,
        beatUpper = beatPaths?.upper,
        beatLower = beatPaths?.lower,
        dashedBase = dashedBase
    )
}

/** Сетка: зависит только от размера и цвета, интерполяций не делает. */
private fun DrawScope.drawGrid(primaryColor: Color) {
    val width = size.width
    val height = size.height
    val gridColor = primaryColor.copy(alpha = 0.15f)

    // Горизонтальные линии сетки
    for (i in 0..4) {
        val y = height * i / 4
        drawLine(color = gridColor, start = Offset(0f, y), end = Offset(width, y), strokeWidth = 1f)
    }

    // Вертикальные линии каждые 3 часа (8 линий + границы)
    for (hour in 0..24 step 3) {
        val x = width * hour / 24
        drawLine(color = gridColor, start = Offset(x, 0f), end = Offset(x, height), strokeWidth = 1f)
    }
}

/** Отрисовка уже готовых путей — дешёвая операция, безопасная на каждом кадре. */
private fun DrawScope.drawGraphPaths(paths: GraphStaticPaths, primaryColor: Color) {
    val combined = paths.beatCombined
    if (combined != null) {
        drawPath(path = combined, color = primaryColor.copy(alpha = 0.2f), style = Fill)
    }
    paths.beatUpper?.let {
        drawPath(path = it, color = primaryColor.copy(alpha = 0.4f), style = Stroke(width = 1f))
    }
    paths.beatLower?.let {
        drawPath(path = it, color = primaryColor.copy(alpha = 0.4f), style = Stroke(width = 1f))
    }
    paths.dashedBase?.let { path ->
        // Параметры штриха и толщины сохранены один-в-один с прежней
        // реализацией drawDashedBaseCurve (alpha 0.3, width 2, даш 10/10).
        drawPath(
            path = path,
            color = primaryColor.copy(alpha = 0.3f),
            style = Stroke(
                width = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
            )
        )
    }
}

/**
 * Указатель текущего момента — единственная по-настоящему динамичная часть
 * графика. Рисуется отдельным слоем, чтобы не пересчитывать кривые.
 */
private fun DrawScope.drawCurrentTimeIndicator(
    graphParams: GraphParams,
    currentLocalTime: LocalTime,
    currentCarrierFrequency: Float,
    currentBeatFrequency: Float,
    indicatorColor: Color
) {
    val height = size.height
    val currentX = graphParams.timeToX(currentLocalTime)
    val rightChannelY = graphParams.beatUpperY(currentCarrierFrequency, currentBeatFrequency).coerceIn(0f, height)
    val leftChannelY = graphParams.beatLowerY(currentCarrierFrequency, currentBeatFrequency).coerceIn(0f, height)
    // При ОТРИЦАТЕЛЬНОЙ частоте биений каналы меняются местами, поэтому
    // верх/низ полосы берём по координатам, а не по именам «upper/lower».
    val currentUpperY = minOf(rightChannelY, leftChannelY)
    val currentLowerY = maxOf(rightChannelY, leftChannelY)

    // Вертикальная линия текущего момента: вне области биений — полупрозрачная,
    // внутри области биений — ярче. Точку пересечения с несущей убираем.
    val indicatorAlpha = 0.3f
    drawLine(color = indicatorColor.copy(alpha = indicatorAlpha), start = Offset(currentX, 0f), end = Offset(currentX, currentUpperY), strokeWidth = 2f)
    drawLine(color = indicatorColor.copy(alpha = indicatorAlpha), start = Offset(currentX, currentLowerY), end = Offset(currentX, height), strokeWidth = 2f)
    // Вертикальная линия показывающая диапазон частот каналов
    drawLine(color = indicatorColor.copy(alpha = 0.5f), start = Offset(currentX, currentUpperY), end = Offset(currentX, currentLowerY), strokeWidth = 3f)
}

private data class BeatPaths(
    val upper: Path,
    val lower: Path,
    val combined: Path
)

/**
 * Строит пути области биений. Чистая функция: результат кэшируется в
 * [buildGraphStaticPaths], поэтому ~1000 интерполяций выполняются один раз на
 * изменение кривой, а не на каждый тик времени (1–2 раза в секунду).
 *
 * Ширина берётся из [GraphParams.widthPx] (а не из `DrawScope.size`), чтобы
 * геометрию можно было посчитать вне фазы отрисовки.
 */
private fun buildBeatPaths(
    sortedPoints: List<FrequencyPoint>,
    params: GraphParams,
    interpolationType: InterpolationType,
    splineTension: Float = 0.0f,
    /**
     * Веса касательных кардинального сплайна (см. CardinalTension). ОБЯЗАНЫ
     * приходить снаружи: здесь ~1000 вызовов interpolateChannels, и пересчёт
     * весов на каждом из них — это O(n²) на кадр вместо O(n) на кривую.
     */
    weights: FloatArray? = null
): BeatPaths {
    val width = params.widthPx.toFloat()
    // Динамическое количество сэмплов: минимум 500, для плавных кривых - больше
    val numSamples = (sortedPoints.size * 4).coerceAtLeast(500)

    val upperPath = Path()
    val lowerPath = Path()

    // Начинаем с левой границы
    val startTime = LocalTime.fromSecondOfDay(0)
    // Канальные кривые интерполируются напрямую общей функцией (как в движке)
    //
    // presorted = true: sortedPoints УЖЕ отсортирован вызывающей стороной
    // (displayPoints и virtualPoints кэшируются через remember и сортируются
    // один раз). Раньше interpolateChannels сортировал копию списка на каждом
    // из ~1000 вызовов за кадр — тысячи аллокаций в секунду на главном потоке.
    //
    // ВАЖНО: пара — (ЛЕВЫЙ, ПРАВЫЙ) канал, а не «нижний/верхний». Пути ниже
    // названы upperPath/lowerPath исторически, но при ОТРИЦАТЕЛЬНОЙ частоте
    // биений левый канал звучит ВЫШЕ правого, и роли путей меняются. Заливка
    // от этого не зависит — это полоса между двумя канальными кривыми.
    val (startLeftFreq, startRightFreq) = Interpolation.interpolateChannels(
        sortedPoints, startTime, interpolationType, splineTension,
        presorted = true, weights = weights
    )
    val startUpperY = params.carrierToY(startRightFreq)
    val startLowerY = params.carrierToY(startLeftFreq)

    upperPath.moveTo(0f, startUpperY)
    lowerPath.moveTo(0f, startLowerY)

    // Y нижней границы на каждом сэмпле.
    // Раньше второй (обратный) проход заново интерполировал нижнюю кривую —
    // те же ~500 вызовов, уже посчитанных в прямом проходе. Теперь значения
    // запоминаются: интерполяций ровно в 2 раза меньше.
    val lowerY = FloatArray(numSamples + 1)
    lowerY[0] = startLowerY

    // Для ступенчатой интерполяции используем специальный алгоритм отрисовки
    if (interpolationType == InterpolationType.STEP) {
        // Рисуем ступеньки напрямую по точкам
        // Каждая ступенька: горизонтальная линия от текущей точки до X следующей точки, затем вертикальный переход
        
        // Находим значение на левой границе (до первой точки)
        val firstPointX = params.timeToX(sortedPoints.first().time)
        // Формулы каналов, а не «нижний/верхний»: при beat < 0 левый канал выше.
        val firstUpperY = params.carrierToY(sortedPoints.last().rightChannelFrequency)
        val firstLowerY = params.carrierToY(sortedPoints.last().leftChannelFrequency)
        
        // От левой границы до первой точки - значение последней точки (переход через полночь)
        upperPath.lineTo(firstPointX, firstUpperY)
        lowerPath.lineTo(firstPointX, firstLowerY)
        
        // Рисуем ступеньки между точками
        for (i in 0 until sortedPoints.size) {
            val currentPoint = sortedPoints[i]
            val nextPoint = sortedPoints.getOrNull(i + 1) ?: sortedPoints.first()
            
            val currentX = params.timeToX(currentPoint.time)
            val nextX = if (i == sortedPoints.size - 1) {
                width // до правой границы
            } else {
                params.timeToX(nextPoint.time)
            }
            
            val currentUpperY = params.carrierToY(currentPoint.rightChannelFrequency)
            val currentLowerY = params.carrierToY(currentPoint.leftChannelFrequency)
            
            // Вертикальный переход в точке (если не первая точка)
            if (i > 0 || currentUpperY != firstUpperY) {
                upperPath.lineTo(currentX, currentUpperY)
            }
            if (i > 0 || currentLowerY != firstLowerY) {
                lowerPath.lineTo(currentX, currentLowerY)
            }
            
            // Горизонтальная линия до следующей точки
            upperPath.lineTo(nextX, currentUpperY)
            lowerPath.lineTo(nextX, currentLowerY)
        }

        // Равномерная выборка нижней кривой для замыкания заливки.
        // Для STEP это те же hold-last значения, что давал старый обратный проход.
        for (i in 1..numSamples) {
            val t = i.toDouble() / numSamples
            val time = LocalTime.fromSecondOfDay((t * 24 * 3600).toInt().coerceAtMost(86399))
            lowerY[i] = params.carrierToY(
                Interpolation.interpolateChannels(
                    sortedPoints, time, interpolationType, splineTension,
                    presorted = true, weights = weights
                ).first // левый канал
            )
        }
    } else {
        // Обычная интерполяция для других типов
        for (i in 1..numSamples) {
            val t = i.toDouble() / numSamples
            val time = LocalTime.fromSecondOfDay((t * 24 * 3600).toInt().coerceAtMost(86399))
            // Канальные кривые через общую функцию (как в движке): (левый, правый)
            val (leftFreq, rightFreq) = Interpolation.interpolateChannels(
                sortedPoints, time, interpolationType, splineTension,
                presorted = true, weights = weights
            )
            lowerY[i] = params.carrierToY(leftFreq)
            val x = (t * width).toFloat()
            upperPath.lineTo(x, params.carrierToY(rightFreq))
            lowerPath.lineTo(x, lowerY[i])
        }
    }

    // Замыкаем путь для заливки
    val combinedPath = Path()
    combinedPath.addPath(upperPath)

    // Обратный путь по нижней границе — по уже посчитанным значениям,
    // без повторной интерполяции
    for (i in numSamples downTo 0) {
        val t = i.toDouble() / numSamples
        val x = (t * width).toFloat()
        combinedPath.lineTo(x, lowerY[i])
    }

    combinedPath.close()

    return BeatPaths(upper = upperPath, lower = lowerPath, combined = combinedPath)
}

/**
 * Строит пунктирную линию базовой кривой (проходящей через основные точки).
 * Используется в режимах ADVANCED и SMOOTH для отображения исходной кривой.
 *
 * Как и [buildBeatPaths] — чистая функция, результат кэшируется.
 */
private fun buildDashedBaseCurvePath(
    realPoints: List<FrequencyPoint>,
    params: GraphParams,
    interpolationType: InterpolationType,
    splineTension: Float,
    /**
     * Веса касательных для [realPoints] — базовой кривой.
     *
     * НЕ те же, что у канальных кривых: пунктир идёт по РЕАЛЬНЫМ точкам,
     * а канальные кривые в режиме расслабления — по виртуальным. Веса
     * считаются по своим узлам в каждом случае.
     */
    weights: FloatArray? = null
): Path {
    val width = params.widthPx.toFloat()
    val carrierPath = Path()

    // Начинаем с левой границы (время 0)
    val startTime = LocalTime.fromSecondOfDay(0)
    val startCarrier = interpolateCarrierFrequency(
        realPoints, startTime, interpolationType, splineTension,
        presorted = true, weights = weights
    )
    val startY = params.carrierToY(startCarrier)
    carrierPath.moveTo(0f, startY)
    
    // Динамическое количество сэмплов
    val numSamples = (realPoints.size * 2).coerceAtLeast(300)
    for (i in 1..numSamples) {
        val t = i.toDouble() / numSamples
        val time = LocalTime.fromSecondOfDay((t * 24 * 3600).toInt().coerceAtMost(86399))
        val carrier = interpolateCarrierFrequency(
            realPoints, time, interpolationType, splineTension,
            presorted = true, weights = weights
        )
        val y = params.carrierToY(carrier)
        val x = (t * width).toFloat()
        carrierPath.lineTo(x, y)
    }

    return carrierPath
}

@Composable
fun DraggablePoint(
    xPx: Float,
    yPx: Float,
    isSelected: Boolean,
    point: FrequencyPoint,
    maxBeat: Float,
    originalIndex: Int,
    carrierRange: FrequencyRange,
    minTimeSeconds: Int,
    maxTimeSeconds: Int,
    graphWidthPx: Int,
    graphHeightPx: Int,
    primaryColor: Color,
    onPointSelected: (Int) -> Unit,
    // Частота биений в колбэках жеста отсутствует: её рассчитывает ViewModel
    // из желаемого значения точки (см. [PointDragState]).
    onDragStart: (Int, LocalTime, Float) -> Unit,
    onDragUpdate: (Int, LocalTime, Float, DragDirection) -> Unit,
    onDragEnd: (Int, LocalTime, Float, DragDirection) -> Unit
) {
    val density = LocalDensity.current
    
    var totalDragX by remember { mutableStateOf(0f) }
    var totalDragY by remember { mutableStateOf(0f) }
    var currentDragDirection by remember { mutableStateOf(DragDirection.NONE) }
    var hasDirectionDetermined by remember { mutableStateOf(false) }
    var startSeconds by remember { mutableStateOf(0) }
    var startCarrier by remember { mutableStateOf(0.0f) }
    
    val pointSize = if (isSelected) POINT_MARKER_SELECTED_SIZE else POINT_MARKER_SIZE
    val halfSizePx = with(density) { (pointSize / 2).roundToPx() }
    
    Box(
        modifier = Modifier
            .offset { IntOffset((xPx - halfSizePx).toInt(), (yPx - halfSizePx).toInt()) }
            .size(pointSize)
            .background(if (isSelected) primaryColor else primaryColor.copy(alpha = 0.7f), CircleShape)
            .border(2.dp, Color.White, CircleShape)
            .clickable { onPointSelected(originalIndex) }
            .pointerInput(originalIndex, point.time, point.carrierFrequency) {
                detectDragGestures(
                    onDragStart = { _ ->
                        totalDragX = 0f; totalDragY = 0f
                        currentDragDirection = DragDirection.NONE
                        hasDirectionDetermined = false
                        startSeconds = point.time.toSecondOfDay()
                        startCarrier = point.carrierFrequency
                        onDragStart(originalIndex, point.time, point.carrierFrequency)
                    },
                    onDragEnd = {
                        val newTime = calculateTimeFromDrag(startSeconds, totalDragX, minTimeSeconds, maxTimeSeconds, graphWidthPx.toFloat())
                        val newCarrier = calculateCarrierFromDrag(startCarrier, totalDragY, carrierRange, graphHeightPx.toFloat())
                        onDragEnd(originalIndex, newTime, newCarrier, currentDragDirection)
                        totalDragX = 0f; totalDragY = 0f
                        currentDragDirection = DragDirection.NONE
                        hasDirectionDetermined = false
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        totalDragX += dragAmount.x
                        totalDragY += dragAmount.y

                        if (!hasDirectionDetermined && (abs(totalDragX) > DRAG_DIRECTION_THRESHOLD || abs(totalDragY) > DRAG_DIRECTION_THRESHOLD)) {
                            currentDragDirection = if (abs(totalDragX) > abs(totalDragY)) DragDirection.HORIZONTAL else DragDirection.VERTICAL
                            hasDirectionDetermined = true
                        }

                        val newTime = calculateTimeFromDrag(startSeconds, totalDragX, minTimeSeconds, maxTimeSeconds, graphWidthPx.toFloat())
                        val newCarrier = calculateCarrierFromDrag(startCarrier, totalDragY, carrierRange, graphHeightPx.toFloat())
                        when (currentDragDirection) {
                            DragDirection.HORIZONTAL -> onDragUpdate(originalIndex, newTime, startCarrier, DragDirection.HORIZONTAL)
                            DragDirection.VERTICAL -> onDragUpdate(originalIndex, point.time, newCarrier, DragDirection.VERTICAL)
                            DragDirection.NONE -> onDragUpdate(originalIndex, newTime, newCarrier, DragDirection.NONE)
                        }
                    }
                )
            }
    ) {
        // Размер индикатора — по МОДУЛЮ частоты биений: знак задаёт только
        // раскладку каналов, а «толщина» пульсации определяется |beat|.
        // Сверху ограничен самим маркером: геометрический предел модуля
        // (до 1980 Гц) превышает масштаб maxBeat, и индикатор не должен
        // вылезать за круг.
        val beatIndicatorSize = with(density) {
            abs(point.beatFrequency / maxBeat * 12).toFloat().toDp()
                .coerceIn(4.dp, pointSize)
        }
        Box(modifier = Modifier.size(beatIndicatorSize).background(Color.White.copy(alpha = 0.6f), CircleShape).align(Alignment.Center))
    }
}

// Шаг перемещения по времени (в минутах)
private const val TIME_STEP_MINUTES = 5

/**
 * Приводит диапазон, пришедший из [RangeSlider], к допустимому: удерживает
 * минимальный зазор [CARRIER_RANGE_MIN_SPAN] между ручками и не выпускает их
 * за [CARRIER_RANGE_BOUNDS].
 *
 * [RangeSlider] сам не даёт ручкам разойтись (start <= end), но позволяет им
 * слипнуться. Какая именно ручка тянется, он тоже не сообщает, поэтому
 * двинувшийся конец ищется сравнением с [previous]: за одно касание меняется
 * только один из них.
 *
 * Второй конец остаётся на месте, пока движущийся не подойдёт к нему
 * вплотную — тогда он ОТТАЛКИВАЕТСЯ, сохраняя зазор: верхняя граница
 * уезжает вверх, если нижнюю тянут к ней снизу, и наоборот. Тем самым ручка
 * всегда идёт за пальцем, а не залипает.
 *
 * Границы [CARRIER_RANGE_BOUNDS] заведомо шире зазора, поэтому место для
 * отталкивания есть всегда: нижняя ручка не уходит выше
 * `MAX - SPAN`, верхняя не опускается ниже `MIN + SPAN`, и зазор выполняется
 * без взаимных уступок.
 *
 * Значения, не менявшие диапазон (повторный вызов с тем же значением),
 * возвращаются как есть: иначе у пресета с исторически узким диапазоном
 * (зазор < 100 Гц) метка скакнула бы сразу при открытии диалога, без касания.
 */
private fun applyRangeGap(
    candidate: ClosedFloatingPointRange<Float>,
    previous: ClosedFloatingPointRange<Float>
): ClosedFloatingPointRange<Float> {
    val startDelta = abs(candidate.start - previous.start)
    val endDelta = abs(candidate.endInclusive - previous.endInclusive)
    if (startDelta < CARRIER_RANGE_EPSILON && endDelta < CARRIER_RANGE_EPSILON) {
        return previous
    }

    return if (startDelta >= endDelta) {
        // Тянут нижнюю границу.
        val start = candidate.start.coerceIn(
            MIN_AUDIBLE_FREQUENCY, MAX_FREQUENCY - CARRIER_RANGE_MIN_SPAN
        )
        val end = maxOf(previous.endInclusive, start + CARRIER_RANGE_MIN_SPAN)
            .coerceAtMost(MAX_FREQUENCY)
        start..end
    } else {
        // Тянут верхнюю границу.
        val end = candidate.endInclusive.coerceIn(
            MIN_AUDIBLE_FREQUENCY + CARRIER_RANGE_MIN_SPAN, MAX_FREQUENCY
        )
        val start = minOf(previous.start, end - CARRIER_RANGE_MIN_SPAN)
            .coerceAtLeast(MIN_AUDIBLE_FREQUENCY)
        start..end
    }
}

/**
 * Вычисляет время из перетаскивания с шагом в 5 минут.
 */
private fun calculateTimeFromDrag(startSeconds: Int, dragX: Float, minSeconds: Int, maxSeconds: Int, graphWidth: Float): LocalTime {
    val newSeconds = (startSeconds + (dragX * 24 * 3600 / graphWidth).toInt()).coerceIn(minSeconds, maxSeconds)
    
    // Округляем до шага в 5 минут
    val stepSeconds = TIME_STEP_MINUTES * 60
    val snappedSeconds = (newSeconds / stepSeconds) * stepSeconds
    
    // Убеждаемся, что время не выходит за границы
    val finalSeconds = snappedSeconds.coerceIn(minSeconds, maxSeconds)
    return LocalTime.fromSecondOfDay(finalSeconds)
}

/**
 * Шаг «в одно деление» несущей частоты: 1% разницы между верхней и нижней
 * границами диапазона, округлённый до ближайшего целого СВЕРХУ.
 *
 * Округление вверх гарантирует, что шаг не выродится в 0 на узких
 * диапазонах: у диапазона 100–140 Гц разница 40 Гц, 1% — это 0,4 Гц, и
 * округление «вниз» или «до ближайшего» обнулило бы шаг, а «вверх»
 * даёт 1 Гц. На широком диапазоне (20–2000 Гц) шаг равен 20 Гц.
 *
 * Шаг ОБЩИЙ для перетаскивания точки по графику и для свайпа по полю
 * несущей в контекстном окне ([PointEditorPopup]) — иначе два жеста давали
 * бы разные значения на одной и той же высоте.
 *
 * @return шаг в герцах; 0, если диапазон вырожден ([FrequencyRange] не
 *         допускает max <= min, но границы могут быть сколь угодно близки)
 */
internal fun carrierStep(carrierRange: FrequencyRange): Float {
    val span = carrierRange.max - carrierRange.min
    if (span <= 0f) return 0f
    return ceil(span / 100f)
}

/**
 * Приводит несущую частоту к сетке с шагом [carrierStep].
 *
 * Сетка привязана к НИЖНЕЙ границе диапазона, а не к значению, с которого
 * началось перетаскивание: тогда точка, отпущенная в одной и той же
 * позиции, получает одно и то же значение независимо от стартового, и
 * две разные точки, поставленные на одну высоту, совпадут по частоте.
 *
 * Значение сначала зажимается границами, а уже потом округляется до
 * ближайшего узла сетки — иначе у верхней границы округление «задирало»
 * бы частоту выше максимума, и после [FrequencyRange.clamp] точка
 * прилипала бы к краю раньше, чем следовало.
 */
internal fun quantizeCarrier(value: Float, carrierRange: FrequencyRange): Float {
    val step = carrierStep(carrierRange)
    if (step <= 0f) return carrierRange.clamp(value)
    val clamped = carrierRange.clamp(value)
    val stepsFromMin = round((clamped - carrierRange.min) / step)
    return carrierRange.clamp(carrierRange.min + stepsFromMin * step)
}

private fun calculateCarrierFromDrag(startCarrier: Float, dragY: Float, carrierRange: FrequencyRange, graphHeight: Float): Float {
    if (graphHeight <= 0f) return carrierRange.clamp(startCarrier)
    val rawCarrier = startCarrier - dragY * (carrierRange.max - carrierRange.min) / graphHeight
    return quantizeCarrier(rawCarrier, carrierRange)
}

// Функции интерполяции

/**
 * Интерполяция несущей частоты по базовой кривой (используется для пунктирной
 * линии базовой кривой в режимах расслабления — каноническое правило оценки базовых кривых).
 */
fun interpolateCarrierFrequency(
    points: List<FrequencyPoint>,
    time: LocalTime,
    interpolationType: InterpolationType = InterpolationType.LINEAR,
    splineTension: Float = 0.0f,
    presorted: Boolean = false,
    weights: FloatArray? = null
): Float = interpolateFrequency(
    points, time, interpolationType, splineTension, presorted, weights
) { it.carrierFrequency }

fun interpolateFrequency(
    points: List<FrequencyPoint>,
    time: LocalTime,
    interpolationType: InterpolationType = InterpolationType.LINEAR,
    splineTension: Float = 0.0f,
    /**
     * true, если [points] уже отсортирован по времени суток.
     * Горячий путь (drawDashedBaseCurve) передаёт отсортированный список и
     * вызывает эту функцию >=300 раз за кадр — без флага каждый вызов
     * аллоцировал и сортировал копию списка.
     */
    presorted: Boolean = false,
    /**
     * Веса касательных кардинального сплайна (см. CardinalTension).
     *
     * Те же, что у канальных кривых: сплайн линеен, поэтому carrier = (left+right)/2
     * интерполируется РОВНО теми же весами. Иначе пунктирная базовая кривая
     * разошлась бы с нарисованными каналами — она бы вылетала за границы там,
     * где каналы уже прижаты.
     */
    weights: FloatArray? = null,
    frequencySelector: (FrequencyPoint) -> Float
): Float {
    val sortedPoints = if (presorted) points else points.sortedBy { it.time.toSecondOfDay() }
    if (sortedPoints.isEmpty()) return 0.0f
    if (sortedPoints.size == 1) return frequencySelector(sortedPoints[0])

    // Веса привязаны к узлам; при несовпадении размера молча игнорируются —
    // безопаснее отдать номинальный сплайн, чем применить чужие веса.
    val w = if (weights != null && weights.size == sortedPoints.size) weights else null

    val targetSeconds = time.toSecondOfDay()
    
    // Находим интервал, в который попадает время
    var intervalIndex = -1
    for (i in 0 until sortedPoints.size - 1) {
        val current = sortedPoints[i].time.toSecondOfDay()
        val next = sortedPoints[i + 1].time.toSecondOfDay()
        if (targetSeconds in current..next) {
            intervalIndex = i
            break
        }
    }
    
    // Если не нашли в обычных интервалах - это переход через полночь
    if (intervalIndex == -1) {
        return interpolateBetweenPoints(
            sortedPoints,
            sortedPoints.size - 1,
            0,
            time,
            frequencySelector,
            interpolationType,
            splineTension,
            isWrapping = true,
            weights = w
        )
    }
    
    return interpolateBetweenPoints(
        sortedPoints,
        intervalIndex,
        intervalIndex + 1,
        time,
        frequencySelector,
        interpolationType,
        splineTension,
        isWrapping = false,
        weights = w
    )
}

/**
 * Интерполяция между двумя точками с учётом соседних для кубического сплайна
 */
private fun interpolateBetweenPoints(
    sortedPoints: List<FrequencyPoint>,
    leftIndex: Int,
    rightIndex: Int,
    time: LocalTime,
    frequencySelector: (FrequencyPoint) -> Float,
    interpolationType: InterpolationType,
    splineTension: Float,
    isWrapping: Boolean,
    weights: FloatArray? = null
): Float {
    val leftPoint = sortedPoints[leftIndex]
    val rightPoint = sortedPoints[rightIndex]
    
    // Вычисляем нормализованную позицию t в интервале [0, 1]
    val t1 = leftPoint.time.toSecondOfDay()
    val t2 = if (isWrapping) {
        rightPoint.time.toSecondOfDay() + 24 * 3600
    } else {
        rightPoint.time.toSecondOfDay()
    }
    val t = if (time.toSecondOfDay() < t1 && isWrapping) {
        time.toSecondOfDay() + 24 * 3600
    } else {
        time.toSecondOfDay()
    }
    
    if (t2 == t1) return frequencySelector(leftPoint)

    // Кламп ratio [0,1] — согласовано с C++ (buildLookupTableInternal)
    val ratio = ((t - t1).toFloat() / (t2 - t1)).coerceIn(0.0f, 1.0f)
    
    // Получаем 4 точки для интерполяции
    val p0 = getNeighborPoint(sortedPoints, leftIndex, -1, frequencySelector, isWrapping)
    val p1 = frequencySelector(leftPoint)
    val p2 = frequencySelector(rightPoint)
    val p3 = getNeighborPoint(sortedPoints, rightIndex, +1, frequencySelector, isWrapping)
    
    val w1 = weights?.get(leftIndex) ?: 1.0f
    val w2 = weights?.get(rightIndex) ?: 1.0f

    // Используем общий объект интерполяции с параметром tension
    return Interpolation.interpolate(
        interpolationType, p0, p1, p2, p3, ratio, splineTension, false, w1, w2
    )
}

/**
 * Получить соседнюю точку с учётом цикличности графика и перехода через полночь
 * @param isWrapping true, если текущий интервал переходит через полночь
 */
private fun getNeighborPoint(
    points: List<FrequencyPoint>,
    currentIndex: Int,
    offset: Int,
    frequencySelector: (FrequencyPoint) -> Float,
    isWrapping: Boolean = false
): Float {
    val size = points.size
    // Циклический доступ — согласован с C++ (wrap-соседи всегда по модулю size)
    val neighborIndex = ((currentIndex + offset) % size + size) % size
    return frequencySelector(points[neighborIndex])
}

