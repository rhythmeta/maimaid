package org.rhythmeta.maimaid.core.chart

import kotlinx.serialization.Serializable

/**
 * Persisted chart-preview settings, mirroring the reference app's speed / display / audio panels.
 * Speeds are stored in UI units (3–9) and converted for the renderer.
 */
@Serializable
data class ChartPreviewSettings(
    val hiSpeed: Double = 6.0,
    val playbackSpeed: Double = 1.0,
    /** Vertical flow of the rhythm lane, as a multiplier of its base pixel-per-ms scale. */
    val rhythmSpeed: Double = 1.0,
    val slideDelay: Double = 0.0,
    val alwaysKeepHiSpeed: Boolean = false,
    val mirrorMode: MirrorMode = MirrorMode.NONE,
    val judgmentLineDesign: JudgmentLineDesign = JudgmentLineDesign.SIMPLE,
    val slideRotation: Boolean = true,
    val pinkSlideStart: Boolean = false,
    val highlightExNotes: Boolean = false,
    val normalColorBreakSlide: Boolean = false,
    val showFireworks: Boolean = true,
    val showHitEffect: Boolean = true,
    val showNoteTotal: Boolean = true,
    val showBreakCount: Boolean = true,
    val musicVolume: Float = 1f,
    val answerVolume: Float = 1f,
    val judgeVolume: Float = 1f,
    /** Positive values delay the music relative to the chart. */
    val musicOffsetMs: Int = 0,
    /** Positive values delay the judge/answer SFX relative to the chart. */
    val sfxOffsetMs: Int = 0,
) {
    fun toRenderConfig(comboLabel: String, breakNoExLabel: String): ChartRenderConfig =
        ChartRenderConfig(
            hiSpeed = ChartRenderConfig.fromUiHiSpeed(hiSpeed),
            alwaysKeepHiSpeed = alwaysKeepHiSpeed,
            slideDelay = slideDelay,
            playbackSpeed = playbackSpeed,
            mirrorMode = mirrorMode,
            highlightExNotes = highlightExNotes,
            normalColorBreakSlide = normalColorBreakSlide,
            pinkSlideStart = pinkSlideStart,
            slideRotation = slideRotation,
            judgmentLineDesign = judgmentLineDesign,
            showBpm = true,
            showNoteTotal = showNoteTotal,
            showBreakCount = showBreakCount,
            showBreakIndex = false,
            rainbowBpm = false,
            ddrColorMode = false,
            ddrColorExtended = false,
            showFireworks = showFireworks,
            showHitEffect = showHitEffect,
            hudComboLabel = comboLabel,
            hudBreakNoExLabel = breakNoExLabel,
        )
}
