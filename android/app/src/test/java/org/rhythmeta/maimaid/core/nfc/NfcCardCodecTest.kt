package org.rhythmeta.maimaid.core.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcCardCodecTest {
    private val uid = byteArrayOf(0x12, 0xAB.toByte(), 0x34, 0xCD.toByte())
    private val sampleAccess = "01036000000000000002"

    @Test
    fun felicaPublishesIdmAndPmm() {
        val idm = byteArrayOf(0x01, 0x2E, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
        val pmm = byteArrayOf(0x00, 0xF1.toByte(), 0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
        val result = NfcCardCodec.decode(idm, isFelica = true, manufacturer = pmm)
        assertEquals(NfcCardRead.Credential("012E112233445566", "00F1112233445566"), result)
    }

    @Test
    fun felicaWithoutPmmStillYieldsTheIdm() {
        val idm = byteArrayOf(0x01, 0x2E, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
        assertEquals(
            NfcCardRead.Credential("012E112233445566", null),
            NfcCardCodec.decode(idm, isFelica = true, manufacturer = byteArrayOf(0)),
        )
        assertEquals(
            NfcCardRead.Credential("012E112233445566", null),
            NfcCardCodec.decode(idm, isFelica = true),
        )
    }

    @Test
    fun aFelicaIdMustBeEightBytes() {
        assertEquals(
            NfcCardRead.Unsupported,
            NfcCardCodec.decode(byteArrayOf(1, 2, 3), isFelica = true),
        )
    }

    @Test
    fun mifareUidIsRememberedWhenNoAccessCodeIsReadable() {
        assertEquals(
            NfcCardRead.MifareUid("12AB34CD"),
            NfcCardCodec.decode(uid, isFelica = false, isMifareClassic = true),
        )
    }

    @Test
    fun aPlainNdefTextRecordCompletesTheCredential() {
        val result = NfcCardCodec.decode(
            uid,
            isFelica = false,
            isMifareClassic = true,
            ndefTextPayloads = listOf(ndefText("AccessCode: $sampleAccess")),
        )
        assertEquals(NfcCardRead.Credential(sampleAccess, "12AB34CD"), result)

        assertEquals(
            NfcCardRead.Credential(sampleAccess, "12AB34CD"),
            NfcCardCodec.decode(
                uid,
                isFelica = false,
                isMifareClassic = true,
                ndefTextPayloads = listOf(ndefText(sampleAccess)),
            ),
        )
    }

    @Test
    fun ambiguousOrInvalidNdefTextFallsBackToTheUid() {
        val invalid = byteArrayOf(0x1F) + "en$sampleAccess".encodeToByteArray()
        assertEquals(
            NfcCardRead.MifareUid("12AB34CD"),
            NfcCardCodec.decode(
                uid,
                isFelica = false,
                isMifareClassic = true,
                ndefTextPayloads = listOf(invalid),
            ),
        )
        assertEquals(
            NfcCardRead.Unsupported,
            NfcCardCodec.decode(
                uid,
                isFelica = false,
                ndefTextPayloads = listOf(ndefText(sampleAccess), ndefText("30000000000000000003")),
            ),
        )
    }

    @Test
    fun unknownTechnologiesAreUnsupported() {
        assertEquals(
            NfcCardRead.Unsupported,
            NfcCardCodec.decode(uid + byteArrayOf(1), isFelica = false, isMifareClassic = true),
        )
        assertEquals(NfcCardRead.Unsupported, NfcCardCodec.decode(uid, isFelica = false))
    }

    @Test
    fun onlyABareAccessCodeAcceptsAScannedUid() {
        assertTrue(NfcCardCodec.isBareAccessCode(sampleAccess))
        assertTrue(NfcCardCodec.isBareAccessCode("  01035000000000000001 "))
        assertFalse(NfcCardCodec.isBareAccessCode("012E112233445566"))
        assertFalse(NfcCardCodec.isBareAccessCode("$sampleAccess 12AB34CD"))
    }

    private fun ndefText(text: String) = byteArrayOf(0x02) + "en$text".encodeToByteArray()
}
