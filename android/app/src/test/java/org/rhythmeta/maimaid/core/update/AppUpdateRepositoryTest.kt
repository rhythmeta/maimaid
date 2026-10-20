package org.rhythmeta.maimaid.core.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import org.rhythmeta.maimaid.core.update.AppUpdatePolicyTest.Companion.PACKAGE
import org.rhythmeta.maimaid.core.update.AppUpdatePolicyTest.Companion.UPDATE_URL
import org.rhythmeta.maimaid.core.update.AppUpdatePolicyTest.Companion.artifact
import org.rhythmeta.maimaid.core.update.AppUpdatePolicyTest.Companion.manifest
import org.rhythmeta.maimaid.core.update.AppUpdatePolicyTest.Companion.sha

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateRepositoryTest {
    @Test fun automaticChecksOnceButManualCheckCanRefresh() = runTest {
        val fixture = Fixture(this)
        fixture.repository.check(automatic = true)
        fixture.repository.check(automatic = true)
        assertEquals(1, fixture.remote.checks)
        fixture.repository.check()
        assertEquals(2, fixture.remote.checks)
        assertEquals(AppUpdatePhase.Available, fixture.repository.state.value.phase)
    }

    @Test fun concurrentChecksShareOneRequestAndHistoryFailureDoesNotBlockUpdate() = runTest {
        val fixture = Fixture(this)
        fixture.remote.gate = CompletableDeferred()
        fixture.remote.historyFails = true
        val first = launch { fixture.repository.check() }
        runCurrent()
        val second = launch { fixture.repository.check() }
        runCurrent()
        assertEquals(1, fixture.remote.checks)
        fixture.remote.gate?.complete(Unit)
        first.join(); second.join()
        assertEquals(AppUpdatePhase.Available, fixture.repository.state.value.phase)
        assertTrue(fixture.repository.state.value.changes.incomplete)
    }

    @Test fun downloadIsPersistentAndOnlyVerifiedFilesBecomeReady() = runTest {
        val fixture = Fixture(this)
        fixture.repository.check()
        fixture.repository.download()
        runCurrent()
        assertEquals(AppUpdatePhase.Downloading, fixture.repository.state.value.phase)
        assertNotNull(fixture.store.pending)
        fixture.downloader.snapshot = AppDownloadSnapshot(AppDownloadSnapshot.Status.Complete, 10, 10)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(AppUpdatePhase.Ready, fixture.repository.state.value.phase)
        assertEquals(1, fixture.verifications)
        fixture.repository.verifiedApk()
        assertEquals(2, fixture.verifications)
    }

    @Test fun restoresCompletedDownloadAndRevalidatesIt() = runTest {
        val fixture = Fixture(this, PendingAppUpdate(manifest(), artifact(), 99))
        fixture.downloader.snapshot = AppDownloadSnapshot(AppDownloadSnapshot.Status.Complete, 10, 10)
        runCurrent()
        assertEquals(AppUpdatePhase.Ready, fixture.repository.state.value.phase)
        assertEquals(1, fixture.verifications)
        assertEquals(0, fixture.downloader.enqueues)
    }

    @Test fun completedUpgradeRemovesOldDownloadAndPersistedState() = runTest {
        val fixture = Fixture(this, PendingAppUpdate(manifest(), artifact(), 99), installedCode = 11)
        runCurrent()
        assertNull(fixture.store.pending)
        assertEquals(listOf(99L), fixture.downloader.removed)
        assertEquals(AppUpdatePhase.Idle, fixture.repository.state.value.phase)
    }

    @Test fun cancelRemovesTaskAndAllowsFreshRetry() = runTest {
        val fixture = Fixture(this)
        fixture.repository.check(); fixture.repository.download(); runCurrent()
        fixture.repository.cancel()
        assertNull(fixture.store.pending)
        assertEquals(AppUpdatePhase.Available, fixture.repository.state.value.phase)
        fixture.repository.download(); runCurrent()
        assertEquals(2, fixture.downloader.enqueues)
        assertEquals(1L, fixture.downloader.removed.first())
    }

    @Test fun failedDownloadCanBeRetriedWithoutDuplicateTasks() = runTest {
        val fixture = Fixture(this)
        fixture.repository.check()
        fixture.downloader.snapshot = AppDownloadSnapshot(AppDownloadSnapshot.Status.Failed, failure = AppUpdateFailure.Storage)
        fixture.repository.download(); runCurrent()
        assertEquals(AppUpdateFailure.Storage, fixture.repository.state.value.failure)
        fixture.downloader.snapshot = AppDownloadSnapshot(AppDownloadSnapshot.Status.Running)
        fixture.repository.download(); runCurrent()
        fixture.repository.download()
        assertEquals(2, fixture.downloader.enqueues)
        assertEquals(listOf(1L), fixture.downloader.removed)
    }

    @Test fun incompatibleSignatureNeverBecomesInstallable() = runTest {
        val fixture = Fixture(this)
        fixture.verificationFailure = AppUpdateFailure.Signature
        fixture.repository.check()
        fixture.downloader.snapshot = AppDownloadSnapshot(AppDownloadSnapshot.Status.Complete)
        fixture.repository.download(); runCurrent()
        assertEquals(AppUpdatePhase.Failed, fixture.repository.state.value.phase)
        assertEquals(AppUpdateFailure.Signature, fixture.repository.state.value.failure)
    }

    private class Fixture(scope: TestScope, initial: PendingAppUpdate? = null, installedCode: Long = 10) {
        val store = Store(initial)
        val remote = Remote()
        val downloader = Downloader()
        var verifications = 0
        var verificationFailure: AppUpdateFailure? = null
        val repository = AppUpdateRepository(remote, store, downloader,
            AppUpdateVerifier { _, _, _ -> verifications++; verificationFailure?.let { throw AppUpdateException(it) } },
            scope.backgroundScope, UPDATE_URL, PACKAGE, installedCode, sha(1), listOf("arm64-v8a"), 36)
    }

    private class Store(var pending: PendingAppUpdate?) : AppUpdateStore {
        override suspend fun read() = pending
        override suspend fun save(update: PendingAppUpdate?) { pending = update }
    }
    private class Remote : AppUpdateRemoteSource {
        var checks = 0
        var gate: CompletableDeferred<Unit>? = null
        var historyFails = false
        override suspend fun fetchManifest(): AppUpdateManifest { checks++; gate?.await(); return manifest() }
        override suspend fun fetchHistory(url: String): AppUpdateHistory {
            if (historyFails) error("offline")
            return AppUpdateHistory(1, listOf(AppUpdateCommit(sha(2), listOf(sha(1)), "Changes", "Author"), AppUpdateCommit(sha(1), emptyList(), "Old", "Author")))
        }
    }
    private class Downloader : AppUpdateDownloader {
        var enqueues = 0
        val removed = mutableListOf<Long>()
        var snapshot = AppDownloadSnapshot(AppDownloadSnapshot.Status.Running)
        override suspend fun enqueue(manifest: AppUpdateManifest, artifact: AppUpdateArtifact): Long { enqueues++; return enqueues.toLong() }
        override suspend fun query(id: Long) = snapshot
        override suspend fun remove(update: PendingAppUpdate) { removed.add(update.downloadId) }
        override fun file(update: PendingAppUpdate) = File("unused-test.apk")
    }
}
