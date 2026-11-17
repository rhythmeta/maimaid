package org.rhythmeta.maimaid.core.chart

import org.junit.Assert.assertEquals
import org.junit.Test

class ChartAssetIdResolverTest {
    @Test
    fun resolvesDxInternalIdAndKeepsStableCandidates() {
        assertEquals(11_422, ChartAssetIdResolver.resolve(1_422, "dx"))
        assertEquals(listOf(11_422, 1_422), ChartAssetIdResolver.candidates(11_422, "dx"))
    }

    @Test
    fun resolvesUtageIdToItsBaseSongAsFallback() {
        assertEquals(listOf(100_142, 142, 10_142), ChartAssetIdResolver.candidates(100_142, "utage"))
    }
}
