package org.rhythmeta.maimaid.core.astrodx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.rhythmeta.maimaid.core.data.ScoreInput
import org.rhythmeta.maimaid.core.database.SheetEntity
import org.rhythmeta.maimaid.core.database.SongEntity

class AstroDxSheetMatcherTest {
    @Test
    fun matchesByNormalizedTitleDifficultyAndExactNotes() {
        val song = song("1", "Oshama Scramble!")
        val matching = sheet("match", song.songIdentifier, "dx", "master", 1000)
        val wrongNotes = sheet("wrong", song.songIdentifier, "dx", "master", 999)
        val result = AstroDxSheetMatcher(listOf(song), listOf(matching, wrongNotes)).match(score("[DX] Ｏｓｈａｍａ Ｓｃｒａｍｂｌｅ!", 5, 1000))
        assertEquals("match", result?.sheetKey)
    }

    @Test
    fun skipsAmbiguousAndEasyCharts() {
        val song = song("1", "Song")
        val first = sheet("one", song.songIdentifier, "std", "master", 100)
        val second = sheet("two", song.songIdentifier, "std", "master", 100)
        val matcher = AstroDxSheetMatcher(listOf(song), listOf(first, second))
        assertNull(matcher.match(score("Song", 5, 100)))
        assertNull(matcher.match(score("Song", 1, 100)))
    }

    @Test
    fun matchesUtageKanjiAndExactType() {
        val song = song("1", "Utage song")
        val chart = sheet("utage", song.songIdentifier, "utage", "【協】", 100)
        assertEquals("utage", AstroDxSheetMatcher(listOf(song), listOf(chart))
            .match(score("[協]Utage song", 7, 100))?.sheetKey)
    }

    private fun score(title: String, id: Int, notes: Int) = AstroDxScore(
        title, title, id, null, notes, ScoreInput(99.0, 100),
    )

    private fun song(id: String, title: String) = SongEntity(
        songIdentifier = id, category = "maimai", title = title, artist = "", imageName = "",
        version = null, releaseDate = null, sortOrder = 0, bpm = null, isNew = false,
        isLocked = false, comment = null,
    )

    private fun sheet(key: String, songId: String, type: String, difficulty: String, total: Int) = SheetEntity(
        sheetKey = key, songIdentifier = songId, type = type, difficulty = difficulty,
        version = null, level = "13", levelValue = 13.0, internalLevel = null,
        internalLevelValue = null, noteDesigner = null, tap = null, hold = null, slide = null,
        touch = null, breakCount = null, total = total, regionJp = true, regionIntl = true,
        regionUsa = false, regionCn = true,
    )
}
