import SwiftUI

// MARK: - Palette

/// Note-family colours shared by the density legend, the stacked density bars and the rhythm lane.
///
/// They match the stage renderer so the timelines read like the notes on the stage. Holds share the
/// tap fill there, so the legend keeps them apart with yellow, exactly like the Android widget's
/// `DensityColors`.
nonisolated enum ChartTimelineColors {
    static let tap = ChartConstants.Colors.tapPink
    static let hold = ChartConstants.Colors.simultaneousGold
    static let slide = ChartConstants.Colors.slideCyan
    static let touch = ChartConstants.Colors.touchBlue
    static let breakNote = ChartConstants.Colors.breakOrange

    /// Yellow ring that marks a chord (双押) on the rhythm lane.
    static let chordRing = ChartColor(argb: 0xFFFF_D400)
}

// MARK: - Density data

/// One bucket of the density timeline.
nonisolated struct ChartDensityBucket: Equatable, Sendable {
    /// Chart time of the bucket's leading edge.
    var startMs: Double
    var tap: Int = 0
    var hold: Int = 0
    var slide: Int = 0
    var touch: Int = 0
    var breakNote: Int = 0

    /// Notes in this bucket; bar heights are stacked from these five counts.
    var total: Int { tap + hold + slide + touch + breakNote }
}

/// Precomputed density data for the chart timeline: one bucket per 500 ms of chart time.
nonisolated struct ChartDensityData: Equatable, Sendable {
    var buckets: [ChartDensityBucket]
    var durationMs: Double
    /// Busiest bucket, at least 1; bar heights are normalised against it.
    var maxCount: Int

    /// Buckets `notes` exactly like `ChartDensityData.from` on Android: a note counts when its head
    /// time falls inside `0...durationMs` (both bounds inclusive) and it belongs to one of the five
    /// bar families.
    static func make(notes: [ChartNote], durationMs: Double, bucketMs: Double = 500) -> ChartDensityData {
        // Kotlin returns `maxCount = 1` and no buckets for an empty chart, so the view can bail out
        // instead of dividing by a zero duration.
        guard durationMs > 0, bucketMs > 0 else {
            return ChartDensityData(buckets: [], durationMs: durationMs, maxCount: 1)
        }
        let bucketCount = Int((durationMs / bucketMs).rounded(.down)) + 1
        var counts = Array(repeating: [0, 0, 0, 0, 0], count: bucketCount)
        for note in notes {
            guard note.timingMs >= 0, note.timingMs <= durationMs else { continue }
            let index = Int((note.timingMs / bucketMs).rounded(.down))
            guard index >= 0, index < bucketCount else { continue }
            switch note.kind {
            case .tap:
                // Break taps get their own bar so breaks stay readable inside the stack.
                counts[index][note.isBreak ? 4 : 0] += 1
            case .holdStart:
                counts[index][1] += 1
            case .slide:
                counts[index][2] += 1
            case .touch, .touchHoldStart:
                counts[index][3] += 1
            case .holdEnd, .touchHoldEnd:
                // Tails are not note heads; the Kotlin `when` ignores them as well.
                break
            }
        }
        let buckets = counts.indices.map { index in
            ChartDensityBucket(
                startMs: Double(index) * bucketMs,
                tap: counts[index][0],
                hold: counts[index][1],
                slide: counts[index][2],
                touch: counts[index][3],
                breakNote: counts[index][4]
            )
        }
        let busiest = buckets.map(\.total).max() ?? 1
        return ChartDensityData(buckets: buckets, durationMs: durationMs, maxCount: max(1, busiest))
    }
}

// MARK: - Density timeline

/// Stacked note-density timeline with a seekable playhead, mirroring the Android
/// `ChartDensityTimeline` widget.
///
/// Dragging anywhere on the strip seeks, like the Kotlin `awaitEachGesture` / `drag` handler: the
/// touch-down starts the scrub, every following move reports the finger position, and releasing
/// finishes it. A plain tap therefore seeks and commits in one step, which is what the Android
/// widget does too.
struct ChartDensityTimeline: View {
    /// Strip height; the Android widget is `52.dp` tall.
    private static let height: CGFloat = 52

