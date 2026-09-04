package com.binauralcycles.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.binaural.core.audio.model.CardinalTension
import com.binaural.core.audio.model.FrequencyCurve
import com.binaural.core.audio.model.FrequencyMath
import com.binaural.core.audio.model.FrequencyPoint
import com.binaural.core.audio.model.FrequencyRange
import com.binaural.core.audio.model.Interpolation
import com.binaural.core.audio.model.InterpolationType
import com.binaural.core.audio.model.RelaxationMode
import com.binaural.core.audio.model.RelaxationModeSettings
import kotlinx.datetime.LocalTime
import android.graphics.Paint
import java.util.Locale

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
    
    /**
     * Y «верхней» границы полосы биений = ПРАВЫЙ канал: carrier + beat/2.
     * При ОТРИЦАТЕЛЬНОЙ частоте биений правый канал звучит ниже левого, и эта
     * координата оказывается ниже [beatLowerY]. Полоса между ними от этого
     * не меняется — обе границы считаются по одной формуле каналов.
     */
    fun beatUpperY(carrier: Float, beat: Float): Float {
        val upperFrequency = FrequencyMath.rightChannelFrequency(carrier, beat)
        return carrierToY(upperFrequency)
    }

    /** Y «нижней» границы полосы биений = ЛЕВЫЙ канал: carrier − beat/2. */
    fun beatLowerY(carrier: Float, beat: Float): Float {
        val lowerFrequency = FrequencyMath.leftChannelFrequency(carrier, beat)
        return carrierToY(lowerFrequency)
    }
}

/**
 * Мини-график частот для отображения в списке пресетов
 * Использует глобальный кэш геометрии для оптимизации производительности
 */
