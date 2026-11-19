import Foundation

/// Extrapolates the audible music position from the render timestamps the audio graph reports.
///
/// A timestamp pairs a music frame position with the host time at which that frame is presented.
/// Between samples the clock runs on the monotonic clock at the current playback speed. Small
/// discrepancies are corrected by nudging the clock rate (at most ±3 % of the playback speed, over
/// `convergenceSeconds`); a large discrepancy is treated as a discontinuity and snapped, which is
/// what a seek or a flush looks like.
///
/// The port keeps the reference's value semantics: the engine stores the clock inside its locked
/// control snapshot, so a value type removes the need for a second lock (the Kotlin class is only
/// ever mutated from one thread at a time for the same reason).
nonisolated struct ChartAudioClock: Sendable {
    let sampleRate: Int
    let convergenceSeconds: Double
    let maximumRelativeCorrection: Double
    let snapThresholdMs: Double

    private(set) var isPlaying = false
    private(set) var playbackSpeed: Double = 1.0

    private var anchorFrame = 0.0
    private var anchorNanos: Int64 = 0
    private var correction = 0.0
    private var seeded = false

    init(
        sampleRate: Int,
        convergenceSeconds: Double = 2.0,
        maximumRelativeCorrection: Double = 0.03,
        snapThresholdMs: Double = 50.0
    ) {
        self.sampleRate = sampleRate
        self.convergenceSeconds = convergenceSeconds
        self.maximumRelativeCorrection = maximumRelativeCorrection
        self.snapThresholdMs = snapThresholdMs
    }

    mutating func reset(frame: Double, nanoTime: Int64, playing: Bool) {
        anchorFrame = frame
        anchorNanos = nanoTime
        correction = 0.0
        isPlaying = playing
        seeded = true
    }

    mutating func setPlaying(_ playing: Bool, nanoTime: Int64) {
        guard isPlaying != playing else { return }
        anchorFrame = positionFrameAt(nanoTime)
        anchorNanos = nanoTime
        correction = 0.0
        isPlaying = playing
    }

    mutating func setSpeed(_ newSpeed: Float, nanoTime: Int64) {
        let clamped = min(2.0, max(0.1, Double(newSpeed)))
        guard clamped != playbackSpeed else { return }
        anchorFrame = positionFrameAt(nanoTime)
        anchorNanos = nanoTime
        correction = 0.0
        playbackSpeed = clamped
    }

    func positionFrameAt(_ nanoTime: Int64) -> Double {
        guard seeded, isPlaying else { return anchorFrame }
        let elapsedSeconds = Double(nanoTime - anchorNanos) / 1_000_000_000.0
        return anchorFrame + elapsedSeconds * Double(sampleRate) * (playbackSpeed + correction)
    }

    mutating func onTimestamp(frame timestampFrame: Int64, nanoTime: Int64) {
        guard seeded else {
            reset(frame: Double(timestampFrame), nanoTime: nanoTime, playing: isPlaying)
            return
        }
        guard isPlaying else { return }
        let sampled = Double(timestampFrame)
        let projected = positionFrameAt(nanoTime)
        let errorFrames = sampled - projected
        let snapFrames = snapThresholdMs * Double(sampleRate) / 1000.0
        if errorFrames > snapFrames {
            // The audio is clearly ahead (e.g. after a flush): snap forward.
            anchorFrame = sampled
            anchorNanos = nanoTime
            correction = 0.0
            return
        }
        // Never snap backwards: right after a resume the graph still reports a playhead behind the
        // projected clock, and jumping back is exactly the visible jitter this clock exists to
        // avoid. A real backwards jump is delivered as an explicit reset instead.
        let errorSeconds = errorFrames / Double(sampleRate)
        let limit = maximumRelativeCorrection * playbackSpeed
        correction = min(limit, max(-limit, errorSeconds / convergenceSeconds))
        // Keep the visual clock continuous: continue from the projected position at the sampled
        // instant while the corrected rate converges over the next timestamp interval.
        anchorFrame = projected
        anchorNanos = nanoTime
    }
}
