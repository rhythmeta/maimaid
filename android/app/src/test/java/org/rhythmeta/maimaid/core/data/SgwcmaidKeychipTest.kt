package org.rhythmeta.maimaid.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SgwcmaidKeychipTest {
    @Test
    fun parsesCatalog() {
        val catalog = SgwcmaidKeychipCatalogLoader.parse(
            """
            {
              "version": 1,
              "source": "test",
              "provinces": [{"id": 7, "name": "Region A", "count": 2}],
              "keychips": [
                {"keychip": "CHIP-1", "provinceId": 7, "storeId": 701, "latencyMs": 120},
                {"keychip": "CHIP-2", "provinceId": 7, "storeId": 702, "latencyMs": 80}
              ]
            }
            """.trimIndent(),
        )
        assertEquals(1, catalog.version)
        assertEquals(listOf(SgwcmaidProvince(7, "Region A", 2)), catalog.provinces)
        assertEquals(listOf("CHIP-1", "CHIP-2"), catalog.keychipsInProvince(7).map { it.keychip })
    }

    @Test
    fun ignoresUnknownFieldsInTheBuildInput() {
        val catalog = SgwcmaidKeychipCatalogLoader.parse(
            """
            {
              "version": 1,
              "source": "test",
              "provinces": [{"id": 7, "name": "Region A", "count": 1, "extra": true}],
              "keychips": [
                {"keychip": "CHIP-1", "storeName": "ignored", "provinceId": 7,
                 "storeId": 701, "latencyMs": 120}
              ]
            }
            """.trimIndent(),
        )
        assertEquals("CHIP-1", catalog.keychips.single().keychip)
    }

    @Test
    fun prefersSelectedProvinceThenFallsBack() {
        val plan = SgwcmaidKeychipSelector.plan(makeCatalog(), provinceId = 1, cursor = 0)
        assertEquals(listOf("B", "C", "A"), plan.preferred.map { it.keychip })
        assertEquals(listOf("D", "E"), plan.fallback.map { it.keychip })
        assertEquals(
            listOf("B", "C", "D"),
            plan.attempts(preferredLimit = 2, fallbackLimit = 1).map { it.keychip },
        )
    }

    @Test
    fun cursorRotatesBothPools() {
        val plan = SgwcmaidKeychipSelector.plan(makeCatalog(), provinceId = 1, cursor = 1)
        assertEquals(listOf("C", "A", "B"), plan.preferred.map { it.keychip })
        assertEquals(listOf("E", "D"), plan.fallback.map { it.keychip })
        assertEquals(
            listOf("C", "A", "E"),
            plan.attempts(preferredLimit = 2, fallbackLimit = 1).map { it.keychip },
        )
    }

    @Test
    fun automaticSelectionUsesTheNationwidePool() {
        val plan = SgwcmaidKeychipSelector.plan(makeCatalog(), provinceId = null, cursor = 0)
        assertTrue(plan.preferred.isEmpty())
        assertEquals(listOf("D", "B", "E", "C", "A"), plan.fallback.map { it.keychip })
        assertEquals(
            listOf("D", "B", "E"),
            plan.attempts(preferredLimit = 2, fallbackLimit = 3).map { it.keychip },
        )
    }

    @Test
    fun unknownProvinceFallsBackNationwide() {
        val catalog = makeCatalog()
        val plan = SgwcmaidKeychipSelector.plan(catalog, provinceId = 99, cursor = 0)
        assertTrue(plan.preferred.isEmpty())
        assertEquals(catalog.keychips.size, plan.fallback.size)
    }

    @Test
    fun attemptsCapEachPool() {
        val keychips = (0 until 8).map { chip("P$it", provinceId = 1, latencyMs = it) } +
            (0 until 8).map { chip("F$it", provinceId = 2, latencyMs = it) }
        val attempts = SgwcmaidKeychipSelector.plan(
            SgwcmaidKeychipCatalog(version = 1, keychips = keychips),
            provinceId = 1,
            cursor = 0,
        ).attempts()
        assertEquals(8, attempts.size)
        assertTrue(attempts.take(4).all { it.provinceId == 1 })
        assertTrue(attempts.drop(4).all { it.provinceId == 2 })
    }

    @Test
    fun emptyCatalogProducesNoAttempts() {
        val plan = SgwcmaidKeychipSelector.plan(
            SgwcmaidKeychipCatalog(), provinceId = 7, cursor = 0,
        )
        assertTrue(plan.isEmpty)
        assertTrue(plan.attempts().isEmpty())
    }

    @Test
    fun cabinetUsesCatalogLocation() {
        val cabinet = SgwcmaidCabinet(
            SgwcmaidKeychip("CHIP-1", provinceId = 7, storeId = 701, latencyMs = 120),
        )
        assertEquals("CHIP-1", cabinet.keychip)
        assertEquals(7, cabinet.regionId)
        assertEquals(701, cabinet.placeId)
    }

    @Test
    fun onlyExchangeFailuresAreRetried() {
        assertTrue(SgwcmaidKeychipFailure.isRetryable("chime_timeout"))
        assertTrue(SgwcmaidKeychipFailure.isRetryable("chime_network"))
        assertTrue(SgwcmaidKeychipFailure.isRetryable("chime_http_503"))
        assertTrue(SgwcmaidKeychipFailure.isRetryable("chime_db_resolve_fail"))
        assertFalse(SgwcmaidKeychipFailure.isRetryable("chime_code_invalid_or_expired"))
        assertFalse(SgwcmaidKeychipFailure.isRetryable("chime_code_already_used"))
        assertFalse(SgwcmaidKeychipFailure.isRetryable("invalid_sgwcmaid"))
    }

    private fun makeCatalog(): SgwcmaidKeychipCatalog = SgwcmaidKeychipCatalog(
        version = 1,
        source = "test",
        keychips = listOf(
            chip("A", provinceId = 1, latencyMs = 300),
            chip("B", provinceId = 1, latencyMs = 100),
            chip("C", provinceId = 1, latencyMs = 200),
            chip("D", provinceId = 2, latencyMs = 50),
            chip("E", provinceId = 2, latencyMs = 150),
        ),
    )

    private fun chip(keychip: String, provinceId: Int, latencyMs: Int) = SgwcmaidKeychip(
        keychip = keychip,
        provinceId = provinceId,
        storeId = provinceId * 100,
        latencyMs = latencyMs,
    )
}
