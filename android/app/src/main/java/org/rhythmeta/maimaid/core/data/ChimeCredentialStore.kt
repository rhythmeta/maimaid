package org.rhythmeta.maimaid.core.data

import android.content.Context

class ChimeCredentialStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("chime_credentials", Context.MODE_PRIVATE)

    /** Falls back to the keychip supplied at build time through MAIMAID_SGWCMAID. */
    fun keychip(): String = preferences.getString(KEYCHIP, "").orEmpty()
        .ifBlank { SgwcmaidBuildConfig.current.keychip }

    fun setKeychip(value: String) {
        preferences.edit().putString(KEYCHIP, value.trim()).apply()
    }

    private companion object { const val KEYCHIP = "keychip" }
}
