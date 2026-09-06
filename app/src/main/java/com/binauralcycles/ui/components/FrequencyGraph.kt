package com.binauralcycles.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import android.graphics.Paint
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import com.binaural.core.audio.model.CardinalTension
import com.binauralcycles.ui.theme.Spacing
import com.binaural.core.audio.model.FrequencyMath
import com.binaural.core.audio.model.FrequencyPoint
import com.binaural.core.audio.model.FrequencyRange
import com.binaural.core.audio.model.Interpolation
import com.binaural.core.audio.model.InterpolationType
import com.binaural.core.audio.model.RelaxationModeSettings
import com.binauralcycles.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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

// Минимально допустимая ширина диапазона несущих частот. При правке
// границ (через клик по крайним меткам) разница между пределами не может
// быть меньше этого значения — иначе график вырождается в почти прямую
// линию и теряет смысл. См. проверку в диалоге смены границы.
private const val MIN_CARRIER_RANGE_SPAN_HZ = 100.0f

// Диаметр маркера точки на графике. Вынесен в константу, чтобы размер в
// расчёте разъезда меток оси Y совпадал с размером нарисованного круга —
// иначе метка уезжала бы от воображаемой точки, а не от настоящей.
private val POINT_MARKER_SIZE = 24.dp
private val POINT_MARKER_SELECTED_SIZE = 30.dp

