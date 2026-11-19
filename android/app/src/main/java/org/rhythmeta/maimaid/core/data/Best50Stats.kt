package org.rhythmeta.maimaid.core.data

/** Aggregate achievement / DX-score metrics for a Best 50 bucket. */
data class Best50SummaryStats(
    val averageAchievement: Double,
    val dxAchievement: Double,
    val dxStars: Int,
    val hasDxData: Boolean,
)

object Best50StatsCalculator {
    /**
     * Entries without a recorded DX score are not part of the DX achievement rate: they are
     * excluded from both the numerator and the denominator instead of counting as zero.
     */
    fun calculate(entries: List<RatingUtils.Entry>): Best50SummaryStats? {
        if (entries.isEmpty()) return null
        val dxEntries = entries.filter { it.dxScore > 0 && it.maxDxScore > 0 }
        val averageDx = if (dxEntries.isEmpty()) {
            0.0
        } else {
            dxEntries.sumOf { it.dxScore.toDouble() / it.maxDxScore } / dxEntries.size
        }
        return Best50SummaryStats(
            averageAchievement = entries.sumOf(RatingUtils.Entry::achievement) / entries.size,
            dxAchievement = averageDx * 100.0,
            dxStars = if (dxEntries.isEmpty()) 0 else ScoreRules.dxStarsForRate(averageDx),
            hasDxData = dxEntries.isNotEmpty(),
        )
    }
}
