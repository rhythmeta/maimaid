import CoreGraphics

/// Centered square lattice shared by the catalog, score query, and collections grids.
struct MaimaiCatalogGridGeometry {
    let width: CGFloat
    let count: Int
    let zoom: CGFloat
    let gap: CGFloat = 2

    var columns: Int { zoom < 1.5 ? 5 : 3 }
    var size: CGFloat {
        sizeFor(5) + (sizeFor(3) - sizeFor(5)) * (zoom - 1).clamped(to: 0...1)
    }
    var step: CGFloat { size + gap }
    var height: CGFloat {
        guard count > 0 else { return 0 }
        return max(0, CGFloat((count + columns - 1) / columns) * step - gap)
    }

    func sizeFor(_ columns: Int) -> CGFloat {
        max(1, (width - gap * CGFloat(columns - 1)) / CGFloat(columns))
    }

    func frame(_ index: Int) -> CGRect {
        let origin = (width - size) / 2 - CGFloat(columns / 2) * step
        return CGRect(
            x: origin + CGFloat(index % columns) * step,
            y: CGFloat(index / columns) * step,
            width: size,
            height: size
        )
    }

    func visible(top: CGFloat, height: CGFloat) -> Range<Int> {
        guard count > 0, height > 0 else { return 0..<0 }
        let first = max(0, Int(floor(top / step)) - 2) * columns
        let last = min(count, (max(0, Int(ceil((top + height) / step))) + 2) * columns)
        return min(first, last)..<last
    }

    func zoomForSize(_ targetSize: CGFloat) -> CGFloat {
        let denominator = sizeFor(3) - sizeFor(5)
        guard denominator > 0 else { return 1 }
        return (1 + (targetSize - sizeFor(5)) / denominator).clamped(to: 1...2)
    }
}

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
