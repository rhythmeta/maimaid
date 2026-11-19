import Foundation
import Observation

/// Drives the audible chart clock for the preview page.
///
/// The engine is the master clock while a track is loaded (it knows the sample frame the listener
/// hears); charts without a track, or whose track failed to decode, fall back to a wall-clock
/// advance so the stage still animates. Every mutation happens on the main actor; the engine's frame
/// position read is lock-free.
@MainActor
@Observable
final class ChartPlaybackController {
    /// Chart duration in milliseconds.
    let durationMs: Double

    /// Current chart position; refreshed at display rate while playing so the scrubber and the
    /// timelines stay in sync.
    private(set) var positionMs: Double = 0
    private(set) var isPlaying = false

    /// True while the user drags the scrubber: the tick loop then leaves the position alone.
    private(set) var isScrubbing = false
    private var wasPlayingBeforeScrub = false

    /// User playback speed (1.0 = normal); the hold-to-boost overlay multiplies it.
    var playbackSpeed: Double = 1
    /// Extra multiplier applied while the stage is held down (1.5x fast-forward).
    var speedBoost: Double = 1

    private let engine: ChartAudioEngine
    private var ticker: Task<Void, Never>?
    private var anchorPositionMs: Double = 0
    private var anchorInstant = ContinuousClock.now

    init(durationMs: Double, engine: ChartAudioEngine) {
        self.durationMs = max(durationMs, 1)
        self.engine = engine
    }

    /// Stops the tick loop when the page goes away.
    func stop() {
        stopTicker()
    }

    /// Effective speed multiplier for the audible/visual clock.
    private var effectiveSpeed: Double {
        max(0.1, playbackSpeed * speedBoost)
    }

    /// Position right now, interpolated between observable updates so a 120 Hz display stays smooth.
    func currentPositionMs() -> Double {
        guard isPlaying, !isScrubbing else { return positionMs }
        if engine.isReady {
            return min(max(engine.chartPositionMs, 0), durationMs)
        }
        let elapsed = anchorInstant.duration(to: .now)
        let seconds = Double(elapsed.components.seconds) + Double(elapsed.components.attoseconds) * 1e-18
        return min(max(anchorPositionMs + seconds * 1000 * effectiveSpeed, 0), durationMs)
    }

    func play() {
        guard !isPlaying else { return }
        if positionMs >= durationMs {
            positionMs = 0
            engine.seek(toChartTimeMs: 0, playAfterSeek: false)
        }
        anchorPositionMs = positionMs
        anchorInstant = .now
        engine.setPlaybackSpeed(Float(effectiveSpeed))
        engine.play()
        isPlaying = true
        startTicker()
    }

    func pause() {
        guard isPlaying else { return }
        let current = currentPositionMs()
        isPlaying = false
        positionMs = current
        stopTicker()
        engine.pause()
        engine.stopSfx()
    }

    func toggle() {
        if isPlaying {
            pause()
        } else {
            play()
        }
    }

    /// Restarts the preview from the beginning after the chart reached its end.
    func rewind() {
        positionMs = 0
        anchorPositionMs = 0
        anchorInstant = .now
        engine.seek(toChartTimeMs: 0, playAfterSeek: false)
    }

    func beginScrub() {
        guard !isScrubbing else { return }
        wasPlayingBeforeScrub = isPlaying
        isScrubbing = true
        if wasPlayingBeforeScrub {
            isPlaying = false
            stopTicker()
            engine.pause()
            engine.stopSfx()
        }
    }

    func scrub(to positionMs: Double) {
        self.positionMs = min(max(positionMs, 0), durationMs)
    }

    func endScrub() {
        guard isScrubbing else { return }
        isScrubbing = false
        anchorPositionMs = positionMs
        anchorInstant = .now
        if wasPlayingBeforeScrub {
            engine.seek(toChartTimeMs: positionMs, playAfterSeek: true)
            isPlaying = true
            startTicker()
        } else {
            engine.seek(toChartTimeMs: positionMs, playAfterSeek: false)
        }
        wasPlayingBeforeScrub = false
    }

    /// Moves to a chart position without a scrub gesture (measure jump buttons).
    func seek(to positionMs: Double) {
        let clamped = min(max(positionMs, 0), durationMs)
        self.positionMs = clamped
        anchorPositionMs = clamped
        anchorInstant = .now
        engine.seek(toChartTimeMs: clamped, playAfterSeek: isPlaying)
    }

    /// Applies a speed change (settings slider or the hold-to-boost overlay).
    func applyPlaybackSpeed(settingsSpeed: Double, boost: Double) {
        playbackSpeed = settingsSpeed
        if speedBoost != boost {
            positionMs = currentPositionMs()
            anchorPositionMs = positionMs
            anchorInstant = .now
            speedBoost = boost
        }
        engine.setPlaybackSpeed(Float(effectiveSpeed))
    }

    /// Stops playback when the page leaves the foreground.
    func handleBackground() {
        pause()
    }

    // MARK: - Tick loop

    private func startTicker() {
        stopTicker()
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(8))
                guard let self else { return }
                guard self.isPlaying else { return }
                self.tick()
            }
        }
    }

    private func stopTicker() {
        ticker?.cancel()
        ticker = nil
    }

    private func tick() {
        guard !isScrubbing else { return }
        let position = currentPositionMs()
        positionMs = position
        if position >= durationMs {
            isPlaying = false
            stopTicker()
            engine.pause()
            engine.stopSfx()
            return
        }
        if !engine.isReady {
            // Silent fallback: keep the anchor chasing the wall clock so interpolation stays valid.
            anchorPositionMs = position
            anchorInstant = .now
        }
    }
}
