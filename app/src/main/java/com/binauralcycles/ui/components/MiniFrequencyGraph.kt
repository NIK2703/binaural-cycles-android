package com.binauralcycles.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.binaural.core.audio.model.FrequencyCurve
import com.binaural.core.audio.model.FrequencyPoint
import com.binaural.core.audio.model.FrequencyRange
import com.binaural.core.audio.model.Interpolation
import com.binaural.core.audio.model.InterpolationType
import com.binaural.core.audio.model.RelaxationModeSettings
import kotlinx.datetime.LocalTime
import android.graphics.Paint

/**
 * Кэшированные данные графика для оптимизации отрисовки
 */
private data class CachedGraphPaths(
    val carrierPath: Path,
    val upperBeatPath: Path,
    val lowerBeatPath: Path,
    val combinedBeatPath: Path,
    val gridLines: FloatArray,
    val verticalLines: FloatArray,
    val pointPositions: FloatArray,
    val labeltexts: List<String>,
    val isRelaxationMode: Boolean
)

/**
 * Параметры мини-графика
 */
private data class MiniGraphParams(
    val widthPx: Int,
    val heightPx: Int,
    val carrierRange: FrequencyRange,
    val maxBeat: Float
) {
    val carrierRangeSize: Float get() = (carrierRange.max - carrierRange.min).coerceAtLeast(50.0f)
    
    fun timeToX(time: LocalTime): Float {
        val seconds = time.toSecondOfDay()
        return (seconds / (24.0f * 3600f) * widthPx)
    }
    
    fun carrierToY(carrier: Float): Float {
        return heightPx - ((carrier - carrierRange.min) / carrierRangeSize * heightPx)
    }
    
    fun beatUpperY(carrier: Float, beat: Float): Float {
        val upperFrequency = carrier + beat / 2.0f
        return carrierToY(upperFrequency)
    }
    
    fun beatLowerY(carrier: Float, beat: Float): Float {
        val lowerFrequency = carrier - beat / 2.0f
        return carrierToY(lowerFrequency)
    }
}

/**
 * Мини-график частот для отображения в списке пресетов
 */
