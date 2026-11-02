package org.rhythmeta.maimaid.ui.theme

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

val LocalEnableBlur = staticCompositionLocalOf { true }
val LocalEnableFloatingBottomBar = staticCompositionLocalOf { true }
val LocalEnableFloatingBottomBarBlur = staticCompositionLocalOf { true }
val LocalEnablePredictiveBack = staticCompositionLocalOf { true }
val LocalPageScale = staticCompositionLocalOf { 1f }

/** Light and dark MIUIX color roles generated for one seed color. */
private class MiuixColorPair(val light: Colors, val dark: Colors)

@SuppressLint("NewApi")
@Composable
fun MaimaidTheme(
    settings: AppThemeSettings = DefaultAppThemeSettings,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemDarkTheme = isSystemInDarkTheme()
    val darkTheme = settings.colorMode.isDark || (settings.colorMode.isSystem && systemDarkTheme)
    val effectiveColorSpec = settings.colorSpec.effectiveFor(settings.paletteStyle)
    // KernelSU leaves the seed unspecified for the built-in Miuix palette. A
    // system dynamic seed is only resolved for Monet modes.
    val dynamicallyGenerated = settings.colorMode.isMonet
    val monetSeedColor: Color? = when {
        !dynamicallyGenerated -> null
        settings.keyColor != 0 -> Color(settings.keyColor)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context).primary else dynamicLightColorScheme(context).primary
        }
        else -> Color(0xFF3F8C8E)
    }
    val schemeMode = when (settings.colorMode) {
        ColorMode.SYSTEM, ColorMode.MONET_SYSTEM -> ColorSchemeMode.System
        ColorMode.LIGHT, ColorMode.MONET_LIGHT -> ColorSchemeMode.Light
        ColorMode.DARK, ColorMode.MONET_DARK, ColorMode.DARK_AMOLED -> ColorSchemeMode.Dark
    }
    // MIUIX 0.9.4 generates its Monet palettes against the 5.x material-color-utilities class
    // layout, which no longer matches the 6.x palette engine that provides the CMF style and the
    // 2026 spec. The app therefore generates every dynamic palette itself and hands the resulting
    // roles to the controller as explicit colors; only the seed-less Miuix palette still comes
    // from MIUIX untouched.
    val dynamicColors = if (monetSeedColor != null) {
        remember(monetSeedColor, settings.paletteStyle, effectiveColorSpec) {
            MiuixColorPair(
                light = paletteColorScheme(
                    seedColor = monetSeedColor,
                    isDark = false,
                    style = settings.paletteStyle,
                    spec = effectiveColorSpec,
                ).toMiuixColors(isDark = false),
                dark = paletteColorScheme(
                    seedColor = monetSeedColor,
                    isDark = true,
                    style = settings.paletteStyle,
                    spec = effectiveColorSpec,
                ).toMiuixColors(isDark = true),
            )
        }
    } else {
        null
    }
    val controller = remember(settings.colorMode, darkTheme, schemeMode, dynamicColors) {
        if (dynamicColors != null) {
            ThemeController(
                colorSchemeMode = schemeMode,
                lightColors = dynamicColors.light,
                darkColors = dynamicColors.dark,
                isDark = darkTheme,
            )
        } else {
            ThemeController(
                colorSchemeMode = schemeMode,
                isDark = darkTheme,
            )
        }
    }
    val generatedColors = controller.currentColors()
    val resolvedController = if (settings.colorMode.isAmoled) {
        val amoledColors = remember(generatedColors) {
            generatedColors.copy(
                background = Color.Black,
                surface = Color.Black,
                surfaceVariant = Color(0xFF101010),
            )
        }
        remember(amoledColors) {
            ThemeController(
                colorSchemeMode = ColorSchemeMode.Dark,
                lightColors = amoledColors,
                darkColors = amoledColors,
                isDark = true,
            )
        }
    } else {
        controller
    }

    LaunchedEffect(darkTheme) {
        val window = (context as? Activity)?.window ?: return@LaunchedEffect
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MiuixTheme(controller = resolvedController) {
        CompositionLocalProvider(
            LocalEnableBlur provides (settings.enableBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU),
            LocalEnableFloatingBottomBar provides settings.enableFloatingBottomBar,
            LocalEnableFloatingBottomBarBlur provides (
                settings.enableFloatingBottomBarBlur &&
                    settings.enableFloatingBottomBar &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ),
            LocalEnablePredictiveBack provides settings.enablePredictiveBack,
            LocalPageScale provides settings.pageScale,
        ) {
            content()
        }
    }
}