// Смещение меток оси Y влево за пределы Box'а графика: метки стоят у
// левого края оси Y и торчат наружу на эту величину (отрицательное значение).
private val Y_AXIS_LABEL_OFFSET_X = (-8).dp

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
    // СКРАБ: РЕАЛЬНЫЙ момент времени суток (без сдвига предпросмотра) — серая
    // линия. Приходит из того же источника, что и ось (менеджер публикует их
    // парой), поэтому вычитать сдвиг из оси здесь больше не нужно: два
    // StateFlow доезжали в непредсказуемом порядке, и «ось − сдвиг» залипало
    // на величину сдвига (§14.7 плана). null — у вызывающего нет скраба,
    // тогда реальным считается сама ось.
    realTimeOfDay: LocalTime? = null,
    // СКРАБ: ручку отпустили на времени [LocalTime] — перестроить ось.
    // Звук меняется ОДИН раз по отпускании, а не на каждом шаге жеста.
    onScrubTo: (LocalTime) -> Unit = {},
    // СКРАБ: вернуть прослушивание к реальному текущему моменту.
    onScrubReset: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // УДАЛЕНО: `val sortedPoints = points.sortedBy { ... }` вычислялось на каждой
    // рекомпозиции (3-4 раза в секунду при воспроизведении) и нигде не
    // использовалось — в drawBehind передаётся allPoints. Отсортированный список
    // берётся из remember ниже (displayPoints).
    var dragState by remember { mutableStateOf(PointDragState()) }
    // Диалог правки границы несущей: две метки (MIN/MAX) открывают диалог
    // ввода значения по отдельности. Без алгоритма смещения меток — позиции
    // фиксированы, метки не разъезжаются при перетаскивании/добавлении точек.
    var showRangeDialog by remember { mutableStateOf(false) }
    var editingRangeType by remember { mutableStateOf<RangeType?>(null) }
    var tempRangeValue by remember { mutableStateOf(TextFieldValue("")) }

    // Локализованный формат Гц - объявляем здесь для использования во всём компоненте
    val hzFormat = stringResource(R.string.hz_value_format)

    // Единое время приходит из PlaybackTelemetry (StateFlow сервиса);
    // приватный тикер удалён - график живёт тем же потоком данных, что и карточки
    val currentLocalTime = externalCurrentTime
        ?: Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).time
    val density = LocalDensity.current

    // СКРАБ: на графике три времени, и путать их нельзя.
    //
    //  axisTime    — ось движка (уже со сдвигом): где звук находится на самом
    //                деле. Красная линия.
    //  realTime    — реальное «сейчас», серая линия-призрак. Приходит ГОТОВЫМ
    //                из менеджера (§14.7 плана), а не считается здесь.
    //  previewTime — красная линия: под пальцем это цель перетаскивания.
    //
    // Ни одно из трёх не считается из положения пальца: иначе во время
    // перетаскивания серая линия поехала бы вместе с ручкой — ровно наоборот
    // тому, что нужно (§5.1 плана).
    val axisTime = currentLocalTime
    //
    // РАНЬШЕ здесь было `remember(axisTime) { scrubOffsetSeconds }` и
    // `realTime = ось − этот_сдвиг`. Залипало: ось и сдвиг — два StateFlow,
    // порядок их прихода не гарантирован, и remember, однажды посчитанный на
    // «оси без сдвига», уже не пересчитывался до следующего ИЗМЕНЕНИЯ оси.
    // Если ось приезжала первой, серая линия вставала на цель перетаскивания
    // и висела там до минуты. Теперь реальное время публикует менеджер.
    val realTime = realTimeOfDay ?: axisTime
    // Цель перетаскивания живёт, пока палец на ручке.
    var scrubDragTime by remember { mutableStateOf<LocalTime?>(null) }
    // Цель, которую палец уже отпустил, а ось до неё ЕЩЁ НЕ ДОЕХАЛА.
    //
    // Между отпусканием и приходом новой оси проходит до секунды (опрос 1 Гц),
    // и ось в UI всё это время СТАРАЯ. Если отдать красную линию оси сразу,
    // она на это окно отскочит к реальному «сейчас»: жест выглядит
    // отменённым — линия возвращается и через мгновение снова уезжает на цель.
    var scrubPendingTarget by remember { mutableStateOf<LocalTime?>(null) }
    val previewTime = scrubDragTime ?: scrubPendingTarget ?: axisTime

    // Ось доехала до цели — можно снова вести красную линию осью.
    //
    // Ключ — ось: именно её приход и есть сигнал «доехала». Допуск
    // [SCRUB_AXIS_SETTLE_TOL_SEC] перекрывает квантование телеметрии до 60 с
    // (§11.5 плана). [SCRUB_SETTLE_TIMEOUT_MS] — страховка: если скраб не
    // состоялся (звук встал, сменился пресет, сдвиг так и остался 0), линия
    // обязана вернуться на ось, а не замереть на цели навсегда.
    LaunchedEffect(axisTime, scrubPendingTarget) {
        val target = scrubPendingTarget ?: return@LaunchedEffect
        if (abs(circularDiffSeconds(axisTime, target)) <= SCRUB_AXIS_SETTLE_TOL_SEC) {
            scrubPendingTarget = null
            return@LaunchedEffect
        }
        delay(SCRUB_SETTLE_TIMEOUT_MS)
        scrubPendingTarget = null
    }

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
            // Отступы сверху/снизу уменьшены (16.dp -> Spacing.sm=8.dp) по
            // просьбе: график на экране редактирования пресета должен быть
            // вертикально плотнее. Горизонталь тоже уменьшена (16.dp ->
            // Spacing.sm=8.dp) по просьбе — график ближе к краям экрана.
            // Крайние метки частот рисуются внутри Box'а (от 3.dp от левого
            // края), поэтому уменьшение внешнего отступа их не обрезает.
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm)
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
            // Прежний цвет обычных меток (до правки) — onSurfaceVariant, рисовался
            // при α≈0.85. Текущий (после правки) — primary при α0.15 (как линии сетки).
            val axisLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
            // Цвет обычных (некликабельных) меток — СЕРЕДИНА между прежним и
            // текущим: усредняем оба цвета по hue и берём α = (0.85 + 0.15) / 2 = 0.5.
            // Кликабельные крайние метки границ остаются яркими (primary), чтобы
            // выделяться на их фоне.
            val gridLabelColor = Color(
                red = (axisLabelColor.red + primaryColor.red) / 2f,
                green = (axisLabelColor.green + primaryColor.green) / 2f,
                blue = (axisLabelColor.blue + primaryColor.blue) / 2f,
                alpha = 0.5f
            )

            // Кисть и цвет для подписей осей (часовые метки + метки частот).
            // Размер текста считается один раз по density; цвет переставляется
            // прямо в DrawScope на случай смены темы.
            val axisLabelPaint = remember(density) {
                Paint().apply {
                    textSize = with(density) { 9.sp.toPx() }
                    isAntiAlias = true
                    textAlign = Paint.Align.LEFT
                }
            }
            val axisLabelBottomPx = with(density) { 11.dp.toPx() }
            val axisLabelLeftPx = with(density) { 3.dp.toPx() }
            val errorColor = MaterialTheme.colorScheme.error
            // Линия-призрак (реальное «сейчас» при скрабе): цвет primary
            // из динамической темы (Monet) с такой же прозрачностью,
            // как у линии воспроизведения (SCRUB_ARROW_ALPHA = 0.3f).
            val ghostLineColor = MaterialTheme.colorScheme.primary

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
                        val width = size.width
                        val height = size.height
                        drawGrid(primaryColor)
                        // ВСЕ метки — ДО кривой, то есть в фоне, ПОД графиком.
                        drawHourAxisLabels(
                            width = width,
                            height = height,
                            paint = axisLabelPaint,
                            textColor = gridLabelColor,
                            bgColor = cardSurfaceColor.copy(alpha = 0.75f),
                            bottomPx = axisLabelBottomPx
                        )
                        drawFrequencyAxisLabels(
                            width = width,
                            height = height,
                            graphParams = graphParams,
                            paint = axisLabelPaint,
                            edgeTextColor = primaryColor,
                            edgeBgColor = primaryColor.copy(alpha = 0.1f),
                            midTextColor = gridLabelColor,
                            midBgColor = cardSurfaceColor.copy(alpha = 0.75f),
                            hzFormat = hzFormat,
                            leftPx = axisLabelLeftPx
                        )
                        drawGraphPaths(staticPaths, primaryColor)
                    }
                    // Отдельный слой под динамичный указатель: он единственный
                    // зависит от времени и частот, и он же самый дешёвый.
                    .drawBehind {
                        if (isPlaying) {
                            // Серая линия — «призрак» реального сейчас. Рисуется
                            // ДО красной, то есть ПОД ней: при совпадении она
                            // просто прячется под красной, а не спорит с ней.
                            // Полосы биений на ней намеренно нет — это только
                            // положение.
                            //
                            // Видимость решает ГЕОМЕТРИЯ, а не состояние
                            // скраба (§14.8 плана). Раньше здесь стояло
                            // `scrubOffsetSeconds != 0 || scrubDragTime != null
                            // || scrubPendingTarget != null`, и ровно в тот
                            // момент, когда серая линия нужнее всего, все три
                            // условия были ложны: палец уже отпущен (цель
                            // перетаскивания пуста), ось доехала до цели и
                            // сняла [scrubPendingTarget], а StateFlow сдвига
                            // ещё не долетел — сдвиг по-прежнему 0. Линия
                            // пропадала на отпускании и возвращалась позже.
                            //
                            // Теперь решает только расстояние между красной
                            // линией ([previewTime]) и реальным «сейчас»
                            // ([realTime]): пока они разошлись — серая видна,
                            // неважно, чем вызвано расхождение: пальцем,
                            // недоехавшей осью или уже применённым сдвигом.
                            // Оба времени приходят готовыми (§14.7), разность
                            // берётся по кругу суток — иначе около полуночи
                            // пара 23:59 / 00:01 дала бы всю ширину графика.
                            val ghostGapPx =
                                offNowGapPx(previewTime, realTime, graphParams.widthPx)
                            if (ghostGapPx > GHOST_LINE_MIN_GAP_PX) {
                                val realX = graphParams.timeToX(realTime)
                                drawLine(
                                    // Цвет из темы (Monet), альфа — как у
                                    // основных сегментов красной линии
                                    // воспроизведения (§14.9, 0.3f), а не 0.35f
                                    // на сером: серый на светлой теме
                                    // сливался с сеткой.
                                    color = ghostLineColor.copy(alpha = SCRUB_ARROW_ALPHA),
                                    start = Offset(realX, 0f),
                                    end = Offset(realX, size.height),
                                    // Та же толщина, что у красной линии: её
                                    // основные сегменты нарисованы шириной 2f
                                    // (только полоса биений — 3f). Прежние
                                    // `2.dp.toPx()` давали в 2–3 раза больше,
                                    // и серая выглядела жирнее красной.
                                    strokeWidth = 2f
                                )
                            }
                            drawCurrentTimeIndicator(
                                graphParams = graphParams,
                                currentLocalTime = previewTime,
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
                    val badgeText = when (dragState.direction) {
                        DragDirection.HORIZONTAL -> "%02d:%02d".format(dragState.currentTime!!.hour, dragState.currentTime!!.minute)
                        DragDirection.VERTICAL -> hzFormat.format(dragState.currentCarrier)
                        DragDirection.NONE -> ""
                    }
                    // Бейдж времени/несущей над точкой — единый вид с ручкой
                    // скраба (TimeLabelBadge, §запрос 2026-09-04).
                    Box(modifier = Modifier.offset { IntOffset(previewXPx.toInt() - 50, previewYPx.toInt() - 160) }) {
                        TimeLabelBadge(badgeText)
                    }
                }

                // Прозрачные зоны касания поверх крайних меток частот (MAX сверху,
                // MIN снизу), нарисованных в drawFrequencyAxisLabels. Геометрия
                // синхронна с отрисовкой (padX/padY те же), чтобы зона точно
                // накрывала видимую часть метки. Касание открывает диалог смены
                // соответствующей границы диапазона. Метки лежат В ФОНЕ (до
                // кривой), поэтому зоны касания — отдельные прозрачные Box-дети.
                val edgeTextH = axisLabelPaint.textSize
                val edgePadX = edgeTextH * 0.3f
                val edgePadY = edgeTextH * 0.18f
                val maxTw = axisLabelPaint.measureText(hzFormat.format(carrierRange.max))
                val minTw = axisLabelPaint.measureText(hzFormat.format(carrierRange.min))
                val edgeLabelLeft = axisLabelLeftPx - edgePadX
                val maxLabelTop = -edgeTextH * 0.65f - edgePadY
                val minLabelTop = heightPx - edgeTextH * 0.65f - edgePadY

                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset { IntOffset(edgeLabelLeft.toInt(), maxLabelTop.toInt()) }
                        .size(
                            width = with(density) { (maxTw + 2 * edgePadX).toDp() },
                            height = with(density) { (edgeTextH + 2 * edgePadY).toDp() }
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            editingRangeType = RangeType.MAX
                            val maxText = "%.0f".format(carrierRange.max)
                            tempRangeValue = TextFieldValue(maxText, selection = TextRange(maxText.length))
                            showRangeDialog = true
                        }
                ) {}

                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset { IntOffset(edgeLabelLeft.toInt(), minLabelTop.toInt()) }
                        .size(
                            width = with(density) { (minTw + 2 * edgePadX).toDp() },
                            height = with(density) { (edgeTextH + 2 * edgePadY).toDp() }
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            editingRangeType = RangeType.MIN
                            val minText = "%.0f".format(carrierRange.min)
                            tempRangeValue = TextFieldValue(minText, selection = TextRange(minText.length))
                            showRangeDialog = true
                        }
                ) {}

                // СКРАБ: ручка ◀|▶ — объявлена ПОСЛЕДНИМ детём Box-а области
                // графика, то есть выше маркеров точек (§7 плана). Появляется
                // только во время воспроизведения: без звука «прослушать
                // другое время суток» невозможно.
                if (isPlaying) {
                    // ВИДИМОСТЬ кнопки сброса (§запрос 2026-09-04): видна, пока
                    // красная линия ([previewTime]) разошлась с реальным «сейчас»
                    // ([realTime]) дальше порога — тот же порог, что у серой
                    // линии-призрака. ИЛИ пока идёт перетаскивание ручки
                    // (scrubDragTime != null): тогда не гаснет даже при
                    // мгновенном проходе через «сейчас», то есть скраб с одного
                    // кастомного положения на другое кнопку не прячет.
                    // Не `scrubOffsetSeconds != 0`: флаг — про состояние скраба,
                    // а не про положение линии, и сразу после отпускания он ещё 0
                    // (§14.8) — кнопка мигала бы.
                    val showResetNow = offNowGapPx(previewTime, realTime, graphParams.widthPx) >
                        GHOST_LINE_MIN_GAP_PX || scrubDragTime != null
                    ScrubHandle(
                        time = previewTime,
                        showReset = showResetNow,
                        graphParams = graphParams,
                        indicatorColor = errorColor,
                        realTimeOfDay = realTimeOfDay,
                        onDrag = { scrubDragTime = it },
                        onCommit = { target ->
                            scrubDragTime = null
                            // Красная линия остаётся на цели, пока ось не
                            // доедет: иначе она на кадр-два (а с учётом опроса
                            // 1 Гц — до секунды) отскочит к «сейчас».
                            scrubPendingTarget = target
                            onScrubTo(target)
                        },
                        onCancel = {
                            scrubDragTime = null
                            scrubPendingTarget = null
                        },
                        onReset = {
                            // СКРАБ: сброс к «сейчас» И очистка локальных целей
                            // жеста, чтобы красная линия сразу встала на ось
                            // (она и есть текущий момент). Без очистки линия
                            // залипла бы на застывшей цели, а серая уползла бы
                            // вперёд (§14.11). Вызывается и кнопкой сброса, и
                            // «магнитом» при отпускании у «сейчас».
                            scrubDragTime = null
                            scrubPendingTarget = null
                            onScrubReset()
                        }
                    )
                }
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
            // Запас под тень окна в пикселях: на него расширяется слой
            // анимации и на столько же сдвигается позиция слоя (см. ниже).
            val shadowPadPx = with(density) { POINT_POPUP_SHADOW_PAD.toPx() }

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
                        // переезд не перекомпонуется ни разу. Из него вычтен
                        // запас под тень: слой больше самого окна (padding
                        // ниже), и без вычета окно уехало бы влево-вверх.
                        .offset {
                            IntOffset(
                                (popupLeftPx(popupAnchorX.value, popupSize.width) - shadowPadPx).roundToInt(),
                                (popupAnchorY.value + gapPx - shadowPadPx).roundToInt()
                            )
                        }
                        // Слайд: при появлении окно приезжает снизу вверх,
                        // при скрытии уезжает вниз. Слой, а не модификатор
                        // композиции: кадры анимации не трогают композицию.
                        //
                        // ВАЖНО: пока альфа меньше единицы, слой рисует
                        // содержимое в отдельный буфер и обрезает рисование
                        // по СВОИМ границам. Тень тела окна рисуется ВНЕ
                        // прямоугольника окна — под ним, слева и справа, —
                        // поэтому при слое точно по окну она срезается
                        // полностью: на экране остаются лишь клочки тени в
                        // скруглённых углах, то есть там, где тень попадает
                        // внутрь прямоугольника окна. Границы слоя расширены
                        // на [POINT_POPUP_SHADOW_PAD] (padding ниже) — тень
                        // целиком внутри слоя и едет вместе с окном.
                        .graphicsLayer {
                            alpha = popupProgress.value
                            translationY = (1f - popupProgress.value) * popupSlidePx
                        }
                        // Запас под тень — ВНУТРИ слоя, поэтому padding
                        // объявлен ПОСЛЕ graphicsLayer: так слой становится
                        // больше окна. Наоборот (padding до graphicsLayer)
                        // не работает — запас остался бы снаружи слоя, и
                        // тень по-прежнему срезалась бы по границам окна.
                        .padding(POINT_POPUP_SHADOW_PAD)
                ) {
                    Box(
                        modifier = Modifier
                            // Размер самого окна — БЕЗ запаса: по нему
                            // считаются и центрирование под точкой, и
                            // прямоугольник закрытия по касанию мимо окна.
                            .onSizeChanged { popupSize = it }
                            // Касания по самому окну не должны уходить на
                            // график и закрывать его: съедаем их, без ripple.
                            // Висит на ВНУТРЕННЕМ Box — зона касания ровно
                            // по окну, без запаса под тень: касание впритык
                            // к окну закрывает его, как и раньше.
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
                            // Уголок смотрит на точку, поэтому его смещение
                            // внутри окна считается на фазе раскладки — по
                            // тому же анимационному положению, что и само
                            // окно. Значение, а не состояние: иначе каждый
                            // кадр переезда перекомпоновывал бы всё окно.
                            arrowOffsetX = {
                                val x = popupAnchorX.value
                                x - popupLeftPx(x, popupSize.width)
                            },
                            onCarrierFrequencyChange = { onPointCarrierChanged(anchorIndex, it) },
                            onBeatFrequencyChange = { onPointBeatChanged(anchorIndex, it) },
                            onTimeChange = { onPointTimeChanged(anchorIndex, it) },
                            onRemove = { onRemovePoint(anchorIndex) },
                            // Удаление неактивно, когда в кривой осталась
                            // последняя (единственная) точка.
                            canRemove = points.size > 1
                        )
                    }
                }
            }
        }
        
    }
    
    val minCarrierTitle = stringResource(R.string.min_channel_frequency)
    val maxCarrierTitle = stringResource(R.string.max_channel_frequency)
    val frequencyLabel = stringResource(R.string.frequency_hz)
    val okLabel = stringResource(R.string.ok)
    val cancelLabel = stringResource(R.string.cancel)

    if (showRangeDialog) {
        AlertDialog(
            onDismissRequest = { showRangeDialog = false },
            title = { Text(if (editingRangeType == RangeType.MIN) minCarrierTitle else maxCarrierTitle) },
            text = {
                OutlinedTextField(
                    value = tempRangeValue,
                    onValueChange = { newValue ->
                        // Оставляем только цифры и одну десятичную точку
                        val filtered = buildString {
                            var dotSeen = false
                            for (ch in newValue.text) {
                                if (ch.isDigit()) append(ch)
                                else if (ch == '.' && !dotSeen) {
                                    append(ch)
                                    dotSeen = true
                                }
                            }
                        }
                        tempRangeValue = newValue.copy(
                            text = filtered,
                            selection = TextRange(filtered.length)
                        )
                    },
                    label = { Text(frequencyLabel) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val value = tempRangeValue.text.toFloatOrNull()
                    // Вместо молчаливого отказа при выходе за допустимые границы
                    // ЗАЖИМАЕМ значение в пределы: нижний предел не может быть
                    // меньше слышимого минимума (20 Гц), верхний — больше
                    // максимума (2000 Гц). Дополнительно не даём границам
                    // сблизиться ближе MIN_CARRIER_RANGE_SPAN_HZ.
                    if (value != null) {
                        if (editingRangeType == RangeType.MIN) {
                            // Меняем только нижний предел. Значения < 20 Гц
                            // зажимаются в 20, а не отвергаются молча; также не
                            // даём ему подойти к верхнему ближе
                            // MIN_CARRIER_RANGE_SPAN_HZ: [20 Гц, верхний − 100 Гц].
                            val newMin = value.coerceIn(
                                MIN_AUDIBLE_FREQUENCY,
                                carrierRange.max - MIN_CARRIER_RANGE_SPAN_HZ
                            )
                            onCarrierRangeChange(newMin, carrierRange.max)
                        } else {
                            // Меняем только верхний предел. Значения > 2000 Гц
                            // зажимаются в 2000, а не отвергаются молча; также
                            // не даём ему подойти к нижнему ближе 100 Гц:
                            // [нижний + 100 Гц, 2000 Гц].
                            val newMax = value.coerceIn(
                                carrierRange.min + MIN_CARRIER_RANGE_SPAN_HZ,
                                MAX_FREQUENCY
                            )
                            onCarrierRangeChange(carrierRange.min, newMax)
                        }
                    }
                    showRangeDialog = false
                }) { Text(okLabel) }
            },
            dismissButton = { TextButton(onClick = {
                showRangeDialog = false
            }) { Text(cancelLabel) } }
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
    // Одноточечная кривая — допустимое состояние (см. FrequencyCurve.require
    // points.size >= 1). buildBeatPaths для одной точки строит плоскую полосу
    // постоянной частоты, поэтому рисуем её наравне с многоточечными.
    val beatPaths = if (sortedPoints.isNotEmpty()) {
        buildBeatPaths(sortedPoints, params, interpolationType, splineTension, beatWeights)
    } else {
        null
    }

    // Пунктирная линия базовой кривой (через основные точки), когда включены
    // периоды расслабления и несущая реально снижается
    val showDashedBase = relaxationModeSettings.enabled &&
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

/**
 * Часовые метки по вертикальным линиям сетки. Формат — время суток
 * (например, «3:00»), конец суток — «23:59». Каждая метка центрируется
 * ровно ПО своей вертикальной линии, включая крайние (0:00 и 23:59) — они
 * могут чуть обрезаться у граней графика, но стоят ПОСЕРЕДИНЕ линии.
 * Рисуются ДО кривой — в фоне, под графиком и точками.
 */
private fun DrawScope.drawHourAxisLabels(
    width: Float,
    height: Float,
    paint: Paint,
    textColor: Color,
    bgColor: Color,
    bottomPx: Float
) {
    paint.textAlign = Paint.Align.LEFT
    val baseline = height - bottomPx
    val textSize = paint.textSize
    val padX = textSize * 0.3f
    val padY = textSize * 0.18f
    for (hour in 0..24 step 3) {
        val x = width * hour / 24
        // Метки — в формате времени суток. Последняя линия (час 24) —
        // это конец суток, поэтому показываем «23:59», а не «24:00».
        val text = if (hour >= 24) "23:59" else "%d:00".format(hour)
        val tw = paint.measureText(text)
        // Центрируем ровно по линии (включая крайние — без прижима к краям).
        val drawX = x - tw / 2f
        drawRoundRect(
            color = bgColor,
            topLeft = Offset(drawX - padX, baseline - textSize - padY),
            size = Size(tw + 2 * padX, textSize + 2 * padY),
            cornerRadius = CornerRadius(padY * 2f)
        )
        paint.color = android.graphics.Color.argb(
            (textColor.alpha * 255).toInt(),
            (textColor.red * 255).toInt(),
            (textColor.green * 255).toInt(),
            (textColor.blue * 255).toInt()
        )
        drawContext.canvas.nativeCanvas.drawText(text, drawX, baseline, paint)
    }
}

/**
 * Частотные метки по горизонтальным линиям сетки. Частота каждой линии
 * вычисляется из [GraphParams.yToCarrier] (т.е. из текущего carrierRange),
 * поэтому метки автоматически пересчитываются при смене границ графика
 * в пресете. Крайние линии (i=0 — MAX, i=4 — MIN) — границы диапазона:
 * их метки центрируются ПО линии и рисуются ЦВЕТОМ (как прежние боковые
 * метки границ), по нажатию на них открывается диалог смены границы.
 * Все метки рисуются ДО кривой — в фоне, под графиком.
 */
private fun DrawScope.drawFrequencyAxisLabels(
    width: Float,
    height: Float,
    graphParams: GraphParams,
    paint: Paint,
    edgeTextColor: Color,
    edgeBgColor: Color,
    midTextColor: Color,
    midBgColor: Color,
    hzFormat: String,
    leftPx: Float
) {
    paint.textAlign = Paint.Align.LEFT
    val textH = paint.textSize
    val padX = textH * 0.3f
    val padY = textH * 0.18f
    for (i in 0..4) {
        val y = height * i / 4
        val carrier = graphParams.yToCarrier(y)
        val text = hzFormat.format(carrier)
        val tw = paint.measureText(text)
        // Центрируем метку ровно ПО линии (включая крайние — без сдвига внутрь).
        val centerY = y
        val baseline = centerY + textH * 0.35f
        val isEdge = i == 0 || i == 4
        val textColor = if (isEdge) edgeTextColor else midTextColor
        val bgColor = if (isEdge) edgeBgColor else midBgColor
        drawRoundRect(
            color = bgColor,
            topLeft = Offset(leftPx - padX, baseline - textH - padY),
            size = Size(tw + 2 * padX, textH + 2 * padY),
            cornerRadius = CornerRadius(padY * 2f)
        )
        paint.color = android.graphics.Color.argb(
            (textColor.alpha * 255).toInt(),
            (textColor.red * 255).toInt(),
            (textColor.green * 255).toInt(),
            (textColor.blue * 255).toInt()
        )
        drawContext.canvas.nativeCanvas.drawText(text, leftPx, baseline, paint)
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
    indicatorColor: Color
) {
    val height = size.height
    val currentX = graphParams.timeToX(currentLocalTime)
    // Зону пересечения с графиком (яркий сегмент внутри полосы биений)
    // больше не рисуем — вертикальная линия текущего момента однородная
    // на всю высоту.
    val indicatorAlpha = 0.3f
    drawLine(
        color = indicatorColor.copy(alpha = indicatorAlpha),
        start = Offset(currentX, 0f),
        end = Offset(currentX, height),
        strokeWidth = 2f
    )
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

/**
 * Бейдж метки: всплывает над перетаскиваемым объектом — точкой графика или
 * ручкой скраба — и показывает его текущее время суток (при горизонтальном
 * перетаскивании) либо частоту несущей (при вертикальном). Единый вид для
 * обоих жестов (§запрос 2026-09-04): скраб и точка не должны расходиться в
 * оформлении бейджа.
 */
@Composable
private fun TimeLabelBadge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.inverseSurface,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.inverseOnSurface
            )
        }
    }
}

