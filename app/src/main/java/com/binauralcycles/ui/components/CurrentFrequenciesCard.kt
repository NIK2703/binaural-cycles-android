package com.binauralcycles.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.binauralcycles.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

// Движок клампит частоту канала к >= 0 Гц; ниже 0 Гц канал замолкает,
// и отображаемое значение перестаёт соответствовать звуку
private const val MIN_CHANNEL_FREQUENCY = 0.0

@Composable
fun CurrentFrequenciesCard(
    beatFrequency: Float,
    carrierFrequency: Float,
    isPlaying: Boolean
) {
    val leftChannelFreq = carrierFrequency - beatFrequency / 2.0
    val isLeftChannelTooLow = leftChannelFreq < MIN_CHANNEL_FREQUENCY

    // Локализованные строки
    val beatLabel = stringResource(R.string.beat_frequency)
    val carrierLabel = stringResource(R.string.carrier_frequency)
    val hzDecimalFormat = stringResource(R.string.hz_value_format_decimal)
    val hzFormat = stringResource(R.string.hz_value_format)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = if (isPlaying) colorScheme.primaryContainer
            else colorScheme.surfaceVariant
        )
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
                valueColor = colorScheme.primary
            )

            VerticalDividerBox(
                modifier = Modifier.height(32.dp),
                dividerColor = colorScheme.onSurfaceSecondary.copy(alpha = 0.3f)
            )

            // Несущая частота
            FrequencyColumn(
                label = carrierLabel,
                value = hzFormat.format(carrierFrequency),
                valueColor = colorScheme.onSurface
            )

            if (isLeftChannelTooLow) {
                VerticalDividerBox(
                    modifier = Modifier.height(32.dp),
                    dividerColor = colorScheme.onSurfaceSecondary.copy(alpha = 0.3f)
                )

                Text(
                    text = "⚠",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    color = colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun FrequencyColumn(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = colorScheme.onSurfaceSecondary
        )
        Text(
            text = value,
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
    }
}

@Composable
private fun VerticalDividerBox(
    modifier: Modifier = Modifier,
    dividerColor: androidx.compose.ui.graphics.Color
) {
    Box(
        modifier = modifier
            .width(1.dp)
            .background(dividerColor)
    )
}
