package org.rhythmeta.maimaid.core.astrodx

import java.text.Normalizer
import java.util.Locale
import org.rhythmeta.maimaid.core.database.SheetEntity
import org.rhythmeta.maimaid.core.database.SongEntity

/** Conservative matching: exact title, difficulty and note count, with no fuzzy fallback. */
class AstroDxSheetMatcher(songs: List<SongEntity>, sheets: List<SheetEntity>) {
    private val songsByTitle = songs.groupBy { normalize(it.title) }
    private val sheetsBySong = sheets.groupBy { it.songIdentifier }

    fun match(score: AstroDxScore): SheetEntity? {
        val difficulty = difficulties[score.difficultyId]
        if (difficulty == null && score.difficultyId != 7) return null
        val title = normalize(score.title)
        val hints = typeTag.findAll("$title ${normalize(score.path)}").map {
            if (it.groupValues[1] == "dx") "dx" else "std"
        }.toSet()
        if (hints.size > 1) return null
        var cleaned = typeTag.replace(title, "").trim()
        val utageTag = if (score.difficultyId == 7) utagePrefix.find(cleaned)?.groupValues?.get(1) else null
        if (utageTag != null) cleaned = utagePrefix.replaceFirst(cleaned, "").trim()
        val candidates = songsByTitle[cleaned].orEmpty().flatMap { sheetsBySong[it.songIdentifier].orEmpty() }
            .filter { sheet ->
                sheet.total == score.totalNotes && if (score.difficultyId == 7) {
                    sheet.type.equals("utage", ignoreCase = true) &&
                        (utageTag == null || normalizeUtage(sheet.difficulty) == utageTag)
                } else {
                    sheet.type in setOf("std", "standard", "dx") &&
                        sheet.difficulty.lowercase(Locale.ROOT) == difficulty &&
                        (hints.isEmpty() || (if (sheet.type == "standard") "std" else sheet.type) in hints)
                }
            }
        return candidates.singleOrNull()
    }

    private companion object {
        val difficulties = mapOf(2 to "basic", 3 to "advanced", 4 to "expert", 5 to "master", 6 to "remaster")
        val typeTag = Regex("\\[(dx|st|std)]")
        val utagePrefix = Regex("^(?:\\[|【)([宴光星協耐蛸習蔵回覚逆速遅舞狂跳撫滑割弾技音傾？疑嘘])(?:]|】)")
        fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT)
        fun normalizeUtage(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .filter(Char::isLetterOrDigit)
    }
}