// ===== СКРАБ: ручка предпросмотра на указателе текущего момента =====
// docs/plan_playback_scrub_handle.md §5.2 (геометрия), §6 (состояние), §7 (жесты).

/** Чип ручки: 28×18 dp, скругление 8 dp. */
private val SCRUB_CHIP_WIDTH = 28.dp
private val SCRUB_CHIP_HEIGHT = 18.dp

/**
 * Тактильная зона 48×40 dp — заметно больше визуала и выступает на 8 dp выше
 * области графика (внутрь 8 dp полей карточки, поэтому не обрезается).
 */
private val SCRUB_TOUCH_WIDTH = 48.dp
private val SCRUB_TOUCH_HEIGHT = 40.dp

/** Отступ чипа от верхней грани области графика. */
private val SCRUB_CHIP_TOP = 3.dp

/** Залитый треугольник 7×9 dp. */
private val SCRUB_ARROW_WIDTH = 7.dp
private val SCRUB_ARROW_HEIGHT = 9.dp

/**
 * Прозрачность заливки треугольников ползунка (§14.9).
 *
 * Совпадает с прозрачностью ОСНОВНОЙ части линии воспроизведения
 * (`drawCurrentTimeIndicator`: вне полосы биений `indicatorColor.copy(0.3f)`).
 * Полоса биений ярче (0.5f) — она здесь не рисуется, поэтому берём 0.3f,
 * чтобы ползунок не выделялся ярче самой линии.
 */
