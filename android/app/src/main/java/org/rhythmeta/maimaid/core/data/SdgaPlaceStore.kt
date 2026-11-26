package org.rhythmeta.maimaid.core.data

import android.content.Context

/**
 * Persists the manually selected import region/arcade and the load-balancing cursor.
 *
 * The selection is local-only and is never derived from the device location. The country
 * and arcade must match the keychip pool, so a stale selection simply falls back to the
 * whole catalog.
 */
class SdgaPlaceStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("sdga_places", Context.MODE_PRIVATE)

    /** `null` means automatic: use the whole catalog. */
    fun country(): String? = preferences.getString(KEY_COUNTRY, null)?.takeIf { it.isNotBlank() }

    fun setCountry(country: String?) {
        preferences.edit().apply {
            if (country.isNullOrBlank()) remove(KEY_COUNTRY) else putString(KEY_COUNTRY, country)
        }.apply()
    }

    /** `null` means every arcade of the selected country. */
    fun placeId(): Int? = preferences.getInt(KEY_PLACE, 0).takeIf { it > 0 }

    fun setPlaceId(placeId: Int?) {
        preferences.edit().apply {
            if (placeId != null && placeId > 0) putInt(KEY_PLACE, placeId) else remove(KEY_PLACE)
        }.apply()
    }

    fun cursor(): Int = preferences.getInt(KEY_CURSOR, 0)

    /** Rotates the pool so consecutive imports spread across cabinets. */
    fun advanceCursor(by: Int) {
        val next = (cursor() + by.coerceAtLeast(0)) % 1_000_000
        preferences.edit().putInt(KEY_CURSOR, next).apply()
    }

    private companion object {
        const val KEY_COUNTRY = "country"
        const val KEY_PLACE = "placeId"
        const val KEY_CURSOR = "cursor"
    }
}

/**
 * Rotates the cabinet pool by the persisted cursor.
 *
 * The international pool has no latency data, so the order inside one arcade is simply the
 * catalog order offset by the cursor. Retrying the next cabinet is safe here: unlike the
 * one-time SGWCMAID QR code, an access code can be resolved again.
 */
internal object SdgaCabinetSelector {
    const val ATTEMPT_LIMIT = 4

    /**
     * Catalog entries include keychips the server no longer answers for, so the order
     * matters: with no explicit region the known-good build-time cabinet goes first and the
     * pool follows for load spreading; an explicit region keeps the user's choice in front.
     * The fallback stays as the final attempt in both cases.
     */
    fun plan(
        catalog: SdgaPlacesCatalog,
        country: String?,
        placeId: Int?,
        cursor: Int,
        fallback: SdgaCabinet? = null,
    ): List<SdgaCabinet> {
        val pool = catalog.cabinets(country, placeId).distinctBy { it.keychip }
        val rotated = rotate(pool, cursor).filter { it.keychip != fallback?.keychip }
        if (fallback == null) return rotated.take(ATTEMPT_LIMIT)
        val explicitSelection = !country.isNullOrBlank() || placeId != null
        // The known-good cabinet is the second attempt of an explicit region too, so a
        // region whose keychips are all dead still finishes with one retry.
        return (if (explicitSelection) rotated.take(1) + fallback + rotated.drop(1)
        else listOf(fallback) + rotated).take(ATTEMPT_LIMIT)
    }

    private fun rotate(pool: List<SdgaCabinet>, cursor: Int): List<SdgaCabinet> {
        if (pool.isEmpty()) return emptyList()
        val offset = ((cursor % pool.size) + pool.size) % pool.size
        return pool.drop(offset) + pool.take(offset)
    }
}
