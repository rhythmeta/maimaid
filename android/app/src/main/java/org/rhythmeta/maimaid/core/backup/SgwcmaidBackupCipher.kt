package org.rhythmeta.maimaid.core.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import org.rhythmeta.maimaid.core.data.SgwcmaidBinding

/**
 * Encrypts the SGWCMAID binding before it enters a cloud backup.
 *
 * The snapshot is stored on the backend, so the player id is never written in the clear.
 * Unlike the local binding (Android Keystore, device-bound), the backup must be readable
 * after a reinstall or on another device, so the key is derived from the signed-in backend
 * account instead: only the same account can decrypt its own snapshot. This keeps the id
 * opaque inside the stored object; it is not a defence against the account owner or the
 * backend that already knows that account id.
 *
 * Layout: `version(1) | iv(12) | AES-256-GCM ciphertext + tag(16)`.
 * The derivation must stay byte-identical to the iOS `SgwcmaidBackupCipher`.
 */
object SgwcmaidBackupCipher {
    private const val VERSION = 1
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BYTES = 32
    private const val SALT = "maimaid.backup.sgwcmaid.v1"
    private const val INFO = "aes-256-gcm"

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun encrypt(binding: SgwcmaidBinding, accountId: String): ByteArray {
        val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(accountId), GCMParameterSpec(TAG_BITS, iv))
        val plain = json.encodeToString(SgwcmaidBinding.serializer(), binding).encodeToByteArray()
        return byteArrayOf(VERSION.toByte()) + iv + cipher.doFinal(plain)
    }

    /** Returns null for a foreign account, an older format or a corrupted blob. */
    fun decrypt(blob: ByteArray, accountId: String): SgwcmaidBinding? {
        if (blob.size <= 1 + IV_BYTES) return null
        if (blob.first().toInt() != VERSION) return null
        return runCatching {
            val iv = blob.copyOfRange(1, 1 + IV_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(accountId), GCMParameterSpec(TAG_BITS, iv))
            val plain = cipher.doFinal(blob.copyOfRange(1 + IV_BYTES, blob.size))
            json.decodeFromString(SgwcmaidBinding.serializer(), plain.decodeToString())
                .takeIf { it.userId > 0 }
        }.getOrNull()
    }

    private fun key(accountId: String): SecretKeySpec =
        SecretKeySpec(hkdf(accountId.encodeToByteArray(), SALT.encodeToByteArray(), INFO.encodeToByteArray(), KEY_BYTES), "AES")

    /** RFC 5869 HKDF with HMAC-SHA256, kept explicit so both platforms agree on every byte. */
    internal fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val take = minOf(previous.size, length - offset)
            previous.copyInto(output, offset, 0, take)
            offset += take
            counter += 1
        }
        return output
    }
}
