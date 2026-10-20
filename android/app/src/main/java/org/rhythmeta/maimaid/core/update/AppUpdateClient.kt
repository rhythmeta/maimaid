package org.rhythmeta.maimaid.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class AppUpdateClient(private val updateUrl: String, private val json: Json) : AppUpdateRemoteSource {
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    override suspend fun fetchManifest(): AppUpdateManifest {
        val body = get(updateUrl)
        return try { json.decodeFromString(body) }
        catch (_: SerializationException) { throw AppUpdateException(AppUpdateFailure.InvalidManifest) }
    }
    override suspend fun fetchHistory(url: String): AppUpdateHistory = json.decodeFromString(get(url))

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Cache-Control", "no-cache")
            .header("User-Agent", "maimaid-android").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw AppUpdateException(AppUpdateFailure.Network)
            response.body.string()
        }
    }
}