private const val SCRUB_ARROW_ALPHA = 0.3f

/**
 * Запас вокруг тактильной зоны под тень. Тень рисуется ВНУТРИ `graphicsLayer`,
 * а слой обрезает отрисовку по своим границам: без этого запаса масштаб 1.15
 * срезал бы тень — ровно та же ловушка, что уже обойдена в `PointEditor.kt`.
 */
private val SCRUB_SHADOW_PAD = 8.dp

/** Масштаб чипа на захвате. */
private const val SCRUB_GRAB_SCALE = 1.15f

/**
 * Кнопка сброса: иконка 18 dp БЕЗ фона, тактильная зона 40 dp.
 *
 * Фона нет сознательно: кружок `surfaceContainerHighest` весил визуально
 * больше самого ползунка и спорил с графиком под ним. Цвет и прозрачность
 * кнопки — как у треугольников ручки ([SCRUB_ARROW_ALPHA]).
 */
private val SCRUB_RESET_ICON = 18.dp
private val SCRUB_RESET_TOUCH = 40.dp

/**
 * Зазор между НИЖНЕЙ гранью кнопки сброса и верхней гранью чипа ручки.
 *
 * Кнопка стоит НАД ручкой (§запрос 2026-09-04), а не сбоку от неё: сбоку она
 * отбирала захват у самой ручки. Верхняя грань чипа — [SCRUB_CHIP_TOP]
 * (3 dp), значит кнопка целиком лежит ВЫШЕ области графика — в полях
 * карточки и в зазоре между карточкой и полем названия пресета.
 */
