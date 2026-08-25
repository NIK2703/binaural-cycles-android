package com.binaural.core.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

// Фиксированный базовый цвет палитры (без Monet / динамических цветов).
private val SeedColor = Color(0xFF7B5CFF)

@Composable
fun BinauralTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val controller = ThemeController(
        if (darkTheme) ColorSchemeMode.Dark else ColorSchemeMode.Light,
        keyColor = SeedColor,
        isDark = darkTheme,
        paletteStyle = ThemePaletteStyle.TonalSpot,
    )

    MiuixTheme(controller = controller) {
        val context = LocalContext.current
        LaunchedEffect(darkTheme) {
            val window = (context as? Activity)?.window ?: return@LaunchedEffect
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
        CompositionLocalProvider(
            LocalContentColor provides MiuixTheme.colorScheme.onBackground
        ) {
            content()
        }
    }
}
