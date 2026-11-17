package org.rhythmeta.maimaid.core.chart

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

import org.rhythmeta.maimaid.core.chart.render.ChartRenderer

/**
 * Renders fixed chart frames to PNG so they can be diffed against the reference engine's
 * canvas output. Fixtures live in androidTest assets; output goes to the app's external
 * files directory for `adb pull`.
 */
@RunWith(AndroidJUnit4::class)
class ChartRenderGoldenTest {
    @Test
    fun renderOverlapFrames() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val maidata = instrumentation.context.assets
            .open("chart/overlap.txt")
            .bufferedReader()
            .readText()
        val chart = SimaiParser.parse(maidata, 5)
        val outputDirectory = instrumentation.targetContext.getExternalFilesDir(null) ?: error("no dir")
        listOf(4.05, 5.5).forEachIndexed { index, beat ->
            renderFrame(outputDirectory, "overlap-$index-$beat.png", chart, beat, effects = false)
        }
    }

    @Test
    fun renderCoverBackground() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val maidata = instrumentation.context.assets
            .open("chart/chord.txt")
            .bufferedReader()
            .readText()
        val chart = SimaiParser.parse(maidata, 5)
        val outputDirectory = instrumentation.targetContext.getExternalFilesDir(null) ?: error("no dir")
        val cover = Bitmap.createBitmap(190, 190, Bitmap.Config.ARGB_8888)
        Canvas(cover).drawColor(android.graphics.Color.RED)
        val bitmap = Bitmap.createBitmap(840, 840, Bitmap.Config.ARGB_8888)
        ChartRenderer().render(
            canvas = Canvas(bitmap),
            sizePx = 840f,
            chart = chart,
            currentBeats = 4.6,
            beatsPerMeasure = 4.0,
            config = ChartRenderConfig(showFireworks = false, showHitEffect = false),
            isPlaying = false,
            background = cover,
        )
        File(outputDirectory, "cover-bg.png").outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        val pixel = bitmap.getPixel(200, 200)
        org.junit.Assert.assertTrue(
            "cover background missing: ${Integer.toHexString(pixel)}",
            android.graphics.Color.red(pixel) > 80 && android.graphics.Color.green(pixel) == 0,
        )
    }

    @Test
    fun renderBreakChordFrames() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val maidata = instrumentation.context.assets
            .open("chart/breakchord.txt")
            .bufferedReader()
            .readText()
        val chart = SimaiParser.parse(maidata, 5)
        val outputDirectory = instrumentation.targetContext.getExternalFilesDir(null) ?: error("no dir")
        listOf(4.6, 8.6).forEachIndexed { index, beat ->
            renderFrame(outputDirectory, "breakchord-$index-$beat.png", chart, beat, effects = false)
        }
    }

    @Test
    fun renderChordFrames() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val maidata = instrumentation.context.assets
            .open("chart/chord.txt")
            .bufferedReader()
            .readText()
        val chart = SimaiParser.parse(maidata, 5)
        val outputDirectory = instrumentation.targetContext.getExternalFilesDir(null) ?: error("no dir")
        listOf(4.6, 7.2).forEachIndexed { index, beat ->
            renderFrame(outputDirectory, "chord-$index-$beat.png", chart, beat, effects = false)
        }
    }

    @Test
    fun renderEffectFrames() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val maidata = instrumentation.context.assets
            .open("chart/effects-maidata.txt")
            .bufferedReader()
            .readText()
        val chart = SimaiParser.parse(maidata, 5)
        val outputDirectory = instrumentation.targetContext.getExternalFilesDir(null) ?: error("no dir")
        listOf(4.3, 4.5, 5.1, 4.15).forEachIndexed { index, beat ->
            renderFrame(outputDirectory, "eff-$index-$beat.png", chart, beat, effects = true)
        }
    }

    @Test
    fun renderReferenceFrames() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val maidata = instrumentation.context.assets
            .open("chart/parity-maidata.txt")
            .bufferedReader()
            .readText()
        val chart = SimaiParser.parse(maidata, 5)
        val outputDirectory = instrumentation.targetContext.getExternalFilesDir(null) ?: error("no dir")
        val beats = listOf(8.25, 10.0, 12.9, 15.5, 16.5, 18.75)
        beats.forEachIndexed { index, beat ->
            renderFrame(outputDirectory, "frame-$index-$beat.png", chart, beat, effects = true)
            renderFrame(outputDirectory, "plain-$index-$beat.png", chart, beat, effects = false)
        }
        listOf(5.0, 6.0).forEachIndexed { index, beat ->
            renderFrame(outputDirectory, "hold-$index-$beat.png", chart, beat, effects = false)
        }
    }

    private fun renderFrame(
        outputDirectory: File,
        name: String,
        chart: Chart,
        beat: Double,
        effects: Boolean,
    ) {
        val bitmap = Bitmap.createBitmap(840, 840, Bitmap.Config.ARGB_8888)
        val renderer = ChartRenderer()
        renderer.render(
            canvas = Canvas(bitmap),
            sizePx = 840f,
            chart = chart,
            currentBeats = beat,
            beatsPerMeasure = 4.0,
            config = ChartRenderConfig(
                hudComboLabel = "COMBO",
                hudBreakNoExLabel = "NO EX",
                showFireworks = effects,
                showHitEffect = effects,
            ),
            isPlaying = false,
        )
        File(outputDirectory, name).outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        // Regression guard: the judgment ring and at least some artwork must be present.
        val ringPixel = bitmap.getPixel(420, 420 - (840 * 0.45f).toInt())
        org.junit.Assert.assertNotEquals(0, ringPixel and 0x00FFFFFF)
        var nonBlack = 0
        for (y in 0 until 840 step 8) {
            for (x in 0 until 840 step 8) {
                if (bitmap.getPixel(x, y) and 0x00FFFFFF != 0) nonBlack++
            }
        }
        org.junit.Assert.assertTrue("stage looks empty", nonBlack > 100)
    }
}