private val SCRUB_RESET_GAP = 4.dp

/**
 * Слот под бейдж времени над ручкой.
 *
 * Бейдж НЕЛЬЗЯ класть внутрь тактильной зоны ручки: та шириной 48 dp, и
 * бейдж (~59 dp) ужался бы под неё — текст «14:30» не влез бы и перенёсся в
 * две строки, а высота обрезалась бы 40 dp зоны. У точки бейдж лежит в
 * свободном контейнере во всю область графика и потому рисуется в натуральную
 * величину — расхождение было хорошо заметно (§запрос 2026-09-04).
 *
 * Поэтому бейдж вынесен ОТДЕЛЬНЫМ ребёнком области графика, а этот слот
 * только позиционирует его: по центру ручки и сразу над кнопкой сброса.
 */
private val SCRUB_BUBBLE_SLOT_WIDTH = 160.dp

/** Ожидаемая высота бейджа (pad 8 + строка bodyLarge + pad 8). */
private val SCRUB_BUBBLE_SLOT_HEIGHT = 40.dp

/** Зазор между нижней гранью бейджа и верхней гранью кнопки сброса. */
private val SCRUB_BUBBLE_GAP = 2.dp

/**
 * Ручка скраба: чип из двух залитых треугольников ◀|▶ на верхнем конце
 * красной линии указателя, плюс кнопка возврата к реальному «сейчас».
 *
 * Звук перестраивается ОДИН раз, по отпускании ([onCommit]): каждый шаг жеста —
 * это полный хэндофф с кроссфейдом ~1 с, и обновление «вживую» превратило бы
 * звук в кашу. Пока палец идёт, звучит старая ось — это не ошибка, а норма:
 * решение ещё не принято.
 *
 * @param time линия, на которой стоит ручка (цель перетаскивания или ось).
 * @param showReset показывать ли кнопку сброса: воспроизведение идёт НЕ по
 *   текущему моменту. Считает вызывающий по геометрии (§14.10).
 */
