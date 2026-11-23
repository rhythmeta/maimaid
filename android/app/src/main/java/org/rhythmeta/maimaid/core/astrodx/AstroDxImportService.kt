package org.rhythmeta.maimaid.core.astrodx

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.rhythmeta.maimaid.core.data.ScoreRules
import org.rhythmeta.maimaid.core.database.MaimaidDatabase
import org.rhythmeta.maimaid.core.database.ScoreEntity
import kotlin.coroutines.coroutineContext

data class AstroDxImportResult(val found: Int, val matched: Int, val updated: Int, val skipped: Int)

class AstroDxImportService(private val database: MaimaidDatabase) {
    suspend fun import(bytes: ByteArray, profileId: String): AstroDxImportResult = withContext(Dispatchers.Default) {
        val parsed = AstroDxScoreParser.parse(AstroDxCacheCodec.decode(bytes))
        apply(parsed, profileId)
    }

    suspend fun apply(parsed: AstroDxParsedCache, profileId: String): AstroDxImportResult = database.withTransaction {
        check(database.profileDao().activeProfile()?.id == profileId) { "profile_changed" }
        val catalog = database.catalogDao()
        val sheets = catalog.sheets()
        check(sheets.isNotEmpty()) { "catalog_empty" }
        val matcher = AstroDxSheetMatcher(catalog.songs(), sheets)
        val dao = database.scoreDao()
        val pending = linkedMapOf<String, ScoreEntity>()
        val original = mutableMapOf<String, ScoreEntity?>()
        var matched = 0
        val now = System.currentTimeMillis()
        for (score in parsed.scores) {
            coroutineContext.ensureActive()
            val sheet = matcher.match(score) ?: continue
            matched++
            if (sheet.sheetKey !in original) original[sheet.sheetKey] = dao.score(profileId, sheet.sheetKey)
            val existing = pending[sheet.sheetKey] ?: original[sheet.sheetKey]
            // The cache has no play dates. achievedAt is the local import time; no play records are invented.
            pending[sheet.sheetKey] = ScoreRules.mergeScore(profileId, sheet.sheetKey, existing, score.input, now)
        }
        var updated = 0
        for ((key, score) in pending) {
            coroutineContext.ensureActive()
            if (score != original[key]) {
                dao.upsertScore(score)
                updated++
            }
        }
        AstroDxImportResult(parsed.scores.size + parsed.invalidCount, matched, updated,
            parsed.scores.size - matched + parsed.invalidCount)
    }
}
