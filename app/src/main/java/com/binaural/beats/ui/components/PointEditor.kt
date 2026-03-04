package com.binaural.beats.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.binaural.core.audio.model.FrequencyPoint
import com.binaural.core.audio.model.FrequencyRange
import com.binaural.core.ui.theme.BeatFrequencyColor
import com.binaural.core.ui.theme.CarrierFrequencyColor

private const val MIN_AUDIBLE_FREQUENCY = 20.0

@Composable
fun PointEditor(
    point: FrequencyPoint,
    carrierRange: FrequencyRange,
    onCarrierFrequencyChange: (Double) -> Unit,
    onBeatFrequencyChange: (Double) -> Unit,
    onRemove: () -> Unit,
    onDeselect: () -> Unit
) {
    val maxBeatForCurrentCarrier = maxBeatForCarrier(point.carrierFrequency)
    var sliderCarrier by remember(point.carrierFrequency) { mutableStateOf(point.carrierFrequency.toFloat()) }
    var sliderBeat by remember(point.beatFrequency) { mutableStateOf(point.beatFrequency.toFloat()) }
    var tempCarrierFrequency by remember(point.carrierFrequency) { mutableStateOf(point.carrierFrequency.toString()) }
    var tempBeatFrequency by remember(point.beatFrequency) { mutableStateOf(point.beatFrequency.toString()) }
    
    val carrierValue = tempCarrierFrequency.toDoubleOrNull()
    val beatValue = tempBeatFrequency.toDoubleOrNull()
    
    val isCarrierValid = carrierValue != null && carrierValue >= MIN_AUDIBLE_FREQUENCY && carrierValue <= 2000.0
    val isBeatValid = beatValue != null && beatValue >= 0.125 && beatValue <= maxBeatForCurrentCarrier

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Заголовок с временем и кнопками
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Точка: %02d:%02d".format(point.time.hour, point.time.minute),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                
                Row {
                    IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Удалить",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onDeselect, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Закрыть",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Несущая частота - компактный ряд
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Несущая:",
                    style = MaterialTheme.typography.bodySmall,
                    color = CarrierFrequencyColor,
                    modifier = Modifier.width(70.dp)
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
                    modifier = Modifier.width(90.dp),
                    suffix = { Text("Гц", style = MaterialTheme.typography.bodySmall) },
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = MaterialTheme.typography.bodySmall.fontSize
                    )
                )
                
                if (isCarrierValid && carrierValue != null && carrierValue != point.carrierFrequency) {
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = { onCarrierFrequencyChange(carrierValue) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Применить",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            
            Slider(
                value = sliderCarrier,
                onValueChange = {
                    val rounded = kotlin.math.round(it)
                    sliderCarrier = rounded
                    tempCarrierFrequency = "%.0f".format(rounded)
                },
                onValueChangeFinished = { onCarrierFrequencyChange(kotlin.math.round(sliderCarrier).toDouble()) },
                valueRange = carrierRange.min.toFloat()..carrierRange.max.toFloat(),
                modifier = Modifier.padding(vertical = 2.dp),
                colors = SliderDefaults.colors(
                    thumbColor = CarrierFrequencyColor,
                    activeTrackColor = CarrierFrequencyColor
                )
            )
            
            // Частота биений - компактный ряд
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Биения:",
                    style = MaterialTheme.typography.bodySmall,
                    color = BeatFrequencyColor,
                    modifier = Modifier.width(70.dp)
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
                    modifier = Modifier.width(90.dp),
                    suffix = { Text("Гц", style = MaterialTheme.typography.bodySmall) },
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = MaterialTheme.typography.bodySmall.fontSize
                    )
                )
                
                if (isBeatValid && beatValue != null && beatValue != point.beatFrequency) {
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = { onBeatFrequencyChange(beatValue) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Применить",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.weight(1f))
                
                Text(
                    "макс %.1f".format(maxBeatForCurrentCarrier),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            val minBeatForSlider = 1f
            val maxBeatForSlider = maxBeatForCurrentCarrier.toFloat().coerceAtLeast(minBeatForSlider)
            Slider(
                value = sliderBeat.coerceIn(minBeatForSlider, maxBeatForSlider),
                onValueChange = {
                    val rounded = kotlin.math.round(it).coerceIn(minBeatForSlider, maxBeatForSlider)
                    sliderBeat = rounded
                    tempBeatFrequency = "%.0f".format(rounded)
                },
                onValueChangeFinished = { onBeatFrequencyChange(kotlin.math.round(sliderBeat).toDouble()) },
                valueRange = minBeatForSlider..maxBeatForSlider,
                modifier = Modifier.padding(vertical = 2.dp),
                colors = SliderDefaults.colors(
                    thumbColor = BeatFrequencyColor,
                    activeTrackColor = BeatFrequencyColor
                )
            )
        }
    }
}