package com.binaural.beats.ui.components

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
    
    fun beatWidth(beat: Double): Float {
        return (beat / maxBeat * heightPx * 0.25).toFloat()
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
    primaryColor: Color = MaterialTheme.colorScheme.primary
) {
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
                            interpolationType = frequencyCurve.interpolationType
                        )
                    }
            )
            
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
    interpolationType: InterpolationType
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
    
    // Вертикальные линии каждые 6 часов
    for (hour in 6 until 24 step 6) {
        val x = width * hour / 24
        drawLine(
            color = gridColor,
            start = Offset(x, 0f),
            end = Offset(x, height),
            strokeWidth = 0.5f
        )
    }
    
    if (sortedPoints.size >= 2) {
        drawMiniBeatArea(sortedPoints, graphParams, primaryColor, width, height, interpolationType)
        drawMiniCarrierLine(sortedPoints, graphParams, primaryColor, width, height, interpolationType)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMiniBeatArea(
    sortedPoints: List<FrequencyPoint>,
    params: MiniGraphParams,
    primaryColor: Color,
    width: Float,
    height: Float,
    interpolationType: InterpolationType
) {
    val numSamples = 100
    
    val upperPath = Path()
    val lowerPath = Path()
    
    // Начинаем с левой границы
    val startTime = LocalTime.fromSecondOfDay(0)
    val startCarrier = interpolateCarrierFrequency(sortedPoints, startTime, interpolationType)
    val startBeat = interpolateBeatFrequency(sortedPoints, startTime, interpolationType)
    val startCarrierY = params.carrierToY(startCarrier)
    val startBeatWidth = params.beatWidth(startBeat)
    
    upperPath.moveTo(0f, startCarrierY - startBeatWidth)
    lowerPath.moveTo(0f, startCarrierY + startBeatWidth)
    
    for (i in 1..numSamples) {
        val t = i.toDouble() / numSamples
        val time = LocalTime.fromSecondOfDay((t * 24 * 3600).toInt().coerceAtMost(86399))
        val carrier = interpolateCarrierFrequency(sortedPoints, time, interpolationType)
        val beat = interpolateBeatFrequency(sortedPoints, time, interpolationType)
        val carrierY = params.carrierToY(carrier)
        val beatWidth = params.beatWidth(beat)
        val x = (t * width).toFloat()
        upperPath.lineTo(x, carrierY - beatWidth)
        lowerPath.lineTo(x, carrierY + beatWidth)
    }
    
    // Замыкаем путь
    val combinedPath = Path()
    combinedPath.addPath(upperPath)
    
    for (i in numSamples downTo 0) {
        val t = i.toDouble() / numSamples
        val time = LocalTime.fromSecondOfDay((t * 24 * 3600).toInt().coerceAtMost(86399))
        val carrier = interpolateCarrierFrequency(sortedPoints, time, interpolationType)
        val beat = interpolateBeatFrequency(sortedPoints, time, interpolationType)
        val carrierY = params.carrierToY(carrier)
        val beatWidth = params.beatWidth(beat)
        val x = (t * width).toFloat()
        combinedPath.lineTo(x, carrierY + beatWidth)
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
    interpolationType: InterpolationType
) {
    val numSamples = 100
    val carrierPath = Path()
    
    // Начинаем с левой границы (время 0)
    val startTime = LocalTime.fromSecondOfDay(0)
    val startCarrier = interpolateCarrierFrequency(sortedPoints, startTime, interpolationType)
    val startY = params.carrierToY(startCarrier)
    carrierPath.moveTo(0f, startY)
    
    // Рисуем кривую с интерполяцией
    for (i in 1..numSamples) {
        val t = i.toDouble() / numSamples
        val time = LocalTime.fromSecondOfDay((t * 24 * 3600).toInt().coerceAtMost(86399))
        val carrier = interpolateCarrierFrequency(sortedPoints, time, interpolationType)
        val y = params.carrierToY(carrier)
        val x = (t * width).toFloat()
        carrierPath.lineTo(x, y)
    }
    
    drawPath(
        path = carrierPath,
        color = primaryColor,
        style = Stroke(width = 1.5f)
    )
}
