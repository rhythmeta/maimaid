import CoreGraphics
import Foundation
import SwiftUI

/// Frame driver for the chart stage, a direct port of the reference `MainRenderer.renderFrame`
/// layer order: stage → fireworks → HUD → tracks → slide stars → approach → heads → touches →
/// effects.
nonisolated final class ChartRenderer {
    // The painter graph needs a `RenderContext`, which needs a SwiftUI `GraphicsContext` that only
    // exists inside the draw closure. The graph is therefore built on the first frame and the shared
    // context is re-pointed at the current canvas on every frame.
    private var context: RenderContext!
    private var notePainter: NotePainter!
    private var slidePainter: SlidePainter!
    private var holdPainter: HoldPainter!
    private var touchPainter: TouchPainter!
    private var effectsPainter: EffectsPainter!
    private var stagePainter: StagePainter!
    private let bpmFlash = BpmChangeFlash()

    private var headFillPaint = ChartPaint()
    private var headStrokePaint: ChartPaint = {
        var paint = ChartPaint()
        paint.style = .stroke
        return paint
    }()

    private var scene: ChartSceneGraph?
    private var touchQueue: [TouchPainter.TouchDrawCommand] = []
    private var approachArcs: [ApproachArc] = []

    /// Renders one frame. `sizePx` is the square stage side in points.
    func render(canvas: ChartCanvas, sizePx: Float, scene: ChartSceneGraph, currentBeats: Double,
                config: ChartRenderConfig, isPlaying: Bool, background: ChartImage?,
                sensorImage: ChartImage?, speedMultiplier: Double = 1) {
        ensureGraph(canvas: canvas, sizePx: sizePx, config: config)
        if self.scene !== scene {
            self.scene = scene
            bpmFlash.reset()
        }

        context.config = config
        context.canvas = canvas
        context.logicalSize = sizePx
        context.centerX = sizePx / 2
        context.centerY = sizePx / 2
        context.radius = sizePx * 0.45

        let prepared = scene.prepared
        let timing = scene.timeline
        let currentTimeMs = timing.msFromBeat(currentBeats)
        let currentBpm = timing.bpmAtBeat(currentBeats)
        let divisor = timing.divisorAtBeat(currentBeats)

        stagePainter.clear(background)
        stagePainter.renderJudgmentLine(sensorImage)

        if config.showFireworks && !prepared.fireworkTouches.isEmpty {
            canvas.withSave { inner in
                clipCircle(inner, radius: sizePx / 2)
                effectsPainter.renderTouchFireworks(prepared.fireworkTouches, currentTimeMs: currentTimeMs)
            }
        }

        if config.showBpm {
            stagePainter.renderBpmDisplay(
                currentBpm: currentBpm,
                currentBeats: currentBeats,
                divisor: divisor,
                keysPerSecond: ChartKeyDensity.keysPerSecond(scene.keyPressTimes, currentTimeMs: currentTimeMs),
                bpmFlashColor: bpmFlash.color(forBpm: currentBpm, isPlaying: isPlaying,
                                              baseColor: ChartConstants.Colors.white)
            )
        }
        if config.showNoteTotal || config.showBreakCount || speedMultiplier > 1 {
            stagePainter.renderNoteCounts(
                totalNotes: prepared.noteCompletionTimes.count,
                completedNotes: prepared.countCompleted(prepared.noteCompletionTimes, currentTimeMs),
                totalBreaks: prepared.breakCompletionTimes.count,
                completedBreaks: prepared.countCompleted(prepared.breakCompletionTimes, currentTimeMs),
                totalBreaksNoEx: prepared.breakNoExCompletionTimes.count,
                completedBreaksNoEx: prepared.countCompleted(prepared.breakNoExCompletionTimes, currentTimeMs),
                speedLabel: speedMultiplier > 1 ? "\(speedMultiplier)X" : nil
            )
        }

        canvas.withSave { inner in
            clipCircle(inner, radius: sizePx / 2)
            let lookAheadMs = ChartConstants.baseApproachTimeMs / config.hiSpeed / prepared.minHiSpeed

            renderSlideTracks(scene, currentTimeMs: currentTimeMs, lookAheadMs: lookAheadMs)
            renderSlideStars(scene, currentTimeMs: currentTimeMs, lookAheadMs: lookAheadMs)
            renderApproachIndicators(scene, currentTimeMs: currentTimeMs, currentBeats: currentBeats,
                                     lookAheadMs: lookAheadMs)
            renderLayeredHeads(scene, currentTimeMs: currentTimeMs, lookAheadMs: lookAheadMs)
            renderTouchBorders(scene, currentTimeMs: currentTimeMs, lookAheadMs: lookAheadMs)
            renderTouches(scene, currentTimeMs: currentTimeMs, lookAheadMs: lookAheadMs)
            if config.showHitEffect {
                renderEffects(scene, currentTimeMs: currentTimeMs, lookAheadMs: lookAheadMs)
            }
        }
    }

    /// Builds the painter graph once, on the first frame, when a real graphics context exists.
    private func ensureGraph(canvas: ChartCanvas, sizePx: Float, config: ChartRenderConfig) {
        guard context == nil else { return }
        let created = RenderContext(
            canvas: canvas,
            logicalSize: sizePx,
            centerX: sizePx / 2,
            centerY: sizePx / 2,
            radius: sizePx * 0.45,
            config: config
        )
        context = created
        notePainter = NotePainter(created)
        slidePainter = SlidePainter(created, notePainter)
        holdPainter = HoldPainter(created)
        touchPainter = TouchPainter(created)
        effectsPainter = EffectsPainter(created, notePainter, touchPainter)
        stagePainter = StagePainter(created)
    }

    /// Clips the stage to its disc.
    private func clipCircle(_ canvas: ChartCanvas, radius: Float) {
        let rect = CGRect(
            x: CGFloat(context.centerX - radius),
            y: CGFloat(context.centerY - radius),
            width: CGFloat(radius * 2),
            height: CGFloat(radius * 2)
        )
        canvas.clipPath(Path(ellipseIn: rect))
    }

    // MARK: - Slides

    private func renderSlideTracks(_ scene: ChartSceneGraph, currentTimeMs: Double, lookAheadMs: Double) {
        let range = scene.prepared.slideIndex.range(nowMs: currentTimeMs, lookAheadMs: lookAheadMs)
        guard !range.isEmpty else { return }
        for index in stride(from: range.upperBound - 1, through: range.lowerBound, by: -1) {
            let note = scene.prepared.slides[index]
            let meta = scene.prepared.metaOf(note)
            slidePainter.renderSlide(note, currentTimeMs: currentTimeMs, mode: .tracks,
                                     isSimultaneous: meta.simultaneousSlideCount >= 2)
        }
    }

    private func renderSlideStars(_ scene: ChartSceneGraph, currentTimeMs: Double, lookAheadMs: Double) {
        let range = scene.prepared.slideIndex.range(nowMs: currentTimeMs, lookAheadMs: lookAheadMs)
        guard !range.isEmpty else { return }
        for index in stride(from: range.upperBound - 1, through: range.lowerBound, by: -1) {
            let note = scene.prepared.slides[index]
            let meta = scene.prepared.metaOf(note)
            slidePainter.renderSlide(note, currentTimeMs: currentTimeMs, mode: .stars,
                                     isSimultaneous: meta.simultaneousSlideCount >= 2)
        }
    }

    // MARK: - Approach indicators

    private func renderApproachIndicators(_ scene: ChartSceneGraph, currentTimeMs: Double,
                                          currentBeats: Double, lookAheadMs: Double) {
        let range = scene.prepared.approachIndex.range(nowMs: currentTimeMs, lookAheadMs: lookAheadMs)
        guard !range.isEmpty else { return }
        for groupIndex in range {
            let group = scene.prepared.approachGroups[groupIndex]
            var visible: [(Int, NoteRenderPosition)] = []
            for item in group.notes {
                let note = item.note
                var position: NoteRenderPosition
                let color: ChartColor
                switch note.kind {
                case .holdStart:
                    position = notePainter.calculateNotePosition(note, currentTimeMs: currentTimeMs)
                    let meta = scene.prepared.metaOf(note)
                    let isSimultaneous = meta.simultaneousNoteCount >= 2
                    color = note.isBreakHold
                        ? ChartConstants.Colors.breakOrange
                        : (isSimultaneous ? ChartConstants.Colors.simultaneousGold
                                          : ChartConstants.Colors.tapPink)
                case .slide:
                    position = slidePainter.calculateSlideStartPosition(note, currentTimeMs: currentTimeMs)
                    let meta = scene.prepared.metaOf(note)
                    let isSimultaneous = meta.simultaneousNoteCount >= 2
                    color = note.isStartBreak
                        ? ChartConstants.Colors.breakOrange
                        : (isSimultaneous ? ChartConstants.Colors.simultaneousGold
                                          : (note.hasTapHead ? ChartConstants.Colors.tapPink
                                                             : ChartConstants.Colors.slideCyan))
                default:
                    continue
                }
                guard position.visible else { continue }
                if note.timing - currentBeats <= 0 { continue }
                notePainter.renderApproachArc(position: item.position, noteX: position.x, noteY: position.y,
                                              color: color)
                visible.append((item.position, position))
            }
            if visible.count >= 2, let first = visible.first?.1 {
                let distance = ((first.x - Double(context.centerX)) * (first.x - Double(context.centerX))
                    + (first.y - Double(context.centerY)) * (first.y - Double(context.centerY))).squareRoot()
                for i in visible.indices {
                    for j in (i + 1)..<visible.count {
                        notePainter.renderSimultaneousConnector(from: visible[i].0, to: visible[j].0,
                                                                distance: distance,
                                                                color: ChartConstants.Colors.simultaneousGold)
                    }
                }
            }
        }
    }

    // MARK: - Layered heads

    private func renderLayeredHeads(_ scene: ChartSceneGraph, currentTimeMs: Double, lookAheadMs: Double) {
        let range = scene.prepared.layeredIndex.range(nowMs: currentTimeMs, lookAheadMs: lookAheadMs)
        guard !range.isEmpty else { return }

        approachArcs.removeAll(keepingCapacity: true)
        for index in stride(from: range.upperBound - 1, through: range.lowerBound, by: -1) {
            guard case .tap(let tap) = scene.prepared.layeredHeads[index] else { continue }
            if tap.timingMs <= currentTimeMs { continue }
            let position = notePainter.calculateNotePosition(tap, currentTimeMs: currentTimeMs)
            guard position.visible else { continue }
            let meta = scene.prepared.metaOf(tap)
            let distance = ((position.x - Double(context.centerX)) * (position.x - Double(context.centerX))
                + (position.y - Double(context.centerY)) * (position.y - Double(context.centerY))).squareRoot()
            approachArcs.append(ApproachArc(
                position: tap.position,
                distance: distance,
                color: tapApproachColor(tap, isSimultaneous: meta.simultaneousNoteCount >= 2)
            ))
        }
        notePainter.renderApproachArcsBatch(approachArcs)

        for index in stride(from: range.upperBound - 1, through: range.lowerBound, by: -1) {
            switch scene.prepared.layeredHeads[index] {
            case .tap(let note):
                renderSingleTap(scene, tap: note, currentTimeMs: currentTimeMs)
            case .hold(let note):
                renderSingleHold(scene, hold: note, currentTimeMs: currentTimeMs)
            case .slideStart(let note):
                renderSingleSlideStart(scene, slide: note, currentTimeMs: currentTimeMs)
            }
        }
    }

    private func tapApproachColor(_ tap: ChartNote, isSimultaneous: Bool) -> ChartColor {
        if tap.isBreak { return ChartConstants.Colors.breakOrange }
        if isSimultaneous { return ChartConstants.Colors.simultaneousGold }
        if tap.isStar { return ChartConstants.Colors.slideCyan }
        return ChartConstants.Colors.tapPink
    }

    private func renderSingleTap(_ scene: ChartSceneGraph, tap: ChartNote, currentTimeMs: Double) {
        let position = notePainter.calculateNotePosition(tap, currentTimeMs: currentTimeMs)
        guard position.visible else { return }
        let meta = scene.prepared.metaOf(tap)
        let isSimultaneous = meta.simultaneousNoteCount >= 2

        if tap.isStar {
            let rotation = tap.isSpinningStar ? -currentTimeMs * (2 * Double.pi) / 1000.0 : 0.0
            renderStarHead(
                x: position.x, y: position.y, scale: position.scale,
                color: starHeadColor(timing: tap.timing, isBreak: tap.isBreak, isSimultaneous: isSimultaneous),
                rotation: rotation, isEx: tap.isEx, isBreak: tap.isBreak, isSimultaneous: isSimultaneous
            )
        } else {
            notePainter.renderTapNote(
                x: position.x, y: position.y, scale: position.scale, position: tap.position,
                isBreak: tap.isBreak, isSimultaneous: isSimultaneous, isEx: tap.isEx,
                timing: tap.timing, exScale: exScale()
            )
        }

        if let breakIndex = meta.noExBreakIndex, context.config.showBreakIndex, tap.isBreak, !tap.isEx {
            notePainter.renderBreakIndex(x: position.x, y: position.y, scale: position.scale, index: breakIndex)
        }
    }

    private func renderSingleHold(_ scene: ChartSceneGraph, hold: ChartNote, currentTimeMs: Double) {
        let startPos = notePainter.calculateNotePosition(hold, currentTimeMs: currentTimeMs)
        guard startPos.visible else { return }
        guard let holdEnd = scene.prepared.holdEndMap["\(hold.position):\(hold.timing)"] else { return }
        let endPos = notePainter.calculateNotePosition(holdEnd, currentTimeMs: currentTimeMs)
        let meta = scene.prepared.metaOf(hold)
        let isSimultaneous = meta.simultaneousNoteCount >= 2
        let colors = gradientColors(ddr: ddrColor(hold.timing), isBreak: hold.isBreakHold,
                                    isSimultaneous: isSimultaneous)
        holdPainter.renderHold(
            startPos: startPos, endPos: endPos, position: hold.position, colors: colors,
            isEx: hold.isEx, holdStart: hold, holdEnd: holdEnd, currentTimeMs: currentTimeMs,
            isBreak: hold.isBreakHold, isSimultaneous: isSimultaneous, exScale: exScale()
        )
        if let breakIndex = meta.noExBreakIndex, context.config.showBreakIndex, hold.isBreakHold, !hold.isEx {
            notePainter.renderBreakIndex(x: startPos.x, y: startPos.y, scale: startPos.scale, index: breakIndex)
        }
    }

    private func renderSingleSlideStart(_ scene: ChartSceneGraph, slide: ChartNote, currentTimeMs: Double) {
        let position = slidePainter.calculateSlideStartPosition(slide, currentTimeMs: currentTimeMs)
        guard position.visible else { return }
        let meta = scene.prepared.metaOf(slide)
        let isSimultaneous = meta.simultaneousNoteCount >= 2

        if slide.hasTapHead {
            notePainter.renderTapNote(
                x: position.x, y: position.y, scale: position.scale, position: slide.position,
                isBreak: slide.isStartBreak, isSimultaneous: isSimultaneous, isEx: slide.isEx,
                timing: slide.timing, exScale: exScale()
            )
        } else {
            let color = starHeadColor(timing: slide.timing, isBreak: slide.isStartBreak,
                                      isSimultaneous: isSimultaneous)
            let rotation = context.config.slideRotation
                ? slidePainter.calculateStarRotation(slide, currentTimeMs: currentTimeMs) : 0.0
            if slide.isSplitSlide {
                let noteSize = starNoteSize(scale: position.scale)
                if slide.isEx {
                    slidePainter.renderExSplitStarRing(
                        x: position.x, y: position.y, size: noteSize, isBreak: slide.isStartBreak,
                        isSimultaneous: isSimultaneous, exScale: exScale()
                    )
                }
                renderSplitSlideStar(x: position.x, y: position.y, size: noteSize, color: color,
                                     rotation: rotation, isEx: slide.isEx)
            } else {
                renderStarHead(
                    x: position.x, y: position.y, scale: position.scale, color: color,
                    rotation: rotation, isEx: slide.isEx, isBreak: slide.isStartBreak,
                    isSimultaneous: isSimultaneous
                )
            }
        }

        if let breakIndex = meta.noExBreakIndex, context.config.showBreakIndex, slide.isStartBreak, !slide.isEx {
            notePainter.renderBreakIndex(x: position.x, y: position.y, scale: position.scale, index: breakIndex)
        }
    }

    private func starHeadColor(timing: Double, isBreak: Bool, isSimultaneous: Bool) -> ChartColor {
        if let ddr = ddrColor(timing) { return ddr }
        if isBreak { return ChartConstants.Colors.breakOrange }
        if isSimultaneous { return ChartConstants.Colors.slideSimultaneous }
        if context.config.pinkSlideStart { return ChartConstants.Colors.slidePink }
        return ChartConstants.Colors.slideCyan
    }

    private func exScale() -> Double {
        context.config.highlightExNotes ? 1.2 : 1.0
    }

    private func starNoteSize(scale: Double) -> Double {
        (Double(context.radius) / 12.5) * scale * 1.15 * 1.25
    }

    private func renderStarHead(x: Double, y: Double, scale: Double, color: ChartColor, rotation: Double,
                                isEx: Bool, isBreak: Bool, isSimultaneous: Bool) {
        let noteSize = starNoteSize(scale: scale)
        if isEx {
            slidePainter.renderExStarRing(x: x, y: y, size: noteSize, isBreak: isBreak,
                                          isSimultaneous: isSimultaneous, exScale: exScale())
        }
        slidePainter.drawStar(x: x, y: y, size: noteSize, color: color, rotation: rotation, isEx: isEx)
    }

    /// Split-slide head: two interleaved stars instead of one.
    private func renderSplitSlideStar(x: Double, y: Double, size: Double, color: ChartColor,
                                      rotation: Double, isEx: Bool) {
        let canvas = context.canvas
        let radius = Double(context.radius)
        canvas.withSave { inner in
            if rotation != 0 {
                inner.translate(CGFloat(x), CGFloat(y))
                inner.rotate(degrees: CGFloat(rotation * 180 / Double.pi), pivotX: 0, pivotY: 0)
                inner.translate(CGFloat(-x), CGFloat(-y))
            }

            func star(outer: Double, innerRadius: Double, baseAngle: Double) -> Path {
                var path = Path()
                for index in 0..<10 {
                    let angle = Double(index) * Double.pi / 5 + baseAngle
                    let radiusValue = index % 2 == 0 ? outer : innerRadius
                    let px = x + cos(angle) * radiusValue
                    let py = y + sin(angle) * radiusValue
                    if index == 0 {
                        path.move(to: CGPoint(x: px, y: py))
                    } else {
                        path.addLine(to: CGPoint(x: px, y: py))
                    }
                }
                path.closeSubpath()
                return path
            }

            let baseAngles = [-Double.pi / 2, Double.pi / 2]

            if !isEx {
                headStrokePaint.strokeWidth = CGFloat((2 * radius / 300) * 3)
                headStrokePaint.strokeCap = .butt
                headStrokePaint.strokeJoin = .miter
                headStrokePaint.color = ChartConstants.Colors.black
                headStrokePaint.style = .stroke
                for baseAngle in baseAngles {
                    inner.drawPath(star(outer: size, innerRadius: size * 0.5, baseAngle: baseAngle),
                                   headStrokePaint)
                }
            }

            headFillPaint.style = .fill
            for baseAngle in baseAngles {
                var ringPath = star(outer: size, innerRadius: size * 0.5, baseAngle: baseAngle)
                ringPath.addPath(star(outer: size * 0.55, innerRadius: size * 0.5 * 0.55,
                                      baseAngle: baseAngle))
                headFillPaint.evenOdd = true
                headFillPaint.color = color
                headFillPaint.alpha = 1
                inner.drawPath(ringPath, headFillPaint)
            }

            headStrokePaint.style = .stroke
            headStrokePaint.strokeWidth = CGFloat(2 * radius / 300)
            headStrokePaint.color = ChartConstants.Colors.white
            for baseAngle in baseAngles {
                inner.drawPath(star(outer: size, innerRadius: size * 0.5, baseAngle: baseAngle),
                               headStrokePaint)
            }

            headFillPaint.style = .fill
            headFillPaint.color = color
            inner.drawCircle(centerX: x, centerY: y, radius: size * 0.15, headFillPaint)
        }
    }

    // MARK: - Touches

    private func renderTouchBorders(_ scene: ChartSceneGraph, currentTimeMs: Double, lookAheadMs: Double) {
        let range = scene.prepared.touchIndex.range(nowMs: currentTimeMs, lookAheadMs: lookAheadMs)
        guard !range.isEmpty else { return }
        let baseApproachTime = ChartConstants.baseApproachTimeMs / context.config.hiSpeed
        var visibleByPos: [String: Int] = [:]
        for index in range {
            let touch = scene.prepared.touches[index]
            if touch.kind == .touchHoldStart { continue }
            let timeDiff = touch.timingMs - currentTimeMs
            let magnitude = abs(touch.hiSpeed ?? 1)
            let approachTime = baseApproachTime / (magnitude > 0 ? magnitude : 1)
            if timeDiff <= approachTime && timeDiff >= -50 {
                if touch.kind == .touch, !touch.touchPosition.isEmpty {
                    visibleByPos[touch.touchPosition, default: 0] += 1
                }
            }
        }
        for index in stride(from: range.upperBound - 1, through: range.lowerBound, by: -1) {
            let touch = scene.prepared.touches[index]
            guard touch.kind == .touch || touch.kind == .touchHoldStart else { continue }
            let position = touchPainter.touchPosition(touch.touchPosition)
            let isSimultaneous = scene.prepared.metaOf(touch).simultaneousNoteCount >= 2
            touchPainter.renderTouchBorder(position, isSimultaneous: isSimultaneous,
                                           visibleCount: visibleByPos[touch.touchPosition] ?? 0)
        }
    }

    private func renderTouches(_ scene: ChartSceneGraph, currentTimeMs: Double, lookAheadMs: Double) {
        let range = scene.prepared.touchIndex.range(nowMs: currentTimeMs, lookAheadMs: lookAheadMs)
        guard !range.isEmpty else { return }
        touchQueue.removeAll(keepingCapacity: true)
        for index in stride(from: range.upperBound - 1, through: range.lowerBound, by: -1) {
            let touch = scene.prepared.touches[index]
            let meta = scene.prepared.metaOf(touch)
            let isSimultaneous = meta.simultaneousNoteCount >= 2
            touchPainter.renderTouch(touch, currentTimeMs: currentTimeMs, isSimultaneous: isSimultaneous,
                                     queue: &touchQueue, sourceNoteIndex: meta.sourceNoteIndex)
        }
        touchPainter.flush(touchQueue)
    }

    // MARK: - Effects

    private func renderEffects(_ scene: ChartSceneGraph, currentTimeMs: Double, lookAheadMs: Double) {
        let holdRange = scene.prepared.holdEffectIndex.range(nowMs: currentTimeMs, lookAheadMs: lookAheadMs)
        effectsPainter.renderHoldPressEffects(scene.prepared.holdEffectNotes,
                                              holdEndMap: scene.prepared.holdEndMap,
                                              currentTimeMs: currentTimeMs, range: holdRange)
        let touchHoldRange = scene.prepared.touchHoldIndex.range(nowMs: currentTimeMs, lookAheadMs: lookAheadMs)
        effectsPainter.renderTouchHoldEffects(scene.prepared.touchHoldNotes, currentTimeMs: currentTimeMs,
                                              range: touchHoldRange)
        effectsPainter.renderTouchHitEffects(scene.prepared.touches, currentTimeMs: currentTimeMs)
        effectsPainter.renderTapHitEffects(scene.prepared, currentTimeMs: currentTimeMs)
    }

    // MARK: - Colors

    private func gradientColors(ddr: ChartColor?, isBreak: Bool, isSimultaneous: Bool) -> [ChartColor] {
        if let ddr {
            return [ddr, mixColor(ddr, ChartConstants.Colors.black, ChartConstants.ddrDarkenRatio)]
        }
        if isBreak {
            return [ChartConstants.Colors.breakGradientStart, ChartConstants.Colors.breakGradientEnd]
        }
        if isSimultaneous {
            return [ChartConstants.Colors.simultaneousGradientStart, ChartConstants.Colors.simultaneousGradientEnd]
        }
        return [ChartConstants.Colors.tapGradientStart, ChartConstants.Colors.tapGradientEnd]
    }

    private func mixColor(_ from: ChartColor, _ target: ChartColor, _ amount: Double) -> ChartColor {
        func mix(_ a: Double, _ b: Double) -> Double { a + (b - a) * amount }
        return ChartColor(
            red: mix(from.red, target.red),
            green: mix(from.green, target.green),
            blue: mix(from.blue, target.blue),
            alpha: from.alpha
        )
    }

    private func ddrColor(_ timing: Double) -> ChartColor? {
        guard context.config.ddrColorMode else { return nil }
        let epsilon = 0.001
        let fractional = abs(timing.truncatingRemainder(dividingBy: 1.0))
        if fractional < epsilon || fractional > 1 - epsilon { return ChartConstants.Colors.ddrRed }
        let half = abs(timing.truncatingRemainder(dividingBy: 0.5))
        if !(half >= epsilon && half <= 0.499) { return ChartConstants.Colors.ddrBlue }
        let quarter = abs(timing.truncatingRemainder(dividingBy: 0.25))
        if !(quarter >= epsilon && quarter <= 0.249) { return ChartConstants.Colors.ddrYellow }
        if context.config.ddrColorExtended {
            let eighth = abs(timing.truncatingRemainder(dividingBy: 0.125))
            if !(eighth >= epsilon && eighth <= 0.124) { return ChartConstants.Colors.ddrOrange }
            let sixth = 1.0 / 6.0
            let remainder = abs(timing.truncatingRemainder(dividingBy: sixth))
            if remainder < epsilon || remainder > sixth - epsilon { return ChartConstants.Colors.ddrCyan }
        }
        return ChartConstants.Colors.ddrGreen
    }
}
