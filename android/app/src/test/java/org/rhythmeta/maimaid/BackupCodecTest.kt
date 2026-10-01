package org.rhythmeta.maimaid

import org.junit.Assert.*
import org.junit.Test
import org.rhythmeta.maimaid.core.backup.BackupCodec
import org.rhythmeta.maimaid.core.data.PendingAppLogin
import java.io.File

class BackupCodecTest {
    private fun fixture() = File("../../shared/fixtures/maimaid-v1.pb.gz").readBytes()
    @Test fun protobufFixtureIsPortableAndRoundTrips() {
        val decoded = BackupCodec.decode(fixture())
        assertEquals("Test 玩家", decoded.profilesList.single().name)
        assertEquals(100.1234, decoded.scoresList.single().achievement, 0.0000001)
        assertEquals(decoded, BackupCodec.decode(BackupCodec.encode(decoded)))
    }
    @Test fun rejectsAnotherGameAndBrokenReferencesBeforeRestore() {
        val snapshot = BackupCodec.decode(fixture())
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.validate(snapshot.toBuilder().setGame("chunithmd").build()) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.validate(snapshot.toBuilder().clearProfiles().build()) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(fixture(), 1) }
        assertThrows(Exception::class.java) { BackupCodec.decode(fixture().copyOf(16)) }
    }
    @Test fun appLoginRejectsWrongStateAndExpiredRequest() {
        val pending = PendingAppLogin.create()
        assertEquals(43, pending.challenge.length)
        assertTrue(pending.accepts(pending.state, pending.createdAt))
        assertFalse(pending.accepts("different", pending.createdAt))
        assertFalse(pending.accepts(pending.state, pending.createdAt + 1_800_001))
    }
}
