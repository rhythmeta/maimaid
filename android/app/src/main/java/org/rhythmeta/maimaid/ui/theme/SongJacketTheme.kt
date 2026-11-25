package org.rhythmeta.maimaid.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** A song's chrome, controls and popups use its jacket, independently of the app's Monet seed. */
@Composable
internal fun SongJacketTheme(
    seedColor: Color?,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }
    val darkTheme = MiuixTheme.colorScheme.background.luminance() < 0.5f
    val materialColors = remember(seedColor, darkTheme) {
        paletteColorScheme(
            seedColor = seedColor ?: Color.Gray,
            isDark = darkTheme,
            style = if (seedColor == null) PaletteStyle.Monochrome else PaletteStyle.TonalSpot,
            spec = if (seedColor == null) ColorSpec.SpecVersion.SPEC_2021 else ColorSpec.SpecVersion.SPEC_2025,
        )
    }
    val controller = remember(materialColors, darkTheme) {
        val colors = materialColors.toMiuixColors(isDark = darkTheme)
        ThemeController(
            colorSchemeMode = if (darkTheme) ColorSchemeMode.Dark else ColorSchemeMode.Light,
            lightColors = colors,
            darkColors = colors,
            isDark = darkTheme,
        )
    }
    MaterialTheme(colorScheme = materialColors) {
        MiuixTheme(controller = controller, content = content)
    }
}
