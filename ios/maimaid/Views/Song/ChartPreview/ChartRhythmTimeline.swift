import SwiftUI

// MARK: - Rhythm data

/// Note family of a rhythm step, coloured like the density legend.
nonisolated enum ChartRhythmStepKind: Int, Sendable, CaseIterable {
    case tap = 1
    case touch = 2
    case hold = 3
    case slide = 4
    case breakNote = 5

    /// Strongest family wins the colour when several notes land on the same millisecond, mirroring
    /// the Android `RhythmStepKind.priority`.
    var priority: Int { rawValue }

    var color: ChartColor {
        switch self {
        case .tap: ChartTimelineColors.tap
        case .touch: ChartTimelineColors.touch
        case .hold: ChartTimelineColors.hold
        case .slide: ChartTimelineColors.slide
        case .breakNote: ChartTimelineColors.breakNote
        }
    }
}

/// One note time on the rhythm lane together with the measure divisor in effect there.
nonisolated struct ChartRhythmStep: Equatable, Sendable {
    /// Side of the note dot the duration line is drawn on.
    nonisolated enum DurationSide: Equatable, Sendable {
        case none
        case above
        case below
    }

    var timeMs: Double
    /// The number simai writes in braces (`{4}`, `{12}`, …) for the measure this step sits in.
    var divisor: Double
    var kind: ChartRhythmStepKind
    /// Notes stacked on this time; more than one is a chord and gets the yellow ring.
    var noteCount: Int
    /// Total length in ms (hold time, or slide launch + travel); 0 for instant notes.
    var durationMs: Double
    /// Slide launch (delay) length in ms before the travel starts; 0 for holds.
    var launchMs: Double
    /// Holds trail above the dot, slides below it.
    var lineSide: DurationSide
}

/// Length of a note and how its duration line is drawn, the Kotlin `RhythmLine`.
nonisolated struct ChartRhythmLine: Equatable, Sendable {
    var durationMs: Double
    var launchMs: Double
    var side: ChartRhythmStep.DurationSide
}

/// A BPM change point already resolved to chart time.
nonisolated struct ChartRhythmBpmMark: Equatable, Sendable {
    var timeMs: Double
    var bpm: Double
}