@Composable
fun MiniFrequencyGraph(
    frequencyCurve: FrequencyCurve,
    modifier: Modifier = Modifier,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    indicatorColor: Color = MaterialTheme.colorScheme.error,
    relaxationColor: Color = MaterialTheme.colorScheme.tertiary,
    isPlaying: Boolean = false,
    currentTime: LocalTime = LocalTime(12, 0),
    currentCarrierFrequency: Float = 0.0f,
    currentBeatFrequency: Float = 0.0f,
    relaxationModeSettings: RelaxationModeSettings = RelaxationModeSettings()
) {
    val density = LocalDensity.current
    
    val sortedPoints = remember(frequencyCurve.points) {
        frequencyCurve.points.sortedBy { it.time.toSecondOfDay() }
    }
    
    val virtualPoints = remember(frequencyCurve.points, relaxationModeSettings, frequencyCurve.interpolationType, frequencyCurve.splineTension) {
        createRelaxationVirtualPoints(frequencyCurve.points, relaxationModeSettings, frequencyCurve.interpolationType, frequencyCurve.splineTension)
    }
    
    val carrierRange = frequencyCurve.carrierRange
    
    val maxBeat = remember(frequencyCurve.points, virtualPoints, relaxationModeSettings) {
        val maxFromPoints = frequencyCurve.points.maxOfOrNull { it.beatFrequency } ?: 20.0f
        val maxFromVirtual = if (relaxationModeSettings.enabled && virtualPoints.isNotEmpty()) {
            virtualPoints.maxOfOrNull { it.beatFrequency } ?: 0.0f
        } else {
            0.0f
        }
        maxOf(maxFromPoints, maxFromVirtual).coerceAtLeast(1.0f)
    }
    
    var widthPx by remember { mutableIntStateOf(0) }
    var heightPx by remember { mutableIntStateOf(0) }
    
    val labelPaint = remember {
        Paint().apply {
            textSize = 18f
            isAntiAlias = true
        }
    }
    
    val axisPaint = remember {
        Paint().apply {
            textSize = 24f
            isAntiAlias = true
            isFakeBoldText = true
        }
    }
    
    val graphParams = remember(widthPx, heightPx, carrierRange, maxBeat) {
        if (widthPx > 0 && heightPx > 0) {
            MiniGraphParams(widthPx, heightPx, carrierRange, maxBeat)
        } else {
            null
        }
    }
    
    val cachedPaths = remember(
        sortedPoints,
        virtualPoints,
        graphParams,
        frequencyCurve.interpolationType,
        frequencyCurve.splineTension,
        relaxationModeSettings
    ) {
        if (graphParams != null) {
            computeGraphPaths(
                sortedPoints, 
                graphParams, 
                frequencyCurve.interpolationType, 
                frequencyCurve.splineTension,
                virtualPoints,
                relaxationModeSettings
            )
        } else {
            null
        }
    }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                widthPx = size.width
                heightPx = size.height
            }
            .drawBehind {
                val paths = cachedPaths ?: return@drawBehind
                val params = graphParams ?: return@drawBehind
                
                drawCachedGraph(
                    cachedPaths = paths,
                    primaryColor = primaryColor,
                    indicatorColor = indicatorColor,
                    relaxationColor = relaxationColor,
                    sortedPoints = sortedPoints,
                    virtualPoints = virtualPoints,
                    graphParams = params,
                    maxBeat = maxBeat,
                    isPlaying = isPlaying,
                    currentTime = currentTime,
                    currentCarrierFrequency = currentCarrierFrequency,
                    currentBeatFrequency = currentBeatFrequency,
                    relaxationModeSettings = relaxationModeSettings,
                    labelPaint = labelPaint,
                    axisPaint = axisPaint,
                    carrierRange = carrierRange
                )
            }
    )
}

private fun createRelaxationVirtualPoints(
    points: List<FrequencyPoint>,
    relaxationModeSettings: RelaxationModeSettings,
    interpolationType: InterpolationType,
    splineTension: Float
): List<FrequencyPoint> {
    if (!relaxationModeSettings.enabled || points.size < 2) return emptyList()
    
    val sortedPoints = points.sortedBy { it.time.toSecondOfDay() }
    val virtualPoints = mutableListOf<FrequencyPoint>()
    
    val carrierReduction = relaxationModeSettings.carrierReductionPercent / 100.0f
    val beatReduction = relaxationModeSettings.beatReductionPercent / 100.0f
    
    for (i in 0 until sortedPoints.size) {
        val currentPoint = sortedPoints[i]
        val nextPoint = sortedPoints[(i + 1) % sortedPoints.size]
        
        val currentTimeSeconds = currentPoint.time.toSecondOfDay()
        var nextTimeSeconds = nextPoint.time.toSecondOfDay()
        
        if (nextTimeSeconds <= currentTimeSeconds) {
            nextTimeSeconds += 24 * 3600
        }
        
        val midTimeSeconds = (currentTimeSeconds + nextTimeSeconds) / 2
        val midTime = LocalTime.fromSecondOfDay(midTimeSeconds % (24 * 3600))
        
        val midCarrier = interpolateCarrierFrequencyMini(sortedPoints, midTime, interpolationType, splineTension)
        val midBeat = interpolateBeatFrequencyMini(sortedPoints, midTime, interpolationType, splineTension)
        
        val relaxedCarrier = midCarrier * (1.0f - carrierReduction)
        val relaxedBeat = midBeat * (1.0f - beatReduction)
        
        virtualPoints.add(
            FrequencyPoint(
                time = midTime,
                carrierFrequency = relaxedCarrier,
                beatFrequency = relaxedBeat
            )
        )
    }
    
    return virtualPoints
}

