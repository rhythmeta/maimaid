package org.rhythmeta.maimaid.core.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-scoped Otogame sign-in state shared by the import and sign-in screens.
 *
 * The bearer token is captured from the sign-in WebView and is intentionally kept in memory only,
 * mirroring the iOS implementation.
 */
class OtogameSessionStore {
    private val mutableAuthorizationHeader = MutableStateFlow<String?>(null)

    val authorizationHeader: StateFlow<String?> = mutableAuthorizationHeader.asStateFlow()

    /**
     * Records a captured `Authorization` header.
     *
     * @return `true` when a new session replaced the previous one, `false` when [value] is not a
     * usable bearer header or when it matches the already captured session.
     */
    fun capture(value: String): Boolean {
        val header = value.trim()
        if (!BEARER_HEADER.matches(header)) return false
        while (true) {
            val current = mutableAuthorizationHeader.value
            if (current == header) return false
            if (mutableAuthorizationHeader.compareAndSet(current, header)) return true
        }
    }

    fun clear() {
        mutableAuthorizationHeader.value = null
    }

    private companion object {
        val BEARER_HEADER = Regex("""^Bearer\s+\S+$""", RegexOption.IGNORE_CASE)
    }
}
