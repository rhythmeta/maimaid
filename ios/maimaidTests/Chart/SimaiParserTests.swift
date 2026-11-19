import Foundation
import Testing
@testable import maimaid

/// Locks the Swift port to the reference parser output, mirroring Android's `SimaiParityTest`.
///
/// The fixtures are byte-for-byte copies of
/// `android/app/src/test/resources/chart/parity-maidata.txt` and `parity-expected.txt`, which were
/// produced by running the reference engine's `parseSimaiChart` on the same maidata. Any behavioural
/// drift in note construction, timings, slide paths or flags fails here.
@MainActor
struct SimaiParserTests {
    /// Fixtures live in `ios/maimaidTests/Fixtures/Chart/`.
    ///
    /// This file is `ios/maimaidTests/Chart/SimaiParserTests.swift`, so `#filePath`'s directory is
    /// `ios/maimaidTests/Chart/`; one level up (`../Fixtures/Chart/`) resolves to
    /// `ios/maimaidTests/Fixtures/Chart/`. Verified against the checked-in files (a `../../` prefix
    /// would land on the non-existent `ios/Fixtures/Chart/`).
    private static let fixtureDirectory = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .appending(path: "../Fixtures/Chart")

    private func fixture(_ name: String) throws -> String {
        let data = try Data(contentsOf: Self.fixtureDirectory.appending(path: name))
        return String(decoding: data, as: UTF8.self)
    }

    @Test("Parses identically to the reference engine")
    func parsesIdenticallyToTheReferenceEngine() throws {
        let maidata = try fixture("parity-maidata.txt")
        let expected = try fixture("parity-expected.txt")
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .split(separator: "\n", omittingEmptySubsequences: false)
            .map(String.init)
        let chart = try SimaiParser.parse(maidata, difficulty: 5)

        let header = [
            jsNumber(chart.bpm),
            String(chart.difficulty ?? 0),
            String(chart.measures),
            String(chart.notes.count),
        ].joined(separator: ",")
        #expect(expected.first == header)

        let actual = chart.notes.map(describe)
        #expect(expected.count - 1 == actual.count)
        for (index, line) in expected.dropFirst().enumerated() {
            guard index < actual.count else { break }
            #expect(line == actual[index], "note \(index)")
        }
    }

    @Test("Reports the difficulties present in a maidata")
    func reportsAvailableDifficulties() throws {
        let maidata = try fixture("parity-maidata.txt")
        #expect(SimaiParser.availableDifficulties(maidata) == [5])
        #expect(SimaiParser.chartSlots(maidata) == [5])
    }

    @Test("Parses CRLF maidata like LF maidata")
    func parsesCrlfMaidata() throws {
        // The published chart assets use CRLF. Swift treats "\r\n" as a single grapheme cluster, so
        // splitting the Character sequence on "\n" would not split the file at all; this locks the
        // line-ending normalization.
        let maidata = try fixture("parity-maidata.txt")
        let crlf = maidata.replacingOccurrences(of: "\n", with: "\r\n")
        let chart = try SimaiParser.parse(crlf, difficulty: 5)
        let reference = try SimaiParser.parse(maidata, difficulty: 5)
        #expect(chart.notes.count == reference.notes.count)
        #expect(chart.notes.count > 0)
        #expect(chart.bpm == reference.bpm)
        #expect(chart.title == reference.title)
        #expect(SimaiParser.chartSlots(crlf) == SimaiParser.chartSlots(maidata))
    }

    @Test("Parses an SD chart without difficulty metadata")
    func parsesSdChartWithoutDifficultyMetadata() throws {
        let chart = try SimaiParser.parse("&title=Plain\n&bpm=120\n(120){4}1,2,3,E", difficulty: nil)
        #expect(chart.difficulty == 4)
        #expect(chart.notes.count == 3)
        #expect(abs(chart.leadInMs - 2000.0) < 0.0001)
        #expect(abs((chart.notes.first?.timing ?? 0) - 4.0) < 0.0001)
    }