private fun computeGraphPaths(
    sortedPoints: List<FrequencyPoint>,
    params: MiniGraphParams,
    interpolationType: InterpolationType,
    splineTension: Float,
    virtualPoints: List<FrequencyPoint>,
    relaxationModeSettings: RelaxationModeSettings
): CachedGraphPaths {
    val width = params.widthPx.toFloat()
    val height = params.heightPx.toFloat()
    
    val gridLines = FloatArray(3) { (height * (it + 1) / 4) }
    val verticalLines = FloatArray(7) { (width * (it + 1) * 3 / 24) }
    
    val pointPositions = FloatArray(sortedPoints.size * 2)
    val labelTexts = mutableListOf<String>()
    
    sortedPoints.forEachIndexed { index, point ->
        val x = params.timeToX(point.time)
        val y = params.carrierToY(point.carrierFrequency)
        pointPositions[index * 2] = x
        pointPositions[index * 2 + 1] = y
        
        val beatStr = if (point.beatFrequency == point.beatFrequency.toLong().toFloat()) {
            point.beatFrequency.toLong().toString()
        } else {
            point.beatFrequency.toString()
        }
        labelTexts.add("%.0f(%s)".format(point.carrierFrequency, beatStr))
    }
    
    if (sortedPoints.size < 2) {
        return CachedGraphPaths(
            carrierPath = Path(),
            upperBeatPath = Path(),
            lowerBeatPath = Path(),
            combinedBeatPath = Path(),
            gridLines = gridLines,
            verticalLines = verticalLines,
            pointPositions = pointPositions,
            labeltexts = labelTexts,
            isRelaxationMode = false
        )
    }
    
    val pointsForInterpolation = if (relaxationModeSettings.enabled && virtualPoints.isNotEmpty()) {
        (sortedPoints + virtualPoints).sortedBy { it.time.toSecondOfDay() }
    } else {
        sortedPoints
    }
    
    val carrierPath = computeCarrierPath(pointsForInterpolation, params, interpolationType, splineTension)
    val (upperPath, lowerPath, combinedPath) = computeBeatPaths(pointsForInterpolation, params, interpolationType, splineTension)
    
    return CachedGraphPaths(
        carrierPath = carrierPath,
        upperBeatPath = upperPath,
        lowerBeatPath = lowerPath,
        combinedBeatPath = combinedPath,
        gridLines = gridLines,
        verticalLines = verticalLines,
        pointPositions = pointPositions,
        labeltexts = labelTexts,
        isRelaxationMode = relaxationModeSettings.enabled && virtualPoints.isNotEmpty()
    )
}

private fun computeCarrierPath(
    sortedPoints: List<FrequencyPoint>,
    params: MiniGraphParams,
    interpolationType: InterpolationType,
    splineTension: Float
): Path {
    val carrierPath = Path()
    val width = params.widthPx.toFloat()
    
    val startTime = LocalTime.fromSecondOfDay(0)
    val startCarrier = interpolateCarrierFrequencyMini(sortedPoints, startTime, interpolationType, splineTension)
    val startY = params.carrierToY(startCarrier)
    carrierPath.moveTo(0f, startY)
    
    if (interpolationType == InterpolationType.STEP) {
        val firstPointX = params.timeToX(sortedPoints.first().time)
        val lastCarrierY = params.carrierToY(sortedPoints.last().carrierFrequency)
        
        carrierPath.lineTo(firstPointX, lastCarrierY)
        
        for (i in 0 until sortedPoints.size) {
            val currentPoint = sortedPoints[i]
            val nextPoint = sortedPoints.getOrNull(i + 1) ?: sortedPoints.first()
            
            val currentX = params.timeToX(currentPoint.time)
            val nextX = if (i == sortedPoints.size - 1) width else params.timeToX(nextPoint.time)
            
            val currentCarrierY = params.carrierToY(currentPoint.carrierFrequency)
            
            carrierPath.lineTo(currentX, currentCarrierY)
            carrierPath.lineTo(nextX, currentCarrierY)
        }
    } else {
        val numSamples = 100
        for (i in 1..numSamples) {
            val t = i.toFloat() / numSamples
            val time = LocalTime.fromSecondOfDay((t * 24 * 3600).toInt().coerceAtMost(86399))
            val carrier = interpolateCarrierFrequencyMini(sortedPoints, time, interpolationType, splineTension)
            val y = params.carrierToY(carrier)
            val x = t * width
            carrierPath.lineTo(x, y)
        }
    }
    
    return carrierPath
}

