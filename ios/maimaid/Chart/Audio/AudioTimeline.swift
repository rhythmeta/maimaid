import Foundation

/// Pure conversion between chart time, music position and mixer frames.
///
/// Chart time `T` maps to music position `M = T - leadIn - musicOffset`: a positive music offset
/// delays the music relative to the chart. The inverse is `M + leadIn + musicOffset`; seek targets
/// and the playback clock must always use that pair, otherwise a non-zero offset is applied twice.
///
/// SFX events are shifted by the reference engine's -50 ms answer offset plus the user offset and are
/// then mapped through the same music-position conversion, so music and SFX share one timeline and
/// the trigger frame is exact.
nonisolated enum AudioTimeline {
    /// Reference engine's fixed early hit-sound offset.
    static let baseSfxOffsetMs: Double = -50.0

    static func musicPositionMs(chartTimeMs: Double, leadInMs: Double, musicOffsetMs: Int) -> Double {
        chartTimeMs - leadInMs - Double(musicOffsetMs)
    }

    static func chartTimeMs(musicPositionMs: Double, leadInMs: Double, musicOffsetMs: Int) -> Double {
        musicPositionMs + leadInMs + Double(musicOffsetMs)
    }

    static func frame(forMusicPositionMs musicPositionMs: Double, sampleRate: Int) -> Int64 {
        roundToFrame(musicPositionMs * Double(sampleRate) / 1000.0)
    }

    static func musicPositionMs(forFrame frame: Int64, sampleRate: Int) -> Double {
        Double(frame) * 1000.0 / Double(sampleRate)
    }

    /// Mixer frame at which an SFX event becomes audible. Events that would land before the music
    /// start are clamped to frame 0 (they sound when playback starts, matching the reference's
    /// SoundPool path).
    static func frameForSfx(
        eventTimeMs: Double,
        leadInMs: Double,
        musicOffsetMs: Int,
        sfxOffsetMs: Double,
        sampleRate: Int
    ) -> Int64 {
        let audibleChartMs = eventTimeMs + baseSfxOffsetMs + sfxOffsetMs
        let frame = frame(
            forMusicPositionMs: musicPositionMs(
                chartTimeMs: audibleChartMs,
                leadInMs: leadInMs,
                musicOffsetMs: musicOffsetMs
            ),
            sampleRate: sampleRate
        )
        return max(0, frame)
    }

    static func chartTimeForFrame(
        _ frame: Int64,
        leadInMs: Double,
        musicOffsetMs: Int,
        sampleRate: Int
    ) -> Double {
        chartTimeMs(
            musicPositionMs: musicPositionMs(forFrame: frame, sampleRate: sampleRate),
            leadInMs: leadInMs,
            musicOffsetMs: musicOffsetMs
        )
    }

    /// Kotlin's `roundToLong`: half values round towards positive infinity, and a non-finite or
    /// out-of-range position degrades to 0 instead of trapping.
    private static func roundToFrame(_ value: Double) -> Int64 {
        guard value.isFinite, value > -9.0e18, value < 9.0e18 else { return 0 }
        return Int64((value + 0.5).rounded(.down))
    }
}