@Composable
private fun BoxScope.ScrubHandle(
    time: LocalTime,
    showReset: Boolean,
    graphParams: GraphParams,
    indicatorColor: Color,
    // Реальное «сейчас» для «магнита» (§14.11 плана): ручка прилипает к нему,
    // если дотянуть ось в пределах ±15 мин. null — магнита нет (нет серой
    // линии, не к чему прилипать).
    realTimeOfDay: LocalTime?,
    onDrag: (LocalTime) -> Unit,
    onCommit: (LocalTime) -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit
) {
    val density = LocalDensity.current
    // px считаются один раз на density: внутри лямбд offset{} это был бы
    // пересчёт на каждом кадре.
    val chipWPx = with(density) { SCRUB_CHIP_WIDTH.roundToPx() }
    val chipHPx = with(density) { SCRUB_CHIP_HEIGHT.roundToPx() }
    val touchWPx = with(density) { SCRUB_TOUCH_WIDTH.roundToPx() }
    val touchHPx = with(density) { SCRUB_TOUCH_HEIGHT.roundToPx() }
    val padPx = with(density) { SCRUB_SHADOW_PAD.roundToPx() }
    val topPx = with(density) { SCRUB_CHIP_TOP.roundToPx() }
    val resetIconPx = with(density) { SCRUB_RESET_ICON.roundToPx() }
    val resetTouchPx = with(density) { SCRUB_RESET_TOUCH.roundToPx() }
    val resetGapPx = with(density) { SCRUB_RESET_GAP.roundToPx() }
    val slotWPx = with(density) { SCRUB_BUBBLE_SLOT_WIDTH.roundToPx() }
    val slotHPx = with(density) { SCRUB_BUBBLE_SLOT_HEIGHT.roundToPx() }
    val bubbleGapPx = with(density) { SCRUB_BUBBLE_GAP.roundToPx() }

    var dragging by remember { mutableStateOf(false) }
    // Цель жеста, ещё не зафиксированная. null — палец ни разу не сдвинулся.
    var pendingTarget by remember { mutableStateOf<LocalTime?>(null) }
    var startSeconds by remember { mutableStateOf(0) }
    var accumulatedPx by remember { mutableStateOf(0f) }
    // Жест читает АКТУАЛЬНОЕ время, но не пересоздаётся на каждом его
    // изменении: `pointerInput` ключуется только геометрией графика.
    val timeNow by rememberUpdatedState(time)
    // Реальное «сейчас» для «магнита» (§14.11): берём свежее на каждом кадре
    // жеста — иначе за время перетаскивания realTimeOfDay ушёл бы на секунду,
    // и граница магнита «дышала» бы.
    val realNow by rememberUpdatedState(realTimeOfDay)
    // Флаг: текущий жест дотянул ручку в магнитную зону — по отпускании делаем
    // сброс (onReset), а не скраб в замороженную секунду (§14.11).
    var snappedToNow by remember { mutableStateOf(false) }

    val handleDescription = stringResource(R.string.scrub_handle_description)

    val scale by animateFloatAsState(
        targetValue = if (dragging) SCRUB_GRAB_SCALE else 1f,
        label = "scrubHandleScale"
    )

    // Чип ВСЕГДА сидит ровно на линии воспроизведения — даже у самого края
    // графика, без клампа по половине чипа. Иначе у края чип «отрывается» от
    // линии и упирается в границу, а его внутренняя красная линия перестаёт быть
    // коллинеарной реальной (запрос на поведение ползунка у границ, 2026-09-03,
    // §14.6). Область графика без клипа, поэтому у крайних значений времени чип
    // чуть выступает в поля карточки — это плата за то, чтобы он был НА линии.
    // `time` сам уже в [0; 86399] (§14.5), так что centerX лежит в [0; widthPx].
    val halfChip = chipWPx / 2f
    val centerX = graphParams.timeToX(time)
    val touchLeft = (centerX - touchWPx / 2f).toInt()
    // Чип стоит на SCRUB_CHIP_TOP, зона — по его центру (отсюда выступ на 8 dp).
    val touchTop = (topPx + chipHPx / 2f - touchHPx / 2f).toInt()
    val chipTopInTouch = (touchHPx - chipHPx) / 2

    // Кнопка сброса: НАД ручкой, по центру линии воспроизведения.
    //
    // Нижняя грань иконки на [SCRUB_RESET_GAP] выше верхней грани чипа, то
    // есть кнопка целиком лежит ВЫШЕ области графика (chipTop = 3 dp, иконка
    // 18 dp → полоса −19…−1 dp). Рисуется оверлеем внутри того же Box-а,
    // поэтому НЕ ЗАНИМАЕТ МЕСТА В РАКЛАДКЕ: график не сдвигается вниз, и
    // никаких «дополнительных отступов сверху» не появляется (§запрос
    // 2026-09-04 — «над графиком, но без отступов»).
    //
    // По горизонтали — центр ручки, с клампом по ВИЗУАЛУ иконки (как раньше
    // клампился кружок): тактильная зона 40 dp может выступать за область
    // графика, визуал — нет.
    val resetHalf = resetIconPx / 2f
    val resetCenterX = centerX.coerceIn(
        resetHalf,
        (graphParams.widthPx - resetHalf).coerceAtLeast(resetHalf)
    )
    val resetIconTop = topPx - resetGapPx - resetIconPx
    val resetTouchLeft = (resetCenterX - resetTouchPx / 2f).toInt()
    val resetTouchTop = (resetIconTop + resetIconPx / 2f - resetTouchPx / 2f).toInt()

    // Слот бейджа: по центру ручки, НАД кнопкой сброса. Кламп по краям
    // графика — чтобы у 00:00 и 23:59 бейдж не уезжал за экран.
    val slotLeft = (centerX - slotWPx / 2f).coerceIn(
        0f,
        (graphParams.widthPx - slotWPx).coerceAtLeast(0).toFloat()
    )
    // Нижняя грань слота — на [SCRUB_BUBBLE_GAP] выше верхней грани иконки
    // сброса. Позиция НЕ зависит от showReset: иначе в момент, когда ось ещё
    // на «сейчас», бейдж при появлении кнопки подпрыгивал бы.
    val slotTop = resetIconTop - bubbleGapPx - slotHPx

    // Кнопка объявлена ДО ручки неслучайно: их тактильные зоны
    // ПЕРЕСЕКАЮТСЯ (ручка 40 dp, кнопка 40 dp, а центры разнесены всего на
    // 20 dp), и в зоне пересечения верх обязан забирать РУЧКА — она основное
    // взаимодействие. Объявленная после, кнопка съедала бы захват ручки чуть
    // в стороне от центра. Кнопке остаётся её верхняя непересекающаяся часть
    // тактильной зоны — около 22×40 dp.
    AnimatedVisibility(
        visible = showReset,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        label = "scrubResetButton"
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(resetTouchLeft, resetTouchTop) }
                .size(SCRUB_RESET_TOUCH)
                // Индикация нажатия оставлена (.clickable без indication=null):
                // кнопка без фона почти не видна, и без ряби нажатие на
                // прозрачную иконку нечем подтвердить.
                .clickable { onReset() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = stringResource(R.string.scrub_reset_description),
                modifier = Modifier.size(SCRUB_RESET_ICON),
                // Цвет и прозрачность — как у треугольников ручки (§14.9),
                // то есть та же альфа, что у основной части красной линии.
                tint = indicatorColor.copy(alpha = SCRUB_ARROW_ALPHA)
            )
        }
    }

    // Пузырь времени — только пока палец на ручке. В отпущенном состоянии о
    // сдвинутом положении говорят серая линия и кнопка сброса над ручкой.
    //
    // ВАЖНО: бейдж — ОТДЕЛЬНЫЙ ребёнок области графика, а не содержимое ручки.
    // Внутри тактильной зоны ручки (48×40 dp) он ужимался под её ширину, и
    // «14:30» не влезало в одну строку — визуально это расходилось с бейджем
    // точки, который лежит в свободном контейнере. Здесь слот 160 dp, бейдж
    // мерится в натуральную величину (§запрос 2026-09-04).
    if (dragging) {
        Box(
            modifier = Modifier
                .offset { IntOffset(slotLeft.roundToInt(), slotTop) }
                .size(SCRUB_BUBBLE_SLOT_WIDTH, SCRUB_BUBBLE_SLOT_HEIGHT),
            contentAlignment = Alignment.BottomCenter
        ) {
            TimeLabelBadge("%02d:%02d".format(time.hour, time.minute))
        }
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(touchLeft - padPx, touchTop - padPx) }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            // Размер — ВМЕСТЕ с запасом под тень, содержимое сжимает padding.
            .size(
                SCRUB_TOUCH_WIDTH + SCRUB_SHADOW_PAD * 2f,
                SCRUB_TOUCH_HEIGHT + SCRUB_SHADOW_PAD * 2f
            )
            .padding(SCRUB_SHADOW_PAD)
            .semantics { contentDescription = handleDescription }
            // Порядок как у DraggablePoint: `clickable` внешний и съедает тап,
            // иначе родительский onDoubleTap добавил бы точку под ручкой.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { /* сам тап ничего не делает — ручку двигает drag */ }
            .pointerInput(graphParams) {
                // ТОЛЬКО горизонталь: вертикальный свайп, начатый на ручке,
                // обязан уходить внешнему verticalScroll экрана.
                detectHorizontalDragGestures(
                    onDragStart = {
                        accumulatedPx = 0f
                        pendingTarget = null
                        startSeconds = timeNow.toSecondOfDay()
                        dragging = true
                        snappedToNow = false
                    },
                    onDragEnd = {
                        dragging = false
                        // СКРАБ: если дотянули до «сейчас» (магнит), это сброс,
                        // а не скраб в замороженную секунду. onReset() снимает и
                        // локальные цели жеста (см. передачу onReset), поэтому
                        // красная линия сразу ложится на ось = реальное «сейчас».
                        if (snappedToNow) {
                            onReset()
                        } else {
                            onCommit(pendingTarget ?: timeNow)
                        }
                        snappedToNow = false
                        pendingTarget = null
                    },
                    onDragCancel = {
                        // Палец ушёл за пределы или системный «назад»: звук
                        // не трогаем, решение не принято.
                        dragging = false
                        pendingTarget = null
                        onCancel()
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        // Накопление БЕЗ снапа: 1 px ≈ 80 с, и снап каждого шага
                        // давал бы рваные скачки по 5 минут. Снапится только
                        // отображаемое и фиксируемое значение.
                        // Накопление НЕ клампуется: dragAmount может быть
                        // отрицательным (тянуть влево), поэтому кламп накопления
                        // к [0; widthPx] ЗАПРЕЩЕН — он ломал движение назад
                        // (линия «не уходила» влево, только вправо). Граница
                        // суток держится КЛАМПОМ ВРЕМЕНИ в snappedTimeFromDrag
                        // (coerceIn 0..86399), а не круговой арифметикой, — так
                        // ручка упирается в край и не перескакивает на другую
                        // сторону (§14.5). Оверскролл за край ограничен самим
                        // временем, а не накоплением.
                        accumulatedPx += dragAmount
                        val target =
                            snappedTimeFromDrag(startSeconds, accumulatedPx, graphParams.widthPx)
                        // СКРАБ «магнит к сейчас» (§14.11): если цель в пределах
                        // ±15 мин от реального текущего момента — прилипаем к нему.
                        // Сравниваем с realNow (свежее realTimeOfDay через
                        // rememberUpdatedState), а не с осью: ось сама сдвинута
                        // скрабом, и «прилипать к ней» было бы бессмысленно.
                        val snap = realNow != null &&
                            abs(circularDiffSeconds(target, realNow!!)) <= SCRUB_SNAP_TO_NOW_SECONDS
                        val finalTarget = if (snap) realNow!! else target
                        snappedToNow = snap
                        pendingTarget = finalTarget
                        onDrag(finalTarget)
                    }
                )
            }
    ) {
        // Ползунок — ТОЛЬКО два залитых треугольника ◀|▶, без фона, обводки и
        // средней линии между ними (§14.9). Цвет и прозрачность — как у
        // основной части линии воспроизведения (SCRUB_ARROW_ALPHA).
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, chipTopInTouch) }
                .size(SCRUB_CHIP_WIDTH, SCRUB_CHIP_HEIGHT)
        ) {
            ScrubArrow(
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp),
                pointRight = false,
                color = indicatorColor.copy(alpha = SCRUB_ARROW_ALPHA)
            )
            ScrubArrow(
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
                pointRight = true,
                color = indicatorColor.copy(alpha = SCRUB_ARROW_ALPHA)
            )
        }

    }

}

