import CoreGraphics
import Foundation
import SwiftUI

/// Guide-arrow templates and slide path geometry for every shape `SlideGeometry.detectSlideShape`
/// can emit.
///
/// The Kotlin reference splits this material in two files. `core/chart/SlideBars.kt` owns the
/// template map (itself copied verbatim from the reference engine's `utils/slideBars.ts`), while
/// `render/SlidePainter.kt` owns the private geometry that consumes it: `getBarChain`,
/// `applyGameArrowAngles`, `getHiddenCount`, `getSegmentLength`, `getSegmentLut`,
/// `getPointOnSegment`, `sampleArcLut`, the Wi-Fi fan layout and the star-head path samplers. Both
/// halves are pure geometry with no canvas access, so this port gathers them into one statically
/// reachable namespace and leaves only drawing in `SlidePainter`.
///
/// Kotlin declares `object SlideBars`. Swift exposes the same stateless namespace as `static`
/// members instead of a shared instance: there is no state to share, and a `static let shared` of a
/// non-`Sendable` class would need `@unchecked Sendable` under strict concurrency.
///
/// Frame geometry (`radius`, `centerX`, `centerY`, `mirrorMode`) is passed explicitly because the
/// geometry is cached on the `SlideSegment`, not owned by the painter. All lengths are in canvas
/// points; all angles are radians in canvas space (Y down).
nonisolated final class SlideBars {
    // MARK: - Template lookup

    /// Swift form of Kotlin's `operator fun get(shape: String)` (`SlideBars[shape]`).
    static func template(_ shape: String) -> [SlideBar]? { shapes[shape] }

    // MARK: - Chunky disappearance steps

    /// Total number of guide arrows a shape draws: the last entry of its step table.
    static func arrowCount(for shape: String) -> Int {
        SlideGeometry.stepMap[shape]?.last ?? 0
    }

    /// Step-table value at `progress`, i.e. how many arrows are already gone.
    ///
    /// Mirrors the `floor(progress * (steps.size - 1))` indexing of `SlidePainter.getHiddenCount`:
    /// the table spans the whole slide, so its last entry is reached only at `progress == 1`.
    static func chunkyStep(shape: String, progress: Double) -> Int {
        guard let steps = SlideGeometry.stepMap[shape], steps.count >= 2 else { return 0 }
        return steps[stepIndex(progress, count: steps.count)]
    }

    /// Number of template arrows a segment already hides at `progress`.
    ///
    /// Wi-Fi fans hide one extra chevron as soon as the slide starts (`step + 1`), because the fan
    /// originates at the start button; every other shape hides whole arrow batches.
    static func hiddenCount(segment: SlideSegment, progress: Double) -> Int {
        guard let shape = detectShape(for: segment) else { return 0 }
        let hidden = chunkyStep(shape: shape.shape, progress: progress)
        if segment.type == .wifi && progress > 0 { return hidden + 1 }
        return hidden
    }

    // MARK: - Guide chain

    /// Transforms a shape template into canvas space: horizontal mirror, start-button rotation,
    /// viewport mirror. Cached on the segment and invalidated by radius or mirror-mode changes.
    ///
    /// Ported from `SlidePainter.getBarChain`. The template is authored against button 1, so the
    /// start rotation is `(startPos - 1) * π/4`; a mirrored template lands its base on button 8 and
    /// therefore skips that extra 45°.
    ///
    /// - Returns: `nil` when the segment has no shape template.
    static func barChain(segment: SlideSegment, radius: Float, centerX: Float, centerY: Float,
                         mirrorMode: MirrorMode) -> [SlideChainPoint]? {
        guard let shape = detectShape(for: segment),
              let bars = shapes[shape.shape],
              let firstBar = bars.first,
              let lastBar = bars.last
        else { return nil }

        if let cached = segment.cachedChain,
           segment.cachedChainRadius == radius,
           segment.cachedChainMirror == mirrorMode {
            return cached
        }

        let startRotation = Double(segment.startPos - (shape.mirror ? 0 : 1)) * Double.pi / 4
        let cosR = cos(startRotation)
        let sinR = sin(startRotation)
        let radiusValue = Double(radius)
        let centerXValue = Double(centerX)
        let centerYValue = Double(centerY)
        // Viewport mirroring flips the already rotated template, so it is applied after the
        // rotation as a sign on each axis.
        let sx = mirrorMode == .horizontal || mirrorMode == .rotate180 ? -1.0 : 1.0
        let sy = mirrorMode == .vertical || mirrorMode == .rotate180 ? -1.0 : 1.0

        let isCircle = shape.shape.hasPrefix("circle")
        let isLine = shape.shape.hasPrefix("line")

        // Line templates are re-projected onto the first→last segment below, so their endpoints in
        // template space are needed up front.
        let firstX = shape.mirror ? -firstBar.x : firstBar.x
        let firstY = firstBar.y
        let lastX = shape.mirror ? -lastBar.x : lastBar.x
        let lineDx = lastX - firstX
        let lineDy = lastBar.y - firstY
        let lineLen2Raw = lineDx * lineDx + lineDy * lineDy
        let lineLen2 = lineLen2Raw != 0 ? lineLen2Raw : 1.0

        var chain: [SlideChainPoint] = []
        chain.reserveCapacity(bars.count)
        for bar in bars {
            var bx = shape.mirror ? -bar.x : bar.x
            var by = bar.y
            if isCircle {
                // Circle templates snap onto a π/32 grid at unit radius so the closing seam and the
                // repeated arcs of `circle2...8` land exactly on the same points as `circle1`.
                let angle = atan2(by, bx)
                let snapped = Double(roundedToInt(angle * 32.0 / Double.pi)) * (Double.pi / 32.0)
                bx = cos(snapped)
                by = sin(snapped)
            } else if isLine {
                // Hand-authored line arrows drift sideways; projecting them onto the chord removes
                // the drift while keeping the spacing along the line.
                let t = ((bx - firstX) * lineDx + (by - firstY) * lineDy) / lineLen2
                bx = firstX + t * lineDx
                by = firstY + t * lineDy
            }
            // A mirrored template reflects the baked tangent angle as π - θ before the start
            // rotation, then the viewport mirror is applied to the final direction.
            let baked = (shape.mirror ? Double.pi - bar.r : bar.r) + startRotation
            chain.append(SlideChainPoint(
                x: centerXValue + sx * radiusValue * (bx * cosR - by * sinR),
                y: centerYValue + sy * radiusValue * (bx * sinR + by * cosR),
                angle: atan2(sy * sin(baked), sx * cos(baked))
            ))
        }

        let isCup = segment.type == .spiralClockwise
            || segment.type == .spiralCounterClockwise
            || segment.type == .spiralClockwiseDouble
            || segment.type == .spiralCounterClockwiseDouble
        if (isCup || isCircle) && chain.count >= 2 {
            // Cup spirals and arc shapes are large-curvature polylines; their template angles would
            // show visible kinks, so the tangents are smoothed along the bisector.
            applyGameArrowAngles(&chain)
        }

        segment.cachedChain = chain
        segment.cachedChainRadius = radius
        segment.cachedChainMirror = mirrorMode
        return chain
    }

    /// Replaces the template angles by the direction of the polyline itself, blended along the
    /// bisector. Ported from `SlidePainter.applyGameArrowAngles`; the sequential
    /// `chain[index - 1].angle` read is intentional (each angle blends with the already smoothed
    /// previous one).
    private static func applyGameArrowAngles(_ chain: inout [SlideChainPoint]) {
        let count = chain.count
        var segmentAngles = [Double](repeating: 0, count: count)
        for index in 0..<(count - 1) {
            segmentAngles[index] = atan2(chain[index + 1].y - chain[index].y,
                                         chain[index + 1].x - chain[index].x)
        }
        segmentAngles[count - 1] = segmentAngles[count - 2]
        chain[0].angle = segmentAngles[0]
        for index in 1..<(count - 1) {
            chain[index].angle = lerpAngle(chain[index - 1].angle, segmentAngles[index], 0.5)
        }
        chain[count - 1].angle = segmentAngles[count - 1]
    }

    // MARK: - Arc length lookup tables

    /// Total polyline length of a segment, cached on the segment (radius and mirror invalidate).
    static func segmentLength(segment: SlideSegment, radius: Float, centerX: Float, centerY: Float,
                              mirrorMode: MirrorMode) -> Double {
        if let cached = segment.cachedLength,
           segment.cachedRadius == radius,
           segment.cachedMirrorMode == mirrorMode {
            return cached
        }
        let lut = segmentLut(segment: segment, radius: radius, centerX: centerX, centerY: centerY,
                             mirrorMode: mirrorMode)
        return lut.last?.s ?? 0
    }

    /// Builds the 65-point arc length table (64 samples) that the track renderer walks to give a
    /// slide its timing-proportional draw progress.
    static func segmentLut(segment: SlideSegment, radius: Float, centerX: Float, centerY: Float,
                           mirrorMode: MirrorMode) -> [ArcLutPoint] {
        if let cached = segment.cachedLut,
           segment.cachedRadius == radius,
           segment.cachedMirrorMode == mirrorMode {
            return cached
        }
        let samples = 64
        var lut: [ArcLutPoint] = []
        lut.reserveCapacity(samples + 1)
        let p0 = pointOnSegment(segment: segment, t: 0, radius: radius, centerX: centerX,
                                centerY: centerY, mirrorMode: mirrorMode)
        let p1 = pointOnSegment(segment: segment, t: 1.0 / Double(samples), radius: radius,
                                centerX: centerX, centerY: centerY, mirrorMode: mirrorMode)
        lut.append(ArcLutPoint(x: p0.x, y: p0.y, angle: atan2(p1.y - p0.y, p1.x - p0.x), s: 0))
        var previous = p0
        var accumulated = 0.0
        for index in 1...samples {
            // Reuse the first sample: it was already computed for the initial tangent.
            let current = index == 1
                ? p1
                : pointOnSegment(segment: segment, t: Double(index) / Double(samples), radius: radius,
                                 centerX: centerX, centerY: centerY, mirrorMode: mirrorMode)
            let dx = current.x - previous.x
            let dy = current.y - previous.y
            accumulated += hypot(dx, dy)
            lut.append(ArcLutPoint(x: current.x, y: current.y, angle: atan2(dy, dx), s: accumulated))
            previous = current
        }
        segment.cachedLut = lut
        segment.cachedLength = lut[lut.count - 1].s
        segment.cachedRadius = radius
        segment.cachedMirrorMode = mirrorMode
        return lut
    }

    /// Looks up the table point at arc length `s`. The angle of the later sample is used, exactly as
    /// in the reference (`b.angle`), so the star head leads its quadrant.
    static func sampleArcLut(_ lut: [ArcLutPoint], s: Double) -> SlideChainPoint {
        // The reference assumes a non-empty table (`lut.first()`); the fallback keeps the helper
        // total without changing any in-range result.
        guard let first = lut.first, let last = lut.last else {
            return SlideChainPoint(x: 0, y: 0, angle: 0)
        }
        if s <= 0 { return SlideChainPoint(x: first.x, y: first.y, angle: first.angle) }
        if s >= last.s { return SlideChainPoint(x: last.x, y: last.y, angle: last.angle) }
        var lo = 0
        var hi = lut.count - 1
        while hi - lo > 1 {
            let mid = (lo + hi) / 2
            if lut[mid].s <= s { lo = mid } else { hi = mid }
        }
        let a = lut[lo]
        let b = lut[hi]
        let span = b.s - a.s
        let u = span > 0 ? (s - a.s) / span : 0
        return SlideChainPoint(x: a.x + (b.x - a.x) * u, y: a.y + (b.y - a.y) * u, angle: b.angle)
    }

    /// Point at normalized distance `t` along a segment: the guide chain with the two judgment-ring
    /// anchors prepended/appended, or the straight start→end chord when the shape has no chain.
    ///
    /// Ported from `SlidePainter.getPointOnSegment`; this is the path the arc length table samples.
    static func pointOnSegment(segment: SlideSegment, t: Double, radius: Float, centerX: Float,
                               centerY: Float, mirrorMode: MirrorMode) -> ChartPoint {
        let start = ringPosition(segment.startPos, radius: radius, centerX: centerX,
                                 centerY: centerY, mirrorMode: mirrorMode)
        let end = ringPosition(segment.endPos, radius: radius, centerX: centerX,
                               centerY: centerY, mirrorMode: mirrorMode)

        if let chain = barChain(segment: segment, radius: radius, centerX: centerX, centerY: centerY,
                                mirrorMode: mirrorMode),
           chain.count >= 2 {
            let count = chain.count + 2
            let index = max(0, min(Double(count - 1), t * Double(count - 1)))
            let base = min(count - 2, Int(index.rounded(.down)))
            let fraction = index - Double(base)
            func anchor(_ position: Int) -> ChartPoint {
                if position == 0 { return start }
                if position == count - 1 { return end }
                return ChartPoint(chain[position - 1].x, chain[position - 1].y)
            }
            let p0 = anchor(base)
            let p1 = anchor(base + 1)
            return ChartPoint(
                p0.x + (p1.x - p0.x) * fraction,
                p0.y + (p1.y - p0.y) * fraction
            )
        }
        return ChartPoint(
            start.x + (end.x - start.x) * t,
            start.y + (end.y - start.y) * t
        )
    }

    // MARK: - Star head path sampling

    /// Point at `progress` along a whole slide path, walking each segment's arc length table.
    ///
    /// Ported from `SlidePainter.pointAlongPath`; the slide star head rides this point.
    static func pointAlongPath(progress: Double, segments: [SlideSegment], radius: Float,
                               centerX: Float, centerY: Float,
                               mirrorMode: MirrorMode) -> ChartPoint {
        guard let firstSegment = segments.first else {
            return ChartPoint(Double(centerX), Double(centerY))
        }
        let luts = segments.map {
            segmentLut(segment: $0, radius: radius, centerX: centerX, centerY: centerY,
                       mirrorMode: mirrorMode)
        }
        let lengths = luts.map { $0.last?.s ?? 0 }
        let totalLength = lengths.reduce(0, +)
        if totalLength == 0 {
            return ringPosition(firstSegment.startPos, radius: radius, centerX: centerX,
                                centerY: centerY, mirrorMode: mirrorMode)
        }
        let target = progress * totalLength
        var cumulative = 0.0
        for index in segments.indices {
            if cumulative + lengths[index] >= target {
                let point = sampleArcLut(luts[index], s: target - cumulative)
                return ChartPoint(point.x, point.y)
            }
            cumulative += lengths[index]
        }
        return ringPosition(segments[segments.count - 1].endPos, radius: radius, centerX: centerX,
                            centerY: centerY, mirrorMode: mirrorMode)
    }

    /// Direction of the slide path at `progress`, used to spin the star head when the rotation
    /// option is on. Ported from `SlidePainter.pathTangentAngle`: the anchor points and the chain
    /// are concatenated into one polyline and the angle is interpolated by arc length.
    static func pathTangentAngle(progress: Double, segments: [SlideSegment], radius: Float,
                                 centerX: Float, centerY: Float, mirrorMode: MirrorMode) -> Double {
        if segments.isEmpty { return 0 }
        var total = 0.0
        var arcPositions: [Double] = []
        var angles: [Double] = []
        for segment in segments {
            guard let chain = barChain(segment: segment, radius: radius, centerX: centerX,
                                       centerY: centerY, mirrorMode: mirrorMode),
                  let firstChainPoint = chain.first,
                  let lastChainPoint = chain.last
            else { continue }
            let start = ringPosition(segment.startPos, radius: radius, centerX: centerX,
                                     centerY: centerY, mirrorMode: mirrorMode)
            let end = ringPosition(segment.endPos, radius: radius, centerX: centerX,
                                   centerY: centerY, mirrorMode: mirrorMode)
            var polyline: [SlideChainPoint] = []
            polyline.reserveCapacity(chain.count + 2)
            polyline.append(SlideChainPoint(x: start.x, y: start.y, angle: firstChainPoint.angle))
            polyline.append(contentsOf: chain)
            polyline.append(SlideChainPoint(x: end.x, y: end.y, angle: lastChainPoint.angle))
            var previous: ChartPoint?
            for point in polyline {
                if let previous {
                    total += hypot(point.x - previous.x, point.y - previous.y)
                }
                arcPositions.append(total)
                angles.append(point.angle)
                previous = ChartPoint(point.x, point.y)
            }
        }
        if angles.isEmpty { return 0 }
        if angles.count == 1 { return angles[0] }
        let target = max(0, min(1, progress)) * total
        for index in 0..<(arcPositions.count - 1) {
            if arcPositions[index] <= target && target <= arcPositions[index + 1] {
                let span = arcPositions[index + 1] - arcPositions[index]
                return lerpAngle(angles[index], angles[index + 1],
                                 span > 0 ? (target - arcPositions[index]) / span : 0)
            }
        }
        return angles[angles.count - 1]
    }

    // MARK: - Wi-Fi fan

    /// One fan chevron in canvas space before the fan rotation is applied to its arms: the corner
    /// point plus both arm deltas and the arm thickness.
    nonisolated struct WifiChevron: Sendable {
        var x: Double
        var y: Double
        var arm1Dx: Double
        var arm1Dy: Double
        var arm2Dx: Double
        var arm2Dy: Double
        var width: Double
    }

    /// A Wi-Fi fan: the spread axis angle plus the chevrons still visible at some progress.
    nonisolated struct WifiFan: Sendable {
        var fanAngle: Double
        var chevrons: [WifiChevron]
    }

    /// Lays out the Wi-Fi chevrons for a segment at `progress`.
    ///
    /// Ported from `SlidePainter.renderWifiBars`. Chevrons are spaced along the start→end ring
    /// chord by `ChartConstants.slideWifiCornerFracs`, so the table also fixes how many of the 11
    /// chevrons the fan can ever show; the arms grow from the start button outwards (`t`).
    ///
    /// - Returns: `nil` when the fan would be empty (coincident buttons or nothing visible).
    static func wifiFan(segment: SlideSegment, progress: Double, radius: Float, centerX: Float,
                        centerY: Float, mirrorMode: MirrorMode) -> WifiFan? {
        guard let steps = SlideGeometry.stepMap["wifi"], let count = steps.last else { return nil }
        let hiddenCount = hiddenCount(segment: segment, progress: progress)
        let pivot = ringPosition(segment.startPos, radius: radius, centerX: centerX, centerY: centerY,
                                 mirrorMode: mirrorMode)
        let endPivot = ringPosition(segment.endPos, radius: radius, centerX: centerX, centerY: centerY,
                                    mirrorMode: mirrorMode)
        let axisLen = hypot(endPivot.x - pivot.x, endPivot.y - pivot.y)
        if axisLen == 0 { return nil }
        let axisUx = (endPivot.x - pivot.x) / axisLen
        let axisUy = (endPivot.y - pivot.y) / axisLen
        let fanAngle = atan2(axisUy, axisUx)

        let armHalfAngle = 67.4 * Double.pi / 180
        let cosA = cos(armHalfAngle)
        let sinA = sin(armHalfAngle)
        // The fan spans 7.5%...97.5% of the chord so the outermost chevron clears both buttons.
        let dFirst = 0.075 * axisLen
        let dLast = 0.975 * axisLen
        let startExtra = scaleByRadius(0.075, radius: radius)

        var chevrons: [WifiChevron] = []
        // Outermost chevron first, exactly like the reference's `count - 1 downTo hiddenCount`.
        for index in stride(from: count - 1, through: hiddenCount, by: -1) {
            guard index >= 0, index < ChartConstants.slideWifiCornerFracs.count else { continue }
            let t = Double(index) / Double(count - 1)
            let d = dFirst + (dLast - dFirst) * ChartConstants.slideWifiCornerFracs[index]
            let armLen = cosA * d + startExtra * (1 - t)
            let width = scaleByRadius(
                ChartConstants.slideWifiLineWidthRatio * (0.7 + 0.3 * t),
                radius: radius
            )
            chevrons.append(WifiChevron(
                x: pivot.x + axisUx * d,
                y: pivot.y + axisUy * d,
                arm1Dx: -armLen * cosA,
                arm1Dy: armLen * sinA,
                arm2Dx: -armLen * cosA,
                arm2Dy: -armLen * sinA,
                width: width
            ))
        }
        if chevrons.isEmpty { return nil }
        return WifiFan(fanAngle: fanAngle, chevrons: chevrons)
    }

    /// Builds the filled fan outline: every chevron is rotated by the fan angle and appended as its
    /// own subpath, so the painter can fill them in one pass (body plus a translated shadow copy).
    ///
    /// Ported from the path building of `SlidePainter.drawWifiChevronsBatch` plus `buildChevronPath`.
    static func wifiPath(_ fan: WifiFan) -> Path {
        var path = Path()
        let cosA = cos(fan.fanAngle)
        let sinA = sin(fan.fanAngle)
        for chevron in fan.chevrons {
            let arm1X = chevron.x + cosA * chevron.arm1Dx - sinA * chevron.arm1Dy
            let arm1Y = chevron.y + sinA * chevron.arm1Dx + cosA * chevron.arm1Dy
            let arm2X = chevron.x + cosA * chevron.arm2Dx - sinA * chevron.arm2Dy
            let arm2Y = chevron.y + sinA * chevron.arm2Dx + cosA * chevron.arm2Dy
            appendChevron(to: &path, cornerX: chevron.x, cornerY: chevron.y,
                          arm1X: arm1X, arm1Y: arm1Y, arm2X: arm2X, arm2Y: arm2Y,
                          width: chevron.width, cosA: cosA, sinA: sinA)
        }
        return path
    }

    /// Appends one chevron: the outer V and an inner offset V that gives the arm its thickness.
    /// The inner outline is the outer one scaled about the point where the two arms meet the fan
    /// axis, which keeps the bevel symmetric for every fan angle.
    private static func appendChevron(to path: inout Path, cornerX: Double, cornerY: Double,
                                      arm1X: Double, arm1Y: Double, arm2X: Double, arm2Y: Double,
                                      width: Double, cosA: Double, sinA: Double) {
        let arm1Dx = arm1X - cornerX
        let arm1Dy = arm1Y - cornerY
        let arm2Dx = arm2X - cornerX
        let arm2Dy = arm2Y - cornerY
        let arm1Len = hypot(arm1Dx, arm1Dy)
        let arm2Len = hypot(arm2Dx, arm2Dy)
        if arm1Len == 0 || arm2Len == 0 { return }
        let cosAngle = max(-1, min(1, (arm1Dx * arm2Dx + arm1Dy * arm2Dy) / (arm1Len * arm2Len)))
        let sinHalf = ((1 - cosAngle) / 2).squareRoot()
        if sinHalf <= 0.001 { return }
        let armAxisProjection = -((arm1Dx * cosA + arm1Dy * sinA) / arm1Len)
        if armAxisProjection <= 0 { return }
        let outerDistance = arm1Len / armAxisProjection
        let innerDistance = max(0, outerDistance - width / sinHalf)
        let innerScale = innerDistance / outerDistance
        let pivotX = cornerX - cosA * outerDistance
        let pivotY = cornerY - sinA * outerDistance
        let innerCornerX = pivotX + (cornerX - pivotX) * innerScale
        let innerCornerY = pivotY + (cornerY - pivotY) * innerScale
        let innerArm1X = pivotX + (arm1X - pivotX) * innerScale
        let innerArm1Y = pivotY + (arm1Y - pivotY) * innerScale
        let innerArm2X = pivotX + (arm2X - pivotX) * innerScale
        let innerArm2Y = pivotY + (arm2Y - pivotY) * innerScale

        path.move(to: CGPoint(x: arm1X, y: arm1Y))
        path.addLine(to: CGPoint(x: cornerX, y: cornerY))
        path.addLine(to: CGPoint(x: arm2X, y: arm2Y))
        path.addLine(to: CGPoint(x: innerArm2X, y: innerArm2Y))
        path.addLine(to: CGPoint(x: innerCornerX, y: innerCornerY))
        path.addLine(to: CGPoint(x: innerArm1X, y: innerArm1Y))
        path.closeSubpath()
    }

    // MARK: - Geometry helpers

    /// Shape key + mirror flag for a segment, the input of every template lookup.
    private static func detectShape(for segment: SlideSegment) -> SlideGeometry.SlideShape? {
        SlideGeometry.detectSlideShape(type: segment.type, startPos: segment.startPos,
                                       endPos: segment.endPos, midPos: segment.midPos)
    }

    /// Step-table index for `progress`, clamped into the table. The clamp only guards against
    /// rounding at the table ends and against non-finite progress; in range it is exactly
    /// `floor(progress * (count - 1))`.
    private static func stepIndex(_ progress: Double, count: Int) -> Int {
        guard count > 0, progress.isFinite else { return 0 }
        let raw = (progress * Double(count - 1)).rounded(.down)
        return Int(min(Double(count - 1), max(0, raw)))
    }

    /// `kotlin.math.roundToInt` rounds halves towards positive infinity (`Math.round`), which is
    /// what the reference's π/32 snapping uses; Swift's `rounded()` would round halves away from
    /// zero and could move a boundary arrow by one grid step.
    private static func roundedToInt(_ value: Double) -> Int {
        Int((value + 0.5).rounded(.down))
    }

    /// Shortest signed angle difference in (-π, π], matching `BasePainter.angleDelta`.
    private static func angleDelta(_ a: Double, _ b: Double) -> Double {
        var delta = (b - a).truncatingRemainder(dividingBy: Double.pi * 2)
        if delta > Double.pi { delta -= Double.pi * 2 }
        if delta < -Double.pi { delta += Double.pi * 2 }
        return delta
    }

    /// Interpolates along the shortest arc, matching `BasePainter.lerpAngle`.
    private static func lerpAngle(_ a: Double, _ b: Double, _ t: Double) -> Double {
        a + angleDelta(a, b) * t
    }

    /// `scaleByRadius` rounds through `Float` in the reference (`(ratio * radius).toFloat()`),
    /// which matters for the fan arm widths at large radii; the conversion is kept here so the
    /// chevron polygons match the Kotlin samples bit for bit.
    private static func scaleByRadius(_ ratio: Double, radius: Float) -> Double {
        Double(Float(ratio * Double(radius)))
    }

    /// Canvas-space position of a judgment-ring button, already mirrored by the viewport mode.
    /// Equivalent to `BasePainter.buttonPosition` / `NotePainter.getPositionOnRing`.
    private static func ringPosition(_ position: Int, radius: Float, centerX: Float, centerY: Float,
                                     mirrorMode: MirrorMode) -> ChartPoint {
        let angle = ChartConstants.baseAngle + ChartConstants.buttonAngleOffset
            + Double(mirrorPosition(position, mirrorMode: mirrorMode) - 1)
            * ChartConstants.buttonAngleStep
        return ChartPoint(
            Double(centerX) + cos(angle) * Double(radius),
            Double(centerY) + sin(angle) * Double(radius)
        )
    }

    /// Same button mirror tables as `BasePainter.mirrorPosition`; replicated because this module is
    /// static and has no painter instance to borrow them from.
    private static func mirrorPosition(_ position: Int, mirrorMode: MirrorMode) -> Int {
        guard mirrorMode != .none, (0...8).contains(position) else { return position }
        let mirrorH = [0, 8, 7, 6, 5, 4, 3, 2, 1]
        let mirrorV = [0, 4, 3, 2, 1, 8, 7, 6, 5]
        let rotate180 = [0, 5, 6, 7, 8, 1, 2, 3, 4]
        switch mirrorMode {
        case .horizontal: return mirrorH[position]
        case .vertical: return mirrorV[position]
        case .rotate180: return rotate180[position]
        case .none: return position
        }
    }

    // MARK: - Shape templates

    /// Guide-arrow templates for every shape, in unit-circle coordinates (center 0, judgment ring
    /// radius 1, canvas Y down), copied verbatim from `SlideBars.kt` / `utils/slideBars.ts`.
    ///
    /// `SlideBar.r` is the arrow's baked tangent angle in radians, not a radius. Never round these
    /// values: they are the reference artwork, and changing them moves arced arrows off the
    /// template. Points are authored against button 1; the start-button rotation and the mirrored
    /// variants (negative x, `π - r` tangent) are applied by `barChain`, so no shape needs a
    /// separate mirrored table.
    static let shapes: [String: [SlideBar]] = [
        "L2": [
            slideBar(0.292, -0.8865, 2.74889),
            slideBar(0.2014, -0.84898, 2.74889),
            slideBar(0.1107, -0.81141, 2.74889),
            slideBar(0.01999, -0.77383, 2.74889),
            slideBar(-0.07067, -0.73628, 2.74889),
            slideBar(-0.16136, -0.69871, 2.74889),
            slideBar(-0.25202, -0.66116, 2.74889),
            slideBar(-0.34269, -0.62361, 2.74889),
            slideBar(-0.43339, -0.58604, 2.74889),
            slideBar(-0.52405, -0.54848, 2.74889),
            slideBar(-0.61471, -0.51093, 2.74889),
            slideBar(-0.70538, -0.47338, 2.74889),
            slideBar(-0.79607, -0.43581, 2.74889),
            slideBar(-0.88674, -0.39826, 2.74889),
            slideBar(-0.86634, -0.38278, 0.0),
            slideBar(-0.76813, -0.38278, 0.0),
            slideBar(-0.67005, -0.38278, 0.0),
            slideBar(-0.57187, -0.38278, 0.0),
            slideBar(-0.47372, -0.38278, 0.0),
            slideBar(-0.37554, -0.38278, 0.0),
            slideBar(-0.27749, -0.38278, 0.0),
            slideBar(-0.17934, -0.38278, 0.0),
            slideBar(-0.08115, -0.38278, 0.0),
            slideBar(0.01695, -0.38278, 0.0),
            slideBar(0.1151, -0.38278, 0.0),
            slideBar(0.21326, -0.38278, 0.0),
            slideBar(0.31145, -0.38278, 0.0),
            slideBar(0.40959, -0.38278, 0.0),
            slideBar(0.5077, -0.38278, 0.0),
            slideBar(0.60586, -0.38278, 0.0),
            slideBar(0.70403, -0.38278, 0.0),
            slideBar(0.80216, -0.38278, 0.0),
        ],
        "L3": [
            slideBar(0.292, -0.8865, 2.74889),
            slideBar(0.2014, -0.84898, 2.74889),
            slideBar(0.1107, -0.81141, 2.74889),
            slideBar(0.01999, -0.77383, 2.74889),
            slideBar(-0.07067, -0.73628, 2.74889),
            slideBar(-0.16136, -0.69871, 2.74889),
            slideBar(-0.25202, -0.66116, 2.74889),
            slideBar(-0.34269, -0.62361, 2.74889),
            slideBar(-0.43339, -0.58604, 2.74889),
            slideBar(-0.52405, -0.54848, 2.74889),
            slideBar(-0.61471, -0.51093, 2.74889),
            slideBar(-0.70538, -0.47338, 2.74889),
            slideBar(-0.79607, -0.43581, 2.74889),
            slideBar(-0.88674, -0.39826, 2.74889),
            slideBar(-0.87065, -0.36064, 0.3927),
            slideBar(-0.77997, -0.32307, 0.3927),
            slideBar(-0.68929, -0.28552, 0.3927),
            slideBar(-0.59862, -0.24796, 0.3927),
            slideBar(-0.50799, -0.21042, 0.3927),
            slideBar(-0.41733, -0.17286, 0.3927),
            slideBar(-0.32666, -0.13531, 0.3927),
            slideBar(-0.23595, -0.09773, 0.3927),
            slideBar(-0.14527, -0.06017, 0.3927),
            slideBar(-0.05466, -0.02264, 0.3927),
            slideBar(0.03608, 0.01494, 0.3927),
            slideBar(0.12675, 0.0525, 0.3927),
            slideBar(0.21737, 0.09004, 0.3927),
            slideBar(0.30804, 0.12759, 0.3927),
            slideBar(0.39876, 0.16517, 0.3927),
            slideBar(0.4894, 0.20272, 0.3927),
            slideBar(0.58011, 0.24029, 0.3927),
            slideBar(0.67074, 0.27783, 0.3927),
            slideBar(0.76143, 0.3154, 0.3927),
            slideBar(0.85211, 0.35296, 0.3927),
        ],
        "L4": [
            slideBar(0.292, -0.8865, 2.74889),
            slideBar(0.2014, -0.84898, 2.74889),
            slideBar(0.1107, -0.81141, 2.74889),
            slideBar(0.01999, -0.77383, 2.74889),
            slideBar(-0.07067, -0.73628, 2.74889),
            slideBar(-0.16136, -0.69871, 2.74889),
            slideBar(-0.25202, -0.66116, 2.74889),
            slideBar(-0.34269, -0.62361, 2.74889),
            slideBar(-0.43339, -0.58604, 2.74889),
            slideBar(-0.52405, -0.54848, 2.74889),
            slideBar(-0.61471, -0.51093, 2.74889),
            slideBar(-0.70538, -0.47338, 2.74889),
            slideBar(-0.79607, -0.43581, 2.74889),
            slideBar(-0.88674, -0.39826, 2.74889),
            slideBar(-0.88326, -0.34193, 0.7854),
            slideBar(-0.81381, -0.27249, 0.7854),
            slideBar(-0.74446, -0.20313, 0.7854),
            slideBar(-0.67503, -0.1337, 0.7854),
            slideBar(-0.60563, -0.0643, 0.7854),
            slideBar(-0.53621, 0.00512, 0.7854),
            slideBar(-0.46688, 0.07445, 0.7854),
            slideBar(-0.39748, 0.14386, 0.7854),
            slideBar(-0.32804, 0.21329, 0.7854),
            slideBar(-0.25868, 0.28266, 0.7854),
            slideBar(-0.18928, 0.35206, 0.7854),
            slideBar(-0.11986, 0.42147, 0.7854),
            slideBar(-0.05044, 0.4909, 0.7854),
            slideBar(0.01896, 0.5603, 0.7854),
            slideBar(0.08834, 0.62968, 0.7854),
            slideBar(0.15774, 0.69908, 0.7854),
            slideBar(0.22713, 0.76847, 0.7854),
            slideBar(0.29653, 0.83787, 0.7854),
        ],
        "L5": [
            slideBar(0.292, -0.8865, 2.74889),
            slideBar(0.2014, -0.84898, 2.74889),
            slideBar(0.1107, -0.81141, 2.74889),
            slideBar(0.01999, -0.77383, 2.74889),
            slideBar(-0.07067, -0.73628, 2.74889),
            slideBar(-0.16136, -0.69871, 2.74889),
            slideBar(-0.25202, -0.66116, 2.74889),
            slideBar(-0.34269, -0.62361, 2.74889),
            slideBar(-0.43339, -0.58604, 2.74889),
            slideBar(-0.52405, -0.54848, 2.74889),
            slideBar(-0.61471, -0.51093, 2.74889),
            slideBar(-0.70538, -0.47338, 2.74889),
            slideBar(-0.79607, -0.43581, 2.74889),
            slideBar(-0.88674, -0.39826, 2.74889),
            slideBar(-0.90201, -0.32944, 1.1781),
            slideBar(-0.86444, -0.23874, 1.1781),
            slideBar(-0.8269, -0.14811, 1.1781),
            slideBar(-0.78932, -0.05737, 1.1781),
            slideBar(-0.75176, 0.0333, 1.1781),
            slideBar(-0.71419, 0.124, 1.1781),
            slideBar(-0.67665, 0.21464, 1.1781),
            slideBar(-0.63909, 0.30531, 1.1781),
            slideBar(-0.60153, 0.39599, 1.1781),
            slideBar(-0.56398, 0.48664, 1.1781),
            slideBar(-0.5264, 0.57736, 1.1781),
            slideBar(-0.48886, 0.66801, 1.1781),
            slideBar(-0.4513, 0.75867, 1.1781),
            slideBar(-0.41374, 0.84936, 1.1781),
        ],
        "circle1": [
            slideBar(0.46651, -0.87424, 3.63894),
            slideBar(0.55033, -0.82788, 3.70525),
            slideBar(0.62578, -0.76812, 3.8129),
            slideBar(0.70018, -0.7039, 3.88938),
            slideBar(0.76454, -0.63167, 3.99761),
            slideBar(0.82312, -0.55157, 4.1007),
            slideBar(0.87589, -0.46941, 7.32073),
            slideBar(0.91393, -0.38056, 4.2904),
            slideBar(0.94895, -0.28955, 7.52808),
            slideBar(0.97221, -0.19646, 4.46134),
            slideBar(0.98621, -0.09955, 7.72532),
            slideBar(0.99407, 0.00083, 4.67848),
            slideBar(0.98845, 0.09741, 4.74852),
            slideBar(0.97296, 0.19182, 8.01223),
            slideBar(0.9507, 0.2881, 4.94874),
            slideBar(0.91733, 0.37813, 1.91766),
            slideBar(0.87507, 0.46754, 5.15858),
            slideBar(0.82353, 0.55199, 8.39868),
            slideBar(0.76461, 0.63013, 5.35394),
            slideBar(0.70161, 0.70413, 5.44276),
            slideBar(0.62888, 0.76786, 8.68718),
            slideBar(0.55033, 0.82754, 5.647),
            slideBar(0.467, 0.87508, 5.73711),
            slideBar(0.37787, 0.91891, 5.84727),
            slideBar(0.28665, 0.95082, 9.09177),
            slideBar(0.19252, 0.97159, 6.05867),
            slideBar(0.09569, 0.98646, 6.18272),
            slideBar(-0.00427, 0.99243, 6.24423),
            slideBar(-0.10051, 0.99053, 9.46923),
            slideBar(-0.19538, 0.97607, 9.54903),
            slideBar(-0.29038, 0.95029, 6.52563),
            slideBar(-0.37963, 0.9194, 6.59967),
            slideBar(-0.46734, 0.87465, 3.59472),
            slideBar(-0.55054, 0.82664, 3.67709),
            slideBar(-0.62815, 0.7691, 3.80042),
            slideBar(-0.70059, 0.70349, 3.88938),
            slideBar(-0.76579, 0.63084, 3.99761),
            slideBar(-0.82519, 0.55116, 4.08192),
            slideBar(-0.87362, 0.46941, 4.20673),
            slideBar(-0.91667, 0.38, 4.2904),
            slideBar(-0.94916, 0.28934, 4.38507),
            slideBar(-0.97338, 0.19372, 4.46134),
            slideBar(-0.98807, 0.0981, 4.585),
            slideBar(-0.99283, -0.00021, 4.67848),
            slideBar(-0.98828, -0.09914, 4.74852),
            slideBar(-0.9742, -0.19269, 4.8458),
            slideBar(-0.9502, -0.28976, 4.94874),
            slideBar(-0.91667, -0.37896, 5.04254),
            slideBar(-0.87631, -0.4661, 5.15858),
            slideBar(-0.82498, -0.55344, 5.2618),
            slideBar(-0.76724, -0.63126, 5.35394),
            slideBar(-0.70245, -0.70432, 5.44126),
            slideBar(-0.63084, -0.76951, 5.53465),
            slideBar(-0.55178, -0.82705, 5.647),
            slideBar(-0.46547, -0.87589, 5.73711),
            slideBar(-0.38, -0.91812, 5.84727),
            slideBar(-0.28727, -0.95082, 5.96344),
            slideBar(-0.1931, -0.97358, 6.05867),
            slideBar(-0.09624, -0.98704, 6.14907),
            slideBar(0.00186, -0.99387, 6.24423),
            slideBar(0.09872, -0.98911, 6.31308),
            slideBar(0.19517, -0.97462, 6.41471),
            slideBar(0.291, -0.94978, 6.52558),
        ],
        "circle2": [
            slideBar(0.46651, -0.87424, 3.63894),
            slideBar(0.55033, -0.82788, 3.70525),
            slideBar(0.62578, -0.76812, 3.8129),
            slideBar(0.70018, -0.7039, 3.88938),
            slideBar(0.76454, -0.63167, 3.99761),
            slideBar(0.82312, -0.55157, 4.1007),
            slideBar(0.87589, -0.46941, 7.32073),
        ],
        "circle3": [
            slideBar(0.46651, -0.87424, 3.63894),
            slideBar(0.55033, -0.82788, 3.70525),
            slideBar(0.62578, -0.76812, 3.8129),
            slideBar(0.70018, -0.7039, 3.88938),
            slideBar(0.76454, -0.63167, 3.99761),
            slideBar(0.82312, -0.55157, 4.1007),
            slideBar(0.87589, -0.46941, 7.32073),
            slideBar(0.91393, -0.38056, 4.2904),
            slideBar(0.94895, -0.28955, 7.52808),
            slideBar(0.97221, -0.19646, 4.46134),
            slideBar(0.98621, -0.09955, 7.72532),
            slideBar(0.99407, 0.00083, 4.67848),
            slideBar(0.98845, 0.09741, 4.74852),
            slideBar(0.97296, 0.19182, 8.01223),
            slideBar(0.9507, 0.2881, 4.94874),
        ],
        "circle4": [
            slideBar(0.46651, -0.87424, 3.63894),
            slideBar(0.55033, -0.82788, 3.70525),
            slideBar(0.62578, -0.76812, 3.8129),
            slideBar(0.70018, -0.7039, 3.88938),
            slideBar(0.76454, -0.63167, 3.99761),
            slideBar(0.82312, -0.55157, 4.1007),
            slideBar(0.87589, -0.46941, 7.32073),
            slideBar(0.91393, -0.38056, 4.2904),
            slideBar(0.94895, -0.28955, 7.52808),
            slideBar(0.97221, -0.19646, 4.46134),
            slideBar(0.98621, -0.09955, 7.72532),
            slideBar(0.99407, 0.00083, 4.67848),
            slideBar(0.98845, 0.09741, 4.74852),
            slideBar(0.97296, 0.19182, 8.01223),
            slideBar(0.9507, 0.2881, 4.94874),
            slideBar(0.91733, 0.37813, 1.91766),
            slideBar(0.87507, 0.46754, 5.15858),
            slideBar(0.82353, 0.55199, 8.39868),
            slideBar(0.76461, 0.63013, 5.35394),
            slideBar(0.70161, 0.70413, 5.44276),
            slideBar(0.62888, 0.76786, 8.68718),
            slideBar(0.55033, 0.82754, 5.647),
            slideBar(0.467, 0.87508, 5.73711),
        ],
        "circle5": [
            slideBar(0.46651, -0.87424, 3.63894),
            slideBar(0.55033, -0.82788, 3.70525),
            slideBar(0.62578, -0.76812, 3.8129),
            slideBar(0.70018, -0.7039, 3.88938),
            slideBar(0.76454, -0.63167, 3.99761),
            slideBar(0.82312, -0.55157, 4.1007),
            slideBar(0.87589, -0.46941, 7.32073),
            slideBar(0.91393, -0.38056, 4.2904),
            slideBar(0.94895, -0.28955, 7.52808),
            slideBar(0.97221, -0.19646, 4.46134),
            slideBar(0.98621, -0.09955, 7.72532),
            slideBar(0.99407, 0.00083, 4.67848),
            slideBar(0.98845, 0.09741, 4.74852),
            slideBar(0.97296, 0.19182, 8.01223),
            slideBar(0.9507, 0.2881, 4.94874),
            slideBar(0.91733, 0.37813, 1.91766),
            slideBar(0.87507, 0.46754, 5.15858),
            slideBar(0.82353, 0.55199, 8.39868),
            slideBar(0.76461, 0.63013, 5.35394),
            slideBar(0.70161, 0.70413, 5.44276),
            slideBar(0.62888, 0.76786, 8.68718),
            slideBar(0.55033, 0.82754, 5.647),
            slideBar(0.467, 0.87508, 5.73711),
            slideBar(0.37787, 0.91891, 5.84727),
            slideBar(0.28665, 0.95082, 9.09177),
            slideBar(0.19252, 0.97159, 6.05867),
            slideBar(0.09569, 0.98646, 6.18272),
            slideBar(-0.00427, 0.99243, 6.24423),
            slideBar(-0.10051, 0.99053, 9.46923),
            slideBar(-0.19538, 0.97607, 9.54903),
            slideBar(-0.29038, 0.95029, 6.52563),
        ],
        "circle6": [
            slideBar(0.46651, -0.87424, 3.63894),
            slideBar(0.55033, -0.82788, 3.70525),
            slideBar(0.62578, -0.76812, 3.8129),
            slideBar(0.70018, -0.7039, 3.88938),
            slideBar(0.76454, -0.63167, 3.99761),
            slideBar(0.82312, -0.55157, 4.1007),
            slideBar(0.87589, -0.46941, 7.32073),
            slideBar(0.91393, -0.38056, 4.2904),
            slideBar(0.94895, -0.28955, 7.52808),
            slideBar(0.97221, -0.19646, 4.46134),
            slideBar(0.98621, -0.09955, 7.72532),
            slideBar(0.99407, 0.00083, 4.67848),
            slideBar(0.98845, 0.09741, 4.74852),
            slideBar(0.97296, 0.19182, 8.01223),
            slideBar(0.9507, 0.2881, 4.94874),
            slideBar(0.91733, 0.37813, 1.91766),
            slideBar(0.87507, 0.46754, 5.15858),
            slideBar(0.82353, 0.55199, 8.39868),
            slideBar(0.76461, 0.63013, 5.35394),
            slideBar(0.70161, 0.70413, 5.44276),
            slideBar(0.62888, 0.76786, 8.68718),
            slideBar(0.55033, 0.82754, 5.647),
            slideBar(0.467, 0.87508, 5.73711),
            slideBar(0.37787, 0.91891, 5.84727),
            slideBar(0.28665, 0.95082, 9.09177),
            slideBar(0.19252, 0.97159, 6.05867),
            slideBar(0.09569, 0.98646, 6.18272),
            slideBar(-0.00427, 0.99243, 6.24423),
            slideBar(-0.10051, 0.99053, 9.46923),
            slideBar(-0.19538, 0.97607, 9.54903),
            slideBar(-0.29038, 0.95029, 6.52563),
            slideBar(-0.37963, 0.9194, 6.59967),
            slideBar(-0.46734, 0.87465, 3.59472),
            slideBar(-0.55054, 0.82664, 3.67709),
            slideBar(-0.62815, 0.7691, 3.80042),
            slideBar(-0.70059, 0.70349, 3.88938),
            slideBar(-0.76579, 0.63084, 3.99761),
            slideBar(-0.82519, 0.55116, 4.08192),
            slideBar(-0.87362, 0.46941, 4.20673),
        ],
        "circle7": [
            slideBar(0.46651, -0.87424, 3.63894),
            slideBar(0.55033, -0.82788, 3.70525),
            slideBar(0.62578, -0.76812, 3.8129),
            slideBar(0.70018, -0.7039, 3.88938),
            slideBar(0.76454, -0.63167, 3.99761),
            slideBar(0.82312, -0.55157, 4.1007),
            slideBar(0.87589, -0.46941, 7.32073),
            slideBar(0.91393, -0.38056, 4.2904),
            slideBar(0.94895, -0.28955, 7.52808),
            slideBar(0.97221, -0.19646, 4.46134),
            slideBar(0.98621, -0.09955, 7.72532),
            slideBar(0.99407, 0.00083, 4.67848),
            slideBar(0.98845, 0.09741, 4.74852),
            slideBar(0.97296, 0.19182, 8.01223),
            slideBar(0.9507, 0.2881, 4.94874),
            slideBar(0.91733, 0.37813, 1.91766),
            slideBar(0.87507, 0.46754, 5.15858),
            slideBar(0.82353, 0.55199, 8.39868),
            slideBar(0.76461, 0.63013, 5.35394),
            slideBar(0.70161, 0.70413, 5.44276),
            slideBar(0.62888, 0.76786, 8.68718),
            slideBar(0.55033, 0.82754, 5.647),
            slideBar(0.467, 0.87508, 5.73711),
            slideBar(0.37787, 0.91891, 5.84727),
            slideBar(0.28665, 0.95082, 9.09177),
            slideBar(0.19252, 0.97159, 6.05867),
            slideBar(0.09569, 0.98646, 6.18272),
            slideBar(-0.00427, 0.99243, 6.24423),
            slideBar(-0.10051, 0.99053, 9.46923),
            slideBar(-0.19538, 0.97607, 9.54903),
            slideBar(-0.29038, 0.95029, 6.52563),
            slideBar(-0.37963, 0.9194, 6.59967),
            slideBar(-0.46734, 0.87465, 3.59472),
            slideBar(-0.55054, 0.82664, 3.67709),
            slideBar(-0.62815, 0.7691, 3.80042),
            slideBar(-0.70059, 0.70349, 3.88938),
            slideBar(-0.76579, 0.63084, 3.99761),
            slideBar(-0.82519, 0.55116, 4.08192),
            slideBar(-0.87362, 0.46941, 4.20673),
            slideBar(-0.91667, 0.38, 4.2904),
            slideBar(-0.94916, 0.28934, 4.38507),
            slideBar(-0.97338, 0.19372, 4.46134),
            slideBar(-0.98807, 0.0981, 4.585),
            slideBar(-0.99283, -0.00021, 4.67848),
            slideBar(-0.98828, -0.09914, 4.74852),
            slideBar(-0.9742, -0.19269, 4.8458),
            slideBar(-0.9502, -0.28976, 4.94874),
        ],
        "circle8": [
            slideBar(0.46651, -0.87424, 3.63894),
            slideBar(0.55033, -0.82788, 3.70525),
            slideBar(0.62578, -0.76812, 3.8129),
            slideBar(0.70018, -0.7039, 3.88938),
            slideBar(0.76454, -0.63167, 3.99761),
            slideBar(0.82312, -0.55157, 4.1007),
            slideBar(0.87589, -0.46941, 7.32073),
            slideBar(0.91393, -0.38056, 4.2904),
            slideBar(0.94895, -0.28955, 7.52808),
            slideBar(0.97221, -0.19646, 4.46134),
            slideBar(0.98621, -0.09955, 7.72532),
            slideBar(0.99407, 0.00083, 4.67848),
            slideBar(0.98845, 0.09741, 4.74852),
            slideBar(0.97296, 0.19182, 8.01223),
            slideBar(0.9507, 0.2881, 4.94874),
            slideBar(0.91733, 0.37813, 1.91766),
            slideBar(0.87507, 0.46754, 5.15858),
            slideBar(0.82353, 0.55199, 8.39868),
            slideBar(0.76461, 0.63013, 5.35394),
            slideBar(0.70161, 0.70413, 5.44276),
            slideBar(0.62888, 0.76786, 8.68718),
            slideBar(0.55033, 0.82754, 5.647),
            slideBar(0.467, 0.87508, 5.73711),
            slideBar(0.37787, 0.91891, 5.84727),
            slideBar(0.28665, 0.95082, 9.09177),
            slideBar(0.19252, 0.97159, 6.05867),
            slideBar(0.09569, 0.98646, 6.18272),
            slideBar(-0.00427, 0.99243, 6.24423),
            slideBar(-0.10051, 0.99053, 9.46923),
            slideBar(-0.19538, 0.97607, 9.54903),
            slideBar(-0.29038, 0.95029, 6.52563),
            slideBar(-0.37963, 0.9194, 6.59967),
            slideBar(-0.46734, 0.87465, 3.59472),
            slideBar(-0.55054, 0.82664, 3.67709),
            slideBar(-0.62815, 0.7691, 3.80042),
            slideBar(-0.70059, 0.70349, 3.88938),
            slideBar(-0.76579, 0.63084, 3.99761),
            slideBar(-0.82519, 0.55116, 4.08192),
            slideBar(-0.87362, 0.46941, 4.20673),
            slideBar(-0.91667, 0.38, 4.2904),
            slideBar(-0.94916, 0.28934, 4.38507),
            slideBar(-0.97338, 0.19372, 4.46134),
            slideBar(-0.98807, 0.0981, 4.585),
            slideBar(-0.99283, -0.00021, 4.67848),
            slideBar(-0.98828, -0.09914, 4.74852),
            slideBar(-0.9742, -0.19269, 4.8458),
            slideBar(-0.9502, -0.28976, 4.94874),
            slideBar(-0.91667, -0.37896, 5.04254),
            slideBar(-0.87631, -0.4661, 5.15858),
            slideBar(-0.82498, -0.55344, 5.2618),
            slideBar(-0.76724, -0.63126, 5.35394),
            slideBar(-0.70245, -0.70432, 5.44126),
            slideBar(-0.63084, -0.76951, 5.53465),
            slideBar(-0.55178, -0.82705, 5.647),
            slideBar(-0.46547, -0.87589, 5.73711),
        ],
        "line3": [
            slideBar(0.42038, -0.83332, 1.17809),
            slideBar(0.45791, -0.74273, 1.17809),
            slideBar(0.49548, -0.65203, 1.17809),
            slideBar(0.53305, -0.56132, 1.17809),
            slideBar(0.57061, -0.47066, 1.17809),
            slideBar(0.60817, -0.37997, 1.17809),
            slideBar(0.64573, -0.28931, 1.17809),
            slideBar(0.68328, -0.19864, 1.17809),
            slideBar(0.72085, -0.10794, 1.17809),
            slideBar(0.75841, -0.01728, 1.17809),
            slideBar(0.79596, 0.07338, 1.17809),
            slideBar(0.83352, 0.16405, 1.17809),
            slideBar(0.87108, 0.25473, 1.17809),
        ],
        "line4": [
            slideBar(0.38278, -0.82589, 1.5708),
            slideBar(0.38278, -0.72777, 1.5708),
            slideBar(0.38278, -0.62961, 1.5708),
            slideBar(0.38278, -0.53138, 1.5708),
            slideBar(0.38278, -0.43324, 1.5708),
            slideBar(0.38278, -0.33515, 1.5708),
            slideBar(0.38278, -0.23694, 1.5708),
            slideBar(0.38278, -0.13881, 1.5708),
            slideBar(0.38278, -0.04066, 1.5708),
            slideBar(0.38278, 0.0574, 1.5708),
            slideBar(0.38278, 0.15558, 1.5708),
            slideBar(0.38278, 0.25373, 1.5708),
            slideBar(0.38278, 0.35187, 1.5708),
            slideBar(0.38278, 0.45005, 1.5708),
            slideBar(0.38278, 0.54818, 1.5708),
            slideBar(0.38278, 0.64634, 1.5708),
            slideBar(0.38278, 0.74445, 1.5708),
            slideBar(0.38278, 0.84261, 1.5708),
        ],
        "line5": [
            slideBar(0.34521, -0.83341, 1.9635),
            slideBar(0.30765, -0.74273, 1.9635),
            slideBar(0.27008, -0.65203, 1.9635),
            slideBar(0.2325, -0.56131, 1.9635),
            slideBar(0.19494, -0.47063, 1.9635),
            slideBar(0.15739, -0.37996, 1.9635),
            slideBar(0.11985, -0.28934, 1.9635),
            slideBar(0.08228, -0.19865, 1.9635),
            slideBar(0.04473, -0.10799, 1.9635),
            slideBar(0.00715, -0.01727, 1.9635),
            slideBar(-0.03042, 0.07343, 1.9635),
            slideBar(-0.06798, 0.16412, 1.9635),
            slideBar(-0.10552, 0.25476, 1.9635),
            slideBar(-0.14307, 0.34541, 1.9635),
            slideBar(-0.18064, 0.4361, 1.9635),
            slideBar(-0.21819, 0.52676, 1.9635),
            slideBar(-0.25576, 0.61745, 1.9635),
            slideBar(-0.29332, 0.70814, 1.9635),
            slideBar(-0.33088, 0.79881, 1.9635),
        ],
        "ppqq1": [
            slideBar(0.34101, -0.83522, 2.01008),
            slideBar(0.29928, -0.74641, 2.01008),
            slideBar(0.25754, -0.65759, 2.01008),
            slideBar(0.21579, -0.56874, 2.01008),
            slideBar(0.17406, -0.47994, 2.01008),
            slideBar(0.13231, -0.3911, 2.01008),
            slideBar(0.09058, -0.30228, 2.01008),
            slideBar(0.04884, -0.21345, 1.98181),
            slideBar(0.01223, -0.12253, 1.86501),
            slideBar(-0.00506, -0.0261, 1.70163),
            slideBar(-0.00187, 0.07183, 1.51447),
            slideBar(0.02175, 0.16691, 1.3146),
            slideBar(0.0649, 0.25487, 1.10908),
            slideBar(0.12551, 0.33181, 0.90125),
            slideBar(0.20087, 0.39444, 0.69183),
            slideBar(0.28766, 0.43989, 0.48088),
            slideBar(0.38206, 0.46601, 0.26973),
            slideBar(0.47988, 0.47175, 0.05938),
            slideBar(0.57673, 0.45701, -0.15201),
            slideBar(0.66829, 0.42219, -0.36368),
            slideBar(0.75049, 0.36888, -0.5747),
            slideBar(0.81973, 0.29959, -0.78541),
            slideBar(0.87299, 0.21735, -0.99674),
            slideBar(0.90775, 0.12576, -1.20861),
            slideBar(0.92242, 0.02891, -1.41933),
            slideBar(0.91662, -0.06888, -1.6304),
            slideBar(0.89043, -0.16328, -1.86625),
            slideBar(0.84087, -0.24762, -2.01614),
            slideBar(0.78583, -0.32889, -2.09108),
            slideBar(0.73082, -0.41013, -2.12856),
            slideBar(0.67576, -0.49144, -2.14729),
            slideBar(0.62076, -0.57266, -2.15666),
            slideBar(0.56571, -0.65395, -2.16134),
            slideBar(0.51069, -0.7352, -2.16369),
            slideBar(0.45566, -0.81648, -2.16486),
        ],
        "ppqq2": [
            slideBar(0.34108, -0.83524, 2.00954),
            slideBar(0.29937, -0.74635, 2.00954),
            slideBar(0.25768, -0.65752, 2.00954),
            slideBar(0.216, -0.56868, 2.00954),
            slideBar(0.1743, -0.47982, 2.00954),
            slideBar(0.13261, -0.39098, 2.00954),
            slideBar(0.09092, -0.30212, 2.00954),
            slideBar(0.04922, -0.21327, 1.98365),
            slideBar(0.01224, -0.12252, 1.86596),
            slideBar(-0.00506, -0.02609, 1.70211),
            slideBar(-0.00187, 0.07183, 1.51471),
            slideBar(0.02175, 0.16691, 1.31472),
            slideBar(0.0649, 0.25487, 1.10913),
            slideBar(0.12553, 0.33183, 0.90126),
            slideBar(0.20087, 0.39444, 0.69184),
            slideBar(0.28766, 0.43989, 0.48087),
            slideBar(0.38208, 0.46601, 0.26971),
            slideBar(0.47988, 0.47175, 0.05937),
            slideBar(0.57673, 0.45701, -0.15202),
            slideBar(0.66829, 0.42219, -0.36368),
            slideBar(0.7505, 0.36888, -0.57471),
            slideBar(0.81975, 0.29958, -0.78543),
            slideBar(0.87299, 0.21735, -0.99675),
            slideBar(0.90776, 0.12576, -1.20863),
            slideBar(0.92243, 0.02889, -1.38608),
            slideBar(0.92314, -0.06926, -1.47689),
            slideBar(0.92344, -0.1674, -1.5223),
            slideBar(0.92374, -0.26555, -1.54501),
        ],
        "ppqq3": [
            slideBar(0.34058, -0.83547, 2.01456),
            slideBar(0.29849, -0.74681, 2.01374),
            slideBar(0.25649, -0.65809, 2.01272),
            slideBar(0.21461, -0.56934, 2.01145),
            slideBar(0.17286, -0.4805, 2.0098),
            slideBar(0.13129, -0.3916, 2.00747),
            slideBar(0.08998, -0.30254, 2.00356),
            slideBar(0.04918, -0.2133, 1.98126),
            slideBar(0.01206, -0.12252, 1.87093),
            slideBar(-0.00643, -0.02632, 1.70485),
            slideBar(-0.00329, 0.07158, 1.51333),
            slideBar(0.02085, 0.16652, 1.31181),
            slideBar(0.06439, 0.25429, 1.10664),
            slideBar(0.12518, 0.33112, 0.89955),
            slideBar(0.20058, 0.39367, 0.69144),
            slideBar(0.28732, 0.43919, 0.49444),
            slideBar(0.381, 0.46791, 0.25748),
            slideBar(0.47866, 0.46991, 0.04979),
            slideBar(0.57558, 0.45448, -0.069),
            slideBar(0.672, 0.43616, -0.13525),
            slideBar(0.76817, 0.41652, -0.17288),
            slideBar(0.86414, 0.39601, -0.19502),
        ],
        "ppqq4": [
            slideBar(0.34116, -0.83515, 2.00837),
            slideBar(0.29959, -0.74629, 2.00837),
            slideBar(0.258, -0.65738, 2.00837),
            slideBar(0.2164, -0.56847, 2.00837),
            slideBar(0.17481, -0.47956, 2.00837),
            slideBar(0.13323, -0.39068, 2.00837),
            slideBar(0.09164, -0.30178, 2.00837),
            slideBar(0.05005, -0.21288, 1.98269),
            slideBar(0.01313, -0.12209, 1.86589),
            slideBar(-0.00424, -0.02568, 1.70117),
            slideBar(-0.00088, 0.07222, 1.5131),
            slideBar(0.02296, 0.16725, 1.31398),
            slideBar(0.06609, 0.25521, 1.10903),
            slideBar(0.12668, 0.3322, 0.90037),
            slideBar(0.20213, 0.39469, 0.69015),
            slideBar(0.28903, 0.43992, 0.48037),
            slideBar(0.38344, 0.46611, 0.26938),
            slideBar(0.48123, 0.47183, 0.05782),
            slideBar(0.57805, 0.45683, -0.15325),
            slideBar(0.6696, 0.42191, -0.36392),
            slideBar(0.75182, 0.36867, -0.57511),
            slideBar(0.82104, 0.29933, -0.78699),
            slideBar(0.87406, 0.21696, -0.99786),
            slideBar(0.90876, 0.12535, -1.20873),
            slideBar(0.92352, 0.0285, -1.42046),
            slideBar(0.91751, -0.06928, -1.63193),
            slideBar(0.89113, -0.16364, -1.84273),
            slideBar(0.84565, -0.2504, -2.05362),
            slideBar(0.78301, -0.32574, -2.26532),
            slideBar(0.70589, -0.38616, -2.47686),
            slideBar(0.61781, -0.42905, -2.68738),
            slideBar(0.52275, -0.45269, -2.89885),
            slideBar(0.42482, -0.45575, -3.11056),
            slideBar(0.32845, -0.43815, 2.96156),
            slideBar(0.23787, -0.4008, 2.75083),
            slideBar(0.1571, -0.34536, 2.53951),
            slideBar(0.0898, -0.27419, 2.32764),
            slideBar(0.03902, -0.19041, 2.11693),
            slideBar(0.00676, -0.09789, 1.90591),
            slideBar(-0.00536, -0.00068, 1.69414),
            slideBar(0.00329, 0.0969, 1.48143),
            slideBar(0.03244, 0.19044, 1.30337),
            slideBar(0.07472, 0.27898, 1.21434),
            slideBar(0.11703, 0.36758, 1.16982),
            slideBar(0.15932, 0.45615, 1.14756),
            slideBar(0.20159, 0.54468, 1.13644),
            slideBar(0.2439, 0.63328, 1.13087),
            slideBar(0.28619, 0.72184, 1.12809),
            slideBar(0.32848, 0.81041, 1.1267),
        ],
        "ppqq5": [
            slideBar(0.33939, -0.83605, 2.02747),
            slideBar(0.2962, -0.7479, 2.02581),
            slideBar(0.25322, -0.65968, 2.02374),
            slideBar(0.21044, -0.57132, 2.02118),
            slideBar(0.16796, -0.48286, 2.01785),
            slideBar(0.12581, -0.3942, 2.01314),
            slideBar(0.08422, -0.30531, 2.00532),
            slideBar(0.0436, -0.21597, 1.97692),
            slideBar(0.00743, -0.1248, 1.86455),
            slideBar(-0.01024, -0.02847, 1.69865),
            slideBar(-0.00651, 0.06943, 1.50837),
            slideBar(0.01798, 0.16429, 1.30866),
            slideBar(0.06163, 0.25199, 1.10568),
            slideBar(0.12233, 0.3289, 0.90107),
            slideBar(0.19749, 0.39176, 0.69545),
            slideBar(0.28394, 0.43785, 0.48883),
            slideBar(0.37805, 0.46513, 0.27948),
            slideBar(0.47575, 0.472, 0.06871),
            slideBar(0.57273, 0.45813, -0.142),
            slideBar(0.66469, 0.42428, -0.35248),
            slideBar(0.74753, 0.372, -0.56352),
            slideBar(0.81755, 0.30348, -0.77534),
            slideBar(0.87153, 0.22173, -0.98626),
            slideBar(0.90729, 0.13052, -1.19706),
            slideBar(0.92318, 0.03385, -1.40878),
            slideBar(0.91832, -0.064, -1.62029),
            slideBar(0.89304, -0.15865, -1.83111),
            slideBar(0.84857, -0.24594, -2.04196),
            slideBar(0.78681, -0.322, -2.25362),
            slideBar(0.71041, -0.38331, -2.46522),
            slideBar(0.62283, -0.42723, -2.67546),
            slideBar(0.52805, -0.45203, -2.88571),
            slideBar(0.43019, -0.4565, -3.0957),
            slideBar(0.33354, -0.44049, 2.97772),
            slideBar(0.24232, -0.40473, 2.76686),
            slideBar(0.16068, -0.35059, 2.55323),
            slideBar(0.09259, -0.28019, 2.3367),
            slideBar(0.04143, -0.19664, 2.1454),
            slideBar(0.00473, -0.10562, 2.04191),
            slideBar(-0.03055, -0.01402, 1.98853),
            slideBar(-0.06553, 0.07769, 1.96084),
            slideBar(-0.10032, 0.16947, 1.94629),
            slideBar(-0.13497, 0.26126, 1.93849),
            slideBar(-0.16954, 0.35314, 1.93416),
            slideBar(-0.20402, 0.44501, 1.93164),
            slideBar(-0.23846, 0.53697, 1.93008),
            slideBar(-0.27281, 0.62887, 1.92902),
            slideBar(-0.30713, 0.72084, 1.92822),
            slideBar(-0.3414, 0.81282, 1.92754),
        ],
        "ppqq6": [
            slideBar(0.3403, -0.83561, 2.01743),
            slideBar(0.29799, -0.74705, 2.01621),
            slideBar(0.2558, -0.65841, 2.01468),
            slideBar(0.21379, -0.56971, 2.01279),
            slideBar(0.17198, -0.48093, 2.01032),
            slideBar(0.13042, -0.39198, 2.00683),
            slideBar(0.08928, -0.30288, 2.00099),
            slideBar(0.04888, -0.21345, 1.97533),
            slideBar(0.0126, -0.12233, 1.86509),
            slideBar(-0.00533, -0.02603, 1.70136),
            slideBar(-0.00208, 0.07189, 1.5124),
            slideBar(0.02191, 0.16686, 1.31253),
            slideBar(0.06524, 0.25473, 1.10772),
            slideBar(0.12592, 0.33165, 0.9),
            slideBar(0.20133, 0.39418, 0.69088),
            slideBar(0.28816, 0.43958, 0.48175),
            slideBar(0.38251, 0.46596, 0.2711),
            slideBar(0.48028, 0.47187, 0.0597),
            slideBar(0.57712, 0.45707, -0.15128),
            slideBar(0.66874, 0.42235, -0.36192),
            slideBar(0.75109, 0.36927, -0.57308),
            slideBar(0.82044, 0.30008, -0.78495),
            slideBar(0.87364, 0.21781, -0.99584),
            slideBar(0.90852, 0.12626, -1.20669),
            slideBar(0.92347, 0.02945, -1.41842),
            slideBar(0.91766, -0.06835, -1.62989),
            slideBar(0.89148, -0.16275, -1.8407),
            slideBar(0.84617, -0.24962, -2.05158),
            slideBar(0.78369, -0.32507, -2.26327),
            slideBar(0.7067, -0.38565, -2.47481),
            slideBar(0.61871, -0.42872, -2.68533),
            slideBar(0.52368, -0.45256, -2.89679),
            slideBar(0.42578, -0.45582, -3.10851),
            slideBar(0.32936, -0.43841, 2.96361),
            slideBar(0.23872, -0.40126, 2.76333),
            slideBar(0.15657, -0.34761, 2.65529),
            slideBar(0.07527, -0.29265, 2.60127),
            slideBar(-0.00611, -0.23765, 2.57426),
            slideBar(-0.08738, -0.18273, 2.56076),
            slideBar(-0.16873, -0.12775, 2.55401),
            slideBar(-0.25001, -0.07281, 2.55063),
            slideBar(-0.33135, -0.01784, 2.54894),
            slideBar(-0.4127, 0.03714, 2.5481),
            slideBar(-0.49398, 0.09208, 2.54768),
            slideBar(-0.57531, 0.14704, 2.54747),
            slideBar(-0.65664, 0.20201, 2.54736),
            slideBar(-0.73792, 0.25695, 2.54731),
            slideBar(-0.81926, 0.31192, 2.54728),
        ],
        "ppqq7": [
            slideBar(0.34058, -0.83547, 2.01456),
            slideBar(0.29849, -0.74681, 2.01374),
            slideBar(0.25649, -0.65809, 2.01272),
            slideBar(0.21461, -0.56934, 2.01145),
            slideBar(0.17286, -0.4805, 2.0098),
            slideBar(0.13129, -0.3916, 2.00747),
            slideBar(0.08998, -0.30254, 2.00356),
            slideBar(0.04918, -0.2133, 1.98126),
            slideBar(0.01206, -0.12252, 1.87093),
            slideBar(-0.00643, -0.02632, 1.70485),
            slideBar(-0.00329, 0.07158, 1.51333),
            slideBar(0.02085, 0.16652, 1.31181),
            slideBar(0.06439, 0.25429, 1.10664),
            slideBar(0.12518, 0.33112, 0.89955),
            slideBar(0.20058, 0.39367, 0.69134),
            slideBar(0.28734, 0.43918, 0.48278),
            slideBar(0.38165, 0.46571, 0.27242),
            slideBar(0.47942, 0.47179, 0.06116),
            slideBar(0.57628, 0.45714, -0.14975),
            slideBar(0.66794, 0.42257, -0.36034),
            slideBar(0.75039, 0.36961, -0.57149),
            slideBar(0.81985, 0.30053, -0.78334),
            slideBar(0.87317, 0.21836, -0.99423),
            slideBar(0.90821, 0.12686, -1.20507),
            slideBar(0.92332, 0.03008, -1.4168),
            slideBar(0.91766, -0.06774, -1.62828),
            slideBar(0.89163, -0.16218, -1.83909),
            slideBar(0.84648, -0.24911, -2.04996),
            slideBar(0.78411, -0.32467, -2.26164),
            slideBar(0.70721, -0.38537, -2.47321),
            slideBar(0.6193, -0.42858, -2.68458),
            slideBar(0.52427, -0.4524, -2.89892),
            slideBar(0.42632, -0.45518, -3.04704),
            slideBar(0.32834, -0.44993, -3.1211),
            slideBar(0.23027, -0.44467, 3.12506),
            slideBar(0.13227, -0.43941, 3.10654),
            slideBar(0.03431, -0.43416, 3.09729),
            slideBar(-0.06369, -0.42891, 3.09266),
            slideBar(-0.16168, -0.42365, 3.09034),
            slideBar(-0.25973, -0.4184, 3.08919),
            slideBar(-0.3577, -0.41314, 3.08861),
            slideBar(-0.45576, -0.40789, 3.08832),
            slideBar(-0.55375, -0.40263, 3.08817),
            slideBar(-0.65172, -0.39738, 3.0881),
            slideBar(-0.74974, -0.39213, 3.08807),
            slideBar(-0.84774, -0.38687, 3.08805),
        ],
        "ppqq8": [
            slideBar(0.34058, -0.83547, 2.01456),
            slideBar(0.29849, -0.74681, 2.01374),
            slideBar(0.25649, -0.65809, 2.01272),
            slideBar(0.21461, -0.56934, 2.01145),
            slideBar(0.17286, -0.4805, 2.0098),
            slideBar(0.13129, -0.3916, 2.00747),
            slideBar(0.08998, -0.30254, 2.00356),
            slideBar(0.04918, -0.2133, 1.98126),
            slideBar(0.01206, -0.12252, 1.87093),
            slideBar(-0.00643, -0.02632, 1.70485),
            slideBar(-0.00329, 0.07158, 1.51333),
            slideBar(0.02085, 0.16652, 1.31181),
            slideBar(0.06439, 0.25429, 1.10664),
            slideBar(0.12518, 0.33112, 0.89955),
            slideBar(0.20058, 0.39367, 0.69134),
            slideBar(0.28734, 0.43918, 0.48278),
            slideBar(0.38165, 0.46571, 0.27242),
            slideBar(0.47942, 0.47179, 0.06116),
            slideBar(0.57628, 0.45714, -0.14975),
            slideBar(0.66794, 0.42257, -0.36034),
            slideBar(0.75039, 0.36961, -0.57149),
            slideBar(0.81985, 0.30053, -0.78334),
            slideBar(0.87317, 0.21836, -0.9945),
            slideBar(0.90816, 0.12685, -1.20858),
            slideBar(0.92261, 0.02998, -1.42722),
            slideBar(0.91527, -0.0677, -1.6458),
            slideBar(0.88692, -0.16146, -1.85907),
            slideBar(0.83981, -0.24738, -2.06405),
            slideBar(0.77681, -0.32244, -2.26021),
            slideBar(0.70091, -0.38448, -2.44571),
            slideBar(0.61535, -0.43238, -2.56408),
            slideBar(0.52736, -0.47588, -2.62359),
            slideBar(0.43934, -0.51932, -2.65352),
            slideBar(0.35132, -0.56273, -2.66861),
            slideBar(0.26328, -0.60612, -2.67624),
            slideBar(0.17523, -0.6495, -2.68012),
            slideBar(0.08717, -0.69286, -2.68212),
            slideBar(-0.00089, -0.73621, -2.68317),
            slideBar(-0.08893, -0.77954, -2.68374),
            slideBar(-0.177, -0.82288, -2.68406),
            slideBar(-0.26505, -0.8662, -2.68426),
        ],
        "pq1": [
            slideBar(0.31336, -0.85469, 2.35619),
            slideBar(0.24393, -0.78526, 2.35619),
            slideBar(0.17455, -0.71588, 2.35619),
            slideBar(0.10514, -0.64646, 2.35619),
            slideBar(0.03573, -0.57706, 2.35619),
            slideBar(-0.03365, -0.50768, 2.35619),
            slideBar(-0.10305, -0.43827, 2.35619),
            slideBar(-0.17245, -0.36887, 2.35619),
            slideBar(-0.24185, -0.29948, 2.33454),
            slideBar(-0.30811, -0.22722, 2.20653),
            slideBar(-0.35569, -0.14169, 2.0137),
            slideBar(-0.37991, -0.04685, 1.7895),
            slideBar(-0.37938, 0.05103, 1.54961),
            slideBar(-0.35411, 0.14559, 1.30081),
            slideBar(-0.30558, 0.23059, 1.04843),
            slideBar(-0.23711, 0.30054, 0.79444),
            slideBar(-0.15318, 0.3509, 0.53854),
            slideBar(-0.05918, 0.3782, 0.2827),
            slideBar(0.03867, 0.38083, 0.02707),
            slideBar(0.13401, 0.35865, -0.2295),
            slideBar(0.22055, 0.31293, -0.48601),
            slideBar(0.29266, 0.24675, -0.74184),
            slideBar(0.34574, 0.1645, -0.9987),
            slideBar(0.37609, 0.07145, -1.25059),
            slideBar(0.38278, -0.02631, -1.41069),
            slideBar(0.38278, -0.12449, -1.49074),
            slideBar(0.38278, -0.22259, -1.53077),
            slideBar(0.38278, -0.32076, -1.55078),
            slideBar(0.38278, -0.41888, -1.56079),
            slideBar(0.38278, -0.51706, -1.56579),
            slideBar(0.38278, -0.61522, -1.56829),
            slideBar(0.38278, -0.71336, -1.56955),
            slideBar(0.38278, -0.81149, -1.57017),
        ],
        "pq2": [
            slideBar(0.31336, -0.85469, 2.35619),
            slideBar(0.24393, -0.78526, 2.35619),
            slideBar(0.17455, -0.71588, 2.35619),
            slideBar(0.10514, -0.64646, 2.35619),
            slideBar(0.03576, -0.57709, 2.35619),
            slideBar(-0.03365, -0.50768, 2.35619),
            slideBar(-0.10305, -0.43827, 2.35619),
            slideBar(-0.17246, -0.36887, 2.35619),
            slideBar(-0.24185, -0.29948, 2.33454),
            slideBar(-0.30811, -0.22722, 2.20653),
            slideBar(-0.35569, -0.14169, 2.0137),
            slideBar(-0.37992, -0.04685, 1.7895),
            slideBar(-0.37938, 0.05104, 1.54961),
            slideBar(-0.35412, 0.14559, 1.30081),
            slideBar(-0.30559, 0.23059, 1.04843),
            slideBar(-0.23711, 0.30054, 0.79444),
            slideBar(-0.15318, 0.3509, 0.53854),
            slideBar(-0.05918, 0.3782, 0.2827),
            slideBar(0.03867, 0.38083, 0.02707),
            slideBar(0.134, 0.35865, -0.2295),
            slideBar(0.22054, 0.31293, -0.47911),
            slideBar(0.29366, 0.24767, -0.63225),
            slideBar(0.36308, 0.17825, -0.70883),
            slideBar(0.43248, 0.10885, -0.74711),
            slideBar(0.50187, 0.03945, -0.76625),
            slideBar(0.57128, -0.02995, -0.77583),
            slideBar(0.64066, -0.09933, -0.78061),
            slideBar(0.71007, -0.16874, -0.78301),
            slideBar(0.77946, -0.23813, -0.7842),
            slideBar(0.84887, -0.30754, -0.7848),
        ],
        "pq3": [
            slideBar(0.31335, -0.85468, 2.35619),
            slideBar(0.24393, -0.78525, 2.35619),
            slideBar(0.17453, -0.71586, 2.35619),
            slideBar(0.10513, -0.64645, 2.35619),
            slideBar(0.03573, -0.57706, 2.35619),
            slideBar(-0.03365, -0.50768, 2.35619),
            slideBar(-0.10305, -0.43828, 2.35619),
            slideBar(-0.17245, -0.36887, 2.35619),
            slideBar(-0.24185, -0.29947, 2.33486),
            slideBar(-0.30816, -0.22726, 2.20905),
            slideBar(-0.35614, -0.14196, 2.01419),
            slideBar(-0.38022, -0.0471, 1.78588),
            slideBar(-0.37892, 0.05077, 1.54533),
            slideBar(-0.35318, 0.14523, 1.30088),
            slideBar(-0.30503, 0.23045, 1.05234),
            slideBar(-0.23709, 0.30094, 0.79725),
            slideBar(-0.15326, 0.35143, 0.53772),
            slideBar(-0.05917, 0.3783, 0.29168),
            slideBar(0.03876, 0.38278, 0.14584),
            slideBar(0.13696, 0.38278, 0.07292),
            slideBar(0.23508, 0.38278, 0.03646),
            slideBar(0.33326, 0.38278, 0.01823),
            slideBar(0.43139, 0.38278, 0.00912),
            slideBar(0.52952, 0.38278, 0.00456),
            slideBar(0.62767, 0.38278, 0.00228),
            slideBar(0.72581, 0.38278, 0.00114),
            slideBar(0.82396, 0.38278, 0.00057),
        ],
        "pq4": [
            slideBar(0.31335, -0.85468, 2.3562),
            slideBar(0.24396, -0.78529, 2.3562),
            slideBar(0.17454, -0.71587, 2.3562),
            slideBar(0.10514, -0.64647, 2.3562),
            slideBar(0.03576, -0.57709, 2.3562),
            slideBar(-0.03364, -0.50769, 2.3562),
            slideBar(-0.10305, -0.43828, 2.3562),
            slideBar(-0.17246, -0.36887, 2.3562),
            slideBar(-0.24185, -0.29948, 2.33415),
            slideBar(-0.30805, -0.22717, 2.20581),
            slideBar(-0.35554, -0.14159, 2.01301),
            slideBar(-0.3797, -0.04674, 1.78902),
            slideBar(-0.37914, 0.05114, 1.5494),
            slideBar(-0.35388, 0.14572, 1.30092),
            slideBar(-0.30539, 0.23073, 1.06194),
            slideBar(-0.23871, 0.30262, 0.92368),
            slideBar(-0.16931, 0.37202, 0.85454),
            slideBar(-0.09989, 0.44144, 0.81998),
            slideBar(-0.03048, 0.51085, 0.80269),
            slideBar(0.03891, 0.58025, 0.79405),
            slideBar(0.10832, 0.64965, 0.78973),
            slideBar(0.17771, 0.71905, 0.78757),
            slideBar(0.24711, 0.78845, 0.78649),
            slideBar(0.31651, 0.85786, 0.78595),
        ],
        "pq5": [
            slideBar(0.31332, -0.85465, 2.35619),
            slideBar(0.24393, -0.78526, 2.35619),
            slideBar(0.17453, -0.71586, 2.35619),
            slideBar(0.10513, -0.64646, 2.35619),
            slideBar(0.03573, -0.57706, 2.35619),
            slideBar(-0.03365, -0.50768, 2.35619),
            slideBar(-0.10306, -0.43827, 2.35619),
            slideBar(-0.17246, -0.36887, 2.35619),
            slideBar(-0.24185, -0.29948, 2.32501),
            slideBar(-0.30669, -0.22601, 2.19598),
            slideBar(-0.3533, -0.13989, 2.0137),
            slideBar(-0.37854, -0.04528, 1.81387),
            slideBar(-0.38278, 0.05272, 1.69233),
            slideBar(-0.38278, 0.15085, 1.63157),
            slideBar(-0.38278, 0.249, 1.60118),
            slideBar(-0.38278, 0.34713, 1.58599),
            slideBar(-0.38278, 0.44531, 1.57839),
            slideBar(-0.38278, 0.54344, 1.57459),
            slideBar(-0.38278, 0.6416, 1.5727),
            slideBar(-0.38278, 0.73974, 1.57175),
            slideBar(-0.38278, 0.83789, 1.57127),
        ],
        "pq6": [
            slideBar(0.31336, -0.85469, 2.35619),
            slideBar(0.24393, -0.78526, 2.35619),
            slideBar(0.17455, -0.71588, 2.35619),
            slideBar(0.10514, -0.64646, 2.35619),
            slideBar(0.03573, -0.57706, 2.35619),
            slideBar(-0.03365, -0.50768, 2.35619),
            slideBar(-0.10305, -0.43827, 2.35619),
            slideBar(-0.17245, -0.36887, 2.35619),
            slideBar(-0.24185, -0.29948, 2.33454),
            slideBar(-0.30811, -0.22722, 2.20653),
            slideBar(-0.35569, -0.14169, 2.0137),
            slideBar(-0.37991, -0.04685, 1.7895),
            slideBar(-0.37938, 0.05104, 1.54961),
            slideBar(-0.35411, 0.14559, 1.30081),
            slideBar(-0.30558, 0.23059, 1.04843),
            slideBar(-0.23711, 0.30054, 0.79444),
            slideBar(-0.15318, 0.3509, 0.53854),
            slideBar(-0.05918, 0.3782, 0.2827),
            slideBar(0.03867, 0.38083, 0.02707),
            slideBar(0.13401, 0.35865, -0.2295),
            slideBar(0.22055, 0.31293, -0.48601),
            slideBar(0.29266, 0.24675, -0.74184),
            slideBar(0.34574, 0.16451, -0.9987),
            slideBar(0.37609, 0.07145, -1.25514),
            slideBar(0.38188, -0.02626, -1.511),
            slideBar(0.36281, -0.12227, -1.76755),
            slideBar(0.31995, -0.21026, -2.02432),
            slideBar(0.25611, -0.28448, -2.28008),
            slideBar(0.17566, -0.3402, -2.53689),
            slideBar(0.08363, -0.37358, -2.79345),
            slideBar(-0.01383, -0.38253, -3.04932),
            slideBar(-0.1104, -0.36659, 2.97745),
            slideBar(-0.19975, -0.32662, 2.72088),
            slideBar(-0.27609, -0.26523, 2.53854),
            slideBar(-0.34544, -0.19588, 2.44737),
            slideBar(-0.41485, -0.12648, 2.40178),
            slideBar(-0.48428, -0.05704, 2.37899),
            slideBar(-0.55366, 0.01233, 2.36759),
            slideBar(-0.62306, 0.08173, 2.36189),
            slideBar(-0.69246, 0.15114, 2.35905),
            slideBar(-0.76187, 0.22054, 2.35762),
            slideBar(-0.83127, 0.28995, 2.35691),
        ],
        "pq7": [
            slideBar(0.31336, -0.85469, 2.35619),
            slideBar(0.24393, -0.78526, 2.35619),
            slideBar(0.17455, -0.71588, 2.35619),
            slideBar(0.10514, -0.64646, 2.35619),
            slideBar(0.03573, -0.57706, 2.35619),
            slideBar(-0.03365, -0.50768, 2.35619),
            slideBar(-0.10305, -0.43827, 2.35619),
            slideBar(-0.17245, -0.36887, 2.35619),
            slideBar(-0.24185, -0.29948, 2.33454),
            slideBar(-0.30811, -0.22722, 2.20653),
            slideBar(-0.35569, -0.14169, 2.0137),
            slideBar(-0.37991, -0.04685, 1.7895),
            slideBar(-0.37938, 0.05104, 1.54961),
            slideBar(-0.35411, 0.14559, 1.30081),
            slideBar(-0.30558, 0.23059, 1.04843),
            slideBar(-0.23711, 0.30054, 0.79444),
            slideBar(-0.15318, 0.3509, 0.53854),
            slideBar(-0.05918, 0.3782, 0.2827),
            slideBar(0.03867, 0.38083, 0.02707),
            slideBar(0.13401, 0.35865, -0.2295),
            slideBar(0.22055, 0.31293, -0.48601),
            slideBar(0.29266, 0.24675, -0.74184),
            slideBar(0.34574, 0.16451, -0.9987),
            slideBar(0.37609, 0.07145, -1.25514),
            slideBar(0.38188, -0.02626, -1.511),
            slideBar(0.36281, -0.12227, -1.76755),
            slideBar(0.31995, -0.21026, -2.02431),
            slideBar(0.25612, -0.28448, -2.28007),
            slideBar(0.17566, -0.3402, -2.53688),
            slideBar(0.08364, -0.37357, -2.79221),
            slideBar(-0.01389, -0.38277, -2.9669),
            slideBar(-0.11198, -0.38277, -3.05425),
            slideBar(-0.2102, -0.38277, -3.09792),
            slideBar(-0.30832, -0.38277, -3.11976),
            slideBar(-0.40645, -0.38277, -3.13067),
            slideBar(-0.50459, -0.38277, -3.13613),
            slideBar(-0.60274, -0.38277, -3.13886),
            slideBar(-0.70089, -0.38277, -3.14023),
            slideBar(-0.79903, -0.38277, -3.14091),
        ],
        "pq8": [
            slideBar(0.31336, -0.85469, 2.35619),
            slideBar(0.24393, -0.78526, 2.35619),
            slideBar(0.17455, -0.71588, 2.35619),
            slideBar(0.10514, -0.64646, 2.35619),
            slideBar(0.03573, -0.57706, 2.35619),
            slideBar(-0.03365, -0.50768, 2.35619),
            slideBar(-0.10305, -0.43827, 2.35619),
            slideBar(-0.17245, -0.36887, 2.35619),
            slideBar(-0.24185, -0.29948, 2.33454),
            slideBar(-0.30811, -0.22722, 2.20653),
            slideBar(-0.35569, -0.14169, 2.0137),
            slideBar(-0.37991, -0.04685, 1.7895),
            slideBar(-0.37938, 0.05104, 1.54961),
            slideBar(-0.35411, 0.14559, 1.30081),
            slideBar(-0.30558, 0.23059, 1.04843),
            slideBar(-0.23711, 0.30054, 0.79444),
            slideBar(-0.15318, 0.3509, 0.53854),
            slideBar(-0.05918, 0.3782, 0.2827),
            slideBar(0.03867, 0.38083, 0.02707),
            slideBar(0.13401, 0.35865, -0.2295),
            slideBar(0.22055, 0.31293, -0.48601),
            slideBar(0.29266, 0.24675, -0.74184),
            slideBar(0.34574, 0.16451, -0.9987),
            slideBar(0.37609, 0.07145, -1.25514),
            slideBar(0.38188, -0.02626, -1.511),
            slideBar(0.36281, -0.12227, -1.76755),
            slideBar(0.31995, -0.21026, -2.02169),
            slideBar(0.25647, -0.28486, -2.18894),
            slideBar(0.18705, -0.35428, -2.27257),
            slideBar(0.11766, -0.42366, -2.31438),
            slideBar(0.04823, -0.49309, -2.33529),
            slideBar(-0.02115, -0.56248, -2.34574),
            slideBar(-0.09053, -0.63186, -2.35097),
            slideBar(-0.15995, -0.70128, -2.35358),
            slideBar(-0.22934, -0.77066, -2.35489),
            slideBar(-0.29874, -0.84007, -2.35554),
        ],
        "s": [
            slideBar(0.31336, -0.85468, 2.3562),
            slideBar(0.24396, -0.78529, 2.3562),
            slideBar(0.17456, -0.71589, 2.3562),
            slideBar(0.10512, -0.64645, 2.3562),
            slideBar(0.03572, -0.57705, 2.3562),
            slideBar(-0.03367, -0.50766, 2.3562),
            slideBar(-0.10306, -0.43827, 2.3562),
            slideBar(-0.17245, -0.36887, 2.3562),
            slideBar(-0.24186, -0.29947, 2.3562),
            slideBar(-0.31125, -0.23008, 2.3562),
            slideBar(-0.38065, -0.16068, 2.3562),
            slideBar(-0.29484, -0.12213, 0.3927),
            slideBar(-0.20417, -0.08457, 0.3927),
            slideBar(-0.11347, -0.047, 0.3927),
            slideBar(-0.02283, -0.00946, 0.3927),
            slideBar(0.06786, 0.02811, 0.3927),
            slideBar(0.15853, 0.06566, 0.3927),
            slideBar(0.24921, 0.10323, 0.3927),
            slideBar(0.33988, 0.14078, 0.3927),
            slideBar(0.34616, 0.19517, 2.35619),
            slideBar(0.27678, 0.26454, 2.35619),
            slideBar(0.20735, 0.33398, 2.35619),
            slideBar(0.13796, 0.40337, 2.35619),
            slideBar(0.06859, 0.47273, 2.35619),
            slideBar(-0.00084, 0.54216, 2.35619),
            slideBar(-0.07022, 0.61155, 2.35619),
            slideBar(-0.13963, 0.68096, 2.35619),
            slideBar(-0.20902, 0.75035, 2.35619),
            slideBar(-0.27842, 0.81975, 2.35619),
            slideBar(-0.34782, 0.88915, 2.35619),
        ],
        "v1": [
            slideBar(0.34521, -0.83342, 1.9635),
            slideBar(0.30764, -0.74271, 1.9635),
            slideBar(0.27008, -0.65203, 1.9635),
            slideBar(0.23253, -0.56138, 1.9635),
            slideBar(0.19497, -0.47069, 1.9635),
            slideBar(0.1574, -0.37999, 1.9635),
            slideBar(0.11985, -0.28935, 1.9635),
            slideBar(0.08229, -0.19867, 1.9635),
            slideBar(0.04473, -0.10798, 1.9635),
            slideBar(0.00717, -0.01731, -1.1781),
            slideBar(0.03042, -0.07343, -1.1781),
            slideBar(0.06796, -0.16408, -1.1781),
            slideBar(0.10552, -0.25475, -1.1781),
            slideBar(0.14309, -0.34544, -1.1781),
            slideBar(0.18064, -0.4361, -1.1781),
            slideBar(0.2182, -0.52678, -1.1781),
            slideBar(0.25576, -0.61745, -1.1781),
            slideBar(0.29332, -0.70813, -1.1781),
            slideBar(0.33088, -0.79881, -1.1781),
        ],
        "v2": [
            slideBar(0.34521, -0.83342, 1.9635),
            slideBar(0.30764, -0.74271, 1.9635),
            slideBar(0.27008, -0.65203, 1.9635),
            slideBar(0.23253, -0.56138, 1.9635),
            slideBar(0.19497, -0.47069, 1.9635),
            slideBar(0.1574, -0.37999, 1.9635),
            slideBar(0.11985, -0.28935, 1.9635),
            slideBar(0.08229, -0.19867, 1.9635),
            slideBar(0.04473, -0.10798, 1.9635),
            slideBar(0.00717, -0.01731, 1.9635),
            slideBar(0.07343, -0.03042, -0.3927),
            slideBar(0.16408, -0.06796, -0.3927),
            slideBar(0.25475, -0.10552, -0.3927),
            slideBar(0.34544, -0.14309, -0.3927),
            slideBar(0.4361, -0.18064, -0.3927),
            slideBar(0.52678, -0.2182, -0.3927),
            slideBar(0.61745, -0.25576, -0.3927),
            slideBar(0.70813, -0.29332, -0.3927),
            slideBar(0.79881, -0.33088, -0.3927),
        ],
        "v3": [
            slideBar(0.34521, -0.83342, 1.9635),
            slideBar(0.30764, -0.74271, 1.9635),
            slideBar(0.27008, -0.65203, 1.9635),
            slideBar(0.23253, -0.56138, 1.9635),
            slideBar(0.19497, -0.47069, 1.9635),
            slideBar(0.1574, -0.37999, 1.9635),
            slideBar(0.11985, -0.28935, 1.9635),
            slideBar(0.08229, -0.19867, 1.9635),
            slideBar(0.04473, -0.10798, 1.9635),
            slideBar(0.00717, -0.01731, 1.9635),
            slideBar(0.07343, 0.03042, 0.3927),
            slideBar(0.16408, 0.06796, 0.3927),
            slideBar(0.25475, 0.10552, 0.3927),
            slideBar(0.34544, 0.14309, 0.3927),
            slideBar(0.4361, 0.18064, 0.3927),
            slideBar(0.52678, 0.2182, 0.3927),
            slideBar(0.61745, 0.25576, 0.3927),
            slideBar(0.70813, 0.29332, 0.3927),
            slideBar(0.79881, 0.33088, 0.3927),
        ],
        "v4": [
            slideBar(0.34521, -0.83342, 1.9635),
            slideBar(0.30764, -0.74271, 1.9635),
            slideBar(0.27008, -0.65203, 1.9635),
            slideBar(0.23253, -0.56138, 1.9635),
            slideBar(0.19497, -0.47069, 1.9635),
            slideBar(0.1574, -0.37999, 1.9635),
            slideBar(0.11985, -0.28935, 1.9635),
            slideBar(0.08229, -0.19867, 1.9635),
            slideBar(0.04473, -0.10798, 1.9635),
            slideBar(0.00717, -0.01731, 1.9635),
            slideBar(0.03042, 0.07343, 1.1781),
            slideBar(0.06796, 0.16408, 1.1781),
            slideBar(0.10552, 0.25476, 1.1781),
            slideBar(0.14309, 0.34545, 1.1781),
            slideBar(0.18064, 0.43611, 1.1781),
            slideBar(0.2182, 0.52679, 1.1781),
            slideBar(0.25576, 0.61746, 1.1781),
            slideBar(0.29332, 0.70815, 1.1781),
            slideBar(0.33087, 0.7988, 1.1781),
        ],
        "v6": [
            slideBar(0.34521, -0.83342, 1.9635),
            slideBar(0.30764, -0.74271, 1.9635),
            slideBar(0.27008, -0.65203, 1.9635),
            slideBar(0.23253, -0.56138, 1.9635),
            slideBar(0.19497, -0.47069, 1.9635),
            slideBar(0.1574, -0.37999, 1.9635),
            slideBar(0.11985, -0.28935, 1.9635),
            slideBar(0.08229, -0.19867, 1.9635),
            slideBar(0.04473, -0.10798, 1.9635),
            slideBar(0.00717, -0.01731, 1.9635),
            slideBar(-0.07343, 0.03042, 2.74889),
            slideBar(-0.16408, 0.06796, 2.74889),
            slideBar(-0.25475, 0.10552, 2.74889),
            slideBar(-0.34544, 0.14309, 2.74889),
            slideBar(-0.4361, 0.18064, 2.74889),
            slideBar(-0.52678, 0.2182, 2.74889),
            slideBar(-0.61745, 0.25576, 2.74889),
            slideBar(-0.70813, 0.29332, 2.74889),
            slideBar(-0.79881, 0.33088, 2.74889),
        ],
        "v7": [
            slideBar(0.34521, -0.83342, 1.9635),
            slideBar(0.30764, -0.74271, 1.9635),
            slideBar(0.27008, -0.65203, 1.9635),
            slideBar(0.23253, -0.56138, 1.9635),
            slideBar(0.19497, -0.47069, 1.9635),
            slideBar(0.1574, -0.37999, 1.9635),
            slideBar(0.11985, -0.28935, 1.9635),
            slideBar(0.08229, -0.19867, 1.9635),
            slideBar(0.04473, -0.10798, 1.9635),
            slideBar(0.00717, -0.01731, 1.9635),
            slideBar(-0.07343, -0.03042, -2.74889),
            slideBar(-0.16408, -0.06796, -2.74889),
            slideBar(-0.25475, -0.10552, -2.74889),
            slideBar(-0.34544, -0.14309, -2.74889),
            slideBar(-0.4361, -0.18064, -2.74889),
            slideBar(-0.52678, -0.2182, -2.74889),
            slideBar(-0.61745, -0.25576, -2.74889),
            slideBar(-0.70813, -0.29332, -2.74889),
            slideBar(-0.79881, -0.33088, -2.74889),
        ],
        "v8": [
            slideBar(0.34521, -0.83342, 1.9635),
            slideBar(0.30764, -0.74271, 1.9635),
            slideBar(0.27008, -0.65203, 1.9635),
            slideBar(0.23253, -0.56138, 1.9635),
            slideBar(0.19497, -0.47069, 1.9635),
            slideBar(0.1574, -0.37999, 1.9635),
            slideBar(0.11985, -0.28935, 1.9635),
            slideBar(0.08229, -0.19867, 1.9635),
            slideBar(0.04473, -0.10798, 1.9635),
            slideBar(0.00717, -0.01731, 1.9635),
            slideBar(-0.03042, -0.07343, -1.9635),
            slideBar(-0.06796, -0.16408, -1.9635),
            slideBar(-0.10552, -0.25475, -1.9635),
            slideBar(-0.14309, -0.34544, -1.9635),
            slideBar(-0.18064, -0.4361, -1.9635),
            slideBar(-0.2182, -0.52678, -1.9635),
            slideBar(-0.25576, -0.61745, -1.9635),
            slideBar(-0.29332, -0.70813, -1.9635),
            slideBar(-0.33088, -0.79881, -1.9635),
        ],
        "wifi": [
            slideBar(0.3266, -0.80594, 3.14159),
            slideBar(0.28748, -0.70204, 3.14159),
            slideBar(0.2393, -0.58179, 3.14159),
            slideBar(0.18855, -0.45616, 3.14159),
            slideBar(0.13267, -0.33053, 3.14159),
            slideBar(0.06809, -0.17406, 3.14159),
            slideBar(0.00352, -0.02339, 3.14159),
            slideBar(-0.05795, 0.12625, 3.14159),
            slideBar(-0.11942, 0.27341, 3.14159),
            slideBar(-0.17882, 0.42429, 3.14159),
            slideBar(-0.25354, 0.59359, 3.14159),
        ],
    ]

    /// Compact template row, so the table above keeps the reference's `SlideBar(x, y, r)` shape.
    private static func slideBar(_ x: Double, _ y: Double, _ r: Double) -> SlideBar {
        SlideBar(x: x, y: y, r: r)
    }
}
