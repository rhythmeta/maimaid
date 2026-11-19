import Foundation
import SwiftData

nonisolated enum CommunityAliasSubmitStatus: String, Codable, Sendable {
    case created
    case rejectedDuplicate = "rejected_duplicate"
    case quotaExceeded = "quota_exceeded"
    case unauthenticated
    case invalidRequest = "invalid_request"
    case error
}

nonisolated enum CommunityAliasDuplicateReason: String, Codable, Sendable {
    case lxnsExisting = "lxns_existing"
    case communityExisting = "community_existing"
    case adminRejectedLocked = "admin_rejected_locked"
}

nonisolated struct CommunityAliasSubmitCandidate: Codable, Sendable {
    let id: String
    let songIdentifier: String
    let aliasText: String
    let status: String
    let createdAt: String
}

nonisolated struct CommunityAliasExistingCandidate: Codable, Identifiable, Sendable {
    let candidateId: String
    let aliasText: String
    let status: String
    let similarity: Double
    let bucket: String
    let supportCount: Int
    let opposeCount: Int

    var id: String { candidateId }
}

nonisolated struct CommunityAliasSubmitResponse: Codable, Sendable {
    let status: CommunityAliasSubmitStatus
    let message: String
    let duplicateReason: CommunityAliasDuplicateReason?
    let candidate: CommunityAliasSubmitCandidate?
    let existingCandidates: [CommunityAliasExistingCandidate]?
    let similarAliases: [String]?
    let quotaRemaining: Int?
}

// Candidate identifiers are opaque text on the backend: new submissions use UUIDs, while rows
// migrated from the retired cloud database keep their original numeric IDs. Decoding them as UUIDs
// fails the whole response, so every alias feed must treat them as strings.
nonisolated struct CommunityAliasVotingBoardItem: Codable, Identifiable, Sendable {
    let candidateId: String
    let songIdentifier: String
    let aliasText: String
    let submitterId: String
    let voteOpenAt: Date?
    let voteCloseAt: Date?
    let supportCount: Int
    let opposeCount: Int
    let myVote: Int?
    let createdAt: Date

    var id: String { candidateId }
}

nonisolated struct CommunityAliasMyCandidate: Codable, Identifiable, Sendable {
    let candidateId: String
    let songIdentifier: String
    let aliasText: String
    let status: String
    let voteOpenAt: Date?
    let voteCloseAt: Date?
    let supportCount: Int
    let opposeCount: Int
    let createdAt: Date
    let updatedAt: Date

    var id: String { candidateId }
}

nonisolated struct CommunityAliasVoteResult: Codable, Sendable {
    let candidateId: String
    let supportCount: Int
    let opposeCount: Int
    let myVote: Int?
}

nonisolated struct CommunityAliasApprovedSyncRow: Codable, Sendable {
    let status: String?
    let candidateId: String
    let songIdentifier: String
    let aliasText: String
    let updatedAt: Date
    let approvedAt: Date?
}

private struct CommunityAliasSubmitPayload: Encodable {
    let songIdentifier: String
    let aliasText: String
    let deviceLocalDate: String
    let tzOffsetMinutes: Int
}

private struct CommunityAliasVotePayload: Encodable {
    let vote: Int
}

struct CommunityAliasRowsResponse<Row: Decodable>: Decodable {
    let rows: [Row]
}

private struct CommunityAliasDailyCountResponse: Decodable {
    let count: Int
}

@MainActor
final class CommunityAliasService {
    static let shared = CommunityAliasService()

    private init() {}

    private(set) var lastVoteErrorMessage: String?

    /// Aliases submitted by the signed-in account that are searchable before they are approved.
    /// Mirrors the Android contract: the owner sees their own pending aliases in local search,
    /// while other accounts only see approved aliases from the sync feed.
    /// Mutated by `CommunityAliasService+MyAliases.swift`.
    var myAliasesBySong: [String: [String]] = [:]
    var myAliasesOwnerId: String?

    var isConfigured: Bool { BackendSessionManager.shared.isConfigured }
    var isAuthenticated: Bool { BackendSessionManager.shared.isAuthenticated }

