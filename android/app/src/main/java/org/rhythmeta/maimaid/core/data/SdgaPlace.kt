package org.rhythmeta.maimaid.core.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One record of the build-time places database (`sdga_places.json`).
 *
 * The file is a flat object keyed by cabinet keychip, so several keychips map to the same
 * arcade. `place_id` is hexadecimal and `region0` is decimal; both are kept as strings in
 * the wire format because that is how the reference deployment publishes them.
 */
@Serializable
internal data class SdgaPlaceRecord(
    @SerialName("place_name") val placeName: String = "",
    @SerialName("place_nickname") val placeNickname: String = "",
    @SerialName("place_id") val placeId: String = "",
    @SerialName("region0") val region0: String = "",
    @SerialName("region_name0") val regionName0: String = "",
    @SerialName("region_name1") val regionName1: String = "",
    @SerialName("region_name2") val regionName2: String = "",
)

/** The cabinet credentials handed to AiMeDB and the title server for one import attempt. */
data class SdgaCabinet(
    val keychip: String,
    val placeId: Int,
    val regionId: Int,
)

/** One arcade, with every keychip the catalog knows for it. */
data class SdgaPlace(
    val placeId: Int,
    val name: String,
    val nickname: String,
    /** ISO-3166 alpha-3 code of the country/territory (`SGP`, `HKG`, `TWN`, `KOR`, ...). */
    val country: String,
    /** Top-level subdivision (`Central Singapore`, `Yau Tsim Mong`, ...). */
    val region: String,
    /** City or district as published by the cabinet. */
    val city: String,
    val cabinets: List<SdgaCabinet>,
) {
    val displayName: String get() = name.ifBlank { nickname }
}
