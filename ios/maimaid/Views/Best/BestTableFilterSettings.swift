import Foundation

struct BestTableFilterSettings: Codable, Equatable, Sendable {
    var selectedDifficulties: Set<String> = []
    var selectedCategories: Set<String> = []
    var selectedTypes: Set<String> = []
    var selectedRanks: Set<String> = []
    var selectedDXStars: Set<Int> = []
    var selectedFC: Set<String> = []
    var selectedFS: Set<String> = []

    var isEmpty: Bool {
        selectedDifficulties.isEmpty
            && selectedCategories.isEmpty
            && selectedTypes.isEmpty
            && selectedRanks.isEmpty
            && selectedDXStars.isEmpty
            && selectedFC.isEmpty
            && selectedFS.isEmpty
    }

    func matches(entry: RatingUtils.RatingEntry, songCategory: String?) -> Bool {
        if !selectedDifficulties.isEmpty,
           !selectedDifficulties.contains(entry.diff.lowercased()) {
            return false
        }

        if !selectedCategories.isEmpty {
            guard let songCategory, selectedCategories.contains(songCategory) else { return false }
        }

        if !selectedTypes.isEmpty {
            let normalizedType = entry.type.lowercased() == "standard" ? "std" : entry.type.lowercased()
            guard selectedTypes.contains(normalizedType) else { return false }
        }

        if !selectedRanks.isEmpty,
           !selectedRanks.contains(RatingUtils.calculateRank(achievement: entry.achievement)) {
            return false
        }

        if !selectedFC.isEmpty {
            guard let fc = entry.fc, !fc.isEmpty,
                  selectedFC.contains(ThemeUtils.normalizeFC(fc)) else { return false }
        }

        if !selectedFS.isEmpty {
            guard let fs = entry.fs, !fs.isEmpty,
                  selectedFS.contains(ThemeUtils.normalizeFS(fs)) else { return false }
        }

        return selectedDXStars.isEmpty
            || selectedDXStars.contains(RatingUtils.dxStars(dxScore: entry.dxScore, maxDxScore: entry.maxDxScore))
    }
}

extension BestTableFilterSettings {
    init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        selectedDifficulties = try container.decode(Set<String>.self, forKey: .selectedDifficulties)
        selectedCategories = try container.decode(Set<String>.self, forKey: .selectedCategories)
        selectedTypes = try container.decode(Set<String>.self, forKey: .selectedTypes)
        selectedRanks = try container.decode(Set<String>.self, forKey: .selectedRanks)
        // Preserve existing saved filters when upgrading from a version without DX stars.
        selectedDXStars = try container.decodeIfPresent(Set<Int>.self, forKey: .selectedDXStars) ?? []
        selectedFC = try container.decode(Set<String>.self, forKey: .selectedFC)
        selectedFS = try container.decode(Set<String>.self, forKey: .selectedFS)
    }
}