    let data: ChartDensityData
    let positionMs: Double
    /// Kept for API symmetry with `ChartRhythmTimeline`: the Android density strip draws the same
    /// whether the chart is running or paused, so nothing here depends on it.
    let isPlaying: Bool
    let onScrubStart: (Double) -> Void
    let onScrub: (Double) -> Void
    let onScrubEnd: () -> Void

    /// Strip width, needed to turn a touch position into chart time. `Canvas` has no size outside
    /// its renderer, so the gesture reads it from the layout pass instead of a `GeometryReader`.
    @State private var width: CGFloat = 0
    @State private var isScrubbing = false
    /// Mirrors the in-flight drag so a gesture that is cancelled (a system interruption, or another
    /// recogniser taking the touch) still ends the scrub; `DragGesture` has no cancellation callback.
    @GestureState private var isDragging = false
    /// Text sizes come from `sp` values on Android, which follow the system font scale.
    @ScaledMetric(relativeTo: .caption2) private var labelFontSize: CGFloat = 9
    @ScaledMetric(relativeTo: .caption2) private var badgeFontSize: CGFloat = 10

    var body: some View {
        if data.buckets.isEmpty {
            EmptyView()
        } else {
            let labelSize = labelFontSize
            let badgeSize = badgeFontSize
            Canvas { context, size in
                ChartDensityPainter.draw(
                    data: data,
                    positionMs: positionMs,
                    labelFontSize: labelSize,
                    badgeFontSize: badgeSize,
                    in: context,
                    size: size
                )
            }
            .frame(height: Self.height)
            .contentShape(.rect)
            .onGeometryChange(for: CGFloat.self) { proxy in
                proxy.size.width
            } action: { newWidth in
                width = newWidth
            }
            .gesture(scrubGesture())
            .onChange(of: isDragging) { wasDragging, dragging in
                if wasDragging, !dragging {
                    finishScrub()
                }
            }
            .accessibilityElement()
            .accessibilityLabel(Text(String(localized: "chart.preview.density.label")))
            .accessibilityValue(Text(ChartDensityPainter.timeText(positionMs)))
            .accessibilityHint(Text(String(localized: "chart.preview.density.hint")))
        }
    }

    /// Port of `Modifier.pointerInput { awaitEachGesture { … } }`. `minimumDistance: 0` makes the
    /// gesture start on touch-down like `awaitFirstDown`, and the first change only reports the
    /// start position so the sequence stays `onScrubStart` → `onScrub…` → `onScrubEnd`.
    private func scrubGesture() -> some Gesture {
        DragGesture(minimumDistance: 0)
            .updating($isDragging) { _, dragging, _ in
                dragging = true
            }
            .onChanged { value in
                guard data.durationMs > 0, width > 0 else { return }
                if isScrubbing {
                    onScrub(seekTime(forX: value.location.x))
                } else {
                    isScrubbing = true
                    onScrubStart(seekTime(forX: value.startLocation.x))
                }
            }
            .onEnded { _ in
                finishScrub()
            }
    }

    /// Kotlin `seek(x) = (x / size.width).coerceIn(0, 1) * durationMs`.
    private func seekTime(forX positionX: CGFloat) -> Double {
        let fraction = min(max(positionX / width, 0), 1)
        return Double(fraction) * data.durationMs
    }

    /// Ends an in-flight scrub exactly once, mirroring the Kotlin `onScrubEnd()` that runs after
    /// `drag()` returns, whether the drag finished or was interrupted.
    private func finishScrub() {
        guard isScrubbing else { return }
        isScrubbing = false
        onScrubEnd()
    }
}

// MARK: - Density legend

/// Coloured key for the density bars.
///
/// The note-type names stay in English in every locale, matching the reference legend, so they are
/// drawn verbatim instead of going through the string catalog.
struct ChartDensityLegend: View {
    var body: some View {
        ChartDensityLegendFlow(horizontalSpacing: 12, verticalSpacing: 4) {
            ChartDensityLegendItem(color: ChartTimelineColors.tap.color, label: "Tap")
            ChartDensityLegendItem(color: ChartTimelineColors.hold.color, label: "Hold")
            ChartDensityLegendItem(color: ChartTimelineColors.slide.color, label: "Slide")
            ChartDensityLegendItem(color: ChartTimelineColors.touch.color, label: "Touch")
            ChartDensityLegendItem(color: ChartTimelineColors.breakNote.color, label: "Break")
        }
    }
}

