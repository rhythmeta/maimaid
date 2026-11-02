package org.rhythmeta.maimaid.core.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.Deflater
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The on-the-wire body transformation used by the China cabinet (`Maimai2Servlet`) endpoints.
 *
 * Outbound bodies are deflated, wrapped in a zlib container (2-byte header, 4-byte Adler-32
 * trailer) and AES/CBC encrypted; inbound bodies run the same pipeline backwards. The cabinet
 * service rejects anything else, so the byte layout here must not change.
 *
 * Only `java.io` stream APIs are used for the textual decode. `ByteArrayOutputStream
 * .toString(Charset)` (and the other JDK 10+/11+ members of `java.io`/`java.lang`) only exist
 * from Android API 33, so calling them on the API 29 minimum throws `NoSuchMethodError` — an
 * `Error` that escapes ordinary exception handling and kills the process.
 */
internal object SgwcmaidPayloadCodec {
    /** Size of the zlib stream header [protect] prepends (`0x78 0x9c`). */
    private const val ZLIB_HEADER_SIZE = 2

    /** Size of the Adler-32 trailer [protect] appends. */
    private const val ZLIB_TRAILER_SIZE = 4

    /** Chunk size used while deflating an outbound body. */
    private const val DEFLATE_BUFFER_SIZE = 4096

    /** Minimum plaintext size that can still hold a zlib header and trailer. */
    private const val MIN_ZLIB_SIZE = ZLIB_HEADER_SIZE + ZLIB_TRAILER_SIZE

    /** Encrypts [value] into a cabinet request body. */
    fun protect(value: String, aesKey: String, aesIv: String): ByteArray {
        val input = value.encodeToByteArray()
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        val compressed = try {
            deflater.setInput(input)
            deflater.finish()
            ByteArrayOutputStream().also { output ->
                val buffer = ByteArray(DEFLATE_BUFFER_SIZE)
                while (!deflater.finished()) output.write(buffer, 0, deflater.deflate(buffer))
            }.toByteArray()
        } finally {
            deflater.end()
        }
        val zlib = byteArrayOf(0x78.toByte(), 0x9c.toByte()) + compressed + adler32(input)
        return cipher(Cipher.ENCRYPT_MODE, aesKey, aesIv).doFinal(zlib)
    }

    /**
     * Decrypts and decompresses a cabinet response body.
     *
     * @throws DirectImportException when the payload is too short or is not a valid deflate
     * stream, so callers keep reporting a normal import failure instead of crashing.
     */
    fun unprotect(value: ByteArray, aesKey: String, aesIv: String): String {
        val plain = cipher(Cipher.DECRYPT_MODE, aesKey, aesIv).doFinal(value)
        if (plain.size <= MIN_ZLIB_SIZE) throw DirectImportException("game_short_payload")
        // The inflater runs in nowrap mode over the deflate body that sits between the zlib
        // header and the Adler-32 trailer.
        val deflate = Inflater(true)
        val body = ByteArrayInputStream(plain, ZLIB_HEADER_SIZE, plain.size - MIN_ZLIB_SIZE)
        return try {
            InflaterInputStream(body, deflate, DEFLATE_BUFFER_SIZE).use { input -> input.readBytes().decodeToString() }
        } catch (error: IOException) {
            throw DirectImportException("game_bad_payload")
        } finally {
            deflate.end()
        }
    }

    private fun cipher(mode: Int, aesKey: String, aesIv: String): Cipher =
        Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(mode, SecretKeySpec(aesKey.encodeToByteArray(), "AES"), IvParameterSpec(aesIv.encodeToByteArray()))
        }

    private fun adler32(input: ByteArray): ByteArray {
        var a = 1
        var b = 0
        input.forEach {
            a = (a + (it.toInt() and 255)) % 65521
            b = (b + a) % 65521
        }
        val value = (b shl 16) or a
        return byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
    }
}