/// Precomputed rhythm lane content.
///
/// Every note head becomes one step carrying the divisor of the measure it sits in. Notes sharing a
/// millisecond collapse into a single step so chords read as one rhythm event: the strongest note
/// family wins the colour, the merged note count marks the step as a chord, and its duration feeds
/// the hold / slide line.
nonisolated struct ChartRhythmData: Equatable, Sendable {
    var steps: [ChartRhythmStep]
    /// One tick per measure line, already resolved to chart time.
    var measureTicks: [Double]
    private var bpmMarks: [ChartRhythmBpmMark]

    /// Tempo used before the first BPM event, matching `DEFAULT_BPM` in the Android lane.
    private static let defaultBpm = 120.0

    static func make(chart: Chart, timeline: TimingTimeline) -> ChartRhythmData {
        // Kotlin keys the merge map by `(timingMs * 1000).roundToLong()`, i.e. microseconds, so two
        // notes only merge when they really share a head time.
        var byTime: [Int64: ChartRhythmStep] = [:]
        for note in chart.notes {
            guard let kind = note.rhythmKind else { continue }
            let key = Int64((note.timingMs * 1000.0).rounded())
            let line = note.rhythmLine
            guard let existing = byTime[key] else {
                byTime[key] = ChartRhythmStep(
                    timeMs: note.timingMs,
                    divisor: timeline.divisorAtBeat(note.timing),
                    kind: kind,
                    noteCount: 1,
                    durationMs: line.durationMs,
                    launchMs: line.launchMs,
                    lineSide: line.side
                )
                continue
            }
            if kind.priority > existing.kind.priority {
                // The stronger family takes over the step's colour and duration, and the divisor of
                // the note that won, exactly like the Kotlin branch.
                byTime[key] = ChartRhythmStep(
                    timeMs: existing.timeMs,
                    divisor: timeline.divisorAtBeat(note.timing),
                    kind: kind,
                    noteCount: existing.noteCount + 1,
                    durationMs: line.durationMs,
                    launchMs: line.launchMs,
                    lineSide: line.side
                )
            } else {
                var merged = existing
                merged.noteCount += 1
                byTime[key] = merged
            }
        }
        // Kotlin `(0..chart.measures)`; a chart without measures yields no ticks at all.
        let measureTicks = chart.measures >= 0
            ? (0...chart.measures).map { timeline.msFromBeat(Double($0) * 4) }
            : []
        return ChartRhythmData(
            steps: byTime.values.sorted { $0.timeMs < $1.timeMs },
            measureTicks: measureTicks,
            bpmMarks: chart.bpmEvents
                .map { ChartRhythmBpmMark(timeMs: timeline.msFromBeat($0.timing), bpm: $0.bpm) }
                .sorted { $0.timeMs < $1.timeMs }
        )
    }

    /// BPM in effect at `timeMs`, following the chart's own BPM events.
    func bpmAt(_ timeMs: Double) -> Double {
        var low = 0
        var high = bpmMarks.count - 1
        var bpm = bpmMarks.first?.bpm ?? Self.defaultBpm
        while low <= high {
            let mid = (low + high) / 2
            if bpmMarks[mid].timeMs <= timeMs {
                bpm = bpmMarks[mid].bpm
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return bpm
    }
}

// MARK: - Note mapping

nonisolated extension ChartNote {
    /// Hold tails and touch-hold tails are not rhythm steps; heads and taps are.
    var rhythmKind: ChartRhythmStepKind? {
        switch kind {
        case .tap:
            return isBreak ? .breakNote : .tap
        case .holdStart:
            return .hold
        case .slide:
            // Headless slides carry no head note, so they add no rhythm step.
            return isHeadless ? nil : .slide
        case .touch, .touchHoldStart:
            return .touch
        case .holdEnd, .touchHoldEnd:
            return nil
        }
    }

    /// Length of the note in ms and the side its duration line belongs on.
    ///
    /// Holds (including touch holds) trail above the dot, slides below it. A slide reports its
    /// launch delay separately so the lane can dash the launch beat and keep the travel solid; its
    /// length covers the whole path (the largest `delay + duration` over its segments).
    var rhythmLine: ChartRhythmLine {
        switch kind {
        case .holdStart:
            return ChartRhythmLine(durationMs: max(holdLengthMs, 0), launchMs: 0, side: .above)
        case .touchHoldStart:
            return ChartRhythmLine(durationMs: max(durationMs, 0), launchMs: 0, side: .above)
        case .slide:
            var endMs = 0.0
            var launchMs = 0.0
            let pathCount = max(allDelayMs.count, allDurationMs.count, 1)
            for index in 0..<pathCount {
                let delay = max(index < allDelayMs.count ? allDelayMs[index] : delayMs, 0)
                let length = max(index < allDurationMs.count ? allDurationMs[index] : durationMs, 0)
                if delay + length > endMs {
                    endMs = delay + length
                    launchMs = delay
                }
            }
            return ChartRhythmLine(durationMs: endMs, launchMs: min(launchMs, endMs), side: .below)
        default:
            return ChartRhythmLine(durationMs: 0, launchMs: 0, side: .none)
        }
    }
}

// MARK: - Rhythm lane

/// Rhythm lane under the density timeline: the upcoming notes, each carrying the measure divisor it
/// belongs to inside its dot, so the subdivision pattern of the chart can be read at a glance.
///
/// The lane is a zoomed look-ahead window rather than a whole-song overview (see
/// `ChartRhythmPainter`), and it renders straight from `positionMs`: the Android widget reads the
/// caller's clock inside its canvas instead of driving an animation of its own, so no
/// `TimelineView` is involved here either.
struct ChartRhythmTimeline: View {
    /// Lane height; the Android widget is `42.dp` tall.
    private static let height: CGFloat = 42

    let data: ChartRhythmData
    let positionMs: Double
    /// Multiplier on the lane's own points-per-ms scale; widening the window instead of crawling
    /// keeps the flow speed in sync with the chart's hi-speed settings.
    let speedScale: Double
    let isPlaying: Bool

    /// Owned here so the BPM flash keeps its start instant between frames, like the Android
    /// `remember(data) { BpmChangeFlash() }`.
    @State private var bpmFlash = ChartRhythmBpmFlash()
    @ScaledMetric(relativeTo: .caption2) private var numberFontSize: CGFloat = 8
    @ScaledMetric(relativeTo: .caption2) private var bpmCaptionFontSize: CGFloat = 8
    @ScaledMetric(relativeTo: .footnote) private var bpmValueFontSize: CGFloat = 13

    var body: some View {
        if data.steps.isEmpty {
            EmptyView()
        } else {
            let textSizes = ChartRhythmPainter.TextSizes(
                number: numberFontSize,
                bpmCaption: bpmCaptionFontSize,
                bpmValue: bpmValueFontSize
            )
            let flashColor = bpmFlash.color(forBpm: data.bpmAt(positionMs), isPlaying: isPlaying, base: .primary)
            Canvas { context, size in
                ChartRhythmPainter.draw(
                    data: data,
                    positionMs: positionMs,
                    speedScale: speedScale,
                    flashColor: flashColor,
                    textSizes: textSizes,
                    in: context,
                    size: size
                )
            }
            .frame(height: Self.height)
            .onChange(of: data) { _, _ in
                bpmFlash.reset()
            }
            .accessibilityElement()
            .accessibilityLabel(Text(String(localized: "chart.preview.rhythm.label")))
        }
    }
}

// MARK: - BPM flash

/// Port of the Android `BpmChangeFlash`.
///
/// While the chart plays, a BPM rise flashes the readout red and a drop flashes it green, then both
/// fade back to the readout's own colour within half a second, so a tempo change is noticeable
/// without a persistent banner.
nonisolated final class ChartRhythmBpmFlash {
    private let fadeDurationMs = 500.0
    private let instantMs = 50.0

    private var lastBpm: Double?
    private var changedAt: ContinuousClock.Instant?
    /// `0` = no flash, `1` = tempo rise, `-1` = tempo drop.
    private var direction = 0

    /// Forgets the previous tempo, e.g. after switching to another chart.
    func reset() {
        lastBpm = nil
        changedAt = nil
        direction = 0
    }

    /// Colour to draw `bpm` with, or nil when the readout should keep `base` unchanged.
    func color(forBpm bpm: Double, isPlaying: Bool, base: Color) -> Color? {
        guard isPlaying else {
            direction = 0
            lastBpm = bpm
            return nil
        }
        guard let previous = lastBpm else {
            lastBpm = bpm
            return nil
        }
        if bpm != previous {
            changedAt = ContinuousClock.now
            direction = bpm > previous ? 1 : -1
            lastBpm = bpm
        }
        guard direction != 0, let changedAt else { return nil }
        let elapsed = Self.milliseconds(changedAt.duration(to: .now))
        guard elapsed < fadeDurationMs else { return nil }
        // Stay on the flash colour for `instantMs`, then blend into the readout colour.
        let fade = min(max((elapsed - instantMs) / (fadeDurationMs - instantMs), 0), 1)
        // Rise: red → base. Drop: green → base. The channel blend the Kotlin code does by hand is
        // left to `Color.mix` in the device colour space, because a dynamic label colour cannot be
        // decomposed without resolving it against an environment.
        return Self.flashColor(direction: direction).mix(with: base, by: fade, in: .device)
    }

    private static func flashColor(direction: Int) -> Color {
        direction > 0
            ? Color(.sRGB, red: 1, green: 0, blue: 0)
            : Color(.sRGB, red: 0, green: 1, blue: 0)
    }

    private static func milliseconds(_ duration: Duration) -> Double {
        let components = duration.components
        return Double(components.seconds) * 1000 + Double(components.attoseconds) / 1e15
    }
}

// MARK: - Rhythm painter

/// Canvas drawing of the rhythm lane, a 1:1 port of the Android widget's `Canvas` block.
///
/// At full width a dense chart puts several notes on the same pixel, which would make the divisors
/// unreadable, so the lane only shows the next two seconds of chart time. Judged heads are dropped
/// and the space they leave shows the live BPM, but a hold or slide keeps its duration line until the
/// tail passes, so the line runs its full length instead of vanishing with the head.
nonisolated enum ChartRhythmPainter {
    /// Fraction of the lane width that sits at the playhead; the rest of the left side is BPM.
    private static let anchorFraction: CGFloat = 0.15
    /// How much of the chart the lane shows ahead of the playhead.
    private static let windowMs = 2_000.0
    private static let minSpeedScale = 0.1
    private static let maxSpeedScale = 5.0

    private static let dotCenterY: CGFloat = 21
    private static let holdLineY: CGFloat = 6
    private static let slideLineY: CGFloat = 36
    private static let dotRadius: CGFloat = 9
    private static let chordRingRadius: CGFloat = 11
    private static let ringWidth: CGFloat = 1.5
    private static let chordRingWidth: CGFloat = 2
    private static let currentRingWidth: CGFloat = 2.8
    private static let durationLineWidth: CGFloat = 3
    private static let durationLineAlpha = 0.55
    private static let durationDash: [CGFloat] = [4, 3]

    private static let bpmCaptionBaseline: CGFloat = 16
    private static let bpmValueBaseline: CGFloat = 36
    private static let bpmGap: CGFloat = 16

    /// Text sizes derived from the `sp` values of the Android widget, scaled by Dynamic Type.
    nonisolated struct TextSizes: Equatable, Sendable {
        var number: CGFloat
        var bpmCaption: CGFloat
        var bpmValue: CGFloat
    }

    /// Keep the divisors from touching once the dots get close; follows the font scale.
    private static let minimumNumberGap: CGFloat = 12
    /// The last stretch of a hold is dashed so its release point is readable.
    private static let holdTailDashMs = 200.0
    /// Latin acronym, matching the stage HUD; not localized on purpose.
    private static let bpmCaption = "BPM"

    static func draw(
        data: ChartRhythmData,
        positionMs: Double,
        speedScale: Double,
        flashColor: Color?,
        textSizes: TextSizes,
        in context: GraphicsContext,
        size: CGSize
    ) {
        let width = size.width
        let height = size.height
        guard width > 0, height > 0 else { return }

        let anchorX = width * anchorFraction
        let position = positionMs
        let pxPerMs = width / CGFloat(windowMs) * CGFloat(min(max(speedScale, minSpeedScale), maxSpeedScale))
        // Chart time → x, the Kotlin `xAt`.
        func xPosition(at timeMs: Double) -> CGFloat {
            anchorX + CGFloat(timeMs - position) * pxPerMs
        }

        // Beat / measure grid.
        for tickMs in data.measureTicks {
            let tickX = xPosition(at: tickMs)
            guard tickX >= 0, tickX <= width else { continue }
            var tick = Path()
            tick.move(to: CGPoint(x: tickX, y: 0))
            tick.addLine(to: CGPoint(x: tickX, y: height))
            context.stroke(tick, with: .color(.primary.opacity(0.10)), lineWidth: 1)
        }

        // Judgment line.
        var playhead = Path()
        playhead.move(to: CGPoint(x: anchorX, y: 0))
        playhead.addLine(to: CGPoint(x: anchorX, y: height))
        context.stroke(playhead, with: .color(.accentColor), lineWidth: 2)

        drawBpmReadout(
            data: data,
            position: position,
            anchorX: anchorX,
            flashColor: flashColor,
            textSizes: textSizes,
            in: context
        )
        drawSteps(
            data: data,
            position: position,
            width: width,
            anchorX: anchorX,
            xPosition: xPosition,
            textSizes: textSizes,
            in: context
        )
    }

    /// Left of the playhead: live BPM instead of notes that were already judged.
    private static func drawBpmReadout(
        data: ChartRhythmData,
        position: Double,
        anchorX: CGFloat,
        flashColor: Color?,
        textSizes: TextSizes,
        in context: GraphicsContext
    ) {
        let textRight = anchorX - bpmGap
        guard textRight > 0 else { return }

        let captionWidth = ChartTimelineText.width(bpmCaption, size: textSizes.bpmCaption, in: context)
        ChartTimelineText.draw(
            bpmCaption,
            left: textRight - captionWidth,
            baseline: bpmCaptionBaseline,
            size: textSizes.bpmCaption,
            color: .secondary,
            in: context
        )

        let value = formatBpm(data.bpmAt(position))
        let valueWidth = ChartTimelineText.width(value, size: textSizes.bpmValue, in: context)
        ChartTimelineText.draw(
            value,
            left: textRight - valueWidth,
            baseline: bpmValueBaseline,
            size: textSizes.bpmValue,
            color: flashColor ?? .primary,
            in: context
        )
    }

    /// Duration lines, dots, chord rings and divisors of every step that is still on screen.
    private static func drawSteps(
        data: ChartRhythmData,
        position: Double,
        width: CGFloat,
        anchorX: CGFloat,
        xPosition: (Double) -> CGFloat,
        textSizes: TextSizes,
        in context: GraphicsContext
    ) {
        let numberGap = max(minimumNumberGap, textSizes.number * 1.4)
        var lastNumberX = -CGFloat.infinity
        var currentTaken = false

        for step in data.steps {
            let tailMs = step.timeMs + step.durationMs
            // Keep the step around until its tail passes so the line runs to completion.
            if tailMs < position { continue }
            if xPosition(step.timeMs) > width + chordRingRadius { break }
            let color = step.kind.color

            if step.durationMs > 0, step.lineSide != .none {
                let lineY = step.lineSide == .above ? holdLineY : slideLineY
                drawDurationLine(
                    step: step,
                    position: position,
                    lineY: lineY,
                    color: color.color.opacity(durationLineAlpha),
                    xPosition: xPosition,
                    clipLeft: anchorX,
                    clipRight: width,
                    in: context
                )
            }

            // The dot belongs to the head only; once judged, just the line is left.
            if step.timeMs < position { continue }
            let dotX = xPosition(step.timeMs)
            let isCurrent = !currentTaken
            if isCurrent { currentTaken = true }

            context.fill(
                circlePath(centerX: dotX, centerY: dotCenterY, radius: dotRadius),
                with: .color(color.color)
            )
            if step.noteCount > 1 {
                // Chord: the ring sits outside the fill so the yellow stays readable even on a
                // yellow tap dot.
                context.stroke(
                    circlePath(centerX: dotX, centerY: dotCenterY, radius: chordRingRadius),
                    with: .color(ChartTimelineColors.chordRing.color),
                    lineWidth: isCurrent ? currentRingWidth : chordRingWidth
                )
            } else {
                context.stroke(
                    circlePath(centerX: dotX, centerY: dotCenterY, radius: dotRadius),
                    with: .color(.white.opacity(0.85)),
                    lineWidth: isCurrent ? currentRingWidth : ringWidth
                )
            }

            if isCurrent || dotX >= lastNumberX + numberGap {
                ChartTimelineText.drawCentered(
                    formatDivisor(step.divisor),
                    center: CGPoint(x: dotX, y: dotCenterY),
                    size: textSizes.number,
                    color: contrast(on: color),
                    in: context
                )
                lastNumberX = dotX
            }
        }
    }

    /// Draws the duration line of one step, clipped to the playhead and to the lane edge.
    ///
    /// Holds are solid and end with a dashed tail; slides start with a dashed launch beat and turn
    /// solid once the path travels.
    private static func drawDurationLine(
        step: ChartRhythmStep,
        position: Double,
        lineY: CGFloat,
        color: Color,
        xPosition: (Double) -> CGFloat,
        clipLeft: CGFloat,
        clipRight: CGFloat,
        in context: GraphicsContext
    ) {
        let startMs = max(step.timeMs, position)
        let endMs = step.timeMs + step.durationMs
        guard endMs > startMs else { return }

        switch step.lineSide {
        case .above:
            let splitMs = max(endMs - holdTailDashMs, step.timeMs)
            drawSegment(
                from: xPosition(startMs),
                to: xPosition(splitMs),
                lineY: lineY,
                color: color,
                clipLeft: clipLeft,
                clipRight: clipRight,
                dashed: false,
                in: context
            )
            drawSegment(
                from: xPosition(splitMs),
                to: xPosition(endMs),
                lineY: lineY,
                color: color,
                clipLeft: clipLeft,
                clipRight: clipRight,
                dashed: true,
                in: context
            )
        case .below:
            let splitMs = min(step.timeMs + step.launchMs, endMs)
            drawSegment(
                from: xPosition(startMs),
                to: xPosition(splitMs),
                lineY: lineY,
                color: color,
                clipLeft: clipLeft,
                clipRight: clipRight,
                dashed: true,
                in: context
            )
            drawSegment(
                from: xPosition(splitMs),
                to: xPosition(endMs),
                lineY: lineY,
                color: color,
                clipLeft: clipLeft,
                clipRight: clipRight,
                dashed: false,
                in: context
            )
        case .none:
            break
        }
    }

    /// One horizontal piece of a duration line; dashed pieces keep the butt cap the Kotlin
    /// `PathEffect` uses, solid ones stay round-capped.
    private static func drawSegment(
        from startX: CGFloat,
        to endX: CGFloat,
        lineY: CGFloat,
        color: Color,
        clipLeft: CGFloat,
        clipRight: CGFloat,
        dashed: Bool,
        in context: GraphicsContext
    ) {
        let clippedStart = max(startX, clipLeft)
        let clippedEnd = min(endX, clipRight)
        guard clippedEnd > clippedStart else { return }
        var path = Path()
        path.move(to: CGPoint(x: clippedStart, y: lineY))
        path.addLine(to: CGPoint(x: clippedEnd, y: lineY))
        let style = dashed
            ? StrokeStyle(lineWidth: durationLineWidth, lineCap: .butt, dash: durationDash)
            : StrokeStyle(lineWidth: durationLineWidth, lineCap: .round)
        context.stroke(path, with: .color(color), style: style)
    }

    private static func circlePath(centerX: CGFloat, centerY: CGFloat, radius: CGFloat) -> Path {
        Path(ellipseIn: CGRect(x: centerX - radius, y: centerY - radius, width: radius * 2, height: radius * 2))
    }

    /// Dark numerals on light fills, white on dark ones, so every note colour stays readable.
    private static func contrast(on background: ChartColor) -> Color {
        let luminance = 0.299 * background.red + 0.587 * background.green + 0.114 * background.blue
        return luminance > 0.5 ? ChartColor.black.withAlpha(0.85).color : .white
    }

    /// Divisors are whole numbers in practice; keep a decimal only when the chart really uses one.
    private static func formatDivisor(_ divisor: Double) -> String {
        let rounded = divisor.rounded()
        return abs(divisor - rounded) < 0.01 ? String(Int(rounded)) : String(divisor)
    }

    /// BPM readout: whole values stay integral, fractional charts keep one decimal.
    private static func formatBpm(_ bpm: Double) -> String {
        let rounded = bpm.rounded()
        if abs(bpm - rounded) < 0.05 { return String(Int(rounded)) }
        return String((bpm * 10).rounded() / 10)
    }
}
