package org.rhythmeta.maimaid.core.chart

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Touch sensor layout shared by rendering and hit testing. */
object TouchGeometry {
    private const val BASE_ANGLE = -PI / 2
    private const val BUTTON_ANGLE_OFFSET = PI / 8
    private const val BUTTON_ANGLE_STEP = PI / 4

    private val sensorRadii = mapOf(
        "A" to 400.0 / 480.0,
        "B" to 220.0 / 480.0,
        "C" to 0.0,
        "D" to 410.0 / 480.0,
        "E" to 310.0 / 480.0,
    )

    private val horizontalAb = intArrayOf(0, 8, 7, 6, 5, 4, 3, 2, 1)
    private val horizontalDe = intArrayOf(0, 1, 8, 7, 6, 5, 4, 3, 2)
    private val verticalAb = intArrayOf(0, 4, 3, 2, 1, 8, 7, 6, 5)
    private val verticalDe = intArrayOf(0, 5, 4, 3, 2, 1, 8, 7, 6)
    private val rotate180 = intArrayOf(0, 5, 6, 7, 8, 1, 2, 3, 4)

    /** Applies the mirror transform to a sensor name; the C sensor never moves. */
    fun mirrorTouchSensor(position: String, mirrorMode: MirrorMode): String {
        if (mirrorMode == MirrorMode.NONE) return position
        val region = position[0]
        if (region == 'C') return position
        val sensor = position.getOrNull(1)?.digitToIntOrNull() ?: 0
        val mapping = when (mirrorMode) {
            MirrorMode.HORIZONTAL -> if (region == 'A' || region == 'B') horizontalAb else horizontalDe
            MirrorMode.VERTICAL -> if (region == 'A' || region == 'B') verticalAb else verticalDe
            MirrorMode.ROTATE180 -> rotate180
            MirrorMode.NONE -> return position
        }
        return "$region${mapping.getOrElse(sensor) { sensor }}"
    }

    /** Canvas-space position of a sensor point. */
    fun sensorPoint(
        position: String,
        centerX: Double,
        centerY: Double,
        radius: Double,
        mirrorMode: MirrorMode,
    ): ChartPoint {
        val mirrored = mirrorTouchSensor(position, mirrorMode)
        val region = mirrored[0]
        val sensor = mirrored.getOrNull(1)?.digitToIntOrNull() ?: 0
        if (region == 'C') return ChartPoint(centerX, centerY)

        val distance = radius * (sensorRadii["$region"] ?: 0.0)
        val angle = if (region == 'D' || region == 'E') {
            BASE_ANGLE + (sensor - 1) * BUTTON_ANGLE_STEP
        } else {
            BASE_ANGLE + BUTTON_ANGLE_OFFSET + (sensor - 1) * BUTTON_ANGLE_STEP
        }
        return ChartPoint(centerX + cos(angle) * distance, centerY + sin(angle) * distance)
    }
}
