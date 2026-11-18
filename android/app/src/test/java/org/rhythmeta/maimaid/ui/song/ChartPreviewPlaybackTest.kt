package org.rhythmeta.maimaid.ui.song

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rhythmeta.maimaid.core.chart.ChartAudioState

class ChartPreviewPlaybackTest {
    @Test
    fun chartWaitsForTheTrackWhileItDownloadsOrPrepares() {
        assertTrue(previewAudioPending(hasAudio = true, ChartAudioState.Idle))
        assertTrue(previewAudioPending(hasAudio = true, ChartAudioState.Preparing))
    }

    @Test
    fun preparedTrackReleasesTheChart() {
        assertFalse(previewAudioPending(hasAudio = true, ChartAudioState.Ready(120_000)))
    }

    @Test
    fun failedTrackFallsBackToSilentPlaybackInsteadOfWaitingForever() {
        assertFalse(previewAudioPending(hasAudio = true, ChartAudioState.Failed("HTTP 404")))
    }

    @Test
    fun sheetWithoutTrackStartsImmediately() {
        assertFalse(previewAudioPending(hasAudio = false, ChartAudioState.Idle))
        // A stale state from the previously selected sheet must not block a track-less chart.
        assertFalse(previewAudioPending(hasAudio = false, ChartAudioState.Preparing))
        assertFalse(previewAudioPending(hasAudio = false, ChartAudioState.Ready(1)))
    }
}
