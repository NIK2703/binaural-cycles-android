package com.binaural.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Сила приглушения: 0f — цвет как есть, 1f — полностью серый той же светлоты.
 *
 * Значения подобраны так, чтобы Monet-палитра теряла «крикливость», но
 * иерархия поверхностей (фон < карточка < панель < контейнер) и
 * различимость активной карточки сохранялись.
 */
private object Muted {
    /** Фон приложения и базовые поверхности. */
    const val SURFACE = 0.65f

    /** Второстепенные поверхности: карточки, чипы, выпадающие меню. */
    const val SURFACE_VARIANT = 0.65f

    /** Верхний бар и залитые контейнеры — самое заметное цветное пятно. */
    const val CONTAINER = 0.75f

    /** Контейнеры вторичного/третичного цвета (активная карточка пресета). */
    const val CONTAINER_SECONDARY = 0.55f

    /**
     * Ошибки гасим слабо: красный в тёмной теме уходит в грязно-коричневый
     * уже на 0.35 (#8C1D18 -> #712825), а цвет ошибки обязан оставаться
     * узнаваемым без чтения текста.
     */
    const val ERROR = 0.10f
    const val ERROR_CONTAINER = 0.25f

    /** Акценты-не-фоны: линии графиков, слайдеры, курсоры. */
    const val ACCENT = 0.25f

    /** Границы и разделители. */
    const val OUTLINE = 0.40f
}

/**
 * Серый той же светлоты, что и исходный цвет.
 *
 * Яркость считаем по Rec.709 luma над гамма-компонентами, а не берём
 * HSV-value (максимальный канал). На value тёмный насыщенный цвет
 * (#4A3780) превращается в средне-серый (#808080), и фон тёмной темы
 * оказывается СВЕТЛЕЕ окружения — иерархия поверхностей ломается.
 */
internal fun Color.toNeutralGray(): Color {
    val y = 0.299f * red + 0.587f * green + 0.114f * blue
    return Color(red = y, green = y, blue = y, alpha = alpha)
}

/**
 * Приглушение цвета: сдвиг к серому той же светлоты.
 * Светлота не меняется, поэтому контраст с «on-» парами сохраняется.
 */
internal fun Color.muted(strength: Float): Color {
    val t = strength.coerceIn(0f, 1f)
    if (t == 0f) return this
    val gray = toNeutralGray()
    return Color(
        red = red + (gray.red - red) * t,
        green = green + (gray.green - green) * t,
        blue = blue + (gray.blue - blue) * t,
        alpha = alpha
    )
}

/**
 * Приглушённая копия схемы: гасятся фоны и контейнеры, «on-» пары
 * (текст/иконки) не трогаем — их насыщенность и так низкая, а трогать
 * значит рисковать контрастом.
 */
internal fun ColorScheme.muted(): ColorScheme = copy(
    background = background.muted(Muted.SURFACE),
    surface = surface.muted(Muted.SURFACE),
    surfaceVariant = surfaceVariant.muted(Muted.SURFACE_VARIANT),
    surfaceTint = surfaceTint.muted(Muted.SURFACE),

    surfaceBright = surfaceBright.muted(Muted.SURFACE),
    surfaceDim = surfaceDim.muted(Muted.SURFACE),
    surfaceContainerLowest = surfaceContainerLowest.muted(Muted.SURFACE),
    surfaceContainerLow = surfaceContainerLow.muted(Muted.SURFACE),
    surfaceContainer = surfaceContainer.muted(Muted.SURFACE),
    surfaceContainerHigh = surfaceContainerHigh.muted(Muted.SURFACE),
    surfaceContainerHighest = surfaceContainerHighest.muted(Muted.SURFACE),

    primary = primary.muted(Muted.ACCENT),
    primaryContainer = primaryContainer.muted(Muted.CONTAINER),

    secondary = secondary.muted(Muted.ACCENT),
    secondaryContainer = secondaryContainer.muted(Muted.CONTAINER_SECONDARY),

    tertiary = tertiary.muted(Muted.ACCENT),
    tertiaryContainer = tertiaryContainer.muted(Muted.CONTAINER_SECONDARY),

    error = error.muted(Muted.ERROR),
    errorContainer = errorContainer.muted(Muted.ERROR_CONTAINER),

    outline = outline.muted(Muted.OUTLINE),
    outlineVariant = outlineVariant.muted(Muted.OUTLINE),

    inverseSurface = inverseSurface.muted(Muted.SURFACE)
)

/**
 * Акцентная пара для элементов, которые обязаны выделяться на приглушённом фоне
 * (кнопка добавления пресета).
 *
 * Берётся из схемы ДО приглушения, поэтому остаётся единственным
 * по-настоящему насыщенным пятном на экране. Пара контейнер/контент
 * приходит из самой схемы, значит контраст текста и иконки гарантирован.
 */
@Immutable
data class AccentColors(
    val container: Color,
    val content: Color
)

val LocalAccentColors = compositionLocalOf {
    AccentColors(container = Purple40, content = Color.White)
}
