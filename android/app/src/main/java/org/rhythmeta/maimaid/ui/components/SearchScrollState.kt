package org.rhythmeta.maimaid.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

/** Tracks search-result blur without changing the browsing page's title collapse or scroll state. */
internal class SearchScrollState {
    private var contentOffset by mutableFloatStateOf(0f)

    val contentScrolled: Boolean get() = contentOffset < -1f

    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            contentOffset = if (available.y > 0f) 0f else (contentOffset + consumed.y).coerceAtMost(0f)
            return Offset.Zero
        }
    }

    fun reset() {
        contentOffset = 0f
    }
}

@Composable
internal fun rememberSearchScrollState(expanded: Boolean, query: String): SearchScrollState {
    val state = remember { SearchScrollState() }
    LaunchedEffect(expanded, query) {
        if (expanded) state.reset()
    }
    return state
}
