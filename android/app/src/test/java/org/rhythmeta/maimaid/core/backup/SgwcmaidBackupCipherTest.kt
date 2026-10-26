package org.rhythmeta.maimaid.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rhythmeta.maimaid.core.data.SgwcmaidBinding

class SgwcmaidBackupCipherTest {
    private val binding = SgwcmaidBinding(userId = 10834470, playerName = "Ｋｅｒｉｔｉａｌ")

    @Test
    fun `round trip restores the binding`() {
        val blob = SgwcmaidBackupCipher.encrypt(binding, "account-one")
        assertEquals(binding, SgwcmaidBackupCipher.decrypt(blob, "account-one"))
    }

    @Test
    fun `another account cannot decrypt the blob`() {
        val blob = SgwcmaidBackupCipher.encrypt(binding, "account-one")
        assertNull(SgwcmaidBackupCipher.decrypt(blob, "account-two"))
    }

    @Test
    fun `tampered or truncated blobs are rejected`() {
        val blob = SgwcmaidBackupCipher.encrypt(binding, "account-one")
        val tampered = blob.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertNull(SgwcmaidBackupCipher.decrypt(tampered, "account-one"))
        assertNull(SgwcmaidBackupCipher.decrypt(blob.copyOf(8), "account-one"))
        assertNull(SgwcmaidBackupCipher.decrypt(byteArrayOf(9) + blob.copyOfRange(1, blob.size), "account-one"))
    }

    @Test
    fun `binding blobs are never written in the clear`() {
        val blob = SgwcmaidBackupCipher.encrypt(binding, "account-one")
        val text = blob.toString(Charsets.ISO_8859_1)
        assertTrue(!text.contains("10834470") && !text.contains("playerName"))
    }

    /** RFC 5869, appendix A.1: both platforms must derive the same key material. */
    @Test
    fun `hkdf matches rfc 5869 test case 1`() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c)
        val info = byteArrayOf(0xf0.toByte(), 0xf1.toByte(), 0xf2.toByte(), 0xf3.toByte(), 0xf4.toByte(),
            0xf5.toByte(), 0xf6.toByte(), 0xf7.toByte(), 0xf8.toByte(), 0xf9.toByte())
        val expected = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
            "34007208d5b887185865"
        val actual = SgwcmaidBackupCipher.hkdf(ikm, salt, info, 42).toHexString()
        assertEquals(expected, actual)
    }

    private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }
}
