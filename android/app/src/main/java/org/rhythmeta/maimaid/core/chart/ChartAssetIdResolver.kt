package org.rhythmeta.maimaid.core.chart

object ChartAssetIdResolver {
    fun resolve(providerSongId: Int?, chartType: String): Int? {
        val id = providerSongId?.takeIf { it > 0 } ?: return null
        return if (chartType.equals("dx", ignoreCase = true) && id < 10_000) id + 10_000 else id
    }

    fun candidates(providerSongId: Int?, chartType: String): List<Int> {
        val resolved = resolve(providerSongId, chartType) ?: return emptyList()
        return buildList {
            add(resolved)
            when {
                resolved >= 100_000 -> {
                    val base = resolved % 100_000
                    if (base > 0) add(base)
                    if (base in 1..9_999) add(base + 10_000)
                }
                resolved >= 10_000 -> add(resolved % 10_000)
                else -> add(resolved + 10_000)
            }
        }.distinct()
    }
}
