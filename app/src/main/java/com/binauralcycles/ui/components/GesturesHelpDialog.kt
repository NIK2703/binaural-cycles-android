package com.binauralcycles.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.binaural.core.audio.model.FrequencyRange
import com.binauralcycles.R
import com.binauralcycles.ui.theme.Spacing

/**
 * Один пункт справки: иконка жеста + что он делает.
 *
 * Иконка изображает САМ ЖЕСТ (касание, перетаскивание, свайп), а не результат:
 * справка отвечает на вопрос «что сделать пальцем», и картинка жеста ищется
 * глазами быстрее, чем картинка результата. Поэтому шаг в 16 dp [Spacing.md]
 * между иконкой и текстом, а выравнивание по верху строки — длинный текст
 * уходит вниз, а иконка остаётся на уровне первой строки.
 */
private data class GestureHint(
    val icon: ImageVector,
    val text: String
)

/**
 * Справка по управлению в редакторе пресета.
 *
 * Открывается автоматически при первом входе в редактор (пока пользователь не
 * закроет её по «Понятно» — флаг в DataStore фиксирует, что показано, и
 * окно больше не появляется само), а также в любой момент — кнопкой справки
 * в верхней панели рядом с сохранением.
 *
 * Порядок пунктов — «от графика к окну точки»: сначала как получить точку
 * (двойное нажатие), потом как с ней работать (перетаскивание, затем
 * нажатие), затем как править её значения (свайп по полям) и в конце — как
 * подвинуть сам график (метки границ). Каждый следующий пункт относится к
 * объекту, полученному предыдущим.
 *
 * Диалог, а не экран и не подсказка-«пузырь»: список короткий, читается целиком
 * и не требует навигации. Закрывается и кнопкой, и системным «назад»,
 * и касанием мимо — всё это даёт [AlertDialog] через [onDismiss].
 *
 * @param carrierRange текущий диапазон несущей редактируемого пресета. Его
 *        границы подставляются в текст про метки: «нажмите на метки (600 Гц и
 *        100 Гц)» понятнее, чем абстрактные «граничные метки», — пользователь
 *        ищет глазами те же числа, что видит на графике. Формат берётся тот же,
 *        которым метки нарисованы ([R.string.hz_value_format]), чтобы справка
 *        и график не расходились в написании.
 */
@Composable
fun GesturesHelpDialog(
    carrierRange: FrequencyRange,
    onDismiss: () -> Unit
) {
    val hzFormat = stringResource(R.string.hz_value_format)
    // stringResource внутри списка: строки собраны один раз на композицию,
    // а не перечитываются при каждой перерисовке диалога.
    val hints = listOf(
        GestureHint(Icons.Filled.TouchApp, stringResource(R.string.gesture_add_point)),
        GestureHint(Icons.Filled.OpenWith, stringResource(R.string.gesture_drag_point)),
        GestureHint(Icons.Filled.RadioButtonChecked, stringResource(R.string.gesture_select_point)),
        GestureHint(Icons.Filled.SwapVert, stringResource(R.string.gesture_swipe_fields)),
        GestureHint(
            Icons.Filled.UnfoldMore,
            // MAX первой: метка верхней границы на графике выше, порядок в
            // тексте идёт сверху вниз, как сами метки.
            stringResource(
                R.string.gesture_range_labels,
                hzFormat.format(carrierRange.max),
                hzFormat.format(carrierRange.min)
            )
        ),
        // СКРАБ: как подвинуть само прослушивание. Иконка жеста «тяни
        // влево-вправо» повторяет форму ручки ◀|▶, а кнопка сброса — та же
        // иконка, что и на графике: справка и экран не должны расходиться.
        GestureHint(Icons.Filled.SwapHoriz, stringResource(R.string.gesture_scrub_handle)),
        GestureHint(Icons.Filled.Refresh, stringResource(R.string.gesture_scrub_reset))
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.gestures_help_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                hints.forEach { hint ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = hint.icon,
                            contentDescription = null,   // текст справа уже описывает жест
                            modifier = Modifier
                                .size(24.dp)
                                // Иконка оптически «провисает» относительно
                                // первой строки текста: приподнимаем её на 2 dp.
                                .padding(top = 2.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = hint.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.got_it))
            }
        }
    )
}