    func submitAlias(songIdentifier: String, aliasText: String) async -> CommunityAliasSubmitResponse {
        guard isConfigured else {
            return .init(
                status: .error,
                message: String(localized: "settings.cloud.config.error.unconfigured"),
                duplicateReason: nil,
                candidate: nil,
                existingCandidates: nil,
                similarAliases: nil,
                quotaRemaining: nil
            )
        }

        guard isAuthenticated else {
            return .init(
                status: .unauthenticated,
                message: String(localized: "community.alias.submit.loginRequired"),
                duplicateReason: nil,
                candidate: nil,
                existingCandidates: nil,
                similarAliases: nil,
                quotaRemaining: nil
            )
        }

        let localDate = Date.now.formatted(
            .iso8601
            .year()
            .month()
            .day()
            .dateSeparator(.dash)
        )

        do {
            let payload = CommunityAliasSubmitPayload(
                songIdentifier: songIdentifier,
                aliasText: aliasText,
                deviceLocalDate: localDate,
                tzOffsetMinutes: TimeZone.current.secondsFromGMT() / 60
            )
            let response: CommunityAliasSubmitResponse = try await BackendAPIClient.request(
                path: "maimaid/v1/community/candidates",
                method: "POST",
                body: payload,
                authentication: .required
            )
            return response
        } catch let apiError as BackendAPIError {
            if apiError.statusCode == 401 {
                return .init(
                    status: .unauthenticated,
                    message: String(localized: "community.alias.submit.loginRequired"),
                    duplicateReason: nil,
                    candidate: nil,
                    existingCandidates: nil,
                    similarAliases: nil,
                    quotaRemaining: nil
                )
            }

            return .init(
                status: .error,
                message: apiError.message,
                duplicateReason: nil,
                candidate: nil,
                existingCandidates: nil,
                similarAliases: nil,
                quotaRemaining: nil
            )
        } catch {
            return .init(
                status: .error,
                message: error.localizedDescription,
                duplicateReason: nil,
                candidate: nil,
                existingCandidates: nil,
                similarAliases: nil,
                quotaRemaining: nil
            )
        }
    }

    func fetchVotingBoard(limit: Int = 120, offset: Int = 0) async -> [CommunityAliasVotingBoardItem] {
        guard isConfigured else { return [] }
        let safeLimit = max(1, min(limit, 200))
        let safeOffset = max(0, offset)
        do {
            let response: CommunityAliasRowsResponse<CommunityAliasVotingBoardItem> =
                try await BackendAPIClient.request(
                path: "maimaid/v1/community/candidates:votingBoard?limit=\(safeLimit)&offset=\(safeOffset)",
                method: "GET",
                authentication: .optional
            )
            return response.rows
        } catch {
            print("CommunityAliasService.fetchVotingBoard failed: \(error)")
            return []
        }
    }

    func fetchMyDailySubmissionCount(localDate: Date = Date()) async -> Int? {
        guard isConfigured, isAuthenticated else { return nil }
        let formattedDate = localDate.formatted(
            .iso8601
            .year()
            .month()
            .day()
            .dateSeparator(.dash)
        )
        do {
            let response: CommunityAliasDailyCountResponse = try await BackendAPIClient.request(
                path: "maimaid/v1/community/candidates:dailyCount?localDate=\(formattedDate)",
                method: "GET",
                authentication: .required
            )
            return max(0, response.count)
        } catch {
            print("CommunityAliasService.fetchMyDailySubmissionCount failed: \(error)")
            return nil
        }
    }

