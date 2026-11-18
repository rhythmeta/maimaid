package org.rhythmeta.maimaid.core.chart.audio

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rhythmeta.maimaid.core.chart.ChartSfxKind

class ChartMixerTest {
    private val rate = 48_000

    private class FakeBank(
        private val samples: Map<ChartSfxKind, ShortArray>,
    ) : SfxSampleBank {
        override val sampleRate: Int = 48_000
        override fun sample(kind: ChartSfxKind): ShortArray? = samples[kind]
    }

    private fun bank(vararg samples: Pair<ChartSfxKind, ShortArray>): SfxSampleBank =
        FakeBank(samples.toMap())

    private fun impulse(value: Short = 10_000): ShortArray = shortArrayOf(value)

    private fun constant(value: Short, length: Int = 4_800): ShortArray =
        ShortArray(length) { value }

    private fun tapEvent(frame: Long): ChartMixEvent =
        ChartMixEvent(frame, playsAnswer = false, judgeKinds = intArrayOf(ChartSfxKind.TAP.ordinal), touchHoldEndFrame = -1)

    private fun render(mixer: ChartMixer, frames: Int): ShortArray {
        val out = ShortArray(frames * 2)
        mixer.render(out, frames)
        return out
    }

    @Test
    fun passesMusicThroughWithVolume() {
        val source = MemoryPcmFrameSource(ShortArray(1_024) { 16_384 })
        val mixer = ChartMixer(rate, bank())
        mixer.setMusicSource(source)
        mixer.musicVolume = 0.5f
        val out = render(mixer, 256)
        assertEquals(8_191.0, out[0].toDouble(), 2.0)
        assertEquals(8_191.0, out[1].toDouble(), 2.0)
    }

    @Test
    fun startsVoiceAtExactSampleFrame() {
        val mixer = ChartMixer(rate, bank(ChartSfxKind.TAP to impulse()))
        mixer.setEvents(listOf(tapEvent(100)))
        val out = render(mixer, 512)
        assertEquals(0, out[198].toInt())
        assertEquals(0, out[199].toInt())
        assertTrue(out[200].toInt() != 0)
        assertTrue(out[201].toInt() != 0)
        assertEquals(0, out[202].toInt())
    }

    @Test
    fun seekSkipsPastEventsButKeepsTheOneAtTheSeekFrame() {
        val mixer = ChartMixer(rate, bank(ChartSfxKind.TAP to impulse()))
        mixer.setEvents(listOf(tapEvent(100), tapEvent(200), tapEvent(300)))
        mixer.seekToFrame(200)
        val out = render(mixer, 256)
        assertTrue(out[0].toInt() != 0) // event at 200
        assertEquals(0, out[2].toInt()) // event at 100 must not replay
        assertTrue(out[(300 - 200) * 2].toInt() != 0) // event at 300
        val second = render(mixer, 256)
        assertEquals(0, second.maxOf { abs(it.toInt()) })
    }

    @Test
    fun replacingEventsDoesNotReplayThePast() {
        val mixer = ChartMixer(rate, bank(ChartSfxKind.TAP to impulse()))
        mixer.setEvents(listOf(tapEvent(100)))
        render(mixer, 512)
        mixer.setEvents(listOf(tapEvent(100), tapEvent(600)))
        val out = render(mixer, 256)
        assertEquals(0, out[0].toInt())
        assertTrue(out[(600 - 512) * 2].toInt() != 0)
        val next = render(mixer, 256)
        assertEquals(0, next.maxOf { abs(it.toInt()) })
    }

    @Test
    fun fullPoolEvictsOldestButNeverDropsNewEvents() {
        val mixer = ChartMixer(
            sampleRate = rate,
            bank = bank(ChartSfxKind.TAP to constant(1_000, 48_000)),
            maxVoices = 2,
            reserve = 2,
        )
        mixer.setEvents((0 until 40).map { tapEvent(it * 128L) })
        repeat(20) { render(mixer, 256) }
        assertEquals(40, mixer.stats.eventsDue)
        assertEquals(40, mixer.stats.eventsMixed)
        assertTrue(mixer.stats.maxVoices >= 2)
        assertTrue(mixer.stats.voiceEvictions > 0)
    }

    @Test
    fun gainScalesWithTheNumberOfVoices() {
        val one = ChartMixer(rate, bank(ChartSfxKind.TAP to constant(1_000, 4_800)))
        one.setEvents(listOf(tapEvent(0)))
        val single = render(one, 256)[0].toInt()

        val two = ChartMixer(rate, bank(ChartSfxKind.TAP to constant(1_000, 4_800)))
        two.setEvents(listOf(tapEvent(0), tapEvent(0)))
        val pair = render(two, 256)[0].toInt()
        // Two equal voices at 1/sqrt(2) each sum to ~1.414x a single voice.
        assertEquals(single * 1.414, pair.toDouble(), single * 0.05)
    }

