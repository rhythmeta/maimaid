package org.rhythmeta.maimaid.core.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.rhythmeta.maimaid.BuildConfig

/**
 * Deployment values for the SGWCMAID import, supplied at build time through the single
 * `MAIMAID_SGWCMAID` environment variable (a JSON object). Nothing here is baked into the
 * sources; an unset variable yields an empty config and the feature reports
 * `sgwcmaid_not_configured`.
 */
@Serializable
internal data class SgwcmaidConfig(
    val chimeUrl: String = "",
    val gameServer: String = "",
    val keychip: String = "",
    val aesKey: String = "",
    val aesIv: String = "",
    val apiSalt: String = "",
    val chimeSecret: String = "",
    val titleKey: String = "",
    val placeId: Int = 0,
    val regionId: Int = 0,
) {
    val isComplete: Boolean
        get() = chimeUrl.isNotBlank() && gameServer.isNotBlank() && aesKey.length == 32 &&
            aesIv.length == 16 && apiSalt.isNotBlank() && chimeSecret.isNotBlank() &&
            titleKey.isNotBlank() && placeId > 0 && regionId > 0
}

internal object SgwcmaidBuildConfig {
    private val json = Json { ignoreUnknownKeys = true }

    val current: SgwcmaidConfig by lazy {
        runCatching { json.decodeFromString<SgwcmaidConfig>(BuildConfig.SGWCMAID_CONFIG) }
            .onFailure { error ->
                if (BuildConfig.DEBUG) android.util.Log.w("SgwcmaidConfig", "invalid MAIMAID_SGWCMAID JSON", error)
            }
            .getOrDefault(SgwcmaidConfig())
    }
}
