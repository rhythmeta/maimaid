package org.rhythmeta.maimaid.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SdgaCredentialTest {
    @Test
    fun parsesBareIdm() {
        val credential = SdgaCredentialParser.parse("012E112233445566")
        assertEquals(SdgaCredential.Idm("012E112233445566", null), credential)
    }

    @Test
    fun parsesIdmWithPmm() {
        val credential = SdgaCredentialParser.parse("abcd112233445566 0010a1a2a3a4a5a6")
        assertEquals(
            SdgaCredential.Idm("ABCD112233445566", "0010A1A2A3A4A5A6"),
            credential,
        )
    }

    @Test
    fun parsesAccessCode() {
        val credential = SdgaCredentialParser.parse("01035000000000000001")
        assertEquals(SdgaCredential.AccessCode("01035000000000000001", null), credential)
    }

    @Test
    fun parsesAccessCodeWithMifareUid() {
        val credential = SdgaCredentialParser.parse("01036000000000000002:89ab-cdef")
        assertEquals(
            SdgaCredential.AccessCode("01036000000000000002", "89ABCDEF"),
            credential,
        )
    }

    @Test
    fun stripsSeparatorsBeforeMatching() {
        assertEquals(
            SdgaCredential.Idm("012E112233445566", null),
            SdgaCredentialParser.parse(" 012E 1122-3344/5566 "),
        )
        assertEquals(
            SdgaCredential.AccessCode("01035000000000000001", null),
            SdgaCredentialParser.parse("0103 5000 0000 0000 0001"),
        )
    }

    @Test
    fun rejectsShapesThatDoNotMatchEitherFormat() {
        // An IDm followed by an 8-digit Mifare UID is neither an IDm pair nor an access code.
        assertNull(SdgaCredentialParser.parse("012E112233445566 89ABCDEF"))
        assertNull(SdgaCredentialParser.parse("01036000000000000002 89ABCD"))
        assertNull(SdgaCredentialParser.parse(""))
    }

    @Test
    fun aimeCardMayOmitThePmm() {
        val credential = SdgaCredential.Idm("012E112233445566", null)
        assertEquals(SdgaCredentialParser.DEFAULT_PMM, SdgaCredentialParser.pmmFor(credential))
        assertTrue(!SdgaCredentialParser.needsPmm("012e112233445566"))
    }

    @Test
    fun otherFelicMediaMustSupplyTheirOwnPmm() {
        assertTrue(SdgaCredentialParser.needsPmm("ABCD112233445566"))
        val error = runCatching {
            SdgaCredentialParser.pmmFor(SdgaCredential.Idm("ABCD112233445566", null))
        }.exceptionOrNull()
        assertEquals("sdga_pmm_required", (error as DirectImportException).code)
    }

    @Test
    fun normalizedValueKeepsTheOptionalField() {
        assertEquals(
            "0103600000000000000289ABCDEF",
            SdgaCredential.AccessCode("01036000000000000002", "89ABCDEF").normalized,
        )
    }
}
