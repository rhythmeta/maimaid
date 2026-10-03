package org.rhythmeta.maimaid

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.rhythmeta.maimaid.core.data.*
import org.rhythmeta.maimaid.core.database.*

@RunWith(AndroidJUnit4::class)
class LocalScoreImportTest {
    @Test fun importPreservesProfilesMergesBestsAndDeduplicatesHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, MaimaidDatabase::class.java).build()
        try {
            val first = UserProfileEntity("one", "One", "cn", isActive = true, createdAt = 1)
            val second = UserProfileEntity("two", "Two", "jp", isActive = false, createdAt = 2)
            database.profileDao().upsert(first)
            database.profileDao().upsert(second)
            database.catalogDao().upsertSongs(listOf(SongEntity("test", "test", "Test", "Artist", "", null, null, 0, null, false, false, null)))
            val sheet = SheetEntity("test-dx-master", "test", "dx", "master", null, "13", 13.0, null, null,
                null, null, null, null, null, null, null, true, true, false, true, providerSongId = 10123)
            database.catalogDao().upsertSheets(listOf(sheet))
            val dao = database.scoreDao()
            dao.upsertScore(ScoreRules.mergeScore("one", sheet.sheetKey, null, ScoreInput(100.5, 100, "fc"), 1))
            dao.upsertScore(ScoreRules.mergeScore("two", sheet.sheetKey, null, ScoreInput(88.0), 1))
            val service = ThirdPartyImportService(database, ProfileCredentialStore(context), Json)
            val df = ThirdPartyImportPolicy.decode(Json.parseToJsonElement("""{"records":[
                {"song_id":10123,"type":"DX","level_index":3,"achievements":99,"dxScore":110,"fc":"app"}]}""").jsonObject, ScoreImportProvider.DivingFish)
            assertEquals(1, service.apply(df, ScoreImportProvider.DivingFish, "one").updatedCount)
            assertEquals(100.5, dao.score("one", sheet.sheetKey)!!.achievement, 0.0)
            assertEquals(110, dao.score("one", sheet.sheetKey)!!.dxScore)
            assertEquals("app", dao.score("one", sheet.sheetKey)!!.fc)
            assertEquals(88.0, dao.score("two", sheet.sheetKey)!!.achievement, 0.0)
            assertTrue(dao.playRecords("one").isEmpty())
            assertEquals(0, service.apply(df, ScoreImportProvider.DivingFish, "one").updatedCount)
            val lxns = ThirdPartyImportPolicy.decode(Json.parseToJsonElement("""{"success":true,"data":[
                {"id":123,"type":"dx","level_index":3,"achievements":99,"dx_score":110,"play_time":"2026-10-01T12:34:56.123Z"}]}""").jsonObject, ScoreImportProvider.Lxns)
            repeat(2) { service.apply(lxns, ScoreImportProvider.Lxns, "one") }
            assertEquals(1, dao.playRecords("one").size)
            assertTrue(dao.playRecords("two").isEmpty())
            database.profileDao().activate(second)
            try {
                service.apply(df, ScoreImportProvider.DivingFish, "one")
                fail("A switched profile must reject the pending import")
            } catch (error: DirectImportException) {
                assertEquals("profile_changed", error.code)
            }
            assertEquals(88.0, dao.score("two", sheet.sheetKey)!!.achievement, 0.0)
            for (body in listOf("""{"access_token":"a","refresh_token":"r"}""", """{"data":{"access_token":"a","refresh_token":"r"}}""")) {
                assertEquals("a" to "r", service.tokenPair(Json.parseToJsonElement(body).jsonObject))
            }
        } finally { database.close() }
    }
}
