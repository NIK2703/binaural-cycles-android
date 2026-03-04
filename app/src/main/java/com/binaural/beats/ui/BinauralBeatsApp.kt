package com.binaural.beats.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.binaural.beats.viewmodel.BinauralViewModel
import com.binaural.core.audio.engine.SampleRate
import com.binaural.core.audio.model.FrequencyPoint
import com.binaural.core.audio.model.FrequencyRange
import com.binaural.core.ui.theme.BeatFrequencyColor
import com.binaural.core.ui.theme.CarrierFrequencyColor
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs

// Порог для определения направления перетаскивания
private const val DRAG_DIRECTION_THRESHOLD = 10f

// Минимальная слышимая частота
private const val MIN_AUDIBLE_FREQUENCY = 20.0

@Composable
fun BinauralBeatsApp(
    viewModel: BinauralViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Заголовок
        Text(
            text = "Бинауральные Ритмы",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Текущие частоты
        CurrentFrequenciesCard(
            beatFrequency = uiState.currentBeatFrequency,
            carrierFrequency = uiState.currentCarrierFrequency,
            isPlaying = uiState.isPlaying
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Единый график частот с перетаскиваемыми точками
        FrequencyGraph(
            points = uiState.frequencyCurve.points,
            selectedPointIndex = uiState.selectedPointIndex,
            currentCarrierFrequency = uiState.currentCarrierFrequency,
            currentBeatFrequency = uiState.currentBeatFrequency,
            carrierRange = uiState.carrierRange,
            beatRange = uiState.beatRange,
            isPlaying = uiState.isPlaying,
            onPointSelected = { viewModel.selectPoint(it) },
            onPointTimeChanged = { index, newTime ->
                viewModel.updatePointTimeDirect(index, newTime)
            },
            onPointCarrierChanged = { index, newCarrier ->
                viewModel.updatePointCarrierFrequencyDirect(index, newCarrier)
            },
            onAddPoint = { time, carrier, beat ->
                viewModel.addPoint(time, carrier, beat)
            },
            onCarrierRangeChange = { min, max -> viewModel.updateCarrierRange(min, max) },
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Редактирование выбранной точки
        if (uiState.selectedPointIndex != null) {
            val points = uiState.frequencyCurve.points
            
            if (uiState.selectedPointIndex!! in points.indices) {
                val selectedPoint = points[uiState.selectedPointIndex!!]
                PointEditor(
                    point = selectedPoint,
                    carrierRange = uiState.carrierRange,
                    onCarrierFrequencyChange = { viewModel.updatePointCarrierFrequency(it) },
                    onBeatFrequencyChange = { viewModel.updatePointBeatFrequency(it) },
                    onRemove = { viewModel.removePoint(uiState.selectedPointIndex!!) },
                    onDeselect = { viewModel.deselectPoint() }
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Настройки каналов и нормализации
        ChannelSettingsCard(
            channelSwapEnabled = uiState.channelSwapEnabled,
            channelSwapIntervalSeconds = uiState.channelSwapIntervalSeconds,
            isChannelsSwapped = uiState.isChannelsSwapped,
            volumeNormalizationEnabled = uiState.volumeNormalizationEnabled,
            volumeNormalizationStrength = uiState.volumeNormalizationStrength,
            sampleRate = uiState.sampleRate,
            currentLeftFreq = uiState.currentCarrierFrequency - uiState.currentBeatFrequency / 2.0,
            currentRightFreq = uiState.currentCarrierFrequency + uiState.currentBeatFrequency / 2.0,
            onChannelSwapEnabledChange = { viewModel.setChannelSwapEnabled(it) },
            onChannelSwapIntervalChange = { viewModel.setChannelSwapInterval(it) },
            onVolumeNormalizationEnabledChange = { viewModel.setVolumeNormalizationEnabled(it) },
            onVolumeNormalizationStrengthChange = { viewModel.setVolumeNormalizationStrength(it) },
            onSampleRateChange = { viewModel.setSampleRate(it) }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Громкость
        VolumeSlider(
            volume = uiState.volume,
            onVolumeChange = { viewModel.setVolume(it) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Кнопка воспроизведения
        PlayButton(
            isPlaying = uiState.isPlaying,
            onClick = { viewModel.togglePlayback() }
        )
    }
}

@Composable
fun CurrentFrequenciesCard(
    beatFrequency: Double,
    carrierFrequency: Double,
    isPlaying: Boolean
) {
    // Вычисляем реальные частоты каналов: carrier ± beat/2
    val leftChannelFreq = carrierFrequency - beatFrequency / 2.0
    val rightChannelFreq = carrierFrequency + beatFrequency / 2.0
    
    // Проверяем, не опускается ли левый канал ниже слышимого диапазона
    val isLeftChannelTooLow = leftChannelFreq < MIN_AUDIBLE_FREQUENCY
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isPlaying)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                FrequencyDisplay(
                    label = "Частота биений",
                    value = "%.1f Гц".format(beatFrequency),
                    color = BeatFrequencyColor
                )
                FrequencyDisplay(
                    label = "Несущая частота",
                    value = "%.0f Гц".format(carrierFrequency),
                    color = CarrierFrequencyColor
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Реальные частоты каналов
            Text(
                text = "Каналы:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                FrequencyDisplay(
                    label = "Левый",
                    value = "%.1f Гц".format(leftChannelFreq),
                    color = if (isLeftChannelTooLow) Color.Red else Color(0xFF2196F3)
                )
                FrequencyDisplay(
                    label = "Правый",
                    value = "%.1f Гц".format(rightChannelFreq),
                    color = Color(0xFF4CAF50)
                )
            }
            
            if (isLeftChannelTooLow) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "⚠ Левый канал ниже 20 Гц - не слышно!",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Red,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun FrequencyDisplay(
    label: String,
    value: String,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

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
 * так, чтобы левый канал был >= MIN_AUDIBLE_FREQUENCY
 * leftChannel = carrier - beat/2 >= MIN_AUDIBLE_FREQUENCY
 * beat <= 2 * (carrier - MIN_AUDIBLE_FREQUENCY)
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
    
    // Конвертация времени в X координату (в пикселях)
    fun timeToX(time: LocalTime): Float {
        val seconds = time.toSecondOfDay()
        return (seconds / (24.0 * 3600) * widthPx).toFloat()
    }
    
    // Конвертация несущей частоты в Y координату (в пикселях)
    fun carrierToY(carrier: Double): Float {
        return heightPx - ((carrier - carrierRange.min) / carrierRangeSize * heightPx).toFloat()
    }
    
    // Конвертация X координаты в время
    fun xToTime(x: Float): LocalTime {
        val seconds = (x / widthPx * 24 * 3600).toInt().coerceIn(0, 86399)
        return LocalTime.fromSecondOfDay(seconds)
    }
    
    // Конвертация Y координаты в несущую частоту
    fun yToCarrier(y: Float): Double {
        val carrier = carrierRange.min + (1.0 - y / heightPx) * carrierRangeSize
        return carrierRange.clamp(kotlin.math.round(carrier))
    }
    
    // Ширина области биений в пикселях
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
    // Сортируем точки по времени
    val sortedPoints = points.sortedBy { it.time.toSecondOfDay() }
    
    // Текущее время - обновляем только когда воспроизводится аудио
    val currentTime = remember { mutableStateOf(LocalTime(12, 0)) }
    
    // Состояние перетаскивания
    var dragState by remember { mutableStateOf(PointDragState()) }
    
    // Состояние диалога редактирования диапазона
    var showRangeDialog by remember { mutableStateOf(false) }
    var editingRangeType by remember { mutableStateOf<RangeType?>(null) }
    var tempRangeValue by remember { mutableStateOf("") }
    
    // Обновляем время только при воспроизведении, и с меньшей частотой (каждые 5 секунд)
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (true) {
                kotlinx.coroutines.delay(5000)
                val now = Clock.System.now()
                val zone = TimeZone.currentSystemDefault()
                val localDateTime = now.toLocalDateTime(zone)
                currentTime.value = localDateTime.time
            }
        }
    }
    
    // При запуске получаем текущее время один раз
    LaunchedEffect(Unit) {
        val now = Clock.System.now()
        val zone = TimeZone.currentSystemDefault()
        val localDateTime = now.toLocalDateTime(zone)
        currentTime.value = localDateTime.time
    }
    
    val currentLocalTime = currentTime.value
    val density = LocalDensity.current

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Получаем размеры в пикселях
            val widthPx = with(density) { maxWidth.roundToPx() }
            val heightPx = with(density) { maxHeight.roundToPx() }
            
            // Создаем параметры графика
            val graphParams = remember(widthPx, heightPx, carrierRange, beatRange) {
                GraphParams(widthPx, heightPx, carrierRange, beatRange)
            }

            // График с Canvas
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val width = size.width
                        val height = size.height

                        // Рисуем сетку
                        val gridColor = Color.Gray.copy(alpha = 0.2f)
                        
                        // Горизонтальные линии (несущая частота)
                        for (i in 0..4) {
                            val y = height * i / 4
                            drawLine(
                                color = gridColor,
                                start = Offset(0f, y),
                                end = Offset(width, y),
                                strokeWidth = 1f
                            )
                        }
                        
                        // Вертикальные линии (время)
                        for (i in 0..4) {
                            val x = width * i / 4
                            drawLine(
                                color = gridColor,
                                start = Offset(x, 0f),
                                end = Offset(x, height),
                                strokeWidth = 1f
                            )
                        }

                        // Рисуем область биений (вокруг несущей частоты)
                        if (sortedPoints.size >= 2) {
                            drawBeatArea(sortedPoints, graphParams)
                        }

                        // Рисуем линию несущей частоты
                        if (sortedPoints.size >= 2) {
                            drawCarrierLine(sortedPoints, graphParams)
                        }
                        
                        // Текущее время - вертикальная линия
                        val currentX = graphParams.timeToX(currentLocalTime)
                        val currentCarrierY = graphParams.carrierToY(currentCarrierFrequency)
                        val currentBeatWidth = graphParams.beatWidth(currentBeatFrequency)
                        
                        drawLine(
                            color = Color.Red.copy(alpha = 0.7f),
                            start = Offset(currentX, 0f),
                            end = Offset(currentX, height),
                            strokeWidth = 2f
                        )
                        
                        drawCircle(
                            color = Color.Red,
                            radius = 8f,
                            center = Offset(currentX, currentCarrierY)
                        )
                        
                        drawLine(
                            color = Color.Red.copy(alpha = 0.5f),
                            start = Offset(currentX - currentBeatWidth, currentCarrierY),
                            end = Offset(currentX + currentBeatWidth, currentCarrierY),
                            strokeWidth = 3f
                        )
                    }
            ) {
                // Точки на графике
                sortedPoints.forEachIndexed { sortedIndex, point ->
                    val originalIndex = points.indexOf(point)
                    val isSelected = selectedPointIndex == originalIndex
                    
                    val prevPoint = sortedPoints.getOrNull(sortedIndex - 1)
                    val nextPoint = sortedPoints.getOrNull(sortedIndex + 1)
                    
                    val minTimeSeconds = prevPoint?.time?.toSecondOfDay()?.plus(60) ?: 0
                    val maxTimeSeconds = nextPoint?.time?.toSecondOfDay()?.minus(60) ?: (24 * 3600 - 60)
                    
                    // Вычисляем позицию точки с учётом перетаскивания
                    // Точка двигается визуально сразу, даже до определения направления
                    val displayTime = if (dragState.startIndex == originalIndex && dragState.currentTime != null) {
                        dragState.currentTime!!
                    } else point.time
                    
                    // Если перетаскивание начато, но направление ещё не определено, показываем текущую позицию
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
                
                // Отображение значения при перетаскивании (над точкой)
                if (dragState.startIndex >= 0 && dragState.currentTime != null) {
                    val previewXPx = graphParams.timeToX(dragState.currentTime!!)
                    val previewYPx = graphParams.carrierToY(dragState.currentCarrier)
                    
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(previewXPx.toInt() - 30, previewYPx.toInt() - 75) }
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.inverseSurface,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                when (dragState.direction) {
                                    DragDirection.HORIZONTAL -> {
                                        Text(
                                            text = "%02d:%02d".format(
                                                dragState.currentTime!!.hour,
                                                dragState.currentTime!!.minute
                                            ),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.inverseOnSurface
                                        )
                                    }
                                    DragDirection.VERTICAL -> {
                                        Text(
                                            text = "%.0f Гц".format(dragState.currentCarrier),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.inverseOnSurface
                                        )
                                    }
                                    DragDirection.NONE -> {
                                        // До определения направления показываем обе координаты
                                        Text(
                                            text = "%02d:%02d / %.0f Гц".format(
                                                dragState.currentTime!!.hour,
                                                dragState.currentTime!!.minute,
                                                dragState.currentCarrier
                                            ),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.inverseOnSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Кликабельные подписи осей Y (мин/макс несущей частоты)
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (-8).dp)
            ) {
                // Максимальная частота (вверху) - кликабельная
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = CarrierFrequencyColor.copy(alpha = 0.1f),
                    modifier = Modifier
                        .clickable {
                            editingRangeType = RangeType.MAX
                            tempRangeValue = "%.0f".format(carrierRange.max)
                            showRangeDialog = true
                        }
                ) {
                    Text(
                        text = "%.0f".format(carrierRange.max),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = CarrierFrequencyColor,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
                
                Spacer(modifier = Modifier.weight(1f))
                
                // Минимальная частота (внизу) - кликабельная
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = CarrierFrequencyColor.copy(alpha = 0.1f),
                    modifier = Modifier
                        .clickable {
                            editingRangeType = RangeType.MIN
                            tempRangeValue = "%.0f".format(carrierRange.min)
                            showRangeDialog = true
                        }
                ) {
                    Text(
                        text = "%.0f Гц".format(carrierRange.min),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = CarrierFrequencyColor,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

            // Ось X (время)
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("0ч", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("6ч", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("12ч", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("18ч", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("24ч", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            
            // Текущее время
            Text(
                text = "%02d:%02d".format(currentLocalTime.hour, currentLocalTime.minute),
                style = MaterialTheme.typography.labelSmall,
                color = Color.Red,
                modifier = Modifier.align(Alignment.TopEnd)
            )
            
            // Легенда и кнопка добавления
            Row(
                modifier = Modifier.align(Alignment.TopStart),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(12.dp).background(CarrierFrequencyColor, CircleShape))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Несущая", style = MaterialTheme.typography.labelSmall, color = CarrierFrequencyColor)
                Spacer(modifier = Modifier.width(8.dp))
                Box(modifier = Modifier.size(12.dp).background(BeatFrequencyColor.copy(alpha = 0.5f), RoundedCornerShape(2.dp)))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Биения", style = MaterialTheme.typography.labelSmall, color = BeatFrequencyColor)
                
                Spacer(modifier = Modifier.width(16.dp))
                
                // Кнопка добавления точки
                IconButton(
                    onClick = {
                        val now = Clock.System.now()
                        val zone = TimeZone.currentSystemDefault()
                        val localTime = now.toLocalDateTime(zone).time
                        onAddPoint(localTime, carrierRange.min + (carrierRange.max - carrierRange.min) / 2, beatRange.min)
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Добавить точку",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
    
    // Диалог редактирования диапазона
    if (showRangeDialog) {
        AlertDialog(
            onDismissRequest = { showRangeDialog = false },
            title = { 
                Text(if (editingRangeType == RangeType.MIN) "Мин. несущая частота" else "Макс. несущая частота")
            },
            text = {
                OutlinedTextField(
                    value = tempRangeValue,
                    onValueChange = { tempRangeValue = it },
                    label = { Text("Частота (Гц)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val value = tempRangeValue.toDoubleOrNull()
                        if (value != null && value >= MIN_AUDIBLE_FREQUENCY) {
                            val newMin = if (editingRangeType == RangeType.MIN) value else carrierRange.min
                            val newMax = if (editingRangeType == RangeType.MAX) value else carrierRange.max
                            if (newMin < newMax) {
                                onCarrierRangeChange(newMin, newMax)
                            }
                        }
                        showRangeDialog = false
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRangeDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}

/**
 * Рисует область биений с правильным переходом через полночь
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBeatArea(
    sortedPoints: List<FrequencyPoint>,
    params: GraphParams
) {
    val width = size.width
    val height = size.height
    val numSamples = 100
    
    val first = sortedPoints.first()
    val last = sortedPoints.last()
    
    // Вычисляем значения на границах
    val lastBeatWidth = params.beatWidth(last.beatFrequency)
    val lastCarrierY = params.carrierToY(last.carrierFrequency)
    val firstBeatWidth = params.beatWidth(first.beatFrequency)
    val firstCarrierY = params.carrierToY(first.carrierFrequency)
    
    val upperPath = Path()
    val lowerPath = Path()
    
    // Начинаем от левого края (x=0) с Y последней точки (переход через полночь)
    upperPath.moveTo(0f, lastCarrierY - lastBeatWidth)
    lowerPath.moveTo(0f, lastCarrierY + lastBeatWidth)
    
    // Рисуем от 0 до 24 часов
    for (i in 0..numSamples) {
        val t = i.toDouble() / numSamples
        val seconds = (t * 24 * 3600).toInt().coerceAtMost(86399)
        val time = LocalTime.fromSecondOfDay(seconds)
        
        val carrierFreq = interpolateCarrierFrequency(sortedPoints, time)
        val beatFreq = interpolateBeatFrequency(sortedPoints, time)
        
        val beatWidth = params.beatWidth(beatFreq)
        val x = (t * width).toFloat()
        val carrierY = params.carrierToY(carrierFreq)
        
        upperPath.lineTo(x, carrierY - beatWidth)
        lowerPath.lineTo(x, carrierY + beatWidth)
    }
    
    // Завершаем на правом краю (x=width) с Y первой точки
    upperPath.lineTo(width, firstCarrierY - firstBeatWidth)
    lowerPath.lineTo(width, firstCarrierY + firstBeatWidth)
    
    // Создаем замкнутый путь
    val combinedPath = Path()
    combinedPath.addPath(upperPath)
    
    // Обратный путь по нижней границе
    for (i in numSamples downTo 0) {
        val t = i.toDouble() / numSamples
        val seconds = (t * 24 * 3600).toInt().coerceAtMost(86399)
        val time = LocalTime.fromSecondOfDay(seconds)
        val carrierFreq = interpolateCarrierFrequency(sortedPoints, time)
        val beatFreq = interpolateBeatFrequency(sortedPoints, time)
        val beatWidth = params.beatWidth(beatFreq)
        val x = (t * width).toFloat()
        val carrierY = params.carrierToY(carrierFreq)
        combinedPath.lineTo(x, carrierY + beatWidth)
    }
    
    // Замыкаем путь - возвращаемся к начальной точке
    combinedPath.lineTo(0f, lastCarrierY + lastBeatWidth)
    combinedPath.close()
    
    drawPath(
        path = combinedPath,
        color = BeatFrequencyColor.copy(alpha = 0.3f),
        style = Fill
    )
    
    drawPath(
        path = upperPath,
        color = BeatFrequencyColor.copy(alpha = 0.6f),
        style = Stroke(width = 1f)
    )
    drawPath(
        path = lowerPath,
        color = BeatFrequencyColor.copy(alpha = 0.6f),
        style = Stroke(width = 1f)
    )
}

/**
 * Рисует линию несущей частоты с правильным переходом через полночь
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCarrierLine(
    sortedPoints: List<FrequencyPoint>,
    params: GraphParams
) {
    val width = size.width
    val carrierPath = Path()
    
    val first = sortedPoints.first()
    val last = sortedPoints.last()
    
    val firstX = params.timeToX(first.time)
    val firstY = params.carrierToY(first.carrierFrequency)
    val lastX = params.timeToX(last.time)
    val lastY = params.carrierToY(last.carrierFrequency)
    
    // Линия от левого края (0ч) до первой точки
    // Используем значение последней точки для перехода через полночь
    carrierPath.moveTo(0f, lastY)
    carrierPath.lineTo(firstX, firstY)
    
    // Основная линия через все точки
    for (i in sortedPoints.indices) {
        val point = sortedPoints[i]
        val x = params.timeToX(point.time)
        val y = params.carrierToY(point.carrierFrequency)
        carrierPath.lineTo(x, y)
    }
    
    // Линия от последней точки до правого края (24ч)
    // Используем значение первой точки для перехода через полночь
    carrierPath.lineTo(width, firstY)

    drawPath(
        path = carrierPath,
        color = CarrierFrequencyColor,
        style = Stroke(width = 3f)
    )
}

private enum class RangeType {
    MIN, MAX
}

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
    
    // Позиция точки с центрированием
    val offsetX = (xPx - halfSizePx).toInt()
    val offsetY = (yPx - halfSizePx).toInt()
    
    Box(
        modifier = Modifier
            .offset { IntOffset(offsetX, offsetY) }
            .size(pointSize)
            .background(
                if (isSelected) CarrierFrequencyColor else CarrierFrequencyColor.copy(alpha = 0.7f),
                CircleShape
            )
            .border(2.dp, Color.White, CircleShape)
            .clickable { onPointSelected(originalIndex) }
            .pointerInput(originalIndex, point.time, point.carrierFrequency, minTimeSeconds, maxTimeSeconds, graphWidthPx, graphHeightPx, carrierRange) {
                detectDragGestures(
                    onDragStart = { offset ->
                        totalDragX = 0f
                        totalDragY = 0f
                        currentDragDirection = DragDirection.NONE
                        hasDirectionDetermined = false
                        startSeconds = point.time.toSecondOfDay()
                        startCarrier = point.carrierFrequency
                        
                        android.util.Log.d(
                            "DraggablePoint",
                            "onDragStart: index=$originalIndex, offset=$offset, " +
                            "pointCenter=($xPx, $yPx), time=${point.time}, carrier=${point.carrierFrequency}"
                        )
                        
                        onDragStart(originalIndex, point.time, point.carrierFrequency)
                    },
                    onDragEnd = {
                        android.util.Log.d(
                            "DraggablePoint",
                            "onDragEnd: index=$originalIndex, totalDrag=($totalDragX, $totalDragY), " +
                            "direction=$currentDragDirection"
                        )
                        
                        val newTime = calculateTimeFromDrag(
                            startSeconds,
                            totalDragX,
                            minTimeSeconds,
                            maxTimeSeconds,
                            graphWidthPx.toFloat()
                        )
                        val newCarrier = calculateCarrierFromDrag(
                            startCarrier,
                            totalDragY,
                            carrierRange,
                            graphHeightPx.toFloat()
                        )
                        onDragEnd(originalIndex, newTime, newCarrier, currentDragDirection)
                        
                        totalDragX = 0f
                        totalDragY = 0f
                        currentDragDirection = DragDirection.NONE
                        hasDirectionDetermined = false
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        
                        totalDragX += dragAmount.x
                        totalDragY += dragAmount.y
                        
                        if (!hasDirectionDetermined) {
                            if (abs(totalDragX) > DRAG_DIRECTION_THRESHOLD || abs(totalDragY) > DRAG_DIRECTION_THRESHOLD) {
                                currentDragDirection = if (abs(totalDragX) > abs(totalDragY)) {
                                    DragDirection.HORIZONTAL
                                } else {
                                    DragDirection.VERTICAL
                                }
                                hasDirectionDetermined = true
                                
                                android.util.Log.d(
                                    "DraggablePoint",
                                    "Direction determined: $currentDragDirection, totalDrag=($totalDragX, $totalDragY)"
                                )
                            }
                        }
                        
                        // Обновляем позицию в зависимости от направления (или до его определения)
                        when (currentDragDirection) {
                            DragDirection.HORIZONTAL -> {
                                val newTime = calculateTimeFromDrag(
                                    startSeconds,
                                    totalDragX,
                                    minTimeSeconds,
                                    maxTimeSeconds,
                                    graphWidthPx.toFloat()
                                )
                                onDragUpdate(
                                    originalIndex,
                                    newTime,
                                    startCarrier,
                                    DragDirection.HORIZONTAL
                                )
                            }
                            DragDirection.VERTICAL -> {
                                val newCarrier = calculateCarrierFromDrag(
                                    startCarrier,
                                    totalDragY,
                                    carrierRange,
                                    graphHeightPx.toFloat()
                                )
                                onDragUpdate(
                                    originalIndex,
                                    point.time,
                                    newCarrier,
                                    DragDirection.VERTICAL
                                )
                            }
                            DragDirection.NONE -> {
                                // До определения направления - обновляем обе координаты
                                val newTime = calculateTimeFromDrag(
                                    startSeconds,
                                    totalDragX,
                                    minTimeSeconds,
                                    maxTimeSeconds,
                                    graphWidthPx.toFloat()
                                )
                                val newCarrier = calculateCarrierFromDrag(
                                    startCarrier,
                                    totalDragY,
                                    carrierRange,
                                    graphHeightPx.toFloat()
                                )
                                onDragUpdate(
                                    originalIndex,
                                    newTime,
                                    newCarrier,
                                    DragDirection.NONE
                                )
                            }
                        }
                    }
                )
            }
    ) {
        val beatIndicatorSize = with(density) {
            ((point.beatFrequency / maxBeat) * 12).toFloat().toDp().coerceAtLeast(4.dp)
        }
        Box(
            modifier = Modifier
                .size(beatIndicatorSize)
                .background(BeatFrequencyColor, CircleShape)
                .align(Alignment.Center)
        )
    }
}

private fun calculateTimeFromDrag(
    startSeconds: Int,
    dragX: Float,
    minSeconds: Int,
    maxSeconds: Int,
    graphWidth: Float
): LocalTime {
    val totalSeconds = 24 * 3600
    val secondsPerPixel = totalSeconds / graphWidth
    val deltaSeconds = (dragX * secondsPerPixel).toInt()
    val newSeconds = (startSeconds + deltaSeconds).coerceIn(minSeconds, maxSeconds)
    return LocalTime.fromSecondOfDay(newSeconds)
}

private fun calculateCarrierFromDrag(
    startCarrier: Double,
    dragY: Float,
    carrierRange: FrequencyRange,
    graphHeight: Float
): Double {
    val hertzPerPixel = (carrierRange.max - carrierRange.min) / graphHeight
    val deltaHertz = -dragY * hertzPerPixel
    val newCarrier = startCarrier + deltaHertz
    // Округляем до целого числа при перетаскивании
    return carrierRange.clamp(kotlin.math.round(newCarrier))
}

// Функция интерполяции несущей частоты
fun interpolateCarrierFrequency(points: List<FrequencyPoint>, time: LocalTime): Double {
    return interpolateFrequency(points, time) { it.carrierFrequency }
}

// Функция интерполяции частоты биений
fun interpolateBeatFrequency(points: List<FrequencyPoint>, time: LocalTime): Double {
    return interpolateFrequency(points, time) { it.beatFrequency }
}

// Общая функция интерполяции
fun interpolateFrequency(
    points: List<FrequencyPoint>, 
    time: LocalTime, 
    frequencySelector: (FrequencyPoint) -> Double
): Double {
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

@Composable
fun PointEditor(
    point: FrequencyPoint,
    carrierRange: FrequencyRange,
    onCarrierFrequencyChange: (Double) -> Unit,
    onBeatFrequencyChange: (Double) -> Unit,
    onRemove: () -> Unit,
    onDeselect: () -> Unit
) {
    // Вычисляем максимальную частоту биений для текущей несущей
    val maxBeatForCurrentCarrier = maxBeatForCarrier(point.carrierFrequency)
    
    // Состояния для слайдеров
    var sliderCarrier by remember(point.carrierFrequency) { mutableStateOf(point.carrierFrequency.toFloat()) }
    var sliderBeat by remember(point.beatFrequency) { mutableStateOf(point.beatFrequency.toFloat()) }
    
    // Состояния для текстовых полей
    var tempCarrierFrequency by remember(point.carrierFrequency) { mutableStateOf(point.carrierFrequency.toString()) }
    var tempBeatFrequency by remember(point.beatFrequency) { mutableStateOf(point.beatFrequency.toString()) }
    
    // Проверка валидности ввода
    val carrierValue = tempCarrierFrequency.toDoubleOrNull()
    val beatValue = tempBeatFrequency.toDoubleOrNull()
    
    val isCarrierValid = carrierValue != null && carrierValue >= MIN_AUDIBLE_FREQUENCY && carrierValue <= 2000.0
    val isBeatValid = beatValue != null && beatValue >= 0.125 && beatValue <= maxBeatForCurrentCarrier
    
    // Реальные частоты каналов
    val leftChannel = point.carrierFrequency - point.beatFrequency / 2.0
    val rightChannel = point.carrierFrequency + point.beatFrequency / 2.0
    val isLeftTooLow = leftChannel < MIN_AUDIBLE_FREQUENCY

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = CarrierFrequencyColor.copy(alpha = 0.1f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Точка: %02d:%02d".format(point.time.hour, point.time.minute),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDeselect, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Закрыть",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Несущая частота - текстовое поле и слайдер
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Несущая:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CarrierFrequencyColor,
                    modifier = Modifier.width(80.dp)
                )
                OutlinedTextField(
                    value = tempCarrierFrequency,
                    onValueChange = {
                        tempCarrierFrequency = it
                        val value = it.toDoubleOrNull()
                        if (value != null && value >= MIN_AUDIBLE_FREQUENCY && value <= carrierRange.max) {
                            sliderCarrier = value.toFloat()
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = !isCarrierValid && tempCarrierFrequency.isNotEmpty(),
                    modifier = Modifier.width(100.dp),
                    suffix = { Text("Гц") }
                )
                if (isCarrierValid && carrierValue != null && carrierValue != point.carrierFrequency) {
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            onCarrierFrequencyChange(carrierValue)
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Применить",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            
            // Слайдер несущей частоты (только целые числа)
            Slider(
                value = sliderCarrier,
                onValueChange = {
                    // Округляем до целого при перемещении слайдера
                    val rounded = kotlin.math.round(it)
                    sliderCarrier = rounded
                    tempCarrierFrequency = "%.0f".format(rounded)
                },
                onValueChangeFinished = {
                    // Применяем только целое значение
                    onCarrierFrequencyChange(kotlin.math.round(sliderCarrier).toDouble())
                },
                valueRange = carrierRange.min.toFloat()..carrierRange.max.toFloat(),
                modifier = Modifier.padding(vertical = 4.dp),
                colors = SliderDefaults.colors(
                    thumbColor = CarrierFrequencyColor,
                    activeTrackColor = CarrierFrequencyColor
                )
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Частота биений - текстовое поле и слайдер
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Биения:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BeatFrequencyColor,
                    modifier = Modifier.width(80.dp)
                )
                OutlinedTextField(
                    value = tempBeatFrequency,
                    onValueChange = {
                        tempBeatFrequency = it
                        val value = it.toDoubleOrNull()
                        if (value != null && value >= 0.125 && value <= maxBeatForCurrentCarrier) {
                            sliderBeat = value.toFloat()
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = !isBeatValid && tempBeatFrequency.isNotEmpty(),
                    modifier = Modifier.width(100.dp),
                    suffix = { Text("Гц") }
                )
                if (isBeatValid && beatValue != null && beatValue != point.beatFrequency) {
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            onBeatFrequencyChange(beatValue)
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Применить",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            
            // Подсказка о максимальной частоте биений
            Text(
                text = "Макс. %.1f Гц (лев. канал ≥ 20 Гц)".format(maxBeatForCurrentCarrier),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Слайдер частоты биений (только целые числа, минимум 1 Гц)
            val minBeatForSlider = 1f
            val maxBeatForSlider = maxBeatForCurrentCarrier.toFloat().coerceAtLeast(minBeatForSlider)
            Slider(
                value = sliderBeat.coerceIn(minBeatForSlider, maxBeatForSlider),
                onValueChange = {
                    // Округляем до целого при перемещении слайдера
                    val rounded = kotlin.math.round(it).coerceIn(minBeatForSlider, maxBeatForSlider)
                    sliderBeat = rounded
                    tempBeatFrequency = "%.0f".format(rounded)
                },
                onValueChangeFinished = {
                    // Применяем только целое значение
                    onBeatFrequencyChange(kotlin.math.round(sliderBeat).toDouble())
                },
                valueRange = minBeatForSlider..maxBeatForSlider,
                modifier = Modifier.padding(vertical = 4.dp),
                colors = SliderDefaults.colors(
                    thumbColor = BeatFrequencyColor,
                    activeTrackColor = BeatFrequencyColor
                )
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Отображение реальных частот каналов
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = "Реальные частоты каналов:",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Левый",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "%.1f Гц".format(leftChannel),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isLeftTooLow) Color.Red else Color(0xFF2196F3)
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Правый",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "%.1f Гц".format(rightChannel),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF4CAF50)
                            )
                        }
                    }
                    if (isLeftTooLow) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "⚠ Левый канал < 20 Гц",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Red
                        )
                    }
                }
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun VolumeSlider(
    volume: Float,
    onVolumeChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.VolumeDown,
            contentDescription = "Громкость",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = volume,
            onValueChange = onVolumeChange,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
        )
        Icon(
            imageVector = Icons.Default.VolumeUp,
            contentDescription = "Громкость",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun PlayButton(
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = Modifier.size(72.dp),
        containerColor = if (isPlaying) 
            MaterialTheme.colorScheme.error 
        else 
            MaterialTheme.colorScheme.primary
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
            contentDescription = if (isPlaying) "Стоп" else "Воспроизведение",
            modifier = Modifier.size(36.dp)
        )
    }
}

/**
 * Карточка настроек каналов и нормализации громкости
 */
@Composable
fun ChannelSettingsCard(
    channelSwapEnabled: Boolean,
    channelSwapIntervalSeconds: Int,
    isChannelsSwapped: Boolean,
    volumeNormalizationEnabled: Boolean,
    volumeNormalizationStrength: Float,
    sampleRate: SampleRate,
    currentLeftFreq: Double,
    currentRightFreq: Double,
    onChannelSwapEnabledChange: (Boolean) -> Unit,
    onChannelSwapIntervalChange: (Int) -> Unit,
    onVolumeNormalizationEnabledChange: (Boolean) -> Unit,
    onVolumeNormalizationStrengthChange: (Float) -> Unit,
    onSampleRateChange: (SampleRate) -> Unit
) {
    var showSwapIntervalDialog by remember { mutableStateOf(false) }
    var showSampleRateDialog by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Заголовок
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Настройки каналов",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Частота дискретизации
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Качество аудио",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "%d Гц (%s)".format(
                                sampleRate.value,
                                when (sampleRate) {
                                    SampleRate.LOW -> "экономия батареи"
                                    SampleRate.MEDIUM -> "стандарт"
                                    SampleRate.HIGH -> "высокое"
                                }
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(
                    onClick = { showSampleRateDialog = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Изменить",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Spacer(modifier = Modifier.height(8.dp))
            
            // Перестановка каналов
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = null,
                        tint = if (channelSwapEnabled) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Авто-перестановка каналов",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (channelSwapEnabled) {
                            Text(
                                text = if (isChannelsSwapped) "Л↔П (переставлены)" else "Л-П (норма)",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isChannelsSwapped) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Switch(
                    checked = channelSwapEnabled,
                    onCheckedChange = onChannelSwapEnabledChange
                )
            }
            
            // Интервал перестановки
            if (channelSwapEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 28.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Интервал: ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = { showSwapIntervalDialog = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = formatInterval(channelSwapIntervalSeconds),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Spacer(modifier = Modifier.height(8.dp))
            
            // Нормализация громкости
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Equalizer,
                        contentDescription = null,
                        tint = if (volumeNormalizationEnabled) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Нормализация громкости",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (volumeNormalizationEnabled) {
                            // Нормализация: канал с меньшей частотой = 100%
                            // Канал с большей частотой = мин/макс
                            val minFreq = minOf(currentLeftFreq, currentRightFreq)
                            val leftNormalized = minFreq / currentLeftFreq
                            val rightNormalized = minFreq / currentRightFreq
                            
                            // Применяем силу нормализации
                            val leftFinal = 1.0 + volumeNormalizationStrength * (leftNormalized - 1.0)
                            val rightFinal = 1.0 + volumeNormalizationStrength * (rightNormalized - 1.0)
                            
                            Text(
                                text = "Л: %.1f%%, П: %.1f%%".format(leftFinal * 100, rightFinal * 100),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Switch(
                    checked = volumeNormalizationEnabled,
                    onCheckedChange = onVolumeNormalizationEnabledChange
                )
            }
            
            // Сила нормализации
            if (volumeNormalizationEnabled) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 28.dp, top = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Сила: ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = volumeNormalizationStrength,
                            onValueChange = onVolumeNormalizationStrengthChange,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                            valueRange = 0f..1f
                        )
                        Text(
                            text = "%.0f%%".format(volumeNormalizationStrength * 100),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(40.dp)
                        )
                    }
                }
            }
        }
    }
    
    // Диалог выбора интервала
    if (showSwapIntervalDialog) {
        SwapIntervalDialog(
            currentInterval = channelSwapIntervalSeconds,
            onDismiss = { showSwapIntervalDialog = false },
            onConfirm = { seconds ->
                onChannelSwapIntervalChange(seconds)
                showSwapIntervalDialog = false
            }
        )
    }
    
    // Диалог выбора частоты дискретизации
    if (showSampleRateDialog) {
        SampleRateDialog(
            currentSampleRate = sampleRate,
            onDismiss = { showSampleRateDialog = false },
            onConfirm = { rate ->
                onSampleRateChange(rate)
                showSampleRateDialog = false
            }
        )
    }
}

/**
 * Форматирование интервала в человекочитаемый вид
 */
fun formatInterval(seconds: Int): String {
    return when {
        seconds < 60 -> "$seconds сек"
        seconds < 3600 -> {
            val minutes = seconds / 60
            val secs = seconds % 60
            if (secs == 0) "$minutes мин" else "$minutes мин $secs сек"
        }
        else -> {
            val hours = seconds / 3600
            val minutes = (seconds % 3600) / 60
            if (minutes == 0) "$hours ч" else "$hours ч $minutes мин"
        }
    }
}

/**
 * Диалог выбора интервала перестановки каналов
 */
@Composable
fun SwapIntervalDialog(
    currentInterval: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var selectedInterval by remember { mutableStateOf(currentInterval) }
    
    val presetIntervals = listOf(
        30 to "30 сек",
        60 to "1 мин",
        120 to "2 мин",
        300 to "5 мин",
        600 to "10 мин",
        900 to "15 мин",
        1800 to "30 мин",
        3600 to "1 час"
    )
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Интервал перестановки") },
        text = {
            Column {
                Text(
                    text = "Выберите интервал автоматической перестановки каналов:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                presetIntervals.forEach { (seconds, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedInterval = seconds }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedInterval == seconds,
                            onClick = { selectedInterval = seconds }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedInterval) }) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

/**
 * Диалог выбора частоты дискретизации
 */
@Composable
fun SampleRateDialog(
    currentSampleRate: SampleRate,
    onDismiss: () -> Unit,
    onConfirm: (SampleRate) -> Unit
) {
    var selectedRate by remember { mutableStateOf(currentSampleRate) }
    
    val sampleRateOptions = listOf(
        SampleRate.LOW to "Низкое (22050 Гц) - экономия батареи",
        SampleRate.MEDIUM to "Стандарт (44100 Гц)",
        SampleRate.HIGH to "Высокое (48000 Гц)"
    )
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Качество аудио") },
        text = {
            Column {
                Text(
                    text = "Выберите частоту дискретизации. Более низкая частота снижает расход батареи, но может немного ухудшить качество звука.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                sampleRateOptions.forEach { (rate, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedRate = rate }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedRate == rate,
                            onClick = { selectedRate = rate }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedRate) }) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}