    func vote(candidateId: String, support: Bool) async -> CommunityAliasVoteResult? {
        guard isConfigured, isAuthenticated else {
            lastVoteErrorMessage = String(localized: "community.alias.service.vote.sessionInvalid")
            return nil
        }

        lastVoteErrorMessage = nil

        do {
            let normalizedCandidateId = Self.encodedCandidateId(candidateId)
            let escapedCandidateId =
                normalizedCandidateId.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed)
                ?? normalizedCandidateId
            let payload = CommunityAliasVotePayload(vote: support ? 1 : -1)
            let response: CommunityAliasVoteResult = try await BackendAPIClient.request(
                path: "maimaid/v1/community/candidates/\(escapedCandidateId):vote",
                method: "POST",
                body: payload,
                authentication: .required
            )
            return response
        } catch let apiError as BackendAPIError {
            lastVoteErrorMessage = apiError.message
            return nil
        } catch {
            lastVoteErrorMessage = error.localizedDescription
            return nil
        }
    }

    /// Candidate IDs are opaque server text: UUIDs for new submissions, legacy numeric IDs otherwise.
    /// The backend stores new UUIDs lowercase, so preserve that form when building the vote route.
    private static func encodedCandidateId(_ candidateId: String) -> String {
        UUID(uuidString: candidateId) == nil ? candidateId : candidateId.lowercased()
    }

    func syncApprovedAliasesIfNeeded(container: ModelContainer, minimumInterval: TimeInterval = 10 * 60) async {
        if let lastPoll = UserDefaults.app.communityAliasLastPollAt,
           Date.now.timeIntervalSince(lastPoll) < minimumInterval {
            return
        }

        UserDefaults.app.communityAliasLastPollAt = Date.now
        // Use the container's main context so catalog views observing Song.aliases pick the merge up.
        let context = container.mainContext
        await syncApprovedAliasesIntoSongs(modelContext: context)
    }

    func syncApprovedAliasesIntoSongs(
        modelContext: ModelContext,
        force: Bool = false,
        updateSongs: Bool = true
    ) async {
        guard isConfigured else { return }

        let now = Date.now
        let path = "maimaid/v1/community/aliases:sync"
        let response: CommunityAliasRowsResponse<CommunityAliasApprovedSyncRow>
        do {
            response = try await BackendAPIClient.request(path: path, method: "GET", authentication: .none)
        } catch {
            print("CommunityAliasService.syncApprovedAliasesIntoSongs failed: \(error)")
            return
        }

        let approvedRows = response.rows.filter { ($0.status ?? "approved") == "approved" }
        var didMutateSongAliases = purgeRejectedAliases(from: response.rows, modelContext: modelContext)
        var didMutate = didMutateSongAliases

        let remoteIdSet = Set(approvedRows.map(\.candidateId))
        if reconcileApprovedCacheForForceSync(remoteIdSet: remoteIdSet, modelContext: modelContext) {
            didMutate = true
        }

        guard !approvedRows.isEmpty else {
            if didMutate {
                saveAliasChanges(
                    modelContext: modelContext,
                    didMutateSongAliases: didMutateSongAliases,
                    maxUpdatedAt: nil,
                    now: now
                )
            }
            return
        }

        var maxUpdatedAt = Date.distantPast
        for row in approvedRows {
            if applyApprovedAliasRow(row, modelContext: modelContext, updateSongs: updateSongs) {
                didMutateSongAliases = true
            }
            maxUpdatedAt = max(maxUpdatedAt, row.updatedAt)
        }

        saveAliasChanges(
            modelContext: modelContext,
            didMutateSongAliases: didMutateSongAliases,
            maxUpdatedAt: maxUpdatedAt,
            now: now
        )
    }

    /// Removes aliases the server tombstoned with a non-approved status; returns whether a song changed.
    private func purgeRejectedAliases(
        from rows: [CommunityAliasApprovedSyncRow],
        modelContext: ModelContext
    ) -> Bool {
        var changed = false
        for row in rows where row.status != nil && row.status != "approved" {
            guard let song = fetchSong(songIdentifier: row.songIdentifier, modelContext: modelContext) else {
                continue
            }
            let previousCount = song.aliases.count
            song.aliases.removeAll { $0.localizedCaseInsensitiveCompare(row.aliasText) == .orderedSame }
            changed = changed || song.aliases.count != previousCount
        }
        return changed
    }

    /// Upserts one approved alias into the cache and, when requested, into the song's searchable aliases.
    /// Returns whether `song.aliases` changed.
    private func applyApprovedAliasRow(
        _ row: CommunityAliasApprovedSyncRow,
        modelContext: ModelContext,
        updateSongs: Bool
    ) -> Bool {
        let remoteId = row.candidateId
        let cache = fetchCache(remoteId: remoteId, modelContext: modelContext)
            ?? CommunityAliasCache(
                remoteId: remoteId,
                songIdentifier: row.songIdentifier,
                aliasText: row.aliasText,
                status: "approved",
                voteOpenAt: nil,
                voteCloseAt: nil,
                approvedAt: row.approvedAt,
                updatedAt: row.updatedAt,
                createdAt: row.approvedAt ?? row.updatedAt
            )

        cache.songIdentifier = row.songIdentifier
        cache.aliasText = row.aliasText
        cache.status = "approved"
        cache.approvedAt = row.approvedAt
        cache.updatedAt = row.updatedAt

        if cache.modelContext == nil {
            modelContext.insert(cache)
        }

        guard updateSongs, let song = fetchSong(songIdentifier: row.songIdentifier, modelContext: modelContext) else {
            return false
        }
        let exists = song.aliases.contains {
            $0.localizedCaseInsensitiveCompare(row.aliasText) == .orderedSame
        }
        guard !exists else { return false }
        song.aliases.append(row.aliasText)
        return true
    }

    private func saveAliasChanges(
        modelContext: ModelContext,
        didMutateSongAliases: Bool,
        maxUpdatedAt: Date?,
        now: Date
    ) {
        do {
            try modelContext.save()
            if let maxUpdatedAt, maxUpdatedAt != .distantPast {
                UserDefaults.app.communityAliasApprovedSyncAt = min(maxUpdatedAt, now)
            }
            if didMutateSongAliases {
                NotificationCenter.default.post(name: .maimaiSongAliasesDidChange, object: nil)
            }
        } catch {
            print("CommunityAliasService.sync save failed: \(error)")
        }
    }

    private func reconcileApprovedCacheForForceSync(
        remoteIdSet: Set<String>,
        modelContext: ModelContext
    ) -> Bool {
        let approvedStatus = "approved"
        let descriptor = FetchDescriptor<CommunityAliasCache>(
            predicate: #Predicate { item in
                item.status == approvedStatus
            }
        )
        guard let localApproved = try? modelContext.fetch(descriptor), !localApproved.isEmpty else {
            return false
        }

        var changed = false
        for item in localApproved where !remoteIdSet.contains(item.remoteId) {
            modelContext.delete(item)
            changed = true
        }
        return changed
    }

    private func fetchCache(remoteId: String, modelContext: ModelContext) -> CommunityAliasCache? {
        let descriptor = FetchDescriptor<CommunityAliasCache>(
            predicate: #Predicate { item in
                item.remoteId == remoteId
            }
        )
        return try? modelContext.fetch(descriptor).first
    }

    private func fetchSong(songIdentifier: String, modelContext: ModelContext) -> Song? {
        let descriptor = FetchDescriptor<Song>(
            predicate: #Predicate { song in
                song.songIdentifier == songIdentifier
            }
        )
        return try? modelContext.fetch(descriptor).first
    }
}
