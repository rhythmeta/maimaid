import Foundation
import Testing

@testable import maimaid

@MainActor
struct CollectionSharingServiceTests {
    private let golden = "MMD2.4-IOyk9MUSjJVwgODhYS5GI2NDAQYkqpkGLLTSwuSS0SEuJiNgIKMReXpEixpVYUpBaVCAlzMRtDlXEUpUIUAgA"

    @Test func sharesSnapshotsUsingTheDashboardDomain() throws {
        let link = SongCollectionCodec.webURL(for: golden)
        #expect(link.absoluteString == "https://dash.rhythmeta.org/collection/" + golden)
        #expect(CollectionSharingService.isCollectionLink(link))
        let payload = try CollectionSharingService.resolveImport(link.absoluteString)
        #expect(payload.name == "Road to SSS")
        #expect(payload.entries.map(\.songId) == ["100", "200", "300"])
    }

    @Test func importsRawCodesDashboardLinksAndAppLinks() throws {
        let expected = try SongCollectionCodec.decode(golden)
        for input in [
            "  \(golden)\n",
            "https://dash.rhythmeta.org/collection/\(golden)",
            "maimaid://collection/\(golden)"
        ] {
            #expect(try CollectionSharingService.resolveImport(input) == expected)
        }
    }

    @Test func rejectsCloudIDsLegacyCodesAndMalformedRoutes() throws {
        let id = "96c5349c-30a7-4b85-9892-10b64e07bdb1"
        for input in [
            id,
            "https://dash.rhythmeta.org/collection/\(id)",
            "maimaid://collection/\(id)",
            "MMD1.invalid",
            "MMD2.",
            "https://maimaid.rhythmeta.org/collection/\(golden)",
            "https://example.org/collection/\(golden)",
            "https://dash.rhythmeta.org/other/\(golden)",
            "https://dash.rhythmeta.org/collection/\(golden)/extra",
            "maimaid://collection/\(golden)/extra"
        ] {
            #expect(SongCollectionCodec.extractToken(from: input) == nil)
            #expect(throws: SongCollectionCodecError.self) {
                try CollectionSharingService.resolveImport(input)
            }
            if let url = URL(string: input) {
                #expect(CollectionSharingService.isCollectionLink(url) == false)
            }
        }
    }
}