/// One dot + name pair of the legend.
struct ChartDensityLegendItem: View {
    let color: Color
    let label: String

    var body: some View {
        HStack(spacing: 4) {
            Circle()
                .fill(color)
                .frame(width: 8, height: 8)
                .accessibilityHidden(true)
            Text(verbatim: label)
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }
}

/// Wrapping row for the legend, the SwiftUI counterpart of the Compose `FlowRow` the Android
/// legend uses (12 pt between items, 4 pt between rows).
///
/// The chart-preview widgets have to stay self-contained so they can be type-checked on their own,
/// so this keeps a local flow layout instead of reaching for the catalog's `FlowLayout`.
nonisolated struct ChartDensityLegendFlow: Layout {
    var horizontalSpacing: CGFloat
    var verticalSpacing: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) -> CGSize {
        let rows = rows(subviews: subviews, maxWidth: proposal.width ?? .infinity)
        let contentWidth = rows.map(\.width).max() ?? 0
        let height = rows.reduce(0) { $0 + $1.height } + verticalSpacing * CGFloat(max(rows.count - 1, 0))
        // The Android legend is a `FlowRow(fillMaxWidth())`, so it fills the row and lays its items
        // out from the leading edge instead of shrinking to its content.
        return CGSize(width: proposal.width ?? contentWidth, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout Void) {
        var rowOriginY = bounds.minY
        for row in rows(subviews: subviews, maxWidth: bounds.width) {
            var itemOriginX = bounds.minX
            for index in row.indices {
                let size = subviews[index].sizeThatFits(.unspecified)
                subviews[index].place(
                    at: CGPoint(x: itemOriginX, y: rowOriginY),
                    anchor: .topLeading,
                    proposal: ProposedViewSize(size)
                )
                itemOriginX += size.width + horizontalSpacing
            }
            rowOriginY += row.height + verticalSpacing
        }
    }

    private struct Row {
        var indices: [Int] = []
        var width: CGFloat = 0
        var height: CGFloat = 0
    }

    private func rows(subviews: Subviews, maxWidth: CGFloat) -> [Row] {
        var rows: [Row] = []
        var current = Row()
        for index in 0..<subviews.count {
            let size = subviews[index].sizeThatFits(.unspecified)
            let spacing = current.indices.isEmpty ? 0 : horizontalSpacing
            let widthIfPlaced = current.width + spacing + size.width
            if !current.indices.isEmpty, widthIfPlaced > maxWidth {
                rows.append(current)
                current = Row(indices: [index], width: size.width, height: size.height)
            } else {
                current.indices.append(index)
                current.width = widthIfPlaced
                current.height = max(current.height, size.height)
            }
        }
        if !current.indices.isEmpty { rows.append(current) }
        return rows
    }
}

// MARK: - Density painter

/// Canvas drawing of the density strip, a 1:1 port of the Android widget's `Canvas` block.
nonisolated enum ChartDensityPainter {
    /// Grid line and time-label interval, `markerIntervalMs` in Kotlin.
    private static let markerIntervalMs = 30_000.0
    /// Nominal bucket length. `DensityBarPaths` hard-codes the same 500 ms for the bar width, which
    /// keeps the bar rhythm independent of how many buckets the chart produced.
    private static let bucketMs = 500.0

    private static let barsTop: CGFloat = 14
    private static let barsHeight: CGFloat = 32
    /// Bars grow upwards from this line (`barsTop + barsHeight`).
    private static var barsBottom: CGFloat { barsTop + barsHeight }
    private static let markerTop: CGFloat = 12
    private static let labelBaseline: CGFloat = 10
    private static let minBarHeight: CGFloat = 2
    private static let minBarWidth: CGFloat = 1

    private static let badgeHeight: CGFloat = 16
    private static let badgeTextPadding: CGFloat = 8
    private static let badgeCornerRadius: CGFloat = 4
    private static let gridDash: [CGFloat] = [2, 2]
    private static let gridWidth: CGFloat = 1
    private static let playheadWidth: CGFloat = 2

    private static let barColors: [ChartColor] = [
        ChartTimelineColors.tap,
        ChartTimelineColors.hold,
        ChartTimelineColors.slide,
        ChartTimelineColors.touch,
        ChartTimelineColors.breakNote
    ]

    static func draw(
        data: ChartDensityData,
        positionMs: Double,
        labelFontSize: CGFloat,
        badgeFontSize: CGFloat,
        in context: GraphicsContext,
        size: CGSize
    ) {
        let width = size.width
        let height = size.height
        // Every x below divides by the duration; a chart without one has no buckets, so the view
        // already returned nothing for it.
        guard width > 0, height > 0, data.durationMs > 0 else { return }

        drawTimeGrid(durationMs: data.durationMs, width: width, height: height, in: context)
        drawBars(data, width: width, in: context)
        drawTimeLabels(durationMs: data.durationMs, width: width, fontSize: labelFontSize, in: context)
        drawPlayhead(
            data: data,
            positionMs: positionMs,
            width: width,
            height: height,
            fontSize: badgeFontSize,
            in: context
        )
    }

    /// `m:ss` chart time, the Kotlin `formatDensityTime`.
    static func timeText(_ timeMs: Double) -> String {
        let totalSeconds = max(Int(timeMs / 1000), 0)
        let minutes = totalSeconds / 60
        let seconds = totalSeconds % 60
        // Zero-padded by hand so the label keeps ASCII digits in every locale, like `padStart`.
        return "\(minutes):\(seconds < 10 ? "0" : "")\(seconds)"
    }

    /// 30 s grid lines behind the bars.
    private static func drawTimeGrid(durationMs: Double, width: CGFloat, height: CGFloat, in context: GraphicsContext) {
        var markerMs = markerIntervalMs
        while markerMs < durationMs {
            let lineX = CGFloat(markerMs / durationMs) * width
            var line = Path()
            line.move(to: CGPoint(x: lineX, y: markerTop))
            line.addLine(to: CGPoint(x: lineX, y: height))
            context.stroke(
                line,
                with: .color(.primary.opacity(0.12)),
                style: StrokeStyle(lineWidth: gridWidth, dash: gridDash)
            )
            markerMs += markerIntervalMs
        }
    }

    /// Stacked bars, bottom-up: tap, hold, slide, touch, break.
    ///
    /// The geometry is accumulated into one path per note type, the same idea as the Android
    /// widget's `DensityBarPaths`: hundreds of per-bar draws were the frame-time hotspot there, and
    /// five fills keep the per-frame cost flat.
    private static func drawBars(_ data: ChartDensityData, width: CGFloat, in context: GraphicsContext) {
        let duration = data.durationMs
        let bucketWidth = max(minBarWidth, CGFloat(bucketMs / duration) * width)
        var paths = [Path](repeating: Path(), count: barColors.count)
        for bucket in data.buckets {
            guard bucket.total > 0 else { continue }
            let left = CGFloat(bucket.startMs / duration) * width
            let barHeight = max(minBarHeight, CGFloat(Double(bucket.total) / Double(data.maxCount)) * barsHeight)
            let segments = [bucket.tap, bucket.hold, bucket.slide, bucket.touch, bucket.breakNote]
            var stackBottom = barsBottom
            for index in segments.indices {
                let count = segments[index]
                guard count > 0 else { continue }
                let segmentHeight = barHeight * CGFloat(count) / CGFloat(bucket.total)
                paths[index].addRect(
                    CGRect(x: left, y: stackBottom - segmentHeight, width: bucketWidth, height: segmentHeight)
                )
                stackBottom -= segmentHeight
            }
        }
        for index in barColors.indices {
            context.fill(paths[index], with: .color(barColors[index].color))
        }
    }

    /// Time labels, edge-clamped like the reference: the first label is left-aligned, the last one
    /// right-aligned, everything in between is centred on its grid line.
    private static func drawTimeLabels(
        durationMs: Double,
        width: CGFloat,
        fontSize: CGFloat,
        in context: GraphicsContext
    ) {
        var labelMs = 0.0
        while labelMs <= durationMs {
            let text = timeText(labelMs)
            let textWidth = ChartTimelineText.width(text, size: fontSize, in: context)
            let labelX: CGFloat
            if labelMs == 0 {
                labelX = 0
            } else if labelMs >= durationMs {
                labelX = width - textWidth
            } else {
                labelX = CGFloat(labelMs / durationMs) * width - textWidth / 2
            }
            ChartTimelineText.draw(
                text,
                left: labelX,
                baseline: labelBaseline,
                size: fontSize,
                color: .secondary,
                in: context
            )
            labelMs += markerIntervalMs
        }
    }

    /// Playhead line plus its current-time badge.
    private static func drawPlayhead(
        data: ChartDensityData,
        positionMs: Double,
        width: CGFloat,
        height: CGFloat,
        fontSize: CGFloat,
        in context: GraphicsContext
    ) {
        let playhead = min(max(positionMs, 0), data.durationMs)
        let playheadX = CGFloat(playhead / data.durationMs) * width

        var line = Path()
        line.move(to: CGPoint(x: playheadX, y: markerTop))
        line.addLine(to: CGPoint(x: playheadX, y: height))
        context.stroke(line, with: .color(.accentColor), style: StrokeStyle(lineWidth: playheadWidth))

        let text = timeText(playhead)
        let textWidth = ChartTimelineText.width(text, size: fontSize, in: context)
        let badgeWidth = textWidth + badgeTextPadding
        let badgeLeft = min(max(playheadX - badgeWidth / 2, 0), max(width - badgeWidth, 0))
        let badge = Path(
            roundedRect: CGRect(x: badgeLeft, y: 0, width: badgeWidth, height: badgeHeight),
            cornerRadius: badgeCornerRadius
        )
        context.fill(badge, with: .color(.accentColor))
        ChartTimelineText.drawCentered(
            text,
            center: CGPoint(x: badgeLeft + badgeWidth / 2, y: badgeHeight / 2),
            size: fontSize,
            color: .white,
            in: context
        )
    }
}

// MARK: - Text helpers

/// Small-text drawing shared by both timeline canvases.
///
/// `GraphicsContext` only places text by anchor, while the Android widgets place it by baseline
/// (`drawText(text, x, baselineY, paint)`), so these helpers resolve the text first and offset the
/// draw by its first baseline to land the glyphs on the same rows as the reference.
nonisolated enum ChartTimelineText {
    /// Generous single-line proposal for measurement; both widgets only draw short labels.
    private static let measurementBox = CGSize(width: 4096, height: 4096)

    /// Width of `text`, the equivalent of `Paint.measureText`.
    static func width(_ text: String, size: CGFloat, in context: GraphicsContext) -> CGFloat {
        context.resolve(makeText(text, size: size)).measure(in: measurementBox).width
    }

    /// Draws `text` with its baseline on `baseline` and its left edge on `left`.
    static func draw(
        _ text: String,
        left: CGFloat,
        baseline: CGFloat,
        size: CGFloat,
        color: Color,
        in context: GraphicsContext
    ) {
        var resolved = context.resolve(makeText(text, size: size))
        resolved.shading = .color(color)
        let firstBaseline = resolved.firstBaseline(in: measurementBox)
        context.draw(resolved, at: CGPoint(x: left, y: baseline - firstBaseline), anchor: .topLeading)
    }

    /// Draws `text` centred on `center`, for the places where the Android paint is centre-aligned.
    static func drawCentered(
        _ text: String,
        center: CGPoint,
        size: CGFloat,
        color: Color,
        in context: GraphicsContext
    ) {
        var resolved = context.resolve(makeText(text, size: size))
        resolved.shading = .color(color)
        context.draw(resolved, at: center, anchor: .center)
    }

    private static func makeText(_ text: String, size: CGFloat) -> Text {
        // `verbatim:` because these strings are chart data (times, divisors), not localized copy.
        Text(verbatim: text).font(.system(size: size))
    }
}
