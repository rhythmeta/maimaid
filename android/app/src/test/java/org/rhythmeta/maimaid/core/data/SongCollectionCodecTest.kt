package org.rhythmeta.maimaid.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SongCollectionCodecTest {
    @Test
    fun decodesSharedGoldenPayloadAndPreservesEntryOrder() {
        val payload = SongCollectionCodec.decode(GOLDEN_PAYLOAD)

        assertEquals("Road to SSS", payload.name)
        assertEquals(listOf("100", "200", "300"), payload.entries.map(SongCollectionExportEntry::songId))
        assertEquals(listOf("master", "expert", "remaster"), payload.entries.map(SongCollectionExportEntry::difficulty))
    }

    @Test
    fun androidEncodingRoundTripsWithRawDeflate() {
        val collection = SongCollectionExport(
            name = "Test",
            entries = listOf(
                SongCollectionExportEntry(
                    songId = "song",
                    chartType = "dx",
                    difficulty = "master",
                ),
            ),
        )

        val encoded = SongCollectionCodec.encode(collection)
        val decoded = SongCollectionCodec.decode(encoded)

        assertTrue(encoded.startsWith(SongCollectionCodec.PREFIX))
        assertEquals(collection, decoded)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsLegacyPayload() {
        SongCollectionCodec.decode("MMD1.invalid")
    }

    @Test
    fun sharesSnapshotsUsingTheDashboardDomain() {
        val collection = SongCollectionCodec.decode(GOLDEN_PAYLOAD)
        val link = SongCollectionCodec.webUrl(collection)

        assertTrue(link.startsWith("https://dash.rhythmeta.org/collection/MMD2."))
        assertEquals(collection, CollectionSharingService().resolveImport(link))
    }

    @Test
    fun importsRawCodesDashboardLinksAndAppLinks() {
        val expected = SongCollectionCodec.decode(GOLDEN_PAYLOAD)
        for (input in listOf(
            "  $GOLDEN_PAYLOAD\n",
            "https://dash.rhythmeta.org/collection/$GOLDEN_PAYLOAD",
            "maimaid://collection/$GOLDEN_PAYLOAD",
        )) {
            assertEquals(expected, CollectionSharingService().resolveImport(input))
        }
    }

    @Test
    fun rejectsCloudIdsLegacyCodesAndMalformedRoutes() {
        val id = "96c5349c-30a7-4b85-9892-10b64e07bdb1"
        for (input in listOf(
            id,
            "https://dash.rhythmeta.org/collection/$id",
            "maimaid://collection/$id",
            "MMD1.invalid",
            "MMD2.",
            "https://maimaid.rhythmeta.org/collection/$GOLDEN_PAYLOAD",
            "https://example.org/collection/$GOLDEN_PAYLOAD",
            "https://dash.rhythmeta.org/other/$GOLDEN_PAYLOAD",
            "https://dash.rhythmeta.org/collection/$GOLDEN_PAYLOAD/extra",
            "maimaid://collection/$GOLDEN_PAYLOAD/extra",
        )) {
            assertNull(input, SongCollectionCodec.extractToken(input))
            assertThrows(IllegalArgumentException::class.java) {
                CollectionSharingService().resolveImport(input)
            }
        }
    }

    private companion object {
        const val GOLDEN_PAYLOAD =
            "MMD2.4-IOyk9MUSjJVwgODhYS5GI2NDAQYkqpkGLLTSwuSS0SEuJiNgIKMReXpEixpVYUpBaVCAlzMRtDlXEUpUIUAgA"
    }
}
