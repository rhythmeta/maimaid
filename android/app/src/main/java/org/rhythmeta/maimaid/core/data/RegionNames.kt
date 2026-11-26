package org.rhythmeta.maimaid.core.data

import java.util.Locale

/**
 * Turns the region codes used by the international deployment into readable names.
 *
 * The places catalog publishes ISO-3166 alpha-3 codes (`SGP`, `HKG`, …) while IP
 * geolocation answers with alpha-2 codes (`SG`, `HK`, …). Both are rendered with the
 * platform's localized country names, so every app language gets its own wording without
 * shipping a table of translations. Unknown codes fall back to the raw string.
 */
internal object RegionNames {
    private val ISO3_TO_ISO2 = mapOf(
        "AUS" to "AU",
        "CAN" to "CA",
        "CHN" to "CN",
        "GBR" to "GB",
        "HKG" to "HK",
        "IDN" to "ID",
        "JPN" to "JP",
        "KOR" to "KR",
        "MAC" to "MO",
        "MMR" to "MM",
        "MYS" to "MY",
        "NZL" to "NZ",
        "PHL" to "PH",
        "SGP" to "SG",
        "THA" to "TH",
        "TWN" to "TW",
        "USA" to "US",
        "VNM" to "VN",
    )

    /** Alpha-2 code for an alpha-3 or alpha-2 input, uppercased. */
    fun iso2(code: String?): String? {
        val normalized = code?.trim()?.uppercase().orEmpty()
        return when {
            normalized.length == 2 -> normalized
            else -> ISO3_TO_ISO2[normalized]
        }
    }

    /**
     * Localized country name for [code], with [fallback] (or the raw code) when the
     * platform has no name for it.
     */
    fun displayName(code: String?, fallback: String? = null, locale: Locale = Locale.getDefault()): String {
        val raw = code?.trim().orEmpty()
        val region = iso2(raw)
        val name = region?.let {
            runCatching {
                Locale.Builder().setRegion(it).build().getDisplayCountry(locale)
            }.getOrNull()
        }
        return name?.takeIf { it.isNotBlank() && !it.equals(region, ignoreCase = true) }
            ?: fallback?.takeIf { it.isNotBlank() }
            ?: raw
    }
}
