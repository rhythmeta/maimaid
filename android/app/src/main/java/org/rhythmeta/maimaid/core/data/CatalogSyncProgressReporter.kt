package org.rhythmeta.maimaid.core.data

/**
 * Publishes a single sync stage as an [CatalogSyncProgress] whose overall fraction is remapped
 * into the `[overallStart, overallEnd]` slice of the whole operation.
 *
 * The reporter is driven from several threads: item counters come from the coroutine that owns
 * the stage, while byte counters come from the download workers, hence the synchronized accessors.
 */
class CatalogSyncProgressReporter(
    private val stage: CatalogSyncStage,
    private val overallStart: Float,
    private val overallEnd: Float,
    private val useByteProgress: Boolean = false,
    private val byteBudget: CatalogSyncByteBudget = CatalogSyncByteBudget(),
    private val onProgress: (CatalogSyncProgress) -> Unit,
) {
    private val speedTracker = DownloadSpeedTracker()
    private var completedItems = 0
    private var totalItems = 0
    private var stageBytes = 0L
    private var stageExpectedBytes = 0L
    private var bytesPerSecond = 0L

    @Synchronized
    fun start(totalItems: Int = 0) {
        this.totalItems = totalItems.coerceAtLeast(0)
        publish()
    }

    @Synchronized
    fun onItemsProgress(completedItems: Int, totalItems: Int) {
        this.completedItems = completedItems.coerceAtLeast(0)
        this.totalItems = totalItems.coerceAtLeast(0)
        publish()
    }

    @Synchronized
    fun onTransfer(byteCount: Long, expectedBytes: Long?) {
        if (expectedBytes != null) stageExpectedBytes += expectedBytes.coerceAtLeast(0L)
        val snapshot = speedTracker.addBytes(byteCount)
        stageBytes = snapshot.downloadedBytes
        bytesPerSecond = snapshot.bytesPerSecond
        publish()
    }

    @Synchronized
    fun complete() {
        if (totalItems > 0) completedItems = totalItems
        publish(stageFraction = 1f)
    }

    /**
     * Everything accounted for up to the end of this stage: the budget baseline plus the bytes this
     * stage moved. The next stage uses it as its baseline so the counter keeps climbing.
     */
    @Synchronized
    fun transferredBytes(): Long = byteBudget.baselineBytes + stageBytes

    private fun publish(stageFraction: Float = calculatedStageFraction()) {
        val overallFraction = overallStart + (overallEnd - overallStart) * stageFraction
        onProgress(
            CatalogSyncProgress(
                stage = stage,
                overallFraction = overallFraction.coerceIn(0f, 1f),
                completedItems = completedItems,
                totalItems = totalItems,
                downloadedBytes = byteBudget.baselineBytes + stageBytes,
                totalBytes = publishedTotalBytes(),
                bytesPerSecond = bytesPerSecond,
            ),
        )
    }

    /**
     * A stage that downloads many responses cannot know its own total up front, and the running sum
     * of the responses started so far is not a denominator — it grows while the download runs. Only a
     * manifest-provided budget, or a single-response stage's `Content-Length`, is trustworthy.
     */
    private fun publishedTotalBytes(): Long? = byteBudget.totalBytes
        ?: if (useByteProgress) stageExpectedBytes.takeIf { it > 0L } else null

    private fun calculatedStageFraction(): Float {
        val denominator = byteBudget.totalBytes ?: stageExpectedBytes
        return when {
            useByteProgress && denominator > 0L -> stageBytes.toFloat() / denominator
            totalItems > 0 -> completedItems.toFloat() / totalItems
            else -> 0f
        }.coerceIn(0f, 1f)
    }
}
