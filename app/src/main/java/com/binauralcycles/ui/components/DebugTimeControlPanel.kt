package com.binauralcycles.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.binauralcycles.viewmodel.BinauralViewModel
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalTime
import kotlin.math.roundToInt

/**
 * DEBUG-панель управления виртуальным временем суток.
 * Рендерится только в debug-сборке (гейтинг в месте вызова).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugTimeControlPanel(viewModel: BinauralViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    // Текущее время живёт в телеметрии (тикает раз в секунду), а не в uiState.
    val currentTime by remember { viewModel.telemetry.map { it.currentTime } }
        .collectAsState(initial = LocalTime(12, 0))

    // Локальное состояние слайдера времени, чтобы не "воевать"
    // с обновляющимся во время перетаскивания значением.
    var timeSliderSeconds by remember { mutableIntStateOf(currentTime.toSecondOfDay()) }
    var isDraggingTime by remember { mutableStateOf(false) }
    LaunchedEffect(currentTime) {
        if (!isDraggingTime) {
            timeSliderSeconds = currentTime.toSecondOfDay()
        }
    }

    var scaleSlider by remember { mutableFloatStateOf(uiState.debugTimeScale) }
    LaunchedEffect(uiState.debugTimeScale) {
        scaleSlider = uiState.debugTimeScale
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            Text(
                text = "DEBUG: виртуальное время",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )

            // Вкл/выкл
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = uiState.debugVirtualTimeEnabled,
                    onCheckedChange = { viewModel.setDebugVirtualTimeEnabled(it) }
                )
                Spacer(Modifier.width(8.dp))
                Text("Отвязать от реального времени")
            }

            if (uiState.debugVirtualTimeEnabled) {
                // Текущее виртуальное время
                Text(
                    text = "Текущее время: ${formatTime(currentTime.toSecondOfDay())}",
                    style = MaterialTheme.typography.bodyLarge
                )

                // Scrub времени суток
                Text("Время суток (scrub)", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = timeSliderSeconds.toFloat(),
                    onValueChange = { v ->
                        isDraggingTime = true
                        val snapped = (v / 60).roundToInt() * 60 // шаг 1 минута
                        timeSliderSeconds = snapped
                        viewModel.debugScrubTime(snapped)
                    },
                    onValueChangeFinished = { isDraggingTime = false },
                    valueRange = 0f..86399f
                )

                // Быстрые пресеты времени
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 6, 12, 18).forEach { hour ->
                        AssistChip(
                            onClick = { viewModel.debugScrubTime(hour * 3600) },
                            label = { Text("%02d:00".format(hour)) }
                        )
                    }
                }

                // Ускорение
                Text(
                    text = "Ускорение: ${uiState.debugTimeScale.roundToInt()}x",
                    style = MaterialTheme.typography.labelLarge
                )
                Slider(
                    value = scaleSlider,
                    onValueChange = { v ->
                        scaleSlider = v
                        viewModel.debugSetTimeScale(v.roundToInt().toFloat())
                    },
                    valueRange = 1f..60f,
                    steps = 58 // целые значения 1..60
                )

                // Play/Pause виртуального времени + сброс
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { viewModel.debugSetVirtualTimeRunning(!uiState.debugVirtualTimeRunning) }
                    ) {
                        Icon(
                            imageVector = if (uiState.debugVirtualTimeRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (uiState.debugVirtualTimeRunning) "Пауза времени" else "Ход времени"
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { viewModel.debugResetToRealTime() }) {
                        Text("К реальному времени")
                    }
                }
            }
        }
    }
}

private fun formatTime(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}