    // MARK: - Reference `describe(note)` port

    /// One tab-separated debug line per note: same field order, same flags order and same
    /// six-decimal formatting as the Kotlin helper that produced `parity-expected.txt`.
    private func describe(_ note: ChartNote) -> String {
        let type: String
        switch note.kind {
        case .tap:
            if note.isBreak {
                type = "break"
            } else if note.isSameTime {
                type = "simultaneous"
            } else {
                type = "tap"
            }
        case .holdStart:
            type = note.isSameTime ? "hold-start-simultaneous" : "hold-start"
        case .holdEnd:
            type = note.isSameTime ? "hold-end-simultaneous" : "hold-end"
        case .slide:
            type = "slide"
        case .touch:
            type = "touch"
        case .touchHoldStart:
            type = "touch-hold-start"
        case .touchHoldEnd:
            type = "touch-hold-end"
        }

        let position: String = switch note.kind {
        case .touch, .touchHoldStart, .touchHoldEnd: note.touchPosition
        default: String(note.position)
        }

        let duration: Double = switch note.kind {
        case .holdStart, .slide, .touchHoldStart: note.duration
        default: 0.0
        }

        let durationMs: Double = switch note.kind {
        case .slide, .touchHoldStart: note.durationMs
        default: 0.0
        }

        let delayMs = note.kind == .slide ? note.delayMs : 0.0

        let paths = note.kind == .slide
            ? note.allSlideSegments
                .map { path in
                    path.map { segment in
                        "\(segment.type.notation)\(segment.startPos)"
                            + "\(segment.midPos.map(String.init) ?? "")\(segment.endPos)"
                    }
                    .joined(separator: "+")
                }
                .joined(separator: "|")
            : ""

        // Mirrors the Kotlin type guards: only holds report `isBreakHold`, only touch and touch-hold
        // heads report `hasFirework` (a touch-hold tail's own flag is ignored by the reference).
        let isBreakHold = switch note.kind {
        case .holdStart, .holdEnd: note.isBreakHold
        default: false
        }
        let hasFirework = switch note.kind {
        case .touch, .touchHoldStart: note.hasFirework
        default: false
        }

        let flags = [
            type,
            String(isEx(note)),
            String(note.kind == .tap && note.isStar),
            String(note.kind == .tap && note.isSpinningStar),
            String(note.kind == .slide && note.isHeadless),
            String(note.kind == .slide && note.hasTapHead),
            String(note.kind == .slide && note.isStartBreak),
            String(isBreakHold),
            String(note.kind == .slide && note.isSplitSlide),
            String(note.hasDelayMarker),
            String(hasFirework),
            note.hiSpeed?.description ?? "",
        ].joined(separator: ",")

        return [
            type,
            position,
            fixed6(note.timing),
            fixed6(note.timingMs),
            fixed6(duration),
            fixed6(durationMs),
            fixed6(delayMs),
            paths,
            flags,
        ].joined(separator: ";")
    }

    private func isEx(_ note: ChartNote) -> Bool {
        switch note.kind {
        case .tap, .holdStart, .holdEnd, .slide: note.isEx
        default: false
        }
    }

    /// JavaScript-style number printing: integer-valued numbers lose the decimal part.
    private func jsNumber(_ value: Double) -> String {
        if value == value.rounded(.towardZero) && value.isFinite && abs(value) < 9.0e18 {
            return String(Int64(value))
        }
        return value.description
    }

    /// `%.6f` equivalent without C-style formatting: fixed six decimals, locale-independent, and
    /// never `-0.000000` (the reference normalizes that).
    private func fixed6(_ value: Double) -> String {
        let text = value.formatted(
            .number
                .precision(.fractionLength(6))
                .grouping(.never)
                .locale(Locale(identifier: "en_US_POSIX"))
        )
        return text == "-0.000000" ? "0.000000" : text
    }
}
