package org.rhythmeta.maimaid.ui.theme

import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec

/** KernelSU-compatible theme mode values persisted by the Android app. */
enum class ColorMode(val value: Int) {
    SYSTEM(0),
    LIGHT(1),
    DARK(2),
    MONET_SYSTEM(3),
    MONET_LIGHT(4),
    MONET_DARK(5),
    DARK_AMOLED(6),
    ;

    val isSystem: Boolean get() = this == SYSTEM || this == MONET_SYSTEM
    val isDark: Boolean get() = this == DARK || this == MONET_DARK || this == DARK_AMOLED
	  val isAmoled: Boolean get() = this == DARK_AMOLED
    val isMonet: Boolean get() = value >= MONET_SYSTEM.value

    fun toNonMonetMode(): ColorMode = when (this) {
        MONET_SYSTEM -> SYSTEM
        MONET_LIGHT -> LIGHT
        MONET_DARK, DARK_AMOLED -> DARK
        else -> this
    }

    fun toMonetMode(): ColorMode = when (this) {
        SYSTEM -> MONET_SYSTEM
        LIGHT -> MONET_LIGHT
        DARK, DARK_AMOLED -> MONET_DARK
        else -> this
    }

    companion object {
        fun fromValue(value: Int): ColorMode = entries.firstOrNull { it.value == value } ?: SYSTEM
    }
}

/**
 * Palette styles the appearance settings can offer, in menu order.
 *
 * The CMF entry is a [PaletteStyle.Cmf] instance rather than an enum constant, so the list is the
 * only stable source of truth for the picker.
 */
val ThemePaletteStyles: List<PaletteStyle>
    get() = PaletteStyle.KnownStyles

/** Label shown in the palette style picker. Only the CMF style needs its brand casing. */
val PaletteStyle.displayLabel: String
    get() = if (this is PaletteStyle.Cmf) "CMF" else name

/**
 * Color specifications a palette style can render.
 *
 * Mirrors the InstallerX contract the CMF palette is ported from: the 2025 spec is limited to the
 * classic M3 styles, CMF is defined only by the 2026 spec, and everything else stays on the 2021
 * spec.
 */
val PaletteStyle.supportedSpecs: List<ColorSpec.SpecVersion>
    get() = when (this) {
        PaletteStyle.TonalSpot,
        PaletteStyle.Neutral,
        PaletteStyle.Vibrant,
        PaletteStyle.Expressive,
        -> listOf(ColorSpec.SpecVersion.SPEC_2021, ColorSpec.SpecVersion.SPEC_2025)
        is PaletteStyle.Cmf -> listOf(ColorSpec.SpecVersion.SPEC_2026)
        PaletteStyle.Rainbow,
        PaletteStyle.FruitSalad,
        PaletteStyle.Monochrome,
        PaletteStyle.Fidelity,
        PaletteStyle.Content,
        -> listOf(ColorSpec.SpecVersion.SPEC_2021)
    }

/**
 * Resolve the spec actually used for [style] without overwriting the user's preference.
 *
 * CMF is fixed to [ColorSpec.SpecVersion.SPEC_2026]; a 2026 request for any other style falls back
 * to the newest spec that style supports.
 */
fun ColorSpec.SpecVersion.effectiveFor(style: PaletteStyle): ColorSpec.SpecVersion =
    takeIf { it in style.supportedSpecs } ?: style.supportedSpecs.last()

/** True when the style's spec cannot be chosen by the user, e.g. CMF and its 2026 spec. */
val PaletteStyle.hasFixedColorSpec: Boolean
    get() = supportedSpecs.size == 1

data class AppThemeSettings(
    val colorMode: ColorMode = ColorMode.SYSTEM,
    val keyColor: Int = 0,
    val paletteStyle: PaletteStyle = PaletteStyle.TonalSpot,
    val colorSpec: ColorSpec.SpecVersion = ColorSpec.SpecVersion.SPEC_2025,
    val enableBlur: Boolean = true,
    val enableFloatingBottomBar: Boolean = true,
    val enableFloatingBottomBarBlur: Boolean = true,
    val enablePredictiveBack: Boolean = true,
    val pageScale: Float = 1f,
)

val DefaultAppThemeSettings = AppThemeSettings()
