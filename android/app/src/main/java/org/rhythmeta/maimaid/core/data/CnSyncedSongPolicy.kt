package org.rhythmeta.maimaid.core.data

/**
 * Charts the CN server shipped while it was still running an older version.
 *
 * Their catalog `version` is the JP one, so the version-index rule alone would file them under the
 * old-song bucket, even though CN players receive them as current-version songs.
 */
object CnSyncedSongPolicy {
    /**
     * Provider song ids of the CN-synced charts. Keep the list sorted and comment every entry with
     * its title.
     *
     * - `12025` 魔法仕掛けのファンタジア — MAGiCAL in JP, synced to CN while CN is on PRiSM PLUS
     *   (WEC 2026).
     */
    private val syncedSongIds = setOf(12_025)

    /**
     * Returns true when [songId] belongs to a chart the CN server synced ahead of its own version,
     * so it counts as a CN current-version song.
     */
    fun contains(songId: Int?): Boolean {
        val id = songId?.takeIf { it > 0 } ?: return false
        if (id in syncedSongIds) return true
        // STD charts of the same song carry the base id (12025 -> 2025).
        return id < 10_000 && (id + 10_000) in syncedSongIds
    }
}
