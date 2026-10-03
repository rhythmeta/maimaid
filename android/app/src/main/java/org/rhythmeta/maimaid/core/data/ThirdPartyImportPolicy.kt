package org.rhythmeta.maimaid.core.data

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*
import org.rhythmeta.maimaid.core.database.SheetEntity
import org.rhythmeta.maimaid.core.database.SongEntity

enum class ScoreImportProvider { DivingFish, Lxns }

data class ImportedScore(
    val songId: Int,
    val title: String,
    val type: String,
    val levelIndex: Int,
    val input: ScoreInput,
    val playedAt: Long?,
) {
    fun recordId(profileId: String): String? {
        val time = playedAt ?: return null
        val key = listOf("lxns", profileId.lowercase(), songId, type, levelIndex, time,
            kotlin.math.round(input.achievement * 10000).toLong(), input.dxScore,
            input.fc.orEmpty(), input.fs.orEmpty()).joinToString("|")
        val bytes = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        bytes[6] = ((bytes[6].toInt() and 15) or 80).toByte()
        bytes[8] = ((bytes[8].toInt() and 63) or 128).toByte()
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long).toString()
    }
}

data class ScoreImportPayload(
    val scores: List<ImportedScore>, val fetchedCount: Int,
    val name: String? = null, val rating: Int? = null, val plate: String? = null,
)

object ThirdPartyImportPolicy {
    fun decode(root: JsonObject, provider: ScoreImportProvider): ScoreImportPayload {
        val lxns = provider == ScoreImportProvider.Lxns
        check(!lxns || root["success"]?.jsonPrimitive?.booleanOrNull == true) { "Invalid score response" }
        val rows = root[if (lxns) "data" else "records"] as? JsonArray ?: error("Invalid score response")
        val scores = rows.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val achievement = row["achievements"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val type = chartType(row.text("type").orEmpty())
            val level = row["level_index"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            if (type !in listOf("standard", "dx", "utage") || (type != "utage" && level !in 0..4)) return@mapNotNull null
            val rawId = row[if (lxns) "id" else "song_id"]?.jsonPrimitive?.intOrNull ?: 0
            val id = if (lxns && type == "dx" && rawId in 1..9999) rawId + 10000 else rawId
            val input = ScoreInput(achievement,
                row["dx_score"]?.jsonPrimitive?.intOrNull ?: row["dxScore"]?.jsonPrimitive?.intOrNull ?: 0,
                ScoreRules.canonicalFc(row.text("fc")), ScoreRules.canonicalFs(row.text("fs")))
            if (ScoreRules.validate(input, 0) != null) return@mapNotNull null
            val date = if (lxns) row.text("play_time")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                ?.takeIf { it > 0 } else null
            ImportedScore(id, row.text(if (lxns) "song_name" else "title").orEmpty(), type, level, input, date)
        }
        return ScoreImportPayload(scores, rows.size, root.text("nickname"),
            root["rating"]?.jsonPrimitive?.intOrNull, root.text("plate"))
    }

    fun chartType(value: String): String = when (val type = value.lowercase()) {
        "sd", "std" -> "standard"
        else -> type
    }

    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).trim().lowercase()
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
