import Foundation

/// Fixed-capacity SFX voice pool. It never refuses a new voice: when `maxVoices` voices are already
/// sounding, the oldest one is faded out over `evictFadeFrames`; extra slots (`reserve`) hold the
/// fading voices so the new event can start immediately. Only when even the reserve is exhausted is
/// the oldest fading voice cut, which keeps the promise "a new event always plays" under any density.
///
/// The pool also implements the reference engine's monophonic layers: starting a voice in a mono group
/// fades out the previous voice of the same group instead of cutting it.
nonisolated final class ChartVoicePool {
    let maxVoices: Int
    let reserve: Int
    let evictFadeFrames: Int

    private let capacity: Int

    private var samples: [[Int16]?]
    private var samplePosition: [Int]
    private var startFrame: [Int64]
    private var sequence: [Int64]
    private var layer: [Int]
    private var monoGroup: [Int]
    private var looping: [Bool]
    private var loopEndFrame: [Int64]
    private var fadeLeft: [Int]
    private var fadeTotal: [Int]
    private var fadeDelay: [Int]
    private var active: [Bool]
    private var bufferOffset: [Int]

    private var activeCount = 0
    private var nextSequence: Int64 = 0
    private var evictionCount = 0
    private var hardStopCount = 0
    private var peakVoices = 0

    init(maxVoices: Int, reserve: Int, evictFadeFrames: Int) {
        let voices = max(1, maxVoices)
        let spare = max(2, reserve)
        self.maxVoices = voices
        self.reserve = spare
        self.evictFadeFrames = max(1, evictFadeFrames)
        capacity = voices + spare
        samples = Array(repeating: nil, count: capacity)
        samplePosition = Array(repeating: 0, count: capacity)
        startFrame = Array(repeating: 0, count: capacity)
        sequence = Array(repeating: 0, count: capacity)
        layer = Array(repeating: 0, count: capacity)
        monoGroup = Array(repeating: -1, count: capacity)
        looping = Array(repeating: false, count: capacity)
        loopEndFrame = Array(repeating: -1, count: capacity)
        fadeLeft = Array(repeating: 0, count: capacity)
        fadeTotal = Array(repeating: 0, count: capacity)
        fadeDelay = Array(repeating: 0, count: capacity)
        active = Array(repeating: false, count: capacity)
        bufferOffset = Array(repeating: 0, count: capacity)
    }

    var audibleVoices: Int { activeCount }
    var evictions: Int { evictionCount }
    var hardStops: Int { hardStopCount }
    var peak: Int { peakVoices }

    func start(
        sample: [Int16],
        layerIndex: Int,
        monoGroupIndex: Int,
        frame: Int64,
        offsetInBuffer: Int,
        isLooping: Bool,
        loopEnd: Int64,
        monoFadeFrames: Int
    ) -> Bool {
        if monoGroupIndex >= 0 {
            for index in 0..<capacity {
                if active[index] && monoGroup[index] == monoGroupIndex && fadeTotal[index] == 0 {
                    beginFade(index: index, frames: monoFadeFrames, delaySamples: offsetInBuffer)
                }
            }
        }
        // Keep the number of sounding voices at maxVoices even when free reserve slots exist.
        if activeCount >= maxVoices { _ = evictOldestActive(delaySamples: offsetInBuffer) }
        var slot = freeSlot()
        if slot < 0 {
            let evicted = evictOldestActive(delaySamples: offsetInBuffer)
            if evicted { slot = freeSlot() }
        }
        if slot < 0 {
            hardStopOldestFading()
            slot = freeSlot()
        }
        if slot < 0 {
            hardStopOldestActive()
            slot = freeSlot()
        }
        if slot < 0 { return false }

        samples[slot] = sample
        samplePosition[slot] = 0
        startFrame[slot] = frame
        sequence[slot] = nextSequence
        nextSequence += 1
        layer[slot] = layerIndex
        monoGroup[slot] = monoGroupIndex
        looping[slot] = isLooping
        loopEndFrame[slot] = loopEnd
        fadeLeft[slot] = 0
        fadeTotal[slot] = 0
        fadeDelay[slot] = 0
        bufferOffset[slot] = offsetInBuffer
        active[slot] = true
        activeCount += 1
        if activeCount > peakVoices { peakVoices = activeCount }
        return true
    }

    /// Starts a fade-out over `frames` samples. No-op when the voice is already fading faster.
    func beginFade(index: Int, frames: Int, delaySamples: Int = 0) {
        guard active[index] else { return }
        if frames <= 0 {
            hardStop(index: index)
            return
        }
        if fadeTotal[index] != 0 && fadeTotal[index] <= frames { return }
        fadeTotal[index] = frames
        fadeLeft[index] = frames
        fadeDelay[index] = max(0, delaySamples)
    }

    /// Stops loops whose scheduled end has been reached; called once per mix buffer.
    func updateLoops(bufferStartFrame: Int64, fadeFrames: Int) {
        for index in 0..<capacity {
            guard active[index], looping[index] else { continue }
            if bufferStartFrame >= loopEndFrame[index] {
                looping[index] = false
                beginFade(index: index, frames: fadeFrames, delaySamples: 0)
            }
        }
    }

    func stopAll() {
        for index in 0..<capacity {
            active[index] = false
            samples[index] = nil
            fadeLeft[index] = 0
            fadeTotal[index] = 0
            fadeDelay[index] = 0
            bufferOffset[index] = 0
        }
        activeCount = 0
    }

    func render(
        into mix: inout [Float],
        frames: Int,
        bufferStartFrame: Int64,
        layerVolumes: [Float],
        voiceBaseGain: Float
    ) {
        guard frames > 0, mix.count >= frames * 2, !layerVolumes.isEmpty else { return }
        for index in 0..<capacity {
            guard active[index], let sample = samples[index] else { continue }
            guard !sample.isEmpty else {
                deactivate(index: index)
                continue
            }
            let layerIndex = min(max(layer[index], 0), layerVolumes.count - 1)
            let gain = voiceBaseGain * layerVolumes[layerIndex]
            var position = samplePosition[index]
            let offset = min(max(bufferOffset[index], 0), frames)
            bufferOffset[index] = 0
            var sampleIndex = offset
            while sampleIndex < frames {
                let fading = fadeTotal[index] > 0 && fadeDelay[index] == 0
                let fade = fading ? Float(fadeLeft[index]) / Float(fadeTotal[index]) : 1
                let value = Float(sample[position]) / 32_768 * gain * fade
                mix[sampleIndex * 2] += value
                mix[sampleIndex * 2 + 1] += value
                position += 1
                if position >= sample.count {
                    if looping[index] && bufferStartFrame + Int64(sampleIndex) < loopEndFrame[index] {
                        position = 0
                    } else {
                        deactivate(index: index)
                        break
                    }
                }
                if fadeTotal[index] > 0 {
                    if fadeDelay[index] > 0 {
                        fadeDelay[index] -= 1
                    } else {
                        fadeLeft[index] -= 1
                        if fadeLeft[index] <= 0 {
                            deactivate(index: index)
                            break
                        }
                    }
                }
                sampleIndex += 1
            }
            if active[index] { samplePosition[index] = position }
        }
    }

    // MARK: - Slots

    private func deactivate(index: Int) {
        guard active[index] else { return }
        active[index] = false
        samples[index] = nil
        fadeLeft[index] = 0
        fadeTotal[index] = 0
        fadeDelay[index] = 0
        bufferOffset[index] = 0
        activeCount -= 1
    }

    private func freeSlot() -> Int {
        for index in 0..<capacity where !active[index] { return index }
        return -1
    }

    private func evictOldestActive(delaySamples: Int = 0) -> Bool {
        var oldest = -1
        for index in 0..<capacity {
            guard active[index], fadeTotal[index] == 0 else { continue }
            if oldest < 0 || sequence[index] < sequence[oldest] { oldest = index }
        }
        if oldest < 0 { return false }
        beginFade(index: oldest, frames: evictFadeFrames, delaySamples: delaySamples)
        evictionCount += 1
        return true
    }

    private func hardStopOldestFading() {
        var oldest = -1
        for index in 0..<capacity {
            guard active[index], fadeTotal[index] != 0 else { continue }
            if oldest < 0 || sequence[index] < sequence[oldest] { oldest = index }
        }
        if oldest >= 0 {
            hardStopCount += 1
            deactivate(index: oldest)
        }
    }

    private func hardStopOldestActive() {
        var oldest = -1
        for index in 0..<capacity {
            guard active[index] else { continue }
            if oldest < 0 || sequence[index] < sequence[oldest] { oldest = index }
        }
        if oldest >= 0 {
            hardStopCount += 1
            deactivate(index: oldest)
        }
    }

    private func hardStop(index: Int) {
        hardStopCount += 1
        deactivate(index: index)
    }
}