    @Test
    fun limiterKeepsTheMixInsideFullScale() {
        val mixer = ChartMixer(rate, bank(ChartSfxKind.TAP to constant(32_767, 4_800)))
        mixer.setEvents((0 until 12).map { tapEvent(0) })
        val out = render(mixer, 256)
        val peak = out.maxOf { abs(it.toInt()) }
        assertTrue("peak=$peak", peak <= 32_767)
        assertTrue("peak=$peak", peak > 30_000)
    }

    @Test
    fun monoLayerFadesThePreviousVoiceInsteadOfCuttingIt() {
        val first = constant(5_000, 4_800)
        val silent = constant(0, 4_800)
        val bank = object : SfxSampleBank {
            override val sampleRate: Int = 48_000
            private var call = 0
            override fun sample(kind: ChartSfxKind): ShortArray? {
                if (kind != ChartSfxKind.BREAK) return null
                val result = if (call == 0) first else silent
                call++
                return result
            }
        }
        val mixer = ChartMixer(rate, bank)
        val breakKind = intArrayOf(ChartSfxKind.BREAK.ordinal)
        mixer.setEvents(
            listOf(
                ChartMixEvent(0, false, breakKind, -1),
                ChartMixEvent(1_000, false, breakKind, -1),
            ),
        )
        val out = ShortArray(2_048 * 2)
        mixer.render(out, 2_048)
        val atStart = abs(out[100 * 2].toInt())
        val duringFade = abs(out[1_100 * 2].toInt())
        val afterFade = abs(out[1_900 * 2].toInt())
        assertTrue("atStart=$atStart", atStart > 0)
        assertTrue("duringFade=$duringFade atStart=$atStart", duringFade in 1..atStart)
        assertEquals(0, afterFade)
    }

    @Test
    fun touchHoldLoopStopsAtItsEndFrame() {
        val mixer = ChartMixer(
            rate,
            bank(ChartSfxKind.TOUCH_HOLD to constant(1_000, 100)),
        )
        mixer.setEvents(
            listOf(
                ChartMixEvent(0, false, intArrayOf(ChartSfxKind.TOUCH_HOLD.ordinal), 500),
            ),
        )
        val blocks = ArrayList<ShortArray>()
        repeat(12) { blocks += render(mixer, 256) }
        // Still sounding shortly after the loop start, silent long after the end + fade.
        val early = blocks[0].maxOf { abs(it.toInt()) }
        val late = blocks[11].maxOf { abs(it.toInt()) }
        assertTrue("early=$early", early > 0)
        assertEquals(0, late)
    }

    @Test
    fun mutedOutputStillConsumesEvents() {
        val mixer = ChartMixer(rate, bank(ChartSfxKind.TAP to impulse()))
        mixer.setEvents(listOf(tapEvent(100)))
        mixer.sfxEnabled = false
        val muted = render(mixer, 512)
        assertEquals(0, muted.maxOf { abs(it.toInt()) })
        assertEquals(1, mixer.stats.eventsMuted)
        mixer.sfxEnabled = true
        val after = render(mixer, 512)
        assertEquals(0, after.maxOf { abs(it.toInt()) })
    }

    @Test
    fun seekIntoATouchHoldRestoresItsLoop() {
        val mixer = ChartMixer(
            rate,
            bank(ChartSfxKind.TOUCH_HOLD to constant(1_000, 100)),
        )
        mixer.setEvents(
            listOf(
                ChartMixEvent(0, false, intArrayOf(ChartSfxKind.TOUCH_HOLD.ordinal), 5_000),
            ),
        )
        mixer.seekToFrame(2_000)
        val out = render(mixer, 256)
        assertTrue(out.maxOf { abs(it.toInt()) } > 0)
    }

    @Test
    fun missingSamplesAreCountedInsteadOfCrashing() {
        val mixer = ChartMixer(rate, bank())
        mixer.setEvents(listOf(tapEvent(10)))
        render(mixer, 256)
        assertEquals(1, mixer.stats.eventsDue)
        assertEquals(0, mixer.stats.eventsMixed)
        assertEquals(1, mixer.stats.eventsWithoutSamples)
    }

    @Test
    fun noEventIsLostAtHighDensity() {
        val mixer = ChartMixer(
            sampleRate = rate,
            bank = bank(ChartSfxKind.TAP to constant(500, 200)),
            maxVoices = 4,
            reserve = 4,
        )
        val events = (0 until 400).map { tapEvent(it * 24L) }
        mixer.setEvents(events)
        repeat(40) { render(mixer, 256) }
        assertEquals(400, mixer.stats.eventsDue)
        assertEquals(400, mixer.stats.eventsMixed)
        assertEquals(0, mixer.stats.eventsWithoutSamples)
    }
}