/** Залитый треугольник ручки: [pointRight] = false — ◀, true — ▶. */
@Composable
private fun ScrubArrow(
    modifier: Modifier = Modifier,
    pointRight: Boolean,
    color: Color
) {
    Canvas(modifier = modifier.size(SCRUB_ARROW_WIDTH, SCRUB_ARROW_HEIGHT)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(0f, h / 2f)
            lineTo(w, 0f)
            lineTo(w, h)
            close()
        }
        rotate(if (pointRight) 180f else 0f) {
            drawPath(path, color)
        }
    }
}

/**
 * Время из перетаскивания ручки скраба: сдвиг от [startSeconds] в секундах,
 * снап к сетке 5 минут и кламп по краям суток [0; 86399]. Ручку НЕЛЬЗЯ увести
 * за полночь — за пределами графика она останавливается на границе, а не
 * переходит на противоположную сторону (запрос на граничное поведение,
 * 2026-09-03). Круговая нормализация убрана.
 */
/**
 * Допуск «ось доехала до цели скраба», секунды.
 *
 * Слагаемые: квантование оси до 60 с (§11.5 плана) плюс снап ручки к сетке
 * 5 минут — отпущенная «на серой линии» ручка всё равно даёт цель в пределах
 * ±2.5 минут от настоящего «сейчас». Итого 5 минут с запасом; это 0.35 %
 * ширины графика (2–3 px), то есть случай «скраб меньше допуска» неотличим
 * от «ось уже на месте», а вот ложное «ещё не доехала» исключено.
 */
