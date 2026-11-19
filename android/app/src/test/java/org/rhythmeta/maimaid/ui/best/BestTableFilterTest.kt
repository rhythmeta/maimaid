package org.rhythmeta.maimaid.ui.best

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.rhythmeta.maimaid.core.data.RatingUtils
import org.rhythmeta.maimaid.core.data.Best50FilterSettings

class BestTableFilterTest {
    @Test
    fun `empty settings keep every entry`() {
        val entries = listOf(entry("a"), entry("b"))

        val filtered = entries.filteredBy(Best50FilterSettings())

        assertSame(entries, filtered)
    }

    @Test
    fun `difficulty and chart type filters combine`() {
        val entries = listOf(
            entry("basic", difficulty = "basic", type = "dx"),
            entry("expert-std", difficulty = "expert", type = "standard"),
            entry("expert-dx", difficulty = "expert", type = "dx"),
        )

        val filtered = entries.filteredBy(
            Best50FilterSettings(
                selectedDifficulties = setOf("expert"),
                selectedTypes = setOf("std"),
            ),
        )

        assertEquals(listOf("expert-std"), filtered.map(RatingUtils.Entry::sheetKey))
    }

    @Test
    fun `category and rank filters match the song metadata`() {
        val entries = listOf(
            entry("sss", category = "POPS & ANIME", achievement = 100.5),
            entry("s", category = "POPS & ANIME", achievement = 97.5),
            entry("game", category = "GAME & VARIETY", achievement = 100.5),
        )

        val filtered = entries.filteredBy(
            Best50FilterSettings(
                selectedCategories = setOf("POPS & ANIME"),
                selectedRanks = setOf("SSS+"),
            ),
        )

        assertEquals(listOf("sss"), filtered.map(RatingUtils.Entry::sheetKey))
    }

    @Test
    fun `fc and fs filters use the displayed status labels`() {
        val entries = listOf(
            entry("ap-plus", fc = "app", fs = "fsdp"),
            entry("ap", fc = "ap", fs = "fsd"),
            entry("fc", fc = "fc", fs = "fs"),
            entry("none", fc = null, fs = null),
        )

        val filtered = entries.filteredBy(
            Best50FilterSettings(
                selectedFc = setOf("AP+", "FC"),
                selectedFs = setOf("FDX+"),
            ),
        )

        assertEquals(listOf("ap-plus"), filtered.map(RatingUtils.Entry::sheetKey))
    }

    @Test
    fun `entries keep all selected dimensions`() {
        val entries = listOf(
            entry("match", difficulty = "master", category = "maimai", type = "dx"),
            entry("wrong-category", difficulty = "master", category = "GAME & VARIETY", type = "dx"),
        )

        val filtered = entries.filteredBy(
            Best50FilterSettings(
                selectedDifficulties = setOf("master"),
                selectedCategories = setOf("maimai"),
                selectedTypes = setOf("dx"),
                selectedRanks = setOf("SSS+"),
                selectedFc = setOf("AP+"),
                selectedFs = setOf("FDX+"),
            ),
        )

        assertEquals(listOf("match"), filtered.map(RatingUtils.Entry::sheetKey))
    }

    private fun entry(
        sheetKey: String,
        difficulty: String = "master",
        category: String = "POPS & ANIME",
        type: String = "dx",
        achievement: Double = 100.5,
        fc: String? = "app",
        fs: String? = "fsdp",
    ) = RatingUtils.Entry(
        sheetKey = sheetKey,
        songIdentifier = sheetKey,
        songId = 1,
        title = sheetKey,
        imageName = "",
        achievement = achievement,
        rating = 300,
        level = 15.0,
        difficulty = difficulty,
        type = type,
        dxScore = 3000,
        maxDxScore = 3000,
        fc = fc,
        fs = fs,
        isNew = true,
        category = category,
    )
}
