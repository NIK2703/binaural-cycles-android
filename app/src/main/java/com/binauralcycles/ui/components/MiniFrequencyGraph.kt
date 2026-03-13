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
 * Вычисляется один раз при изменении данных пресета
 */
private data class CachedGraphPaths(
    val carrierPath: Path,
    val upperBeatPath: Path,
    val lowerBeatPath: Path,
    val combinedBeatPath: Path,
    val gridLines: FloatArray,  // Горизонтальные линии (только Y координаты)
    val verticalLines: FloatArray,  // Вертикальные линии (только X координаты)
    // Предвычисленные позиции точек и меток
    val pointPositions: FloatArray,  // [x0, y0, x1, y1, ...] для каждой точки
    val labeltexts: List<String>,  // Тексты меток
    // Флаг: используется ли режим расслабления
    val isRelaxationMode: Boolean
)

/**
 * Параметры мини-графика
 */
private data class MiniGraphParams(
    val widthPx: Int,
    val heightPx: Int,
    val carrierRange: FrequencyRange,
    val maxBeat: Double
) {
    val carrierRangeSize: Double get() = (carrierRange.max - carrierRange.min).coerceAtLeast(50.0)
    
    fun timeToX(time: LocalTime): Float {
        val seconds = time.toSecondOfDay()
        return (seconds / (24.0 * 3600) * widthPx).toFloat()
    }
    
    fun carrierToY(carrier: Double): Float {
        return heightPx - ((carrier - carrierRange.min) / carrierRangeSize * heightPx).toFloat()
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

/**
 * Генерирует виртуальные точки режима расслабления между реальными точками.
 * Виртуальные точки создаются посередине между каждой парой соседних точек.
 * Частоты берутся с интерполированной кривой для корректного отображения.
 * Локальная функция для использования в MiniFrequencyGraph.
 */
private fun createRelaxationVirtualPoints(
    points: List<FrequencyPoint>,
    relaxationModeSettings: RelaxationModeSettings,
    interpolationType: InterpolationType,
    splineTension: Float
): List<FrequencyPoint> {
    if (!relaxationModeSettings.enabled || points.size < 2) return emptyList()
    
    val sortedPoints = points.sortedBy { it.time.toSecondOfDay() }
    val virtualPoints = mutableListOf<FrequencyPoint>()
    
    val carrierReduction = relaxationModeSettings.carrierReductionPercent / 100.0
    val beatReduction = relaxationModeSettings.beatReductionPercent / 100.0
    
    for (i in 0 until sortedPoints.size) {
        val currentPoint = sortedPoints[i]
        val nextPoint = sortedPoints[(i + 1) % sortedPoints.size]
        
        // Вычисляем время посередине между точками
        val currentTimeSeconds = currentPoint.time.toSecondOfDay()
        var nextTimeSeconds = nextPoint.time.toSecondOfDay()
        
        // Обработка перехода через полночь
        if (nextTimeSeconds <= currentTimeSeconds) {
            nextTimeSeconds += 24 * 3600
        }
        
        val midTimeSeconds = (currentTimeSeconds + nextTimeSeconds) / 2
        val midTime = LocalTime.fromSecondOfDay(midTimeSeconds % (24 * 3600))
        
        // Интерполируем значения по кривой (учитывает тип интерполяции)
        val midCarrier = interpolateCarrierFrequencyMini(sortedPoints, midTime, interpolationType, splineTension)
        val midBeat = interpolateBeatFrequencyMini(sortedPoints, midTime, interpolationType, splineTension)
        
        // Применяем снижение частот для режима расслабления
        val relaxedCarrier = midCarrier * (1.0 - carrierReduction)
        val relaxedBeat = midBeat * (1.0 - beatReduction)
        
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

/**
 * Мини-график частот для отображения в списке пресетов
 * Показывает кривую несущей частоты и область биений
 * 
 * ОПТИМИЗАЦИЯ: Все элементы отрисовываются через Canvas без лишних Composable.
 * Пути графика кэшируются и пересчитываются только при изменении данных пресета.
 * При прокрутке списка используется кэшированные пути, что устраняет лаги.
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
    currentCarrierFrequency: Double = 0.0,
    currentBeatFrequency: Double = 0.0,
    relaxationModeSettings: RelaxationModeSettings = RelaxationModeSettings()
) {
    val density = LocalDensity.current
    
    // Мемоизированные данные - пересчитываются только при изменении пресета
    val sortedPoints = remember(frequencyCurve.points) {
        frequencyCurve.points.sortedBy { it.time.toSecondOfDay() }
    }
    
    // Генерируем виртуальные точки режима расслабления с учётом типа интерполяции
    val virtualPoints = remember(frequencyCurve.points, relaxationModeSettings, frequencyCurve.interpolationType, frequencyCurve.splineTension) {
        createRelaxationVirtualPoints(frequencyCurve.points, relaxationModeSettings, frequencyCurve.interpolationType, frequencyCurve.splineTension)
    }
    
    val carrierRange = frequencyCurve.carrierRange
    
    val maxBeat = remember(frequencyCurve.points, virtualPoints, relaxationModeSettings) {
        val maxFromPoints = frequencyCurve.points.maxOfOrNull { it.beatFrequency } ?: 20.0
        val maxFromVirtual = if (relaxationModeSettings.enabled && virtualPoints.isNotEmpty()) {
            virtualPoints.maxOfOrNull { it.beatFrequency } ?: 0.0
        } else {
            0.0
        }
        maxOf(maxFromPoints, maxFromVirtual).coerceAtLeast(1.0)
    }
    
    // Размеры графика - вычисляем один раз
    var widthPx by remember { mutableIntStateOf(0) }
    var heightPx by remember { mutableIntStateOf(0) }
    
    // Предвычисленный Paint для меток (переиспользуется)
    val labelPaint = remember {
        Paint().apply {
            textSize = 18f  // ~7sp
            isAntiAlias = true
        }
    }
    
    // Предвычисленный Paint для меток оси Y
    val axisPaint = remember {
        Paint().apply {
            textSize = 24f  // ~9sp
            isAntiAlias = true
            isFakeBoldText = true
        }
    }
    
    // Параметры графика (пересчитываются при изменении размеров)
    val graphParams = remember(widthPx, heightPx, carrierRange, maxBeat) {
        if (widthPx > 0 && heightPx > 0) {
            MiniGraphParams(widthPx, heightPx, carrierRange, maxBeat)
        } else {
            null
        }
    }
    
    // КЭШИРОВАНИЕ ПУТЕЙ - ключевая оптимизация!
    // Пути вычисляются только при изменении данных пресета или размера графика
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
                
                // Отрисовка кэшированных путей (быстро, без вычислений)
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

/**
 * Предвычисляет все пути графика один раз при изменении данных
 * Это ключевая оптимизация - тяжёлые вычисления делаются только при изменении пресета
 */
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
    
    // Сетка - горизонтальные линии (только Y координаты для экономии памяти)
    val gridLines = FloatArray(3) { (height * (it + 1) / 4).toFloat() }
    
    // Вертикальные линии каждые 3 часа (только X координаты)
    val verticalLines = FloatArray(7) { (width * (it + 1) * 3 / 24).toFloat() }
    
    // Предвычисляем позиции точек и тексты меток
    val pointPositions = FloatArray(sortedPoints.size * 2)
    val labelTexts = mutableListOf<String>()
    
    sortedPoints.forEachIndexed { index, point ->
        val x = params.timeToX(point.time)
        val y = params.carrierToY(point.carrierFrequency)
        pointPositions[index * 2] = x
        pointPositions[index * 2 + 1] = y
        
        // Форматируем текст метки
        val beatStr = if (point.beatFrequency == point.beatFrequency.toLong().toDouble()) {
            point.beatFrequency.toLong().toString()
        } else {
            point.beatFrequency.toString()
        }
        labelTexts.add("%.0f(%s)".format(point.carrierFrequency, beatStr))
    }
    
    // Вычисляем пути только если есть минимум 2 точки
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
    
    // Определяем точки для интерполяции в зависимости от режима
    val pointsForInterpolation = if (relaxationModeSettings.enabled && virtualPoints.isNotEmpty()) {
        // В режиме расслабления объединяем основные и виртуальные точки
        (sortedPoints + virtualPoints).sortedBy { it.time.toSecondOfDay() }
    } else {
        sortedPoints
    }
    
    // Вычисляем пути на основе выбранных точек
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

/**
 * Вычисляет путь несущей частоты
 */
private fun computeCarrierPath(
    sortedPoints: List<FrequencyPoint>,
    params: MiniGraphParams,
    interpolationType: InterpolationType,
    splineTension: Float
): Path {
    val carrierPath = Path()
    val width = params.widthPx.toFloat()
    
    // Начинаем с левой границы (время 0)
    val startTime = LocalTime.fromSecondOfDay(0)
    val startCarrier = interpolateCarrierFrequency(sortedPoints, startTime, interpolationType, splineTension)
    val startY = params.carrierToY(startCarrier)
    carrierPath.moveTo(0f, startY)
    
    if (interpolationType == InterpolationType.STEP) {
        // Ступенчатая интерполяция
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
        // Обычная интерполяция
        val numSamples = 100
        for (i in 1..numSamples) {
            val t = i.toDouble() / numSamples
            val time = LocalTime.fromSecondOfDay((t * 24 * 3600).toInt().coerceAtMost(86399))
            val carrier = interpolateCarrierFrequency(sortedPoints, time, interpolationType, splineTension)
            val y = params.carrierToY(carrier)
            val x = (t * width).toFloat()
            carrierPath.lineTo(x, y)
        }
    }
    
    return carrierPath
}

/**
 * Вычисляет пути области биений
 */
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
    
    // Начинаем с левой границы
    val startTime = LocalTime.fromSecondOfDay(0)
    val startCarrier = interpolateCarrierFrequency(sortedPoints, startTime, interpolationType, splineTension)
    val startBeat = interpolateBeatFrequency(sortedPoints, startTime, interpolationType, splineTension)
    
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
            val t = i.toDouble() / numSamples
            val time = LocalTime.fromSecondOfDay((t * 24 * 3600).toInt().coerceAtMost(86399))
            val carrier = interpolateCarrierFrequency(sortedPoints, time, interpolationType, splineTension)
            val beat = interpolateBeatFrequency(sortedPoints, time, interpolationType, splineTension)
            val x = (t * width).toFloat()
            upperPath.lineTo(x, params.beatUpperY(carrier, beat))
            lowerPath.lineTo(x, params.beatLowerY(carrier, beat))
        }
    }
    
    // Создаём замкнутый путь для заливки
    val combinedPath = Path()
    combinedPath.addPath(upperPath)
    
    // Обратный путь по нижней границе
    for (i in numSamples downTo 0) {
        val t = i.toDouble() / numSamples
        val time = LocalTime.fromSecondOfDay((t * 24 * 3600).toInt().coerceAtMost(86399))
        val carrier = interpolateCarrierFrequency(sortedPoints, time, interpolationType, splineTension)
        val beat = interpolateBeatFrequency(sortedPoints, time, interpolationType, splineTension)
        val x = (t * width).toFloat()
        combinedPath.lineTo(x, params.beatLowerY(carrier, beat))
    }
    combinedPath.close()
    
    return Triple(upperPath, lowerPath, combinedPath)
}

/**
 * Быстрая отрисовка кэшированного графика
 * Все тяжёлые вычисления уже сделаны в computeGraphPaths
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCachedGraph(
    cachedPaths: CachedGraphPaths,
    primaryColor: Color,
    indicatorColor: Color,
    relaxationColor: Color,
    sortedPoints: List<FrequencyPoint>,
    virtualPoints: List<FrequencyPoint>,
    graphParams: MiniGraphParams,
    maxBeat: Double,
    isPlaying: Boolean,
    currentTime: LocalTime,
    currentCarrierFrequency: Double,
    currentBeatFrequency: Double,
    relaxationModeSettings: RelaxationModeSettings,
    labelPaint: Paint,
    axisPaint: Paint,
    carrierRange: FrequencyRange
) {
    val width = size.width
    val height = size.height
    val gridColor = primaryColor.copy(alpha = 0.1f)
    
    // Отрисовка сетки (горизонтальные линии)
    for (y in cachedPaths.gridLines) {
        drawLine(
            color = gridColor,
            start = Offset(0f, y),
            end = Offset(width, y),
            strokeWidth = 0.5f
        )
    }
    
    // Вертикальные линии
    for (x in cachedPaths.verticalLines) {
        drawLine(
            color = gridColor,
            start = Offset(x, 0f),
            end = Offset(x, height),
            strokeWidth = 0.5f
        )
    }
    
    // Выбираем цвет в зависимости от режима
    val graphColor = if (cachedPaths.isRelaxationMode) relaxationColor else primaryColor
    
    // Отрисовка области биений (кэшированные пути)
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
    
    // Отрисовка несущей частоты (кэшированный путь)
    drawPath(
        path = cachedPaths.carrierPath,
        color = graphColor.copy(alpha = 0.6f),
        style = Stroke(width = 1.5f)
    )
    
    // Рисуем виртуальные точки режима расслабления - простые кружки без обводки
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
    
    // Рисуем основные точки и метки
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
        
        val beatRatio = (point.beatFrequency / maxBeat).coerceIn(0.0, 1.0)
        val innerRadius = (2f + beatRatio * 2f).toFloat()
        drawCircle(
            color = Color.White,
            radius = innerRadius,
            center = Offset(x, y),
            style = Fill
        )
        
        // Отрисовка метки через nativeCanvas (без Composable)
        val label = labelTexts[i]
        labelPaint.color = android.graphics.Color.argb(
            (0.8f * 255).toInt(),
            (primaryColor.red * 255).toInt(),
            (primaryColor.green * 255).toInt(),
            (primaryColor.blue * 255).toInt()
        )
        
        // Позиционируем метку над точкой (с clamp к границам)
        val labelX = (x - 25f).coerceAtLeast(0f)
        val labelY = (y - 8f).coerceAtLeast(15f)
        
        drawContext.canvas.nativeCanvas.drawText(
            label,
            labelX,
            labelY,
            labelPaint
        )
    }
    
    // Ось Y - мин/макс частоты (справа поверх графика, с отступом от скругления карточки)
    axisPaint.color = android.graphics.Color.argb(
        255,
        (primaryColor.red * 255).toInt(),
        (primaryColor.green * 255).toInt(),
        (primaryColor.blue * 255).toInt()
    )
    axisPaint.textAlign = Paint.Align.RIGHT  // Выравнивание по правому краю
    
    val maxLabel = "%.0f".format(carrierRange.max)
    val minLabel = "%.0f".format(carrierRange.min)
    val axisX = width - 20f  // Отступ от правого края с учётом скругления карточки
    val axisPadding = 20f    // Одинаковый отступ сверху и снизу для меток
    
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
    
    // Индикатор текущего воспроизведения (вычисляется динамически, но это минимальные затраты)
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

// Локальные функции интерполяции для MiniFrequencyGraph

/**
 * Интерполяция несущей частоты
 */
private fun interpolateCarrierFrequencyMini(
    points: List<FrequencyPoint>,
    time: LocalTime,
    interpolationType: InterpolationType,
    splineTension: Float
): Double = interpolateFrequencyMini(points, time, interpolationType, splineTension) { it.carrierFrequency }

/**
 * Интерполяция частоты биений
 */
private fun interpolateBeatFrequencyMini(
    points: List<FrequencyPoint>,
    time: LocalTime,
    interpolationType: InterpolationType,
    splineTension: Float
): Double = interpolateFrequencyMini(points, time, interpolationType, splineTension) { it.beatFrequency }

/**
 * Общая функция интерполяции частоты
 */
private fun interpolateFrequencyMini(
    points: List<FrequencyPoint>,
    time: LocalTime,
    interpolationType: InterpolationType,
    splineTension: Float,
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

/**
 * Интерполяция между двумя точками с учётом соседних для кубического сплайна
 */
private fun interpolateBetweenPointsMini(
    sortedPoints: List<FrequencyPoint>,
    leftIndex: Int,
    rightIndex: Int,
    time: LocalTime,
    frequencySelector: (FrequencyPoint) -> Double,
    interpolationType: InterpolationType,
    splineTension: Float,
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
    
    // Получаем 4 точки для интерполяции
    val p0 = getNeighborPointMini(sortedPoints, leftIndex, -1, frequencySelector, isWrapping)
    val p1 = frequencySelector(leftPoint)
    val p2 = frequencySelector(rightPoint)
    val p3 = getNeighborPointMini(sortedPoints, rightIndex, +1, frequencySelector, isWrapping)
    
    // Используем общий объект интерполяции с параметром tension
    return Interpolation.interpolate(interpolationType, p0, p1, p2, p3, ratio, splineTension)
}

/**
 * Получить соседнюю точку с учётом цикличности графика и перехода через полночь
 */
private fun getNeighborPointMini(
    points: List<FrequencyPoint>,
    currentIndex: Int,
    offset: Int,
    frequencySelector: (FrequencyPoint) -> Double,
    isWrapping: Boolean
): Double {
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