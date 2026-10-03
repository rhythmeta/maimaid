package org.rhythmeta.maimaid.core.data

import java.text.Normalizer
import org.rhythmeta.maimaid.core.database.SheetEntity
import org.rhythmeta.maimaid.core.database.SongEntity

class ScoreImportSheetMatcher(sheets: List<SheetEntity>, songs: Map<String, SongEntity>) {
    private val byId = mutableMapOf<String, MutableList<SheetEntity>>()
    private val byTitle = mutableMapOf<String, MutableList<SheetEntity>>()

    init {
        sheets.forEach { sheet ->
            val type = ThirdPartyImportPolicy.chartType(sheet.type)
            val suffix = "|$type|${if (type == "utage") "utage" else sheet.difficulty.lowercase()}"
            val id = sheet.providerSongId.takeIf { it > 0 } ?: sheet.songIdentifier.toIntOrNull() ?: 0
            if (id > 0) byId.getOrPut("$id$suffix") { mutableListOf() }.add(sheet)
            val title = normalize(songs[sheet.songIdentifier]?.title.orEmpty())
            if (title.isNotBlank()) byTitle.getOrPut("$title$suffix") { mutableListOf() }.add(sheet)
        }
    }

    fun match(score: ImportedScore): SheetEntity? {
        val difficulty = listOf("basic", "advanced", "expert", "master", "remaster").getOrNull(score.levelIndex).orEmpty()
        val suffix = "|${score.type}|${if (score.type == "utage") "utage" else difficulty}"
        if (score.songId > 0) byId["${score.songId}$suffix"]?.let { return it.singleOrNull() }
        val title = normalize(score.title)
        return if (title.isBlank()) null else byTitle["$title$suffix"]?.singleOrNull()
    }
    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).trim().lowercase()
}
