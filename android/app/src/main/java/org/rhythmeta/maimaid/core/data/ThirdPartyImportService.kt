package org.rhythmeta.maimaid.core.data

import androidx.room.withTransaction
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.rhythmeta.maimaid.core.database.MaimaidDatabase
import org.rhythmeta.maimaid.core.database.PlayRecordEntity

class DirectImportException(val code: String) : Exception(code)
data class LocalScoreImportResult(val fetchedCount: Int, val updatedCount: Int, val skippedCount: Int)

class ThirdPartyImportService(
    private val database: MaimaidDatabase,
    private val credentials: ProfileCredentialStore,
    private val json: Json,
) {
    private val tokenMutex = Mutex()

    data class LxnsAuthorization(val url: String, val verifier: String)
    data class DeviceAuthorization(val code: String, val userCode: String, val url: String, val expiresIn: Long, val interval: Long)

    fun createLxnsAuthorization(): LxnsAuthorization {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val verifier = encoder.encodeToString(ByteArray(64).also(SecureRandom()::nextBytes))
        val challenge = encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val params = mapOf("response_type" to "code", "client_id" to LXNS_CLIENT_ID,
            "redirect_uri" to REDIRECT_URI, "scope" to "read_player write_player",
            "state" to UUID.randomUUID().toString(), "code_challenge" to challenge, "code_challenge_method" to "S256")
        return LxnsAuthorization("https://maimai.lxns.net/oauth/authorize?${form(params)}", verifier)
    }

    suspend fun startDivingFish(): DeviceAuthorization {
        val response = request("https://auth.diving-fish.com/oauth/device_authorization",
            mapOf("client_id" to DF_CLIENT_ID, "scope" to "prober.records.read"))
        return DeviceAuthorization(response.required("device_code"), response.required("user_code"),
            response["verification_uri_complete"]?.jsonPrimitive?.contentOrNull ?: response.required("verification_uri"),
            response["expires_in"]?.jsonPrimitive?.longOrNull ?: 600,
            (response["interval"]?.jsonPrimitive?.longOrNull ?: 5).coerceAtLeast(5))
    }

    suspend fun finishDivingFish(device: DeviceAuthorization, profileId: String) {
        val deadline = android.os.SystemClock.elapsedRealtime() + device.expiresIn * 1000
        var interval = device.interval
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            delay(interval * 1000)
            try {
                // A consumed device code cannot be reused; persist its token even if the screen closes.
                withContext(NonCancellable) {
                    val response = request(DF_TOKEN_URL, mapOf("client_id" to DF_CLIENT_ID,
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "device_code" to device.code))
                    saveToken(ScoreImportProvider.DivingFish, profileId, tokenPair(response).second)
                }
                return
            } catch (error: DirectImportException) {
                when (error.code) {
                    "authorization_pending" -> Unit
                    "slow_down" -> interval += 5
                    else -> throw error
                }
            }
        }
        throw DirectImportException("expired_token")
    }

    suspend fun exchangeLxns(code: String, verifier: String, profileId: String) {
        require(verifier.isNotBlank())
        withContext(NonCancellable) {
            val response = request(LXNS_TOKEN_URL, mapOf("client_id" to LXNS_CLIENT_ID,
                "grant_type" to "authorization_code", "code" to code.trim(), "code_verifier" to verifier,
                "redirect_uri" to REDIRECT_URI))
            saveToken(ScoreImportProvider.Lxns, profileId, tokenPair(response).second)
        }
    }

    suspend fun accessToken(provider: ScoreImportProvider, profileId: String): String = tokenMutex.withLock {
        withContext(NonCancellable) {
            val previous = refreshToken(provider, profileId)
            if (previous.isBlank()) throw DirectImportException("invalid_grant")
            try {
                val response = request(if (provider == ScoreImportProvider.Lxns) LXNS_TOKEN_URL else DF_TOKEN_URL,
                    mapOf("client_id" to if (provider == ScoreImportProvider.Lxns) LXNS_CLIENT_ID else DF_CLIENT_ID,
                        "grant_type" to "refresh_token", "refresh_token" to previous))
                val (access, refresh) = tokenPair(response)
                if (refreshToken(provider, profileId) != previous) throw CancellationException()
                saveToken(provider, profileId, refresh)
                access
            } catch (error: DirectImportException) {
                if (error.code == "invalid_grant" && refreshToken(provider, profileId) == previous) {
                    saveToken(provider, profileId, "")
                }
                throw error
            }
        }
    }

    fun refreshToken(provider: ScoreImportProvider, profileId: String): String = credentials.credentials(profileId).let {
        if (provider == ScoreImportProvider.Lxns) it.lxnsToken else it.divingFishToken
    }

    private fun saveToken(provider: ScoreImportProvider, profileId: String, token: String) {
        credentials.update(profileId) { current ->
            if (provider == ScoreImportProvider.Lxns) current.copy(lxnsToken = token)
            else current.copy(divingFishToken = token)
        }
    }

    fun disconnect(provider: ScoreImportProvider, profileId: String) = saveToken(provider, profileId, "")

    suspend fun importScores(provider: ScoreImportProvider, profileId: String): LocalScoreImportResult {
        val access = accessToken(provider, profileId)
        currentCoroutineContext().ensureActive()
        val address = if (provider == ScoreImportProvider.Lxns) "https://maimai.lxns.net/api/v0/user/maimai/player/scores"
            else "https://www.diving-fish.com/api/maimaidxprober/player/records"
        var payload = ThirdPartyImportPolicy.decode(request(address, token = access), provider)
        if (provider == ScoreImportProvider.Lxns) {
            try {
                val response = request("https://maimai.lxns.net/api/v0/user/maimai/player", token = access)
                val player = response["data"] as? JsonObject
                if (response["success"]?.jsonPrimitive?.booleanOrNull == true && player != null) {
                    payload = payload.copy(name = player["name"]?.jsonPrimitive?.contentOrNull,
                        rating = player["rating"]?.jsonPrimitive?.intOrNull,
                        plate = (player["trophy"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull)
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* Player metadata is optional; scores remain importable. */ }
        }
        currentCoroutineContext().ensureActive()
        return apply(payload, provider, profileId)
    }

    internal suspend fun apply(payload: ScoreImportPayload, provider: ScoreImportProvider, profileId: String): LocalScoreImportResult =
        database.withTransaction {
            val profile = database.profileDao().activeProfile()
            if (profile?.id != profileId) throw DirectImportException("profile_changed")
            val sheets = database.catalogDao().sheets()
            check(sheets.isNotEmpty()) { "catalog_empty" }
            val songs = database.catalogDao().songs().associateBy { it.songIdentifier }
            val matcher = ScoreImportSheetMatcher(sheets, songs)
            val dao = database.scoreDao()
            val histories = dao.playRecords(profileId)
            val ids = histories.mapTo(mutableSetOf()) { it.id }
            val fingerprints = histories.mapTo(mutableSetOf()) {
                historyKey(it.sheetKey, it.playedAt, ScoreInput(it.achievement, it.dxScore, it.fc, it.fs))
            }
            var updated = 0
            var matched = 0
            payload.scores.forEach { item ->
                val sheet = matcher.match(item) ?: return@forEach
                if (ScoreRules.validate(item.input, ScoreRules.effectiveMaxDxScore(sheet.total)) != null) return@forEach
                matched++
                val previous = dao.score(profileId, sheet.sheetKey)
                val merged = ScoreRules.mergeScore(profileId, sheet.sheetKey, previous, item.input,
                    item.playedAt ?: System.currentTimeMillis())
                var changed = previous != merged
                if (changed) dao.upsertScore(merged)
                val recordId = item.recordId(profileId)
                if (provider == ScoreImportProvider.Lxns && recordId != null && item.playedAt != null && ids.add(recordId)) {
                    if (fingerprints.add(historyKey(sheet.sheetKey, item.playedAt, item.input))) {
                        val record = PlayRecordEntity(recordId, profileId, sheet.sheetKey, item.input.achievement,
                            ScoreRules.calculateRank(item.input.achievement), item.input.dxScore, item.input.fc, item.input.fs, item.playedAt)
                        dao.upsertPlayRecord(record)
                        changed = true
                    }
                }
                if (changed) updated++
            }
            database.profileDao().upsert(profile.copy(
                name = payload.name?.takeIf { it.isNotBlank() } ?: profile.name,
                playerRating = payload.rating ?: profile.playerRating, plate = payload.plate ?: profile.plate,
                lastImportDateDf = if (provider == ScoreImportProvider.DivingFish) System.currentTimeMillis() else profile.lastImportDateDf,
                lastImportDateLxns = if (provider == ScoreImportProvider.Lxns) System.currentTimeMillis() else profile.lastImportDateLxns))
            LocalScoreImportResult(payload.fetchedCount, updated, payload.fetchedCount - matched)
        }

    private fun historyKey(sheet: String, date: Long, input: ScoreInput): String = listOf(sheet, date,
        kotlin.math.round(input.achievement * 10000).toLong(), input.dxScore,
        ScoreRules.canonicalFc(input.fc).orEmpty(), ScoreRules.canonicalFs(input.fs).orEmpty()).joinToString("|")

    internal fun tokenPair(response: JsonObject): Pair<String, String> {
        val payload = if (response.containsKey("access_token")) response else response["data"] as? JsonObject
            ?: throw DirectImportException("invalid_token_response")
        return payload.required("access_token") to payload.required("refresh_token")
    }

    private suspend fun request(address: String, values: Map<String, String>? = null, token: String? = null): JsonObject =
        withContext(Dispatchers.IO) {
            val connection = URL(address).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 30000; connection.readTimeout = 30000; connection.useCaches = false
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                if (values != null) {
                    connection.requestMethod = "POST"; connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(form(values)) }
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val payload = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                if (status !in 200..299) throw DirectImportException(payload?.get("error")?.jsonPrimitive?.contentOrNull
                    ?: if (status == 401) "unauthorized" else "HTTP $status")
                payload ?: throw DirectImportException("invalid_response")
            } finally { connection.disconnect() }
        }

    private fun form(values: Map<String, String>) = values.entries.joinToString("&") {
        "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
    }
    private fun JsonObject.required(key: String): String = this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: throw DirectImportException("invalid_response")

    companion object {
        const val DF_CLIENT_ID = "5b79b87f22855b80ee35243eeec07916"
        const val LXNS_CLIENT_ID = "cfb7ef40-bc0f-4e3a-8258-9e5f52cd7338"
        const val REDIRECT_URI = "urn:ietf:wg:oauth:2.0:oob"
        const val DF_TOKEN_URL = "https://auth.diving-fish.com/oauth/token"
        const val LXNS_TOKEN_URL = "https://maimai.lxns.net/api/v0/oauth/token"
    }
}
