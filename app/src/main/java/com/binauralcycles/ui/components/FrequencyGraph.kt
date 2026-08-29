package com.binauralcycles.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.binaural.core.audio.model.FrequencyMath
import com.binaural.core.audio.model.FrequencyPoint
import com.binaural.core.audio.model.FrequencyRange
import com.binaural.core.audio.model.Interpolation
import com.binaural.core.audio.model.InterpolationType
import com.binaural.core.audio.model.RelaxationMode
import com.binaural.core.audio.model.RelaxationModeSettings
import com.binauralcycles.R
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.cos

// Порог для определения направления перетаскивания
private const val DRAG_DIRECTION_THRESHOLD = 10f

// Минимальная слышимая частота
private const val MIN_AUDIBLE_FREQUENCY = 20.0f

// Максимальная частота для графика
private const val MAX_FREQUENCY = 2000.0f

/**
 * Направление перетаскивания
 */
enum class DragDirection {
    NONE, HORIZONTAL, VERTICAL
}

/**
 * Состояние перетаскивания точки
 */
private data class PointDragState(
    val direction: DragDirection = DragDirection.NONE,
    val startIndex: Int = -1,
    val startTime: LocalTime? = null,
    val startCarrier: Float = 0.0f,
    val startBeat: Float = 0.0f,
    val currentTime: LocalTime? = null,
    val currentCarrier: Float = 0.0f,
    val currentBeat: Float = 0.0f
)

/**
 * Вычисляет максимальную частоту биений для заданной несущей частоты
 * Формула: (carrierFrequency - 20) * 2 (гарантирует, что нижняя боковая частота останется >= 20 Гц)
 */
