package org.rhythmeta.maimaid.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rhythmeta.maimaid.core.database.GameVersionEntity
import org.rhythmeta.maimaid.core.database.SheetEntity
import org.rhythmeta.maimaid.core.database.SongEntity

class TheoreticalBest50Test {
    @Test
    fun `picks the highest rated old and new charts for a perfect play`() {
        val versions = listOf(
            GameVersionEntity("DX", "DX", null, 0),
            GameVersionEntity("UNiVERSE", "UNiVERSE", null, 1),
            GameVersionEntity("CiRCLE", "CiRCLE", null, 2),
        )
        val oldLevels = (0 until 40).map { 10.0 + it * 0.1 }
        val newLevels = (0 until 20).map { 13.0 + it * 0.1 }
        val songs = oldLevels.mapIndexed { index, _ -> song("old-$index", "DX") } +
            newLevels.mapIndexed { index, _ -> song("new-$index", "CiRCLE") }
        val sheets = oldLevels.mapIndexed { index, level ->
            sheet("old-$index", "old-$index", "DX", level)
        } + newLevels.mapIndexed { index, level ->
            sheet("new-$index", "new-$index", "CiRCLE", level)
        }

        val result = calculateTheoreticalBest50(
            versions = versions,
            songs = songs,
            sheets = sheets,
            server = "jp",
            versionOverride = "CiRCLE",
        )

        val expectedOld = oldLevels.sortedDescending().take(35).map(::theoreticalRating)
        val expectedNew = newLevels.sortedDescending().take(15).map(::theoreticalRating)
        assertEquals(35, result.b35.size)
        assertEquals(15, result.b15.size)
        assertEquals((expectedOld + expectedNew).sum(), result.total)
        assertEquals(expectedOld.minOrNull()!!, result.b35.minOf { it.rating })
        assertEquals(expectedNew.minOrNull()!!, result.b15.minOf { it.rating })
        result.b35.forEach { entry ->
            assertEquals(101.0, entry.achievement, 0.0001)
            assertEquals("app", entry.fc)
            assertEquals("fsdp", entry.fs)
            assertEquals(3_000, entry.dxScore)
        }
        assertTrue(result.b35.all { !it.isNew })
        assertTrue(result.b15.all { it.isNew })
    }

    @Test
    fun `uses the selected version constant and falls back to the server constant`() {
        val versions = listOf(GameVersionEntity("CiRCLE", "CiRCLE", null, 0))
        val result = calculateTheoreticalBest50(
            versions = versions,
            songs = listOf(song("song", "CiRCLE")),
            sheets = listOf(sheet("song-dx-master", "song", "CiRCLE", 13.9, versionConstant = 13.7)),
            server = "jp",
            versionOverride = "CiRCLE",
        )
        val fallback = calculateTheoreticalBest50(
            versions = versions,
            songs = listOf(song("song", "CiRCLE")),
            sheets = listOf(sheet("song-dx-master", "song", "CiRCLE", 13.9, versionConstant = null)),
            server = "jp",
            versionOverride = "CiRCLE",
        )

        assertEquals(13.7, result.b15.single().level, 0.0001)
        assertEquals(theoreticalRating(13.7), result.b15.single().rating)
        assertEquals(13.9, fallback.b15.single().level, 0.0001)
        assertEquals(theoreticalRating(13.9), fallback.total)
    }

    @Test
    fun `ignores removed songs, removed sheets, and charts unavailable on the server`() {
        val versions = listOf(GameVersionEntity("CiRCLE", "CiRCLE", null, 0))
        val result = calculateTheoreticalBest50(
            versions = versions,
            songs = listOf(
                song("ok", "CiRCLE"),
                song("removed-song", "CiRCLE", removed = true),
                song("hidden", "CiRCLE"),
            ),
            sheets = listOf(
                sheet("ok-dx-master", "ok", "CiRCLE", 13.5),
                sheet("removed-song-dx-master", "removed-song", "CiRCLE", 14.0),
                sheet("removed-sheet-dx-master", "ok", "CiRCLE", 14.5, removed = true),
                sheet("hidden-dx-master", "hidden", "CiRCLE", 14.8, regionJp = false),
            ),
            server = "jp",
            versionOverride = null,
        )

        assertEquals(listOf("ok-dx-master"), result.b15.map { it.sheetKey })
        assertEquals(theoreticalRating(13.5), result.total)
    }

    private fun theoreticalRating(level: Double): Int =
        RatingUtils.calculate(level, 101.0, "app", afterCircle = true)

    private fun song(
        identifier: String,
        version: String,
        removed: Boolean = false,
    ) = SongEntity(
        songIdentifier = identifier,
        category = "maimai",
        title = identifier,
        artist = "",
        imageName = "$identifier.png",
        version = version,
        releaseDate = null,
        sortOrder = 0,
        bpm = null,
        isNew = false,
        isLocked = false,
        comment = null,
        isRemoved = removed,
    )

    private fun sheet(
        key: String,
        songIdentifier: String,
        version: String,
        level: Double,
        versionConstant: Double? = level,
        removed: Boolean = false,
        regionJp: Boolean = true,
    ) = SheetEntity(
        sheetKey = key,
        songIdentifier = songIdentifier,
        type = "dx",
        difficulty = "master",
        version = version,
        level = "13+",
        levelValue = level,
        internalLevel = level.toString(),
        internalLevelValue = level,
        noteDesigner = null,
        tap = null,
        hold = null,
        slide = null,
        touch = null,
        breakCount = null,
        total = 1_000,
        regionJp = regionJp,
        regionIntl = true,
        regionUsa = true,
        regionCn = true,
        isRemoved = removed,
        multiverInternalLevelValue = versionConstant?.let { mapOf("CiRCLE" to it) },
    )
}
