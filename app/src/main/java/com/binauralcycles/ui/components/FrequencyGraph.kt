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
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.binaural.core.audio.model.FrequencyPoint
import com.binauralcycles.R
import com.binaural.core.audio.model.FrequencyRange
import com.binaural.core.audio.model.InterpolationType
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
private const val MIN_AUDIBLE_FREQUENCY = 20.0

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
    val startCarrier: Double = 0.0,
    val currentTime: LocalTime? = null,
    val currentCarrier: Double = 0.0
)

/**
 * Вычисляет максимальную частоту биений для заданной несущей частоты
 * Формула: carrierFrequency * 2 - 20 (гарантирует, что обе боковые частоты останутся в слышимом диапазоне >= 20 Гц)
 */
fun maxBeatForCarrier(carrierFrequency: Double): Double {
    return (carrierFrequency * 2 - MIN_AUDIBLE_FREQUENCY).coerceAtLeast(0.0)
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
    val carrierRangeSize: Double get() = (carrierRange.max - carrierRange.min).coerceAtLeast(50.0)
    val maxBeat: Double get() = beatRange.max
    
    fun timeToX(time: LocalTime): Float {
        val seconds = time.toSecondOfDay()
        return (seconds / (24.0 * 3600) * widthPx).toFloat()
    }
    
    fun carrierToY(carrier: Double): Float {
        return heightPx - ((carrier - carrierRange.min) / carrierRangeSize * heightPx).toFloat()
    }
    
    fun xToTime(x: Float): LocalTime {
        val seconds = (x / widthPx * 24 * 3600).toInt().coerceIn(0, 86399)
        return LocalTime.fromSecondOfDay(seconds)
    }
    
    fun yToCarrier(y: Float): Double {
        val carrier = carrierRange.min + (1.0 - y / heightPx) * carrierRangeSize
        return carrierRange.clamp(kotlin.math.round(carrier))
    }
    
    /**
     * Вычисляет Y-координату верхней границы области биений
     * Верхняя граница соответствует частоте канала: carrier + beat/2
     */
    fun beatUpperY(carrier: Double, beat: Double): Float {
        val upperFrequency = carrier + beat / 2
        return carrierToY(upperFrequency)
    }
    
    /**
     * Вычисляет Y-координату нижней границы области биений
     * Нижняя граница соответствует частоте канала: carrier - beat/2
     */
    fun beatLowerY(carrier: Double, beat: Double): Float {
        val lowerFrequency = carrier - beat / 2
        return carrierToY(lowerFrequency)
    }
}

