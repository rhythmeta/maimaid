package org.rhythmeta.maimaid.ui.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogContainerDragOffsetTest {
    @Test
    fun gestureEndsKeepTheCardOnItsPath() {
        assertEquals(0f, catalogContainerDragOffset(deltaY = 120f, progress = 0f), 0.0001f)
        assertEquals(0f, catalogContainerDragOffset(deltaY = 120f, progress = 1f), 0.0001f)
    }

    @Test
    fun followPeaksAtTheMiddleOfTheTransition() {
        val middle = catalogContainerDragOffset(deltaY = 120f, progress = 0.5f)
        assertEquals(120f * 0.35f, middle, 0.0001f)
        assertEquals(
            catalogContainerDragOffset(deltaY = 120f, progress = 0.25f),
            catalogContainerDragOffset(deltaY = 120f, progress = 0.75f),
            0.0001f,
        )
        assertTrue(catalogContainerDragOffset(deltaY = 120f, progress = 0.25f) < middle)
    }

    @Test
    fun followTracksTheFingerSignAndClampsProgress() {
        assertTrue(catalogContainerDragOffset(deltaY = -80f, progress = 0.5f) < 0f)
        assertEquals(
            catalogContainerDragOffset(deltaY = 80f, progress = 1f),
            catalogContainerDragOffset(deltaY = 80f, progress = 2f),
            0.0001f,
        )
        assertEquals(
            catalogContainerDragOffset(deltaY = 80f, progress = 0f),
            catalogContainerDragOffset(deltaY = 80f, progress = -2f),
            0.0001f,
        )
    }
}
