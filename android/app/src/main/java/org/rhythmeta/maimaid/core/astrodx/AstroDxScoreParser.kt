package org.rhythmeta.maimaid.core.astrodx

import kotlinx.serialization.json.*
import org.rhythmeta.maimaid.core.data.ScoreInput
import org.rhythmeta.maimaid.core.data.ScoreRules

data class AstroDxScore(
    val title: String,
    val path: String,
    val difficultyId: Int,
    val alias: String?,
    val totalNotes: Int,
    val input: ScoreInput,
)

data class AstroDxParsedCache(val scores: List<AstroDxScore>, val invalidCount: Int)

object AstroDxScoreParser {
    fun parse(root: JsonObject): AstroDxParsedCache {
        val metadata = root["level_metadata"] as? JsonObject ?: error("cache_invalid")
        val scores = mutableListOf<AstroDxScore>()
        var invalid = 0
        for ((path, value) in metadata) {
            val song = value as? JsonObject ?: error("cache_invalid")
            val difficulties = song["difficulties"] as? JsonArray ?: error("cache_invalid")
            for (element in difficulties) {
                val chart = element as? JsonObject ?: error("cache_invalid")
                val stats = chart["stats"] as? JsonObject ?: continue
                // Failed plays can still carry a valid best score; untouched charts carry only zeros.
                val played = listOf("achievementRate", "dxScore", "clearType", "completePlays", "clearedPlays")
                    .any { (stats[it] as? JsonPrimitive)?.doubleOrNull?.let { n -> n != 0.0 } == true }
                if (!played) continue
                val rate = (stats["achievementRate"] as? JsonPrimitive)?.doubleOrNull
                val dx = (stats["dxScore"] as? JsonPrimitive)?.intOrNull
                val total = (stats["totalNotesCount"] as? JsonPrimitive)?.intOrNull
                val clear = (stats["clearType"] as? JsonPrimitive)?.intOrNull
                val id = (chart["id"] as? JsonPrimitive)?.intOrNull
                val input = rate?.let { ScoreInput(it, dx ?: -1, when (clear) {
                    3 -> "fc"
                    4 -> "fcp"
                    5 -> "ap"
                    6 -> "app"
                    else -> null
                }) }
                if (input == null || total == null || total !in 1..1_000_000 || id == null ||
                    clear !in 0..6 || ScoreRules.validate(input, total * 3) != null) {
                    invalid++
                    continue
                }
                scores += AstroDxScore(
                    title = song.string("title")?.takeIf { it.isNotBlank() } ?: path,
                    path = song.string("path") ?: path,
                    difficultyId = id,
                    alias = chart.string("alias"),
                    totalNotes = total,
                    input = input,
                )
            }
        }
        return AstroDxParsedCache(scores, invalid)
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
}