@Composable
fun FrequencyGraph(
    points: List<FrequencyPoint>,
    selectedPointIndex: Int?,
    currentCarrierFrequency: Double,
    currentBeatFrequency: Double,
    carrierRange: FrequencyRange,
    beatRange: FrequencyRange,
    interpolationType: InterpolationType = InterpolationType.LINEAR,
    isPlaying: Boolean,
    onPointSelected: (Int) -> Unit,
    onPointTimeChanged: (Int, LocalTime) -> Unit,
    onPointCarrierChanged: (Int, Double) -> Unit,
    onAddPoint: (LocalTime, Double, Double) -> Unit,
    onCarrierRangeChange: (Double, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val sortedPoints = points.sortedBy { it.time.toSecondOfDay() }
    val currentTime = remember { mutableStateOf(LocalTime(12, 0)) }
    var dragState by remember { mutableStateOf(PointDragState()) }
    var showRangeDialog by remember { mutableStateOf(false) }
    var editingRangeType by remember { mutableStateOf<RangeType?>(null) }
    var tempRangeValue by remember { mutableStateOf("") }
    
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (true) {
                kotlinx.coroutines.delay(5000)
                val now = Clock.System.now()
                val zone = TimeZone.currentSystemDefault()
                currentTime.value = now.toLocalDateTime(zone).time
            }
        }
    }
    
    LaunchedEffect(Unit) {
        val now = Clock.System.now()
        currentTime.value = now.toLocalDateTime(TimeZone.currentSystemDefault()).time
    }
    
    val currentLocalTime = currentTime.value
    val density = LocalDensity.current

    // Используем кэшированные sortedPoints если доступны (оптимизация)
    val displayPoints = remember(points) { points.sortedBy { it.time.toSecondOfDay() } }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(16.dp)
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
        ) {
            val widthPx = with(density) { maxWidth.roundToPx() }
            val heightPx = with(density) { maxHeight.roundToPx() }
            val graphParams = remember(widthPx, heightPx, carrierRange.min, carrierRange.max, beatRange.min, beatRange.max) {
                GraphParams(widthPx, heightPx, carrierRange, beatRange)
            }

            val primaryColor = MaterialTheme.colorScheme.primary

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawGraphContent(
                            sortedPoints = displayPoints,
                            graphParams = graphParams,
                            currentLocalTime = currentLocalTime,
                            currentCarrierFrequency = currentCarrierFrequency,
                            currentBeatFrequency = currentBeatFrequency,
                            primaryColor = primaryColor,
                            interpolationType = interpolationType
                        )
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { offset ->
                                // Добавляем точку при двойном нажатии
                                val time = graphParams.xToTime(offset.x)
                                val carrier = graphParams.yToCarrier(offset.y)
                                onAddPoint(time, carrier, beatRange.min)
                            }
                        )
                    }
            ) {
                displayPoints.forEachIndexed { sortedIndex, point ->
                    val originalIndex = points.indexOf(point)
                    val isSelected = selectedPointIndex == originalIndex
                    
                    val prevPoint = displayPoints.getOrNull(sortedIndex - 1)
                    val nextPoint = displayPoints.getOrNull(sortedIndex + 1)
                    
                    val minTimeSeconds = prevPoint?.time?.toSecondOfDay()?.plus(60) ?: 0
                    val maxTimeSeconds = nextPoint?.time?.toSecondOfDay()?.minus(60) ?: (24 * 3600 - 60)
                    
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
                        onDragUpdate = { index, newTime, newCarrier, direction ->
                            dragState = dragState.copy(
                                direction = direction,
                                currentTime = newTime,
                                currentCarrier = newCarrier
                            )
                        },
                        onDragEnd = { index, newTime, newCarrier, direction ->
                            if (direction == DragDirection.HORIZONTAL) {
                                onPointTimeChanged(index, newTime)
                            } else if (direction == DragDirection.VERTICAL) {
                                onPointCarrierChanged(index, newCarrier)
                            }
                            dragState = PointDragState()
                        }
                    )
                }
                
                if (dragState.startIndex >= 0 && dragState.currentTime != null) {
                    val previewXPx = graphParams.timeToX(dragState.currentTime!!)
                    val previewYPx = graphParams.carrierToY(dragState.currentCarrier)
                    
                    Box(modifier = Modifier.offset { IntOffset(previewXPx.toInt() - 50, previewYPx.toInt() - 160) }) {
                        Surface(color = MaterialTheme.colorScheme.inverseSurface, shape = RoundedCornerShape(8.dp)) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = when (dragState.direction) {
                                        DragDirection.HORIZONTAL -> "%02d:%02d".format(dragState.currentTime!!.hour, dragState.currentTime!!.minute)
                                        DragDirection.VERTICAL -> "%.0f Гц".format(dragState.currentCarrier)
                                        DragDirection.NONE -> "%02d:%02d / %.0f Гц".format(dragState.currentTime!!.hour, dragState.currentTime!!.minute, dragState.currentCarrier)
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
                    Text("%.0f".format(carrierRange.max), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = primaryColor, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                }
                Spacer(modifier = Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(4.dp), color = primaryColor.copy(alpha = 0.1f),
                    modifier = Modifier.clickable { editingRangeType = RangeType.MIN; tempRangeValue = "%.0f".format(carrierRange.min); showRangeDialog = true }
                ) {
                    Text("%.0f Гц".format(carrierRange.min), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = primaryColor, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                }
            }

            // Ось X - отметки каждые 3 часа
            Row(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("0ч", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("3ч", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("6ч", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("9ч", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("12ч", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("15ч", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("18ч", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("21ч", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("24ч", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            
            Text("%02d:%02d".format(currentLocalTime.hour, currentLocalTime.minute), style = MaterialTheme.typography.labelSmall, color = Color.Red, modifier = Modifier.align(Alignment.TopEnd))
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
                    val value = tempRangeValue.toDoubleOrNull()
                    if (value != null && value >= MIN_AUDIBLE_FREQUENCY) {
                        val newMin = if (editingRangeType == RangeType.MIN) value else carrierRange.min
                        val newMax = if (editingRangeType == RangeType.MAX) value else carrierRange.max
                        if (newMin < newMax) onCarrierRangeChange(newMin, newMax)
                    }
                    showRangeDialog = false
                }) { Text(okLabel) }
            },
            dismissButton = { TextButton(onClick = { showRangeDialog = false }) { Text(cancelLabel) } }
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGraphContent(
    sortedPoints: List<FrequencyPoint>,
    graphParams: GraphParams,
    currentLocalTime: LocalTime,
    currentCarrierFrequency: Double,
    currentBeatFrequency: Double,
    primaryColor: Color,
    interpolationType: InterpolationType
) {
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
    
    if (sortedPoints.size >= 2) {
        drawBeatArea(sortedPoints, graphParams, primaryColor, interpolationType)
        drawCarrierLine(sortedPoints, graphParams, primaryColor, interpolationType)
    }
    
    val currentX = graphParams.timeToX(currentLocalTime)
    val currentCarrierY = graphParams.carrierToY(currentCarrierFrequency)
    val currentUpperY = graphParams.beatUpperY(currentCarrierFrequency, currentBeatFrequency)
    val currentLowerY = graphParams.beatLowerY(currentCarrierFrequency, currentBeatFrequency)
    
    drawLine(color = Color.Red.copy(alpha = 0.7f), start = Offset(currentX, 0f), end = Offset(currentX, height), strokeWidth = 2f)
    drawCircle(color = Color.Red, radius = 8f, center = Offset(currentX, currentCarrierY))
    // Вертикальная линия показывающая диапазон частот каналов (от lower до upper)
    drawLine(color = Color.Red.copy(alpha = 0.5f), start = Offset(currentX, currentUpperY), end = Offset(currentX, currentLowerY), strokeWidth = 3f)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBeatArea(
    sortedPoints: List<FrequencyPoint>,
    params: GraphParams,
    primaryColor: Color,
    interpolationType: InterpolationType
) {
    val width = size.width
    val numSamples = 200 // Больше сэмплов для более гладких кривых
    
    val upperPath = Path()
    val lowerPath = Path()
    
    // Начинаем с левой границы
    val startTime = LocalTime.fromSecondOfDay(0)
    val startCarrier = interpolateCarrierFrequency(sortedPoints, startTime, interpolationType)
    val startBeat = interpolateBeatFrequency(sortedPoints, startTime, interpolationType)
    val startUpperY = params.beatUpperY(startCarrier, startBeat)
    val startLowerY = params.beatLowerY(startCarrier, startBeat)
    
    upperPath.moveTo(0f, startUpperY)
    lowerPath.moveTo(0f, startLowerY)
    
    // Проходим по всему графику с интерполяцией
    for (i in 1..numSamples) {
        val t = i.toDouble() / numSamples
        val time = LocalTime.fromSecondOfDay((t * 24 * 3600).toInt().coerceAtMost(86399))
        val carrier = interpolateCarrierFrequency(sortedPoints, time, interpolationType)
        val beat = interpolateBeatFrequency(sortedPoints, time, interpolationType)
        val upperY = params.beatUpperY(carrier, beat)
        val lowerY = params.beatLowerY(carrier, beat)
        val x = (t * width).toFloat()
        upperPath.lineTo(x, upperY)
        lowerPath.lineTo(x, lowerY)
    }
    
    // Замыкаем путь для заливки
    val combinedPath = Path()
    combinedPath.addPath(upperPath)
    
    // Обратный путь по нижней границе
    for (i in numSamples downTo 0) {
        val t = i.toDouble() / numSamples
        val time = LocalTime.fromSecondOfDay((t * 24 * 3600).toInt().coerceAtMost(86399))
        val carrier = interpolateCarrierFrequency(sortedPoints, time, interpolationType)
        val beat = interpolateBeatFrequency(sortedPoints, time, interpolationType)
        val lowerY = params.beatLowerY(carrier, beat)
        val x = (t * width).toFloat()
        combinedPath.lineTo(x, lowerY)
    }
    
    combinedPath.close()
    
    // Заливка области биений
    drawPath(path = combinedPath, color = primaryColor.copy(alpha = 0.2f), style = Fill)
    // Границы области
    drawPath(path = upperPath, color = primaryColor.copy(alpha = 0.4f), style = Stroke(width = 1f))
    drawPath(path = lowerPath, color = primaryColor.copy(alpha = 0.4f), style = Stroke(width = 1f))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCarrierLine(
    sortedPoints: List<FrequencyPoint>,
    params: GraphParams,
    primaryColor: Color,
    interpolationType: InterpolationType
) {
    val width = size.width
    val numSamples = 200 // Больше сэмплов для более гладких кривых
    val carrierPath = Path()
    
    // Начинаем с левой границы (время 0)
    val startTime = LocalTime.fromSecondOfDay(0)
    val startCarrier = interpolateCarrierFrequency(sortedPoints, startTime, interpolationType)
    val startY = params.carrierToY(startCarrier)
    carrierPath.moveTo(0f, startY)
    
    // Рисуем кривую с интерполяцией по всем сэмплам
    for (i in 1..numSamples) {
        val t = i.toDouble() / numSamples
        val time = LocalTime.fromSecondOfDay((t * 24 * 3600).toInt().coerceAtMost(86399))
        val carrier = interpolateCarrierFrequency(sortedPoints, time, interpolationType)
        val y = params.carrierToY(carrier)
        val x = (t * width).toFloat()
        carrierPath.lineTo(x, y)
    }
    
    drawPath(path = carrierPath, color = primaryColor, style = Stroke(width = 3f))
}

private enum class RangeType { MIN, MAX }

@Composable
fun DraggablePoint(
    xPx: Float,
    yPx: Float,
    isSelected: Boolean,
    point: FrequencyPoint,
    maxBeat: Double,
    originalIndex: Int,
    carrierRange: FrequencyRange,
    minTimeSeconds: Int,
    maxTimeSeconds: Int,
    graphWidthPx: Int,
    graphHeightPx: Int,
    primaryColor: Color,
    onPointSelected: (Int) -> Unit,
    onDragStart: (Int, LocalTime, Double) -> Unit,
    onDragUpdate: (Int, LocalTime, Double, DragDirection) -> Unit,
    onDragEnd: (Int, LocalTime, Double, DragDirection) -> Unit
) {
    val density = LocalDensity.current
    
    var totalDragX by remember { mutableStateOf(0f) }
    var totalDragY by remember { mutableStateOf(0f) }
    var currentDragDirection by remember { mutableStateOf(DragDirection.NONE) }
    var hasDirectionDetermined by remember { mutableStateOf(false) }
    var startSeconds by remember { mutableStateOf(0) }
    var startCarrier by remember { mutableStateOf(0.0) }
    
    val pointSize = if (isSelected) 30.dp else 24.dp
    val halfSizePx = with(density) { (pointSize / 2).roundToPx() }
    
    Box(
        modifier = Modifier
            .offset { IntOffset((xPx - halfSizePx).toInt(), (yPx - halfSizePx).toInt()) }
            .size(pointSize)
            .background(if (isSelected) primaryColor else primaryColor.copy(alpha = 0.7f), CircleShape)
            .border(2.dp, Color.White, CircleShape)
            .clickable { onPointSelected(originalIndex) }
            .pointerInput(originalIndex) {
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
        val beatIndicatorSize = with(density) { ((point.beatFrequency / maxBeat) * 12).toFloat().toDp().coerceAtLeast(4.dp) }
        Box(modifier = Modifier.size(beatIndicatorSize).background(Color.White.copy(alpha = 0.6f), CircleShape).align(Alignment.Center))
    }
}

private fun calculateTimeFromDrag(startSeconds: Int, dragX: Float, minSeconds: Int, maxSeconds: Int, graphWidth: Float): LocalTime {
    val newSeconds = (startSeconds + (dragX * 24 * 3600 / graphWidth).toInt()).coerceIn(minSeconds, maxSeconds)
    return LocalTime.fromSecondOfDay(newSeconds)
}

private fun calculateCarrierFromDrag(startCarrier: Double, dragY: Float, carrierRange: FrequencyRange, graphHeight: Float): Double {
    return carrierRange.clamp(kotlin.math.round(startCarrier - dragY * (carrierRange.max - carrierRange.min) / graphHeight))
}

// Функции интерполяции
fun interpolateCarrierFrequency(points: List<FrequencyPoint>, time: LocalTime, interpolationType: InterpolationType = InterpolationType.LINEAR): Double = 
    interpolateFrequency(points, time, interpolationType) { it.carrierFrequency }

fun interpolateBeatFrequency(points: List<FrequencyPoint>, time: LocalTime, interpolationType: InterpolationType = InterpolationType.LINEAR): Double = 
    interpolateFrequency(points, time, interpolationType) { it.beatFrequency }

fun interpolateFrequency(
    points: List<FrequencyPoint>, 
    time: LocalTime, 
    interpolationType: InterpolationType = InterpolationType.LINEAR,
    frequencySelector: (FrequencyPoint) -> Double
): Double {
    val sortedPoints = points.sortedBy { it.time.toSecondOfDay() }
    if (sortedPoints.isEmpty()) return 0.0
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
    frequencySelector: (FrequencyPoint) -> Double,
    interpolationType: InterpolationType,
    isWrapping: Boolean
): Double {
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
    
    val ratio = (t - t1).toDouble() / (t2 - t1)
    
    return when (interpolationType) {
        InterpolationType.LINEAR -> {
            frequencySelector(leftPoint) + ratio * (frequencySelector(rightPoint) - frequencySelector(leftPoint))
        }
        InterpolationType.CUBIC_SPLINE -> {
            // Для Catmull-Rom нужны 4 точки с учётом перехода через полночь
            val p0 = getNeighborPoint(sortedPoints, leftIndex, -1, frequencySelector, isWrapping)
            val p1 = frequencySelector(leftPoint)
            val p2 = frequencySelector(rightPoint)
            val p3 = getNeighborPoint(sortedPoints, rightIndex, +1, frequencySelector, isWrapping)
            
            val result = catmullRomInterpolate(p0, p1, p2, p3, ratio)
            
            // Ограничиваем результат минимальным значением 0 для частоты биений
            result.coerceAtLeast(0.0)
        }
    }
}

/**
 * Получить соседнюю точку с учётом цикличности графика и перехода через полночь
 * @param isWrapping true, если текущий интервал переходит через полночь
 */
private fun getNeighborPoint(
    points: List<FrequencyPoint>,
    currentIndex: Int,
    offset: Int,
    frequencySelector: (FrequencyPoint) -> Double,
    isWrapping: Boolean = false
): Double {
    val neighborIndex = currentIndex + offset
    
    // Обработка границ массива
    return when {
        neighborIndex < 0 -> {
            // Если идём влево от первой точки
            if (isWrapping) {
                // При переходе через полночь берём последнюю точку
                frequencySelector(points.last())
            } else {
                // Иначе берём первую точку (clamp)
                frequencySelector(points.first())
            }
        }
        neighborIndex >= points.size -> {
            // Если идём вправо от последней точки
            if (isWrapping) {
                // При переходе через полночь берём первую точку
                frequencySelector(points.first())
            } else {
                // Иначе берём последнюю точку (clamp)
                frequencySelector(points.last())
            }
        }
        else -> {
            frequencySelector(points[neighborIndex])
        }
    }
}

/**
 * Интерполяция Catmull-Rom
 * Использует 4 точки для создания плавной кривой
 * Важно: может давать значения за пределами [p1, p2], поэтому требуется clamp
 */
private fun catmullRomInterpolate(p0: Double, p1: Double, p2: Double, p3: Double, t: Double): Double {
    val t2 = t * t
    val t3 = t2 * t
    
    // Формула Catmull-Rom сплайна
    return 0.5 * (
        (2.0 * p1) +
        (-p0 + p2) * t +
        (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2 +
        (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3
    )
}
