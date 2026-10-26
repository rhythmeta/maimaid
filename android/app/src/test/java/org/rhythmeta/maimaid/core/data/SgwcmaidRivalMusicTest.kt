package org.rhythmeta.maimaid.core.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SgwcmaidRivalMusicTest {
    private fun response(body: String) = Json.parseToJsonElement(body).jsonObject

    @Test
    fun `hoists the song id into every chart row`() {
        val cabinet = SgwcmaidRivalMusic.fromResponse(response("""
            {"userId": 1, "rivalId": 1, "length": 2, "nextIndex": 0, "userRivalMusicList": [
              {"musicId": 17, "length": 2, "userRivalMusicDetailList": [
                {"level": 2, "achievement": 977859, "deluxscoreMax": 717},
                {"level": 4, "achievement": 1002678, "deluxscoreMax": 1389}]},
              {"musicId": 111365, "length": 1, "userRivalMusicDetailList": [
                {"level": 10, "achievement": 2018433, "deluxscoreMax": 5093}]}]}
        """))

        val songs = cabinet.map { it.jsonObject }
        assertEquals(2, songs.size)
        assertEquals(listOf(17, 111365), songs.map { it["musicId"].toString().toInt() })
        val first = songs.first()["userMusicDetailList"]!!.let { it as kotlinx.serialization.json.JsonArray }
            .map { it.jsonObject }
        assertEquals(listOf(17, 17), first.map { it["musicId"].toString().toInt() })
        assertEquals(listOf(2, 4), first.map { it["level"].toString().toInt() })
    }

    @Test
    fun `quick import payload decodes achievements and keeps badges unknown`() {
        val cabinet = SgwcmaidRivalMusic.fromResponse(response("""
            {"userRivalMusicList": [
              {"musicId": 10123, "userRivalMusicDetailList": [
                {"level": 3, "achievement": 1000739, "deluxscoreMax": 2267}]},
              {"musicId": 111365, "userRivalMusicDetailList": [
                {"level": 10, "achievement": 2018433, "deluxscoreMax": 5093}]}]}
        """))

        val payload = ThirdPartyImportPolicy.decodeSgwcmaid(cabinet, keepUnknownJudgements = true)

        assertTrue(payload.keepUnknownJudgements)
        assertEquals(2, payload.fetchedCount)
        assertEquals(1, payload.scores.size)
        val score = payload.scores.single()
        assertEquals(10123, score.songId)
        assertEquals("dx", score.type)
        assertEquals(3, score.levelIndex)
        assertEquals(100.0739, score.input.achievement, 0.000001)
        assertEquals(2267, score.input.dxScore)
        // The rival payload has no combo/sync status, so the badge stays unknown instead of
        // being replaced by a fabricated value.
        assertNull(score.input.fc)
        assertNull(score.input.fs)
    }

    @Test
    fun `cabinet imports still report badges and are not marked as unknown`() {
        val payload = ThirdPartyImportPolicy.decode(Json.parseToJsonElement("""
            [{"userMusicDetailList":[{"musicId":10123,"level":3,"achievement":1000739,"comboStatus":4,"syncStatus":5,"deluxscoreMax":2267}]}]
        """), ScoreImportProvider.Sgwcmaid)

        assertFalse(payload.keepUnknownJudgements)
        assertEquals("app", payload.scores.single().input.fc)
        assertEquals("sync", payload.scores.single().input.fs)
    }

    @Test
    fun `skips rows without an achievement and empty songs`() {
        val cabinet = SgwcmaidRivalMusic.fromResponse(response("""
            {"userRivalMusicList": [
              {"musicId": 17, "userRivalMusicDetailList": [{"level": 3, "deluxscoreMax": 10}]},
              {"musicId": 18, "userRivalMusicDetailList": []},
              {"musicId": 19, "userRivalMusicDetailList": [{"level": 2, "achievement": 990000}]}]}
        """))

        val songs = cabinet.map { it.jsonObject }
        assertEquals(1, songs.size)
        assertEquals(19, songs.single()["musicId"].toString().toInt())
        val row = (songs.single()["userMusicDetailList"] as kotlinx.serialization.json.JsonArray)
            .single().jsonObject
        assertEquals(990000, row["achievement"].toString().toInt())
        assertNull(row["deluxscoreMax"])
    }
}
