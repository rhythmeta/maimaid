package org.rhythmeta.maimaid

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import org.rhythmeta.maimaid.core.backup.BackupCodec
import org.rhythmeta.maimaid.core.database.MaimaidDatabase
import org.rhythmeta.backup.proto.BackupProtocol as Proto

@RunWith(AndroidJUnit4::class)
class BackupRecordIdMigrationTest {
    @Test
    fun legacyOtogameRecordsSurviveMigrationAndCanBeBackedUp() {
        val helper = databaseHelper()
        try {
            val db = helper.writableDatabase
            val legacy = "fdb1ff8eb738abd5a7b442d3399a826b1dea03862503a784a7db5141e51aff78"
            val migrated = "fdb1ff8e-b738-5bd5-a7b4-42d3399a826b"
            val manual = "11111111-1111-4111-8111-111111111111"
            for (id in listOf(legacy, "B".repeat(64), manual)) {
                db.execSQL("INSERT INTO play_records VALUES (?, ?, ?)", arrayOf<Any>(id, 100.5, 1_700_000_000_000L))
            }
            MaimaidDatabase.Migration7To8.migrate(db)
            // Repeating conversion must not change already valid UUIDs.
            MaimaidDatabase.Migration7To8.migrate(db)
            val snapshot = Proto.Snapshot.newBuilder().setMagic("RHYTHMETA_BACKUP")
                .setFormatVersion(1).setGame("maimaid")
                .addProfiles(Proto.Profile.newBuilder().setId(manual).setName("Test").setActive(true))
            val ids = mutableSetOf<String>()
            db.query("SELECT id, achievement, playedAt FROM play_records").use { rows ->
                while (rows.moveToNext()) {
                    ids += rows.getString(0)
                    assertEquals(100.5, rows.getDouble(1), 0.0)
                    assertEquals(1_700_000_000_000L, rows.getLong(2))
                    snapshot.addPlayRecords(Proto.PlayRecord.newBuilder().setId(rows.getString(0))
                        .setResult(Proto.Score.newBuilder().setProfileId(manual).setChartKey("test|dx|master")
                            .setAchievement(rows.getDouble(1)).setAchievedAt(rows.getLong(2))))
                }
            }
            assertEquals(setOf(migrated, "bbbbbbbb-bbbb-5bbb-bbbb-bbbbbbbbbbbb", manual), ids)
            val exported = snapshot.build()
            assertEquals(exported, BackupCodec.decode(BackupCodec.encode(exported)))
            // A subsequent import uses the same UUID and updates the same row.
            db.execSQL("INSERT OR REPLACE INTO play_records VALUES (?, ?, ?)", arrayOf<Any>(migrated, 100.5, 1_700_000_000_000L))
            db.query("SELECT count(*) FROM play_records").use {
                it.moveToFirst()
                assertEquals(3, it.getInt(0))
            }
        } finally {
            helper.close()
        }
    }

    @Test
    fun migrationPreservesHistoryWhenAnIOSRecordAlreadyHasTheUUID() {
        val helper = databaseHelper()
        try {
            val db = helper.writableDatabase
            val legacy = "fdb1ff8eb738abd5a7b442d3399a826b1dea03862503a784a7db5141e51aff78"
            val existing = "FDB1FF8E-B738-5BD5-A7B4-42D3399A826B"
            db.execSQL("INSERT INTO play_records VALUES (?, ?, ?)", arrayOf<Any>(legacy, 100.5, 1L))
            db.execSQL("INSERT INTO play_records VALUES (?, ?, ?)", arrayOf<Any>(existing, 100.6, 2L))
            MaimaidDatabase.Migration7To8.migrate(db)
            val ids = mutableSetOf<UUID>()
            db.query("SELECT id, achievement, playedAt FROM play_records ORDER BY playedAt").use {
                assertEquals(2, it.count)
                it.moveToFirst()
                ids += UUID.fromString(it.getString(0))
                assertEquals(100.5, it.getDouble(1), 0.0)
                assertEquals(1L, it.getLong(2))
                it.moveToNext()
                assertEquals(existing, it.getString(0))
                assertTrue(ids.add(UUID.fromString(it.getString(0))))
                assertEquals(100.6, it.getDouble(1), 0.0)
                assertEquals(2L, it.getLong(2))
            }
        } finally {
            helper.close()
        }
    }

    @Test
    fun migrationDoesNotSkipRecordsWhenTheCursorWindowRefills() {
        val helper = databaseHelper()
        try {
            val db = helper.writableDatabase
            val count = 50_000
            db.beginTransaction()
            try {
                db.compileStatement("INSERT INTO play_records VALUES (?, 100.5, 1)").use { insert ->
                    repeat(count) {
                        insert.bindString(1, it.toString(16).padStart(32, '0') + "0".repeat(32))
                        insert.executeInsert()
                    }
                }
                MaimaidDatabase.Migration7To8.migrate(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            db.query("SELECT count(*), sum(length(id) = 36) FROM play_records").use {
                it.moveToFirst()
                assertEquals(count, it.getInt(0))
                assertEquals(count, it.getInt(1))
            }
        } finally {
            helper.close()
        }
    }

    private fun databaseHelper(): SupportSQLiteOpenHelper {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE play_records (id TEXT PRIMARY KEY NOT NULL, achievement REAL NOT NULL, playedAt INTEGER NOT NULL)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
    }
}
