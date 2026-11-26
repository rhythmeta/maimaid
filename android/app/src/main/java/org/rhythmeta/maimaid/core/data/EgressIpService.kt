package org.rhythmeta.maimaid.core.data

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** The exit address the import actually uses, plus what the provider reports about it. */
data class EgressIp(
    val address: String,
    /** ISO-3166 alpha-2, when the provider reported one. */
    val countryCode: String?,
    /** Carrier or organisation behind the address, when known. */
    val isp: String? = null,
    val kind: EgressKind = EgressKind.Unknown,
)

/**
 * How the address looks, not whether the title server accepts it: a Cogent-routed VPS has
 * been observed to pass, so this is shown as a hint rather than a verdict.
 */
enum class EgressKind { Residential, Hosting, Unknown }

/**
 * Reads the public exit address, so the import screen can show which IP — and therefore
 * which country — the title server will see.
 *
 * The international endpoints are region filtered, so this is the first thing to check when
 * an import fails: a Chinese or European exit is rejected even with correct credentials.
 * Providers are tried in order and the result is cached briefly, because switching the VPN
 * is the only reason to look at it twice in a row.
 */
class EgressIpService(
    private val json: Json,
    private val http: OkHttpClient = defaultClient(),
) {
    private var cached: EgressIp? = null
    private var cachedAt = 0L

    suspend fun resolve(force: Boolean = false): EgressIp? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force) {
            cached?.let { if (now - cachedAt < CACHE_MS) return@withContext it }
        }
        // Providers that report the carrier come first: the quality line is the point of
        // this call, and the Cloudflare trace only knows the address and country.
        val result = fetchGeoJson(IPWHOIS_URL) ?: fetchGeoJson(IP_SB_URL) ?: fetchCloudflareTrace()
        if (result != null) {
            cached = result
            cachedAt = now
        }
        result
    }

    /** `https://www.cloudflare.com/cdn-cgi/trace` is a tiny `key=value` text document. */
    private fun fetchCloudflareTrace(): EgressIp? = try {
        val text = get(CLOUDFLARE_TRACE_URL) ?: return null
        parseTrace(text)
    } catch (error: Exception) {
        null
    }

    private fun fetchGeoJson(url: String): EgressIp? = try {
        val text = get(url) ?: return null
        parseGeoJson(text, json)
    } catch (error: Exception) {
        null
    }

    private fun get(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/plain")
            .header("User-Agent", "maimaid-android")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body.string().takeIf { it.isNotBlank() }
        }
    }

    internal companion object {
        const val CLOUDFLARE_TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"
        const val IPWHOIS_URL = "https://ipwho.is/"
        const val IP_SB_URL = "https://api.ip.sb/geoip"

        /** Organisation names that identify a datacenter rather than a consumer line. */
        private val HOSTING_HINTS = listOf(
            "hosting", "server", "vps", "rdp", "datacenter", "data center", "colo", "cloud",
            "amazon", "aws", "google", "microsoft", "azure", "oracle", "digitalocean",
            "hetzner", "ovh", "vultr", "linode", "leaseweb", "contabo", "m247", "choopa",
            "quadranet", "psychz", "colocrossing", "akamai", "kamatera", "hostwinds",
        )

        /** Consumer and mobile carriers, matched after the hosting list. */
        private val RESIDENTIAL_HINTS = listOf(
            "telecom", "mobile", "cellular", "wireless", "broadband", "cable", "fiber",
            // "communications" is deliberately absent: transit carriers such as Cogent use it.
            "fibre", "comcast", "verizon", "at&t", "t-mobile", "charter",
            "cox", "centurylink", "kddi", "ntt", "softbank", "sk broadband", "kt corp",
            "lg u+", "singtel", "starhub", "hkt", "pccw", "chunghwa", "unicom",
        )

        internal fun classify(isp: String?): EgressKind {
            val text = isp?.lowercase()?.trim().orEmpty()
            if (text.isEmpty()) return EgressKind.Unknown
            if (HOSTING_HINTS.any { text.contains(it) }) return EgressKind.Hosting
            if (RESIDENTIAL_HINTS.any { text.contains(it) }) return EgressKind.Residential
            return EgressKind.Unknown
        }
        private const val CACHE_MS = 60_000L

        /** Parses `ip=…` / `loc=…` out of the Cloudflare trace document. */
        internal fun parseTrace(text: String): EgressIp? {
            val fields = text.lineSequence()
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) null
                    else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
                }
                .toMap()
            val address = fields["ip"]?.takeIf { it.isNotBlank() } ?: return null
            val country = fields["loc"]?.takeIf { it.length == 2 }?.uppercase()
            return EgressIp(address, country)
        }

        /** Parses the `{ "ip": "…", "country_code": "SG" }` shape both JSON providers use. */
        internal fun parseGeoJson(text: String, json: Json): EgressIp? {
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            val address = root["ip"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
            val country = root["country_code"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.length == 2 }?.uppercase()
            // ipwho.is nests the carrier under `connection`; api.ip.sb flattens it and also
            // exposes the organisation, which is the stronger datacenter signal.
            val connection = root["connection"] as? kotlinx.serialization.json.JsonObject
            val isp = sequenceOf(
                connection?.get("isp")?.jsonPrimitive?.contentOrNull,
                root["organization"]?.jsonPrimitive?.contentOrNull,
                root["isp"]?.jsonPrimitive?.contentOrNull,
            ).firstOrNull { !it.isNullOrBlank() }?.trim()
            return EgressIp(address, country, isp, classify(isp))
        }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }
}
