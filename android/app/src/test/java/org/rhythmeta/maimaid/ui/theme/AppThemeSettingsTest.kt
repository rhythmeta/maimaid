package org.rhythmeta.maimaid.ui.theme

import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeSettingsTest {
    @Test
    fun monetModesConvertToTheirNonMonetCounterparts() {
        assertEquals(ColorMode.SYSTEM, ColorMode.MONET_SYSTEM.toNonMonetMode())
        assertEquals(ColorMode.LIGHT, ColorMode.MONET_LIGHT.toNonMonetMode())
        assertEquals(ColorMode.DARK, ColorMode.MONET_DARK.toNonMonetMode())
        assertEquals(ColorMode.MONET_DARK, ColorMode.DARK.toMonetMode())
    }

    @Test
    fun modeFlagsMatchKernelSUContract() {
        assertTrue(ColorMode.MONET_SYSTEM.isSystem)
        assertTrue(ColorMode.MONET_SYSTEM.isMonet)
        assertTrue(ColorMode.DARK_AMOLED.isDark)
        assertTrue(ColorMode.DARK_AMOLED.isAmoled)
        assertFalse(ColorMode.LIGHT.isDark)
    }

    @Test
    fun invalidModeFallsBackToSystem() {
        assertEquals(ColorMode.SYSTEM, ColorMode.fromValue(99))
    }

    @Test
    fun unsupportedSpecFallsBackToTheNewestSpecOfTheStyle() {
        assertEquals(
            ColorSpec.SpecVersion.SPEC_2021,
            ColorSpec.SpecVersion.SPEC_2025.effectiveFor(PaletteStyle.Rainbow),
        )
        assertEquals(
            ColorSpec.SpecVersion.SPEC_2025,
            ColorSpec.SpecVersion.SPEC_2025.effectiveFor(PaletteStyle.TonalSpot),
        )
        // The 2026 spec belongs to the CMF palette, so the classic styles never adopt it.
        assertEquals(
            ColorSpec.SpecVersion.SPEC_2025,
            ColorSpec.SpecVersion.SPEC_2026.effectiveFor(PaletteStyle.TonalSpot),
        )
        assertEquals(
            ColorSpec.SpecVersion.SPEC_2021,
            ColorSpec.SpecVersion.SPEC_2026.effectiveFor(PaletteStyle.Content),
        )
    }

    @Test
    fun cmfPaletteIsFixedToSpec2026() {
        val cmf = PaletteStyle.Cmf()
        assertEquals(listOf(ColorSpec.SpecVersion.SPEC_2026), cmf.supportedSpecs)
        // Whatever spec was requested, CMF keeps rendering with the 2026 spec.
        assertEquals(ColorSpec.SpecVersion.SPEC_2026, ColorSpec.SpecVersion.SPEC_2021.effectiveFor(cmf))
        assertEquals(ColorSpec.SpecVersion.SPEC_2026, ColorSpec.SpecVersion.SPEC_2025.effectiveFor(cmf))
        assertEquals(ColorSpec.SpecVersion.SPEC_2026, ColorSpec.SpecVersion.SPEC_2026.effectiveFor(cmf))
        assertTrue(cmf.hasFixedColorSpec)
        assertFalse(PaletteStyle.TonalSpot.hasFixedColorSpec)
    }

    @Test
    fun cmfIsOfferedLastInThePaletteStyleList() {
        assertEquals(10, ThemePaletteStyles.size)
        assertTrue(ThemePaletteStyles.last() is PaletteStyle.Cmf)
        assertEquals("CMF", PaletteStyle.Cmf().displayLabel)
        assertEquals("TonalSpot", PaletteStyle.TonalSpot.displayLabel)
    }

    @Test
    fun cmfPaletteStyleRoundTripsThroughStoredText() {
        // The preference is stored with PaletteStyle.toString() and read back with parseOrNull().
        assertEquals("Cmf", PaletteStyle.Cmf().toString())
        assertEquals("Cmf", PaletteStyle.parseOrNull("Cmf")?.name)
        assertEquals("TonalSpot", PaletteStyle.parseOrNull("TonalSpot")?.name)
        assertNull(PaletteStyle.parseOrNull("Spec2026"))
    }

    @Test
    fun cmfSchemeMapsOntoOpaqueMiuixRoles() {
        val scheme = cmfColorScheme(Color(0xFF3F8C8E), isDark = false)
        val colors = scheme.toMiuixColors(isDark = false)
        assertEquals(scheme.primary, colors.primary)
        assertEquals(scheme.onSurface, colors.onSurface)
        assertEquals(scheme.background, colors.background)
        assertEquals(scheme.surfaceContainerHigh, colors.surfaceContainerHigh)
        // MIUIX expects opaque roles; translucent M3 roles are composited over their surface.
        assertEquals(1f, colors.disabledPrimary.alpha)
        assertEquals(1f, colors.onSurfaceSecondary.alpha)
        assertEquals(0.3f, colors.windowDimming.alpha, 0.01f)
        assertEquals(
            0.6f,
            cmfColorScheme(Color(0xFF3F8C8E), isDark = true).toMiuixColors(isDark = true).windowDimming.alpha,
            0.01f,
        )
    }

    @Test
    fun defaultsMatchProductSettings() {
        assertEquals(ColorMode.SYSTEM, DefaultAppThemeSettings.colorMode)
        assertEquals(0, DefaultAppThemeSettings.keyColor)
        assertEquals(PaletteStyle.TonalSpot, DefaultAppThemeSettings.paletteStyle)
        assertEquals(ColorSpec.SpecVersion.SPEC_2025, DefaultAppThemeSettings.colorSpec)
        assertEquals(1f, DefaultAppThemeSettings.pageScale)
        assertTrue(DefaultAppThemeSettings.enableBlur)
        assertTrue(DefaultAppThemeSettings.enableFloatingBottomBar)
        assertTrue(DefaultAppThemeSettings.enableFloatingBottomBarBlur)
        assertTrue(DefaultAppThemeSettings.enablePredictiveBack)
    }
}
