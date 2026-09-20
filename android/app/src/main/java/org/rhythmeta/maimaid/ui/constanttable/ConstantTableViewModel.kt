package org.rhythmeta.maimaid.ui.constanttable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import org.rhythmeta.maimaid.core.AppContainer
import org.rhythmeta.maimaid.core.data.ConstantTableResponse
import org.rhythmeta.maimaid.core.data.ConstantTableSection
import org.rhythmeta.maimaid.ui.catalog.CatalogFilterSettings

data class ConstantTableUiState(
    val isLoading: Boolean = true,
    val response: ConstantTableResponse = ConstantTableResponse(),
    val selectedBaseLevel: Int? = null,
    val includeScores: Boolean = false,
    val filterSettings: CatalogFilterSettings = CatalogFilterSettings(),
    val sections: List<ConstantTableSection> = emptyList(),
) {
    val chartCount: Int get() = sections.sumOf { it.entries.size }
}

class ConstantTableViewModel(
    container: AppContainer,
) : ViewModel() {
    private val selectedBaseLevel = MutableStateFlow<Int?>(null)
    private val includeScores = MutableStateFlow(false)
    private val filterSettings = MutableStateFlow(CatalogFilterSettings())

    val state = combine(
        container.constantTableRepository.observeConstantTable()
            .map<ConstantTableResponse, ConstantTableResponse?> { it }
            .onStart { emit(null) },
        selectedBaseLevel,
        includeScores,
        filterSettings,
    ) { response, requestedBaseLevel, scoresIncluded, filters ->
        if (response == null) {
            ConstantTableUiState(includeScores = scoresIncluded, filterSettings = filters)
        } else {
            val filteredResponse = response.copy(
                entries = response.entries.filter { entry ->
                    (filters.showFavoritesOnly.not() || entry.isFavorite) &&
                        (filters.selectedCategories.isEmpty() || entry.category in filters.selectedCategories) &&
                        (filters.selectedVersions.isEmpty() || entry.version in filters.selectedVersions)
                },
            )
            val levels = filteredResponse.availableBaseLevels
            val resolvedBaseLevel = requestedBaseLevel
                ?.takeIf(levels::contains)
                ?: 14.takeIf(levels::contains)
                ?: levels.firstOrNull()
            ConstantTableUiState(
                isLoading = false,
                selectedBaseLevel = resolvedBaseLevel,
                includeScores = scoresIncluded,
                filterSettings = filters,
                response = filteredResponse,
                sections = resolvedBaseLevel?.let(filteredResponse::sections).orEmpty(),
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ConstantTableUiState(),
    )

    fun selectBaseLevel(baseLevel: Int) {
        selectedBaseLevel.value = baseLevel
    }

    fun setIncludeScores(include: Boolean) {
        includeScores.value = include
    }

    fun setFilterSettings(settings: CatalogFilterSettings) {
        filterSettings.value = settings
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ConstantTableViewModel::class.java))
            return ConstantTableViewModel(container) as T
        }
    }
}
