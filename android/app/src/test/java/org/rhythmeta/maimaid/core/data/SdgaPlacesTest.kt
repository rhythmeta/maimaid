package org.rhythmeta.maimaid.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SdgaPlacesTest {
    private val json = """
        {
          "A63E0000001": {
            "place_name": "PACO FUNWORLD(KALLANG WAVE MALL)",
            "place_nickname": "PACO FUNWORLD#(KWM)",
            "place_id": "2F6D",
            "region0": "201",
            "region_name0": "Central Singapore",
            "region_name1": "SGP",
            "region_name2": "Kallang Wave Mall"
          },
          "A63E0000002": {
            "place_name": "PACO FUNWORLD(KALLANG WAVE MALL)",
            "place_nickname": "PACO FUNWORLD#(KWM)",
            "place_id": "2F6D",
            "region0": "201",
            "region_name0": "Central Singapore",
            "region_name1": "SGP",
            "region_name2": "Kallang Wave Mall"
          },
          "A63E0000003": {
            "place_name": "SMART GAME(MONG KOK)",
            "place_nickname": "SMART GAME#MONG KOK",
            "place_id": "1A38",
            "region0": "114",
            "region_name0": "Yau Tsim Mong",
            "region_name1": "HKG",
            "region_name2": "Mong Kok"
          }
        }
    """.trimIndent()

    @Test
    fun groupsKeychipsByArcade() {
        val catalog = SdgaPlacesCatalogLoader.parse(json)
        assertEquals(2, catalog.places.size)
        assertEquals(listOf("HKG", "SGP"), catalog.countries)
        val singapore = catalog.places.first { it.country == "SGP" }
        assertEquals(0x2F6D, singapore.placeId)
        assertEquals(201, singapore.cabinets.first().regionId)
        assertEquals(listOf("A63E0000001", "A63E0000002"), singapore.cabinets.map { it.keychip })
    }

    @Test
    fun filtersByCountryAndArcade() {
        val catalog = SdgaPlacesCatalogLoader.parse(json)
        assertEquals(3, catalog.cabinets(null, null).size)
        assertEquals(1, catalog.cabinets("HKG", null).size)
        assertEquals(
            listOf("A63E0000003"),
            catalog.cabinets("HKG", 0x1A38).map { it.keychip },
        )
        assertTrue(catalog.cabinets("JPN", null).isEmpty())
    }

    @Test
    fun selectorRotatesAndFallsBackToTheBuildTimeCabinet() {
        val catalog = SdgaPlacesCatalogLoader.parse(json)
        val fallback = SdgaCabinet("FALLBACK0001", placeId = 1, regionId = 1)
        val rotated = SdgaCabinetSelector.plan(catalog, null, null, cursor = 1, fallback = fallback)
        // Without an explicit region the known-good cabinet leads, the pool follows.
        assertEquals(
            listOf("FALLBACK0001", "A63E0000001", "A63E0000002", "A63E0000003"),
            rotated.map { it.keychip },
        )

        val explicit = SdgaCabinetSelector.plan(catalog, "SGP", null, cursor = 0, fallback = fallback)
        // An explicit region keeps its own cabinet first but still reaches the fallback.
        assertEquals(
            listOf("A63E0000001", "FALLBACK0001", "A63E0000002"),
            explicit.map { it.keychip },
        )

        val withoutFallback = SdgaCabinetSelector.plan(catalog, null, null, cursor = 1)
        assertEquals(
            listOf("A63E0000001", "A63E0000002", "A63E0000003"),
            withoutFallback.map { it.keychip },
        )

        val empty = SdgaCabinetSelector.plan(
            SdgaPlacesCatalog.empty,
            country = null,
            placeId = null,
            cursor = 0,
            fallback = fallback,
        )
        assertEquals(listOf(fallback), empty)

        assertTrue(SdgaCabinetSelector.plan(SdgaPlacesCatalog.empty, null, null, 0).isEmpty())
    }

    @Test
    fun malformedEntriesAreSkippedInsteadOfFailingTheLoad() {
        val catalog = SdgaPlacesCatalogLoader.parse(
            """
            {
              "BAD": {"place_id": "zzzz", "region0": "1"},
              "SHORT": {"place_id": "2F6D", "region0": "1"},
              "A63E0000004": {"place_name": "OK", "place_id": "2F6D", "region0": "201"}
            }
            """.trimIndent(),
        )
        assertEquals(1, catalog.places.size)
        assertEquals("OK", catalog.places.single().displayName)
    }
}