private const val SCRUB_AXIS_SETTLE_TOL_SEC = 300

/**
 * Страховка от зависшей цели скраба, мс: кроссфейд ~1 с плюс опрос 1 Гц.
 * Если за это время ось не пришла, скраб не состоялся (звук встал, сменился
 * пресет) — красная линия обязана вернуться на ось, а не замереть на цели.
 */
private const val SCRUB_SETTLE_TIMEOUT_MS = 2500L

/**
 * Минимальный зазор между красной линией и серой, при котором серую ЕЩЁ
 * стоит рисовать, пиксели (§14.8 плана).
 *
 * Смысл порога — «красная линия уже на текущем моменте или ещё нет», и вопрос
 * этот чисто визуальный, поэтому порог в пикселях, а не в секундах: на узком
 * графике тот же час занимает меньше места, и секунды пришлось бы пересчитывать.
 *
 * 2 px — это ширина самой линии: при меньшем зазоре два штриха сливаются в
 * один и спорить не о чем. Сверху порог ограничен квантованием телеметрии
 * (60 с, §11.5): сутки на ~1000 px дают 0.7 px на минуту, так что «красная
 * на сейчас» (расхождение 0…60 с) устойчиво попадает в мёртвую зону, а
 * расхождение от трёх минут уже видно.
 */
private const val GHOST_LINE_MIN_GAP_PX = 2f

/**
 * СКРАБ: «магнит» к реальному «сейчас» при перетаскивании (§14.11 плана).
 * Если под палец уходит время в пределах ±15 минут от настоящего текущего
 * момента, ручка прилипает к нему — красная линия встаёт ровно на серую, а
 * по отпускании происходит сброс (offset = 0), а не скраб в замороженную
 * секунду. 15 минут — широкое, но ощутимое окно: дотянул близко к «сейчас»,
 * и линия сама «щёлкнула» на него.
 */
private const val SCRUB_SNAP_TO_NOW_SECONDS = 15 * 60

/**
 * Расстояние между красной линией ([preview]) и реальным «сейчас» ([real]) в
 * пикселях графика — мера того, «идёт ли воспроизведение по текущему моменту».
 *
 * Одна формула на двоих: по ней решается и видимость серой линии (§14.8), и
 * видимость кнопки сброса (§14.10). Разность берётся по кругу суток
 * ([circularDiffSeconds]): прямое сравнение координат сломалось бы у полуночи —
 * пара 23:59 / 00:01 дала бы всю ширину графика.
 */
private fun offNowGapPx(preview: LocalTime, real: LocalTime, graphWidthPx: Int): Float =
    abs(circularDiffSeconds(preview, real)) / (24f * 3600f) * graphWidthPx

/**
 * Кратчайшая разность двух моментов суток по кругу, секунды: результат всегда
 * в (−43200; 43200], то есть пригоден для сравнения с допуском.
 */
private fun circularDiffSeconds(a: LocalTime, b: LocalTime): Int {
    val raw = (a.toSecondOfDay() - b.toSecondOfDay()) % 86400
    return when {
        raw > 43200 -> raw - 86400
        raw < -43200 -> raw + 86400
        else -> raw
    }
}

private fun snappedTimeFromDrag(startSeconds: Int, dragPx: Float, graphWidthPx: Int): LocalTime {
    val stepSeconds = SCRUB_STEP_MINUTES * 60
    val raw = startSeconds + (dragPx * 24f * 3600f / graphWidthPx)
    val snapped = round(raw / stepSeconds) * stepSeconds
    // Кламп по краям суток, БЕЗ круговой арифметики: ручка скраба не должна
    // «перескакивать» через полночь на противоположный край графика. За
    // пределами [0; 86399] она останавливается на границе (00:00 или 23:59:59).
    // Круговой обход убран по запросу на граничное поведение (2026-09-03).
    val clamped = snapped.coerceIn(0.0f, 86399.0f)
    return LocalTime.fromSecondOfDay(clamped.toInt())
}

// Шаг перемещения по времени (в минутах) — для точек
private const val TIME_STEP_MINUTES = 5

// Шаг перемещения скраб-ручки (в минутах) — тоньше, чем у точек
private const val SCRUB_STEP_MINUTES = 1

/**
 * Какую границу несущей правит диалог: нижнюю ([MIN]) или верхнюю ([MAX]).
 * Метки оси Y открывают диалог по отдельности, поэтому диалогу нужно знать,
 * какую именно границу он сейчас меняет. Алгоритма смещения меток нет —
 * позиции фиксированы, диалог просто подставляет текущее значение границы.
 */
private enum class RangeType { MIN, MAX }

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

