import Foundation

/// Port of the reference touch draw-order solver (`utils/touchDrawOrder.ts` + `drawGroupSort.ts`).
///
/// Part order decides which petal covers which across sensors, so it has to match the arcade
/// ordering exactly. The reference's unstable quicksort/insertion hybrid is therefore reproduced
/// verbatim: the sort permutes equal keys differently from a stable sort, and that permutation is
/// visible wherever two petals overlap.
nonisolated enum TouchOrder {
    /// Drawable part of one touch note.
    enum Part: Sendable {
        case gauge
        case petal0
        case petal1
        case petal2
        case petal3
        case center
    }

    /// One touch note taking part in the ordering.
    struct TouchSortNote: Sendable {
        var sourceIndex: Int
        var position: String
        var isHold: Bool
        var registeredAtMs: Double
    }

    /// One part to emit, in global draw order.
    struct RankedPart: Sendable {
        var sourceIndex: Int
        var part: Part
    }

    /// Sensor layers in draw order. Petals of the inner rings are painted after the outer ones.
    private static let touchLayers = ["B", "E", "A", "D", "C"]

    private static let defaultLauncherKeys: [Int64] = [
        0x800b_800a, 0x800b_8000, 0x800b_8000, 0x8005_8000, 0x8005_8000, 0x8005_8002,
        0x8005_8002, 0x8005_8000, 0x8005_8000, 0x8005_8000, 0x8005_8002, 0x8005_8002,
        0x8005_8000, 0x8005_8000, 0x8005_8000, 0x8005_8000, 0x8005_8002, 0x8005_8002,
        0x8005_8000, 0x8005_800a, 0x8005_8000, 0x8005_8000, 0x8005_8000, 0x8005_8000,
        0x8005_8000, 0x8005_8000, 0x8005_8000, 0x8006_ffff, 0x8006_ffff, 0x8000_8000,
        0x8000_8000,
    ]

    private struct Element {
        var key: Int64
        var sourceIndex: Int?
        var part: Part?

        init(key: Int64, sourceIndex: Int? = nil, part: Part? = nil) {
            self.key = key
            self.sourceIndex = sourceIndex
            self.part = part
        }
    }

    /// Packs a sorting layer and an order into the reference's 64-bit draw-group key.
    private static func drawGroupKey(layer: Int, order: Int) -> Int64 {
        (Int64(layer + 32768) << 16) | (Int64(order + 32768) & 0xffff)
    }

    private static func appendNoteElements(_ elements: inout [Element], _ note: TouchSortNote) {
        let layer = note.isHold ? 4 : 3
        func add(_ offset: Int, _ part: Part?, _ sortingLayer: Int) {
            elements.append(Element(
                key: drawGroupKey(layer: sortingLayer, order: -note.sourceIndex + offset),
                sourceIndex: note.sourceIndex,
                part: part
            ))
        }
        add(3, .center, layer)
        add(2, .petal0, layer)
        add(note.isHold ? 2 : 1, .petal1, layer)
        add(note.isHold ? 1 : 2, .petal2, layer)
        add(1, .petal3, layer)
        if !note.isHold { add(4, nil, layer) }
        add(2, nil, 5)
        if note.isHold { add(-1, .gauge, layer) }
    }

    /// Resolves the global part order for every queued touch note.
    ///
    /// `notes` is scanned once per sensor layer; each note contributes one element per part, plus
    /// the reference's launcher key block, and the resulting group is sorted with the arcade's
    /// unstable sort.
    static func resolve(_ notes: [TouchSortNote]) -> [RankedPart] {
        // Kotlin's `sortedWith` is stable. The comparator below is a total order except for notes
        // that share a position, timestamp and source index, so decorating with the input index
        // reproduces the reference order exactly without relying on Swift's sort stability.
        let ordered = notes.enumerated().sorted { lhs, rhs in
            let primary = primaryOrder(lhs.element.position, rhs.element.position)
            if primary != 0 { return primary < 0 }
            let registeredLeft = lhs.element.registeredAtMs
            let registeredRight = rhs.element.registeredAtMs
            if registeredLeft != registeredRight { return registeredRight < registeredLeft }
            if lhs.element.sourceIndex != rhs.element.sourceIndex {
                return rhs.element.sourceIndex < lhs.element.sourceIndex
            }
            return lhs.offset < rhs.offset
        }.map { $0.element }

        var parts: [RankedPart] = []
        for layer in touchLayers {
            let group = ordered.filter { $0.position.hasPrefix(layer) }
            if group.isEmpty { continue }
            var elements: [Element] = [Element(key: 0), Element(key: 0xffff_ffff)]
            let sensorCount = layer == "C" ? 1 : 8
            for sensor in 1...sensorCount {
                for note in group where layer == "C" || sensorNumber(note.position) == sensor {
                    appendNoteElements(&elements, note)
                }
                for key in defaultLauncherKeys {
                    elements.append(Element(key: key))
                }
            }
            sortDrawGroup(&elements)
            for element in elements {
                guard let sourceIndex = element.sourceIndex, let part = element.part else { continue }
                parts.append(RankedPart(sourceIndex: sourceIndex, part: part))
            }
        }
        return parts
    }

    /// Primary comparison of the reference comparator: two centre-sensor notes compare equal on
    /// position (the solver then orders them by registration time), everything else compares by
    /// name.
    private static func primaryOrder(_ lhs: String, _ rhs: String) -> Int {
        if lhs.hasPrefix("C") && rhs.hasPrefix("C") { return 0 }
        return positionOrder(lhs, rhs)
    }

    /// `String.compareTo` compares UTF-16 code units; Swift's `String` comparison is
    /// normalization-aware, so the reference's ordering is reproduced on the code units directly.
    private static func positionOrder(_ lhs: String, _ rhs: String) -> Int {
        let left = Array(lhs.utf16)
        let right = Array(rhs.utf16)
        let shared = Swift.min(left.count, right.count)
        for index in 0..<shared where left[index] != right[index] {
            return left[index] < right[index] ? -1 : 1
        }
        if left.count == right.count { return 0 }
        return left.count < right.count ? -1 : 1
    }

    /// Sensor digit of a touch position (`A1` → 1); `C` and malformed names have none.
    private static func sensorNumber(_ position: String) -> Int? {
        let characters = Array(position)
        guard characters.count > 1, characters[1].isASCII,
              let value = characters[1].wholeNumberValue else { return nil }
        return value
    }

    // MARK: - Unstable sort used by the reference implementation

    /// Unstable quicksort/insertion hybrid used by the reference implementation.
    private static func sortDrawGroup(_ items: inout [Element]) {
        func swap(_ a: Int, _ b: Int) {
            let value = items[a]
            items[a] = items[b]
            items[b] = value
        }

        func sortThree(_ a: Int, _ b: Int, _ c: Int) {
            if items[b].key < items[a].key { swap(a, b) }
            if items[c].key < items[b].key { swap(b, c) }
            if items[b].key < items[a].key { swap(a, b) }
        }

        func heapSort(_ first: Int, _ count: Int) {
            func adjust(_ hole: Int, _ length: Int, _ value: Element) {
                var child = hole * 2 + 2
                var currentHole = hole
                while child < length {
                    if items[first + child].key < items[first + child - 1].key { child -= 1 }
                    items[first + currentHole] = items[first + child]
                    currentHole = child
                    child = currentHole * 2 + 2
                }
                if child == length {
                    items[first + currentHole] = items[first + child - 1]
                    currentHole = child - 1
                }
                var parent = (currentHole - 1) / 2
                while currentHole > hole && items[first + parent].key < value.key {
                    items[first + currentHole] = items[first + parent]
                    currentHole = parent
                    parent = (currentHole - 1) / 2
                }
                items[first + currentHole] = value
            }

            var parent = count / 2 - 1
            while parent >= 0 {
                adjust(parent, count, items[first + parent])
                parent -= 1
            }
            var remaining = count
            while remaining > 1 {
                remaining -= 1
                let value = items[first + remaining]
                items[first + remaining] = items[first]
                adjust(0, remaining, value)
            }
        }

        func sort(_ firstIn: Int, _ endIn: Int, _ idealIn: Int) {
            var first = firstIn
            var end = endIn
            var ideal = idealIn
            while end - first >= 32 {
                if ideal <= 0 {
                    heapSort(first, end - first)
                    return
                }
                let last = end - 1
                let middle = first + (last - first) / 2
                if last - first > 64 {
                    let step = (last - first) / 8
                    sortThree(first, first + step, first + 2 * step)
                    sortThree(middle - step, middle, middle + step)
                    sortThree(last - 2 * step, last - step, last)
                    sortThree(first + step, middle, last - step)
                } else {
                    sortThree(first, middle, last)
                }
                swap(middle, last)
                let pivot = items[last].key
                var i = first - 1
                var j = last
                var p = first - 1
                var q = last
                while true {
                    repeat { i += 1 } while items[i].key < pivot && i != last
                    repeat { j -= 1 } while pivot < items[j].key && j != first
                    if i >= j { break }
                    swap(i, j)
                    if items[i].key == pivot {
                        p += 1
                        swap(p, i)
                    }
                    if items[j].key == pivot {
                        q -= 1
                        swap(q, j)
                    }
                }
                swap(i, last)
                var leftEnd = i - 1
                var rightStart = i + 1
                var k = first
                while k < p {
                    swap(k, leftEnd)
                    leftEnd -= 1
                    k += 1
                }
                k = last - 1
                while k > q {
                    swap(k, rightStart)
                    rightStart += 1
                    k -= 1
                }
                let halfIdeal = ideal / 2
                ideal = halfIdeal + halfIdeal / 2
                let leftSize = leftEnd + 1 - first
                let rightSize = end - rightStart
                if leftSize < rightSize {
                    sort(first, leftEnd + 1, leftSize)
                    first = rightStart
                } else {
                    sort(rightStart, end, rightSize)
                    end = leftEnd + 1
                }
            }
            var index = first + 1
            while index < end {
                var j = index
                while j > first && items[j].key < items[j - 1].key {
                    swap(j, j - 1)
                    j -= 1
                }
                index += 1
            }
        }

        sort(0, items.count, items.count)
    }
}

nonisolated extension TouchOrder.Part {
    /// Petal slot of a part, derived the way the reference reads `part.name.last()`. `nil` for the
    /// centre dot and the hold gauge.
    var petalIndex: Int? {
        switch self {
        case .petal0: return 0
        case .petal1: return 1
        case .petal2: return 2
        case .petal3: return 3
        case .gauge, .center: return nil
        }
    }
}
