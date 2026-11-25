package org.rhythmeta.maimaid.ui.best

import kotlinx.serialization.json.Json
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
    fun `DX star filters match exact star counts`() {
        val entries = listOf(0, 84, 85, 89, 90, 92, 93, 94, 95, 96, 97, 100).map { percent ->
            entry("$percent", dxScore = percent * 30)
        }
        val expected = listOf(
            listOf("0", "84"),
            listOf("85", "89"),
            listOf("90", "92"),
            listOf("93", "94"),
            listOf("95", "96"),
            listOf("97", "100"),
        )

        expected.forEachIndexed { index, keys ->
            val filtered = entries.filteredBy(Best50FilterSettings(selectedDxStars = setOf(index)))

            assertEquals(keys, filtered.map(RatingUtils.Entry::sheetKey))
        }
    }

    @Test
    fun `multiple DX stars exclude unselected stars and missing DX scores`() {
        val entries = listOf(
            entry("one", dxScore = 2550),
            entry("three", dxScore = 2790),
            entry("five", dxScore = 2910),
            entry("zero", dxScore = 0),
            entry("unknown-maximum", maxDxScore = 0),
        )

        val filtered = entries.filteredBy(Best50FilterSettings(selectedDxStars = setOf(1, 5)))

        assertEquals(listOf("one", "five"), filtered.map(RatingUtils.Entry::sheetKey))
    }

    @Test
    fun `zero DX stars include entries without DX score data`() {
        val entries = listOf(
            entry("below-one", dxScore = 2549),
            entry("missing-score", dxScore = 0),
            entry("unknown-maximum", maxDxScore = 0),
            entry("one", dxScore = 2550),
        )

        val filtered = entries.filteredBy(Best50FilterSettings(selectedDxStars = setOf(0)))

        assertEquals(
            listOf("below-one", "missing-score", "unknown-maximum"),
            filtered.map(RatingUtils.Entry::sheetKey),
        )
    }

    @Test
    fun `saved filters preserve DX stars and accept settings without stars`() {
        val legacy = Json.decodeFromString<Best50FilterSettings>("""{"selectedRanks":["SSS+"]}""")
        assertEquals(Best50FilterSettings(selectedRanks = setOf("SSS+")), legacy)

        val settings = legacy.copy(selectedDxStars = setOf(0, 1, 5))
        assertEquals(settings, Json.decodeFromString<Best50FilterSettings>(Json.encodeToString(settings)))
    }

    @Test
    fun `entries keep all selected dimensions`() {
        val entries = listOf(
            entry("match", difficulty = "master", category = "maimai", type = "dx"),
            entry("wrong-category", difficulty = "master", category = "GAME & VARIETY", type = "dx"),
            entry("wrong-stars", category = "maimai", dxScore = 2550),
            entry("wrong-rank", category = "maimai", achievement = 99.0),
        )

        val filtered = entries.filteredBy(
            Best50FilterSettings(
                selectedDifficulties = setOf("master"),
                selectedCategories = setOf("maimai"),
                selectedTypes = setOf("dx"),
                selectedRanks = setOf("SSS+"),
                selectedDxStars = setOf(5),
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
        dxScore: Int = 3000,
        maxDxScore: Int = 3000,
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
        dxScore = dxScore,
        maxDxScore = maxDxScore,
        fc = fc,
        fs = fs,
        isNew = true,
        category = category,
    )
}
