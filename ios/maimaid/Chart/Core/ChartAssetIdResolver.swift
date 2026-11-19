import Foundation

/// Maps a provider song id to the chart-asset ids that may publish its maidata.
nonisolated enum ChartAssetIdResolver {
    static func resolve(providerSongId: Int?, chartType: String) -> Int? {
        guard let id = providerSongId, id > 0 else { return nil }
        if chartType.caseInsensitiveCompare("dx") == .orderedSame && id < 10_000 {
            return id + 10_000
        }
        return id
    }

    static func candidates(providerSongId: Int?, chartType: String) -> [Int] {
        guard let resolved = resolve(providerSongId: providerSongId, chartType: chartType) else { return [] }
        var result: [Int] = [resolved]
        if resolved >= 100_000 {
            let base = resolved % 100_000
            if base > 0 { result.append(base) }
            if (1...9_999).contains(base) { result.append(base + 10_000) }
        } else if resolved >= 10_000 {
            result.append(resolved % 10_000)
        } else {
            result.append(resolved + 10_000)
        }
        var seen = Set<Int>()
        return result.filter { seen.insert($0).inserted }
    }
}
