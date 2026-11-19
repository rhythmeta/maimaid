import Foundation

/// Port of the reference engine's BPM-change flash.
///
/// While the chart plays, a BPM rise flashes the readout red and a drop flashes it green, then both
/// fade back to the readout's own colour within half a second, so a tempo change is noticeable
/// without a persistent banner. The fade target is supplied by the caller because the HUD sits on
/// the dark stage while the rhythm lane follows the theme (dark text in light mode).
nonisolated final class BpmChangeFlash {
    /// Milliseconds from a monotonic origin, mirroring Android's `SystemClock.uptimeMillis()`.
    typealias Clock = @Sendable () -> Int64

    /// Default clock: `ProcessInfo.systemUptime` counts seconds since boot, the same base Android's
    /// `SystemClock.uptimeMillis()` uses. Injectable so tests can drive the fade deterministically.
    static let systemUptimeClock: Clock = {
        Int64((ProcessInfo.processInfo.systemUptime * 1000.0).rounded(.down))
    }

    private let fadeDurationMs: Int64
    private let instantMs: Int64
    private let clock: Clock

    private var lastBpm = Double.nan
    private var changedAtMs: Int64 = 0
    private var direction = 0

    init(fadeDurationMs: Int64 = 500, instantMs: Int64 = 50,
         clock: @escaping Clock = BpmChangeFlash.systemUptimeClock) {
        self.fadeDurationMs = fadeDurationMs
        self.instantMs = instantMs
        self.clock = clock
    }

    /// Forgets the previous tempo, e.g. after switching to another chart.
    func reset() {
        lastBpm = .nan
        changedAtMs = 0
        direction = 0
    }

    /// Returns the colour to draw `currentBpm` with, blending from the up/down flash into
    /// `baseColor`, or nil when the readout should keep `baseColor` unchanged.
    ///
    /// `nowMs` mirrors the Kotlin helper's defaulted `nowMs = SystemClock.uptimeMillis()` argument;
    /// pass it to step the fade by hand, otherwise the injected clock supplies the timestamp.
    func color(forBpm currentBpm: Double, isPlaying: Bool, baseColor: ChartColor,
               nowMs: Int64? = nil) -> ChartColor? {
        let now = nowMs ?? clock()
        if !isPlaying {
            direction = 0
            lastBpm = currentBpm
            return nil
        }
        if lastBpm.isNaN {
            lastBpm = currentBpm
            return nil
        }
        if currentBpm != lastBpm {
            changedAtMs = now
            direction = currentBpm > lastBpm ? 1 : -1
            lastBpm = currentBpm
        }
        if direction == 0 { return nil }
        let elapsed = now - changedAtMs
        if elapsed >= fadeDurationMs { return nil }
        let fade = Self.clampToUnit(Double(elapsed - instantMs) / Double(fadeDurationMs - instantMs))

        // Rise: red -> base. Drop: green -> base.
        let flashRed: Double = direction > 0 ? 255 : 0
        let flashGreen: Double = direction > 0 ? 0 : 255
        let red = Self.blend(from: flashRed, to: Self.channelByte(baseColor.red), amount: fade)
        let green = Self.blend(from: flashGreen, to: Self.channelByte(baseColor.green), amount: fade)
        let blue = Self.blend(from: 0, to: Self.channelByte(baseColor.blue), amount: fade)
        return ChartColor(red: red, green: green, blue: blue, alpha: 1)
    }

    /// Kotlin blends packed 0…255 channels and packs the result back with an opaque alpha, so the
    /// base colour's own alpha is intentionally dropped here too.
    private static func channelByte(_ channel: Double) -> Double {
        (channel * 255).rounded()
    }

    /// `blend(from, to, amount)` from the Kotlin port: rounded, clamped to a byte.
    private static func blend(from: Double, to: Double, amount: Double) -> Double {
        let value = (from + (to - from) * amount).rounded()
        return Swift.min(255, Swift.max(0, value)) / 255
    }

    /// `coerceIn(0.0, 1.0)`; NaN (a zero-length fade window) degrades to 0 instead of trapping.
    private static func clampToUnit(_ value: Double) -> Double {
        Swift.min(1, Swift.max(0, value))
    }
}
