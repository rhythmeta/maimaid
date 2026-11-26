package org.rhythmeta.maimaid.core.data

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.rhythmeta.maimaid.BuildConfig

/**
 * The arcades the international-server import may borrow a cabinet from.
 *
 * The deployment data is the same file the reference tool ships: a flat object keyed by
 * keychip. It is injected during the build (see `build.gradle.kts`) and never committed.
 * Losing it degrades the import to the build-time cabinet instead of failing at startup.
 */
class SdgaPlacesCatalog(val places: List<SdgaPlace>) {
    val isEmpty: Boolean get() = places.isEmpty()

    /** Country/territory codes in a stable order, for the picker. */
    val countries: List<String>
        get() = places.map { it.country }.filter { it.isNotBlank() }.distinct().sorted()

    fun placesIn(country: String?): List<SdgaPlace> =
        if (country.isNullOrBlank()) places else places.filter { it.country == country }

    /**
     * Every cabinet that belongs to the selected country and arcade, in catalog order.
     * `null` selects the whole country; `placeId` further narrows it to one arcade.
     */
    fun cabinets(country: String?, placeId: Int?): List<SdgaCabinet> =
        placesIn(country)
            .filter { placeId == null || it.placeId == placeId }
            .flatMap { it.cabinets }

    companion object {
        val empty = SdgaPlacesCatalog(emptyList())
    }
}

internal object SdgaPlacesCatalogLoader {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context): SdgaPlacesCatalog = try {
        val text = context.assets.open(PLACES_ASSET).bufferedReader().use { it.readText() }
        parse(text)
    } catch (error: Exception) {
        if (BuildConfig.DEBUG) {
            android.util.Log.w("SdgaPlaces", "failed to load $PLACES_ASSET", error)
        }
        SdgaPlacesCatalog.empty
    }

    internal fun parse(text: String): SdgaPlacesCatalog {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return SdgaPlacesCatalog.empty
        val grouped = linkedMapOf<Int, MutableList<SdgaCabinet>>()
        val metadata = linkedMapOf<Int, SdgaPlaceRecord>()
        root.forEach { (keychip, element) ->
            val record = runCatching {
                json.decodeFromJsonElement(SdgaPlaceRecord.serializer(), element)
            }.getOrNull() ?: return@forEach
            val placeId = record.placeId.toIntOrNull(16) ?: return@forEach
            val regionId = record.region0.toIntOrNull() ?: 0
            val normalized = keychip.filter { it.isLetterOrDigit() }
            if (normalized.length < 8) return@forEach
            metadata.putIfAbsent(placeId, record)
            grouped.getOrPut(placeId) { mutableListOf() }
                .add(SdgaCabinet(keychip = normalized, placeId = placeId, regionId = regionId))
        }
        val places = grouped.mapNotNull { (placeId, cabinets) ->
            val record = metadata[placeId] ?: return@mapNotNull null
            SdgaPlace(
                placeId = placeId,
                name = record.placeName.ifBlank { record.placeNickname },
                nickname = record.placeNickname,
                country = record.regionName1.uppercase(),
                region = record.regionName0,
                city = record.regionName2,
                cabinets = cabinets,
            )
        }.sortedWith(compareBy({ it.country }, { it.displayName }, { it.placeId }))
        return SdgaPlacesCatalog(places)
    }

    const val PLACES_ASSET = "sdga_places.json"
}

/** Rows grouped for the pickers: country → arcade → cabinets. */
internal fun SdgaPlacesCatalog.groupedPlaces(country: String?): List<SdgaPlace> =
    placesIn(country).sortedBy { it.displayName }

/** Test-only helper that keeps the JSON entry ordering out of the assertions. */
internal fun SdgaPlacesCatalog.hasKeychip(keychip: String): Boolean =
    places.any { place -> place.cabinets.any { it.keychip == keychip } }
