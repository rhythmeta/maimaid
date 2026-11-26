package org.rhythmeta.maimaid.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The card credential a profile last imported with.
 *
 * The international card number is fixed, so remembering it turns the next import into one
 * tap. It is a card credential, not a session token, so the payload is AES/GCM encrypted
 * with a non-exportable Android Keystore key and never leaves the device.
 */
@Serializable
data class SdgaSavedCredential(
    val card: String,
    val extra: String = "",
    val playerName: String = "",
) {
    /** Display form that keeps the head and tail only. */
    val masked: String
        get() = if (card.length <= 8) card else card.take(4) + "…" + card.takeLast(4)
}

class SdgaCredentialStore(
    context: Context,
    private val json: Json,
) {
    private val preferences = context.applicationContext
        .getSharedPreferences("sdga_credentials", Context.MODE_PRIVATE)

    fun load(profileId: String): SdgaSavedCredential? {
        val payload = decrypt(preferences.getString(key(profileId), null)) ?: return null
        return runCatching { json.decodeFromString<SdgaSavedCredential>(payload) }
            .getOrNull()
            ?.takeIf { it.card.isNotBlank() }
    }

    @Synchronized
    fun save(profileId: String, credential: SdgaSavedCredential) {
        if (credential.card.isBlank()) return
        val payload = encrypt(json.encodeToString(SdgaSavedCredential.serializer(), credential))
            ?: return
        preferences.edit().putString(key(profileId), payload).apply()
    }

    fun clear(profileId: String) {
        preferences.edit().remove(key(profileId)).apply()
    }

    private fun key(profileId: String) = "$profileId.credential"

    private fun encrypt(value: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }.getOrNull()

    private fun decrypt(payload: String?): String? {
        if (payload.isNullOrBlank()) return null
        return runCatching {
            val (encodedIv, encodedValue) = payload.split(':', limit = 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, Base64.decode(encodedIv, Base64.NO_WRAP)),
            )
            cipher.doFinal(Base64.decode(encodedValue, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "maimaid.sdga.credential"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
