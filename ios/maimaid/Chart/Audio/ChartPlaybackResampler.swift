import Foundation

// MARK: - Playback rate resampler

/// Applies the playback speed by consuming music frames faster than output frames, which is exactly
/// what `AudioTrack`'s `PlaybackParams` does: pitch follows the speed (no time-stretch) and the SFX
/// timeline stays locked to the music frame axis.
///
/// The mixer always renders whole music frames, so the fractional read position is carried across
/// buffers together with the two music frames the next interpolation needs. Output frames that have
/// no interpolation partner yet (decoder starvation) are silent instead of stalling the timeline.
nonisolated final class ChartPlaybackResampler {
    static let minimumStep = 0.1
    static let maximumStep = 2.0

    private let capacity: Int
    private var pending: [Float]
    private var pendingStartFrame: Int64 = 0
    private var pendingCount = 0
    private var position: Double = 0
    private var musicScratch: [Int16]

    private(set) var step: Double = 1

    init(maxOutputFrames: Int) {
        let frames = max(1, maxOutputFrames)
        capacity = frames * 2 + 8
        pending = [Float](repeating: 0, count: capacity * 2)
        musicScratch = [Int16](repeating: 0, count: capacity * 2)
    }

    func reset(atFrame frame: Int64) {
        let clamped = max(0, frame)
        position = Double(clamped)
        pendingStartFrame = clamped
        pendingCount = 0
    }

    func setStep(_ newStep: Double) {
        let clamped = min(Self.maximumStep, max(Self.minimumStep, newStep))
        guard clamped != step else { return }
        step = clamped
    }

    /// Renders `frames` interleaved stereo output frames and returns the music frame of the first one.
    func render(into output: inout [Float], frames: Int, mixer: ChartMixer) -> Int64 {
        precondition(frames > 0 && frames <= capacity)
        precondition(output.count >= frames * 2)
        var startPosition = position
        let startFrame = Int64(startPosition.rounded(.down))

        // Drop consumed frames, and recover if the mixer ever ended up behind the read position.
        if startFrame > pendingStartFrame {
            let drop = Int(min(startFrame - pendingStartFrame, Int64(pendingCount)))
            if drop > 0 {
                pendingCount -= drop
                if pendingCount > 0 {
                    for index in 0..<(pendingCount * 2) {
                        pending[index] = pending[drop * 2 + index]
                    }
                }
                pendingStartFrame += Int64(drop)
            }
        } else if startFrame < pendingStartFrame {
            startPosition = Double(pendingStartFrame)
            position = startPosition
        }

        let lastPosition = startPosition + Double(frames - 1) * step
        let requiredEnd = Int64(lastPosition.rounded(.down)) + 2
        let availableEnd = pendingStartFrame + Int64(pendingCount)
        if requiredEnd > availableEnd {
            let missing = Int(min(requiredEnd - availableEnd, Int64(capacity - pendingCount)))
            if missing > 0 {
                mixer.render(&musicScratch, frames: missing)
                let scale = Float(1.0 / 32_768.0)
                for index in 0..<(missing * 2) {
                    pending[pendingCount * 2 + index] = Float(musicScratch[index]) * scale
                }
                pendingCount += missing
            }
        }

        let availableFrames = pendingCount
        for frame in 0..<frames {
            let samplePosition = startPosition + Double(frame) * step
            let relative = samplePosition - Double(pendingStartFrame)
            guard relative >= 0, relative < Double(availableFrames - 1) else {
                output[frame * 2] = 0
                output[frame * 2 + 1] = 0
                continue
            }
            let index = Int(relative)
            let fraction = Float(relative - Double(index))
            let leftA = pending[index * 2]
            let leftB = pending[(index + 1) * 2]
            let rightA = pending[index * 2 + 1]
            let rightB = pending[(index + 1) * 2 + 1]
            output[frame * 2] = leftA + (leftB - leftA) * fraction
            output[frame * 2 + 1] = rightA + (rightB - rightA) * fraction
        }
        position = startPosition + Double(frames) * step
        return startFrame
    }
}
