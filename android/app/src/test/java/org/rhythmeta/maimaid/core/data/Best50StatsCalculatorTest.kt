package org.rhythmeta.maimaid.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Best50StatsCalculatorTest {
    @Test
    fun `averages every entry but only counts recorded dx scores`() {
        val stats = Best50StatsCalculator.calculate(
            listOf(
                entry(achievement = 100.0, dxScore = 3_000, maxDxScore = 3_000),
                entry(achievement = 99.0, dxScore = 2_400, maxDxScore = 3_000),
                entry(achievement = 97.0, dxScore = 0, maxDxScore = 3_000),
            ),
        )!!

        assertEquals((100.0 + 99.0 + 97.0) / 3, stats.averageAchievement, 0.0001)
        assertEquals(90.0, stats.dxAchievement, 0.0001)
        assertEquals(ScoreRules.dxStarsForRate(0.9), stats.dxStars)
        assertTrue(stats.hasDxData)
    }

    @Test
    fun `entries without dx scores do not drag the rate down`() {
        val stats = Best50StatsCalculator.calculate(
            listOf(
                entry(achievement = 100.0, dxScore = 2_910, maxDxScore = 3_000),
                entry(achievement = 100.0, dxScore = 0, maxDxScore = 3_000),
            ),
        )!!

        assertEquals(97.0, stats.dxAchievement, 0.0001)
        assertEquals(ScoreRules.dxStarsForRate(0.97), stats.dxStars)
    }

    @Test
    fun `reports no dx data when every score is missing it`() {
        val stats = Best50StatsCalculator.calculate(
            listOf(entry(achievement = 100.0, dxScore = 0, maxDxScore = 3_000)),
        )!!

        assertFalse(stats.hasDxData)
        assertEquals(0.0, stats.dxAchievement, 0.0001)
        assertEquals(0, stats.dxStars)
    }

    @Test
    fun `empty buckets have no stats`() {
        assertNull(Best50StatsCalculator.calculate(emptyList()))
    }

    private fun entry(
        achievement: Double,
        dxScore: Int,
        maxDxScore: Int,
    ) = RatingUtils.Entry(
        sheetKey = "sheet-$achievement-$dxScore",
        songIdentifier = "song",
        songId = 1,
        title = "Song",
        imageName = "",
        achievement = achievement,
        rating = 300,
        level = 14.0,
        difficulty = "master",
        type = "dx",
        dxScore = dxScore,
        maxDxScore = maxDxScore,
        fc = null,
        fs = null,
        isNew = true,
        category = "maimai",
    )
}
