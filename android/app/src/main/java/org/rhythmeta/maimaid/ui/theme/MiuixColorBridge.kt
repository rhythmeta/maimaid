package org.rhythmeta.maimaid.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.material3.dynamicColorScheme as materialDynamicColorScheme
import top.yukonga.miuix.kmp.theme.Colors

/**
 * Generate the Material 3 color scheme for a palette style and specification.
 *
 * materialkolor owns the palette engine, which is what makes the 2026 CMF palette available:
 * [PaletteStyle.Cmf] always renders with [ColorSpec.SpecVersion.SPEC_2026] no matter which spec is
 * requested. The result is turned into MIUIX color roles by [toMiuixColors].
 *
 * @param seedColor The primary seed color, normally the wallpaper or user picked key color.
 * @param isDark Whether the dark variant should be generated.
 * @param style The palette style, e.g. [PaletteStyle.TonalSpot] or [PaletteStyle.Cmf].
 * @param spec The color specification the style should be rendered with.
 */
fun paletteColorScheme(
    seedColor: Color,
    isDark: Boolean,
    style: PaletteStyle,
    spec: ColorSpec.SpecVersion,
): ColorScheme = materialDynamicColorScheme(
    seedColor = seedColor,
    isDark = isDark,
    style = style,
    specVersion = spec,
)

/**
 * Build the Material 3 color scheme for the 2026 CMF palette.
 *
 * CMF (Color, Material, Finish) accepts two seed colors and is defined by the 2026 specification
 * only, so the requested spec is always [ColorSpec.SpecVersion.SPEC_2026].
 */
fun cmfColorScheme(seedColor: Color, isDark: Boolean): ColorScheme =
    paletteColorScheme(
        seedColor = seedColor,
        isDark = isDark,
        style = PaletteStyle.Cmf(),
        spec = ColorSpec.SpecVersion.SPEC_2026,
    )

/**
 * Map a Material 3 [ColorScheme] onto the MIUIX component color roles.
 *
 * Adapted from MIUIX 0.9.4 `theme/MonetMapping.kt` (compose-miuix-ui, Apache-2.0). MIUIX keeps this
 * mapping internal and only feeds it with the palette styles it implements itself, so the app
 * replicates the exact role derivation for the palettes materialkolor generates, keeping CMF
 * visually consistent with the built-in styles.
 *
 * Transparent roles are composited over the surface they are drawn on, because MIUIX expects every
 * color to be opaque.
 *
 * @param isDark Whether the scheme belongs to the dark appearance, which only affects the window
 * dimming scope.
 */
