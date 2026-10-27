package org.rhythmeta.maimaid.core.data

import kotlinx.serialization.json.*

/**
 * Converts a `GetUserRivalMusicApi` response into the cabinet `music.json` shape that
 * [ThirdPartyImportPolicy] already decodes.
 *
 * The quick import reads the player's own best scores through the rival endpoint, which can
 * be queried without opening a game session. Its payload differs from `GetUserMusicApi` in
 * two ways:
 *
 *  * the list is named `userRivalMusicList` and is grouped per song, and
 *  * each chart row omits `musicId` (it lives on the song) and only carries the achievement
 *    and the best DX score.
 *
 * The conversion therefore hoists the song id into every chart row and leaves the
 * cabinet-only fields (play count, combo/sync status, score rank) absent. The import policy
 * treats those as unknown instead of clearing what the profile already has.
 */
object SgwcmaidRivalMusic {
    fun fromResponse(response: JsonObject): JsonArray = fromSongs(songsFrom(response))

    fun songsFrom(response: JsonObject): List<JsonObject> =
        (response["userRivalMusicList"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

    fun fromSongs(songs: List<JsonObject>): JsonArray = JsonArray(songs.mapNotNull { song ->
        val musicId = song["musicId"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
        val rows = (song["userRivalMusicDetailList"] as? JsonArray).orEmpty()
            .filterIsInstance<JsonObject>()
            .mapNotNull { chartRow(musicId, it) }
        if (rows.isEmpty()) return@mapNotNull null
        buildJsonObject {
            put("musicId", JsonPrimitive(musicId))
            put("length", JsonPrimitive(rows.size))
            put("userMusicDetailList", JsonArray(rows))
        }
    })

    private fun chartRow(musicId: Int, detail: JsonObject): JsonObject? {
        val level = detail["level"]?.jsonPrimitive?.intOrNull ?: return null
        val achievement = detail["achievement"]?.jsonPrimitive ?: return null
        if (achievement.intOrNull == null && achievement.doubleOrNull == null) return null
        return buildJsonObject {
            put("musicId", JsonPrimitive(musicId))
            put("level", JsonPrimitive(level))
            put("achievement", achievement)
            detail["deluxscoreMax"]?.jsonPrimitive?.intOrNull?.let { put("deluxscoreMax", JsonPrimitive(it)) }
        }
    }
}
