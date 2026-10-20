import Foundation

nonisolated struct ServerVersionSongSnapshot: Sendable {
    let version: String?
    let category: String
    let releaseDate: String?
    let hasAnyRegion: Bool
    let hasJpRegion: Bool
    let hasIntlRegion: Bool
    let hasCnRegion: Bool
}

nonisolated enum ServerVersionCalculator {
    private struct VersionMetadata: Decodable {
        let version: String
        let releaseDate: String?
    }

    static func releaseDates(from data: Data?) -> [String: String] {
        guard let data,
              let versions = try? JSONDecoder().decode([VersionMetadata].self, from: data) else {
            return [:]
        }

        return versions.reduce(into: [:]) { result, version in
            guard let releaseDate = version.releaseDate, isValidISODate(releaseDate) else { return }
            result[version.version] = releaseDate
        }
    }

    static func latestVersion(
        for server: String,
        songs: [ServerVersionSongSnapshot],
        sequence: [String],
        cutoff: String,
        versionReleaseDates: [String: String],
        fallback: String
    ) -> String {
        let orderedVersions = sequence.isEmpty
            ? Set(songs.compactMap(\.version)).sorted()
            : sequence

        if server == "cn",
           !orderedVersions.isEmpty,
           orderedVersions.allSatisfy({
               guard let date = versionReleaseDates[$0] else { return false }
               return isValidISODate(date)
           }) {
            let chineseVersions = orderedVersions.filter { version in
                songs.contains { song in
                    song.version == version &&
                    isActive(song) &&
                    song.hasCnRegion
                }
            }
            let eligibleVersion = orderedVersions.last { version in
                guard chineseVersions.contains(version),
                      let releaseDate = versionReleaseDates[version] else {
                    return false
                }
                return releaseDate <= cutoff
            }
            return eligibleVersion ?? chineseVersions.first ?? orderedVersions.first ?? fallback
        }

        var serverVersion = orderedVersions.first ?? fallback
        for version in orderedVersions {
            let activeSongs = songs.filter { $0.version == version && isActive($0) }
            guard !activeSongs.isEmpty else { continue }

            let playableCount = activeSongs.count { song in
                let hasRegion: Bool
                switch server {
                case "jp":
                    hasRegion = song.hasJpRegion
                case "intl":
                    hasRegion = song.hasIntlRegion
                case "cn":
                    hasRegion = song.hasCnRegion
                default:
                    hasRegion = false
                }
                if hasRegion { return true }
                guard let releaseDate = song.releaseDate, !releaseDate.isEmpty else { return true }
                return releaseDate <= cutoff
            }

            guard playableCount > 0 else { break }
            serverVersion = version
            if playableCount < activeSongs.count { break }
        }
        return serverVersion
    }

    private static func isActive(_ song: ServerVersionSongSnapshot) -> Bool {
        !song.category.localizedStandardContains("utage") &&
        !song.category.contains("宴") &&
        song.hasAnyRegion
    }

    private static func isValidISODate(_ value: String) -> Bool {
        guard value.count == 10,
              let date = try? Date(value, strategy: .iso8601.year().month().day()) else {
            return false
        }
        return date.formatted(.iso8601.year().month().day().dateSeparator(.dash)) == value
    }
}
