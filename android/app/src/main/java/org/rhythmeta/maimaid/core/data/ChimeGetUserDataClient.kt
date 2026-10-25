package org.rhythmeta.maimaid.core.data

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.rhythmeta.maimaid.BuildConfig

class ChimeGetUserDataClient(
    private val json: Json,
    private val keychips: ChimeCredentialStore,
    private val http: OkHttpClient = defaultHttpClient(),
) {
    suspend fun fetchMusic(code: String): JsonElement = withContext(Dispatchers.IO) {
        val config = SgwcmaidBuildConfig.current
        if (!config.isComplete) throw DirectImportException("sgwcmaid_not_configured")
        val chimeUrl = config.chimeUrl
        val gameServer = config.gameServer
        val keychip = keychips.keychip().takeIf { it.isNotBlank() } ?: throw DirectImportException("chime_keychip_missing")
        if (!code.startsWith("SGWCMAID") || code.length <= 20) throw DirectImportException("invalid_sgwcmaid")
        val timestamp = UTC_STAMP.format(Instant.now())
        val key = sha256Hex("$keychip$timestamp${config.chimeSecret}")
        val chime = postJson(chimeUrl, buildJsonObject {
            put("chipID", JsonPrimitive(keychip)); put("openGameID", JsonPrimitive("MAID")); put("key", JsonPrimitive(key))
            put("qrCode", JsonPrimitive(code.substring(20))); put("timestamp", JsonPrimitive(timestamp)); put("titlekey", JsonPrimitive(config.titleKey))
        })
        // chime answers failures with {"errorID":1,"userID":-1,"token":""}; surface the real reason
        // instead of reporting empty credentials.
        val errorId = chime["errorID"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: -1
        if (errorId != 0) throw DirectImportException(
            when (errorId) {
                1 -> "chime_code_invalid_or_expired"
                3 -> "chime_code_already_used"
                4 -> "chime_db_resolve_fail"
                5 -> "chime_db_access_timeout"
                6 -> "chime_db_access_fail"
                else -> "chime_error_$errorId"
            },
        )
        val userId = chime["userID"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.takeIf { it > 0 }
            ?: throw DirectImportException("chime_empty_credentials")
        val token = chime["token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw DirectImportException("chime_empty_credentials")
        GameClient(keychip.replace("-", "").take(11), gameServer, http, json).fetch(userId, token)
    }

    private fun postJson(url: String, body: JsonObject): JsonObject {
        val request = Request.Builder().url(url).header("user-agent", "WC_AIME_LIB")
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw DirectImportException("chime_http_${response.code}")
            val text = response.body.string()
            if (text.isBlank()) throw DirectImportException("chime_empty_body")
            if (BuildConfig.DEBUG) android.util.Log.d("ChimeHttp", "chime resp=" + redact(text).take(300))
            return runCatching { json.parseToJsonElement(text).jsonObject }
                .getOrElse { throw DirectImportException("chime_bad_json_${text.length}") }
        }
    }

    private class GameClient(
        private val clientId: String,
        private val gameServer: String,
        private val http: OkHttpClient,
        private val json: Json,
    ) {
        private var urn = ""
        // Cookies are keyed by name, like the reference client (a dict), so a refreshed
        // JSESSIONID replaces the old value instead of being appended next to it.
        private val cookies = linkedMapOf<String, String>()

        fun fetch(userId: Long, token: String): JsonElement {
            val setting = call("GetGameSettingApi", buildJsonObject { put("placeId", JsonPrimitive(config.placeId)); put("clientId", JsonPrimitive(clientId)) })
            urn = setting["gameSetting"]?.jsonObject?.get("movieServerUri")?.jsonPrimitive?.contentOrNull.orEmpty()
            if (urn.isNotEmpty() && !urn.endsWith('/')) urn += "/"
            val preview = call("GetUserPreviewApi", buildJsonObject { put("userId", JsonPrimitive(userId)); put("segaIdAuthKey", JsonPrimitive("")); put("token", JsonPrimitive(token)); put("clientId", JsonPrimitive(clientId)) }, userId)
            if ((preview["errorId"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0) != 0) throw DirectImportException("game_preview_error")
            // isLogin=true means the account is still logged in elsewhere; the server then
            // rejects UserLoginApi (returnCode 100) and applies a ~15 minute penalty, so
            // do not hammer it — stop here and let the lock expire.
            if (preview["isLogin"]?.jsonPrimitive?.booleanOrNull == true) throw DirectImportException("game_account_already_logged_in")
            val stamp = System.currentTimeMillis() / 1000
            val login = buildJsonObject { put("userId", JsonPrimitive(userId)); put("accessCode", JsonPrimitive("")); put("regionId", JsonPrimitive(config.regionId)); put("placeId", JsonPrimitive(config.placeId)); put("clientId", JsonPrimitive(clientId)); put("dateTime", JsonPrimitive(stamp)); put("loginDateTime", JsonPrimitive(stamp)); put("isContinue", JsonPrimitive(false)); put("genericFlag", JsonPrimitive(0)); put("token", JsonPrimitive(token)) }
            // returnCode may be absent on success; the reference client treats a missing
            // value as 0 and keeps going even if a takeover retry still reports a code.
            var returnCode = call("UserLoginApi", login, userId).returnCode()
            if (returnCode !in listOf(0, 1)) {
                returnCode = call("UserLoginApi", buildJsonObject { login.forEach { (key, value) -> put(key, value) }; put("isContinue", JsonPrimitive(true)) }, userId).returnCode()
            }
            if (BuildConfig.DEBUG) android.util.Log.d("ChimeHttp", "login returnCode=$returnCode cookies=${cookies.keys}")
            val records = mutableListOf<JsonObject>(); var index = 0
            try {
                repeat(10000) {
                    val result = call("GetUserMusicApi", buildJsonObject { put("userId", JsonPrimitive(userId)); put("nextIndex", JsonPrimitive(index)) }, userId)
                    result["userMusicList"]?.jsonArray?.mapTo(records) { it.jsonObject }
                    val next = result["nextIndex"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                    if (next == 0) return JsonArray(records)
                    if (next == index) throw DirectImportException("game_pagination_stalled")
                    index = next
                }
                throw DirectImportException("game_pagination_limit")
            } finally {
                logout(userId, stamp)
            }
        }

        private fun logout(userId: Long, loginStamp: Long) {
            runCatching {
                call("UserLogoutApi", buildJsonObject { put("userId", JsonPrimitive(userId)); put("accessCode", JsonPrimitive("")); put("regionId", JsonPrimitive(config.regionId)); put("placeId", JsonPrimitive(config.placeId)); put("clientId", JsonPrimitive(clientId)); put("loginDateTime", JsonPrimitive(loginStamp)); put("type", JsonPrimitive(1)) }, userId)
                Unit
            }
        }

        private fun call(api: String, body: JsonObject, userId: Long = 0): JsonObject {
            val digest = md5Hex("${api}MaimaiChn${config.apiSalt}")
            val url = "$gameServer/Maimai2Servlet/$urn$digest"
            // Mirror the known-good client exactly: it sends Accept/charset and a JSON body.
            val request = Request.Builder().url(url).header("user-agent", "$digest#${if (userId == 0L) clientId else userId}")
                .header("Accept", "*/*").header("charset", "UTF-8")
                .header("Mai-Encoding", "1.55").header("Content-Encoding", "deflate")
                .post(protect(json.encodeToString(JsonObject.serializer(), body)).toRequestBody(JSON)).apply {
                    if (cookies.isNotEmpty()) header("Cookie", cookieHeader())
                }.build()
            http.newCall(request).execute().use { response ->
                response.headers.values("Set-Cookie").forEach { raw ->
                    val pair = raw.substringBefore(';').trim()
                    val separator = pair.indexOf('=')
                    if (separator > 0) cookies[pair.substring(0, separator).trim()] = pair.substring(separator + 1).trim()
                }
                if (!response.isSuccessful) throw DirectImportException("game_http_${response.code}")
                val payload = response.body.bytes()
                if (payload.isEmpty()) throw DirectImportException("game_empty_response")
                val text = unprotect(payload)
                if (text.isBlank()) throw DirectImportException("game_empty_text_$api")
                if (BuildConfig.DEBUG) android.util.Log.d("ChimeHttp", "$api ${payload.size}B " + redact(text).take(200))
                return runCatching { json.parseToJsonElement(text).jsonObject }
                    .getOrElse { throw DirectImportException("game_bad_json_${api}_${text.length}") }
            }
        }

        private fun cookieHeader() = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    companion object {
        // Wahlap returns an empty body whenever the request carries Accept-Encoding
        // (any value, including "identity"), and OkHttp injects "Accept-Encoding: gzip"
        // on its own, so drop it just before the request hits the wire.
        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                val request = chain.request().newBuilder().removeHeader("Accept-Encoding").build()
                chain.proceed(request)
            }
            .build()

        private fun JsonObject.returnCode(): Int =
            this["returnCode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0

        internal fun redact(text: String): String =
            text.replace(Regex("\"token\"\\s*:\\s*\"[^\"]*\""), "\"token\":\"***\"")

        private val JSON = "application/json".toMediaType()
        internal val config: SgwcmaidConfig get() = SgwcmaidBuildConfig.current
        private val UTC_STAMP = DateTimeFormatter.ofPattern("yyMMddHHmmss").withZone(ZoneOffset.UTC)
        private fun md5Hex(value: String) = MessageDigest.getInstance("MD5").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        private fun sha256Hex(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02X".format(it) }
        private fun protect(value: String): ByteArray {
            val input = value.toByteArray(); val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true).apply { setInput(input); finish() }; val compressed = ByteArrayOutputStream().also { out -> val buffer = ByteArray(4096); while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer)) }.toByteArray(); deflater.end()
            val zlib = byteArrayOf(0x78.toByte(), 0x9c.toByte()) + compressed + adler32(input)
            return Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(config.aesKey.toByteArray(), "AES"), IvParameterSpec(config.aesIv.toByteArray())) }.doFinal(zlib)
        }
        private fun unprotect(value: ByteArray): String {
            val plain = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(config.aesKey.toByteArray(), "AES"), IvParameterSpec(config.aesIv.toByteArray())) }.doFinal(value)
            val inflater = Inflater(true).apply { setInput(plain, 2, plain.size - 6) }; val out = ByteArrayOutputStream(); val buffer = ByteArray(4096); while (!inflater.finished()) out.write(buffer, 0, inflater.inflate(buffer)) ; inflater.end(); return out.toString(StandardCharsets.UTF_8)
        }
        private fun adler32(input: ByteArray): ByteArray { var a = 1; var b = 0; input.forEach { a = (a + (it.toInt() and 255)) % 65521; b = (b + a) % 65521 }; val value = (b shl 16) or a; return byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()) }
    }
}
