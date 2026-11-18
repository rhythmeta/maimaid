package org.rhythmeta.maimaid.core.chart

/**
 * The engine's judge / answer sound set. Kept free of Android imports so the pure-Kotlin mixer and
 * its JVM tests can refer to it.
 *
 * [mono] mirrors the reference engine: these layers are monophonic and a new instance terminates the
 * previous one. The SoundPool fallback and the PCM mixer both honour the flag, the mixer with a short
 * fade instead of a hard cut.
 */
enum class ChartSfxKind(val assetName: String, val mono: Boolean) {
    ANSWER("answer.wav", false),
    TAP("tap.wav", false),
    TOUCH("touch.wav", false),
    BREAK("break.wav", true),
    SLIDE("slide.wav", true),
    CHEER("cheer.wav", true),
    EX("ex.wav", true),
    FIREWORK("firework.wav", true),
    BREAK_SLIDE("break_slide.wav", true),
    BREAK_SLIDE_CHEER("break_slide_cheer.wav", true),
    TOUCH_HOLD("touch_hold.wav", true),
}
