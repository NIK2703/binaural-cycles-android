package com.binauralcycles.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.binauralcycles.R
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

private const val MIN_AUDIBLE_FREQUENCY = 20.0f

/**
 * Компактная нижняя панель с информацией о текущих частотах и управлении воспроизведением.
 * Отображается поверх всех экранов приложения.
 */
@Composable
fun BottomPlaybackPanel(
    presetName: String?,
    beatFrequency: Float,
    carrierFrequency: Float,
    isPlaying: Boolean,
    volume: Float,
    onPlayClick: () -> Unit,
    onVolumeChange: (Float) -> Unit,  // Вызывается при движении для мгновенного применения
    onVolumeSave: () -> Unit,  // Вызывается при отпускании для сохранения
    modifier: Modifier = Modifier
) {
    val leftChannelFreq = carrierFrequency - beatFrequency / 2.0f
    val isLeftChannelTooLow = leftChannelFreq < MIN_AUDIBLE_FREQUENCY

    // Локальное состояние для мгновенного отклика слайдера
    var localVolume by remember(volume) { mutableFloatStateOf(volume) }

    // Локализованные строки
    val hzDecimalFormat = stringResource(R.string.hz_value_format_decimal)
    val hzFormat = stringResource(R.string.hz_value_format)
    val playLabel = stringResource(R.string.play)
    val stopLabel = stringResource(R.string.stop)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(color = colorScheme.surfaceContainerHigh)
    ) {
        // Column чтобы navigationBarsPadding применялся только к контенту,
        // а фон панели заходил под navigation bar
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .navigationBarsPadding(),
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
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = colorScheme.onSurface
                        )
                    }

                    // Частоты в одну строку
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Частота биений с фоном для выделения
                        Box(
                            modifier = Modifier
                                .background(
                                    color = colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = hzDecimalFormat.format(beatFrequency),
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = colorScheme.onSecondaryContainer
                            )
                        }

                        Text(
                            text = "•",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceSecondary
                        )

                        // Несущая частота
                        Text(
                            text = hzFormat.format(carrierFrequency),
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.onSurface
                        )

                        // Предупреждение о низкой частоте
                        if (isLeftChannelTooLow) {
                            Text(
                                text = "⚠",
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                color = colorScheme.error
                            )
                        }
                    }
                }

                // Слайдер громкости
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.widthIn(max = 160.dp)
                ) {
                    Icon(
                        Icons.Default.VolumeDown,
                        contentDescription = null,
                        tint = colorScheme.onSurfaceSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                    Slider(
                        value = localVolume,
                        onValueChange = {
                            localVolume = it
                            onVolumeChange(it) // Мгновенное применение к аудио-движку
                        },
                        onValueChangeFinished = {
                            onVolumeSave() // Сохранение в preferences при отпускании
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Кнопка воспроизведения
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            color = if (isPlaying) colorScheme.error else colorScheme.primary,
                            shape = CircleShape
                        )
                        .clickable(onClick = onPlayClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) stopLabel else playLabel,
                        tint = if (isPlaying) colorScheme.onError else colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
