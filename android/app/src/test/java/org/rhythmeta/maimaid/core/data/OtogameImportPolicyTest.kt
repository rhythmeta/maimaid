package org.rhythmeta.maimaid.core.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class OtogameImportPolicyTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `only Japanese profiles are eligible`() {
        assertTrue(OtogameImportPolicy.isEligibleServer("jp"))
        assertTrue(OtogameImportPolicy.isEligibleServer(" JP "))
        assertFalse(OtogameImportPolicy.isEligibleServer("intl"))
        assertFalse(OtogameImportPolicy.isEligibleServer("cn"))
        assertFalse(OtogameImportPolicy.isEligibleServer(null))
    }

    @Test
    fun `maps Otogame score values to local canonical values`() {
        assertEquals(98.9266, OtogameImportPolicy.achievement(989_266), 0.000001)
        assertEquals("remaster", OtogameImportPolicy.difficulty(4))
        assertEquals("utage", OtogameImportPolicy.difficulty(10))
        assertNull(OtogameImportPolicy.difficulty(5))
        assertEquals("SSS+", OtogameImportPolicy.rank(13))
        assertEquals("app", OtogameImportPolicy.fullCombo(4))
        assertEquals("fsdp", OtogameImportPolicy.fullSync(4))
        assertEquals("sync", OtogameImportPolicy.fullSync(5))
    }

    @Test
    fun `stable record identity is deterministic and profile scoped`() {
        val record = playlog()
        val first = OtogameImportPolicy.stableRecordId("profile-a", record)

        assertEquals(first, OtogameImportPolicy.stableRecordId("profile-a", record))
        assertNotEquals(first, OtogameImportPolicy.stableRecordId("profile-b", record))
        assertNotEquals(first, OtogameImportPolicy.stableRecordId("profile-a", record.copy(trackNo = 2)))
        assertEquals(first, UUID.fromString(first).toString())
    }

    @Test
    fun `record UUID matches iOS and ignores profile UUID case`() {
        val profile = "11111111-1111-4111-8111-111111111111"
        assertEquals("fdb1ff8e-b738-5bd5-a7b4-42d3399a826b", OtogameImportPolicy.stableRecordId(profile, playlog()))
        val mixedCaseProfile = "a1111111-b111-4111-8111-c11111111111"
        assertEquals(
            OtogameImportPolicy.stableRecordId(mixedCaseProfile, playlog()),
            OtogameImportPolicy.stableRecordId(mixedCaseProfile.uppercase(), playlog()),
        )
    }

    @Test
    fun `reimport recognizes migrated legacy and restored iOS IDs`() {
        val profile = "11111111-1111-4111-8111-111111111111"
        assertTrue(OtogameImportPolicy.isPreviouslyImported(profile, playlog(), setOf("fdb1ff8e-b738-5bd5-a7b4-42d3399a826b")))
        // Older imports hashed the original profile UUID case, before normalization.
        assertTrue(OtogameImportPolicy.isPreviouslyImported(
            "A1111111-B111-4111-8111-C11111111111", playlog(), setOf("e48901f8-e4bf-5aac-8d99-a21c3242a7e1"),
        ))
        assertFalse(OtogameImportPolicy.isPreviouslyImported(profile, playlog().copy(trackNo = 2), setOf("fdb1ff8e-b738-5bd5-a7b4-42d3399a826b")))
    }

    @Test
    fun `response accepts Otogame snake case fields`() {
        val response = json.decodeFromString<OtogamePlaylogResponse>(
            """
            {
              "code": "ok",
              "message": "",
              "data": {
                "data": [{
                "music": {"music_id": "d2bb79f9-random", "name": "Test", "is_deluxe": true},
                  "difficulty": 3,
                  "level_info": {"level": 22},
                  "track_no": 1,
                  "play_date": 1700000000,
                  "achievement": 1005000,
                  "score_rank": 13,
                  "deluxe_score": 1234,
                  "combo_status": 4,
                  "sync_status": 5
                }],
                "pagination": {"page": 1, "per_page": 10, "total_page": 12}
              }
            }
            """.trimIndent(),
        )

        assertEquals("d2bb79f9-random", response.data.data.single().music.musicId)
        assertEquals(3, OtogameImportPolicy.difficultyCode(response.data.data.single()))
        assertEquals(12, response.data.pagination.totalPage)
    }

    @Test
    fun `rating response decodes current B35 and B15 score lists`() {
        val response = json.decodeFromString<OtogameRatingResponse>(
            """
            {
              "code": "ok",
              "message": "",
              "data": {
                "rating_list": [{
                  "music": {"music_id": "old", "name": "Old Song", "is_deluxe": false},
                  "level_info": {"difficulty": 3, "level": 20},
                  "achievement": 1000000,
                  "rating": 300,
                  "combo_status": 1
                }],
                "new_rating_list": [{
                  "music": {"music_id": "new", "name": "New Song", "is_deluxe": true},
                  "level_info": {"difficulty": 4, "level": 21},
                  "achievement": 1005000,
                  "rating": 310,
                  "combo_status": 4
                }]
              }
            }
            """.trimIndent(),
        )

        assertEquals("Old Song", response.data.ratingList.single().music.name)
        assertEquals("New Song", response.data.newRatingList.single().music.name)
        assertEquals(4, response.data.newRatingList.single().comboStatus)
    }

    private fun playlog() = OtogamePlaylog(
        music = OtogameMusic(musicId = "d2bb79f9-random", name = "Test", isDeluxe = true),
        difficulty = 3,
        levelInfo = OtogameLevelInfo(level = 22),
        trackNo = 1,
        playDate = 1_700_000_000,
        achievement = 1_005_000,
        scoreRank = 13,
        deluxeScore = 1_234,
        comboStatus = 4,
        syncStatus = 5,
    )
}
