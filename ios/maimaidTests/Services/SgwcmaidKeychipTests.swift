import Foundation
import Testing

@testable import maimaid

@MainActor
struct SgwcmaidKeychipTests {
  @Test func decodesCatalogJSON() throws {
    let data = Data(
      #"""
      {
        "version": 1,
        "source": "test",
        "provinces": [{"id": 7, "name": "Region A", "count": 2}],
        "keychips": [
          {"keychip": "CHIP-1", "provinceId": 7, "storeId": 701, "latencyMs": 120},
          {"keychip": "CHIP-2", "provinceId": 7, "storeId": 702, "latencyMs": 80}
        ]
      }
      """#.utf8)
    let catalog = try JSONDecoder().decode(SgwcmaidKeychipCatalog.self, from: data)
    #expect(catalog.version == 1)
    #expect(catalog.provinces == [SgwcmaidProvince(id: 7, name: "Region A", count: 2)])
    #expect(catalog.keychips(inProvince: 7).map(\.keychip) == ["CHIP-1", "CHIP-2"])
  }

  @Test func prefersSelectedProvinceAndFallsBackNationwide() {
    let catalog = makeCatalog()
    let plan = SgwcmaidKeychipSelector.plan(catalog: catalog, provinceID: 1, cursor: 0)
    #expect(plan.preferred.map(\.keychip) == ["B", "C", "A"])
    #expect(plan.fallback.map(\.keychip) == ["D", "E"])
    let attempts = plan.attempts(preferredLimit: 2, fallbackLimit: 1)
    #expect(attempts.map(\.keychip) == ["B", "C", "D"])
  }

  @Test func cursorRotatesBothPools() {
    let catalog = makeCatalog()
    let plan = SgwcmaidKeychipSelector.plan(catalog: catalog, provinceID: 1, cursor: 1)
    #expect(plan.preferred.map(\.keychip) == ["C", "A", "B"])
    #expect(plan.fallback.map(\.keychip) == ["E", "D"])
    let attempts = plan.attempts(preferredLimit: 2, fallbackLimit: 1)
    #expect(attempts.map(\.keychip) == ["C", "A", "E"])
  }

  @Test func automaticSelectionUsesTheNationwidePool() {
    let catalog = makeCatalog()
    let plan = SgwcmaidKeychipSelector.plan(catalog: catalog, provinceID: nil, cursor: 0)
    #expect(plan.preferred.isEmpty)
    #expect(plan.fallback.map(\.keychip) == ["D", "B", "E", "C", "A"])
    #expect(plan.attempts(preferredLimit: 2, fallbackLimit: 3).map(\.keychip) == ["D", "B", "E"])
  }

  @Test func unknownProvinceFallsBackNationwide() {
    let catalog = makeCatalog()
    let plan = SgwcmaidKeychipSelector.plan(catalog: catalog, provinceID: 99, cursor: 0)
    #expect(plan.preferred.isEmpty)
    #expect(plan.fallback.count == catalog.keychips.count)
  }

  @Test func attemptsCapEachPool() {
    let keychips =
      (0..<8).map { makeKeychip("P\($0)", provinceID: 1, latencyMS: $0) }
      + (0..<8).map { makeKeychip("F\($0)", provinceID: 2, latencyMS: $0) }
    let catalog = SgwcmaidKeychipCatalog(
      version: 1, source: "test", provinces: [], keychips: keychips)
    let attempts = SgwcmaidKeychipSelector.plan(
      catalog: catalog, provinceID: 1, cursor: 0
    ).attempts()
    #expect(attempts.count == 8)
    #expect(attempts.prefix(4).allSatisfy { $0.provinceID == 1 })
    #expect(attempts.suffix(4).allSatisfy { $0.provinceID == 2 })
  }

  @Test func emptyCatalogProducesNoAttempts() {
    let plan = SgwcmaidKeychipSelector.plan(
      catalog: .empty, provinceID: 7, cursor: 0)
    #expect(plan.isEmpty)
    #expect(plan.attempts().isEmpty)
  }

  @Test func cabinetUsesTheCatalogLocation() {
    let keychip = SgwcmaidKeychip(
      keychip: "CHIP-1", provinceID: 7, storeID: 701, latencyMS: 120)
    let cabinet = SgwcmaidCabinet(keychip: keychip)
    #expect(cabinet.keychip == "CHIP-1")
    #expect(cabinet.regionID == 7)
    #expect(cabinet.placeID == 701)
  }

  @Test func onlyExchangeFailuresAreRetried() {
    #expect(SgwcmaidKeychipFailure.isRetryable("chime_timeout"))
    #expect(SgwcmaidKeychipFailure.isRetryable("chime_network"))
    #expect(SgwcmaidKeychipFailure.isRetryable("chime_http_503"))
    #expect(SgwcmaidKeychipFailure.isRetryable("chime_db_resolve_fail"))
    #expect(SgwcmaidKeychipFailure.isRetryable("chime_bad_json_12"))
    #expect(!SgwcmaidKeychipFailure.isRetryable("chime_code_invalid_or_expired"))
    #expect(!SgwcmaidKeychipFailure.isRetryable("chime_code_already_used"))
    #expect(!SgwcmaidKeychipFailure.isRetryable("invalid_sgwcmaid"))
  }

  @Test func storePersistsRegionAndCursor() throws {
    let suiteName = "SgwcmaidKeychipTests-\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { defaults.removePersistentDomain(forName: suiteName) }
    let store = SgwcmaidKeychipStore(defaults: defaults)

    #expect(store.selectedProvinceID == nil)
    store.selectedProvinceID = 7
    #expect(store.selectedProvinceID == 7)
    #expect(SgwcmaidKeychipStore(defaults: defaults).selectedProvinceID == 7)

    store.advanceCursor(by: 3)
    #expect(store.cursor == 3)
    #expect(SgwcmaidKeychipStore(defaults: defaults).cursor == 3)

    store.selectedProvinceID = nil
    #expect(store.selectedProvinceID == nil)
  }

  private func makeCatalog() -> SgwcmaidKeychipCatalog {
    let keychips = [
      makeKeychip("A", provinceID: 1, latencyMS: 300),
      makeKeychip("B", provinceID: 1, latencyMS: 100),
      makeKeychip("C", provinceID: 1, latencyMS: 200),
      makeKeychip("D", provinceID: 2, latencyMS: 50),
      makeKeychip("E", provinceID: 2, latencyMS: 150)
    ]
    return SgwcmaidKeychipCatalog(
      version: 1, source: "test", provinces: [], keychips: keychips)
  }

  private func makeKeychip(
    _ keychip: String, provinceID: Int, latencyMS: Int
  ) -> SgwcmaidKeychip {
    SgwcmaidKeychip(
      keychip: keychip, provinceID: provinceID,
      storeID: provinceID * 100, latencyMS: latencyMS)
  }
}
