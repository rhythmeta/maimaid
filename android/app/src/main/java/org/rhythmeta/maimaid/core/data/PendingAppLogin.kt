package org.rhythmeta.maimaid.core.data

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.Serializable

@Serializable
data class PendingAppLogin(val state: String, val verifier: String, val createdAt: Long) {
    val challenge: String get() = encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    fun accepts(incomingState: String?, now: Long = System.currentTimeMillis()): Boolean =
        incomingState != null && now >= createdAt && now - createdAt < 30 * 60_000 &&
            MessageDigest.isEqual(state.toByteArray(Charsets.US_ASCII), incomingState.toByteArray(Charsets.US_ASCII))

    companion object {
        private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        fun create(): PendingAppLogin {
            val random = SecureRandom()
            fun token() = encode(ByteArray(32).also(random::nextBytes))
            return PendingAppLogin(token(), token(), System.currentTimeMillis())
        }
    }
}
