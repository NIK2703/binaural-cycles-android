package com.binauralcycles.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.binauralcycles.R
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

@Composable
fun VolumeSlider(
    volume: Float,
    onVolumeChange: (Float) -> Unit,  // Вызывается при движении для мгновенного применения
    onVolumeSave: () -> Unit,  // Вызывается при отпускании для сохранения
    modifier: Modifier = Modifier
) {
    val volumeLabel = stringResource(R.string.volume)
    // Локальное состояние для мгновенного отклика UI
    var localVolume by remember(volume) { mutableFloatStateOf(volume) }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.VolumeDown,
            contentDescription = volumeLabel,
            tint = colorScheme.onSurfaceSecondary,
            modifier = Modifier.size(20.dp)
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
                .padding(horizontal = 8.dp)
        )
        Icon(
            Icons.Default.VolumeUp,
            contentDescription = volumeLabel,
            tint = colorScheme.onSurfaceSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun PlayButton(
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    val playLabel = stringResource(R.string.play)
    val stopLabel = stringResource(R.string.stop)

    Box(
        modifier = Modifier
            .size(56.dp)
            .background(color = colorScheme.primary, shape = CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
            contentDescription = if (isPlaying) stopLabel else playLabel,
            tint = colorScheme.onPrimary,
            modifier = Modifier.size(28.dp)
        )
    }
}
