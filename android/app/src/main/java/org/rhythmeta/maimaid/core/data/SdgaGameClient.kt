package org.rhythmeta.maimaid.core.data

import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.rhythmeta.maimaid.BuildConfig

/** The scores read from the international title server, plus the identity they belong to. */
internal data class SdgaFetchedMusic(
    val music: JsonArray,
    val userId: Long,
    val playerName: String,
)

/**
 * The international (`SDGA`) title-server client.
 *
 * It speaks the same `Maimai2Servlet` protocol as the China client — zlib + AES-CBC bodies,
 * the same `GetUserPreviewApi` / `UserLoginApi` / `GetUserMusicApi` / `UserLogoutApi` calls
 * — but differs in every deployment detail: the `MaimaiExp` hash salt, the `SDGA` game id,
 * a fixed `number` header, the 1.65 encoding, no `urn` path segment, and a login body that
 * carries a real `accessCode` instead of a QR token.
 *
 * Only the read path is implemented: preview, login, paginated music, logout. Nothing here
 * uploads user data.
 */
internal class SdgaGameClient(
    private val config: SdgaConfig,
    private val json: Json,
    private val http: OkHttpClient = defaultHttpClient(),
) {
    private val cookies = linkedMapOf<String, String>()

    fun loginAndFetchMusic(account: AimeDbAccount, cabinet: SdgaCabinet): SdgaFetchedMusic {
        if (!config.isComplete) throw DirectImportException("sdga_not_configured")
        val userId = account.userId
        val clientId = cabinet.keychip

        val preview = call(
            "GetUserPreviewApi",
            buildJsonObject {
                put("userId", JsonPrimitive(userId))
                put("segaIdAuthKey", JsonPrimitive(account.authKey))
                put("clientId", JsonPrimitive(""))
            },
            userId,
            clientId,
        )
        if ((preview["errorId"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0) != 0) {
            throw DirectImportException("game_preview_error")
        }
        // isLogin=true means the account is still in another game session; the server then
        // rejects UserLoginApi and applies a ~15 minute penalty, so stop instead of retrying.
        if (preview["isLogin"]?.jsonPrimitive?.booleanOrNull == true) {
            throw DirectImportException("game_account_already_logged_in")
        }
        val playerName = preview["userName"]?.jsonPrimitive?.contentOrNull.orEmpty()

        val now = Instant.now().epochSecond
        // The cabinet freezes dateTime at power-on, which is earlier than the login itself;
        // the reference client fakes that gap with a random 1000..2000 second offset.
        val bootStamp = now - Random.nextLong(1_000, 2_001)
        val login = buildJsonObject {
            put("userId", JsonPrimitive(userId))
            put("accessCode", JsonPrimitive(account.accessCode))
            put("regionId", JsonPrimitive(cabinet.regionId))
            put("placeId", JsonPrimitive(cabinet.placeId))
            put("clientId", JsonPrimitive(clientId))
            put("dateTime", JsonPrimitive(bootStamp))
            put("loginDateTime", JsonPrimitive(now))
            put("isContinue", JsonPrimitive(false))
            put("genericFlag", JsonPrimitive(0))
        }
        val returnCode = call("UserLoginApi", login, userId, clientId)
            .let { it["returnCode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0 }
        if (returnCode != 1) throw DirectImportException("game_login_$returnCode")

        try {
            return SdgaFetchedMusic(readMusic(userId, clientId), userId, playerName)
        } finally {
            // LogoutType.TestIn (4) uses the simple request VO; type=1 requires a 26-slot
            // delayLog and would leave the session open if it were omitted.
            logout(userId, account.accessCode, cabinet, now)
        }
    }

    private fun readMusic(userId: Long, clientId: String): JsonArray {
        val records = mutableListOf<JsonObject>()
        var index = 0
        repeat(MAX_PAGES) {
            val result = call(
                "GetUserMusicApi",
                buildJsonObject {
                    put("userId", JsonPrimitive(userId))
                    put("nextIndex", JsonPrimitive(index))
                    put("maxCount", JsonPrimitive(999))
                },
                userId,
                clientId,
            )
            result["userMusicList"]?.jsonArray?.mapTo(records) { it.jsonObject }
            val next = result["nextIndex"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            if (next == 0) return JsonArray(records)
            if (next == index) throw DirectImportException("game_pagination_stalled")
            index = next
        }
        throw DirectImportException("game_pagination_limit")
    }

    private fun logout(
        userId: Long,
        accessCode: String,
        cabinet: SdgaCabinet,
        loginStamp: Long,
    ) {
        runCatching {
            call(
                "UserLogoutApi",
                buildJsonObject {
                    put("userId", JsonPrimitive(userId))
                    put("accessCode", JsonPrimitive(accessCode))
                    put("regionId", JsonPrimitive(cabinet.regionId))
                    put("placeId", JsonPrimitive(cabinet.placeId))
                    put("clientId", JsonPrimitive(cabinet.keychip))
                    put("loginDateTime", JsonPrimitive(loginStamp))
                    put("type", JsonPrimitive(4))
                },
                userId,
                cabinet.keychip,
            )
            Unit
        }
    }

    private fun call(api: String, body: JsonObject, userId: Long, clientId: String): JsonObject {
        val digest = md5Hex(api + SALT + config.obfuscateParam)
        val url = config.servletBase + digest
        val agent = if (api in CLIENT_ID_UA_APIS) clientId else userId.toString()
        val request = Request.Builder()
            .url(url)
            .header("user-agent", "$digest#$agent")
            .header("Accept", "*/*")
            .header("charset", "UTF-8")
            .header("Mai-Encoding", config.gameVersion)
            .header("Content-Encoding", "deflate")
            .header("number", "100")
            .apply { if (cookies.isNotEmpty()) header("Cookie", cookieHeader()) }
            .post(
                SgwcmaidPayloadCodec.protect(
                    json.encodeToString(JsonObject.serializer(), body),
                    config.aesKey,
                    config.aesIv,
                ).toRequestBody(JSON),
            )
            .build()
        val response = try {
            http.newCall(request).execute()
        } catch (error: java.io.IOException) {
            throw DirectImportException("game_network")
        }
        response.use { response ->
            response.headers.values("Set-Cookie").forEach { raw ->
                val pair = raw.substringBefore(';').trim()
                val separator = pair.indexOf('=')
                if (separator > 0) {
                    cookies[pair.substring(0, separator).trim()] = pair.substring(separator + 1).trim()
                }
            }
            if (!response.isSuccessful) throw DirectImportException("game_http_${response.code}")
            val payload = response.body.bytes()
            if (payload.isEmpty()) throw DirectImportException("game_empty_response")
            val text = SgwcmaidPayloadCodec.unprotect(payload, config.aesKey, config.aesIv)
            if (text.isBlank()) throw DirectImportException("game_empty_text_$api")
            if (BuildConfig.DEBUG) {
                android.util.Log.d("SdgaGame", "$api ${payload.size}B " + text.take(160))
            }
            return runCatching { json.parseToJsonElement(text).jsonObject }
                .getOrElse { throw DirectImportException("game_bad_json_${api}_${text.length}") }
        }
    }

    private fun cookieHeader(): String =
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    companion object {
        private const val SALT = "MaimaiExp"
        private const val MAX_PAGES = 10_000
        private val JSON = "application/json".toMediaType()

        /**
         * Requests whose UA must carry the cabinet id instead of the player id. The read
         * path above never calls one, but the list is kept so a later write path cannot
         * silently get it wrong.
         */
        private val CLIENT_ID_UA_APIS = setOf(
            "GetGameSettingApi", "GetGameEventApi", "GetGameRankingApi", "GetGameChargeApi",
            "GetGameFestaApi", "GetGameKaleidxScopeApi", "GetGameMusicScoreApi",
            "GetGameNgMusicIdApi", "GetGameTournamentInfoApi", "GetGameWeeklyDataApi",
            "GetPlaceCircleDataApi", "UpsertClientBookkeepingApi", "UpsertClientPlayTimeApi",
            "UpsertClientSettingApi", "UpsertClientTestmodeApi", "UpsertClientUploadApi",
            "GetUserActivityApi", "GetUserPortraitApi", "UploadUserPhotoApi",
            "UploadUserPortraitApi",
        )

        /** The international server rejects gzip-encoded requests, so drop the header. */
        internal fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().removeHeader("Accept-Encoding").build())
            }
            .build()

        private fun md5Hex(value: String): String =
            MessageDigest.getInstance("MD5").digest(value.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
