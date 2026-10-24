package org.rhythmeta.maimaid.ui.song

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.LayoutCoordinates

/** Measures the large title inside its header before navigation transforms are applied. */
internal class SongDetailTitleBounds {
    private var header: LayoutCoordinates? = null
    private var title: LayoutCoordinates? = null
    var bottom by mutableStateOf<Float?>(null)
        private set

    fun updateHeader(coordinates: LayoutCoordinates) {
        header = coordinates
        update()
    }

    fun updateTitle(coordinates: LayoutCoordinates) {
        title = coordinates
        update()
    }

    private fun update() {
        val header = header?.takeIf { it.isAttached } ?: return
        val title = title?.takeIf { it.isAttached } ?: return
        bottom = header.localBoundingBoxOf(title, clipBounds = false).bottom
    }

    fun clear() {
        header = null
        title = null
        bottom = null
    }
}
