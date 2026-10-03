package org.rhythmeta.maimaid.core.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.rhythmeta.maimaid.core.database.SheetEntity
import org.rhythmeta.maimaid.core.database.SongEntity

class ThirdPartyImportPolicyTest {
    @Test fun `maps DX IDs and ignores invalid difficulties`() {
        val payload = ThirdPartyImportPolicy.decode(Json.parseToJsonElement("""{"success":true,"data":[
            {"id":123,"type":"dx","level_index":3,"achievements":100.1,"dx_score":99,"play_time":"2026-10-01T12:34:56.123Z"},
            {"id":123,"type":"dx","level_index":9,"achievements":90}]}""").jsonObject, ScoreImportProvider.Lxns)
        assertEquals(2, payload.fetchedCount)
        assertEquals(1, payload.scores.size)
        val score = payload.scores.single()
        assertEquals(10123, score.songId)
        assertNotNull(score.playedAt)
        assertEquals(score.recordId("one"), score.recordId("one"))
        assertNotEquals(score.recordId("one"), score.recordId("two"))
    }

    @Test fun `DF snapshots never become history and preserve score components`() {
        val payload = ThirdPartyImportPolicy.decode(Json.parseToJsonElement("""{"records":[
            {"song_id":10123,"type":"DX","level_index":3,"achievements":99,"dxScore":110,"fc":"app","play_time":"2026-10-01T12:00:00Z"}]}""").jsonObject, ScoreImportProvider.DivingFish)
        val incoming = payload.scores.single()
        assertNull(incoming.playedAt)
        assertNull(incoming.recordId("one"))
        val previous = ScoreRules.mergeScore("one", "chart", null, ScoreInput(100.5, 100, "fc"), 1)
        val merged = ScoreRules.mergeScore("one", "chart", previous, incoming.input, 2)
        assertEquals(100.5, merged.achievement, 0.00001)
        assertEquals(110, merged.dxScore)
        assertEquals("app", merged.fc)
        assertEquals(merged, ScoreRules.mergeScore("one", "chart", merged, incoming.input, 3))
    }

    @Test fun `utage IDs and missing timestamps remain intact`() {
        val payload = ThirdPartyImportPolicy.decode(Json.parseToJsonElement("""{"success":true,"data":[
            {"id":100123,"type":"utage","level_index":5,"achievements":90,"play_time":null},
            {"id":123,"type":"dx","level_index":3,"achievements":102}]}""").jsonObject, ScoreImportProvider.Lxns)
        assertEquals(1, payload.scores.size)
        assertEquals(100123, payload.scores.single().songId)
        assertNull(payload.scores.single().recordId("one"))
    }
    @Test fun `prefers IDs and refuses ambiguous title and Utage matches`() {
        val song = song("one", "Test")
        val chart = sheet("one-dx-master", "one", "dx", "master", 10123)
        val record = ImportedScore(10123, "Wrong title", "dx", 3, ScoreInput(99.0), null)
        assertEquals(chart, ScoreImportSheetMatcher(listOf(chart), mapOf("one" to song)).match(record))
        val titleOnly = record.copy(songId = 0, title = "Ｔｅｓｔ")
        assertEquals(chart, ScoreImportSheetMatcher(listOf(chart), mapOf("one" to song)).match(titleOnly))
        val other = chart.copy(sheetKey = "two-dx-master", songIdentifier = "two")
        assertNull(ScoreImportSheetMatcher(listOf(chart, other), mapOf("one" to song, "two" to song("two", "Test"))).match(titleOnly))
        val utage = listOf(chart.copy(type = "utage", difficulty = "協", providerSongId = 100123),
            chart.copy(type = "utage", difficulty = "耐", providerSongId = 100123))
        assertNull(ScoreImportSheetMatcher(utage, mapOf("one" to song)).match(record.copy(songId = 100123, type = "utage", levelIndex = 5)))
    }

    private fun song(id: String, title: String) = SongEntity(
        songIdentifier = id,
        category = "maimai",
        title = title,
        artist = "Artist",
        imageName = "",
        version = "CiRCLE",
        releaseDate = null,
        sortOrder = 0,
        bpm = null,
        isNew = false,
        isLocked = false,
        comment = null,
    )

    private fun sheet(
        key: String,
        songIdentifier: String,
        type: String,
        difficulty: String,
        providerSongId: Int,
        regionJp: Boolean = true,
    ) = SheetEntity(
        sheetKey = key,
        songIdentifier = songIdentifier,
        type = type,
        difficulty = difficulty,
        version = "CiRCLE",
        level = "14",
        levelValue = 14.0,
        internalLevel = "14.0",
        internalLevelValue = 14.0,
        noteDesigner = null,
        tap = 100,
        hold = 10,
        slide = 10,
        touch = 0,
        breakCount = 5,
        total = 125,
        regionJp = regionJp,
        regionIntl = true,
        regionUsa = false,
        regionCn = false,
        providerSongId = providerSongId,
    )
}
