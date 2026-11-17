package org.rhythmeta.maimaid.core.chart

/** Renderer configuration mirroring the reference `RendererConfig` with the web app defaults. */
data class ChartRenderConfig(
    /** Internal approach-time divisor: UI speed 6 maps to 3.6. */
    val hiSpeed: Double = ChartConstants.HI_SPEED_DEFAULT * ChartConstants.HI_SPEED_CONVERSION_FACTOR,
    val alwaysKeepHiSpeed: Boolean = false,
    val slideDelay: Double = 0.0,
    val playbackSpeed: Double = 1.0,
    val mirrorMode: MirrorMode = MirrorMode.NONE,
    val highlightExNotes: Boolean = false,
    val normalColorBreakSlide: Boolean = false,
    val pinkSlideStart: Boolean = false,
    val slideRotation: Boolean = true,
    val judgmentLineDesign: JudgmentLineDesign = JudgmentLineDesign.SIMPLE,
    val showBpm: Boolean = true,
    val showNoteTotal: Boolean = true,
    val showBreakCount: Boolean = true,
    val showBreakIndex: Boolean = false,
    val rainbowBpm: Boolean = false,
    val ddrColorMode: Boolean = false,
    val ddrColorExtended: Boolean = false,
    val showFireworks: Boolean = true,
    val showHitEffect: Boolean = true,
    /** Localized in-canvas HUD copy. */
    val hudComboLabel: String = "COMBO",
    val hudBreakNoExLabel: String = "NO EX",
) {
    companion object {
        /** `setHiSpeed(3..9)` from the reference app, converted to the internal scale. */
        fun fromUiHiSpeed(speed: Double): Double =
            speed.coerceIn(3.0, 9.0) * ChartConstants.HI_SPEED_CONVERSION_FACTOR
    }
}
