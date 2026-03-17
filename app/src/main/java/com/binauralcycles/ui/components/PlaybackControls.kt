package com.binauralcycles.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.binauralcycles.R

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
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
    
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(56.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (isPlaying) MaterialTheme.colorScheme.primary 
                            else MaterialTheme.colorScheme.primary
        )
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
            contentDescription = if (isPlaying) stopLabel else playLabel,
            modifier = Modifier.size(28.dp)
        )
    }
}
