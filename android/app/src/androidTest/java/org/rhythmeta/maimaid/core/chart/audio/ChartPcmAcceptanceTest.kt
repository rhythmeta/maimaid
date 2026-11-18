package org.rhythmeta.maimaid.core.chart.audio

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.rhythmeta.maimaid.core.chart.ChartAudioEvent

/**
 * Acceptance harness for the unified PCM engine. It plays a 24 events/s section, checks that every
 * due event was mixed (no drops), that the AudioTrack does not underrun during steady state, and
 * repeats after a seek. Run on an emulator/device with `am instrument`.
 */
@RunWith(AndroidJUnit4::class)
class ChartPcmAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Test
    fun highDensitySectionKeepsEveryEventAndDoesNotUnderrun() {
        val audioFile = copyProbeAudio()
        val engine = PcmChartAudioEngine(context)
        val prepared = runBlocking { engine.prepare(audioFile, sha256(audioFile)) }
        assertTrue("PCM prepare failed", prepared)
        try {
            val events = (0 until 480).map { index ->
                ChartAudioEvent(1_000.0 + index * 1_000.0 / 24.0).apply {
                    hasBaseSound = true
                    hasTapJudgeSound = true
                }
            }
            engine.configure(
                events = events,
                leadInMs = 1_000.0,
                durationMs = 22_000.0,
                musicOffsetMs = 0,
                sfxOffsetMs = 0,
            )
            engine.play()
            SystemClock.sleep(2_000)
            val baseline = engine.stats()
            SystemClock.sleep(10_000)
            val playing = engine.stats()
            assertEquals("dropped events", playing.eventsDue, playing.eventsMixed)
            assertEquals("missing samples", 0, playing.eventsWithoutSamples)
            assertEquals("underflow during steady state", baseline.underruns, playing.underruns)
            assertTrue("events did not run: $playing", playing.eventsMixed > 200)
            assertTrue("voice pool did not rotate: $playing", playing.maxVoices > 1)

            engine.seekTo(12_000.0, playAfterSeek = true)
            SystemClock.sleep(2_000)
            val afterSeek = engine.stats()
            SystemClock.sleep(8_000)
            val seeked = engine.stats()
            assertEquals("dropped events after seek", seeked.eventsDue, seeked.eventsMixed)
            assertEquals("underrun after seek", afterSeek.underruns, seeked.underruns)
            assertTrue("seek did not advance events: $seeked", seeked.eventsMixed > playing.eventsMixed)
            val position = engine.chartPositionMs()
            assertTrue("chart position after seek: $position", position in 12_000.0..22_000.0)
            android.util.Log.i("ChartPcmAcceptance", "playing=$playing")
            android.util.Log.i("ChartPcmAcceptance", "seeked=$seeked position=$position")
        } finally {
            engine.release()
        }
    }

    @Test
    fun speedSetBeforePlayIsAppliedWhenPlaybackStarts() {
        for (asset in listOf("probe.mp3", "probe48.mp3")) {
            runSpeedBeforePlay(asset)
        }
    }

    @Test
    fun speedChangeWhilePlayingAppliesToMusicAndClock() {
        for (asset in listOf("probe.mp3", "probe48.mp3")) {
            runSpeedWhilePlaying(asset)
        }
    }

    private fun runSpeedBeforePlay(asset: String) {
        val audioFile = copyProbeAudio(asset)
        val engine = PcmChartAudioEngine(context)
        assertTrue(runBlocking { engine.prepare(audioFile, sha256(audioFile)) })
        try {
            engine.configure(
                events = emptyList(),
                leadInMs = 1_000.0,
                durationMs = 22_000.0,
                musicOffsetMs = 0,
                sfxOffsetMs = 0,
            )
            engine.setPlaybackSpeed(1.5f)
            engine.play()
            SystemClock.sleep(1_500)
            val before = engine.chartPositionMs()
            SystemClock.sleep(1_000)
            val after = engine.chartPositionMs()
            val rate = (after - before) / 1_000.0
            android.util.Log.i(
                "ChartPcmAcceptance",
                "speed stats=${engine.stats()} rate=$rate before=$before after=$after",
            )
            if (engine.stats().speed == 1.5f) {
                assertTrue("clock rate=$rate", rate in 1.25..1.75)
            } else {
                // Some emulators/backends reject AudioTrack speeds; the engine must keep the chart
                // clock at the accepted rate then (no drift between music and chart).
                android.util.Log.i(
                    "ChartPcmAcceptance",
                    "playback speed unsupported on this output: ${engine.stats()}",
                )
                assertTrue("clock rate=$rate", rate in 0.9..1.1)
            }
        } finally {
            engine.release()
        }
    }

    private fun runSpeedWhilePlaying(asset: String) {
        val audioFile = copyProbeAudio(asset)
        val engine = PcmChartAudioEngine(context)
        assertTrue(runBlocking { engine.prepare(audioFile, sha256(audioFile)) })
        try {
            engine.configure(emptyList(), 1_000.0, 22_000.0, 0, 0)
            engine.play()
            SystemClock.sleep(2_000)
            engine.setPlaybackSpeed(1.5f)
            SystemClock.sleep(800)
            val before = engine.chartPositionMs()
            SystemClock.sleep(1_000)
            val after = engine.chartPositionMs()
            val rate = (after - before) / 1_000.0
            android.util.Log.i(
                "ChartPcmAcceptance",
                "speed while playing stats=${engine.stats()} rate=$rate",
            )
            if (engine.stats().speed == 1.5f) {
                assertTrue("$asset clock rate=$rate", rate in 1.25..1.75)
            } else {
                android.util.Log.i(
                    "ChartPcmAcceptance",
                    "$asset playback speed unsupported: ${engine.stats()}",
                )
                assertTrue("$asset clock rate=$rate", rate in 0.9..1.1)
            }
        } finally {
            engine.release()
        }
    }

    private fun copyProbeAudio(asset: String = "probe.mp3"): File {
        val name = if (asset == "probe.mp3") "pcm-acceptance.mp3" else "pcm-acceptance-48.mp3"
        val file = File(context.cacheDir, name)
        if (!file.isFile) {
            instrumentation.context.assets.open("audio/$asset").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return file
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1_024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
