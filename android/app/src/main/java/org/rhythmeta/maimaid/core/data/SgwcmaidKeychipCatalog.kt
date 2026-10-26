package org.rhythmeta.maimaid.core.data

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.rhythmeta.maimaid.BuildConfig

/**
 * Loads the build-time cabinet catalog from assets.
 *
 * A missing or malformed asset degrades to an empty catalog so the import falls
 * back to the legacy single keychip instead of failing at startup.
 */
internal object SgwcmaidKeychipCatalogLoader {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context): SgwcmaidKeychipCatalog = try {
        val text = context.assets.open("keychips.json").bufferedReader().use { it.readText() }
        parse(text)
    } catch (error: Exception) {
        if (BuildConfig.DEBUG) {
            android.util.Log.w("SgwcmaidKeychips", "failed to load keychips.json", error)
        }
        SgwcmaidKeychipCatalog()
    }

    internal fun parse(text: String): SgwcmaidKeychipCatalog = json.decodeFromString(text)
}
