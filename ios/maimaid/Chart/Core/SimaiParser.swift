import Foundation

// Faithful port of the Android reference implementation
// `android/app/src/main/java/org/rhythmeta/maimaid/core/chart/SimaiParser.kt`, itself a 1:1 port of
// the reference engine's `packages/maimai-chart-engine/src/core/parser/SimaiParser.ts`.
//
// Timing advances only on `,`; BPM, divisor and hi-speed markers take effect in place. The parser
// keeps the upstream quirks (1 ms delay-marker offset, one-measure lead-in, slide parts that restart
// from the slide head) because every renderer depends on them.

/// Parse failure raised wherever the Kotlin reference used `require(...)` or threw an
/// `IllegalArgumentException`.
///
/// The message is English-only and meant for logs and developer-facing diagnostics; `kind` exists so
/// the UI can show its own localized copy instead of parsing `message`.
nonisolated struct SimaiParseError: Error, Equatable, Sendable {
    nonisolated enum Kind: Sendable, Equatable {
        /// Empty input string.
        case emptyInput
        /// No `&`-prefixed metadata line at all.
        case missingMetadata
        /// The chart body never declares a BPM (unreachable in practice: the reference falls back to
        /// 120, but the Kotlin code keeps the guard, so the port does too).
        case missingBpm
        /// An explicit difficulty was requested that the maidata does not contain.
        case difficultyUnavailable
    }

    var kind: Kind
    var message: String

    init(kind: Kind, message: String) {
        self.kind = kind
        self.message = message
    }
}

nonisolated extension SimaiParseError: LocalizedError {
    var errorDescription: String? { message }
}

nonisolated extension SimaiParseError: CustomStringConvertible {
    var description: String { message }
}

/// Simai (`maidata.txt`) parser.
///
/// Mirrors the reference `SimaiParser` object: same regexes, same branch order, same metadata
/// handling. Notes come back in the reference construction order and carry `id == index` into
/// `Chart.notes`, which is what the renderer uses as its per-note metadata key.
nonisolated enum SimaiParser {
    private static let inoteMarkers = (1...7).map { "&inote_\($0)=" }

    /// Difficulty slots present in a maidata text, mirroring `getAvailableDifficulties`.
    static func availableDifficulties(_ text: String) -> Set<Int> {
        let lower = text.lowercased()
        var available: Set<Int> = []
        for (index, marker) in inoteMarkers.enumerated() where lower.contains(marker) {
            available.insert(index + 1)
        }
        if available.isEmpty {
            // A maidata without `&inote_N=` markers is treated as a single-difficulty sheet.
            let body = splitMaidataLines(text)
                .filter { !$0.drop(while: { $0.isWhitespace }).hasPrefix("&") }
                .joined()
            if body.contains(where: { $0 >= "1" && $0 <= "8" }) {
                available.insert(4)
            }
        }
        return available
    }

    /// Chart keys (`inote_N`) present in a maidata text, in ascending difficulty order.
    static func chartSlots(_ text: String) -> [Int] {
        availableDifficulties(text).sorted()
    }

    /// Parses a simai chart. Without an explicit difficulty the highest available slot is used;
    /// a single-difficulty chart falls back to slot 4.
    static func parse(_ text: String, difficulty: Int? = nil) throws -> Chart {
        try SimaiChartParser().parse(text, difficulty: difficulty)
    }

    /// Splits maidata into lines exactly like the reference: split on `\n`, then drop one trailing
    /// `\r` per line.
    ///
    /// Swift treats `\r\n` as a single `Character` (one grapheme cluster), so splitting the
    /// `Character` sequence on `"\n"` would not split a CRLF maidata at all — and the published
    /// chart assets are CRLF. The line endings are normalized first, which is what the Kotlin
    /// implementation's per-line `dropLast()` achieves on UTF-16 code units.
    fileprivate static func splitMaidataLines(_ text: String) -> [String] {
        let normalized = text
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
        return normalized.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
    }
}

// MARK: - Patterns

