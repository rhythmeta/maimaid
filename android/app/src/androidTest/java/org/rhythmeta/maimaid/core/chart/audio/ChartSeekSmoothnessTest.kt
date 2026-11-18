package org.rhythmeta.maimaid.core.chart.audio

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The chart clock must follow a scrub immediately and never report the pre-seek position again:
 * that snap-back was the visible "jerk" before playback resumed.
 */
@RunWith(AndroidJUnit4::class)
class ChartSeekSmoothnessTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Test
    fun playheadFollowsTheSeekWithoutSnappingBack() {
        val file = copyAsset()
        val engine = PcmChartAudioEngine(context)
        try {
            assertTrue(runBlocking { engine.prepare(file, "seek-smoothness") })
            engine.configure(emptyList(), 1_000.0, 22_000.0, 0, 0)
            engine.play()
            SystemClock.sleep(1_200)
            val before = engine.chartPositionMs()

            // Scrub to 20 s while paused, exactly like the timeline does.
            engine.pause()
            SystemClock.sleep(80)
            engine.seekTo(20_000.0, playAfterSeek = false)

            // Sampling right away must already show the target, not the old position.
            val immediate = engine.chartPositionMs()
            assertTrue("immediate position $immediate is far from the target", immediate > 19_500.0)
            assertTrue("immediate position $immediate snapped back", immediate >= before + 1_000.0)

            engine.play()
            var previous = immediate
            var minimum = immediate
            val deadline = SystemClock.uptimeMillis() + 800
            while (SystemClock.uptimeMillis() < deadline) {
                val position = engine.chartPositionMs()
                assertTrue(
                    "position went backwards: $previous -> $position",
                    position >= previous - 20.0,
                )
                if (position < minimum) minimum = position
                previous = position
                SystemClock.sleep(16)
            }
            assertTrue("playhead regressed to $minimum after seek", minimum > 19_500.0)
            assertTrue("playhead did not advance: $previous", previous > immediate + 300.0)
            android.util.Log.i(
                "ChartSeekSmoothness",
                "before=$before immediate=$immediate final=$previous minimum=$minimum",
            )
        } finally {
            engine.release()
        }
    }

    private fun copyAsset(): File {
        val file = File(context.cacheDir, "seek-smoothness.mp3")
        if (!file.isFile) {
            instrumentation.context.assets.open("audio/probe.mp3").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return file
    }
}
