import Foundation
import Testing

@testable import maimaid

/// Candidate IDs are opaque server text. Rows migrated from the retired cloud database keep numeric
/// IDs, so every alias feed must decode them without dropping the response.
@MainActor
struct CommunityAliasServiceTests {
    private struct RowsEnvelope<Row: Decodable>: Decodable {
        let rows: [Row]
    }

    @Test("Approved alias sync decodes legacy numeric and UUID candidate identifiers")
    func decodesApprovedSyncRowsWithMixedIdentifiers() throws {
        let payload = """
        {"rows":[
          {"candidateId":"1","songIdentifier":"RONDØ","aliasText":"rondo","status":"approved",
           "updatedAt":"2026-08-21T04:31:00.362Z","approvedAt":"2026-08-13T01:22:00.029Z"},
          {"candidateId":"65bdf902-59b5-4f9f-a7da-1f31a91bef63","songIdentifier":"マヌルネコのうた",
           "aliasText":"兔猻猫之歌","status":"rejected","updatedAt":"2026-08-17T00:00:00.054Z",
           "approvedAt":"2026-08-15T02:53:09.289Z"}
        ]}
        """

        let response = try BackendAPIClient.decoder.decode(
            RowsEnvelope<CommunityAliasApprovedSyncRow>.self,
            from: Data(payload.utf8)
        )

        #expect(response.rows.map(\.candidateId) == ["1", "65bdf902-59b5-4f9f-a7da-1f31a91bef63"])
        #expect(response.rows.map(\.status) == ["approved", "rejected"])
        #expect(response.rows.filter { ($0.status ?? "approved") == "approved" }.count == 1)
    }

    @Test("My submissions and voting board decode legacy numeric candidate identifiers")
    func decodesLegacyCandidateFeeds() throws {
        let myCandidatesPayload = """
        {"rows":[{"candidateId":"2324","songIdentifier":"MYTH Re：LEASE","aliasText":"三百绝赞2",
         "status":"voting","voteOpenAt":null,"voteCloseAt":"2026-08-24T04:31:00.362Z",
         "supportCount":1,"opposeCount":0,"createdAt":"2026-08-21T04:31:00.362Z",
         "updatedAt":"2026-08-21T04:31:00.362Z"}]}
        """
        let votingBoardPayload = """
        {"rows":[{"candidateId":"2325","songIdentifier":"MYTH Re：LEASE","aliasText":"300绝赞2",
         "submitterId":"user-1","voteOpenAt":null,"voteCloseAt":"2026-08-24T04:31:00.362Z",
         "supportCount":2,"opposeCount":1,"myVote":1,"createdAt":"2026-08-21T04:31:00.362Z"}]}
        """

        let myCandidates = try BackendAPIClient.decoder.decode(
            RowsEnvelope<CommunityAliasMyCandidate>.self,
            from: Data(myCandidatesPayload.utf8)
        )
        let votingBoard = try BackendAPIClient.decoder.decode(
            RowsEnvelope<CommunityAliasVotingBoardItem>.self,
            from: Data(votingBoardPayload.utf8)
        )

        #expect(myCandidates.rows.first?.id == "2324")
        #expect(myCandidates.rows.first?.status == "voting")
        #expect(votingBoard.rows.first?.id == "2325")
        #expect(votingBoard.rows.first?.myVote == 1)
    }

    @Test("Vote results echo opaque candidate identifiers")
    func decodesVoteResultIdentifier() throws {
        let payload = """
        {"candidateId":"2325","supportCount":3,"opposeCount":1,"myVote":1}
        """

        let result = try BackendAPIClient.decoder.decode(
            CommunityAliasVoteResult.self,
            from: Data(payload.utf8)
        )

        #expect(result.candidateId == "2325")
        #expect(result.supportCount == 3)
    }

    @Test("Only searchable candidate states contribute local aliases")
    func filtersSearchableCandidateStatuses() {
        let rows = [
            candidate(id: "1", alias: "三百绝赞2", status: "voting"),
            candidate(id: "2", alias: "300绝赞2", status: "rejected"),
            candidate(id: "3", alias: "300 絶賛", status: "pool_private"),
            candidate(id: "4", alias: "Rondo", status: "approved"),
            candidate(id: "5", alias: "  rondo  ", status: "voting")
        ]

        #expect(CommunityAliasService.searchableAliasTexts(from: rows) == ["三百绝赞2", "300 絶賛", "Rondo"])
    }

    @Test("Alias merging trims, deduplicates, and keeps the first spelling")
    func mergesAliasesForSearch() {
        let merged = CommunityAliasService.mergingAliases(
            ["Rondo", "  existing "],
            with: ["rondo", "New Alias", "NEW ALIAS", "   "]
        )

        #expect(merged == ["Rondo", "existing", "New Alias"])
    }

    private func candidate(id: String, alias: String, status: String) -> CommunityAliasMyCandidate {
        CommunityAliasMyCandidate(
            candidateId: id,
            songIdentifier: "RONDØ",
            aliasText: alias,
            status: status,
            voteOpenAt: nil,
            voteCloseAt: nil,
            supportCount: 0,
            opposeCount: 0,
            createdAt: .now,
            updatedAt: .now
        )
    }
}
