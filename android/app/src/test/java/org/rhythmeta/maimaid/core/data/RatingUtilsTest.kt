package org.rhythmeta.maimaid.core.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rhythmeta.maimaid.core.database.GameVersionEntity
import org.rhythmeta.maimaid.core.database.SheetEntity
import org.rhythmeta.maimaid.core.database.SongEntity

class RatingUtilsTest {
    private val versions = listOf("FiNALE", "DX", "Splash", "UNiVERSE", "FESTiVAL", "BUDDiES", "PRiSM", "CiRCLE")

    @Test
    fun `rating uses the matching achievement coefficient`() {
        assertEquals(280, RatingUtils.calculate(internalLevel = 13.0, achievement = 100.0))
        assertEquals(292, RatingUtils.calculate(internalLevel = 13.0, achievement = 100.5))
    }

    @Test
    fun `rank thresholds match score entry ranks`() {
        RatingUtils.rankThresholds.forEach { threshold ->
            assertEquals(threshold.rank, RatingUtils.rank(threshold.threshold))
        }
    }

    @Test
    fun `ap receives bonus from circle onward`() {
        assertEquals(292, RatingUtils.calculate(13.0, 100.5, fc = "ap", afterCircle = false))
        assertEquals(293, RatingUtils.calculate(13.0, 100.5, fc = "app", afterCircle = true))
        assertTrue(RatingUtils.isAfterCircle("CiRCLE", versions))
        assertFalse(RatingUtils.isAfterCircle("PRiSM", versions))
    }

    @Test
    fun `jp includes current and previous version after circle`() {
        assertTrue(RatingUtils.category("PRiSM", "CiRCLE", "jp", true, versions) == true)
        assertFalse(RatingUtils.category("BUDDiES", "CiRCLE", "jp", true, versions) == true)
        assertFalse(RatingUtils.category("future", "CiRCLE", "jp", true, versions) == true)
    }

    @Test
    fun `cn preview songs stay old until their version becomes current`() {
        assertTrue(RatingUtils.category("PRiSM", "PRiSM", "cn", true, versions) == true)
        assertFalse(RatingUtils.category("CiRCLE", "PRiSM", "cn", true, versions) == true)
        assertFalse(RatingUtils.category("BUDDiES", "PRiSM", "cn", true, versions) == true)
        assertEquals(null, RatingUtils.category("PRiSM", "PRiSM", "cn", false, versions))
    }

    @Test
    fun `latest server version follows iOS release cutoffs`() {
        val versionEntities = listOf(
            GameVersionEntity("PRiSM", "PRiSM", null, 0),
            GameVersionEntity("PRiSM PLUS", "PRiSM+", null, 1),
            GameVersionEntity("CiRCLE", "CiRCLE", null, 2),
        )
        val songs = listOf(
            testSong("prism", "PRiSM", "2024-01-01"),
            testSong("prism-plus-released", "PRiSM PLUS", "2025-04-01"),
            testSong("prism-plus-future", "PRiSM PLUS", "2025-07-01"),
            testSong("circle", "CiRCLE", "2026-06-01"),
        )
        val sheets = listOf(
            testSheet("prism", "PRiSM", intl = true, cn = true),
            testSheet("prism-plus-released", "PRiSM PLUS", intl = true, cn = false),
            testSheet("prism-plus-future", "PRiSM PLUS", intl = false, cn = false),
            testSheet("circle", "CiRCLE", intl = false, cn = false),
        )
        val currentDate = LocalDate.of(2026, 8, 12)

        assertEquals(
            "CiRCLE",
            RatingUtils.latestVersionForServer(songs, sheets, versionEntities, "jp", currentDate),
        )
        assertEquals(
            "PRiSM PLUS",
            RatingUtils.latestVersionForServer(songs, sheets, versionEntities, "intl", currentDate),
        )
        assertEquals(
            "PRiSM PLUS",
            RatingUtils.latestVersionForServer(songs, sheets, versionEntities, "cn", currentDate),
        )
    }

    @Test
    fun `cn ignores sparse early access and uses version release cutoff`() {
        val versionEntities = listOf(
            GameVersionEntity("ORANGE PLUS", "ORANGE+", "2015-03-19", 0),
            GameVersionEntity("PRiSM PLUS", "PRiSM+", "2025-03-13", 1),
            GameVersionEntity("CiRCLE", "CiRCLE", "2025-09-18", 2),
            GameVersionEntity("CiRCLE PLUS", "CiRCLE+", "2026-03-19", 3),
            GameVersionEntity("MAGiCAL", "MAGiCAL", "2026-09-17", 4),
        )
        val songs = listOf(
            testSong("legacy-rerelease", "ORANGE PLUS", "2026-10-16"),
            testSong("prism-cn", "PRiSM PLUS", "2025-06-01"),
            testSong("prism-upcoming", "PRiSM PLUS", "2025-08-01"),
            testSong("circle-early-access-1", "CiRCLE", "2025-09-26"),
            testSong("circle-early-access-2", "CiRCLE", "2025-09-26"),
            testSong("circle-early-access-3", "CiRCLE", "2026-01-23"),
            testSong("circle-early-access-4", "CiRCLE", "2026-01-23"),
            testSong("circle-early-access-5", "CiRCLE", "2026-01-23"),
            testSong("circle-not-cn", "CiRCLE", "2025-09-18"),
            testSong("circle-plus", "CiRCLE PLUS", "2026-03-19"),
            testSong("magical", "MAGiCAL", "2026-09-17"),
        )
        val sheets = listOf(
            testSheet("legacy-rerelease", "ORANGE PLUS", intl = false),
            testSheet("prism-cn", "PRiSM PLUS", intl = false, cn = true),
            testSheet("prism-upcoming", "PRiSM PLUS", intl = false),
            testSheet("circle-early-access-1", "CiRCLE", intl = false, cn = true),
            testSheet("circle-early-access-2", "CiRCLE", intl = false, cn = true),
            testSheet("circle-early-access-3", "CiRCLE", intl = false, cn = true),
            testSheet("circle-early-access-4", "CiRCLE", intl = false, cn = true),
            testSheet("circle-early-access-5", "CiRCLE", intl = false, cn = true),
            testSheet("circle-not-cn", "CiRCLE", intl = false),
            testSheet("circle-plus", "CiRCLE PLUS", intl = false),
            testSheet("magical", "MAGiCAL", intl = false),
        )

        assertEquals(
            "PRiSM PLUS",
            RatingUtils.latestVersionForServer(
                songs,
                sheets,
                versionEntities,
                "cn",
                LocalDate.of(2026, 10, 20),
            ),
        )
    }

    private fun testSong(id: String, version: String, releaseDate: String? = null) = SongEntity(
        songIdentifier = id,
        category = "maimai",
        title = id,
        artist = "",
        imageName = "",
        version = version,
        releaseDate = releaseDate,
        sortOrder = 0,
        bpm = null,
        isNew = false,
        isLocked = false,
        comment = null,
    )

    private fun testSheet(
        songId: String,
        version: String,
        intl: Boolean,
        cn: Boolean = false,
    ) = SheetEntity(
        sheetKey = "$songId-master",
        songIdentifier = songId,
        type = "dx",
        difficulty = "master",
        version = version,
        level = "13",
        levelValue = 13.0,
        internalLevel = "13.0",
        internalLevelValue = 13.0,
        noteDesigner = null,
        tap = null,
        hold = null,
        slide = null,
        touch = null,
        breakCount = null,
        total = null,
        regionJp = true,
        regionIntl = intl,
        regionUsa = false,
        regionCn = cn,
    )
}
