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
fun maxBeatForCarrier(carrierFrequency: Float): Float {
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
    val maxBeat: Float get() = beatRange.max
    
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
     * Вычисляет Y-координату верхней границы области биений
     * Верхняя граница соответствует частоте канала: carrier + beat/2
     */
    fun beatUpperY(carrier: Float, beat: Float): Float {
        val upperFrequency = carrier + beat / 2.0f
        return carrierToY(upperFrequency)
    }
    
    /**
     * Вычисляет Y-координату нижней границы области биений
     * Нижняя граница соответствует частоте канала: carrier - beat/2
     */
    fun beatLowerY(carrier: Float, beat: Float): Float {
        val lowerFrequency = carrier - beat / 2.0f
        return carrierToY(lowerFrequency)
    }
}

/**
 * Генерирует виртуальные точки режима расслабления.
 * Для ADVANCED режима: 4 точки на каждый период расслабления, образующие трапецию.
 * Для SMOOTH режима: чередующиеся точки (базовая → снижающая → базовая → снижающая).
 */
fun generateRelaxationVirtualPoints(
    points: List<FrequencyPoint>,
    relaxationModeSettings: RelaxationModeSettings,
    interpolationType: InterpolationType = InterpolationType.LINEAR,
    splineTension: Float = 0.0f
): List<FrequencyPoint> {
    if (!relaxationModeSettings.enabled || points.size < 2) return emptyList()
    
    return when (relaxationModeSettings.mode) {
        RelaxationMode.STEP -> generateStepVirtualPoints(points, relaxationModeSettings, interpolationType, splineTension)
        RelaxationMode.SMOOTH -> generateSmoothVirtualPoints(points, relaxationModeSettings, interpolationType, splineTension)
    }
}

/**
 * Ступенчатый режим: генерация виртуальных точек по расписанию.
 * Создаётся группа из 4 точек для каждого периода расслабления:
 * - Точка 1: на базовой кривой (начало периода)
 * - Точка 2: сниженные частоты (после перехода)
 * - Точка 3: сниженные частоты (конец расслабления)
 * - Точка 4: на базовой кривой (после выхода)
 * 
 * Между периодами расслабления есть пауза gapBetweenRelaxationMinutes.
 */
private fun generateStepVirtualPoints(
    points: List<FrequencyPoint>,
    relaxationModeSettings: RelaxationModeSettings,
    interpolationType: InterpolationType,
    splineTension: Float
): List<FrequencyPoint> {
    val virtualPoints = mutableListOf<FrequencyPoint>()
    
    val carrierReduction = relaxationModeSettings.carrierReductionPercent / 100.0f
    val beatReduction = relaxationModeSettings.beatReductionPercent / 100.0f
    
    val gapSeconds = relaxationModeSettings.gapBetweenRelaxationMinutes * 60L
    val transitionSeconds = relaxationModeSettings.transitionPeriodMinutes * 60L
    val durationSeconds = relaxationModeSettings.relaxationDurationMinutes * 60L
    
    // Полный период расслабления = 2 * переход + длительность
    val fullPeriodSeconds = 2 * transitionSeconds + durationSeconds
    
    // Генерируем периоды расслабления от 00:00
    val daySeconds = 24 * 3600L
    
    var periodStartSeconds = 0L
    
    while (periodStartSeconds < daySeconds) {
        // Точка 1: начало периода (на базовой кривой)
        val t1 = periodStartSeconds
        val time1 = LocalTime.fromSecondOfDay((t1 % daySeconds).toInt())
        val carrier1 = interpolateCarrierFrequency(points, time1, interpolationType, splineTension)
        val beat1 = interpolateBeatFrequency(points, time1, interpolationType, splineTension)
        virtualPoints.add(FrequencyPoint(time1, carrier1, beat1))
        
        // Точка 2: после перехода (сниженные частоты)
        val t2 = periodStartSeconds + transitionSeconds
        if (t2 < daySeconds) {
            val time2 = LocalTime.fromSecondOfDay((t2 % daySeconds).toInt())
            val baseCarrier2 = interpolateCarrierFrequency(points, time2, interpolationType, splineTension)
            val baseBeat2 = interpolateBeatFrequency(points, time2, interpolationType, splineTension)
            virtualPoints.add(FrequencyPoint(
                time2,
                baseCarrier2 * (1.0f - carrierReduction),
                baseBeat2 * (1.0f - beatReduction)
            ))
        }
        
        // Точка 3: конец расслабления (сниженные частоты)
        val t3 = periodStartSeconds + transitionSeconds + durationSeconds
        if (t3 < daySeconds) {
            val time3 = LocalTime.fromSecondOfDay((t3 % daySeconds).toInt())
            val baseCarrier3 = interpolateCarrierFrequency(points, time3, interpolationType, splineTension)
            val baseBeat3 = interpolateBeatFrequency(points, time3, interpolationType, splineTension)
            virtualPoints.add(FrequencyPoint(
                time3,
                baseCarrier3 * (1.0f - carrierReduction),
                baseBeat3 * (1.0f - beatReduction)
            ))
        }
        
        // Точка 4: после выхода (на базовой кривой)
        val t4 = periodStartSeconds + fullPeriodSeconds
        if (t4 < daySeconds) {
            val time4 = LocalTime.fromSecondOfDay((t4 % daySeconds).toInt())
            val carrier4 = interpolateCarrierFrequency(points, time4, interpolationType, splineTension)
            val beat4 = interpolateBeatFrequency(points, time4, interpolationType, splineTension)
            virtualPoints.add(FrequencyPoint(time4, carrier4, beat4))
        }
        
        // Переходим к следующему периоду: полный период + пауза между периодами
        periodStartSeconds += fullPeriodSeconds + gapSeconds
    }
    
    // Сортируем по времени
    return virtualPoints.sortedBy { it.time.toSecondOfDay() }
}

