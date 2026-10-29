package org.rhythmeta.maimaid.core.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StaticManifestTest {
    @Test
    fun decodesWorkerBundlePath() {
        val manifest = Json.decodeFromString<StaticManifest>(
            """{"schemaVersion":1,"version":"bundle-1","md5":"abc","bundle":"/bundles/abc.json"}""",
        )

        assertEquals("/bundles/abc.json", manifest.bundle)
    }

    @Test
    fun defaultsManifestSchemaVersion() {
        val manifest = Json.decodeFromString<StaticManifest>(
            """{"version":"bundle-1","md5":"abc","bundle":"/bundles/abc.json"}""",
        )

        assertEquals(1, manifest.schemaVersion)
    }

    @Test
    fun decodesPublishedDownloadSizes() {
        val manifest = Json.decodeFromString<StaticManifest>(
            """
            {"schemaVersion":1,"version":"bundle-1","md5":"abc","bundle":"/bundles/abc.json",
             "sizes":{"bundleBytes":5792767,"imageBytes":180000000,"totalBytes":185792767}}
            """.trimIndent(),
        )

        assertEquals(5_792_767L, manifest.sizes?.bundleBytes)
        assertEquals(180_000_000L, manifest.sizes?.imageBytes)
        assertEquals(185_792_767L, manifest.sizes?.totalBytes)
    }

    @Test
    fun toleratesManifestsWithoutSizes() {
        val absent = Json.decodeFromString<StaticManifest>(
            """{"version":"bundle-1","md5":"abc","bundle":"/bundles/abc.json"}""",
        )
        val explicitNull = Json.decodeFromString<StaticManifest>(
            """{"version":"bundle-1","md5":"abc","bundle":"/bundles/abc.json","sizes":null}""",
        )

        assertNull(absent.sizes)
        assertNull(explicitNull.sizes)
    }
}
