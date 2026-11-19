import Foundation

/// Slide shape template selection and per-shape chunky-disappearance steps.
nonisolated enum SlideGeometry {
    private static let mirrorKeys = [-1, 1, 8, 7, 6, 5, 4, 3, 2]
    private static let upperHalf: Set<Int> = [1, 2, 7, 8]

    /// Flips a button index across the 1-5 diagonal (1↔1, 2↔8, 3↔7, 4↔6, 5↔5).
    static func mirrorKey(_ key: Int) -> Int {
        guard key >= 0, key < mirrorKeys.count else { return key }
        return mirrorKeys[key]
    }

    static func isUpperHalf(_ key: Int) -> Bool { upperHalf.contains(key) }

    /// Clockwise steps from start to end, always 1...8 (1 when the endpoints coincide).
    static func relativeEnd(startPos: Int, endPos: Int) -> Int {
        (((endPos - startPos) % 8) + 8) % 8 + 1
    }

    /// Base shape key and whether the 1-5 axis mirror applies.
    struct SlideShape {
        var shape: String
        var mirror: Bool
    }

    static func detectSlideShape(type: SlidePathType, startPos: Int, endPos: Int,
                                 midPos: Int? = nil) -> SlideShape? {
        let rel = relativeEnd(startPos: startPos, endPos: endPos)
        switch type {
        case .line:
            guard (3...7).contains(rel) else { return nil }
            let normRel = rel > 5 ? 10 - rel : rel
            return SlideShape(shape: "line\(normRel)", mirror: normRel != rel)
        case .clockwise:
            if isUpperHalf(startPos) {
                return SlideShape(shape: "circle\(rel)", mirror: false)
            }
            return SlideShape(shape: "circle\(mirrorKey(rel))", mirror: true)
        case .counterClockwise:
            if !isUpperHalf(startPos) {
                return SlideShape(shape: "circle\(rel)", mirror: false)
            }
            return SlideShape(shape: "circle\(mirrorKey(rel))", mirror: true)
        case .shortArc:
            if rel == 1 || rel == 5 { return nil }
            if rel < 5 { return SlideShape(shape: "circle\(rel)", mirror: false) }
            return SlideShape(shape: "circle\(mirrorKey(rel))", mirror: true)
        case .vShape:
            if rel == 5 { return nil }
            return SlideShape(shape: "v\(rel)", mirror: false)
        case .spiralClockwiseDouble:
            return SlideShape(shape: "ppqq\(rel)", mirror: false)
        case .spiralCounterClockwiseDouble:
            return SlideShape(shape: "ppqq\(mirrorKey(rel))", mirror: true)
        case .spiralClockwise:
            return SlideShape(shape: "pq\(rel)", mirror: false)
        case .spiralCounterClockwise:
            return SlideShape(shape: "pq\(mirrorKey(rel))", mirror: true)
        case .sCurve:
            return rel != 5 ? nil : SlideShape(shape: "s", mirror: false)
        case .zCurve:
            return rel != 5 ? nil : SlideShape(shape: "s", mirror: true)
        case .via:
            guard let mid = midPos else { return nil }
            let leftCorner = ((startPos + 5) % 8) + 1
            let rightCorner = ((startPos + 1) % 8) + 1
            if mid == leftCorner && (2...5).contains(rel) {
                return SlideShape(shape: "L\(rel)", mirror: false)
            }
            let mirrorRel = ((8 - (rel - 1)) % 8) + 1
            if mid == rightCorner && (2...5).contains(mirrorRel) {
                return SlideShape(shape: "L\(mirrorRel)", mirror: true)
            }
            return nil
        case .wifi:
            return SlideShape(shape: "wifi", mirror: false)
        }
    }

    /// Chunky disappearance step tables: the array starts at 0 and ends at the total arrow count.
    /// Wi-Fi slides hide inclusively (step + 1); other shapes hide whole batches.
    static let stepMap: [String: [Int]] = [
        "line3": [0, 2, 8, 13],
        "line4": [0, 3, 8, 12, 18],
        "line5": [0, 3, 6, 11, 15, 19],
        "circle1": [0, 3, 11, 19, 27, 35, 43, 50, 58, 63],
        "circle2": [0, 3, 7],
        "circle3": [0, 3, 11, 15],
        "circle4": [0, 3, 11, 19, 23],
        "circle5": [0, 3, 11, 19, 27, 31],
        "circle6": [0, 3, 11, 19, 27, 35, 39],
        "circle7": [0, 3, 11, 19, 27, 35, 43, 47],
        "circle8": [0, 3, 11, 19, 27, 35, 43, 50, 55],
        "v1": [0, 3, 6, 11, 15, 19],
        "v2": [0, 3, 6, 11, 15, 19],
        "v3": [0, 3, 6, 11, 15, 19],
        "v4": [0, 3, 6, 11, 15, 19],
        "v6": [0, 3, 6, 11, 15, 19],
        "v7": [0, 3, 6, 11, 15, 19],
        "v8": [0, 3, 6, 11, 15, 19],
        "ppqq1": [0, 3, 7, 13, 17, 26, 32, 35],
        "ppqq2": [0, 3, 7, 12, 16, 25, 28],
        "ppqq3": [0, 3, 6, 12, 15, 22],
        "ppqq4": [0, 3, 7, 12, 16, 25, 29, 35, 40, 44, 49],
        "ppqq5": [0, 3, 7, 12, 16, 25, 29, 35, 40, 44, 49],
        "ppqq6": [0, 3, 7, 12, 16, 25, 28, 34, 38, 41, 48],
        "ppqq7": [0, 3, 7, 13, 17, 27, 31, 37, 41, 46],
        "ppqq8": [0, 3, 7, 12, 16, 25, 29, 35, 41],
        "pq1": [0, 3, 8, 11, 14, 17, 21, 24, 27, 33],
        "pq2": [0, 3, 8, 11, 14, 18, 21, 24, 30],
        "pq3": [0, 3, 9, 12, 16, 19, 23, 27],
        "pq4": [0, 3, 9, 13, 16, 20, 24],
        "pq5": [0, 3, 9, 13, 17, 21],
        "pq6": [0, 3, 8, 11, 15, 18, 21, 25, 28, 31, 35, 38, 42],
        "pq7": [0, 3, 8, 12, 15, 18, 22, 25, 28, 32, 35, 39],
        "pq8": [0, 3, 8, 11, 14, 17, 21, 24, 27, 30, 36],
        "s": [0, 3, 8, 11, 17, 21, 24, 30],
        "wifi": [0, 1, 4, 6, 11],
        "L2": [0, 2, 7, 15, 21, 26, 32],
        "L3": [0, 2, 8, 17, 20, 26, 29, 34],
        "L4": [0, 2, 8, 17, 22, 26, 32],
        "L5": [0, 2, 8, 16, 22, 28],
    ]
}
