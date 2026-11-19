import Foundation

// The signed-in account's own alias submissions stay searchable before approval, mirroring the
// Android contract. Other accounts only see approved aliases pulled into `Song.aliases`.
extension CommunityAliasService {
    private static let searchableCandidateStatuses: Set<String> = ["pool_private", "voting", "approved"]

    func fetchMySongCandidates(songIdentifier: String, limit: Int = 50) async -> [CommunityAliasMyCandidate] {
        guard isConfigured, isAuthenticated else { return [] }
        guard let rows = await fetchMyCandidates(songIdentifier: songIdentifier, limit: limit) else { return [] }
        let matches = rows.filter { $0.songIdentifier == songIdentifier }
        updateMyAliases(for: songIdentifier, with: matches)
        return matches
    }

    /// Candidate ids the signed-in account submitted, used by the alias board to mark its own
    /// approved aliases (the approved feed only carries the candidate id).
    func fetchMyCandidateIds() async -> Set<String> {
        guard isConfigured, isAuthenticated else { return [] }
        guard let rows = await fetchMyCandidates(songIdentifier: nil, limit: 200) else { return [] }
        return Set(rows.map(\.candidateId))
    }

    /// Pulls every alias the signed-in account submitted so pending aliases stay locally searchable
    /// across launches, including candidates submitted from another device.
    func syncMyAliases() async {
        guard isConfigured, isAuthenticated, let ownerId = BackendSessionManager.shared.currentUser?.id else {
            clearMyAliases()
            return
        }
        ensureMyAliasesOwner(ownerId)
        guard let rows = await fetchMyCandidates(songIdentifier: nil, limit: 200) else { return }
        guard BackendSessionManager.shared.currentUser?.id == ownerId else { return }

        var aliasesBySong: [String: [String]] = [:]
        for row in rows where Self.searchableCandidateStatuses.contains(row.status) {
            aliasesBySong[row.songIdentifier, default: []].append(row.aliasText)
        }
        applyMyAliases(aliasesBySong, ownerId: ownerId)
    }

    /// Makes a just-submitted alias searchable immediately, before the server round-trip completes.
    func rememberSubmittedAlias(songIdentifier: String, aliasText: String) {
        guard let ownerId = BackendSessionManager.shared.currentUser?.id else { return }
        let trimmed = aliasText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }

        ensureMyAliasesOwner(ownerId)

        let existing = myAliasesBySong[songIdentifier] ?? []
        let merged = Self.mergingAliases(existing, with: [trimmed])
        guard merged != existing else { return }

        myAliasesBySong[songIdentifier] = merged
        NotificationCenter.default.post(name: .maimaiSongAliasesDidChange, object: nil)
    }

    func clearMyAliases() {
        myAliasesOwnerId = nil
        guard !myAliasesBySong.isEmpty else { return }
        myAliasesBySong = [:]
        NotificationCenter.default.post(name: .maimaiSongAliasesDidChange, object: nil)
    }

    /// Merges the signed-in account's pending aliases into a song's searchable catalog aliases.
    func mergingSearchableAliases(_ aliases: [String], for songIdentifier: String) -> [String] {
        let mine = myAliasesBySong[songIdentifier] ?? []
        guard !mine.isEmpty else { return aliases }
        return Self.mergingAliases(aliases, with: mine)
    }

    nonisolated static func mergingAliases(_ aliases: [String], with additions: [String]) -> [String] {
        var merged: [String] = []
        var seen = Set<String>()
        for alias in aliases + additions {
            let trimmed = alias.trimmingCharacters(in: .whitespacesAndNewlines)
            let normalized = normalizeAliasText(trimmed)
            guard !normalized.isEmpty, seen.insert(normalized).inserted else { continue }
            merged.append(trimmed)
        }
        return merged
    }

    static func searchableAliasTexts(from rows: [CommunityAliasMyCandidate]) -> [String] {
        mergingAliases(
            [],
            with: rows
                .filter { searchableCandidateStatuses.contains($0.status) }
                .map(\.aliasText)
        )
    }

    nonisolated private static func normalizeAliasText(_ alias: String) -> String {
        alias.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    private func updateMyAliases(for songIdentifier: String, with rows: [CommunityAliasMyCandidate]) {
        guard let ownerId = BackendSessionManager.shared.currentUser?.id else { return }
        ensureMyAliasesOwner(ownerId)

        let aliases = Self.searchableAliasTexts(from: rows)
        let previous = myAliasesBySong[songIdentifier] ?? []
        guard aliases != previous else { return }

        if aliases.isEmpty {
            myAliasesBySong.removeValue(forKey: songIdentifier)
        } else {
            myAliasesBySong[songIdentifier] = aliases
        }
        NotificationCenter.default.post(name: .maimaiSongAliasesDidChange, object: nil)
    }

    /// Drops another account's cached aliases before a new account's aliases are loaded.
    private func ensureMyAliasesOwner(_ ownerId: String) {
        guard myAliasesOwnerId != ownerId else { return }
        myAliasesOwnerId = ownerId
        guard !myAliasesBySong.isEmpty else { return }
        myAliasesBySong = [:]
        NotificationCenter.default.post(name: .maimaiSongAliasesDidChange, object: nil)
    }

    private func applyMyAliases(_ aliasesBySong: [String: [String]], ownerId: String) {
        var normalized: [String: [String]] = [:]
        for (songIdentifier, aliases) in aliasesBySong {
            let merged = Self.mergingAliases([], with: aliases)
            guard !merged.isEmpty else { continue }
            normalized[songIdentifier] = merged
        }

        let ownerChanged = myAliasesOwnerId != ownerId
        guard ownerChanged || normalized != myAliasesBySong else { return }
        myAliasesOwnerId = ownerId
        myAliasesBySong = normalized
        NotificationCenter.default.post(name: .maimaiSongAliasesDidChange, object: nil)
    }

    private func fetchMyCandidates(songIdentifier: String?, limit: Int) async -> [CommunityAliasMyCandidate]? {
        let safeLimit = max(1, min(limit, 200))
        var path = "maimaid/v1/community/candidates:my?limit=\(safeLimit)"
        if let songIdentifier {
            let escapedSongIdentifier =
                songIdentifier.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? songIdentifier
            path += "&songIdentifier=\(escapedSongIdentifier)"
        }

        do {
            let response: CommunityAliasRowsResponse<CommunityAliasMyCandidate> = try await BackendAPIClient.request(
                path: path,
                method: "GET",
                authentication: .required
            )
            return response.rows
        } catch {
            print("CommunityAliasService.fetchMyCandidates failed: \(error)")
            return nil
        }
    }
}
