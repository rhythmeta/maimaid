package org.rhythmeta.maimaid.ui.recommendation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import org.rhythmeta.maimaid.core.AppContainer
import org.rhythmeta.maimaid.core.data.RecommendationResponse

data class RecommendationUiState(
    val isLoading: Boolean = true,
    val response: RecommendationResponse = RecommendationResponse(),
)

class RecommendationViewModel(
    container: AppContainer,
) : ViewModel() {
    val state = container.recommendationRepository
        .observeRecommendations()
        .map {
            RecommendationUiState(
                isLoading = false,
                response = it,
            )
        }
        .onStart { emit(RecommendationUiState()) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = RecommendationUiState(),
        )

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(RecommendationViewModel::class.java))
            return RecommendationViewModel(container) as T
        }
    }
}
