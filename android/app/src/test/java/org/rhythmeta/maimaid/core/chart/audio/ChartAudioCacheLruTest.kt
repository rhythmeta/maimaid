package org.rhythmeta.maimaid.core.chart.audio

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartAudioCacheLruTest {
    private fun sha(index: Int): String = index.toString(16).padStart(64, '0')

    private fun createGroup(directory: File, index: Int, lastModified: Long) {
        val key = sha(index)
        listOf(
            "$key.mp3",
            "$key.v1.48000.s16le.stereo.pcm",
            "$key.v1.48000.s16le.stereo.meta",
        ).forEach { name ->
            val file = File(directory, name)
            file.writeText("x")
            file.setLastModified(lastModified)
        }
    }

    private fun existingGroups(directory: File): Set<String> =
        directory.listFiles().orEmpty()
            .map { it.name.substringBefore('.') }
            .filter { it.isNotEmpty() }
            .toSet()

    @Test
    fun keepsOnlyTheFiveMostRecentSongsAndDeletesTheirPcm() {
        val directory = Files.createTempDirectory("chart-audio-lru").toFile()
        try {
            repeat(7) { index -> createGroup(directory, index, 1_000L * index) }
            ChartAudioCacheLru.prune(directory, keepKeys = setOf(sha(6)))
            assertEquals(
                (2..6).map(::sha).toSet(),
                existingGroups(directory),
            )
            // An evicted song must not leave its PCM or meta behind.
            assertFalse(File(directory, "${sha(0)}.v1.48000.s16le.stereo.pcm").exists())
            assertFalse(File(directory, "${sha(0)}.mp3").exists())
            assertTrue(File(directory, "${sha(6)}.v1.48000.s16le.stereo.pcm").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun neverDeletesTheSongBeingPreparedEvenIfItIsOldest() {
        val directory = Files.createTempDirectory("chart-audio-lru-keep").toFile()
        try {
            repeat(6) { index -> createGroup(directory, index, 1_000L * index) }
            ChartAudioCacheLru.prune(directory, keepKeys = setOf(sha(0)))
            val remaining = existingGroups(directory)
            assertEquals(5, remaining.size)
            assertTrue(sha(0) in remaining)
            assertFalse(sha(1) in remaining)
            assertTrue(sha(5) in remaining)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun purgesPcmFilesLeftBehindByOlderVersions() {
        val directory = Files.createTempDirectory("chart-audio-legacy").toFile()
        try {
            createGroup(directory, 0, 1_000L)
            val before = directory.listFiles().orEmpty().size
            ChartAudioCacheLru.purgeLegacyPcmFiles(directory)
            val remaining = directory.listFiles().orEmpty().map { it.name }
            // Only the MP3 survives: PCM is decoded into memory now.
            assertEquals(listOf("${sha(0)}.mp3"), remaining)
            assertEquals(2, before - remaining.size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun doesNothingWhenAtOrBelowTheLimit() {
        val directory = Files.createTempDirectory("chart-audio-lru-limit").toFile()
        try {
            repeat(5) { index -> createGroup(directory, index, 1_000L * index) }
            ChartAudioCacheLru.prune(directory)
            assertEquals(5, existingGroups(directory).size)
        } finally {
            directory.deleteRecursively()
        }
    }
}
