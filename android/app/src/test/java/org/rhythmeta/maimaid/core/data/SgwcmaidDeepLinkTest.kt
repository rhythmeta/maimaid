package org.rhythmeta.maimaid.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SgwcmaidDeepLinkTest {
    @Test
    fun derivesSgwcmaidFromCabinetLink() {
        assertEquals(EXPECTED_CODE, SgwcmaidDeepLink.extractCode(CABINET_LINK))
    }

    @Test
    fun ignoresTheQueryParametersOfTheCabinetLink() {
        val bare = "https://wq.wahlap.net/qrcode/req/$CABINET_CODE.html"
        assertEquals(EXPECTED_CODE, SgwcmaidDeepLink.extractCode(bare))
        assertEquals(EXPECTED_CODE, SgwcmaidDeepLink.extractCode("$bare?l=1&t=x&d=y"))
    }

    @Test
    fun acceptsHttpAndMixedCaseLinks() {
        val link = "http://WQ.WAHLAP.NET/qrcode/req/${CABINET_CODE.lowercase()}.html"
        assertEquals(EXPECTED_CODE, SgwcmaidDeepLink.extractCode(link))
    }

    @Test
    fun acceptsAlreadyPrefixedCodeInThePath() {
        val link = "https://wq.wahlap.net/qrcode/req/$EXPECTED_CODE.html"
        assertEquals(EXPECTED_CODE, SgwcmaidDeepLink.extractCode(link))
    }

    @Test
    fun acceptsBareSgwcmaidAndTrimsWhitespace() {
        assertEquals(EXPECTED_CODE, SgwcmaidDeepLink.extractCode("  $EXPECTED_CODE\n"))
    }

    @Test
    fun acceptsAppLink() {
        assertEquals(EXPECTED_CODE, SgwcmaidDeepLink.extractCode("maimaid://sgwcmaid/$EXPECTED_CODE"))
        assertEquals(
            EXPECTED_CODE,
            SgwcmaidDeepLink.extractCode("maimaid://sgwcmaid/$CABINET_CODE"),
        )
    }

    @Test
    fun rejectsUnrelatedLinks() {
        for (input in listOf(
            null,
            "",
            "   ",
            "hello",
            "https://example.com/qrcode/req/$CABINET_CODE.html",
            "https://wq.wahlap.net/qrcode/$CABINET_CODE.html",
            "https://wq.wahlap.net/other/req/$CABINET_CODE.html",
            "https://wq.wahlap.net/qrcode/req/notacabinetcode.html",
            "https://wq.wahlap.net/qrcode/req/MAIDshort.html",
            "https://wq.wahlap.net/qrcode/req/$CABINET_CODE.htm",
            "maimaid://collection/$CABINET_CODE",
            "SGWCMAIDshort",
        )) {
            assertNull(input, SgwcmaidDeepLink.extractCode(input))
        }
    }

    @Test
    fun identifiesCabinetLinksEvenWithoutAUsableCode() {
        assertTrue(SgwcmaidDeepLink.isCabinetLink(CABINET_LINK))
        assertTrue(
            SgwcmaidDeepLink.isCabinetLink(
                "https://wq.wahlap.net/qrcode/req/broken.html?x=1",
            ),
        )
        assertFalse(SgwcmaidDeepLink.isCabinetLink("https://wq.wahlap.net/qrcode/other/x.html"))
        assertFalse(SgwcmaidDeepLink.isCabinetLink(EXPECTED_CODE))
        assertFalse(SgwcmaidDeepLink.isCabinetLink(null))
    }

    @Test
    fun rejectsOverlongInput() {
        assertNull(SgwcmaidDeepLink.extractCode("https://wq.wahlap.net/qrcode/req/$CABINET_CODE.html?" + "a".repeat(10_000)))
    }

    private companion object {
        const val CABINET_CODE =
            "MAID26102709152878D587E97AE285B966A8A455530D5D354AD319228F9612FB76B26E0A05AE6A98"
        const val EXPECTED_CODE = "SGWC$CABINET_CODE"
        const val CABINET_LINK =
            "https://wq.wahlap.net/qrcode/req/MAID26102709152878D587E97AE285B966A8A455530D5D354AD319228F9612FB76B26E0A05AE6A98.html" +
                "?l=1793064328" +
                "&t=E8889EE8908C4458202F20E4B8ADE4BA8CE88A82E5A58F20E799BBE585A5E4BA8CE7BBB4E7A081" +
                "&d=E68A8AE4B88BE696B9E4BA8CE7BBB4E7A081E5AFB9E58786E69CBAE58FB0E689ABE68F8FE5A484EFBC" +
                "8CE58FAFE794A8E69CBAE58FB0E69C89E38090E8889EE8908C4458E38091E5928CE38090E4B8ADE4BA8CE88A" +
                "82E5A58FE38091"
    }
}
