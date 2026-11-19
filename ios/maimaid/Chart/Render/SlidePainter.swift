import CoreGraphics
import Foundation
import SwiftUI

/// Slide guide tracks (arrow chains / Wi‑Fi fans) and slide star heads.
///
/// Ported 1:1 from `render/SlidePainter.kt`. The pure geometry that used to be private to the Kotlin
/// painter — templates, guide chains, arc-length tables, chunky-step and hidden count queries, the
/// Wi‑Fi fan layout and the path samplers — now lives in `SlideBars`. What is left here is the
/// canvas-facing half: chevron polygons, the Wi‑Fi fan draws, the star heads, and the per-segment
/// arrow cache that keeps large charts from rebuilding paths every frame.
nonisolated final class SlidePainter: BasePainter {
    /// Which half of a slide is being drawn: its guide track or its star head.
    nonisolated enum RenderMode {
        case tracks
        case stars
    }

    private let notePainter: NotePainter

    /// One guide arrow as three filled polygons: the body, its baked shadow copy (already shifted by
    /// the shadow offset) and the left half that breaks tint separately from the right half.
    private nonisolated struct ArrowPaths {
        var main: Path
        var shadow: Path
        var leftHalf: Path
    }

    /// Cached arrow polygons for one segment.
    ///
    /// Kotlin keeps two `IdentityHashMap`s (`arrowCacheBasis` for invalidation, `arrowCache` for the
    /// paths). Swift dictionaries are values, so the basis is folded into the entry to guarantee the
    /// two can never disagree.
    private nonisolated struct ArrowCacheEntry {
        var basis: String
        var paths: [String: [ArrowPaths]]
    }

    private var arrowCache: [ObjectIdentifier: ArrowCacheEntry] = [:]

    /// The reference's EX glow default (`0xCC64E6E6`). It is the only slide color that has no entry
    /// in `ChartConstants.Colors`, because it appears exclusively in the two EX star rings.
    private static let exStarRingCyan = ChartColor(argb: 0xCC64_E6E6)

    init(_ context: RenderContext, _ notePainter: NotePainter) {
        self.notePainter = notePainter
        super.init(context)
    }

    // MARK: - Slide head position

    /// Calculates the slide head position; first half fades in place, second half expands to the ring.
    ///
    /// Unlike a tap note the head never crosses the ring: it fades in at a quarter of the radius and
    /// then travels outwards to the ring as the hit time approaches (`APPROACH_START_SCALE` offset
    /// plus `0.75` radius units of travel). The `scale` field only fades the head in during the first
    /// half; after that the head is full size and its position does the animation.
    func calculateSlideStartPosition(_ note: ChartNote, currentTimeMs: Double) -> NoteRenderPosition {
        let angle = buttonAngle(note.position)
        let timeDiff = note.timingMs - currentTimeMs
        let approachTime = noteApproachTimeMs(note.hiSpeed)
        if timeDiff > approachTime || timeDiff < -ChartConstants.noteVisibilityAfterMs {
            return .invisible
        }
        let dir = noteApproachDir(note.hiSpeed)
        let halfApproach = approachTime / 2
        let distance: Double
        let scale: Double
        if timeDiff > halfApproach {
            distance = Double(context.radius) * (1 + dir * (ChartConstants.approachStartScale - 1))
            scale = 1 - (timeDiff - halfApproach) / halfApproach
        } else if timeDiff >= 0 {
            let progress = 1 - timeDiff / halfApproach
            distance = Double(context.radius)
                * (1 + dir * (ChartConstants.approachStartScale - 1 + 0.75 * progress))
            scale = 1
        } else {
            // After the hit time the head keeps drifting outwards so the glow leaves the ring.
            let fadeProgress = 1 + -timeDiff / halfApproach
            distance = Double(context.radius)
                * (1 + dir * (ChartConstants.approachStartScale - 1 + 0.75 * fadeProgress))
            scale = 1
        }
        return NoteRenderPosition(
            x: Double(context.centerX) + cos(angle) * distance,
            y: Double(context.centerY) + sin(angle) * distance,
            scale: scale,
            visible: true
        )
    }

    // MARK: - Slide rendering

    /// Renders one slide, either its guide track or its moving star head.
    func renderSlide(_ note: ChartNote, currentTimeMs: Double, mode: RenderMode, isSimultaneous: Bool) {
        if note.isSplitSlide && !note.allSlideSegments.isEmpty {
            let paths = note.allSlideSegments
            let indexes = renderPathIndexes(note)
            // Wi‑Fi fans are drawn before every other concurrent path, so a normal path of the same
            // split slide paints over them. This z-order is part of the reference look.
            for index in indexes where paths[index].first?.type == .wifi {
                renderSlidePath(note, currentTimeMs: currentTimeMs, segments: paths[index],
                                pathIndex: index, mode: mode,
                                hasSimultaneousSlide: isSimultaneous)
            }
            for index in indexes where paths[index].first?.type != .wifi {
                renderSlidePath(note, currentTimeMs: currentTimeMs, segments: paths[index],
                                pathIndex: index, mode: mode,
                                hasSimultaneousSlide: isSimultaneous)
            }
        } else if !note.slideSegments.isEmpty {
            renderSlidePath(note, currentTimeMs: currentTimeMs, segments: note.slideSegments,
                            pathIndex: 0, mode: mode, hasSimultaneousSlide: isSimultaneous)
        }
    }

    /// De-duplicates identical concurrent paths so they are not drawn twice.
    ///
    /// Hand-written simai sometimes lists the same fan/arc twice in one split slide; painting both
    /// would double the alpha of every overlap.
    private func renderPathIndexes(_ note: ChartNote) -> [Int] {
        let paths = note.allSlideSegments
        var seen: Set<String> = []
        var result: [Int] = []
        for index in paths.indices {
            let segments = paths[index]
            if segments.isEmpty { continue }
            var key = segments.map { $0.signatureKey }.joined(separator: "+")
            key += "|" + (value(note.allDelayMs, at: index).map { String($0) } ?? "")
            key += "|" + (value(note.allDurationMs, at: index).map { String($0) } ?? "")
            key += "|" + ((value(note.allSlideBreaks, at: index) ?? false) ? "1" : "0")
            if !seen.insert(key).inserted { continue }
            result.append(index)
        }
        return result
    }

    private func renderSlidePath(_ note: ChartNote, currentTimeMs: Double,
                                 segments: [SlideSegment], pathIndex: Int, mode: RenderMode,
                                 hasSimultaneousSlide: Bool) {
        let durationMs = value(note.allDurationMs, at: pathIndex) ?? note.durationMs
        let delayMs = value(note.allDelayMs, at: pathIndex) ?? note.delayMs
        let slideStart = note.timingMs + delayMs
        if currentTimeMs > slideStart + durationMs { return }

        var progress = 0.0
        if currentTimeMs >= slideStart {
            progress = durationMs > 0 ? min(1, (currentTimeMs - slideStart) / durationMs) : 1
        }

        let isSimultaneous = hasSimultaneousSlide || note.isSplitSlide
        if mode == .stars {
            if currentTimeMs >= note.timingMs {
                renderSlideStar(note, progress: progress, segments: segments, pathIndex: pathIndex,
                                currentTimeMs: currentTimeMs, isSimultaneous: isSimultaneous)
            }
            return
        }

        let ordinary = slideTrackAppearance(note, currentTimeMs: currentTimeMs, isWifi: false)
        let wifi = slideTrackAppearance(note, currentTimeMs: currentTimeMs, isWifi: true)
        if ordinary.alpha == 0 && wifi.alpha == 0 { return }

        let isBreak = value(note.allSlideBreaks, at: pathIndex) ?? false
        var lengths: [Double] = []
        lengths.reserveCapacity(segments.count)
        var totalLength = 0.0
        for segment in segments {
            let length = segmentLength(segment)
            lengths.append(length)
            totalLength += length
        }
        if totalLength <= 0 { return }

        // Progress is mapped onto each segment by its share of the whole path length, so a short
        // segment at the end of a path still gets its full draw window.
        var ranges: [(start: Double, end: Double)] = []
        ranges.reserveCapacity(segments.count)
        var cumulative = 0.0
        for index in segments.indices {
            let start = cumulative / totalLength
            cumulative += lengths[index]
            ranges.append((start, cumulative / totalLength))
        }

        // Reverse index order, as in the reference: the last segment is painted first, so an earlier
        // segment's extrapolated junction arrow lands on top of the next segment's first arrow.
        for index in segments.indices.reversed() {
            let segment = segments[index]
            let alpha = segment.type == .wifi ? wifi.alpha : ordinary.alpha
            if alpha == 0 { continue }
            let range = ranges[index]
            var segmentProgress = 0.0
            if progress > range.start {
                segmentProgress = progress >= range.end
                    ? 1
                    : (progress - range.start) / (range.end - range.start)
            }
            renderSlideSegment(segment, isBreak: isBreak, progress: segmentProgress,
                               isSimultaneous: isSimultaneous,
                               normalBreakColor: context.config.normalColorBreakSlide,
                               isJunctionEnd: index < segments.count - 1,
                               alpha: alpha)
        }
    }

    private func renderSlideSegment(_ segment: SlideSegment, isBreak: Bool, progress: Double,
                                    isSimultaneous: Bool, normalBreakColor: Bool,
                                    isJunctionEnd: Bool, alpha: Double) {
        let color: ChartColor
        if isBreak && !normalBreakColor {
            color = ChartConstants.Colors.breakOrange
        } else if isSimultaneous {
            color = ChartConstants.Colors.slideSimultaneous
        } else {
            color = ChartConstants.Colors.slideCyan
        }
        if segment.type == .wifi {
            renderWifiBars(segment, progress: progress, color: color, alpha: alpha)
            return
        }
        drawSegmentArrows(segment, progress: progress, isJunctionEnd: isJunctionEnd, color: color,
                          alpha: alpha)
    }

    // MARK: - Guide chain

    /// Guide chain of a segment in canvas space, cached on the segment by `SlideBars`.
    private func barChain(_ segment: SlideSegment) -> [SlideChainPoint]? {
        SlideBars.barChain(segment: segment, radius: context.radius, centerX: context.centerX,
                           centerY: context.centerY, mirrorMode: context.config.mirrorMode)
    }

    /// Draws the chevron arrows of one segment: shadow, body, optional break half tint, black edge —
    /// in that exact order, because the black edge is what separates overlapping arrows.
    private func drawSegmentArrows(_ segment: SlideSegment, progress: Double, isJunctionEnd: Bool,
                                   color: ChartColor, alpha: Double) {
        guard let chain = barChain(segment) else { return }
        let basis = "\(context.radius)|\(context.config.mirrorMode.rawValue)"
        let identity = ObjectIdentifier(segment)
        var entry: ArrowCacheEntry
        if let cached = arrowCache[identity], cached.basis == basis {
            entry = cached
        } else {
            entry = ArrowCacheEntry(basis: basis, paths: [:])
        }
        let hiddenCount = SlideBars.hiddenCount(segment: segment, progress: progress)
        let key = "\(hiddenCount)|\(isJunctionEnd ? 1 : 0)"
        var arrowPaths = entry.paths[key]
        if arrowPaths == nil {
            let bars = visibleBarsForSegment(segment, chain: chain, hiddenCount: hiddenCount,
                                             isJunctionEnd: isJunctionEnd)
            guard !bars.isEmpty else { return }
            let built = buildArrowPaths(bars)
            // The reference caps the per-segment cache at 64 entries; beyond that it is cheaper to
            // rebuild than to keep growing.
            if entry.paths.count > 64 { entry.paths.removeAll() }
            entry.paths[key] = built
            arrowCache[identity] = entry
            arrowPaths = built
        }
        guard let arrowPaths else { return }

        // The only color that means "break" here is the constant assigned above, so identity through
        // `Hashable` equality is exactly the reference's packed-int comparison.
        let isBreak = color == ChartConstants.Colors.breakOrange
        let leftColor = isBreak ? ChartConstants.Colors.slideSimultaneous : color
        let rightColor = isBreak ? ChartConstants.Colors.slideArrowRight : color
        let canvas = context.canvas
        let noteStroke = noteStrokeWidth()

        for arrow in arrowPaths {
            applyColor(&fillPaint, ChartConstants.Colors.black, alpha: alpha * 0.4)
            canvas.drawPath(arrow.shadow, fillPaint)
            applyColor(&fillPaint, rightColor, alpha: alpha)
            canvas.drawPath(arrow.main, fillPaint)
            if isBreak {
                applyColor(&fillPaint, leftColor, alpha: alpha)
                canvas.drawPath(arrow.leftHalf, fillPaint)
            }
            strokeSettings(noteStroke, .butt, .miter)
            applyColor(&strokePaint, ChartConstants.Colors.black, alpha: alpha)
            canvas.drawPath(arrow.main, strokePaint)
        }
    }

    /// Arrows still visible at `hiddenCount`, walking the chain from its head backwards.
    ///
    /// A junction end (`isJunctionEnd`, i.e. the segment is not the last of its path) gets one extra
    /// extrapolated arrow so the next segment visually continues the chain. Line segments have no
    /// precomputed tangent in the reference, so their angle is derived from the neighbouring chain
    /// points instead.
    private func visibleBarsForSegment(_ segment: SlideSegment, chain: [SlideChainPoint],
                                       hiddenCount: Int, isJunctionEnd: Bool) -> [SlideChainPoint] {
        if segment.type == .wifi { return [] }
        let usePrecomputedAngle = segment.type != .line
        var result: [SlideChainPoint] = []
        result.reserveCapacity(chain.count)
        let last = chain.count - 1
        guard last >= 0 else { return result }

        if isJunctionEnd && chain.count >= 2 && hiddenCount < chain.count {
            let a = chain[last]
            let b = chain[last - 1]
            // One arrow extrapolated past the chain's end, so the next segment of the path starts
            // with an arrow already in place. The reference drops it once that point leaves the
            // judgment ring.
            let junctionX = 2 * a.x - b.x
            let junctionY = 2 * a.y - b.y
            if distanceToCenter(junctionX, junctionY) <= Double(context.radius) {
                let angle = usePrecomputedAngle ? a.angle : atan2(a.y - b.y, a.x - b.x)
                result.append(SlideChainPoint(x: junctionX, y: junctionY, angle: angle))
            }
        }

        guard last >= hiddenCount else { return result }
        for index in stride(from: last, through: hiddenCount, by: -1) {
            let angle: Double
            if usePrecomputedAngle {
                angle = chain[index].angle
            } else {
                let lo = max(0, index - 1)
                let hi = min(last, index + 1)
                angle = atan2(chain[hi].y - chain[lo].y, chain[hi].x - chain[lo].x)
            }
            result.append(SlideChainPoint(x: chain[index].x, y: chain[index].y, angle: angle))
        }
        return result
    }

    /// Builds filled chevron polygons plus their baked shadow offset.
    ///
    /// The body is a six-point chevron: two flanks at `±height / 2` and `±width / 2` around the
    /// arrow's padded center, closed by a concave back edge through `bisectorPoint`, so the notch
    /// depth follows the flank angle instead of a fixed offset. The shadow is the same polygon
    /// shifted by `-(cos, sin) * offset`, i.e. backwards along the path under the arrow's tail.
    private func buildArrowPaths(_ arrows: [SlideChainPoint]) -> [ArrowPaths] {
        let arrowHeight = Double(scaleByRadius(ChartConstants.slideArrowHeightRatio))
        let arrowWidth = Double(scaleByRadius(ChartConstants.slideArrowSpanRatio))
        let lineWidth = Double(scaleByRadius(ChartConstants.slideArrowWidthRatio))
        let padding = Double(scaleByRadius(ChartConstants.slideArrowPaddingRatio))
        let shadowOffset = Double(scaleByRadius(5.0 / 300.0))

        var result: [ArrowPaths] = []
        result.reserveCapacity(arrows.count)
        for arrow in arrows {
            let cosA = cos(arrow.angle)
            let sinA = sin(arrow.angle)
            // `padding` pushes the arrow forward along the path, so consecutive chevrons overlap.
            let ax = arrow.x + cosA * padding
            let ay = arrow.y + sinA * padding

            let x1 = ax + cosA * (-arrowWidth / 2) - sinA * (-arrowHeight / 2)
            let y1 = ay + sinA * (-arrowWidth / 2) + cosA * (-arrowHeight / 2)
            let x2 = ax + cosA * (arrowWidth / 2)
            let y2 = ay + sinA * (arrowWidth / 2)
            let x3 = ax + cosA * (-arrowWidth / 2) - sinA * (arrowHeight / 2)
            let y3 = ay + sinA * (-arrowWidth / 2) + cosA * (arrowHeight / 2)

            guard let bisector = bisectorPoint(x1, y1, x2, y2, x3, y3, lineWidth) else { continue }
            let bx = bisector.x
            let by = bisector.y
            let bx3 = bx + x3 - x2
            let by3 = by + y3 - y2
            let bx1 = bx + x1 - x2
            let by1 = by + y1 - y2

            var main = Path()
            main.move(to: CGPoint(x: x2, y: y2))
            main.addLine(to: CGPoint(x: x3, y: y3))
            main.addLine(to: CGPoint(x: bx3, y: by3))
            main.addLine(to: CGPoint(x: bx, y: by))
            main.addLine(to: CGPoint(x: bx1, y: by1))
            main.addLine(to: CGPoint(x: x1, y: y1))
            main.closeSubpath()

            let sx = -cosA * shadowOffset
            let sy = -sinA * shadowOffset
            var shadow = Path()
            shadow.move(to: CGPoint(x: x2 + sx, y: y2 + sy))
            shadow.addLine(to: CGPoint(x: x3 + sx, y: y3 + sy))
            shadow.addLine(to: CGPoint(x: bx3 + sx, y: by3 + sy))
            shadow.addLine(to: CGPoint(x: bx + sx, y: by + sy))
            shadow.addLine(to: CGPoint(x: bx1 + sx, y: by1 + sy))
            shadow.addLine(to: CGPoint(x: x1 + sx, y: y1 + sy))
            shadow.closeSubpath()

            var leftHalf = Path()
            leftHalf.move(to: CGPoint(x: x2, y: y2))
            leftHalf.addLine(to: CGPoint(x: bx, y: by))
            leftHalf.addLine(to: CGPoint(x: bx3, y: by3))
            leftHalf.addLine(to: CGPoint(x: x3, y: y3))
            leftHalf.closeSubpath()

            result.append(ArrowPaths(main: main, shadow: shadow, leftHalf: leftHalf))
        }
        return result
    }

    /// Point where the two arrow flanks meet the bisector, `width` away from the chevron corner.
    ///
    /// The bisector length is `width / sin(halfAngle)`, which makes the stroke gap look constant
    /// for every arrow orientation. Degenerate arms (zero length, or flanks almost parallel) are
    /// rejected instead of producing a NaN polygon.
    private func bisectorPoint(_ x1: Double, _ y1: Double, _ x2: Double, _ y2: Double,
                               _ x3: Double, _ y3: Double,
                               _ width: Double) -> (x: Double, y: Double)? {
        let rLen = hypot(x1 - x2, y1 - y2)
        let lLen = hypot(x3 - x2, y3 - y2)
        if rLen == 0 || lLen == 0 { return nil }
        let cosAngle = max(
            -1,
            min(1, ((x1 - x2) * (x3 - x2) + (y1 - y2) * (y3 - y2)) / (rLen * lLen))
        )
        let sinHalf = ((1 - cosAngle) / 2).squareRoot()
        if sinHalf <= 0.001 { return nil }
        let midX = (x1 + x3) / 2
        let midY = (y1 + y3) / 2
        let bLen = hypot(midX - x2, midY - y2)
        let bux = (midX - x2) / bLen
        let buy = (midY - y2) / bLen
        let edgeLen = width / sinHalf
        return (x2 + bux * edgeLen, y2 + buy * edgeLen)
    }

    // MARK: - Wi‑Fi fan

    /// Wi‑Fi fan of one segment: filled chevrons plus their translated shadow.
    ///
    /// The reference builds a single `Path` holding every chevron and draws it twice: once shifted
    /// by the shadow offset in 40%-black, then at full color. Keeping all chevrons in one path is
    /// what puts every shadow below every body — per-chevron draws would let a chevron's shadow
    /// cover its neighbour's arm.
    private func renderWifiBars(_ segment: SlideSegment, progress: Double, color: ChartColor,
                                alpha: Double) {
        guard let fan = SlideBars.wifiFan(
            segment: segment, progress: progress, radius: context.radius,
            centerX: context.centerX, centerY: context.centerY,
            mirrorMode: context.config.mirrorMode
        ) else { return }
        let fanPath = SlideBars.wifiPath(fan)
        let canvas = context.canvas
        let shadowOffset = Double(scaleByRadius(5.0 / 300.0))
        let shadowDx = CGFloat(-cos(fan.fanAngle) * shadowOffset)
        let shadowDy = CGFloat(-sin(fan.fanAngle) * shadowOffset)
        canvas.withTranslation(shadowDx, shadowDy) { inner in
            applyColor(&fillPaint, ChartConstants.Colors.black, alpha: alpha * 0.4)
            inner.drawPath(fanPath, fillPaint)
        }
        applyColor(&fillPaint, color, alpha: alpha)
        canvas.drawPath(fanPath, fillPaint)
    }

    // MARK: - Arc length helpers

    private func segmentLength(_ segment: SlideSegment) -> Double {
        SlideBars.segmentLength(segment: segment, radius: context.radius, centerX: context.centerX,
                                centerY: context.centerY, mirrorMode: context.config.mirrorMode)
    }

    private func pointAlongPath(_ progress: Double, _ segments: [SlideSegment]) -> ChartPoint {
        SlideBars.pointAlongPath(progress: progress, segments: segments, radius: context.radius,
                                 centerX: context.centerX, centerY: context.centerY,
                                 mirrorMode: context.config.mirrorMode)
    }

    private func pathTangentAngle(_ progress: Double, _ segments: [SlideSegment]) -> Double {
        SlideBars.pathTangentAngle(progress: progress, segments: segments, radius: context.radius,
                                   centerX: context.centerX, centerY: context.centerY,
                                   mirrorMode: context.config.mirrorMode)
    }

    // MARK: - Stars

    /// Moving/fading star head of a normal (non-Wi‑Fi) slide path.
    ///
    /// Before `slideStart` the head waits on the start button, fading and scaling up by the delay
    /// progress; `headlessMode == "pop"` heads (the `!` marker) stay hidden until the slide begins.
    private func renderSlideStar(_ note: ChartNote, progress: Double, segments: [SlideSegment],
                                 pathIndex: Int, currentTimeMs: Double, isSimultaneous: Bool) {
        guard let firstSegment = segments.first else { return }
        if firstSegment.type == .wifi {
            renderWifiStars(note, progress: progress, segments: segments, pathIndex: pathIndex,
                            isSimultaneous: isSimultaneous, currentTimeMs: currentTimeMs)
            return
        }

        let delayMs = value(note.allDelayMs, at: pathIndex) ?? note.delayMs
        let slideStart = note.timingMs + delayMs

        let starPos: ChartPoint
        var starAlpha = 1.0
        var starScale = 1.0
        var rotation = 0.0
        let isSliding = currentTimeMs >= slideStart

        if !isSliding {
            if note.headlessMode == "pop" { return }
            starPos = notePainter.positionOnRing(firstSegment.startPos)
            let elapsed = currentTimeMs - note.timingMs
            let waitingProgress = min(1, elapsed / delayMs)
            starAlpha = waitingProgress
            starScale = ChartConstants.slideStarWaitingMinScale
                + (1 - ChartConstants.slideStarWaitingMinScale) * waitingProgress
            if context.config.slideRotation {
                rotation = pathTangentAngle(0, segments) + Double.pi / 2
            }
        } else {
            starPos = pointAlongPath(progress, segments)
            if context.config.slideRotation {
                rotation = pathTangentAngle(progress, segments) + Double.pi / 2
            }
        }

        let size = Double(context.radius) * ChartConstants.slideStarSizeRatio * starScale
        let isBreak = (value(note.allSlideBreaks, at: pathIndex) ?? false)
            && !context.config.normalColorBreakSlide
        let color: ChartColor
        if isBreak {
            color = ChartConstants.Colors.breakOrange
        } else if isSimultaneous {
            color = ChartConstants.Colors.slideSimultaneous
        } else if context.config.pinkSlideStart {
            color = ChartConstants.Colors.slidePink
        } else {
            color = ChartConstants.Colors.slideCyan
        }
        drawStar(x: starPos.x, y: starPos.y, size: size, color: color, rotation: rotation,
                 isEx: note.isEx, alpha: starAlpha)
    }

    /// Wi‑Fi stars: three heads leave the start button, one per fan endpoint, and slide along the
    /// straight chord to that endpoint. They are not path-sampled — the reference interpolates the
    /// chord directly, so the fan heads always stay in step with the drawn fan arms.
    private func renderWifiStars(_ note: ChartNote, progress: Double, segments: [SlideSegment],
                                 pathIndex: Int, isSimultaneous: Bool, currentTimeMs: Double) {
        guard let firstSegment = segments.first else { return }
        let delayMs = value(note.allDelayMs, at: pathIndex) ?? note.delayMs
        let slideStart = note.timingMs + delayMs
        let startPos = firstSegment.startPos
        let endPos = firstSegment.endPos
        // The fan spans the endpoint plus both neighbours; the 1-based arithmetic mirrors the
        // reference and wraps through the 8-button ring.
        let fanPositions = [
            endPos,
            ((endPos - 1 - 1 + 8) % 8) + 1,
            ((endPos - 1 + 1) % 8) + 1,
        ]

        for fanEnd in fanPositions {
            let start = notePainter.positionOnRing(startPos)
            let end = notePainter.positionOnRing(fanEnd)
            let direction = atan2(end.y - start.y, end.x - start.x)
            let rotation = context.config.slideRotation ? direction + Double.pi / 2 : 0

            let starPos: ChartPoint
            var starAlpha = 1.0
            var starScale = 1.0
            if currentTimeMs < slideStart {
                if note.headlessMode == "pop" { continue }
                starPos = start
                let elapsed = currentTimeMs - note.timingMs
                let waitingProgress = min(1, elapsed / delayMs)
                starAlpha = waitingProgress
                starScale = ChartConstants.slideStarWaitingMinScale
                    + (1 - ChartConstants.slideStarWaitingMinScale) * waitingProgress
            } else {
                starPos = ChartPoint(
                    start.x + (end.x - start.x) * progress,
                    start.y + (end.y - start.y) * progress
                )
            }

            let size = Double(context.radius) * ChartConstants.slideStarSizeRatio * starScale
            let isBreak = (value(note.allSlideBreaks, at: pathIndex) ?? false)
                && !context.config.normalColorBreakSlide
            let color: ChartColor
            if isBreak {
                color = ChartConstants.Colors.breakOrange
            } else if isSimultaneous {
                color = ChartConstants.Colors.slideSimultaneous
            } else if context.config.pinkSlideStart {
                color = ChartConstants.Colors.slidePink
            } else {
                color = ChartConstants.Colors.slideCyan
            }
            drawStar(x: starPos.x, y: starPos.y, size: size, color: color, rotation: rotation,
                     isEx: note.isEx, alpha: starAlpha)
        }
    }

    /// Five-pointed star head: black underlay, hollow colored ring, white edges, center dot.
    ///
    /// `alpha` exists for the fade-in of a waiting slide head; the renderer's slide-start pass always
    /// draws at the Kotlin default of `1f`, hence the defaulted parameter.
    func drawStar(x: Double, y: Double, size: Double, color: ChartColor, rotation: Double,
                  isEx: Bool, alpha: Double = 1) {
        let canvas = context.canvas
        canvas.withSave { inner in
            if rotation != 0 {
                inner.translate(CGFloat(x), CGFloat(y))
                inner.rotate(degrees: CGFloat(rotation * 180 / Double.pi), pivotX: 0, pivotY: 0)
                inner.translate(CGFloat(-x), CGFloat(-y))
            }
            let innerRadius = size * 0.5
            let innerHoleOuter = size * 0.55
            let innerHoleInner = innerRadius * 0.55
            let strokeW = noteStrokeWidth()

            func starPath(outer: Double, innerRadius: Double, baseAngle: Double) -> Path {
                var path = Path()
                for index in 0..<10 {
                    let angle = Double(index) * Double.pi / 5 + baseAngle
                    let radius = index % 2 == 0 ? outer : innerRadius
                    let px = x + cos(angle) * radius
                    let py = y + sin(angle) * radius
                    if index == 0 {
                        path.move(to: CGPoint(x: px, y: py))
                    } else {
                        path.addLine(to: CGPoint(x: px, y: py))
                    }
                }
                path.closeSubpath()
                return path
            }

            // EX heads skip the wide black outline of the outer star — the glow ring drawn behind
            // them provides that edge — while the inner hole keeps its black stroke.
            if !isEx {
                strokeSettings(strokeW * 3, .butt, .miter)
                applyColor(&strokePaint, ChartConstants.Colors.black, alpha: alpha)
                inner.drawPath(
                    starPath(outer: size, innerRadius: innerRadius, baseAngle: -Double.pi / 2),
                    strokePaint
                )
            }
            strokeSettings(strokeW * 3, .butt, .miter)
            applyColor(&strokePaint, ChartConstants.Colors.black, alpha: alpha)
            inner.drawPath(
                starPath(outer: innerHoleOuter, innerRadius: innerHoleInner, baseAngle: -Double.pi / 2),
                strokePaint
            )

            var ringPath = starPath(outer: size, innerRadius: innerRadius, baseAngle: -Double.pi / 2)
            ringPath.addPath(
                starPath(outer: innerHoleOuter, innerRadius: innerHoleInner, baseAngle: -Double.pi / 2)
            )
            applyColor(&fillPaint, color, alpha: alpha)
            // The Kotlin code sets `Path.FillType.EVEN_ODD` on the path; the shim carries that on
            // the paint, so it has to be cleared again before the center dot.
            fillPaint.evenOdd = true
            inner.drawPath(ringPath, fillPaint)
            fillPaint.evenOdd = false

            strokeSettings(strokeW, .butt, .miter)
            applyColor(&strokePaint, ChartConstants.Colors.white, alpha: alpha)
            inner.drawPath(
                starPath(outer: size, innerRadius: innerRadius, baseAngle: -Double.pi / 2),
                strokePaint
            )
            inner.drawPath(
                starPath(outer: innerHoleOuter, innerRadius: innerHoleInner, baseAngle: -Double.pi / 2),
                strokePaint
            )

            applyColor(&fillPaint, color, alpha: alpha)
            inner.drawCircle(centerX: x, centerY: y, radius: size * 0.15, fillPaint)
        }
    }

    /// EX outer glow star ring.
    func renderExStarRing(x: Double, y: Double, size: Double, isBreak: Bool, isSimultaneous: Bool,
                          exScale: Double) {
        let scale = 1.19 * exScale
        let outerRadius = size * scale
        let outerInner = size * 0.5 * scale
        let innerInner = size * 0.5
        var ringPath = exStarPath(x: x, y: y, outerRadius: outerRadius, innerRadius: outerInner,
                                  baseAngle: -Double.pi / 2)
        ringPath.addPath(
            exStarPath(x: x, y: y, outerRadius: size, innerRadius: innerInner,
                       baseAngle: -Double.pi / 2)
        )
        applyColor(&fillPaint, exStarRingColor(isBreak: isBreak, isSimultaneous: isSimultaneous))
        fillPaint.evenOdd = true
        context.canvas.drawPath(ringPath, fillPaint)
        fillPaint.evenOdd = false
    }

    /// Split-slide EX glow: two interleaved glowing star rings.
    func renderExSplitStarRing(x: Double, y: Double, size: Double, isBreak: Bool,
                               isSimultaneous: Bool, exScale: Double) {
        let scale = 1.19 * exScale
        let outerRadius = size * scale
        let outerInner = size * 0.5 * scale
        let innerInner = size * 0.5
        applyColor(&fillPaint, exStarRingColor(isBreak: isBreak, isSimultaneous: isSimultaneous))
        for baseAngle in [-Double.pi / 2, Double.pi / 2] {
            var ringPath = exStarPath(x: x, y: y, outerRadius: outerRadius,
                                      innerRadius: outerInner, baseAngle: baseAngle)
            ringPath.addPath(
                exStarPath(x: x, y: y, outerRadius: size, innerRadius: innerInner,
                           baseAngle: baseAngle)
            )
            fillPaint.evenOdd = true
            context.canvas.drawPath(ringPath, fillPaint)
        }
        fillPaint.evenOdd = false
    }

    /// Glow color of both EX rings: break, simultaneous, pink start, then the reference's cyan.
    private func exStarRingColor(isBreak: Bool, isSimultaneous: Bool) -> ChartColor {
        if isBreak { return ChartConstants.Colors.exOverlayBreak }
        if isSimultaneous { return ChartConstants.Colors.exOverlaySimultaneous }
        if context.config.pinkSlideStart { return ChartConstants.Colors.exOverlayNormal }
        return Self.exStarRingCyan
    }

    /// Glow star outline with an arbitrary base rotation (the split variant interleaves two of them).
    private func exStarPath(x: Double, y: Double, outerRadius: Double, innerRadius: Double,
                            baseAngle: Double) -> Path {
        var path = Path()
        for index in 0..<10 {
            let angle = Double(index) * Double.pi / 5 + baseAngle
            let radius = index % 2 == 0 ? outerRadius : innerRadius
            let px = x + cos(angle) * radius
            let py = y + sin(angle) * radius
            if index == 0 {
                path.move(to: CGPoint(x: px, y: py))
            } else {
                path.addLine(to: CGPoint(x: px, y: py))
            }
        }
        path.closeSubpath()
        return path
    }

    /// Star spin rate: proportional to normalized path length, capped at 1.08 deg/ms.
    ///
    /// The path length is normalized into the reference's 480-unit panel space before it is turned
    /// into a spin rate, so the same chart spins at the same speed regardless of the rendered ring
    /// radius. Spinning starts at the approach time, not at the slide time.
    func calculateStarRotation(_ note: ChartNote, currentTimeMs: Double) -> Double {
        let durationMs = note.allDurationMs.first ?? note.durationMs
        let segments = note.allSlideSegments.first ?? note.slideSegments
        if segments.isEmpty || durationMs == 0 { return 0 }
        var totalLengthPixels = 0.0
        for segment in segments {
            totalLengthPixels += segmentLength(segment)
        }
        let normalizedLength = totalLengthPixels
            * (ChartConstants.panelRadiusUnits / Double(context.radius))
        let rotationSpeedDegPerMs = normalizedLength / Double.pi / durationMs * 15 * 0.06
        let capped = min(rotationSpeedDegPerMs, 1.08)
        let rotationSpeedRadPerMs = -capped * (Double.pi / 180)
        let approachTime = noteApproachTimeMs(note.hiSpeed)
        let visibilityStart = note.timingMs - approachTime
        if currentTimeMs < visibilityStart { return 0 }
        let elapsedMs = currentTimeMs - visibilityStart
        return rotationSpeedRadPerMs * elapsedMs
    }

    // MARK: - Model helpers

    /// `List.getOrNull(index)` from the Kotlin model.
    ///
    /// The flat note keeps the first path's delay/duration in scalar fields, so those are the
    /// fallback when a split slide's arrays are shorter than the number of paths.
    private func value<T>(_ values: [T], at index: Int) -> T? {
        values.indices.contains(index) ? values[index] : nil
    }
}