/// Compiled simai patterns.
///
/// The pattern strings are character-for-character the Kotlin `Regex` patterns, with Kotlin's
/// `RegexOption.IGNORE_CASE` expressed as `.caseInsensitive`. `NSRegularExpression` is ICU, the same
/// flavour as Kotlin's `java.util.regex`, which keeps matching behaviour faithful.
///
/// This deliberately avoids Swift's `Regex` type: besides not being `Sendable` (so it cannot live in
/// static storage under Swift 6), the Swift regex engine in the current toolchain mis-matches simple
/// patterns such as `^([-+]?\d*\.?\d+)$` against `"2"`, which would silently drop hi-speed markers.
nonisolated private struct SimaiPatterns {
    let inoteLine: NSRegularExpression
    let bpm: NSRegularExpression
    let divisor: NSRegularExpression
    let hiSpeed: NSRegularExpression
    let hold: NSRegularExpression
    let slide: NSRegularExpression
    let slidePathChar: NSRegularExpression
    let slidePathStart: NSRegularExpression
    let touch: NSRegularExpression
    let tap: NSRegularExpression
    let multiTimingBlock: NSRegularExpression
    let secondsWithDelay: NSRegularExpression
    let secondsOnlyBlock: NSRegularExpression
    let timingBlock: NSRegularExpression
    let inlineBpm: NSRegularExpression
    let slideBreak: NSRegularExpression
    let bracketBreak: NSRegularExpression
    let pathBlock: NSRegularExpression
    let breakEx: NSRegularExpression
    let segmentSeconds: NSRegularExpression
    let segmentSecondsOnly: NSRegularExpression
    let segmentStandard: NSRegularExpression

    init() throws {
        inoteLine = try Self.make("^&inote_(\\d)=(.*)$", caseInsensitive: true)
        bpm = try Self.make("^\\((\\d+(?:\\.\\d+)?)\\)")
        divisor = try Self.make("^\\{(\\d+(?:\\.\\d+)?)\\}")
        hiSpeed = try Self.make("^<HS\\*([-+]?\\d*\\.?\\d+)>", caseInsensitive: true)
        hold = try Self.make(
            "^(\\d+)[hbx]{1,3}\\[(?:([\\d.]+):([\\d.]+)|#([\\d.]+))][bx]*$",
            caseInsensitive: true
        )
        slide = try Self.make("^(\\d+)([bx?!@]*[-><^vpqszVw*]+.*)$", caseInsensitive: true)
        slidePathChar = try Self.make("[-><^vpqszVw]")
        slidePathStart = try Self.make("[-><^vpqszVw*]")
        touch = try Self.make(
            "^([ABCDE])(\\d*)([hbfx]*)(?:\\[(?:([\\d.]+):([\\d.]+)|#([\\d.]+))])?$",
            caseInsensitive: true
        )
        tap = try Self.make("^(\\d+)([bx$]*)$", caseInsensitive: true)
        multiTimingBlock = try Self.make("\\[[\\d.:#+]+]")
        secondsWithDelay = try Self.make("\\[([\\d.]+)##([\\d.]+)]")
        secondsOnlyBlock = try Self.make("\\[#([\\d.]+)]")
        timingBlock = try Self.make("\\[(?:([\\d.]+)#)?([\\d.]+):([\\d.]+)(?:##([\\d.]+))?]")
        inlineBpm = try Self.make("\\((\\d+(?:\\.\\d+)?)\\)")
        slideBreak = try Self.make("[-><^vpqszVw]\\d*b", caseInsensitive: true)
        bracketBreak = try Self.make("]b", caseInsensitive: true)
        pathBlock = try Self.make(
            "\\[(?:(?:[\\d.]+#)?[\\d.]+:[\\d.]+(?:##[\\d.]+)?|[\\d.]+##[\\d.]+)]",
            caseInsensitive: true
        )
        breakEx = try Self.make("[bx]", caseInsensitive: true)
        segmentSeconds = try Self.make("^([\\d.]+)##([\\d.]+)$")
        segmentSecondsOnly = try Self.make("^#([\\d.]+)$")
        segmentStandard = try Self.make("^(?:([\\d.]+)#)?([\\d.]+):([\\d.]+)(?:##([\\d.]+))?$")
    }

    private static func make(_ pattern: String, caseInsensitive: Bool = false) throws -> NSRegularExpression {
        try NSRegularExpression(pattern: pattern, options: caseInsensitive ? [.caseInsensitive] : [])
    }
}

// MARK: - Implementation

