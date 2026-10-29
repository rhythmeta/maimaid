package org.rhythmeta.maimaid.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSyncProgressReporterTest {
    @Test
    fun `item progress is remapped into the stage slice`() {
        val published = mutableListOf<CatalogSyncProgress>()
        val reporter = CatalogSyncProgressReporter(
            stage = CatalogSyncStage.Covers,
            overallStart = 0.2f,
            overallEnd = 0.6f,
            onProgress = published::add,
        )

        reporter.start()
        reporter.onItemsProgress(completedItems = 50, totalItems = 100)

        assertEquals(0.4f, published.last().overallFraction, TOLERANCE)
        assertEquals(CatalogSyncStage.Covers, published.last().stage)
        assertEquals(50, published.last().completedItems)
        assertEquals(100, published.last().totalItems)
    }

    @Test
    fun `complete always lands on the end of the stage`() {
        val published = mutableListOf<CatalogSyncProgress>()
        val reporter = CatalogSyncProgressReporter(
            stage = CatalogSyncStage.PresetAvatars,
            overallStart = 0.85f,
            overallEnd = 1f,
            onProgress = published::add,
        )

        reporter.start()
        reporter.complete()

        assertEquals(1f, published.last().overallFraction, TOLERANCE)
    }

    @Test
    fun `an empty stage stays at its start until it completes`() {
        val published = mutableListOf<CatalogSyncProgress>()
        val reporter = CatalogSyncProgressReporter(
            stage = CatalogSyncStage.Covers,
            overallStart = 0f,
            overallEnd = 0.85f,
            onProgress = published::add,
        )

        reporter.start()
        assertEquals(0f, published.last().overallFraction, TOLERANCE)
        assertTrue(published.last().totalBytes == null)

        reporter.complete()
        assertEquals(0.85f, published.last().overallFraction, TOLERANCE)
    }

    @Test
    fun `byte progress drives the fraction when enabled`() {
        val published = mutableListOf<CatalogSyncProgress>()
        val reporter = CatalogSyncProgressReporter(
            stage = CatalogSyncStage.CatalogBundle,
            overallStart = 0f,
            overallEnd = 0.7f,
            useByteProgress = true,
            onProgress = published::add,
        )

        reporter.start(totalItems = 1)
        reporter.onTransfer(byteCount = 0L, expectedBytes = 200L)
        reporter.onTransfer(byteCount = 100L, expectedBytes = null)

        assertEquals(0.35f, published.last().overallFraction, TOLERANCE)
        assertEquals(100L, published.last().downloadedBytes)
        assertEquals(200L, published.last().totalBytes)
    }

    @Test
    fun `budget baseline is added to the reported bytes`() {
        val published = mutableListOf<CatalogSyncProgress>()
        val reporter = CatalogSyncProgressReporter(
            stage = CatalogSyncStage.Covers,
            overallStart = 0f,
            overallEnd = 0.85f,
            byteBudget = CatalogSyncByteBudget(baselineBytes = 1_000L, totalBytes = 5_000L),
            onProgress = published::add,
        )

        reporter.start()
        reporter.onTransfer(byteCount = 0L, expectedBytes = 200L)
        reporter.onTransfer(byteCount = 400L, expectedBytes = null)

        assertEquals(1_400L, published.last().downloadedBytes)
        assertEquals(1_400L, reporter.transferredBytes())
    }

    @Test
    fun `budget total wins over the accumulated response lengths`() {
        val published = mutableListOf<CatalogSyncProgress>()
        val reporter = CatalogSyncProgressReporter(
            stage = CatalogSyncStage.Covers,
            overallStart = 0f,
            overallEnd = 0.85f,
            byteBudget = CatalogSyncByteBudget(totalBytes = 5_000L),
            onProgress = published::add,
        )

        reporter.start()
        reporter.onTransfer(byteCount = 0L, expectedBytes = 200L)
        reporter.onTransfer(byteCount = 0L, expectedBytes = 300L)

        assertEquals(5_000L, published.last().totalBytes)
    }

    @Test
    fun `a multi response stage without a budget reports no denominator`() {
        val published = mutableListOf<CatalogSyncProgress>()
        val reporter = CatalogSyncProgressReporter(
            stage = CatalogSyncStage.Covers,
            overallStart = 0f,
            overallEnd = 0.85f,
            onProgress = published::add,
        )

        reporter.start()
        reporter.onTransfer(byteCount = 0L, expectedBytes = 200L)
        reporter.onTransfer(byteCount = 50L, expectedBytes = null)

        assertNull(published.last().totalBytes)
        assertEquals(50L, published.last().downloadedBytes)
    }

    @Test
    fun `a budgeted total also drives the byte fraction of the bundle stage`() {
        val published = mutableListOf<CatalogSyncProgress>()
        val reporter = CatalogSyncProgressReporter(
            stage = CatalogSyncStage.CatalogBundle,
            overallStart = 0f,
            overallEnd = 0.7f,
            useByteProgress = true,
            byteBudget = CatalogSyncByteBudget(totalBytes = 1_000L),
            onProgress = published::add,
        )

        reporter.start(totalItems = 1)
        // The transport reports an encoded Content-Length that is smaller than the decoded body.
        reporter.onTransfer(byteCount = 0L, expectedBytes = 120L)
        reporter.onTransfer(byteCount = 250L, expectedBytes = null)

        assertEquals(0.175f, published.last().overallFraction, TOLERANCE)
        assertEquals(1_000L, published.last().totalBytes)
    }

    private companion object {
        const val TOLERANCE = 0.0001f
    }
}
