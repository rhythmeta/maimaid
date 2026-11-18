package org.rhythmeta.maimaid.core.chart.audio

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Feeds a bundled MP3 to [ChartStreamingTrack] in small chunks, the way the network would, and
 * checks that PCM becomes readable before the whole file has been delivered.
 */
@RunWith(AndroidJUnit4::class)
class ChartStreamingTrackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Test
    fun decodesWhileTheStreamIsStillArriving() {
        val bytes = instrumentation.context.assets.open("audio/probe.mp3").use { it.readBytes() }
        var delivered = 0
        val track = ChartStreamingTrack(totalBytes = bytes.size.toLong()) { sink ->
            val chunk = 8 * 1_024
            var offset = 0
            while (offset < bytes.size) {
                val count = minOf(chunk, bytes.size - offset)
                sink.onChunk(bytes, offset, count)
                offset += count
                delivered = offset
                // Simulate a slow-ish network for the first frames only, then finish quickly.
                if (offset < 64 * 1_024) SystemClock.sleep(100)
            }
        }
        try {
            track.start()
            val start = SystemClock.uptimeMillis()
            val ready = runBlocking { track.awaitReady() }
            val readyMs = SystemClock.uptimeMillis() - start
            val deliveredAtReady = delivered
            val progress = track.downloadProgress
            android.util.Log.i(
                "ChartStreamTest",
                "ready=$ready readyMs=$readyMs deliveredAtReady=$deliveredAtReady/${bytes.size} " +
                    "sr=${track.sampleRate} frames=${track.frameCount} " +
                    "progress=${progress?.percent}% speed=${progress?.bytesPerSecond}B/s",
            )
            assertTrue("no download progress reported", progress != null)
            assertTrue("progress exceeded 100%", (progress?.percent ?: 101) <= 100)
            assertTrue("track never became ready", ready)
            assertTrue("whole file was delivered before playback could start",
                deliveredAtReady < bytes.size)
            // Keep playing for a moment: the mixer must find data at the playhead.
            SystemClock.sleep(1_500)
            val out = ShortArray(512)
            val read = track.readFrames(0, out, 0, 256)
            assertEquals(256, read)
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (!track.isComplete && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(20)
            }
            android.util.Log.i(
                "ChartStreamTest",
                "complete=${track.isComplete} decoded=${track.decodedFrames} " +
                    "expectedFrames=${track.frameCount}",
            )
            assertTrue("stream never completed", track.isComplete)
            assertTrue("decoded ${track.decodedFrames} frames", track.decodedFrames > 1_000_000)
        } finally {
            track.close()
        }
    }

    @Test
    fun reportsFailureForNonAudioBytes() {
        val junk = ByteArray(64 * 1_024) { 0x00 }
        val track = ChartStreamingTrack(totalBytes = junk.size.toLong()) { sink ->
            sink.onChunk(junk, 0, junk.size)
        }
        try {
            track.start()
            val ready = runBlocking { track.awaitReady() }
            assertTrue(!ready)
        } finally {
            track.close()
        }
    }
}