/// One parser instance per `parse` call.
///
/// The reference `SimaiParser` object is stateless; the Swift port keeps that property by compiling
/// the patterns once per `parse` call instead of caching them in static storage.
nonisolated private struct SimaiChartParser {
    private let patterns: SimaiPatterns

    init() throws {
        patterns = try SimaiPatterns()
    }

    // MARK: Entry point

    func parse(_ text: String, difficulty: Int?) throws -> Chart {
        guard !text.isEmpty else {
            throw SimaiParseError(kind: .emptyInput, message: "Invalid input: expected a non-empty string")
        }

        let lines = SimaiParser.splitMaidataLines(text)
        guard lines.contains(where: { $0.hasPrefix("&") }) else {
            throw SimaiParseError(
                kind: .missingMetadata,
                message: "Invalid simai format: expected & metadata lines (e.g. &title=, &inote_4=)"
            )
        }

        var metadata = Metadata()
        var inotes: [Int: String] = [:]

        var currentInote: Int?
        var currentContent: [String] = []

        func flushInote() {
            guard let slot = currentInote else { return }
            inotes[slot] = currentContent.joined(separator: "\n")
            currentInote = nil
            currentContent.removeAll()
        }

        for line in lines {
            let trimmed = trimmedWhitespace(line)
            if let match = firstMatch(patterns.inoteLine, in: trimmed) {
                flushInote()
                currentInote = Int(match.groups[1]) ?? 0
                let body = match.groups[2]
                if !body.isEmpty { currentContent.append(body) }
                continue
            }
            if currentInote != nil {
                // Inside an `&inote_N` block: a following non-inote `&` line starts the metadata tail,
                // any other line is chart body (kept verbatim, including its indentation).
                if trimmed.hasPrefix("&") && !trimmed.hasPrefix("&inote") {
                    flushInote()
                    metadata.apply(parseMetadataLine(trimmed))
                } else if !trimmed.hasPrefix("&") {
                    currentContent.append(line)
                }
                continue
            }
            if trimmed.hasPrefix("&") {
                metadata.apply(parseMetadataLine(trimmed))
            }
        }
        flushInote()

        let availableDiffs = inotes.keys.sorted(by: >)
        var selectedDifficulty = difficulty.flatMap { $0 > 0 ? $0 : nil }
        if selectedDifficulty == nil && !availableDiffs.isEmpty {
            selectedDifficulty = availableDiffs.first
        }

        let chartBody: String
        if let selectedDifficulty, let body = inotes[selectedDifficulty] {
            chartBody = body
        } else if availableDiffs.isEmpty {
            chartBody = lines
                .map(trimmedWhitespace)
                .filter { !$0.isEmpty && !$0.hasPrefix("&") }
                .joined()
            selectedDifficulty = 4
        } else {
            // Mirrors the reference message; `difficulty` is never nil on this path because a nil
            // request always falls back to the highest available slot above.
            throw SimaiParseError(
                kind: .difficultyUnavailable,
                message: "Difficulty \(difficulty.map(String.init) ?? "null") not found in chart. "
                    + "Available: \(availableDiffs.map(String.init).joined(separator: ", "))"
            )
        }

        let selectedDesigner = selectedDifficulty.flatMap { metadata.designers[$0] } ?? metadata.designer

        var metadataBpm = metadata.bpm
        if metadataBpm.isNaN, let match = firstMatch(patterns.inlineBpm, in: chartBody) {
            metadataBpm = Double(match.groups[1]) ?? .nan
        }
        // Upstream falls back to 120 so charts without BPM metadata still render.
        if metadataBpm.isNaN { metadataBpm = 120.0 }

        let parsed = parseNotes(chartBody, initialBpm: metadataBpm)
        let notes = parsed.notes

        guard !parsed.firstBpm.isNaN else {
            // The reference message is Chinese ("Simai 文件缺少 BPM 声明"); the contract for this port
            // keeps error text English-only and lets the UI localize through `SimaiParseError.kind`.
            throw SimaiParseError(kind: .missingBpm, message: "Simai chart is missing a BPM declaration")
        }

        var maxMeasure = 0
        var maxTiming = 0.0
        for note in notes {
            if note.measure > maxMeasure { maxMeasure = note.measure }
            var endTiming = note.timing
            switch note.kind {
            case .holdStart, .touchHoldStart:
                endTiming = note.timing + note.duration
            case .slide:
                var maxEndMs = 0.0
                let count = max(note.allDelayMs.count, note.allDurationMs.count)
                for index in 0..<count {
                    let delay = index < note.allDelayMs.count ? note.allDelayMs[index] : 0.0
                    let duration = index < note.allDurationMs.count ? note.allDurationMs[index] : 0.0
                    if delay + duration > maxEndMs { maxEndMs = delay + duration }
                }
                endTiming = note.timing + maxEndMs * note.bpm / 60_000.0
            default:
                break
            }
            if endTiming > maxTiming { maxTiming = endTiming }
        }
        maxMeasure = max(maxMeasure, Int(ceil(maxTiming / 4.0)))

        let leadInMs = 60_000.0 * 4.0 / parsed.firstBpm
        var shiftedNotes = notes.map { $0.withLeadIn(beatOffset: 4.0, msOffset: leadInMs) }
        // `ChartRenderer` keys its per-note metadata by `ChartNote.id`, so the id is the note index.
        for index in shiftedNotes.indices {
            shiftedNotes[index].id = index
        }

        let bpmEvents = [BpmEvent(timing: 0.0, bpm: parsed.firstBpm)]
            + parsed.bpmEvents.map { BpmEvent(timing: $0.timing + 4.0, bpm: $0.bpm) }
        // Kotlin's `sortedBy` is stable; Swift's `sort` is not, so tie-break on the insertion order.
        let divisorEvents = ([DivisorEvent(timing: 0.0, divisor: 4.0)]
            + parsed.divisorEvents.map { DivisorEvent(timing: $0.timing + 4.0, divisor: $0.divisor) })
            .enumerated()
            .sorted { lhs, rhs in
                lhs.element.timing == rhs.element.timing
                    ? lhs.offset < rhs.offset
                    : lhs.element.timing < rhs.element.timing
            }
            .map(\.element)

        return Chart(
            title: metadata.title,
            artist: metadata.artist,
            designer: selectedDesigner,
            bpm: parsed.firstBpm,
            level: metadata.level,
            designers: metadata.designers,
            difficulty: selectedDifficulty,
            availableDifficulties: Set(inotes.keys),
            measures: maxMeasure + 2,
            notes: shiftedNotes,
            bpmEvents: bpmEvents,
            divisorEvents: divisorEvents,
            firstMs: metadata.firstSec.map { $0 * 1000.0 },
            sheetDesigner: selectedDifficulty.flatMap { metadata.designers[$0] }
        )
    }

    // MARK: Metadata

    /// Mutable metadata accumulator. The reference repeats the same seven assignments in two branches
    /// of the line loop; `apply` keeps that behaviour in one place.
    private struct Metadata {
        var bpm = Double.nan
        var title = ""
        var artist = ""
        var designer = ""
        var level: [Int: String] = [:]
        var designers: [Int: String] = [:]
        var firstSec: Double?

        mutating func apply(_ line: MetadataLine) {
            bpm = line.bpm ?? bpm
            title = line.title ?? title
            artist = line.artist ?? artist
            designer = line.designer ?? designer
            if let parsedLevel = line.level { level.merge(parsedLevel) { _, new in new } }
            if let parsedDesigners = line.designers { designers.merge(parsedDesigners) { _, new in new } }
            firstSec = line.firstSec ?? firstSec
        }
    }

    private struct MetadataLine {
        var bpm: Double?
        var title: String?
        var artist: String?
        var designer: String?
        var level: [Int: String]?
        var designers: [Int: String]?
        var firstSec: Double?
    }

    private func parseMetadataLine(_ line: String) -> MetadataLine {
        guard let eqIndex = line.firstIndex(of: "=") else { return MetadataLine() }
        let keyStart = line.index(after: line.startIndex)
        // The reference slices `substring(1, eqIndex)`, which requires a `&` prefix; every caller
        // passes a `&`-prefixed line, so this guard only protects malformed input.
        guard eqIndex >= keyStart else { return MetadataLine() }
        let key = trimmedWhitespace(String(line[keyStart..<eqIndex]))
        let value = trimmedWhitespace(String(line[line.index(after: eqIndex)...]))

        if key == "title" { return MetadataLine(title: value) }
        if key == "artist" { return MetadataLine(artist: value) }
        if key == "des" { return MetadataLine(designer: value) }
        if key.hasPrefix("des_") {
            guard let slot = Int(key.dropFirst(4)), (1...7).contains(slot) else { return MetadataLine() }
            return MetadataLine(designers: [slot: value])
        }
        if key.hasPrefix("lv_") {
            guard let slot = Int(key.dropFirst(3)), (1...7).contains(slot) else { return MetadataLine() }
            return MetadataLine(level: [slot: value])
        }
        if key == "bpm" {
            guard let parsedBpm = Double(value) else { return MetadataLine() }
            return MetadataLine(bpm: parsedBpm)
        }
        if key == "first" {
            guard let parsedFirst = Double(value) else { return MetadataLine() }
            return MetadataLine(firstSec: parsedFirst)
        }
        return MetadataLine()
    }

    // MARK: Chart body

    private struct ParsedNotes {
        var notes: [ChartNote]
        var firstBpm: Double
        var bpmEvents: [BpmEvent]
        var divisorEvents: [DivisorEvent]
    }

    /// The reference treats only these four characters as body whitespace (not the full Unicode set).
    private func isBodyWhitespace(_ char: Character) -> Bool {
        char == " " || char == "\t" || char == "\r" || char == "\n"
    }

    private func parseNotes(_ chartBody: String, initialBpm: Double) -> ParsedNotes {
        var notes: [ChartNote] = []
        var bpmEvents: [BpmEvent] = []
        var divisorEvents: [DivisorEvent] = []

        let chars = Array(chartBody)
        var currentBpm = initialBpm
        var firstBpm: Double?
        var divisor = 4.0
        var currentBeat = 0.0
        var currentMs = 0.0
        var currentHiSpeed = 1.0
        var pos = 0

        while pos < chars.count {
            while pos < chars.count && isBodyWhitespace(chars[pos]) { pos += 1 }
            if pos >= chars.count { break }

            var noteContent = ""
            let startPos = pos

            while pos < chars.count && chars[pos] != "," {
                let char = chars[pos]
                if isBodyWhitespace(char) {
                    pos += 1
                    continue
                }
                if char == "(" {
                    let suffix = String(chars[pos...])
                    if let match = firstMatch(patterns.bpm, in: suffix) {
                        currentBpm = Double(match.groups[1]) ?? currentBpm
                        if firstBpm == nil { firstBpm = currentBpm }
                        bpmEvents.append(BpmEvent(timing: currentBeat, bpm: currentBpm))
                        pos += suffix.distance(from: suffix.startIndex, to: match.range.upperBound)
                        continue
                    }
                }
                if char == "{" {
                    let suffix = String(chars[pos...])
                    if let match = firstMatch(patterns.divisor, in: suffix) {
                        let newDivisor = Double(match.groups[1]) ?? divisor
                        if newDivisor != divisor {
                            divisor = newDivisor
                            divisorEvents.append(DivisorEvent(timing: currentBeat, divisor: divisor))
                        }
                        pos += suffix.distance(from: suffix.startIndex, to: match.range.upperBound)
                        continue
                    }
                }
                if char == "<" {
                    let suffix = String(chars[pos...])
                    if let match = firstMatch(patterns.hiSpeed, in: suffix) {
                        currentHiSpeed = Double(match.groups[1]) ?? currentHiSpeed
                        pos += suffix.distance(from: suffix.startIndex, to: match.range.upperBound)
                        continue
                    }
                }
                noteContent.append(char)
                pos += 1
            }

            // Guard kept from the reference; unreachable because the inner loop only stops at `,` or
            // the end of the body.
            if pos == startPos && pos < chars.count && chars[pos] != "," {
                pos += 1
                continue
            }

            let beatIncrement = 4.0 / divisor

            if !trimmedWhitespace(noteContent).isEmpty {
                let measure = Int(floor(currentBeat / 4.0))
                let positionInMeasure = Int(
                    floor(currentBeat.truncatingRemainder(dividingBy: 4.0) / 4.0 * 512.0)
                )

                var noteGroups: [String] = []
                var currentGroup = ""
                for char in noteContent {
                    switch char {
                    case "`":
                        if !trimmedWhitespace(currentGroup).isEmpty {
                            noteGroups.append(trimmedWhitespace(currentGroup))
                        }
                        currentGroup = "`"
                    case "/":
                        if !trimmedWhitespace(currentGroup).isEmpty {
                            noteGroups.append(trimmedWhitespace(currentGroup))
                        }
                        currentGroup = ""
                    default:
                        currentGroup.append(char)
                    }
                }
                if !trimmedWhitespace(currentGroup).isEmpty {
                    noteGroups.append(trimmedWhitespace(currentGroup))
                }

                let isSimultaneous = noteGroups.count > 1
                for group in noteGroups {
                    var noteStr = trimmedWhitespace(group)
                    if noteStr.isEmpty { continue }
                    var hasDelayMarker = false
                    if noteStr.hasPrefix("`") {
                        hasDelayMarker = true
                        noteStr = String(noteStr.dropFirst())
                    }
                    let parsedNotes = parseNoteString(
                        noteStr: noteStr,
                        timing: currentBeat,
                        timingMs: currentMs,
                        measure: measure,
                        positionInMeasure: positionInMeasure,
                        bpm: currentBpm,
                        isSimultaneous: isSimultaneous,
                        hasDelayMarker: hasDelayMarker
                    )
                    if currentHiSpeed != 1.0 {
                        notes.append(contentsOf: parsedNotes.map { $0.withHiSpeed(currentHiSpeed) })
                    } else {
                        notes.append(contentsOf: parsedNotes)
                    }
                }
            }

            currentBeat += beatIncrement
            currentMs += 60_000.0 * beatIncrement / currentBpm

            if pos < chars.count && chars[pos] == "," { pos += 1 }
        }

        return ParsedNotes(
            notes: notes,
            firstBpm: firstBpm ?? initialBpm,
            bpmEvents: bpmEvents.isEmpty ? [BpmEvent(timing: 0.0, bpm: initialBpm)] : bpmEvents,
            divisorEvents: divisorEvents
        )
    }

    // MARK: Single note

    private func parseHoldDuration(divisor: String?, beats: String?, seconds: String?, bpm: Double) -> Double {
        if let divisor, let beats, let divisorValue = Double(divisor), let beatsValue = Double(beats) {
            return (4.0 / divisorValue) * beatsValue
        }
        // The reference force-unwraps `seconds`; the regex guarantees one of the two forms, so an
        // unparsable value can only come from malformed input.
        guard let seconds, let secondsValue = Double(seconds) else { return 0 }
        return (secondsValue * bpm) / 60.0
    }

    private func parseNoteString(
        noteStr: String,
        timing: Double,
        timingMs: Double,
        measure: Int,
        positionInMeasure: Int,
        bpm: Double,
        isSimultaneous: Bool,
        hasDelayMarker: Bool
    ) -> [ChartNote] {
        var notes: [ChartNote] = []
        let delayOffset = hasDelayMarker ? 1.0 : 0.0

        // Holds are tried before slides: `1h[4:1]` must not fall into the slide branch.
        if let match = firstMatch(patterns.hold, in: noteStr) {
            let values = match.groups
            let position = Int(values[1]) ?? 0
            if (1...8).contains(position) {
                let holdDuration = parseHoldDuration(
                    divisor: nilIfEmpty(values[2]),
                    beats: nilIfEmpty(values[3]),
                    seconds: nilIfEmpty(values[4]),
                    bpm: bpm
                )
                let lower = noteStr.lowercased()
                let isBreakHold = lower.contains("b")
                let isEx = lower.contains("x")
                let durationMs = 60_000.0 * holdDuration / bpm

                var head = makeNote(
                    .holdStart, timing: timing, timingMs: timingMs + delayOffset, measure: measure,
                    positionInMeasure: positionInMeasure, bpm: bpm, hasDelayMarker: hasDelayMarker
                )
                head.position = position
                head.duration = holdDuration
                head.isSameTime = isSimultaneous
                head.isEx = isEx
                head.isBreakHold = isBreakHold
                notes.append(head)

                let endTiming = timing + holdDuration
                var tail = makeNote(
                    .holdEnd, timing: endTiming, timingMs: timingMs + durationMs + delayOffset,
                    measure: Int(floor(endTiming / 4.0)),
                    positionInMeasure: Int(
                        floor(endTiming.truncatingRemainder(dividingBy: 4.0) / 4.0 * 512.0)
                    ),
                    bpm: bpm, hasDelayMarker: false
                )
                tail.position = position
                tail.holdStartTiming = timing
                tail.isSameTime = isSimultaneous
                tail.isEx = isEx
                tail.isBreakHold = isBreakHold
                notes.append(tail)
                return notes
            }
        }

        if let match = firstMatch(patterns.slide, in: noteStr) {
            let values = match.groups
            if hasMatch(patterns.slidePathChar, in: values[2]) {
                let startPosition = Int(values[1]) ?? 0
                let slideNotation = values[2]
                let pathStartRange = firstMatchRange(patterns.slidePathStart, in: slideNotation)
                let startModifiers = pathStartRange.map { slideNotation[..<$0.lowerBound].lowercased() } ?? ""
                let headlessMarker: String?
                if startModifiers.contains("!") {
                    headlessMarker = "!"
                } else if startModifiers.contains("?") {
                    headlessMarker = "?"
                } else {
                    headlessMarker = nil
                }
                let isStartBreak = startModifiers.contains("b")
                let isHeadless = headlessMarker != nil
                let hasTapHead = !isHeadless && startModifiers.contains("@")
                let isEx = noteStr.lowercased().contains("x")

                // Each `*`-separated part is parsed independently and restarts from `startPosition`,
                // exactly like the reference (so `1-3*4-6` yields a second path from 1 to 6).
                let slideParts = slideNotation
                    .split(separator: "*", omittingEmptySubsequences: false)
                    .map(String.init)
                var allSlideSegments: [[SlideSegment]] = []
                var allDurations: [Double] = []
                var allDurationMs: [Double] = []
                var allDelayMs: [Double] = []
                var allCustomLengths: [Double?] = []
                var allSlideBreaks: [Bool] = []

                for part in slideParts {
                    let hasBreak = hasMatch(patterns.slideBreak, in: part)
                        || hasMatch(patterns.bracketBreak, in: part)
                    allSlideBreaks.append(hasBreak)

                    let hasMultipleTimings = matchCount(patterns.multiTimingBlock, in: part) > 1
                    if hasMultipleTimings {
                        let parsed = parseSlideSegmentsWithTiming(
                            startPosition: startPosition, pathNotation: part, defaultBpm: bpm
                        )
                        allSlideSegments.append(parsed.segments)
                        allDurations.append(parsed.totalDuration)
                        allDurationMs.append(parsed.totalDurationMs)
                        allDelayMs.append(60_000.0 / bpm)
                        allCustomLengths.append(nil)
                    } else {
                        var duration = 1.0
                        var customDelay: Double?
                        var customDurationSeconds: Double?
                        var customLengthSeconds: Double?

                        if let secondsMatch = firstMatch(patterns.secondsWithDelay, in: part) {
                            let block = secondsMatch.groups
                            customDelay = Double(block[1])
                            customLengthSeconds = Double(block[2])
                            duration = customLengthSeconds ?? duration
                        } else if let secondsOnlyMatch = firstMatch(patterns.secondsOnlyBlock, in: part) {
                            let block = secondsOnlyMatch.groups
                            customLengthSeconds = Double(block[1])
                            duration = customLengthSeconds ?? duration
                        } else if let timingMatch = firstMatch(patterns.timingBlock, in: part) {
                            let block = timingMatch.groups
                            if !block[1].isEmpty { customDelay = Double(block[1]) }
                            if let divisorValue = Double(block[2]), let beatsValue = Double(block[3]) {
                                duration = (4.0 / divisorValue) * beatsValue
                            }
                            if !block[4].isEmpty { customDurationSeconds = Double(block[4]) }
                        }

                        // Quirk preserved from upstream: a `[delay##seconds]` / `[#seconds]` block
                        // treats the delay as seconds, while a `[divisor:beats]` block treats it as a
                        // beat divisor.
                        let durationMsValue: Double
                        let delayMsValue: Double
                        if let customLengthSeconds {
                            durationMsValue = customLengthSeconds * 1000.0
                            delayMsValue = customDelay.map { $0 * 1000.0 } ?? (60_000.0 / bpm)
                        } else {
                            durationMsValue = customDurationSeconds.map { $0 * 1000.0 }
                                ?? (60_000.0 * duration / bpm)
                            delayMsValue = customDelay.map { 60_000.0 / $0 } ?? (60_000.0 / bpm)
                        }

                        allDurations.append(duration)
                        allDurationMs.append(durationMsValue)
                        allDelayMs.append(delayMsValue)
                        allCustomLengths.append(customDurationSeconds)

                        let withoutBlocks = replacing(patterns.pathBlock, in: part, with: "")
                        let pathOnly = replacing(patterns.breakEx, in: withoutBlocks, with: "")
                        allSlideSegments.append(
                            parseSlideSegments(startPosition: startPosition, pathNotation: pathOnly)
                        )
                    }
                }

                if (1...8).contains(startPosition) {
                    var slide = makeNote(
                        .slide, timing: timing, timingMs: timingMs + delayOffset, measure: measure,
                        positionInMeasure: positionInMeasure, bpm: bpm, hasDelayMarker: hasDelayMarker
                    )
                    slide.position = startPosition
                    slide.isHeadless = isHeadless
                    if let headlessMarker {
                        slide.headlessMode = headlessMarker == "!" ? "pop" : "fade"
                    }
                    slide.hasTapHead = hasTapHead
                    slide.isStartBreak = isStartBreak
                    slide.allSlideBreaks = allSlideBreaks
                    slide.isEx = isEx
                    // `slideParts` always has at least one element, so index 0 exists.
                    slide.duration = allDurations[0]
                    slide.durationMs = allDurationMs[0]
                    slide.delayMs = allDelayMs[0]
                    slide.slideSegments = allSlideSegments[0]
                    slide.allSlideSegments = allSlideSegments
                    slide.allDurations = allDurations
                    slide.allDurationMs = allDurationMs
                    slide.allDelayMs = allDelayMs
                    slide.allCustomLengths = allCustomLengths
                    slide.isSplitSlide = allSlideSegments.count > 1
                    slide.customLength = allCustomLengths[0]
                    notes.append(slide)
                    return notes
                }
            }
        }

        // `1234` is a same-time tap chord, tried before the touch and tap branches.
        if isMultiDigitNote(noteStr) {
            let digits = Array(noteStr)
            if digits.allSatisfy({ $0 >= "0" && $0 <= "9" }) && digits.allSatisfy({ $0 >= "1" && $0 <= "8" }) {
                for digit in digits {
                    var tap = makeNote(
                        .tap, timing: timing, timingMs: timingMs + delayOffset, measure: measure,
                        positionInMeasure: positionInMeasure, bpm: bpm, hasDelayMarker: hasDelayMarker
                    )
                    tap.position = digit.wholeNumberValue ?? 0
                    tap.isSameTime = true
                    notes.append(tap)
                }
                return notes
            }
        }

        if let match = firstMatch(patterns.touch, in: noteStr) {
            let values = match.groups
            let region = values[1].uppercased()
            let sensorNum = values[2].isEmpty ? nil : Int(values[2])
            let modifiers = values[3].lowercased()

            let isValidTouch: Bool
            switch region {
            case "C":
                isValidTouch = sensorNum == nil || sensorNum == 1 || sensorNum == 2
            case "A", "B", "D", "E":
                isValidTouch = sensorNum.map { (1...8).contains($0) } ?? false
            default:
                isValidTouch = false
            }

            if isValidTouch {
                let touchPosition = sensorNum.map { "\(region)\($0)" } ?? region
                let isHold = modifiers.contains("h")
                let hasFirework = modifiers.contains("f")
                if isHold && (!values[4].isEmpty || !values[6].isEmpty) {
                    let holdDuration = parseHoldDuration(
                        divisor: nilIfEmpty(values[4]),
                        beats: nilIfEmpty(values[5]),
                        seconds: nilIfEmpty(values[6]),
                        bpm: bpm
                    )
                    let durationMs = 60_000.0 * holdDuration / bpm

                    var head = makeNote(
                        .touchHoldStart, timing: timing, timingMs: timingMs + delayOffset,
                        measure: measure, positionInMeasure: positionInMeasure, bpm: bpm,
                        hasDelayMarker: hasDelayMarker
                    )
                    head.touchPosition = touchPosition
                    head.duration = holdDuration
                    head.durationMs = durationMs
                    head.hasFirework = hasFirework
                    notes.append(head)

                    let endTiming = timing + holdDuration
                    var tail = makeNote(
                        .touchHoldEnd, timing: endTiming, timingMs: timingMs + durationMs + delayOffset,
                        measure: Int(floor(endTiming / 4.0)),
                        positionInMeasure: Int(
                            floor(endTiming.truncatingRemainder(dividingBy: 4.0) / 4.0 * 512.0)
                        ),
                        bpm: bpm, hasDelayMarker: false
                    )
                    tail.touchPosition = touchPosition
                    tail.holdStartTiming = timing
                    tail.hasFirework = hasFirework
                    notes.append(tail)
                } else {
                    var touch = makeNote(
                        .touch, timing: timing, timingMs: timingMs + delayOffset, measure: measure,
                        positionInMeasure: positionInMeasure, bpm: bpm, hasDelayMarker: hasDelayMarker
                    )
                    touch.touchPosition = touchPosition
                    touch.hasFirework = hasFirework
                    notes.append(touch)
                }
                return notes
            }
        }

        if let match = firstMatch(patterns.tap, in: noteStr) {
            let values = match.groups
            let position = Int(values[1]) ?? 0
            let modifiers = values[2].lowercased()
            let starCount = modifiers.filter { $0 == "$" }.count
            let isBreak = modifiers.contains("b")
            let isEx = modifiers.contains("x")
            if (1...8).contains(position) {
                var tap = makeNote(
                    .tap, timing: timing, timingMs: timingMs + delayOffset, measure: measure,
                    positionInMeasure: positionInMeasure, bpm: bpm, hasDelayMarker: hasDelayMarker
                )
                tap.position = position
                tap.isBreak = isBreak
                // Breaks are never "same time": the reference forces `isSameTime` off for breaks.
                tap.isSameTime = isSimultaneous && !isBreak
                tap.isStar = starCount > 0
                tap.isSpinningStar = starCount >= 2
                tap.isEx = isEx
                notes.append(tap)
            }
        }

        return notes
    }

    private func isMultiDigitNote(_ noteStr: String) -> Bool {
        noteStr.count >= 2 && noteStr.allSatisfy { $0 >= "0" && $0 <= "9" }
    }

    // MARK: Slide paths

    private struct SlidePathParseResult {
        var segments: [SlideSegment]
        var totalDuration: Double
        var totalDurationMs: Double
    }

    private func parseSlideSegmentsWithTiming(
        startPosition: Int,
        pathNotation: String,
        defaultBpm: Double
    ) -> SlidePathParseResult {
        let chars = Array(pathNotation)
        var segments: [SlideSegment] = []
        var segmentDurations: [Double] = []
        var segmentDurationMs: [Double] = []
        var currentPos = startPosition
        var index = 0

        while index < chars.count {
            let char = chars[index]
            let pathType: SlidePathType
            if index + 1 < chars.count && chars[index + 1] == char && (char == "p" || char == "q") {
                pathType = char == "p" ? .spiralClockwiseDouble : .spiralCounterClockwiseDouble
                index += 2
            } else if "-><^vpqszVw".contains(char) {
                guard let symbolType = SlidePathType(symbol: char) else {
                    index += 1
                    continue
                }
                pathType = symbolType
                index += 1
            } else {
                index += 1
                continue
            }

            if pathType == .via {
                var numStr = ""
                while index < chars.count && chars[index].isWholeNumber {
                    numStr.append(chars[index])
                    index += 1
                }
                if index < chars.count && chars[index] == "[" {
                    if let bracketEnd = chars[(index + 1)...].firstIndex(of: "]") {
                        index = bracketEnd + 1
                    }
                }
                if numStr.count >= 2, let midDigit = numStr.first,
                   let endPos = Int(String(numStr.dropFirst())) {
                    let midPos = midDigit.wholeNumberValue ?? 0
                    let start = currentPos
                    let leftCorner = ((start + 5) % 8) + 1
                    let rightCorner = ((start + 1) % 8) + 1
                    let d = (((endPos - start) % 8) + 8) % 8
                    let isStandardV = (midPos == leftCorner && (1...4).contains(d))
                        || (midPos == rightCorner && (1...4).contains((8 - d) % 8))
                    if isStandardV {
                        segments.append(SlideSegment(type: .via, startPos: start, endPos: endPos, midPos: midPos))
                        segmentDurations.append(1.0)
                        segmentDurationMs.append(60_000.0 / defaultBpm)
                    } else {
                        segments.append(SlideSegment(type: .line, startPos: start, endPos: midPos))
                        segments.append(SlideSegment(type: .line, startPos: midPos, endPos: endPos))
                        segmentDurations.append(0.5)
                        segmentDurations.append(0.5)
                        segmentDurationMs.append(30_000.0 / defaultBpm)
                        segmentDurationMs.append(30_000.0 / defaultBpm)
                    }
                    currentPos = endPos
                }
            } else {
                var numStr = ""
                while index < chars.count && chars[index].isWholeNumber {
                    numStr.append(chars[index])
                    index += 1
                }
                while index < chars.count && isBreakOrExMarker(chars[index]) {
                    index += 1
                }

                var segDuration = 1.0
                var segDurationMs = 60_000.0 / defaultBpm

                if index < chars.count && chars[index] == "[" {
                    if let bracketEnd = chars[(index + 1)...].firstIndex(of: "]") {
                        let timingStr = String(chars[(index + 1)..<bracketEnd])
                        if let secondsMatch = firstMatch(patterns.segmentSeconds, in: timingStr) {
                            let block = secondsMatch.groups
                            if let value = Double(block[2]) {
                                segDuration = value
                                segDurationMs = value * 1000.0
                            }
                        } else if let secondsOnlyMatch = firstMatch(patterns.segmentSecondsOnly, in: timingStr) {
                            let block = secondsOnlyMatch.groups
                            if let value = Double(block[1]) {
                                segDurationMs = value * 1000.0
                                segDuration = segDurationMs * defaultBpm / 60_000.0
                            }
                        } else if let standardMatch = firstMatch(patterns.segmentStandard, in: timingStr) {
                            let block = standardMatch.groups
                            if let divisorValue = Double(block[2]), let beatsValue = Double(block[3]) {
                                segDuration = (4.0 / divisorValue) * beatsValue
                            }
                            if !block[4].isEmpty {
                                if let seconds = Double(block[4]) { segDurationMs = seconds * 1000.0 }
                            } else {
                                segDurationMs = 60_000.0 * segDuration / defaultBpm
                            }
                        }
                        index = bracketEnd + 1
                        while index < chars.count && isBreakOrExMarker(chars[index]) {
                            index += 1
                        }
                    }
                }

                if !numStr.isEmpty, let endPos = Int(numStr) {
                    segments.append(SlideSegment(type: pathType, startPos: currentPos, endPos: endPos))
                    segmentDurations.append(segDuration)
                    segmentDurationMs.append(segDurationMs)
                    currentPos = endPos
                }
            }
        }

        return SlidePathParseResult(
            segments: segments,
            totalDuration: segmentDurations.reduce(0, +),
            totalDurationMs: segmentDurationMs.reduce(0, +)
        )
    }

    private func parseSlideSegments(startPosition: Int, pathNotation: String) -> [SlideSegment] {
        // The reference parses single-timing paths at a synthetic 120 BPM; only the segment list is
        // consumed, the tempo has no effect on it.
        parseSlideSegmentsWithTiming(startPosition: startPosition, pathNotation: pathNotation, defaultBpm: 120.0)
            .segments
    }

    private func isBreakOrExMarker(_ char: Character) -> Bool {
        char == "b" || char == "x" || char == "B" || char == "X"
    }

    // MARK: Small helpers

    private func makeNote(
        _ kind: ChartNoteKind,
        timing: Double,
        timingMs: Double,
        measure: Int,
        positionInMeasure: Int,
        bpm: Double,
        hasDelayMarker: Bool
    ) -> ChartNote {
        // Every note the parser builds uses scale 1.0; the reference passes it explicitly.
        ChartNote(
            kind: kind,
            timing: timing,
            timingMs: timingMs,
            measure: measure,
            positionInMeasure: positionInMeasure,
            scale: 1.0,
            bpm: bpm,
            hasDelayMarker: hasDelayMarker
        )
    }

    private func trimmedWhitespace(_ text: String) -> String {
        text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func nilIfEmpty(_ text: String) -> String? {
        text.isEmpty ? nil : text
    }

    // MARK: Regex plumbing
    //
    // Kotlin uses `Regex.find(...)` (search with the pattern's own anchors) plus `containsMatchIn`,
    // `findAll().count()` and `replace(regex, "")`. The helpers below map those one-to-one onto
    // `NSRegularExpression` so the pattern strings themselves stay identical to the reference.

    private func firstMatch(
        _ regex: NSRegularExpression,
        in text: String
    ) -> (groups: [String], range: Range<String.Index>)? {
        let fullRange = NSRange(text.startIndex..<text.endIndex, in: text)
        guard let match = regex.firstMatch(in: text, options: [], range: fullRange),
              let range = Range(match.range, in: text) else { return nil }
        // Element 0 is the whole match and a group that did not participate is the empty string,
        // which is exactly Kotlin's `MatchResult.groupValues` indexing.
        var groups: [String] = []
        groups.reserveCapacity(match.numberOfRanges)
        for index in 0..<match.numberOfRanges {
            if let groupRange = Range(match.range(at: index), in: text) {
                groups.append(String(text[groupRange]))
            } else {
                groups.append("")
            }
        }
        return (groups, range)
    }

    private func firstMatchRange(_ regex: NSRegularExpression, in text: String) -> Range<String.Index>? {
        let fullRange = NSRange(text.startIndex..<text.endIndex, in: text)
        guard let match = regex.firstMatch(in: text, options: [], range: fullRange) else { return nil }
        return Range(match.range, in: text)
    }

    private func hasMatch(_ regex: NSRegularExpression, in text: String) -> Bool {
        let fullRange = NSRange(text.startIndex..<text.endIndex, in: text)
        return regex.firstMatch(in: text, options: [], range: fullRange) != nil
    }

    private func matchCount(_ regex: NSRegularExpression, in text: String) -> Int {
        let fullRange = NSRange(text.startIndex..<text.endIndex, in: text)
        return regex.numberOfMatches(in: text, options: [], range: fullRange)
    }

    private func replacing(_ regex: NSRegularExpression, in text: String, with template: String) -> String {
        let fullRange = NSRange(text.startIndex..<text.endIndex, in: text)
        return regex.stringByReplacingMatches(in: text, options: [], range: fullRange, withTemplate: template)
    }
}
