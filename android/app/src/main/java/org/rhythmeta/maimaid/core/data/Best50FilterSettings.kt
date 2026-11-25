package org.rhythmeta.maimaid.core.data

import kotlinx.serialization.Serializable

/**
 * Quick filters applied to the Best 50 list, the rating summary, and the exported image.
 * An empty set means "no restriction" for that dimension.
 */
@Serializable
data class Best50FilterSettings(
    val selectedDifficulties: Set<String> = emptySet(),
    val selectedCategories: Set<String> = emptySet(),
    val selectedTypes: Set<String> = emptySet(),
    val selectedRanks: Set<String> = emptySet(),
    val selectedDxStars: Set<Int> = emptySet(),
    val selectedFc: Set<String> = emptySet(),
    val selectedFs: Set<String> = emptySet(),
) {
    val isEmpty: Boolean
        get() = selectedDifficulties.isEmpty() &&
            selectedCategories.isEmpty() &&
            selectedTypes.isEmpty() &&
            selectedRanks.isEmpty() &&
            selectedDxStars.isEmpty() &&
            selectedFc.isEmpty() &&
            selectedFs.isEmpty()
}
