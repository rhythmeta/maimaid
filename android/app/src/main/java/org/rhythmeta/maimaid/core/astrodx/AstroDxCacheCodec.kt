package org.rhythmeta.maimaid.core.astrodx

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Known cache v3 (encrypted) and v4 (raw Deflate); never executes cache content. */
object AstroDxCacheCodec {
    const val MAX_INPUT_BYTES = 16 * 1024 * 1024
    private const val MAX_JSON_BYTES = 64 * 1024 * 1024
    // Public format constant, not an account credential. Verified with a real v3 cache.
    private const val KEY = "94625184621352165487465213218452"

    fun decode(input: ByteArray): JsonObject {
        require(input.isNotEmpty() && input.size <= MAX_INPUT_BYTES) { "cache_too_large" }
        val text = input.toString(Charsets.UTF_8).removePrefix("\uFEFF").trim()
        val bytes = when {
            text.startsWith("{") -> text.toByteArray()
            text.all { it.isWhitespace() || it in BASE64_CHARS } -> {
                val encrypted = Base64.getDecoder().decode(text.filterNot(Char::isWhitespace))
                require(encrypted.isNotEmpty() && encrypted.size % 16 == 0) { "cache_invalid" }
                Cipher.getInstance("AES/ECB/PKCS5Padding").run {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY.toByteArray(Charsets.UTF_8), "AES"))
                    doFinal(encrypted)
                }
            }
            input.size >= 2 && input[0] == 0x1f.toByte() && input[1] == 0x8b.toByte() ->
                GZIPInputStream(ByteArrayInputStream(input)).use { readLimited(it, MAX_JSON_BYTES) }
            else -> {
                val hasZlibHeader = input.size >= 2 && (input[0].toInt() and 15) == 8 &&
                    (((input[0].toInt() and 255) * 256 + (input[1].toInt() and 255)) % 31 == 0)
                val inflater = Inflater(!hasZlibHeader)
                try {
                    InflaterInputStream(ByteArrayInputStream(input), inflater).use { readLimited(it, MAX_JSON_BYTES) }
                } finally { inflater.end() }
            }
        }
        val root = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
            ?: error("cache_invalid")
        require(root["version"]?.jsonPrimitive?.intOrNull in setOf(3, 4)) { "cache_version" }
        require(root["level_metadata"] is JsonObject) { "cache_invalid" }
        return root
    }

    fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= limit) { "cache_too_large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private const val BASE64_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
}
