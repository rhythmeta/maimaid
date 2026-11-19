import Foundation
import Observation

struct BestTableFilterSettings: Codable, Equatable, Sendable {
    var selectedDifficulties: Set<String> = []
    var selectedCategories: Set<String> = []
    var selectedTypes: Set<String> = []
    var selectedRanks: Set<String> = []
    var selectedFC: Set<String> = []
    var selectedFS: Set<String> = []

    var isEmpty: Bool {
        selectedDifficulties.isEmpty
            && selectedCategories.isEmpty
            && selectedTypes.isEmpty
            && selectedRanks.isEmpty
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

        return true
    }
}

/// Persisted Best 50 page options: the theoretical-mode switch and the quick filters.
@MainActor
@Observable
final class BestTablePreferences {
    private static let theoreticalKey = "best50.theoretical"
    private static let filtersKey = "best50.filters"

    var theoretical: Bool {
        didSet {
            guard theoretical != oldValue else { return }
            UserDefaults.app.set(theoretical, forKey: Self.theoreticalKey)
        }
    }

    var filters: BestTableFilterSettings {
        didSet {
            guard filters != oldValue else { return }
            saveFilters()
        }
    }

    init() {
        theoretical = UserDefaults.app.bool(forKey: Self.theoreticalKey)
        if let data = UserDefaults.app.data(forKey: Self.filtersKey),
           let decoded = try? JSONDecoder().decode(BestTableFilterSettings.self, from: data) {
            filters = decoded
        } else {
            filters = BestTableFilterSettings()
        }
    }

    private func saveFilters() {
        guard let data = try? JSONEncoder().encode(filters) else { return }
        UserDefaults.app.set(data, forKey: Self.filtersKey)
    }
}
