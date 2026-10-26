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
 * The China-server player a profile was last imported from.
 *
 * Only the display name is ever surfaced in the UI; [userId] stays private to the import
 * pipeline and is persisted encrypted.
 */
@Serializable
data class SgwcmaidBinding(
    val userId: Long,
    val playerName: String,
)

/**
 * Remembers the SGWCMAID player of each local profile so the scores can be refreshed
 * without scanning another cabinet QR code.
 *
 * The payload is AES/GCM encrypted with a non-exportable Android Keystore key, matching
 * [ProfileCredentialStore] and [BackendTokenStore]. Losing the key (for example after a
 * restore to another device) simply yields no binding, which falls back to a QR import.
 */
class SgwcmaidBindingStore(
    context: Context,
    private val json: Json,
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "sgwcmaid_bindings",
        Context.MODE_PRIVATE,
    )

    fun load(profileId: String): SgwcmaidBinding? {
        val payload = decrypt(preferences.getString(key(profileId), null)) ?: return null
        return runCatching { json.decodeFromString<SgwcmaidBinding>(payload) }.getOrNull()
            ?.takeIf { it.userId > 0 }
    }

    @Synchronized
    fun save(profileId: String, binding: SgwcmaidBinding) {
        if (binding.userId <= 0) return
        val payload = encrypt(json.encodeToString(SgwcmaidBinding.serializer(), binding))
        check(payload != null && preferences.edit().putString(key(profileId), payload).commit()) {
            "Could not save the SGWCMAID binding"
        }
    }

    @Synchronized
    fun clear(profileId: String) {
        preferences.edit().remove(key(profileId)).apply()
    }

    private fun key(profileId: String) = "$profileId.binding"

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
        const val KEY_ALIAS = "maimaid.sgwcmaid.binding"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
