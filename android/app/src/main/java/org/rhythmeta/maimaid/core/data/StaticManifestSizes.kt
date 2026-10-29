package org.rhythmeta.maimaid.core.data

import kotlinx.serialization.Serializable

/**
 * Byte sizes published by the static-asset build in `manifest.json`.
 *
 * The numbers are measured from the bytes stored in the published tree, so they describe a whole
 * first-run download: the JSON bundle plus every cover and preset-avatar image. Covers are fetched
 * through the CDN image transform, so the bytes a device receives differ slightly from these.
 */
@Serializable
data class StaticManifestSizes(
    val bundleBytes: Long = 0,
    val imageBytes: Long = 0,
    val totalBytes: Long = 0,
)