fun ColorScheme.toMiuixColors(isDark: Boolean): Colors {
    val baseSurface = surface
    val baseSurfaceContainerHigh = surfaceContainerHigh
    val onSurfaceSecondaryOpaque = onSurface.copy(alpha = 0.8f).ensureOpaqueOver(baseSurface)
    val onSurfaceContainerHighOpaque =
        onSurface.copy(alpha = 0.8f).ensureOpaqueOver(baseSurfaceContainerHigh)
    val sliderBackground = primary.copy(alpha = 0.2f).ensureOpaqueOver(baseSurface)
    val disabledPrimaryOpaque = primary.copy(alpha = 0.38f).ensureOpaqueOver(baseSurface)
    val disabledOnPrimaryOpaque = onPrimary.copy(alpha = 0.38f).ensureOpaqueOver(disabledPrimaryOpaque)
    val disabledPrimaryButtonOpaque = primary.copy(alpha = 0.38f).ensureOpaqueOver(baseSurface)
    val disabledOnPrimaryButtonOpaque =
        onPrimary.copy(alpha = 0.6f).ensureOpaqueOver(disabledPrimaryButtonOpaque)
    val disabledPrimarySliderOpaque = primary.copy(alpha = 0.38f).ensureOpaqueOver(baseSurface)
    val disabledSecondaryOpaque = outlineVariant.copy(alpha = 0.5f).ensureOpaqueOver(baseSurface)
    val disabledOnSecondaryOpaque = onSurface.copy(alpha = 0.38f).ensureOpaqueOver(disabledSecondaryOpaque)
    val disabledSecondaryVariantOpaque =
        surfaceContainerHigh.copy(alpha = 0.6f).ensureOpaqueOver(baseSurface)
    val disabledOnSecondaryVariantOpaque =
        onSurface.copy(alpha = 0.38f).ensureOpaqueOver(disabledSecondaryVariantOpaque)

    return Colors(
        primary = primary,
        onPrimary = onPrimary,
        primaryVariant = primaryFixed,
        onPrimaryVariant = onPrimaryFixed,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        disabledPrimary = disabledPrimaryOpaque,
        disabledOnPrimary = disabledOnPrimaryOpaque,
        disabledPrimaryButton = disabledPrimaryButtonOpaque,
        disabledOnPrimaryButton = disabledOnPrimaryButtonOpaque,
        disabledPrimarySlider = disabledPrimarySliderOpaque,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = outlineVariant,
        onSecondary = outline,
        secondaryVariant = surfaceContainerHigh,
        onSecondaryVariant = onSurface,
        disabledSecondary = disabledSecondaryOpaque,
        disabledOnSecondary = disabledOnSecondaryOpaque,
        disabledSecondaryVariant = disabledSecondaryVariantOpaque,
        disabledOnSecondaryVariant = disabledOnSecondaryVariantOpaque,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        secondaryContainerVariant = surfaceContainerHighest,
        onSecondaryContainerVariant = onSurfaceVariant,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        tertiaryContainerVariant = onTertiaryContainer,
        background = background,
        onBackground = onBackground,
        onBackgroundVariant = primary,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceSecondary = onSurfaceSecondaryOpaque,
        onSurfaceVariantSummary = onSurfaceVariant,
        onSurfaceVariantActions = onSurfaceVariant,
        disabledOnSurface = onSurface,
        surfaceContainer = surfaceContainer,
        onSurfaceContainer = onSurface,
        onSurfaceContainerVariant = onSurfaceVariant,
        surfaceContainerHigh = surfaceContainerHigh,
        onSurfaceContainerHigh = onSurfaceContainerHighOpaque,
        surfaceContainerHighest = surfaceContainerHighest,
        onSurfaceContainerHighest = onSurface,
        outline = outline,
        dividerLine = outlineVariant,
        windowDimming = if (isDark) {
            Color.Black.copy(alpha = 0.6f)
        } else {
            Color.Black.copy(alpha = 0.3f)
        },
        sliderKeyPoint = primary,
        sliderKeyPointForeground = surfaceContainerHigh,
        sliderBackground = sliderBackground,
    )
}

/** Composite this (possibly translucent) color over an opaque [background]. */
private fun Color.compositeOver(background: Color): Color {
    val foregroundAlpha = alpha
    val backgroundAlpha = background.alpha
    val outAlpha = foregroundAlpha + backgroundAlpha * (1f - foregroundAlpha)
    if (outAlpha == 0f) return Color.Transparent
    val red = (this.red * foregroundAlpha + background.red * backgroundAlpha * (1f - foregroundAlpha)) / outAlpha
    val green = (this.green * foregroundAlpha + background.green * backgroundAlpha * (1f - foregroundAlpha)) / outAlpha
    val blue = (this.blue * foregroundAlpha + background.blue * backgroundAlpha * (1f - foregroundAlpha)) / outAlpha
    return Color(red, green, blue, outAlpha)
}

/** Composite this color over [background] and return the result as an opaque color. */
private fun Color.opaqueOver(background: Color): Color {
    val composited = compositeOver(background)
    return Color(composited.red, composited.green, composited.blue, 1f)
}

/** Composite this color over [background] only when it carries transparency. */
private fun Color.ensureOpaqueOver(background: Color): Color =
    if (alpha >= 1f) this else opaqueOver(background)
