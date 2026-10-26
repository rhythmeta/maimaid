package org.rhythmeta.maimaid.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtogameSessionStoreTest {
    @Test
    fun `captures a bearer session and trims surrounding whitespace`() {
        val store = OtogameSessionStore()

        assertTrue(store.capture("  Bearer v2.local.token  "))
        assertEquals("Bearer v2.local.token", store.authorizationHeader.value)
    }

    @Test
    fun `ignores headers that are not bearer credentials`() {
        val store = OtogameSessionStore()

        assertFalse(store.capture("Basic abc"))
        assertFalse(store.capture("Bearer"))
        assertFalse(store.capture("Bearer two tokens"))
        assertFalse(store.capture(""))
        assertNull(store.authorizationHeader.value)
    }

    @Test
    fun `reports only session replacements as new captures`() {
        val store = OtogameSessionStore()

        assertTrue(store.capture("Bearer first"))
        assertFalse(store.capture("Bearer first"))
        assertTrue(store.capture("Bearer second"))
        assertEquals("Bearer second", store.authorizationHeader.value)
    }

    @Test
    fun `clear resets the captured session`() {
        val store = OtogameSessionStore()
        store.capture("Bearer first")

        store.clear()

        assertNull(store.authorizationHeader.value)
        assertTrue(store.capture("Bearer first"))
    }
}
