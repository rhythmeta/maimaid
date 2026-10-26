package org.rhythmeta.maimaid.core.data

import kotlinx.serialization.Serializable

/**
 * One China-server cabinet from the build-time keychip catalog.
 *
 * The catalog is injected during the build (see `build.gradle.kts`) and never
 * committed.
 */
@Serializable
data class SgwcmaidKeychip(
    val keychip: String,
    val provinceId: Int,
    val storeId: Int,
    val latencyMs: Int,
)

/** A selectable province, with the direct-controlled municipalities as their own entries. */
@Serializable
data class SgwcmaidProvince(
    val id: Int,
    val name: String,
    val count: Int,
)

@Serializable
data class SgwcmaidKeychipCatalog(
    val version: Int = 0,
    val source: String = "",
    val provinces: List<SgwcmaidProvince> = emptyList(),
    val keychips: List<SgwcmaidKeychip> = emptyList(),
) {
    val isEmpty: Boolean get() = keychips.isEmpty()

    fun keychipsInProvince(id: Int): List<SgwcmaidKeychip> = keychips.filter { it.provinceId == id }
}

/** The cabinet credentials handed to the game API for one import attempt. */
internal data class SgwcmaidCabinet(
    val keychip: String,
    val placeId: Int,
    val regionId: Int,
) {
    constructor(keychip: SgwcmaidKeychip) : this(
        keychip = keychip.keychip,
        placeId = keychip.storeId,
        regionId = keychip.provinceId,
    )
}
