package org.rhythmeta.maimaid.core.chart.audio

import android.content.Context
import android.os.SystemClock
import android.media.MediaExtractor
import java.nio.ByteBuffer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/** Logs the wall-clock cost of decoding the bundled probe tracks (no assertions). */
@RunWith(AndroidJUnit4::class)
class ChartPcmDecodePerfTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Test
    fun prerollTiming() {
        for (asset in listOf("probe.mp3", "probe48.mp3")) {
            val file = copyAsset(asset)
            repeat(3) { run ->
                val start = System.nanoTime()
                val stream = ChartPcmStream(file)
                stream.start()
                stream.awaitFormat(3_000)
                val prerollFrames = stream.sampleRate * ChartPcmStream.PREROLL_MS / 1_000
                val ready = stream.awaitPreroll(prerollFrames, 3_000)
                val prerollMs = (System.nanoTime() - start) / 1_000_000.0
                // Then let it finish to report the full decode cost for comparison.
                while (!stream.isComplete && System.currentTimeMillis() < System.currentTimeMillis() + 5_000) {
                    SystemClock.sleep(20)
                    if (stream.isComplete) break
                }
                val fullMs = (System.nanoTime() - start) / 1_000_000.0
                android.util.Log.i(
                    "ChartPcmPerf",
                    "$asset run=$run preroll=${"%.1f".format(prerollMs)}ms ready=$ready " +
                        "full=${"%.1f".format(fullMs)}ms sr=${stream.sampleRate} " +
                        "frames=${stream.frameCount}",
                )
                stream.close()
            }
        }
    }

    @Test
    fun enginePrepareLatency() {
        for (asset in listOf("probe.mp3", "probe48.mp3")) {
            val file = copyAsset(asset)
            repeat(3) { run ->
                val engine = PcmChartAudioEngine(context)
                val start = System.nanoTime()
                val prepared = kotlinx.coroutines.runBlocking { engine.prepare(file, asset) }
                val ms = (System.nanoTime() - start) / 1_000_000.0
                android.util.Log.i(
                    "ChartPcmPerf",
                    "$asset engine-prepare run=$run prepared=$prepared " +
                        "time=${"%.1f".format(ms)}ms",
                )
                engine.release()
            }
        }
    }

    @Test
    fun farSeekWhileDecodingDoesNotStarve() {
        val file = copyAsset("probe48.mp3")
        val engine = PcmChartAudioEngine(context)
        try {
            kotlinx.coroutines.runBlocking { engine.prepare(file, "far-seek") }
            engine.configure(emptyList(), 1_000.0, 22_000.0, 0, 0)
            engine.play()
            SystemClock.sleep(120) // decode is still running for most of the track
            engine.seekTo(20_000.0, playAfterSeek = true)
            SystemClock.sleep(1_500)
            val early = engine.stats()
            SystemClock.sleep(1_000)
            val later = engine.stats()
            android.util.Log.i(
                "ChartPcmPerf",
                "far-seek starved then=${early.starvedBuffers} now=${later.starvedBuffers} " +
                    "position=${engine.chartPositionMs()}",
            )
        } finally {
            engine.release()
        }
    }

    @Test
    fun extractorOnlyTiming() {
        val file = copyAsset("probe.mp3")
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        extractor.selectTrack(0)
        val buffer = ByteBuffer.allocateDirect(64 * 1024)
        repeat(2) { run ->
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            var packets = 0
            var bytes = 0L
            val start = System.nanoTime()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                packets++
                bytes += size
                extractor.advance()
            }
            val ms = (System.nanoTime() - start) / 1_000_000.0
            android.util.Log.i(
                "ChartPcmPerf",
                "extractor-only run=$run packets=$packets bytes=$bytes time=${"%.1f".format(ms)}ms",
            )
        }
        extractor.release()
    }

    private fun copyAsset(asset: String): File {
        val file = File(context.cacheDir, asset)
        if (!file.isFile) {
            instrumentation.context.assets.open("audio/$asset").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return file
    }
}
