package org.rhythmeta.maimaid.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/** Debounces result updates while the input keeps its immediate text state. */
@Composable
internal fun rememberDebouncedSearch(query: String): State<String> = produceState(query, query) {
    if (query.isNotBlank()) delay(50.milliseconds)
    value = query
}
