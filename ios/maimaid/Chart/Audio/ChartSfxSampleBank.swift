import Foundation

/// Mono PCM samples of one SFX asset at the mixer's sample rate.
nonisolated struct ChartSfxSample: Sendable {
    let sampleRate: Int
    let samples: [Int16]
}

/// Failure of the bundled WAV parser. A missing or unparsable asset only drops that one kind, so the
/// error is never surfaced to the UI.
nonisolated struct ChartSfxSampleError: LocalizedError {
    var message: String
    var errorDescription: String? { message }
}

/// Loads the chart's judge / answer WAV assets into mono PCM at the mixer's sample rate.
///
/// The shipped assets are 48 kHz mono 16-bit, so the common path is a plain RIFF parse with no
/// conversion. Other rates are accepted with a linear interpolation fallback so a future asset swap
/// cannot silence the layer. Loading is resilient by design: a missing or unreadable file simply
/// means that kind is absent and the mixer counts it as a missing sample.
nonisolated final class ChartSfxSampleBank: ChartSfxSampleProviding, Sendable {
    let sampleRate: Int

    private let samples: [ChartSfxKind: ChartSfxSample]

    /// Kinds that could be decoded, in declaration order (used by `ChartAudioRenderer.debugSummary`).
    let loadedKinds: [ChartSfxKind]
    /// Kinds whose file was missing or unusable.
    let missingKinds: [ChartSfxKind]

    init(sampleRate: Int, samples: [ChartSfxKind: ChartSfxSample]) {
        self.sampleRate = sampleRate
        self.samples = samples
        loadedKinds = ChartSfxKind.allCases.filter { samples[$0] != nil }
        missingKinds = ChartSfxKind.allCases.filter { samples[$0] == nil }
    }

    func sample(_ kind: ChartSfxKind) -> [Int16]? {
        samples[kind]?.samples
    }

    var isEmpty: Bool { samples.isEmpty }

    // MARK: - Loading

    static func load(sampleRate: Int = 48_000, bundle: Bundle = .main) -> ChartSfxSampleBank {
        var loaded: [ChartSfxKind: ChartSfxSample] = [:]
        for kind in ChartSfxKind.allCases {
            guard let url = url(for: kind, in: bundle),
                  let parsed = try? decodeWav(at: url)
            else { continue }
            let resolved = parsed.sampleRate == sampleRate
                ? parsed.samples
                : resampleLinear(parsed.samples, from: parsed.sampleRate, to: sampleRate)
            loaded[kind] = ChartSfxSample(sampleRate: sampleRate, samples: resolved)
        }
        return ChartSfxSampleBank(sampleRate: sampleRate, samples: loaded)
    }

    /// Finds a bundled asset in either of the two layouts the app has shipped with: flattened into
    /// the bundle root, or preserving the `maimai/chart` folder inside the resources directory.
    static func url(for kind: ChartSfxKind, in bundle: Bundle) -> URL? {
        let name = kind.assetName as NSString
        let base = name.deletingPathExtension
        let fileExtension = name.pathExtension
        if let url = bundle.url(forResource: base, withExtension: fileExtension) {
            return url
        }
        for directory in ["maimai/chart", "Resources/maimai/chart"] {
            if let url = bundle.url(forResource: base, withExtension: fileExtension, subdirectory: directory) {
                return url
            }
        }
        return nil
    }

    // MARK: - WAV parsing

    static func decodeWav(at url: URL) throws -> ChartSfxSample {
        let data: Data
        do {
            data = try Data(contentsOf: url, options: .mappedIfSafe)
        } catch {
            throw ChartSfxSampleError(message: "Unable to read \(url.lastPathComponent)")
        }
        return try decodeWav(data)
    }

    /// RIFF/WAVE parser matching the reference implementation: `fmt ` decides the channel count and
    /// rate, `data` carries interleaved 16-bit frames, every other chunk is skipped.
    static func decodeWav(_ data: Data) throws -> ChartSfxSample {
        guard data.count >= 12 else { throw ChartSfxSampleError(message: "truncated WAV header") }
        guard fourCC(data, 0) == "RIFF", fourCC(data, 8) == "WAVE" else {
            throw ChartSfxSampleError(message: "not a RIFF/WAVE file")
        }
        var sampleRate = 0
        var channels = 0
        var pcm: [Int16] = []
        var offset = 12
        while offset + 8 <= data.count {
            let identifier = fourCC(data, offset)
            let size = Int(littleEndianUInt32(data, offset + 4))
            offset += 8
            guard size >= 0, offset + size <= data.count else { break }
            switch identifier {
            case "fmt ":
                guard size >= 16 else { throw ChartSfxSampleError(message: "truncated fmt chunk") }
                let audioFormat = littleEndianUInt16(data, offset)
                channels = Int(littleEndianUInt16(data, offset + 2))
                sampleRate = Int(littleEndianUInt32(data, offset + 4))
                let bits = littleEndianUInt16(data, offset + 14)
                guard audioFormat == 1 else {
                    throw ChartSfxSampleError(message: "unsupported WAV format \(audioFormat)")
                }
                guard bits == 16 else {
                    throw ChartSfxSampleError(message: "unsupported WAV bits \(bits)")
                }
            case "data":
                let frameCount = size / 2
                var raw = [Int16](repeating: 0, count: frameCount)
                for index in 0..<frameCount {
                    raw[index] = Int16(bitPattern: littleEndianUInt16(data, offset + index * 2))
                }
                if channels <= 1 {
                    pcm = raw
                } else {
                    let frameTotal = frameCount / channels
                    var mixed = [Int16](repeating: 0, count: frameTotal)
                    for frame in 0..<frameTotal {
                        var sum = 0
                        for channel in 0..<channels {
                            sum += Int(raw[frame * channels + channel])
                        }
                        mixed[frame] = Int16(truncatingIfNeeded: sum / channels)
                    }
                    pcm = mixed
                }
            default:
                break
            }
            offset += size
            // RIFF pads odd-sized chunks to an even boundary; the shipped assets never need it, but
            // a stray `LIST` chunk of odd length would otherwise desynchronise the chunk walk.
            if size % 2 == 1 { offset += 1 }
        }
        guard sampleRate > 0, !pcm.isEmpty else {
            throw ChartSfxSampleError(message: "incomplete WAV")
        }
        return ChartSfxSample(sampleRate: sampleRate, samples: pcm)
    }

    /// Linear fallback for assets that are not already at the mixer rate.
    static func resampleLinear(_ samples: [Int16], from: Int, to: Int) -> [Int16] {
        guard from != to, from > 0, to > 0, !samples.isEmpty else { return samples }
        let outLength = max(1, Int(Int64(samples.count) * Int64(to) / Int64(from)))
        let step = Double(from) / Double(to)
        let last = samples.count - 1
        var output = [Int16](repeating: 0, count: outLength)
        for index in 0..<outLength {
            let position = Double(index) * step
            let left = min(last, max(0, Int(position)))
            let right = min(last, left + 1)
            let fraction = position - Double(left)
            let value = Double(samples[left]) * (1 - fraction) + Double(samples[right]) * fraction
            output[index] = Int16(truncatingIfNeeded: Int(value))
        }
        return output
    }

    // MARK: - Little-endian helpers

    private static func fourCC(_ data: Data, _ offset: Int) -> String {
        guard offset >= 0, offset + 4 <= data.count else { return "" }
        let bytes = (0..<4).map { data[offset + $0] }
        return String(bytes: bytes, encoding: .ascii) ?? ""
    }

    private static func littleEndianUInt16(_ data: Data, _ offset: Int) -> UInt16 {
        guard offset >= 0, offset + 2 <= data.count else { return 0 }
        return UInt16(data[offset]) | (UInt16(data[offset + 1]) << 8)
    }

    private static func littleEndianUInt32(_ data: Data, _ offset: Int) -> UInt32 {
        guard offset >= 0, offset + 4 <= data.count else { return 0 }
        return UInt32(data[offset])
            | (UInt32(data[offset + 1]) << 8)
            | (UInt32(data[offset + 2]) << 16)
            | (UInt32(data[offset + 3]) << 24)
    }
}