/**
 * Плавный режим: чередующиеся точки (базовая → снижающая → базовая → снижающая).
 * Интервал между точками регулируется параметром smoothIntervalMinutes.
 * Итоговая кривая строится ТОЛЬКО по этим виртуальным точкам.
 */
private fun generateSmoothVirtualPoints(
    points: List<FrequencyPoint>,
    relaxationModeSettings: RelaxationModeSettings,
    interpolationType: InterpolationType,
    splineTension: Float
): List<FrequencyPoint> {
    val virtualPoints = mutableListOf<FrequencyPoint>()
    
    val carrierReduction = relaxationModeSettings.carrierReductionPercent / 100.0f
    val beatReduction = relaxationModeSettings.beatReductionPercent / 100.0f
    val intervalSeconds = relaxationModeSettings.smoothIntervalMinutes * 60L
    val daySeconds = 24 * 3600L
    
    // Генерируем точки от 00:00 до 23:59 с заданным интервалом
    // Чётные индексы (0, 2, 4...) - точки на базовой кривой
    // Нечётные индексы (1, 3, 5...) - снижающие точки
    
    var currentSeconds = 0L
    var index = 0
    
    while (currentSeconds < daySeconds) {
        val time = LocalTime.fromSecondOfDay((currentSeconds % daySeconds).toInt())
        
        if (index % 2 == 0) {
            // Чётный индекс - точка на базовой кривой
            val carrier = interpolateCarrierFrequency(points, time, interpolationType, splineTension)
            val beat = interpolateBeatFrequency(points, time, interpolationType, splineTension)
            virtualPoints.add(FrequencyPoint(time, carrier, beat))
        } else {
            // Нечётный индекс - снижающая точка
            val baseCarrier = interpolateCarrierFrequency(points, time, interpolationType, splineTension)
            val baseBeat = interpolateBeatFrequency(points, time, interpolationType, splineTension)
            virtualPoints.add(FrequencyPoint(
                time,
                baseCarrier * (1.0f - carrierReduction),
                baseBeat * (1.0f - beatReduction)
            ))
        }
        
        currentSeconds += intervalSeconds
        index++
    }
    
    return virtualPoints.sortedBy { it.time.toSecondOfDay() }
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
    modifier: Modifier = Modifier
) {
    val sortedPoints = points.sortedBy { it.time.toSecondOfDay() }
    val currentTime = remember { mutableStateOf(LocalTime(12, 0)) }
    var dragState by remember { mutableStateOf(PointDragState()) }
    var showRangeDialog by remember { mutableStateOf(false) }
    var editingRangeType by remember { mutableStateOf<RangeType?>(null) }
    var tempRangeValue by remember { mutableStateOf("") }
    
    // Локализованный формат Гц - объявляем здесь для использования во всём компоненте
    val hzFormat = stringResource(R.string.hz_value_format)
    
    // Инициализируем текущим временем сразу при первом отображении
    LaunchedEffect(Unit) {
        val now = Clock.System.now()
        currentTime.value = now.toLocalDateTime(TimeZone.currentSystemDefault()).time
    }
    
    // Обновляем время каждые 5 секунд при воспроизведении
    // При паузе не обновляем, но и не сбрасываем в 12:00
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (true) {
                kotlinx.coroutines.delay(5000)
                val now = Clock.System.now()
                currentTime.value = now.toLocalDateTime(TimeZone.currentSystemDefault()).time
            }
        }
        // При isPlaying = false НЕ сбрасываем время - оставляем текущее
    }
    
    val currentLocalTime = currentTime.value
    val density = LocalDensity.current

    // Используем кэшированные sortedPoints если доступны (оптимизация)
    val displayPoints = remember(points) { points.sortedBy { it.time.toSecondOfDay() } }
    
    // Генерируем виртуальные точки режима расслабления с учётом типа интерполяции
    val virtualPoints = remember(points, relaxationModeSettings, interpolationType, splineTension) {
        generateRelaxationVirtualPoints(points, relaxationModeSettings, interpolationType, splineTension)
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
            val relaxationColor = MaterialTheme.colorScheme.tertiary

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
            
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawGraphContent(
                            sortedPoints = allPoints,
                            realPoints = displayPoints,
                            graphParams = graphParams,
                            currentLocalTime = currentLocalTime,
                            currentCarrierFrequency = currentCarrierFrequency,
                            currentBeatFrequency = currentBeatFrequency,
                            primaryColor = primaryColor,
                            indicatorColor = errorColor,
                            relaxationColor = relaxationColor,
                            interpolationType = interpolationType,
                            splineTension = splineTension,
                            isPlaying = isPlaying,
                            relaxationModeSettings = relaxationModeSettings
                        )
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { offset ->
                                // Добавляем точку при двойном нажатии
                                val time = graphParams.xToTime(offset.x)
                                val carrier = graphParams.yToCarrier(offset.y)
                                // Интерполируем частоту биения на основе соседних точек
                                val interpolatedBeat = if (displayPoints.size >= 2) {
                                    kotlin.math.round(interpolateBeatFrequency(displayPoints, time, interpolationType, splineTension))
                                        .coerceIn(beatRange.min, maxBeatForCarrier(carrier))
                                } else {
                                    beatRange.min
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

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGraphContent(
    sortedPoints: List<FrequencyPoint>,
    realPoints: List<FrequencyPoint>,
    graphParams: GraphParams,
    currentLocalTime: LocalTime,
    currentCarrierFrequency: Float,
    currentBeatFrequency: Float,
    primaryColor: Color,
    indicatorColor: Color,
    relaxationColor: Color,
    interpolationType: InterpolationType,
    splineTension: Float = 0.0f,
    isPlaying: Boolean = false,
    relaxationModeSettings: RelaxationModeSettings = RelaxationModeSettings()
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
        drawBeatArea(sortedPoints, graphParams, primaryColor, interpolationType, splineTension)
        drawCarrierLine(sortedPoints, graphParams, primaryColor, interpolationType, splineTension)
    }
    
    // В режимах STEP и SMOOTH рисуем пунктирную линию базовой кривой (через основные точки)
    if (relaxationModeSettings.enabled && 
        (relaxationModeSettings.mode == RelaxationMode.STEP || relaxationModeSettings.mode == RelaxationMode.SMOOTH) &&
        realPoints.size >= 2) {
        drawDashedBaseCurve(realPoints, graphParams, primaryColor, interpolationType, splineTension)
    }
    
    // Рисуем указатель текущей частоты только если воспроизводится этот график
    if (isPlaying) {
        val currentX = graphParams.timeToX(currentLocalTime)
        val currentCarrierY = graphParams.carrierToY(currentCarrierFrequency)
        val currentUpperY = graphParams.beatUpperY(currentCarrierFrequency, currentBeatFrequency)
        val currentLowerY = graphParams.beatLowerY(currentCarrierFrequency, currentBeatFrequency)
        
        drawLine(color = indicatorColor.copy(alpha = 0.7f), start = Offset(currentX, 0f), end = Offset(currentX, height), strokeWidth = 2f)
        drawCircle(color = indicatorColor, radius = 8f, center = Offset(currentX, currentCarrierY))
        // Вертикальная линия показывающая диапазон частот каналов (от lower до upper)
        drawLine(color = indicatorColor.copy(alpha = 0.5f), start = Offset(currentX, currentUpperY), end = Offset(currentX, currentLowerY), strokeWidth = 3f)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBeatArea(
    sortedPoints: List<FrequencyPoint>,
    params: GraphParams,
    primaryColor: Color,
    interpolationType: InterpolationType,
    splineTension: Float = 0.0f
) {
    val width = size.width
    // Динамическое количество сэмплов: минимум 500, для плавных кривых - больше
    val numSamples = (sortedPoints.size * 4).coerceAtLeast(500)
    
    val upperPath = Path()
    val lowerPath = Path()
    
    // Начинаем с левой границы
    val startTime = LocalTime.fromSecondOfDay(0)
    // Интерполируем частоты каналов НАПРЯМУЮ - каждая кривая проходит через свои точки
    val startUpperFreq = interpolateChannelFrequency(sortedPoints, startTime, interpolationType, splineTension, isUpper = true)
    val startLowerFreq = interpolateChannelFrequency(sortedPoints, startTime, interpolationType, splineTension, isUpper = false)
    val startUpperY = params.carrierToY(startUpperFreq)
    val startLowerY = params.carrierToY(startLowerFreq)
    
    upperPath.moveTo(0f, startUpperY)
    lowerPath.moveTo(0f, startLowerY)
    
    // Для ступенчатой интерполяции используем специальный алгоритм отрисовки
    if (interpolationType == InterpolationType.STEP) {
        // Рисуем ступеньки напрямую по точкам
        // Каждая ступенька: горизонтальная линия от текущей точки до X следующей точки, затем вертикальный переход
        
        // Находим значение на левой границе (до первой точки)
        val firstPointX = params.timeToX(sortedPoints.first().time)
        val firstUpperY = params.carrierToY(sortedPoints.last().carrierFrequency + sortedPoints.last().beatFrequency / 2.0f)
        val firstLowerY = params.carrierToY(sortedPoints.last().carrierFrequency - sortedPoints.last().beatFrequency / 2.0f)
        
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
            
            val currentUpperY = params.carrierToY(currentPoint.carrierFrequency + currentPoint.beatFrequency / 2.0f)
            val currentLowerY = params.carrierToY(currentPoint.carrierFrequency - currentPoint.beatFrequency / 2.0f)
            
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
    } else {
        // Обычная интерполяция для других типов
        for (i in 1..numSamples) {
            val t = i.toDouble() / numSamples
            val time = LocalTime.fromSecondOfDay((t * 24 * 3600).toInt().coerceAtMost(86399))
            // Интерполируем частоты каналов НАПРЯМУЮ
            val upperFreq = interpolateChannelFrequency(sortedPoints, time, interpolationType, splineTension, isUpper = true)
            val lowerFreq = interpolateChannelFrequency(sortedPoints, time, interpolationType, splineTension, isUpper = false)
            val upperY = params.carrierToY(upperFreq)
            val lowerY = params.carrierToY(lowerFreq)
            val x = (t * width).toFloat()
            upperPath.lineTo(x, upperY)
            lowerPath.lineTo(x, lowerY)
        }
    }
    
    // Замыкаем путь для заливки
    val combinedPath = Path()
    combinedPath.addPath(upperPath)
    
    // Обратный путь по нижней границе
    for (i in numSamples downTo 0) {
        val t = i.toDouble() / numSamples
        val time = LocalTime.fromSecondOfDay((t * 24 * 3600).toInt().coerceAtMost(86399))
        val lowerFreq = interpolateChannelFrequency(sortedPoints, time, interpolationType, splineTension, isUpper = false)
        val lowerY = params.carrierToY(lowerFreq)
        val x = (t * width).toFloat()
        combinedPath.lineTo(x, lowerY)
    }
    
    combinedPath.close()
    
    // Заливка области биений
    drawPath(path = combinedPath, color = primaryColor.copy(alpha = 0.2f), style = Fill)
    // Границы области (кривые частот каналов)
    drawPath(path = upperPath, color = primaryColor.copy(alpha = 0.4f), style = Stroke(width = 1f))
    drawPath(path = lowerPath, color = primaryColor.copy(alpha = 0.4f), style = Stroke(width = 1f))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCarrierLine(
    sortedPoints: List<FrequencyPoint>,
    params: GraphParams,
    primaryColor: Color,
    interpolationType: InterpolationType,
    splineTension: Float = 0.0f
) {
    val width = size.width
    val carrierPath = Path()
    
    // Начинаем с левой границы (время 0)
    val startTime = LocalTime.fromSecondOfDay(0)
    val startCarrier = interpolateCarrierFrequency(sortedPoints, startTime, interpolationType, splineTension)
    val startY = params.carrierToY(startCarrier)
    carrierPath.moveTo(0f, startY)
    
    // Для ступенчатой интерполяции рисуем ступеньки напрямую по точкам
    if (interpolationType == InterpolationType.STEP) {
        // Находим значение на левой границе (до первой точки) - это значение последней точки (переход через полночь)
        val firstPointX = params.timeToX(sortedPoints.first().time)
        val lastCarrierY = params.carrierToY(sortedPoints.last().carrierFrequency)
        
        // От левой границы до первой точки - значение последней точки
        carrierPath.lineTo(firstPointX, lastCarrierY)
        
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
            
            val currentCarrierY = params.carrierToY(currentPoint.carrierFrequency)
            
            // Вертикальный переход в точке
            carrierPath.lineTo(currentX, currentCarrierY)
            // Горизонтальная линия до следующей точки
            carrierPath.lineTo(nextX, currentCarrierY)
        }
    } else {
        // Обычная интерполяция для других типов
        // Динамическое количество сэмплов: минимум 500, для плавных кривых - больше
        val numSamples = (sortedPoints.size * 4).coerceAtLeast(500)
        for (i in 1..numSamples) {
            val t = i.toDouble() / numSamples
            val time = LocalTime.fromSecondOfDay((t * 24 * 3600).toInt().coerceAtMost(86399))
            val carrier = interpolateCarrierFrequency(sortedPoints, time, interpolationType, splineTension)
            val y = params.carrierToY(carrier)
            val x = (t * width).toFloat()
            carrierPath.lineTo(x, y)
        }
    }
    
    drawPath(path = carrierPath, color = primaryColor.copy(alpha = 0.6f), style = Stroke(width = 3f))
}

private enum class RangeType { MIN, MAX }

/**
 * Рисует пунктирную линию базовой кривой (проходящей через основные точки)
 * Используется в режимах ADVANCED и SMOOTH для отображения исходной кривой
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDashedBaseCurve(
    realPoints: List<FrequencyPoint>,
    params: GraphParams,
    primaryColor: Color,
    interpolationType: InterpolationType,
    splineTension: Float
) {
    val width = size.width
    val carrierPath = Path()
    
    // Начинаем с левой границы (время 0)
    val startTime = LocalTime.fromSecondOfDay(0)
    val startCarrier = interpolateCarrierFrequency(realPoints, startTime, interpolationType, splineTension)
    val startY = params.carrierToY(startCarrier)
    carrierPath.moveTo(0f, startY)
    
    // Динамическое количество сэмплов
    val numSamples = (realPoints.size * 2).coerceAtLeast(300)
    for (i in 1..numSamples) {
        val t = i.toDouble() / numSamples
        val time = LocalTime.fromSecondOfDay((t * 24 * 3600).toInt().coerceAtMost(86399))
        val carrier = interpolateCarrierFrequency(realPoints, time, interpolationType, splineTension)
        val y = params.carrierToY(carrier)
        val x = (t * width).toFloat()
        carrierPath.lineTo(x, y)
    }
    
    // Рисуем пунктирной линией
    val dashPattern = floatArrayOf(10f, 10f)
    drawPath(
        path = carrierPath,
        color = primaryColor.copy(alpha = 0.3f),
        style = Stroke(
            width = 2f,
            pathEffect = PathEffect.dashPathEffect(dashPattern)
        )
    )
}

/**
 * Вычисляет максимальную частоту биений для верхней границы (2000 Гц).
 * Формула: carrier + beat/2 <= MAX_FREQUENCY => beat <= 2 * (MAX_FREQUENCY - carrier)
 */
fun maxBeatForUpperLimit(carrierFrequency: Float): Float {
    return ((MAX_FREQUENCY - carrierFrequency) * 2).coerceAtLeast(0.0f)
}

/**
 * Вычисляет скорректированную частоту биения для заданной несущей частоты.
 * Учитывает обе границы: нижнюю (20 Гц) и верхнюю (2000 Гц).
 * Нижняя граница: carrier - beat/2 >= MIN_AUDIBLE_FREQUENCY => beat <= 2 * (carrier - MIN_AUDIBLE_FREQUENCY)
 * Верхняя граница: carrier + beat/2 <= MAX_FREQUENCY => beat <= 2 * (MAX_FREQUENCY - carrier)
 */
fun adjustBeatForCarrier(carrier: Float, currentBeat: Float): Float {
    val maxBeatForLower = maxBeatForCarrier(carrier)  // для нижней границы (20 Гц)
    val maxBeatForUpper = maxBeatForUpperLimit(carrier)  // для верхней границы (2000 Гц)
    val maxBeat = minOf(maxBeatForLower, maxBeatForUpper)
    return currentBeat.coerceAtMost(maxBeat)
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
        val beatIndicatorSize = with(density) { ((point.beatFrequency / maxBeat) * 12).toFloat().toDp().coerceAtLeast(4.dp) }
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
fun interpolateCarrierFrequency(
    points: List<FrequencyPoint>, 
    time: LocalTime, 
    interpolationType: InterpolationType = InterpolationType.LINEAR,
    splineTension: Float = 0.0f
): Float = interpolateFrequency(points, time, interpolationType, splineTension) { it.carrierFrequency }

fun interpolateBeatFrequency(
    points: List<FrequencyPoint>, 
    time: LocalTime, 
    interpolationType: InterpolationType = InterpolationType.LINEAR,
    splineTension: Float = 0.0f
): Float = interpolateFrequency(points, time, interpolationType, splineTension) { it.beatFrequency }

/**
 * Интерполяция частоты канала (верхнего или нижнего)
 * Каждая кривая канала проходит через точки: carrier ± beat/2
 * Это означает что интерполяция применяется К КАЖДОЙ кривой отдельно
 * 
 * @param isUpper true = верхний канал (carrier + beat/2), false = нижний (carrier - beat/2)
 */
fun interpolateChannelFrequency(
    points: List<FrequencyPoint>, 
    time: LocalTime, 
    interpolationType: InterpolationType = InterpolationType.LINEAR,
    splineTension: Float = 0.0f,
    isUpper: Boolean
): Float {
    // Селектор, который вычисляет частоту канала для каждой точки
    val channelSelector: (FrequencyPoint) -> Float = { point ->
        if (isUpper) {
            point.carrierFrequency + point.beatFrequency / 2.0f
        } else {
            point.carrierFrequency - point.beatFrequency / 2.0f
        }
    }
    return interpolateFrequency(points, time, interpolationType, splineTension, channelSelector)
}

fun interpolateFrequency(
    points: List<FrequencyPoint>, 
    time: LocalTime, 
    interpolationType: InterpolationType = InterpolationType.LINEAR,
    splineTension: Float = 0.0f,
    frequencySelector: (FrequencyPoint) -> Float
): Float {
    val sortedPoints = points.sortedBy { it.time.toSecondOfDay() }
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
    
    val ratio = (t - t1).toFloat() / (t2 - t1)
    
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

