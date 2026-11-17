package org.rhythmeta.maimaid.core.chart

import kotlin.math.PI

/** Visual constants copied 1:1 from the reference engine's `utils/constants.ts`. */
object ChartConstants {
    const val BASE_APPROACH_TIME_MS = 2250.0
    const val NOTE_VISIBILITY_AFTER_MS = 0.0
    const val NOTE_HIT_EFFECT_DURATION_MS = 450.0
    const val HOLD_ACTIVE_CYCLE_MS = 16_000.0 / 60.0

    const val BUTTON_ANGLE_STEP = PI / 4
    const val BUTTON_ANGLE_OFFSET = PI / 8
    const val BASE_ANGLE = -PI / 2

    const val PANEL_RADIUS_UNITS = 480.0
    const val NOTE_SIZE_RATIO = 1.0 / 12.5
    const val TAP_INNER_RING_RATIO = 0.65
    const val APPROACH_START_SCALE = 0.25
    const val BUTTON_MARKER_RATIO = 9.0 / 300.0
    const val NOTE_STROKE_WIDTH_RATIO = 2.0 / 300.0
    const val JUDGMENT_LINE_WIDTH_RATIO = 3.0 / 300.0
    const val SLIDE_ARROW_WIDTH_RATIO = 16.6 / 300.0
    const val SLIDE_ARROW_HEIGHT_RATIO = 44.6 / 300.0
    const val SLIDE_ARROW_SPAN_RATIO = 12.2 / 300.0
    const val SLIDE_ARROW_PADDING_RATIO = 9.5 / 300.0
    const val SLIDE_STAR_SIZE_RATIO = 1.6 / 10.42
    const val SLIDE_STAR_WAITING_MIN_SCALE = 0.5
    const val SLIDE_WIFI_LINE_WIDTH_RATIO = 19.2 / 300.0
    val SLIDE_WIFI_CORNER_FRACS = doubleArrayOf(
        0.0, 0.094, 0.178, 0.266, 0.355, 0.462, 0.57, 0.674, 0.779, 0.884, 1.0,
    )

    const val HOLD_WIDTH_RATIO = 1.5
    const val HOLD_INNER_RATIO = 0.62
    const val HOLD_ACTIVE_BRIGHTNESS_MIN = 0.82
    const val HOLD_ACTIVE_BRIGHTNESS_MAX = 1.0
    const val HOLD_ACTIVE_GLOW_RATIO = 0.06
    val HOLD_ACTIVE_BRIGHTNESS_CURVE = doubleArrayOf(
        0.714, 0.51, 0.286, 0.095, 0.0, 0.01, 0.095, 0.293, 0.552, 0.762, 0.917, 0.976, 1.0,
        0.995, 0.969, 0.917,
    )
    val HOLD_ACTIVE_GLOW_RAMP = arrayOf(
        doubleArrayOf(0.0, 0.99, 1.11, 1.29),
        doubleArrayOf(0.1, 0.99, 0.98, 1.04),
        doubleArrayOf(0.2, 0.99, 0.79, 0.72),
        doubleArrayOf(0.3, 0.98, 0.63, 0.62),
        doubleArrayOf(0.4, 0.93, 0.49, 0.62),
        doubleArrayOf(0.5, 0.76, 0.28, 0.43),
        doubleArrayOf(0.6, 0.54, 0.08, 0.13),
        doubleArrayOf(0.7, 0.41, 0.02, 0.05),
        doubleArrayOf(0.8, 0.3, 0.01, 0.04),
        doubleArrayOf(0.9, 0.24, 0.02, 0.05),
    )

    const val TOUCH_APPROACH_MULTIPLIER = 0.9
    const val TOUCH_CENTER_DOT_RATIO = 1.0 / 37.5
    const val TOUCH_PETAL_OPEN_RATIO = 1.0 / 6.25
    const val TOUCH_PETAL_CLOSED_RATIO = 1.0 / 12.5
    const val TOUCH_HOLD_CENTRE_BURST_ANGLE = -PI / 6

    const val HI_SPEED_DEFAULT = 6.0
    const val HI_SPEED_CONVERSION_FACTOR = (2.0 / 3.0) * 0.9
    const val NOTE_LIGHTEN_RATIO = 0.18
    const val DDR_DARKEN_RATIO = 0.25
    const val RAINBOW_SPEED_DEG_PER_SEC = 60.0

    /** ARGB colors lifted from the reference palette. */
    object Colors {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val TAP_PINK = 0xFFFF69B4.toInt()
        const val TAP_GRADIENT_START = 0xFFFD197D.toInt()
        const val TAP_GRADIENT_END = 0xFFFF7DD5.toInt()
        const val SIMULTANEOUS_GOLD = 0xFFFFD700.toInt()
        const val SIMULTANEOUS_GRADIENT_START = 0xFFFCB600.toInt()
        const val SIMULTANEOUS_GRADIENT_END = 0xFFFFFE00.toInt()
        const val BREAK_ORANGE = 0xFFFF8C00.toInt()
        const val BREAK_GRADIENT_START = 0xFFFFB347.toInt()
        const val BREAK_GRADIENT_END = 0xFFFF6600.toInt()

        const val SLIDE_CYAN = 0xFF01FBFD.toInt()
        const val SLIDE_ARROW_RIGHT = 0xFFFF9001.toInt()
        const val SLIDE_PINK = 0xFFFF69B4.toInt()
        const val SLIDE_SIMULTANEOUS = 0xFFFFED24.toInt()

        const val TOUCH_CYAN = 0xFF00FFFF.toInt()
        const val TOUCH_BLUE = 0xFF0080FF.toInt()
        const val TOUCH_SIMULTANEOUS_YELLOW = 0xFFFFFF00.toInt()
        const val TOUCH_HOLD_RED = 0xFFF74601.toInt()
        const val TOUCH_HOLD_YELLOW = 0xFFF6ED00.toInt()
        const val TOUCH_HOLD_GREEN = 0xFF12A86A.toInt()
        const val TOUCH_HOLD_BLUE = 0xFF0097F6.toInt()

        const val EX_OVERLAY_BREAK = 0xCCFFC878.toInt()
        const val EX_OVERLAY_SIMULTANEOUS = 0xCCFFF596.toInt()
        const val EX_OVERLAY_NORMAL = 0xCCFFB4D2.toInt()

        const val HIT_EFFECT_GOLD = 0xFFF5EA72.toInt()

        const val DDR_RED = 0xFFFF0000.toInt()
        const val DDR_BLUE = 0xFF0066FF.toInt()
        const val DDR_YELLOW = 0xFFFFFF00.toInt()
        const val DDR_GREEN = 0xFF00FF00.toInt()
        const val DDR_ORANGE = 0xFFFF8800.toInt()
        const val DDR_CYAN = 0xFF00DDFF.toInt()

        const val HUD_MUTED = 0xFF94A3B8.toInt()
        const val FPS_GOOD = 0xFF22C55E.toInt()
        const val FPS_OK = 0xFFEAB308.toInt()
        const val FPS_BAD = 0xFFEF4444.toInt()
    }
}
