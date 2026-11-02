package org.rhythmeta.maimaid.core.data

import java.util.zip.Adler32
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SgwcmaidPayloadCodecTest {
    private val aesKey = "0123456789abcdef"
    private val aesIv = "fedcba9876543210"

    private fun decrypt(payload: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey.encodeToByteArray(), "AES"), IvParameterSpec(aesIv.encodeToByteArray()))
            doFinal(payload)
        }

    private fun encrypt(plain: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey.encodeToByteArray(), "AES"), IvParameterSpec(aesIv.encodeToByteArray()))
            doFinal(plain)
        }

    @Test
    fun `round trips a utf8 json body`() {
        val body = """{"userId":123,"playerName":"プレイヤー","userMusicDetailList":[{"musicId":17,"achievement":1005000}]}"""

        assertEquals(body, SgwcmaidPayloadCodec.unprotect(SgwcmaidPayloadCodec.protect(body, aesKey, aesIv), aesKey, aesIv))
    }

    @Test
    fun `wraps the raw deflate body in a zlib container`() {
        val body = """{"api":"Ping"}"""
        val plain = decrypt(SgwcmaidPayloadCodec.protect(body, aesKey, aesIv))

        assertEquals(0x78, plain[0].toInt() and 0xFF)
        assertEquals(0x9c, plain[1].toInt() and 0xFF)
        val expectedAdler = Adler32().apply { update(body.encodeToByteArray()) }.value
        val trailer = plain.takeLast(4).joinToString("") { "%02x".format(it) }
        assertEquals("%08x".format(expectedAdler), trailer)
    }

    @Test
    fun `rejects a payload that is not a deflate stream`() {
        val error = assertThrows(DirectImportException::class.java) {
            SgwcmaidPayloadCodec.unprotect(encrypt(ByteArray(64) { 0x41 }), aesKey, aesIv)
        }

        assertTrue(error.code.startsWith("game_"))
    }

    @Test
    fun `rejects a payload shorter than the zlib container`() {
        val error = assertThrows(DirectImportException::class.java) {
            SgwcmaidPayloadCodec.unprotect(encrypt(ByteArray(3)), aesKey, aesIv)
        }

        assertEquals("game_short_payload", error.code)
    }
}
