package com.binauralcycles.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.binauralcycles.R

/**
 * Стартовое напоминание: добавить приложение в исключения фонового
 * энергосбережения, чтобы воспроизведение ритмов не прерывалось системой.
 *
 * Показывается один раз за всё время жизни установки. Обе кнопки закрывают
 * напоминание окончательно — разница только в том, открывается ли сразу
 * системный диалог; повторная точка входа — переключатель в настройках.
 *
 * @param onConfirm «Ок» — закрыть напоминание и открыть системный диалог
 * @param onCancel «Отмена» — закрыть напоминание, больше не показывать.
 *                 Сюда же попадает закрытие жестом/тапом вне диалога, иначе
 *                 флаг показа остался бы false и окно появлялось бы снова
 */
@Composable
fun BatteryOptimizationPromptDialog(
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = {
            Icon(
                imageVector = Icons.Default.BatterySaver,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(text = stringResource(R.string.battery_optimization_prompt_title))
        },
        text = {
            Text(text = stringResource(R.string.battery_optimization_prompt_message))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.battery_optimization_prompt_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(text = stringResource(R.string.cancel))
            }
        }
    )
}
