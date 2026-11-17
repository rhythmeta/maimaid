package org.rhythmeta.maimaid.core.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class ChartPlaybackTest {
    @Test
    fun audioCacheRequiresMatchingSizeAndSha256() {
        val file = File.createTempFile("maimaid-audio-cache", ".mp3")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(byteArrayOf(1, 2, 3))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }

            assertTrue(ChartAudioCacheIntegrity.matches(file, 3, digest))
            assertEquals(false, ChartAudioCacheIntegrity.matches(file, 3, "0".repeat(64)))
            assertEquals(false, ChartAudioCacheIntegrity.matches(file, 2, digest))
        } finally {
            file.delete()
        }
    }

}
