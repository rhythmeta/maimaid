package org.rhythmeta.maimaid.core.data

import java.util.Locale
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionNamesTest {
    @Test
    fun mapsTheCatalogAlpha3Codes() {
        assertEquals("SG", RegionNames.iso2("SGP"))
        assertEquals("HK", RegionNames.iso2("hkg"))
        assertEquals("TW", RegionNames.iso2("TWN"))
        assertEquals("KR", RegionNames.iso2("KOR"))
        assertNull(RegionNames.iso2("ZZZ"))
        assertNull(RegionNames.iso2(null))
    }

    @Test
    fun acceptsAlpha2FromGeolocation() {
        assertEquals("JP", RegionNames.iso2("jp"))
        assertEquals("SG", RegionNames.iso2("SG"))
    }

    @Test
    fun rendersLocalizedCountryNames() {
        assertEquals("Singapore", RegionNames.displayName("SGP", locale = Locale.ENGLISH))
        val chinese = RegionNames.displayName("SGP", locale = Locale.SIMPLIFIED_CHINESE)
        val japanese = RegionNames.displayName("SGP", locale = Locale.JAPANESE)
        assertNotEquals("SGP", chinese)
        assertNotEquals("SGP", japanese)
        assertTrue(chinese.isNotBlank() && japanese.isNotBlank())
    }

    @Test
    fun unknownCodesFallBackToTheRawValue() {
        assertEquals("XYZ", RegionNames.displayName("XYZ"))
        assertEquals("XYZ", RegionNames.displayName("XYZ", fallback = " "))
    }
}

class EgressIpTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesCloudflareTrace() {
        val info = EgressIpService.parseTrace(
            """
            fl=24f12
            h=www.cloudflare.com
            ip=203.0.113.9
            ts=1764000000.1
            visit_scheme=https
            loc=JP
            tls=TLSv1.3
            """.trimIndent(),
        )
        assertEquals("203.0.113.9", info?.address)
        assertEquals("JP", info?.countryCode)
    }

    @Test
    fun traceWithoutAnAddressIsRejected() {
        assertNull(EgressIpService.parseTrace("fl=1\nloc=JP\n"))
        assertNull(EgressIpService.parseTrace(""))
    }

    @Test
    fun parsesTheJsonProviderShape() {
        val info = EgressIpService.parseGeoJson(
            """{"ip":"198.51.100.7","country_code":"sg","country":"Singapore"}""",
            json,
        )
        assertEquals("198.51.100.7", info?.address)
        assertEquals("SG", info?.countryCode)
    }

    @Test
    fun jsonWithoutCountryStillReportsTheAddress() {
        val info = EgressIpService.parseGeoJson("""{"ip":"198.51.100.7"}""", json)
        assertEquals("198.51.100.7", info?.address)
        assertNull(info?.countryCode)
    }

    @Test
    fun readsTheCarrierFromTheNestedIphoneShape() {
        val info = EgressIpService.parseGeoJson(
            """{"ip":"154.3.40.207","country_code":"US","connection":{"asn":174,"isp":"Cogent Communications, LLC","org":"DigiRDP"}}""",
            json,
        )
        assertEquals("Cogent Communications, LLC", info?.isp)
        // Cogent is a transit carrier, not proof of a datacenter; keep it neutral.
        assertEquals(EgressKind.Unknown, info?.kind)
    }

    @Test
    fun prefersTheOrganisationWhenTheProviderFlattensIt() {
        val info = EgressIpService.parseGeoJson(
            """{"ip":"203.0.113.5","country_code":"JP","isp":"Cogent Communications","organization":"DigiRDP"}""",
            json,
        )
        assertEquals("DigiRDP", info?.isp)
        assertEquals(EgressKind.Hosting, info?.kind)
    }

    @Test
    fun classifiesCarriers() {
        assertEquals(EgressKind.Hosting, EgressIpService.classify("Amazon Data Services"))
        assertEquals(EgressKind.Hosting, EgressIpService.classify("Hetzner Online GmbH"))
        assertEquals(EgressKind.Residential, EgressIpService.classify("China Unicom"))
        assertEquals(EgressKind.Residential, EgressIpService.classify("KDDI Corporation"))
        assertEquals(EgressKind.Unknown, EgressIpService.classify("Cogent Communications, LLC"))
        assertEquals(EgressKind.Unknown, EgressIpService.classify(null))
    }

    @Test
    fun malformedJsonIsIgnored() {
        assertNull(EgressIpService.parseGeoJson("<html>blocked</html>", json))
        assertNull(EgressIpService.parseGeoJson("""{"country_code":"SG"}""", json))
    }
}