private fun computeBeatPaths(
    sortedPoints: List<FrequencyPoint>,
    params: MiniGraphParams,
    interpolationType: InterpolationType,
    splineTension: Float
): Triple<Path, Path, Path> {
    val width = params.widthPx.toFloat()
    val numSamples = 100
    
    val upperPath = Path()
    val lowerPath = Path()
    
    val startTime = LocalTime.fromSecondOfDay(0)
    val startCarrier = interpolateCarrierFrequencyMini(sortedPoints, startTime, interpolationType, splineTension)
    val startBeat = interpolateBeatFrequencyMini(sortedPoints, startTime, interpolationType, splineTension)
    
    upperPath.moveTo(0f, params.beatUpperY(startCarrier, startBeat))
    lowerPath.moveTo(0f, params.beatLowerY(startCarrier, startBeat))
    
    if (interpolationType == InterpolationType.STEP) {
        val firstPointX = params.timeToX(sortedPoints.first().time)
        val lastPoint = sortedPoints.last()
        
        upperPath.lineTo(firstPointX, params.beatUpperY(lastPoint.carrierFrequency, lastPoint.beatFrequency))
        lowerPath.lineTo(firstPointX, params.beatLowerY(lastPoint.carrierFrequency, lastPoint.beatFrequency))
        
        for (i in 0 until sortedPoints.size) {
            val currentPoint = sortedPoints[i]
            val nextPoint = sortedPoints.getOrNull(i + 1) ?: sortedPoints.first()
            
            val currentX = params.timeToX(currentPoint.time)
            val nextX = if (i == sortedPoints.size - 1) width else params.timeToX(nextPoint.time)
            
            val upperY = params.beatUpperY(currentPoint.carrierFrequency, currentPoint.beatFrequency)
            val lowerY = params.beatLowerY(currentPoint.carrierFrequency, currentPoint.beatFrequency)
            
            upperPath.lineTo(currentX, upperY)
            upperPath.lineTo(nextX, upperY)
            lowerPath.lineTo(currentX, lowerY)
            lowerPath.lineTo(nextX, lowerY)
        }
    } else {
        for (i in 1..numSamples) {
            val t = i.toFloat() / numSamples
            val time = LocalTime.fromSecondOfDay((t * 24 * 3600).toInt().coerceAtMost(86399))
            val carrier = interpolateCarrierFrequencyMini(sortedPoints, time, interpolationType, splineTension)
            val beat = interpolateBeatFrequencyMini(sortedPoints, time, interpolationType, splineTension)
            val x = t * width
            upperPath.lineTo(x, params.beatUpperY(carrier, beat))
            lowerPath.lineTo(x, params.beatLowerY(carrier, beat))
        }
    }
    
    val combinedPath = Path()
    combinedPath.addPath(upperPath)
    
    for (i in numSamples downTo 0) {
        val t = i.toFloat() / numSamples
        val time = LocalTime.fromSecondOfDay((t * 24 * 3600).toInt().coerceAtMost(86399))
        val carrier = interpolateCarrierFrequencyMini(sortedPoints, time, interpolationType, splineTension)
        val beat = interpolateBeatFrequencyMini(sortedPoints, time, interpolationType, splineTension)
        val x = t * width
        combinedPath.lineTo(x, params.beatLowerY(carrier, beat))
    }
    combinedPath.close()
    
    return Triple(upperPath, lowerPath, combinedPath)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCachedGraph(
    cachedPaths: CachedGraphPaths,
    primaryColor: Color,
    indicatorColor: Color,
    relaxationColor: Color,
    sortedPoints: List<FrequencyPoint>,
    virtualPoints: List<FrequencyPoint>,
    graphParams: MiniGraphParams,
    maxBeat: Float,
    isPlaying: Boolean,
    currentTime: LocalTime,
    currentCarrierFrequency: Float,
    currentBeatFrequency: Float,
    relaxationModeSettings: RelaxationModeSettings,
    labelPaint: Paint,
    axisPaint: Paint,
    carrierRange: FrequencyRange
) {
    val width = size.width
    val height = size.height
    val gridColor = primaryColor.copy(alpha = 0.1f)
    
    for (y in cachedPaths.gridLines) {
        drawLine(
            color = gridColor,
            start = Offset(0f, y),
            end = Offset(width, y),
            strokeWidth = 0.5f
        )
    }
    
    for (x in cachedPaths.verticalLines) {
        drawLine(
            color = gridColor,
            start = Offset(x, 0f),
            end = Offset(x, height),
            strokeWidth = 0.5f
        )
    }
    
    val graphColor = if (cachedPaths.isRelaxationMode) relaxationColor else primaryColor
    
    drawPath(
        path = cachedPaths.combinedBeatPath,
        color = graphColor.copy(alpha = 0.15f),
        style = Fill
    )
    drawPath(
        path = cachedPaths.upperBeatPath,
        color = graphColor.copy(alpha = 0.3f),
        style = Stroke(width = 0.5f)
    )
    drawPath(
        path = cachedPaths.lowerBeatPath,
        color = graphColor.copy(alpha = 0.3f),
        style = Stroke(width = 0.5f)
    )
    
    drawPath(
        path = cachedPaths.carrierPath,
        color = graphColor.copy(alpha = 0.6f),
        style = Stroke(width = 1.5f)
    )
    
    if (relaxationModeSettings.enabled) {
        for (point in virtualPoints) {
            val x = graphParams.timeToX(point.time)
            val y = graphParams.carrierToY(point.carrierFrequency)
            
            drawCircle(
                color = relaxationColor.copy(alpha = 0.5f),
                radius = 3f,
                center = Offset(x, y),
                style = Fill
            )
        }
    }
    
    val pointPositions = cachedPaths.pointPositions
    val labelTexts = cachedPaths.labeltexts
    
    for (i in sortedPoints.indices) {
        val x = pointPositions[i * 2]
        val y = pointPositions[i * 2 + 1]
        val point = sortedPoints[i]
        
        drawCircle(
            color = primaryColor,
            radius = 5f,
            center = Offset(x, y),
            style = Fill
        )
        
        val beatRatio = (point.beatFrequency / maxBeat).coerceIn(0.0f, 1.0f)
        val innerRadius = 2f + beatRatio * 2f
        drawCircle(
            color = Color.White,
            radius = innerRadius,
            center = Offset(x, y),
            style = Fill
        )
        
        val label = labelTexts[i]
        labelPaint.color = android.graphics.Color.argb(
            (0.8f * 255).toInt(),
            (primaryColor.red * 255).toInt(),
            (primaryColor.green * 255).toInt(),
            (primaryColor.blue * 255).toInt()
        )
        
        val labelX = (x - 25f).coerceAtLeast(0f)
        val labelY = (y - 8f).coerceAtLeast(15f)
        
        drawContext.canvas.nativeCanvas.drawText(
            label,
            labelX,
            labelY,
            labelPaint
        )
    }
    
    axisPaint.color = android.graphics.Color.argb(
        255,
        (primaryColor.red * 255).toInt(),
        (primaryColor.green * 255).toInt(),
        (primaryColor.blue * 255).toInt()
    )
    axisPaint.textAlign = Paint.Align.RIGHT
    
    val maxLabel = "%.0f".format(carrierRange.max)
    val minLabel = "%.0f".format(carrierRange.min)
    val axisX = width - 20f
    val axisPadding = 20f
    
    drawContext.canvas.nativeCanvas.drawText(
        maxLabel,
        axisX,
        axisPadding + axisPaint.textSize,
        axisPaint
    )
    
    drawContext.canvas.nativeCanvas.drawText(
        minLabel,
        axisX,
        height - axisPadding,
        axisPaint
    )
    
    if (isPlaying) {
        val currentX = graphParams.timeToX(currentTime)
        val currentCarrierY = graphParams.carrierToY(currentCarrierFrequency)
        val currentUpperY = graphParams.beatUpperY(currentCarrierFrequency, currentBeatFrequency)
        val currentLowerY = graphParams.beatLowerY(currentCarrierFrequency, currentBeatFrequency)
        
        drawLine(
            color = indicatorColor.copy(alpha = 0.7f),
            start = Offset(currentX, 0f),
            end = Offset(currentX, height),
            strokeWidth = 2f
        )
        
        drawCircle(
            color = indicatorColor,
            radius = 6f,
            center = Offset(currentX, currentCarrierY)
        )
        
        drawLine(
            color = indicatorColor.copy(alpha = 0.5f),
            start = Offset(currentX, currentUpperY),
            end = Offset(currentX, currentLowerY),
            strokeWidth = 2f
        )
    }
}

// Локальные функции интерполяции для MiniFrequencyGraph - все используют Float

private fun interpolateCarrierFrequencyMini(
    points: List<FrequencyPoint>,
    time: LocalTime,
    interpolationType: InterpolationType,
    splineTension: Float
): Float = interpolateFrequencyMini(points, time, interpolationType, splineTension) { it.carrierFrequency }

private fun interpolateBeatFrequencyMini(
    points: List<FrequencyPoint>,
    time: LocalTime,
    interpolationType: InterpolationType,
    splineTension: Float
): Float = interpolateFrequencyMini(points, time, interpolationType, splineTension) { it.beatFrequency }

private fun interpolateFrequencyMini(
    points: List<FrequencyPoint>,
    time: LocalTime,
    interpolationType: InterpolationType,
    splineTension: Float,
    frequencySelector: (FrequencyPoint) -> Float
): Float {
    val sortedPoints = points.sortedBy { it.time.toSecondOfDay() }
    if (sortedPoints.isEmpty()) return 0.0f
    if (sortedPoints.size == 1) return frequencySelector(sortedPoints[0])
    
    val targetSeconds = time.toSecondOfDay()
    
    var intervalIndex = -1
    for (i in 0 until sortedPoints.size - 1) {
        val current = sortedPoints[i].time.toSecondOfDay()
        val next = sortedPoints[i + 1].time.toSecondOfDay()
        if (targetSeconds in current..next) {
            intervalIndex = i
            break
        }
    }
    
    if (intervalIndex == -1) {
        return interpolateBetweenPointsMini(
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
    
    return interpolateBetweenPointsMini(
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

private fun interpolateBetweenPointsMini(
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
    
    val ratio = (t - t1).toFloat() / (t2 - t1)
    
    val p0 = getNeighborPointMini(sortedPoints, leftIndex, -1, frequencySelector, isWrapping)
    val p1 = frequencySelector(leftPoint)
    val p2 = frequencySelector(rightPoint)
    val p3 = getNeighborPointMini(sortedPoints, rightIndex, +1, frequencySelector, isWrapping)
    
    return Interpolation.interpolate(interpolationType, p0, p1, p2, p3, ratio, splineTension)
}

private fun getNeighborPointMini(
    points: List<FrequencyPoint>,
    currentIndex: Int,
    offset: Int,
    frequencySelector: (FrequencyPoint) -> Float,
    isWrapping: Boolean
): Float {
    val neighborIndex = currentIndex + offset
    
    return when {
        neighborIndex < 0 -> {
            if (isWrapping) frequencySelector(points.last())
            else frequencySelector(points.first())
        }
        neighborIndex >= points.size -> {
            if (isWrapping) frequencySelector(points.first())
            else frequencySelector(points.last())
        }
        else -> frequencySelector(points[neighborIndex])
    }
}