package com.binauralcycles.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.binaural.core.audio.model.FrequencyCurve
import com.binaural.core.audio.model.FrequencyPoint
import com.binaural.core.audio.model.FrequencyRange
import com.binaural.core.audio.model.InterpolationType
import kotlinx.datetime.LocalTime

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
 * Мини-график частот для отображения в списке пресетов
 * Показывает кривую несущей частоты и область биений
 */
@Composable
fun MiniFrequencyGraph(
    frequencyCurve: FrequencyCurve,
    modifier: Modifier = Modifier,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    isPlaying: Boolean = false,
    currentTime: LocalTime = LocalTime(12, 0),
    currentCarrierFrequency: Double = 0.0,
    currentBeatFrequency: Double = 0.0
) {
    // Используем переданные частоты напрямую - они уже вычислены в ViewModel/родителе
    val displayCarrierFrequency = currentCarrierFrequency
    val displayBeatFrequency = currentBeatFrequency
    val density = LocalDensity.current
    val sortedPoints = remember(frequencyCurve.points) {
        frequencyCurve.points.sortedBy { it.time.toSecondOfDay() }
    }
    
    // Используем диапазон из настроек графика
    val carrierRange = frequencyCurve.carrierRange
    
    // Вычисляем максимальную частоту биений
    val maxBeat = remember(frequencyCurve.points) {
        (frequencyCurve.points.maxOfOrNull { it.beatFrequency } ?: 20.0).coerceAtLeast(1.0)
    }
    
    Box(
        modifier = modifier
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
        ) {
            val widthPx = with(density) { maxWidth.roundToPx() }
            val heightPx = with(density) { maxHeight.roundToPx() }
            
            val graphParams = remember(widthPx, heightPx, carrierRange, maxBeat) {
                MiniGraphParams(widthPx, heightPx, carrierRange, maxBeat)
            }
            
            // График на весь размер
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawMiniGraphContent(
                            sortedPoints = sortedPoints,
                            graphParams = graphParams,
                            primaryColor = primaryColor,
                            interpolationType = frequencyCurve.interpolationType,
                            splineTension = frequencyCurve.splineTension,
                            isPlaying = isPlaying,
                            currentTime = currentTime,
                            currentCarrierFrequency = displayCarrierFrequency,
                            currentBeatFrequency = displayBeatFrequency,
                            maxBeat = maxBeat
                        )
                    }
            ) {
                // Метки частот для каждой точки
                sortedPoints.forEach { point ->
                    val xPx = graphParams.timeToX(point.time)
                    val yPx = graphParams.carrierToY(point.carrierFrequency)
                    // Форматируем частоту биения без лишних нулей
                    val beatStr = if (point.beatFrequency == point.beatFrequency.toLong().toDouble()) {
                        point.beatFrequency.toLong().toString()
                    } else {
                        point.beatFrequency.toString()
                    }
                    val label = "%.0f(%s)".format(point.carrierFrequency, beatStr)
                    
                    // Позиционируем метку над точкой
                    Box(
                        modifier = Modifier
                            .offset { 
                                IntOffset(
                                    (xPx - 25f).toInt().coerceAtLeast(0), 
                                    (yPx - 20f).toInt().coerceAtLeast(0)
                                ) 
                            }
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 7.sp,
                            fontWeight = FontWeight.Medium,
                            color = primaryColor.copy(alpha = 0.8f)
                        )
                    }
                }
            }
            
            // Ось Y - мин/макс частоты (справа поверх графика)
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(top = 4.dp, bottom = 4.dp, end = 6.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "%.0f".format(carrierRange.max),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    color = primaryColor
                )
                Text(
                    text = "%.0f".format(carrierRange.min),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    color = primaryColor
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMiniGraphContent(
    sortedPoints: List<FrequencyPoint>,
    graphParams: MiniGraphParams,
    primaryColor: Color,
    interpolationType: InterpolationType,
    splineTension: Float,
    isPlaying: Boolean,
    currentTime: LocalTime,
    currentCarrierFrequency: Double,
    currentBeatFrequency: Double,
    maxBeat: Double
) {
    val width = size.width
    val height = size.height
    
    // Сетка
    val gridColor = primaryColor.copy(alpha = 0.1f)
    for (i in 1..3) {
        val y = height * i / 4
        drawLine(
            color = gridColor,
            start = Offset(0f, y),
            end = Offset(width, y),
            strokeWidth = 0.5f
        )
    }
    
    // Вертикальные линии каждые 3 часа
    for (hour in 3 until 24 step 3) {
        val x = width * hour / 24
        drawLine(
            color = gridColor,
            start = Offset(x, 0f),
            end = Offset(x, height),
            strokeWidth = 0.5f
        )
    }
    
    if (sortedPoints.size >= 2) {
        drawMiniBeatArea(sortedPoints, graphParams, primaryColor, width, height, interpolationType, splineTension)
        drawMiniCarrierLine(sortedPoints, graphParams, primaryColor, width, height, interpolationType, splineTension)
    }
    
    // Рисуем точки с метками частот
    drawMiniPoints(sortedPoints, graphParams, primaryColor, maxBeat)
    
    // Вертикальная полоса текущего воспроизведения
    if (isPlaying) {
        val currentX = graphParams.timeToX(currentTime)
        val currentCarrierY = graphParams.carrierToY(currentCarrierFrequency)
        val currentUpperY = graphParams.beatUpperY(currentCarrierFrequency, currentBeatFrequency)
        val currentLowerY = graphParams.beatLowerY(currentCarrierFrequency, currentBeatFrequency)
        
        // Вертикальная линия
        drawLine(
            color = Color.Red.copy(alpha = 0.7f),
            start = Offset(currentX, 0f),
            end = Offset(currentX, height),
            strokeWidth = 2f
        )
        
        // Точка на несущей частоте
        drawCircle(
            color = Color.Red,
            radius = 6f,
            center = Offset(currentX, currentCarrierY)
        )
        
        // Вертикальная линия показывающая диапазон частот каналов (от lower до upper)
        drawLine(
            color = Color.Red.copy(alpha = 0.5f),
            start = Offset(currentX, currentUpperY),
            end = Offset(currentX, currentLowerY),
            strokeWidth = 2f
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMiniBeatArea(
    sortedPoints: List<FrequencyPoint>,
    params: MiniGraphParams,
    primaryColor: Color,
    width: Float,
    height: Float,
    interpolationType: InterpolationType,
    splineTension: Float
) {
    val numSamples = 100
    
    val upperPath = Path()
    val lowerPath = Path()
    
    // Начинаем с левой границы
    val startTime = LocalTime.fromSecondOfDay(0)
    val startCarrier = interpolateCarrierFrequency(sortedPoints, startTime, interpolationType, splineTension)
    val startBeat = interpolateBeatFrequency(sortedPoints, startTime, interpolationType, splineTension)
    val startUpperY = params.beatUpperY(startCarrier, startBeat)
    val startLowerY = params.beatLowerY(startCarrier, startBeat)
    
    upperPath.moveTo(0f, startUpperY)
    lowerPath.moveTo(0f, startLowerY)
    
    for (i in 1..numSamples) {
        val t = i.toDouble() / numSamples
        val time = LocalTime.fromSecondOfDay((t * 24 * 3600).toInt().coerceAtMost(86399))
        val carrier = interpolateCarrierFrequency(sortedPoints, time, interpolationType, splineTension)
        val beat = interpolateBeatFrequency(sortedPoints, time, interpolationType, splineTension)
        val upperY = params.beatUpperY(carrier, beat)
        val lowerY = params.beatLowerY(carrier, beat)
        val x = (t * width).toFloat()
        upperPath.lineTo(x, upperY)
        lowerPath.lineTo(x, lowerY)
    }
    
    // Замыкаем путь
    val combinedPath = Path()
    combinedPath.addPath(upperPath)
    
    for (i in numSamples downTo 0) {
        val t = i.toDouble() / numSamples
        val time = LocalTime.fromSecondOfDay((t * 24 * 3600).toInt().coerceAtMost(86399))
        val carrier = interpolateCarrierFrequency(sortedPoints, time, interpolationType, splineTension)
        val beat = interpolateBeatFrequency(sortedPoints, time, interpolationType, splineTension)
        val lowerY = params.beatLowerY(carrier, beat)
        val x = (t * width).toFloat()
        combinedPath.lineTo(x, lowerY)
    }
    
    combinedPath.close()
    
    drawPath(
        path = combinedPath,
        color = primaryColor.copy(alpha = 0.15f),
        style = Fill
    )
    drawPath(
        path = upperPath,
        color = primaryColor.copy(alpha = 0.3f),
        style = Stroke(width = 0.5f)
    )
    drawPath(
        path = lowerPath,
        color = primaryColor.copy(alpha = 0.3f),
        style = Stroke(width = 0.5f)
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMiniCarrierLine(
    sortedPoints: List<FrequencyPoint>,
    params: MiniGraphParams,
    primaryColor: Color,
    width: Float,
    height: Float,
    interpolationType: InterpolationType,
    splineTension: Float
) {
    val numSamples = 100
    val carrierPath = Path()
    
    // Начинаем с левой границы (время 0)
    val startTime = LocalTime.fromSecondOfDay(0)
    val startCarrier = interpolateCarrierFrequency(sortedPoints, startTime, interpolationType, splineTension)
    val startY = params.carrierToY(startCarrier)
    carrierPath.moveTo(0f, startY)
    
    // Рисуем кривую с интерполяцией
    for (i in 1..numSamples) {
        val t = i.toDouble() / numSamples
        val time = LocalTime.fromSecondOfDay((t * 24 * 3600).toInt().coerceAtMost(86399))
        val carrier = interpolateCarrierFrequency(sortedPoints, time, interpolationType, splineTension)
        val y = params.carrierToY(carrier)
        val x = (t * width).toFloat()
        carrierPath.lineTo(x, y)
    }
    
    drawPath(
        path = carrierPath,
        color = primaryColor.copy(alpha = 0.6f),
        style = Stroke(width = 1.5f)
    )
}

/**
 * Рисует точки на графике с метками частот
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMiniPoints(
    sortedPoints: List<FrequencyPoint>,
    params: MiniGraphParams,
    primaryColor: Color,
    maxBeat: Double
) {
    for (point in sortedPoints) {
        val x = params.timeToX(point.time)
        val y = params.carrierToY(point.carrierFrequency)
        
        // Внешний круг точки
        drawCircle(
            color = primaryColor,
            radius = 5f,
            center = Offset(x, y),
            style = Fill
        )
        
        // Внутренний белый круг (индикатор биения)
        val beatRatio = (point.beatFrequency / maxBeat).coerceIn(0.0, 1.0)
        val innerRadius = (2f + beatRatio * 2f).toFloat()
        drawCircle(
            color = Color.White,
            radius = innerRadius,
            center = Offset(x, y),
            style = Fill
        )
    }
}
