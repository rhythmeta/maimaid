package org.rhythmeta.maimaid.core.astrodx

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AstroDxCacheCodecTest {
    @Test
    fun decodesPlainAndRawDeflateV4() {
        val json = "{\"version\":4,\"level_metadata\":{}}"
        val compressed = ByteArrayOutputStream().also { output ->
            DeflaterOutputStream(output, Deflater(Deflater.DEFAULT_COMPRESSION, true)).use {
                it.write(json.toByteArray())
            }
        }.toByteArray()
        assertEquals(4, AstroDxCacheCodec.decode(json.toByteArray())["version"]?.toString()?.toInt())
        assertEquals(4, AstroDxCacheCodec.decode(compressed)["version"]?.toString()?.toInt())
    }

    @Test
    fun decodesEncryptedV3AndRejectsUnknownVersion() {
        val json = "{\"version\":3,\"level_metadata\":{}}"
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec("94625184621352165487465213218452".toByteArray(), "AES"))
        val encoded = Base64.getEncoder().encode(cipher.doFinal(json.toByteArray()))
        assertEquals(3, AstroDxCacheCodec.decode(encoded)["version"]?.toString()?.toInt())
        val unknown = "{\"version\":2,\"level_metadata\":{}}".toByteArray()
        assertThrows(IllegalArgumentException::class.java) { AstroDxCacheCodec.decode(unknown) }
    }
}

