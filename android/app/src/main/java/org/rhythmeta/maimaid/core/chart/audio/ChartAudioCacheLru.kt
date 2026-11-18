package org.rhythmeta.maimaid.core.chart.audio

import java.io.File

/**
 * Bounds `filesDir/chart-audio` to at most [MAX_TRACKS] downloaded songs.
 *
 * Only the MP3s are cached on disk nowadays (PCM is decoded into memory), but the eviction still
 * works on "song groups": every file whose name starts with the audio sha256 — `<sha>.mp3` plus any
 * leftovers from older versions — is deleted together, so a re-download can never mix files from
 * two different generations of the same song.
 */
object ChartAudioCacheLru {
    const val MAX_TRACKS = 5

    /**
     * Deletes the least recently used song groups until at most [maxTracks] remain.
     * [keepKeys] (sha256 strings) are never deleted; pass the track that is being prepared now.
     */
    fun prune(
        directory: File,
        keepKeys: Set<String> = emptySet(),
        maxTracks: Int = MAX_TRACKS,
    ) {
        if (maxTracks <= 0) return
        val files = directory.listFiles()?.filter { it.isFile } ?: return
        val groups = LinkedHashMap<String, MutableList<File>>()
        for (file in files) {
            val key = groupKey(file.name) ?: continue
            groups.getOrPut(key) { mutableListOf() }.add(file)
        }
        if (groups.size <= maxTracks) return
        val keep = keepKeys.map { it.lowercase() }.toSet()
        val ordered = groups.entries.sortedBy { entry ->
            entry.value.maxOf { it.lastModified() }
        }
        var remaining = ordered.size
        for ((key, groupFiles) in ordered) {
            if (remaining <= maxTracks) break
            if (key in keep) continue
            groupFiles.forEach { it.delete() }
            remaining--
        }
    }

    /**
     * Deletes PCM files written by older versions of the app. Decoded audio now lives in memory, so
     * these are pure waste (tens of MB per track) and must not occupy the cache directory.
     */
    fun purgeLegacyPcmFiles(directory: File): Int {
        val files = directory.listFiles() ?: return 0
        var removed = 0
        for (file in files) {
            if (!file.isFile) continue
            val name = file.name
            if (!name.endsWith(".pcm") && !name.endsWith(".meta")) continue
            if (file.delete()) removed++
        }
        return removed
    }

    private fun groupKey(name: String): String? {
        // Every cache file starts with the 64-char sha256 followed by '.', so the first segment is
        // the song identity. Files without a prefix (e.g. dot-files) are ignored.
        val key = name.substringBefore('.').lowercase()
        return key.takeIf { it.isNotEmpty() }
    }
}
