package org.rhythmeta.maimaid.core.data

/**
 * The ordered cabinet candidates for one import. `preferred` holds the selected
 * region in latency order; `fallback` holds every other region. Both lists are
 * rotated by the persisted cursor so consecutive imports spread across cabinets
 * instead of always hitting the fastest one.
 */
internal data class SgwcmaidKeychipPlan(
    val preferred: List<SgwcmaidKeychip>,
    val fallback: List<SgwcmaidKeychip>,
) {
    val isEmpty: Boolean get() = preferred.isEmpty() && fallback.isEmpty()

    fun attempts(
        preferredLimit: Int = SgwcmaidKeychipSelector.PREFERRED_ATTEMPT_LIMIT,
        fallbackLimit: Int = SgwcmaidKeychipSelector.FALLBACK_ATTEMPT_LIMIT,
    ): List<SgwcmaidKeychip> =
        preferred.take(preferredLimit.coerceAtLeast(0)) +
            fallback.take(fallbackLimit.coerceAtLeast(0))
}

/** Pure selection policy shared by the import client and its tests. */
internal object SgwcmaidKeychipSelector {
    /**
     * A province can contain hundreds of cabinets, so cap the retries. The
     * fallback budget is separate to guarantee a nationwide attempt even when
     * every cabinet in the selected province fails.
     */
    const val PREFERRED_ATTEMPT_LIMIT = 4
    const val FALLBACK_ATTEMPT_LIMIT = 4

    fun plan(
        catalog: SgwcmaidKeychipCatalog,
        provinceId: Int?,
        cursor: Int,
    ): SgwcmaidKeychipPlan {
        val preferred = provinceId?.let(catalog::keychipsInProvince).orEmpty()
        val fallback = if (provinceId != null) {
            catalog.keychips.filter { it.provinceId != provinceId }
        } else {
            catalog.keychips
        }
        return SgwcmaidKeychipPlan(
            preferred = rotatedByLatency(preferred, cursor),
            fallback = rotatedByLatency(fallback, cursor),
        )
    }

    /**
     * Sorts the pool by measured latency (then keychip for stability) and
     * rotates it by the cursor, giving round-robin load balancing inside each
     * latency bucket.
     */
    private fun rotatedByLatency(
        keychips: List<SgwcmaidKeychip>,
        cursor: Int,
    ): List<SgwcmaidKeychip> {
        if (keychips.isEmpty()) return emptyList()
        val sorted = keychips.sortedWith(compareBy({ it.latencyMs }, { it.keychip }))
        val offset = ((cursor % sorted.size) + sorted.size) % sorted.size
        return sorted.drop(offset) + sorted.take(offset)
    }
}

/**
 * Decides whether a failed chime exchange is worth retrying with the next
 * cabinet. A code that is invalid or already used cannot be rescued by another
 * keychip; transport, HTTP, and chime database errors can be cabinet-specific.
 */
internal object SgwcmaidKeychipFailure {
    fun isRetryable(code: String): Boolean = when (code) {
        "invalid_sgwcmaid", "sgwcmaid_not_configured",
        "chime_code_invalid_or_expired", "chime_code_already_used",
        "chime_keychip_missing" -> false
        else -> code.startsWith("chime_")
    }
}
