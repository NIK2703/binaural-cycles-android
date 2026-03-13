package com.binauralcycles.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.binauralcycles.R

private const val MIN_AUDIBLE_FREQUENCY = 20.0

/**
 * Компактная нижняя панель с информацией о текущих частотах и управлением воспроизведением.
 * Отображается поверх всех экранов приложения.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomPlaybackPanel(
    presetName: String?,
    beatFrequency: Double,
    carrierFrequency: Double,
    isPlaying: Boolean,
    volume: Float,
    onPlayClick: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val leftChannelFreq = carrierFrequency - beatFrequency / 2.0
    val isLeftChannelTooLow = leftChannelFreq < MIN_AUDIBLE_FREQUENCY
    
    // Локальное состояние для мгновенного отклика слайдера
    var localVolume by remember(volume) { mutableFloatStateOf(volume) }
    
    // Локализованные строки
    val hzDecimalFormat = stringResource(R.string.hz_value_format_decimal)
    val hzFormat = stringResource(R.string.hz_value_format)
    val playLabel = stringResource(R.string.play)
    val stopLabel = stringResource(R.string.stop)

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Информация о текущем пресете и частотах
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                // Название пресета
                presetName?.let { name ->
                    Text(
                        text = name,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                // Частоты в одну строку
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Частота биений с фоном для выделения
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = hzDecimalFormat.format(beatFrequency),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    // Несущая частота
                    Text(
                        text = hzFormat.format(carrierFrequency),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    // Предупреждение о низкой частоте
                    if (isLeftChannelTooLow) {
                        Text(
                            text = "⚠",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            
            // Слайдер громкости (компактный)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.widthIn(max = 120.dp)
            ) {
                Icon(
                    Icons.Default.VolumeDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Slider(
                    value = localVolume,
                    onValueChange = { 
                        localVolume = it
                        onVolumeChange(it) // Мгновенное применение громкости
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp)
                        .height(16.dp),
                    thumb = {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .padding(1.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(2.dp)
                            )
                            SliderDefaults.Thumb(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    },
                    track = { sliderState ->
                        SliderDefaults.Track(
                            sliderState = sliderState,
                            modifier = Modifier.height(3.dp),
                            thumbTrackGapSize = 2.dp
                        )
                    }
                )
            }
            
            Spacer(modifier = Modifier.width(8.dp))
            
            // Кнопка воспроизведения
            FilledIconButton(
                onClick = onPlayClick,
                modifier = Modifier.size(44.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (isPlaying) 
                        MaterialTheme.colorScheme.error 
                    else 
                        MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) stopLabel else playLabel,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
