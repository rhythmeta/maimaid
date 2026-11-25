import Foundation
import Observation

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
