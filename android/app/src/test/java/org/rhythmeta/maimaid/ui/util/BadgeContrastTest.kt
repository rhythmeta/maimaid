package org.rhythmeta.maimaid.ui.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BadgeContrastTest {
    @Test
    fun darkBackgroundUsesWhiteText() {
        assertEquals(Color.White, BadgeContrast.foreground(listOf(Color(0xFF1976D2))))
    }

    @Test
    fun brightBackgroundUsesDarkTextWithTheSameHue() {
        val background = Color(0xFFFFB74D)
        val foreground = BadgeContrast.foreground(listOf(background))

        assertTrue(foreground.red > foreground.green && foreground.green > foreground.blue)
        assertTrue(contrast(foreground, background) >= 4.5f)
    }

    @Test
    fun chartTypeBadgesRemainReadableInBothThemesIncludingSplitBadges() {
        for (dark in listOf(false, true)) {
            val colors = listOf("std", "dx", "utage").map {
                SongVisualUtils.chartTypeColor(it, dark, Color.Blue)
            }
            val backgrounds = colors.map(::listOf) + listOf(colors.take(2), colors.take(2).reversed())
            for (badge in backgrounds) {
                val foreground = BadgeContrast.foreground(badge)
                assertTrue(badge.all { contrast(foreground, it) >= 4.5f })
            }
        }
    }

    private fun contrast(first: Color, second: Color): Float {
        val lighter = maxOf(first.luminance(), second.luminance())
        val darker = minOf(first.luminance(), second.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
