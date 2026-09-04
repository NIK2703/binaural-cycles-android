package com.binauralcycles.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.binauralcycles.R

/**
 * Диалог при попытке запуска воспроизведения без подключённых наушников.
 *
 * Если наушники подключаются во время показа диалога, он автоматически
 * скрывается и воспроизведение запускается (см. [BinauralViewModel]).
 *
 * @param onPlayAnyway «Запустить» — закрыть диалог и запустить воспроизведение
 * @param onDismiss «Понятно» — закрыть диалог без запуска
 */
@Composable
fun HeadphoneRequiredDialog(
    onPlayAnyway: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Headphones,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(text = stringResource(R.string.headphone_required_title))
        },
        text = {
            Text(text = stringResource(R.string.headphone_required_message))
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.headphone_required_dismiss))
            }
        },
        dismissButton = {
            TextButton(onClick = onPlayAnyway) {
                Text(text = stringResource(R.string.headphone_required_play_anyway))
            }
        }
    )
}
