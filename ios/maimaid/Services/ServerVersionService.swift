import Foundation
import SwiftData

enum GameServer: String, CaseIterable, Identifiable, Codable, Sendable {
    case jp
    case intl
    case cn

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .jp:   return String(localized: "server.jp")
        case .intl: return String(localized: "server.intl")
        case .cn:   return String(localized: "server.cn")
        }
    }

    /// Maps to the corresponding region boolean on Sheet (Legacy, now partially deprecated by time-offset logic)
    var regionKeyPath: KeyPath<Sheet, Bool> {
        switch self {
        case .jp:   return \.regionJp
        case .intl: return \.regionIntl
        case .cn:   return \.regionCn
        }
    }
}

@MainActor
class ServerVersionService {
    static let shared = ServerVersionService()

    private init() {}

    /// Determines the latest version for a given server by finding the newest
    /// version with at least one playable song on that server.
    /// Deleted songs (not playable on ANY server) are excluded.
    func cutoffDate(for server: GameServer) -> String {
        let now = Date()
        let calendar = Calendar.current

        switch server {
        case .jp:
            return "9999-12-31" // All JP songs are playable
        case .cn:
            let date = calendar.date(byAdding: .month, value: -15, to: now) ?? now
            return date.formatted(.iso8601.year().month().day().dateSeparator(.dash))
        case .intl:
            let date = calendar.date(byAdding: .month, value: -4, to: now) ?? now
            return date.formatted(.iso8601.year().month().day().dateSeparator(.dash))
        }
    }

    func isPlayable(song: Song, cutoff: String, server: GameServer? = nil) -> Bool {
        // Exclude utage
        if song.category.lowercased().contains("utage") || song.category.contains("宴") {
            return false
        }

        // Exclude deleted songs (no regions at all)
        let isDeleted = song.sheets.isEmpty || song.sheets.allSatisfy { sheet in
            !sheet.regionJp && !sheet.regionIntl && !sheet.regionCn
        }
        if isDeleted { return false }

        // If the song explicitly has the region set to true for the specified server, it is actively playable
        if let srv = server {
            let hasRegion = song.sheets.contains { sheet in
                switch srv {
                case .jp: return sheet.regionJp
                case .intl: return sheet.regionIntl
                case .cn: return sheet.regionCn
                }
            }
            if hasRegion {
                return true
            }
        }

        guard let releaseDateStr = song.releaseDate, !releaseDateStr.isEmpty else {
            return true // Playable if missing date
        }

        return releaseDateStr <= cutoff
    }

    func latestVersion(for server: GameServer, songs: [Song]) -> String {
        let sequence = UserDefaults.app.maimaiVersionSequence
        let snapshots = songs.map { song in
            let sheets = song.sheets
            let hasJpRegion = sheets.contains { $0.regionJp }
            let hasIntlRegion = sheets.contains { $0.regionIntl }
            let hasCnRegion = sheets.contains { $0.regionCn }
            return ServerVersionSongSnapshot(
                version: song.version,
                category: song.category,
                releaseDate: song.releaseDate,
                hasAnyRegion: hasJpRegion || hasIntlRegion || hasCnRegion,
                hasJpRegion: hasJpRegion,
                hasIntlRegion: hasIntlRegion,
                hasCnRegion: hasCnRegion
            )
        }
        return ServerVersionCalculator.latestVersion(
            for: server.rawValue,
            songs: snapshots,
            sequence: sequence,
            cutoff: cutoffDate(for: server),
            versionReleaseDates: ServerVersionCalculator.releaseDates(from: UserDefaults.app.maimaiVersionsData),
            fallback: ThemeUtils.latestVersion
        )
    }

    /// Returns a dictionary of latest versions for all servers
    func allLatestVersions(songs: [Song]) -> [GameServer: String] {
        var result: [GameServer: String] = [:]
        for server in GameServer.allCases {
            result[server] = latestVersion(for: server, songs: songs)
        }
        return result
    }

    /// Returns the active user profile, or nil if none exists
    func activeProfile(context: ModelContext) -> UserProfile? {
        let descriptor = FetchDescriptor<UserProfile>(predicate: #Predicate { $0.isActive == true })
        return try? context.fetch(descriptor).first
    }

    /// Returns the latest version for the active user's server
    func latestVersionForActiveUser(songs: [Song], context: ModelContext) -> String {
        guard let profile = activeProfile(context: context),
              let server = GameServer(rawValue: profile.server) else {
            return ThemeUtils.latestVersion
        }
        return latestVersion(for: server, songs: songs)
    }
}
