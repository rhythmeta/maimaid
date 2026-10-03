package org.rhythmeta.maimaid.ui.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

internal object BadgeContrast {
    fun foreground(backgrounds: List<Color>): Color {
        val first = backgrounds.firstOrNull() ?: return Color.Black
        val darkTint = Color(first.red * 0.2f, first.green * 0.2f, first.blue * 0.2f)
        val luminances = backgrounds.map(Color::luminance)
        fun minimumContrast(color: Color): Float {
            val foreground = color.luminance()
            return luminances.minOf { (maxOf(it, foreground) + 0.05f) / (minOf(it, foreground) + 0.05f) }
        }

        // Both halves of a split badge must remain readable.
        if (minimumContrast(darkTint) >= 4.5f) return darkTint
        return if (minimumContrast(Color.White) >= minimumContrast(Color.Black)) Color.White else Color.Black
    }
}
