package org.rhythmeta.maimaid.core.chart.audio

import java.io.File

/**
 * Bounds `filesDir/chart-audio` to at most [MAX_TRACKS] songs, shared by the MP3 and PCM caches.
 *
 * A song is one audio asset: every file whose name starts with the audio sha256 belongs to the same
 * group — `<sha>.<ext>`, `<sha>.v1.<rate>.s16le.stereo.pcm`, its `.meta` and any `.tmp`. The least
 * recently used groups are deleted as a whole, so an evicted song never leaves a stale PCM behind
 * (and a re-download cannot pair a new MP3 with an old PCM).
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

    private fun groupKey(name: String): String? {
        // Every cache file starts with the 64-char sha256 followed by '.', so the first segment is
        // the song identity. Files without a prefix (e.g. dot-files) are ignored.
        val key = name.substringBefore('.').lowercase()
        return key.takeIf { it.isNotEmpty() }
    }
}
