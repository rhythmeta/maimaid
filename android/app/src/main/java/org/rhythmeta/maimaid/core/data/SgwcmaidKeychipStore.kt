package org.rhythmeta.maimaid.core.data

import android.content.Context

/**
 * Persists the manually selected import region and the load-balancing cursor.
 *
 * The selection is local-only and is never derived from the device location.
 */
class SgwcmaidKeychipStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("sgwcmaid_keychips", Context.MODE_PRIVATE)

    /** `null` means automatic: use the nationwide pool directly. */
    fun selectedProvinceId(): Int? = preferences.getInt(KEY_PROVINCE, 0).takeIf { it > 0 }

    fun setSelectedProvinceId(id: Int?) {
        preferences.edit().apply {
            if (id != null && id > 0) putInt(KEY_PROVINCE, id) else remove(KEY_PROVINCE)
        }.apply()
    }

    fun cursor(): Int = preferences.getInt(KEY_CURSOR, 0)

    /**
     * Advances the round-robin cursor so the next import starts after the
     * cabinet this import settled on. Failed candidates are skipped until the
     * cursor wraps around the pool.
     */
    fun advanceCursor(by: Int) {
        val next = (cursor() + by.coerceAtLeast(0)) % 1_000_000
        preferences.edit().putInt(KEY_CURSOR, next).apply()
    }

    private companion object {
        const val KEY_PROVINCE = "provinceId"
        const val KEY_CURSOR = "cursor"
    }
}
