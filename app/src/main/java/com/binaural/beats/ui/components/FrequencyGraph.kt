package com.binaural.beats.ui.components

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.binaural.core.audio.model.FrequencyPoint
import com.binaural.core.audio.model.FrequencyRange
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs

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
 */
fun maxBeatForCarrier(carrierFrequency: Double): Double {
    return 2.0 * (carrierFrequency - MIN_AUDIBLE_FREQUENCY).coerceAtLeast(0.0)
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
    
    fun beatWidth(beat: Double): Float {
        return (beat / maxBeat * heightPx * 0.2).toFloat()
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
            val graphParams = remember(widthPx, heightPx, carrierRange, beatRange) {
                GraphParams(widthPx, heightPx, carrierRange, beatRange)
            }

            val primaryColor = MaterialTheme.colorScheme.primary
            
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawGraphContent(
                            sortedPoints = sortedPoints,
                            graphParams = graphParams,
                            currentLocalTime = currentLocalTime,
                            currentCarrierFrequency = currentCarrierFrequency,
                            currentBeatFrequency = currentBeatFrequency,
                            primaryColor = primaryColor
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
                sortedPoints.forEachIndexed { sortedIndex, point ->
                    val originalIndex = points.indexOf(point)
                    val isSelected = selectedPointIndex == originalIndex
                    
                    val prevPoint = sortedPoints.getOrNull(sortedIndex - 1)
                    val nextPoint = sortedPoints.getOrNull(sortedIndex + 1)
                    
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
                    
                    Box(modifier = Modifier.offset { IntOffset(previewXPx.toInt() - 30, previewYPx.toInt() - 75) }) {
                        Surface(color = MaterialTheme.colorScheme.inverseSurface, shape = RoundedCornerShape(4.dp)) {
                            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = when (dragState.direction) {
                                        DragDirection.HORIZONTAL -> "%02d:%02d".format(dragState.currentTime!!.hour, dragState.currentTime!!.minute)
                                        DragDirection.VERTICAL -> "%.0f Гц".format(dragState.currentCarrier)
                                        DragDirection.NONE -> "%02d:%02d / %.0f Гц".format(dragState.currentTime!!.hour, dragState.currentTime!!.minute, dragState.currentCarrier)
                                    },
                                    style = MaterialTheme.typography.labelMedium,
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
    
    if (showRangeDialog) {
        AlertDialog(
            onDismissRequest = { showRangeDialog = false },
            title = { Text(if (editingRangeType == RangeType.MIN) "Мин. несущая частота" else "Макс. несущая частота") },
            text = {
                OutlinedTextField(value = tempRangeValue, onValueChange = { tempRangeValue = it }, label = { Text("Частота (Гц)") }, singleLine = true)
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
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showRangeDialog = false }) { Text("Отмена") } }
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGraphContent(
    sortedPoints: List<FrequencyPoint>,
    graphParams: GraphParams,
    currentLocalTime: LocalTime,
    currentCarrierFrequency: Double,
    currentBeatFrequency: Double,
    primaryColor: Color
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
        drawBeatArea(sortedPoints, graphParams, primaryColor)
        drawCarrierLine(sortedPoints, graphParams, primaryColor)
    }
    
    val currentX = graphParams.timeToX(currentLocalTime)
    val currentCarrierY = graphParams.carrierToY(currentCarrierFrequency)
    val currentBeatWidth = graphParams.beatWidth(currentBeatFrequency)
    
    drawLine(color = Color.Red.copy(alpha = 0.7f), start = Offset(currentX, 0f), end = Offset(currentX, height), strokeWidth = 2f)
    drawCircle(color = Color.Red, radius = 8f, center = Offset(currentX, currentCarrierY))
    drawLine(color = Color.Red.copy(alpha = 0.5f), start = Offset(currentX - currentBeatWidth, currentCarrierY), end = Offset(currentX + currentBeatWidth, currentCarrierY), strokeWidth = 3f)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBeatArea(
    sortedPoints: List<FrequencyPoint>,
    params: GraphParams,
    primaryColor: Color
) {
    val width = size.width
    val numSamples = 100
    
    val first = sortedPoints.first()
    val last = sortedPoints.last()
    
    val firstSeconds = first.time.toSecondOfDay()
    val lastSeconds = last.time.toSecondOfDay()
    
    // Расстояние между last и проекцией first (через границу 24ч)
    val wrapDuration = firstSeconds + 24 * 3600 - lastSeconds
    val wrapRatio = if (wrapDuration > 0) (24.0 * 3600 - lastSeconds) / wrapDuration else 0.0
    
    // Интерполированные значения на границах
    val lastCarrierY = params.carrierToY(last.carrierFrequency)
    val firstCarrierY = params.carrierToY(first.carrierFrequency)
    val lastBeatWidth = params.beatWidth(last.beatFrequency)
    val firstBeatWidth = params.beatWidth(first.beatFrequency)
    
    // Интерполируем и несущую частоту, и ширину биений на границах
    val carrierAtBoundary = lastCarrierY + wrapRatio.toFloat() * (firstCarrierY - lastCarrierY)
    val beatAtBoundary = lastBeatWidth + wrapRatio.toFloat() * (firstBeatWidth - lastBeatWidth)
    
    val upperPath = Path()
    val lowerPath = Path()
    
    // Начинаем с левой границы с интерполированными значениями
    upperPath.moveTo(0f, carrierAtBoundary - beatAtBoundary)
    lowerPath.moveTo(0f, carrierAtBoundary + beatAtBoundary)
    
    // Проходим по всему графику
    for (i in 0..numSamples) {
        val t = i.toDouble() / numSamples
        val time = LocalTime.fromSecondOfDay((t * 24 * 3600).toInt().coerceAtMost(86399))
        val beatWidth = params.beatWidth(interpolateBeatFrequency(sortedPoints, time))
        val x = (t * width).toFloat()
        val carrierY = params.carrierToY(interpolateCarrierFrequency(sortedPoints, time))
        upperPath.lineTo(x, carrierY - beatWidth)
        lowerPath.lineTo(x, carrierY + beatWidth)
    }
    
    // Заканчиваем на правой границе с интерполированными значениями
    upperPath.lineTo(width, carrierAtBoundary - beatAtBoundary)
    lowerPath.lineTo(width, carrierAtBoundary + beatAtBoundary)
    
    // Замыкаем путь
    val combinedPath = Path()
    combinedPath.addPath(upperPath)
    
    for (i in numSamples downTo 0) {
        val t = i.toDouble() / numSamples
        val time = LocalTime.fromSecondOfDay((t * 24 * 3600).toInt().coerceAtMost(86399))
        val beatWidth = params.beatWidth(interpolateBeatFrequency(sortedPoints, time))
        val x = (t * width).toFloat()
        val carrierY = params.carrierToY(interpolateCarrierFrequency(sortedPoints, time))
        combinedPath.lineTo(x, carrierY + beatWidth)
    }
    
    combinedPath.lineTo(0f, carrierAtBoundary + beatAtBoundary)
    combinedPath.close()
    
    drawPath(path = combinedPath, color = primaryColor.copy(alpha = 0.2f), style = Fill)
    drawPath(path = upperPath, color = primaryColor.copy(alpha = 0.4f), style = Stroke(width = 1f))
    drawPath(path = lowerPath, color = primaryColor.copy(alpha = 0.4f), style = Stroke(width = 1f))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCarrierLine(
    sortedPoints: List<FrequencyPoint>,
    params: GraphParams,
    primaryColor: Color
) {
    val width = size.width
    val carrierPath = Path()
    
    val first = sortedPoints.first()
    val last = sortedPoints.last()
    
    val firstX = params.timeToX(first.time)
    val firstY = params.carrierToY(first.carrierFrequency)
    val lastX = params.timeToX(last.time)
    val lastY = params.carrierToY(last.carrierFrequency)
    
    // Вычисляем интерполированное значение на границах графика
    // Левая граница (время 0): интерполируем между проекцией last (время - 24ч) и first
    // Правая граница (время 24ч): интерполируем между last и проекцией first (время + 24ч)
    val firstSeconds = first.time.toSecondOfDay()
    val lastSeconds = last.time.toSecondOfDay()
    
    // Расстояние между last и проекцией first (через границу 24ч)
    val wrapDuration = firstSeconds + 24 * 3600 - lastSeconds
    
    // Интерполяция на границах (значения должны быть одинаковыми из-за периодичности)
    val wrapRatio = if (wrapDuration > 0) (24.0 * 3600 - lastSeconds) / wrapDuration else 0.0
    val yAtBoundary = lastY + wrapRatio.toFloat() * (firstY - lastY)
    
    // Начинаем с левой границы с интерполированным значением
    carrierPath.moveTo(0f, yAtBoundary)
    carrierPath.lineTo(firstX, firstY)
    
    // Проходим через все точки
    for (point in sortedPoints) {
        carrierPath.lineTo(params.timeToX(point.time), params.carrierToY(point.carrierFrequency))
    }
    
    // Заканчиваем на правой границе с интерполированным значением
    carrierPath.lineTo(width, yAtBoundary)
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
            .pointerInput(originalIndex, point.time, point.carrierFrequency, minTimeSeconds, maxTimeSeconds, graphWidthPx, graphHeightPx, carrierRange) {
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
                        
                        when (currentDragDirection) {
                            DragDirection.HORIZONTAL -> onDragUpdate(originalIndex, calculateTimeFromDrag(startSeconds, totalDragX, minTimeSeconds, maxTimeSeconds, graphWidthPx.toFloat()), startCarrier, DragDirection.HORIZONTAL)
                            DragDirection.VERTICAL -> onDragUpdate(originalIndex, point.time, calculateCarrierFromDrag(startCarrier, totalDragY, carrierRange, graphHeightPx.toFloat()), DragDirection.VERTICAL)
                            DragDirection.NONE -> onDragUpdate(originalIndex, calculateTimeFromDrag(startSeconds, totalDragX, minTimeSeconds, maxTimeSeconds, graphWidthPx.toFloat()), calculateCarrierFromDrag(startCarrier, totalDragY, carrierRange, graphHeightPx.toFloat()), DragDirection.NONE)
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
fun interpolateCarrierFrequency(points: List<FrequencyPoint>, time: LocalTime): Double = interpolateFrequency(points, time) { it.carrierFrequency }
fun interpolateBeatFrequency(points: List<FrequencyPoint>, time: LocalTime): Double = interpolateFrequency(points, time) { it.beatFrequency }

fun interpolateFrequency(points: List<FrequencyPoint>, time: LocalTime, frequencySelector: (FrequencyPoint) -> Double): Double {
    val sortedPoints = points.sortedBy { it.time.toSecondOfDay() }
    if (sortedPoints.isEmpty()) return 0.0
    if (sortedPoints.size == 1) return frequencySelector(sortedPoints[0])
    
    val targetSeconds = time.toSecondOfDay()
    
    for (i in 0 until sortedPoints.size - 1) {
        val current = sortedPoints[i]
        val next = sortedPoints[i + 1]
        if (targetSeconds in current.time.toSecondOfDay()..next.time.toSecondOfDay()) {
            val t1 = current.time.toSecondOfDay()
            val t2 = next.time.toSecondOfDay()
            val ratio = if (t2 != t1) (targetSeconds - t1).toDouble() / (t2 - t1) else 0.0
            return frequencySelector(current) + ratio * (frequencySelector(next) - frequencySelector(current))
        }
    }
    
    val first = sortedPoints.first()
    val last = sortedPoints.last()
    
    return if (targetSeconds > last.time.toSecondOfDay()) {
        val t1 = last.time.toSecondOfDay()
        val t2 = first.time.toSecondOfDay() + 24 * 3600
        val ratio = (targetSeconds - t1).toDouble() / (t2 - t1)
        frequencySelector(last) + ratio * (frequencySelector(first) - frequencySelector(last))
    } else {
        val t1 = last.time.toSecondOfDay() - 24 * 3600
        val t2 = first.time.toSecondOfDay()
        val ratio = (targetSeconds - t1).toDouble() / (t2 - t1)
        frequencySelector(last) + ratio * (frequencySelector(first) - frequencySelector(last))
    }
}