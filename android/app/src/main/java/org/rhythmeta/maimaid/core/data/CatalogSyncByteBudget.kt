package org.rhythmeta.maimaid.core.data

/**
 * Byte accounting for one sync stage.
 *
 * @property baselineBytes bytes that were already on disk before this stage started. Reported
 * [CatalogSyncProgress.downloadedBytes] is always `baselineBytes + <bytes moved in this stage>`, so
 * the counter keeps climbing across the bundle → covers → avatars stages instead of restarting.
 * @property totalBytes a trusted denominator for the whole first-run download, taken from the static
 * manifest. `null` when the manifest predates the `sizes` field, in which case stages that involve
 * many responses report no denominator at all rather than a growing guess.
 */
data class CatalogSyncByteBudget(
    val baselineBytes: Long = 0,
    val totalBytes: Long? = null,
)
