package org.rhythmeta.maimaid.core.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/** Owns the persistent system download independently of screens and Activity recreation. */
class AppUpdateRepository(
    private val remote: AppUpdateRemoteSource,
    private val store: AppUpdateStore,
    private val downloader: AppUpdateDownloader,
    private val verifier: AppUpdateVerifier,
    private val scope: CoroutineScope,
    private val updateUrl: String,
    private val packageName: String,
    private val installedCode: Long,
    private val installedSha: String,
    private val supportedAbis: List<String>,
    private val sdk: Int,
) {
    private val mutableState = MutableStateFlow(AppUpdateState())
    val state = mutableState.asStateFlow()
    private val checkMutex = Mutex()
    private val downloadMutex = Mutex()
    private var automaticCheckStarted = false
    private var pending: PendingAppUpdate? = null
    private var monitor: Job? = null
    private val restored = scope.async { restoreDownload() }

    suspend fun check(automatic: Boolean = false): Boolean {
        restored.await()
        if (automatic && automaticCheckStarted) return false
        if (automatic) automaticCheckStarted = true
        if (!automatic && pending != null && state.value.phase == AppUpdatePhase.Failed) cancel()
        if (!checkMutex.tryLock()) return false
        try {
            if (pending != null || state.value.phase in activePhases) return true
            mutableState.value = AppUpdateState(phase = AppUpdatePhase.Checking)
            val manifest = remote.fetchManifest()
            AppUpdatePolicy.validate(manifest, updateUrl, packageName)
            if (!AppUpdatePolicy.isNewer(manifest, installedCode, installedSha)) {
                mutableState.value = AppUpdateState(phase = AppUpdatePhase.Latest)
                return false
            }
            val artifact = AppUpdatePolicy.selectArtifact(manifest, supportedAbis, sdk)
            mutableState.value = AppUpdateState(AppUpdatePhase.Available, manifest, artifact)
            loadChanges(manifest)
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableState.value = AppUpdateState(phase = AppUpdatePhase.Failed, failure = failure(error, AppUpdateFailure.Network))
            return false
        } finally {
            checkMutex.unlock()
        }
    }

    suspend fun download() {
        restored.await()
        downloadMutex.withLock {
            val state = state.value
            val manifest = state.manifest ?: return
            val artifact = state.artifact ?: return
            if (state.phase in activePhases || state.phase == AppUpdatePhase.Ready) return
            try {
                monitor?.cancelAndJoin()
                pending?.let { downloader.remove(it) }
                pending = null
                store.save(null)
                val id = downloader.enqueue(manifest, artifact)
                val update = PendingAppUpdate(manifest, artifact, id)
                pending = update
                try {
                    store.save(update)
                } catch (error: Exception) {
                    downloader.remove(update)
                    pending = null
                    throw error
                }
                mutableState.value = state.copy(phase = AppUpdatePhase.Downloading, failure = null, downloadedBytes = 0, totalBytes = artifact.size)
                startMonitor(update)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = state.copy(phase = AppUpdatePhase.Failed, failure = failure(error, AppUpdateFailure.Download))
            }
        }
    }

    suspend fun cancel() {
        restored.await()
        downloadMutex.withLock {
            try {
                monitor?.cancelAndJoin()
                monitor = null
                pending?.let { downloader.remove(it) }
                pending = null
                store.save(null)
                mutableState.value = state.value.copy(phase = AppUpdatePhase.Available, failure = null, downloadedBytes = 0)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = state.value.copy(phase = AppUpdatePhase.Failed, failure = failure(error, AppUpdateFailure.Storage))
            }
        }
    }

    suspend fun verifiedApk(): File {
        restored.await()
        val update = pending ?: throw AppUpdateException(AppUpdateFailure.Install)
        return try {
            val file = downloader.file(update)
            verifier.verify(file, update.manifest, update.artifact)
            file
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutableState.value = state.value.copy(phase = AppUpdatePhase.Failed, failure = failure(error, AppUpdateFailure.Install))
            throw error
        }
    }

    fun installFailed() {
        // Keep the verified file available when the installer cannot be opened.
        mutableState.value = state.value.copy(failure = AppUpdateFailure.Install)
    }

    private suspend fun restoreDownload() {
        try {
            val update = store.read() ?: return
            // Never trust restored paths or metadata without revalidation.
            AppUpdatePolicy.validate(update.manifest, updateUrl, packageName)
            require(update.downloadId > 0 && update.artifact in update.manifest.artifacts)
            if (!AppUpdatePolicy.isNewer(update.manifest, installedCode, installedSha)) {
                downloader.remove(update)
                store.save(null)
                return
            }
            AppUpdatePolicy.selectArtifact(update.manifest, supportedAbis, sdk)
            if (update.artifact.abi !in supportedAbis) throw AppUpdateException(AppUpdateFailure.UnsupportedDevice)
            pending = update
            mutableState.value = AppUpdateState(AppUpdatePhase.Downloading, update.manifest, update.artifact, totalBytes = update.artifact.size)
            startMonitor(update)
            loadChanges(update.manifest)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            pending = null
            try { store.save(null) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* Report the storage failure below. */ }
            mutableState.value = AppUpdateState(phase = AppUpdatePhase.Failed, failure = failure(error, AppUpdateFailure.Download))
        }
    }

    private fun startMonitor(update: PendingAppUpdate) {
        monitor = scope.launch {
            try {
                while (true) {
                    val snapshot = downloader.query(update.downloadId)
                    mutableState.value = state.value.copy(downloadedBytes = snapshot.downloadedBytes,
                        totalBytes = snapshot.totalBytes.takeIf { it > 0 } ?: update.artifact.size)
                    when (snapshot.status) {
                        AppDownloadSnapshot.Status.Running -> delay(1_000.milliseconds)
                        AppDownloadSnapshot.Status.Complete -> {
                            mutableState.value = state.value.copy(phase = AppUpdatePhase.Verifying)
                            verifier.verify(downloader.file(update), update.manifest, update.artifact)
                            mutableState.value = state.value.copy(phase = AppUpdatePhase.Ready, failure = null)
                            return@launch
                        }
                        AppDownloadSnapshot.Status.Failed, AppDownloadSnapshot.Status.Missing ->
                            throw AppUpdateException(snapshot.failure)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.value = state.value.copy(phase = AppUpdatePhase.Failed, failure = failure(error, AppUpdateFailure.Download))
            }
        }
    }

    private suspend fun loadChanges(manifest: AppUpdateManifest) {
        val changes = try {
            AppUpdatePolicy.changes(remote.fetchHistory(manifest.historyUrl), installedSha, manifest.sha)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            AppUpdateChanges(incomplete = true)
        }
        if (state.value.manifest?.sha == manifest.sha) mutableState.value = state.value.copy(changes = changes)
    }

    private fun failure(error: Exception, fallback: AppUpdateFailure) = (error as? AppUpdateException)?.failure ?: fallback

    private companion object {
        val activePhases = setOf(AppUpdatePhase.Downloading, AppUpdatePhase.Verifying)
    }
}
