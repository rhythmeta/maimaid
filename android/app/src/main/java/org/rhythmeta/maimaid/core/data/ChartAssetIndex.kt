package org.rhythmeta.maimaid.core.data

import kotlinx.serialization.Serializable

/** The independently published chart preview lookup table. */
@Serializable
data class ChartAssetIndex(
    val schemaVersion: Int = 1,
    val generatedAt: String? = null,
    val source: Source? = null,
    val entries: List<Entry> = emptyList(),
) {
    @Serializable
    data class Source(
        val name: String? = null,
        val catalogUrl: String? = null,
        val attribution: String? = null,
        val metadataLicense: String? = null,
        val mediaRights: String? = null,
    )

    @Serializable
    data class Entry(
        val id: Int,
        val title: String = "",
        val files: Files = Files(),
    )

    @Serializable
    data class Files(
        val maidata: File? = null,
        val audio: File? = null,
    )

    @Serializable
    data class File(
        val path: String,
        val sourceUrl: String? = null,
        val size: Long,
        val sha256: String,
    )

    fun entryFor(id: Int): Entry? = entries.firstOrNull { it.id == id }
}
