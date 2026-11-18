package org.rhythmeta.maimaid.core.chart

/** State of the chart music track as seen by the preview UI. */
sealed interface ChartAudioState {
    data object Idle : ChartAudioState
    data object Preparing : ChartAudioState
    data class Ready(val durationMillis: Int) : ChartAudioState
    data class Failed(val message: String) : ChartAudioState
}
