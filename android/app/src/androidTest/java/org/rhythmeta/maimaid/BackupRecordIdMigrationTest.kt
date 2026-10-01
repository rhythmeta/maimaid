package org.rhythmeta.maimaid

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.rhythmeta.maimaid.core.backup.BackupCodec
import org.rhythmeta.maimaid.core.database.MaimaidDatabase
import org.rhythmeta.backup.proto.BackupProtocol as Proto

@RunWith(AndroidJUnit4::class)
class BackupRecordIdMigrationTest {
    @Test
    fun legacyOtogameRecordsSurviveMigrationAndCanBeBackedUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE play_records (id TEXT PRIMARY KEY NOT NULL, achievement REAL NOT NULL, playedAt INTEGER NOT NULL)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
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
}