private fun maxBeatForCarrier(carrierFrequency: Float): Float {
    return ((carrierFrequency - MIN_AUDIBLE_FREQUENCY) * 2).coerceAtLeast(0.0f)
}

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
    // НОВОЕ: внешнее время (например, виртуальное из uiState). null => свои часы.
    externalCurrentTime: LocalTime? = null,
    modifier: Modifier = Modifier
) {
    // УДАЛЕНО: `val sortedPoints = points.sortedBy { ... }` вычислялось на каждой
    // рекомпозиции (3-4 раза в секунду при воспроизведении) и нигде не
    // использовалось — в drawBehind передаётся allPoints. Отсортированный список
    // берётся из remember ниже (displayPoints).
    var dragState by remember { mutableStateOf(PointDragState()) }
    var showRangeDialog by remember { mutableStateOf(false) }
    var editingRangeType by remember { mutableStateOf<RangeType?>(null) }
    var tempRangeValue by remember { mutableStateOf("") }

    // Локализованный формат Гц - объявляем здесь для использования во всём компоненте
    val hzFormat = stringResource(R.string.hz_value_format)

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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(16.dp)
    ) {
        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            val widthPx = with(density) { maxWidth.roundToPx() }
            val heightPx = with(density) { maxHeight.roundToPx() }
            val graphParams = remember(widthPx, heightPx, carrierRange.min, carrierRange.max, beatRange.min, beatRange.max) {
                GraphParams(widthPx, heightPx, carrierRange, beatRange)
            }

            val primaryColor = MaterialTheme.colorScheme.primary
            val errorColor = MaterialTheme.colorScheme.error

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
                    relaxationModeSettings = relaxationModeSettings
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
                                // Предел модуля — ГЕОМЕТРИЧЕСКИЙ (хранимый beatRange
                                // не участвует: он задаёт только масштаб маркеров).
                                // Границы графика учитываются, чтобы новая точка
                                // не провалилась ниже минимума частот пресета —
                                // ровно так же её клампит addEditingPoint.
                                val interpolatedBeat = if (displayPoints.size >= 2) {
                                    val (leftFreq, rightFreq) = Interpolation.interpolateChannels(
                                        displayPoints, time, interpolationType, splineTension,
                                        presorted = true
                                    )
                                    FrequencyMath.clampBeat(
                                        carrier,
                                        kotlin.math.round(rightFreq - leftFreq),
                                        carrierRange = carrierRange
                                    )
                                } else {
                                    // 0 или 1 точка: берём частоту биений единственной точки
                                    displayPoints.firstOrNull()?.let {
                                        FrequencyMath.clampBeat(
                                            carrier, it.beatFrequency, carrierRange = carrierRange)
                                    } ?: 0.0f
                                }
                                onAddPoint(time, carrier, interpolatedBeat)
                            }
                        )
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
                        onDragStart = { index, time, carrier, beat ->
                            dragState = PointDragState(
                                direction = DragDirection.NONE,
                                startIndex = index,
                                startTime = time,
                                startCarrier = carrier,
                                startBeat = beat,
                                currentTime = time,
                                currentCarrier = carrier,
                                currentBeat = beat
                            )
                        },
                        onDragUpdate = { index, newTime, newCarrier, newBeat, direction ->
                            dragState = dragState.copy(
                                direction = direction,
                                currentTime = newTime,
                                currentCarrier = newCarrier,
                                currentBeat = newBeat
                            )
                        },
                        onDragEnd = { index, newTime, newCarrier, newBeat, direction ->
                            if (direction == DragDirection.HORIZONTAL) {
                                onPointTimeChanged(index, newTime)
                            } else if (direction == DragDirection.VERTICAL) {
                                onPointCarrierChanged(index, newCarrier)
                                // Если частота биения была скорректирована
                                if (newBeat != dragState.startBeat) {
                                    onPointBeatChanged(index, newBeat)
                                }
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

            // Ось Y
            Column(modifier = Modifier.align(Alignment.CenterStart).offset(x = (-8).dp)) {
                Surface(shape = RoundedCornerShape(4.dp), color = primaryColor.copy(alpha = 0.1f),
                    modifier = Modifier.clickable { editingRangeType = RangeType.MAX; tempRangeValue = "%.0f".format(carrierRange.max); showRangeDialog = true }
                ) {
                    Text(hzFormat.format(carrierRange.max), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = primaryColor, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                }
                Spacer(modifier = Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(4.dp), color = primaryColor.copy(alpha = 0.1f),
                    modifier = Modifier.clickable { editingRangeType = RangeType.MIN; tempRangeValue = "%.0f".format(carrierRange.min); showRangeDialog = true }
                ) {
                    Text(hzFormat.format(carrierRange.min), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = primaryColor, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                }
            }
        }
        
        // Ось X - отметки каждые 3 часа (ниже графика)
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("3", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("6", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("9", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("12", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("15", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("18", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("21", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("24", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    
    val hzLabel = stringResource(R.string.hz)
    val minCarrierTitle = stringResource(R.string.min_carrier_frequency)
    val maxCarrierTitle = stringResource(R.string.max_carrier_frequency)
    val frequencyLabel = stringResource(R.string.frequency_hz)
    val okLabel = stringResource(R.string.ok)
    val cancelLabel = stringResource(R.string.cancel)
    
    if (showRangeDialog) {
        AlertDialog(
            onDismissRequest = { showRangeDialog = false },
            title = { Text(if (editingRangeType == RangeType.MIN) minCarrierTitle else maxCarrierTitle) },
            text = {
                OutlinedTextField(value = tempRangeValue, onValueChange = { tempRangeValue = it }, label = { Text(frequencyLabel) }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    val value = tempRangeValue.toFloatOrNull()
                    if (value != null && value >= MIN_AUDIBLE_FREQUENCY) {
                        val newMin = if (editingRangeType == RangeType.MIN) value else carrierRange.min
                        // Ограничиваем максимум значением 2000 Гц
                        val newMax = if (editingRangeType == RangeType.MAX) value.coerceAtMost(MAX_FREQUENCY) else carrierRange.max
                        if (newMin < newMax) onCarrierRangeChange(newMin, newMax)
                    }
                    showRangeDialog = false
                }) { Text(okLabel) }
            },
            dismissButton = { TextButton(onClick = { showRangeDialog = false }) { Text(cancelLabel) } }
        )
    }
}

private typealias DrawScope = androidx.compose.ui.graphics.drawscope.DrawScope

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
    relaxationModeSettings: RelaxationModeSettings
): GraphStaticPaths {
    val beatPaths = if (sortedPoints.size >= 2) {
        buildBeatPaths(sortedPoints, params, interpolationType, splineTension)
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
        buildDashedBaseCurvePath(realPoints, params, interpolationType, splineTension)
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
    splineTension: Float = 0.0f
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
        sortedPoints, startTime, interpolationType, splineTension, presorted = true
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
                    sortedPoints, time, interpolationType, splineTension, presorted = true
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
                sortedPoints, time, interpolationType, splineTension, presorted = true
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

private enum class RangeType { MIN, MAX }

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
    splineTension: Float
): Path {
    val width = params.widthPx.toFloat()
    val carrierPath = Path()
    
    // Начинаем с левой границы (время 0)
    val startTime = LocalTime.fromSecondOfDay(0)
    val startCarrier = interpolateCarrierFrequency(
        realPoints, startTime, interpolationType, splineTension, presorted = true
    )
    val startY = params.carrierToY(startCarrier)
    carrierPath.moveTo(0f, startY)
    
    // Динамическое количество сэмплов
    val numSamples = (realPoints.size * 2).coerceAtLeast(300)
    for (i in 1..numSamples) {
        val t = i.toDouble() / numSamples
        val time = LocalTime.fromSecondOfDay((t * 24 * 3600).toInt().coerceAtMost(86399))
        val carrier = interpolateCarrierFrequency(
            realPoints, time, interpolationType, splineTension, presorted = true
        )
        val y = params.carrierToY(carrier)
        val x = (t * width).toFloat()
        carrierPath.lineTo(x, y)
    }

    return carrierPath
}

/**
 * Вычисляет максимальную частоту биений для верхней границы (2000 Гц).
 * Формула: carrier + beat/2 <= MAX_FREQUENCY => beat <= 2 * (MAX_FREQUENCY - carrier)
 */
private fun maxBeatForUpperLimit(carrierFrequency: Float): Float {
    return ((MAX_FREQUENCY - carrierFrequency) * 2).coerceAtLeast(0.0f)
}

/**
 * Вычисляет скорректированную частоту биения для заданной несущей частоты.
 * Учитывает обе границы: нижнюю (20 Гц) и верхнюю (2000 Гц).
 *
 * ЗНАК СОХРАНЯЕТСЯ: клампится только МОДУЛЬ (beat = right − left — величина
 * знаковая; при смене знака боковые частоты просто меняются местами, поэтому
 * ограничение симметрично):
 *   |beat| <= 2 * (carrier − 20 Гц)    — нижняя боковая >= 20 Гц;
 *   |beat| <= 2 * (2000 Гц − carrier)  — верхняя боковая <= 2000 Гц.
 */
fun adjustBeatForCarrier(carrier: Float, currentBeat: Float): Float {
    val maxBeatForLower = maxBeatForCarrier(carrier)  // для нижней границы (20 Гц)
    val maxBeatForUpper = maxBeatForUpperLimit(carrier)  // для верхней границы (2000 Гц)
    val maxBeat = minOf(maxBeatForLower, maxBeatForUpper).coerceAtLeast(0.0f)
    return currentBeat.coerceIn(-maxBeat, maxBeat)
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
    onDragStart: (Int, LocalTime, Float, Float) -> Unit,
    onDragUpdate: (Int, LocalTime, Float, Float, DragDirection) -> Unit,
    onDragEnd: (Int, LocalTime, Float, Float, DragDirection) -> Unit
) {
    val density = LocalDensity.current
    
    var totalDragX by remember { mutableStateOf(0f) }
    var totalDragY by remember { mutableStateOf(0f) }
    var currentDragDirection by remember { mutableStateOf(DragDirection.NONE) }
    var hasDirectionDetermined by remember { mutableStateOf(false) }
    var startSeconds by remember { mutableStateOf(0) }
    var startCarrier by remember { mutableStateOf(0.0f) }
    var startBeat by remember { mutableStateOf(0.0f) }
    
    val pointSize = if (isSelected) 30.dp else 24.dp
    val halfSizePx = with(density) { (pointSize / 2).roundToPx() }
    
    Box(
        modifier = Modifier
            .offset { IntOffset((xPx - halfSizePx).toInt(), (yPx - halfSizePx).toInt()) }
            .size(pointSize)
            .background(if (isSelected) primaryColor else primaryColor.copy(alpha = 0.7f), CircleShape)
            .border(2.dp, Color.White, CircleShape)
            .clickable { onPointSelected(originalIndex) }
            .pointerInput(originalIndex, point.time, point.carrierFrequency, point.beatFrequency) {
                detectDragGestures(
                    onDragStart = { _ ->
                        totalDragX = 0f; totalDragY = 0f
                        currentDragDirection = DragDirection.NONE
                        hasDirectionDetermined = false
                        startSeconds = point.time.toSecondOfDay()
                        startCarrier = point.carrierFrequency
                        startBeat = point.beatFrequency
                        onDragStart(originalIndex, point.time, point.carrierFrequency, point.beatFrequency)
                    },
                    onDragEnd = {
                        val newTime = calculateTimeFromDrag(startSeconds, totalDragX, minTimeSeconds, maxTimeSeconds, graphWidthPx.toFloat())
                        val newCarrier = calculateCarrierFromDrag(startCarrier, totalDragY, carrierRange, graphHeightPx.toFloat())
                        val adjustedBeat = adjustBeatForCarrier(newCarrier, startBeat)
                        onDragEnd(originalIndex, newTime, newCarrier, adjustedBeat, currentDragDirection)
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
                        val adjustedBeat = adjustBeatForCarrier(newCarrier, startBeat)
                        when (currentDragDirection) {
                            DragDirection.HORIZONTAL -> onDragUpdate(originalIndex, newTime, startCarrier, startBeat, DragDirection.HORIZONTAL)
                            DragDirection.VERTICAL -> onDragUpdate(originalIndex, point.time, newCarrier, adjustedBeat, DragDirection.VERTICAL)
                            DragDirection.NONE -> onDragUpdate(originalIndex, newTime, newCarrier, adjustedBeat, DragDirection.NONE)
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

private fun calculateCarrierFromDrag(startCarrier: Float, dragY: Float, carrierRange: FrequencyRange, graphHeight: Float): Float {
    return carrierRange.clamp(kotlin.math.round(startCarrier - dragY * (carrierRange.max - carrierRange.min) / graphHeight))
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
    presorted: Boolean = false
): Float = interpolateFrequency(
    points, time, interpolationType, splineTension, presorted
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
    frequencySelector: (FrequencyPoint) -> Float
): Float {
    val sortedPoints = if (presorted) points else points.sortedBy { it.time.toSecondOfDay() }
    if (sortedPoints.isEmpty()) return 0.0f
    if (sortedPoints.size == 1) return frequencySelector(sortedPoints[0])
    
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
            isWrapping = true
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
        isWrapping = false
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
    isWrapping: Boolean
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
    
    // Используем общий объект интерполяции с параметром tension
    return Interpolation.interpolate(interpolationType, p0, p1, p2, p3, ratio, splineTension)
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

