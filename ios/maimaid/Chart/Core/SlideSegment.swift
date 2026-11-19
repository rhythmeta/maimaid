import CoreGraphics
import Foundation

/// Slide path symbol, matching simai's path notation.
nonisolated enum SlidePathType: String, Sendable, CaseIterable {
    case line = "-"
    case clockwise = ">"
    case counterClockwise = "<"
    case shortArc = "^"
    case vShape = "v"
    case spiralClockwise = "p"
    case spiralClockwiseDouble = "pp"
    case spiralCounterClockwise = "q"
    case spiralCounterClockwiseDouble = "qq"
    case sCurve = "s"
    case zCurve = "z"
    case wifi = "w"
    case via = "V"

    /// Symbol notation, kept for debug descriptions.
    var notation: String { rawValue }

    init?(symbol: Character) {
        switch symbol {
        case "-": self = .line
        case ">": self = .clockwise
        case "<": self = .counterClockwise
        case "^": self = .shortArc
        case "v": self = .vShape
        case "p": self = .spiralClockwise
        case "q": self = .spiralCounterClockwise
        case "s": self = .sCurve
        case "z": self = .zCurve
        case "w": self = .wifi
        case "V": self = .via
        default: return nil
        }
    }
}

/// A point in canvas points.
nonisolated struct ChartPoint: Sendable {
    var x: Double
    var y: Double

    init(_ x: Double, _ y: Double) {
        self.x = x
        self.y = y
    }

    static let zero = ChartPoint(0, 0)
}

/// One sampled point of a slide path length table.
nonisolated struct ArcLutPoint: Sendable {
    var x: Double
    var y: Double
    var angle: Double
    var s: Double
}

/// A unit-circle guide arrow from the slide bar template.
nonisolated struct SlideBar: Sendable {
    var x: Double
    var y: Double
    var r: Double
}

/// A guide arrow already transformed into canvas space.
nonisolated struct SlideChainPoint: Sendable {
    var x: Double
    var y: Double
    var angle: Double
}

/// One segment of a slide path. Geometry caches mirror the reference implementation and are
/// invalidated when the judgment ring radius or the mirror mode changes.
nonisolated final class SlideSegment {
    let type: SlidePathType
    let startPos: Int
    let endPos: Int
    let midPos: Int?

    var cachedLength: Double?
    var cachedLut: [ArcLutPoint]?
    var cachedRadius: Float = -1
    var cachedMirrorMode: MirrorMode?
    var cachedChain: [SlideChainPoint]?
    var cachedChainRadius: Float = -1
    var cachedChainMirror: MirrorMode?

    init(type: SlidePathType, startPos: Int, endPos: Int, midPos: Int? = nil) {
        self.type = type
        self.startPos = startPos
        self.endPos = endPos
        self.midPos = midPos
    }

    var signatureKey: String {
        "\(type.rawValue):\(startPos):\(endPos):\(midPos.map(String.init) ?? "")"
    }

    var debugDescription: String {
        "\(type.notation)\(startPos)-\(endPos)"
    }
}

/// Note position in one frame: canvas coordinates, approach scale and visibility.
nonisolated struct NoteRenderPosition: Sendable {
    var x: Double
    var y: Double
    var scale: Double
    var visible: Bool

    static let invisible = NoteRenderPosition(x: 0, y: 0, scale: 0, visible: false)
}
