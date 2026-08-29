package com.binauralcycles.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.binaural.core.audio.model.FrequencyMath
import com.binauralcycles.R

// Движок клампит частоту канала к >= 0 Гц; ниже 0 Гц канал замолкает,
// и отображаемое значение перестаёт соответствовать звуку
private const val MIN_CHANNEL_FREQUENCY = 0.0f

@Composable
fun CurrentFrequenciesCard(
    beatFrequency: Float,
    carrierFrequency: Float,
    isPlaying: Boolean
) {
    // Каналы по общей формуле (как в движке): left = carrier − beat/2,
    // right = carrier + beat/2. При ОТРИЦАТЕЛЬНОЙ частоте биений ниже нуля
    // может уйти ПРАВЫЙ канал — поэтому проверяем оба, а не только левый.
    val leftChannelFreq = FrequencyMath.leftChannelFrequency(carrierFrequency, beatFrequency)
    val rightChannelFreq = FrequencyMath.rightChannelFrequency(carrierFrequency, beatFrequency)
    val isChannelTooLow = minOf(leftChannelFreq, rightChannelFreq) < MIN_CHANNEL_FREQUENCY
    
    // Локализованные строки
    val beatLabel = stringResource(R.string.beat_frequency)
    val carrierLabel = stringResource(R.string.carrier_frequency)
    val hzDecimalFormat = stringResource(R.string.hz_value_format_decimal)
    val hzFormat = stringResource(R.string.hz_value_format)
    
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isPlaying) MaterialTheme.colorScheme.primaryContainer 
                else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Частота биений
            FrequencyColumn(
                label = beatLabel,
                value = hzDecimalFormat.format(beatFrequency),
                color = MaterialTheme.colorScheme.primary
            )
            
            VerticalDivider(
                modifier = Modifier.height(32.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            )
            
            // Несущая частота
            FrequencyColumn(
                label = carrierLabel,
                value = hzFormat.format(carrierFrequency),
                color = MaterialTheme.colorScheme.onSurface
            )
            
            if (isChannelTooLow) {
                VerticalDivider(
                    modifier = Modifier.height(32.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                )
                
                Text(
                    text = "⚠",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun FrequencyColumn(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}