@Composable
fun MiniFrequencyGraph(
    frequencyCurve: FrequencyCurve,
    modifier: Modifier = Modifier,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    indicatorColor: Color = MaterialTheme.colorScheme.error,
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
    
    val carrierRange = frequencyCurve.carrierRange

    // Paint объекты создаём один раз с учётом масштаба интерфейса
    val labelPaint = remember(density) {
        Paint().apply {
            textSize = with(density) { 8.sp.toPx() }
            isAntiAlias = true
        }
    }

    val axisPaint = remember(density) {
        Paint().apply {
            textSize = with(density) { 10.sp.toPx() }
            isAntiAlias = true
        }
    }

    // Геометрия считается ЛЕНИВО в фазе отрисовки из DrawScope.size, а не через
    // состояние размера + onSizeChanged. Раньше размер приходил асинхронно
    // (после первого layout), из-за чего первый кадр рисовал пустоту, а геометрия
    // пересчитывалась для всех карточек только со второго кадра — ровно тогда,
    // когда shared-анимация сворачивания должна была уже «ехать». Теперь size
    // известен в draw сразу, MiniGraphCache возвращает закэшированные Path'и, а
    // тяжёлый computeGraphGeometry отрабатывает один раз на пару (размер, параметры).
    val geometryFor: (Int, Int) -> CachedGraphGeometry? = { w, h ->
        if (w <= 0 || h <= 0) {
            null
        } else {
            MiniGraphCache.getOrCreate(
                points = sortedPoints,
                virtualPoints = emptyList(),  // Виртуальные точки вычисляются внутри
                widthPx = w,
                heightPx = h,
                carrierRangeMin = carrierRange.min,
                carrierRangeMax = carrierRange.max,
                interpolationType = frequencyCurve.interpolationType,
                splineTension = frequencyCurve.splineTension,
                relaxationModeSettings = relaxationModeSettings
            ) {
                computeGraphGeometry(
                    sortedPoints = sortedPoints,
                    widthPx = w,
                    heightPx = h,
                    carrierRange = carrierRange,
                    interpolationType = frequencyCurve.interpolationType,
                    splineTension = frequencyCurve.splineTension,
                    relaxationModeSettings = relaxationModeSettings
                )
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                onDrawBehind {
                    val geometry = geometryFor(size.width.toInt(), size.height.toInt()) ?: return@onDrawBehind
                    drawCachedGeometry(
                        geometry = geometry,
                        primaryColor = primaryColor,
                        indicatorColor = indicatorColor,
                        sortedPoints = sortedPoints,
                        width = size.width,
                        height = size.height,
                        isPlaying = false,
                        currentTime = currentTime,
                        currentCarrierFrequency = currentCarrierFrequency,
                        currentBeatFrequency = currentBeatFrequency,
                        relaxationModeSettings = relaxationModeSettings,
                        labelPaint = labelPaint,
                        axisPaint = axisPaint,
                        carrierRange = carrierRange,
                        drawIndicatorOnly = false
                    )
                }
            }
            // Динамичный слой: указатель текущего момента. Перерисовывается на
            // каждом тике телеметрии (isPlaying / currentTime), но дёшев — только
            // 3 линии. Статичный слой выше закэширован через drawWithCache.
            .drawBehind {
                val geometry = geometryFor(size.width.toInt(), size.height.toInt()) ?: return@drawBehind
                if (isPlaying) {
                    drawCachedGeometry(
                        geometry = geometry,
                        primaryColor = primaryColor,
                        indicatorColor = indicatorColor,
                        sortedPoints = sortedPoints,
                        width = size.width,
                        height = size.height,
                        isPlaying = isPlaying,
                        currentTime = currentTime,
                        currentCarrierFrequency = currentCarrierFrequency,
                        currentBeatFrequency = currentBeatFrequency,
                        relaxationModeSettings = relaxationModeSettings,
                        labelPaint = labelPaint,
                        axisPaint = axisPaint,
                        carrierRange = carrierRange,
                        drawIndicatorOnly = true
                    )
                }
            }
    )
}

/**
 * Вычисляет геометрию графика (без цветов)
 */
private fun computeGraphGeometry(
    sortedPoints: List<FrequencyPoint>,
    widthPx: Int,
    heightPx: Int,
    carrierRange: FrequencyRange,
    interpolationType: InterpolationType,
    splineTension: Float,
    relaxationModeSettings: RelaxationModeSettings
): CachedGraphGeometry {
    val params = MiniGraphParams(
        widthPx = widthPx,
        heightPx = heightPx,
        carrierRange = carrierRange,
        maxBeat = 1.0f  // Временное значение
    )
    
    // Генерируем виртуальные точки; carrierRange передаём обязательно —
    // по его минимуму виртуальные точки ограничиваются снизу.
    val virtualPoints = createRelaxationVirtualPoints(
        sortedPoints,
        relaxationModeSettings,
        interpolationType,
        splineTension,
        carrierRange
    )
    
    // Вычисляем maxBeat — по МОДУЛЮ частоты биений: знак задаёт только
    // раскладку каналов, а масштаб («толщина» полосы) определяется |beat|.
    val maxBeat = run {
        val maxFromPoints = sortedPoints.maxOfOrNull { FrequencyMath.beatMagnitude(it.beatFrequency) } ?: 20.0f
        val maxFromVirtual = if (relaxationModeSettings.enabled && virtualPoints.isNotEmpty()) {
            virtualPoints.maxOfOrNull { FrequencyMath.beatMagnitude(it.beatFrequency) } ?: 0.0f
        } else {
            0.0f
        }
        maxOf(maxFromPoints, maxFromVirtual).coerceAtLeast(1.0f)
    }
    
    val finalParams = params.copy(maxBeat = maxBeat)
    
    // Определяем точки для интерполяции
    val pointsForInterpolation = when {
        relaxationModeSettings.enabled && relaxationModeSettings.mode == RelaxationMode.STEP && virtualPoints.isNotEmpty() -> {
            virtualPoints
        }
        relaxationModeSettings.enabled && relaxationModeSettings.mode == RelaxationMode.SMOOTH && virtualPoints.isNotEmpty() -> {
            virtualPoints
        }
        relaxationModeSettings.enabled && virtualPoints.isNotEmpty() -> {
            (sortedPoints + virtualPoints).sortedBy { it.time.toSecondOfDay() }
        }
        else -> sortedPoints
    }
    
    // Веса касательных — ОДИН РАЗ на геометрию (MiniGraphCache держит её до
    // смены размера или параметров), а не на каждый из ~800 сэмплов ниже.
    //
    // ДВА набора: основной — по pointsForInterpolation (в режиме расслабления
    // это виртуальные точки), базовый — по sortedPoints (пунктир исходной кривой).
    val weights = CardinalTension.forPoints(
        pointsForInterpolation, interpolationType, splineTension, carrierRange, presorted = true
    )
    val baseWeights = CardinalTension.forPoints(
        sortedPoints, interpolationType, splineTension, carrierRange, presorted = true
    )

    // Вычисляем пути
    val carrierPath = computeCarrierPath(pointsForInterpolation, finalParams, interpolationType, splineTension, weights)
    val (upperPath, lowerPath, combinedPath) = computeBeatPaths(pointsForInterpolation, finalParams, interpolationType, splineTension, weights)
    
    // Вычисляем путь базовой кривой (по основным точкам) для режимов STEP и SMOOTH
    val baseCarrierPath = if (relaxationModeSettings.enabled && 
        (relaxationModeSettings.mode == RelaxationMode.STEP || relaxationModeSettings.mode == RelaxationMode.SMOOTH) &&
        relaxationModeSettings.carrierReductionPercent > 0 &&
        sortedPoints.size >= 2) {
        computeCarrierPath(sortedPoints, finalParams, interpolationType, splineTension, baseWeights)
    } else {
        null
    }
    
    // Позиции точек и подписи
    val pointPositions = FloatArray(sortedPoints.size * 2)
    val labelTexts = mutableListOf<String>()
    
    sortedPoints.forEachIndexed { index, point ->
        val x = finalParams.timeToX(point.time)
        val y = finalParams.carrierToY(point.carrierFrequency)
        pointPositions[index * 2] = x
        pointPositions[index * 2 + 1] = y
        
        val beatStr = if (point.beatFrequency == point.beatFrequency.toLong().toFloat()) {
            point.beatFrequency.toLong().toString()
        } else {
            // Локале-зависимый разделитель (запятая в RU/DE и т.п.) вместо жёсткой точки
            String.format(Locale.getDefault(), "%.1f", point.beatFrequency)
        }
        labelTexts.add("%.0f(%s)".format(Locale.getDefault(), point.carrierFrequency, beatStr))
    }
    
    // Позиции виртуальных точек (не используются в SMOOTH режиме)
    val virtualPointPositions = FloatArray(0)
    
    return CachedGraphGeometry(
        carrierPath = carrierPath,
        upperBeatPath = upperPath,
        lowerBeatPath = lowerPath,
        combinedBeatPath = combinedPath,
        baseCarrierPath = baseCarrierPath,
        pointPositions = pointPositions,
        labelTexts = labelTexts,
        virtualPointPositions = virtualPointPositions,
        isRelaxationMode = relaxationModeSettings.enabled && virtualPoints.isNotEmpty(),
        maxBeat = maxBeat
    )
}

/**
 * Отрисовывает закэшированную геометрию с текущими цветами
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCachedGeometry(
    geometry: CachedGraphGeometry,
    primaryColor: Color,
    indicatorColor: Color,
    sortedPoints: List<FrequencyPoint>,
    width: Float,
    height: Float,
    isPlaying: Boolean,
    currentTime: LocalTime,
    currentCarrierFrequency: Float,
    currentBeatFrequency: Float,
    relaxationModeSettings: RelaxationModeSettings,
    labelPaint: Paint,
    axisPaint: Paint,
    carrierRange: FrequencyRange,
    drawIndicatorOnly: Boolean = false
) {
    if (!drawIndicatorOnly) {
    // Получаем сетку из глобального кэша (одна на все карточки)
    val cachedGrid = GridCache.getOrCreate(width.toInt(), height.toInt())
    
    // Сетка - кэшированная одна на все карточки, strokeWidth 1.0f вместо 0.5f
    val gridColor = primaryColor.copy(alpha = 0.1f)
    
    for (y in cachedGrid.gridLines) {
        drawLine(
            color = gridColor,
            start = Offset(0f, y),
            end = Offset(width, y),
            strokeWidth = 1.0f
        )
    }
    
    for (x in cachedGrid.verticalLines) {
        drawLine(
            color = gridColor,
            start = Offset(x, 0f),
            end = Offset(x, height),
            strokeWidth = 1.0f
        )
    }
    
    // Выбираем цвет графика.
    // Все карточки пресетов используют единый цвет monet (primary) независимо
    // от режима расслабления: иначе кастомные пресеты с включённым режимом
    // расслабления перекрашивались в tertiary (красноватый оттенок), а
    // стандартные — в primary, что ломало визуальное единообразие списка.
    // Форма кривой расслабления по-прежнему рисуется, меняется только цвет.
    val graphColor = primaryColor
    
    // Область биений
    drawPath(
        path = geometry.combinedBeatPath,
        color = graphColor.copy(alpha = 0.15f),
        style = Fill
    )
    drawPath(
        path = geometry.upperBeatPath,
        color = graphColor.copy(alpha = 0.3f),
        style = Stroke(width = 0.5f)
    )
    drawPath(
        path = geometry.lowerBeatPath,
        color = graphColor.copy(alpha = 0.3f),
        style = Stroke(width = 0.5f)
    )
    
    // Пунктирная линия базовой кривой (для режимов ADVANCED и SMOOTH)
    geometry.baseCarrierPath?.let { basePath ->
        val dashPattern = floatArrayOf(6f, 6f)
        drawPath(
            path = basePath,
            color = primaryColor.copy(alpha = 0.3f),
            style = Stroke(
                width = 1f,
                pathEffect = PathEffect.dashPathEffect(dashPattern)
            )
        )
    }
    
    // Основные точки и подписи
    val pointPositions = geometry.pointPositions
    val labelTexts = geometry.labelTexts
    
    for (i in sortedPoints.indices) {
        val x = pointPositions[i * 2]
        val y = pointPositions[i * 2 + 1]

        // Точки — полупрозрачная цветная заливка (без белой сердцевины-обводки).
        drawCircle(
            color = primaryColor.copy(alpha = 0.5f),
            radius = 5f,
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
    
    // Ось Y
    axisPaint.color = android.graphics.Color.argb(
        (0.8f * 255).toInt(),
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
    
    }

    // Индикатор воспроизведения
    if (isPlaying || drawIndicatorOnly) {
        val carrierRangeSize = (carrierRange.max - carrierRange.min).coerceAtLeast(50.0f)
        fun timeToX(time: LocalTime): Float = (time.toSecondOfDay() / (24.0f * 3600f) * width)
        fun carrierToY(carrier: Float): Float = height - ((carrier - carrierRange.min) / carrierRangeSize * height)
        // Формулы каналов: при beat < 0 «верхний» и «нижний» меняются местами,
        // поэтому границы полосы ниже берём по координатам, а не по именам.
        fun beatUpperY(carrier: Float, beat: Float): Float =
            carrierToY(FrequencyMath.rightChannelFrequency(carrier, beat))
        fun beatLowerY(carrier: Float, beat: Float): Float =
            carrierToY(FrequencyMath.leftChannelFrequency(carrier, beat))

        val currentX = timeToX(currentTime)
        val rightChannelY = beatUpperY(currentCarrierFrequency, currentBeatFrequency).coerceIn(0f, height)
        val leftChannelY = beatLowerY(currentCarrierFrequency, currentBeatFrequency).coerceIn(0f, height)
        val currentUpperY = minOf(rightChannelY, leftChannelY)
        val currentLowerY = maxOf(rightChannelY, leftChannelY)
        
        // Вертикальная линия текущего момента: вне области биений — полупрозрачная,
        // внутри области биений — ярче. Точку пересечения с несущей убираем.
        val indicatorAlpha = 0.3f
        drawLine(
            color = indicatorColor.copy(alpha = indicatorAlpha),
            start = Offset(currentX, 0f),
            end = Offset(currentX, currentUpperY),
            strokeWidth = 2f
        )
        drawLine(
            color = indicatorColor.copy(alpha = indicatorAlpha),
            start = Offset(currentX, currentLowerY),
            end = Offset(currentX, height),
            strokeWidth = 2f
        )
        drawLine(
            color = indicatorColor.copy(alpha = 0.5f),
            start = Offset(currentX, currentUpperY),
            end = Offset(currentX, currentLowerY),
            strokeWidth = 2f
        )
    }
}

// Функции генерации виртуальных точек

private fun createRelaxationVirtualPoints(
    points: List<FrequencyPoint>,
    relaxationModeSettings: RelaxationModeSettings,
    interpolationType: InterpolationType,
    splineTension: Float,
    carrierRange: FrequencyRange
): List<FrequencyPoint> {
    // Единая реализация в core-модели (RelaxationModeSettings).
    // Диапазон несущей обязателен: по его минимуму клампятся виртуальные точки.
    return relaxationModeSettings.generateVirtualPoints(points, interpolationType, splineTension, carrierRange)
}

// Функции вычисления путей

private fun computeCarrierPath(
    sortedPoints: List<FrequencyPoint>,
    params: MiniGraphParams,
    interpolationType: InterpolationType,
    splineTension: Float,
    /**
     * Веса касательных кардинального сплайна (см. CardinalTension). Приходят
     * снаружи: внутри ~400 вызовов interpolateChannels, пересчёт весов на
     * каждом из них превратил бы O(n) в O(n²).
     */
    weights: FloatArray? = null
): Path {
    val carrierPath = Path()
    val width = params.widthPx.toFloat()

    val startTime = LocalTime.fromSecondOfDay(0)
    // Линия несущей отображается как (l+u)/2 от канальных кривых — как в движке
    // presorted = true: вызывающие стороны передают уже отсортированный список
    val (startLowerFreq, startUpperFreq) = Interpolation.interpolateChannels(
        sortedPoints, startTime, interpolationType, splineTension,
        presorted = true, weights = weights
    )
    val startY = params.carrierToY((startLowerFreq + startUpperFreq) / 2.0f)
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
        val numSamples = (sortedPoints.size * 4).coerceAtLeast(400)
        for (i in 1..numSamples) {
            val t = i.toFloat() / numSamples
            val time = LocalTime.fromSecondOfDay((t * 24 * 3600).toInt().coerceAtMost(86399))
            val (lowerFreq, upperFreq) = Interpolation.interpolateChannels(
                sortedPoints, time, interpolationType, splineTension,
                presorted = true, weights = weights
            )
            val y = params.carrierToY((lowerFreq + upperFreq) / 2.0f)
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
    splineTension: Float,
    /** Веса касательных кардинального сплайна (см. [computeCarrierPath]). */
    weights: FloatArray? = null
): Triple<Path, Path, Path> {
    val width = params.widthPx.toFloat()
    val numSamples = (sortedPoints.size * 4).coerceAtLeast(400)
    
    val upperPath = Path()
    val lowerPath = Path()

    val startTime = LocalTime.fromSecondOfDay(0)
    // Канальные кривые интерполируются напрямую — как в движке
    // presorted = true: вызывающие стороны передают уже отсортированный список
    val (startLowerFreq, startUpperFreq) = Interpolation.interpolateChannels(
        sortedPoints, startTime, interpolationType, splineTension,
        presorted = true, weights = weights
    )

    // Кэш Y нижней кривой: раньше обратный проход для combinedPath
    // пересчитывал interpolateChannels по всем сэмплам второй раз (2x работа).
    val lowerY = FloatArray(numSamples + 1)
    lowerY[0] = params.carrierToY(startLowerFreq)

    upperPath.moveTo(0f, params.carrierToY(startUpperFreq))
    lowerPath.moveTo(0f, params.carrierToY(startLowerFreq))

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
            val lowerLineY = params.beatLowerY(currentPoint.carrierFrequency, currentPoint.beatFrequency)

            upperPath.lineTo(currentX, upperY)
            upperPath.lineTo(nextX, upperY)
            lowerPath.lineTo(currentX, lowerLineY)
            lowerPath.lineTo(nextX, lowerLineY)
        }

        // Значения нижней кривой для combinedPath (STEP держит последнее значение)
        for (i in 1..numSamples) {
            val t = i.toFloat() / numSamples
            val time = LocalTime.fromSecondOfDay((t * 24 * 3600).toInt().coerceAtMost(86399))
            val (lowerFreq, _) = Interpolation.interpolateChannels(
                sortedPoints, time, interpolationType, splineTension,
                presorted = true, weights = weights
            )
            lowerY[i] = params.carrierToY(lowerFreq)
        }
    } else {
        for (i in 1..numSamples) {
            val t = i.toFloat() / numSamples
            val time = LocalTime.fromSecondOfDay((t * 24 * 3600).toInt().coerceAtMost(86399))
            val (lowerFreq, upperFreq) = Interpolation.interpolateChannels(
                sortedPoints, time, interpolationType, splineTension,
                presorted = true, weights = weights
            )
            val x = t * width
            lowerY[i] = params.carrierToY(lowerFreq)
            upperPath.lineTo(x, params.carrierToY(upperFreq))
            lowerPath.lineTo(x, lowerY[i])
        }
    }

    val combinedPath = Path()
    combinedPath.addPath(upperPath)

    for (i in numSamples downTo 0) {
        val t = i.toFloat() / numSamples
        val x = t * width
        combinedPath.lineTo(x, lowerY[i])
    }
    combinedPath.close()

    return Triple(upperPath, lowerPath, combinedPath)
}