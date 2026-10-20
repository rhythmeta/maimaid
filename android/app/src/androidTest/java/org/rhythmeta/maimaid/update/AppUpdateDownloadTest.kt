package org.rhythmeta.maimaid.update

import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.rhythmeta.maimaid.BuildConfig
import org.rhythmeta.maimaid.core.update.*

/** Opt-in end-to-end test. The APK URL must point to a newer, same-signed test build. */
class AppUpdateDownloadTest {
    @Test fun opensInstallerFromRestoredDownload() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val json = Json { ignoreUnknownKeys = true }
        val store = AppUpdateStateStore(context, json)
        val pending = requireNotNull(store.read()) { "No persisted update download" }
        val remote = object : AppUpdateRemoteSource {
            override suspend fun fetchManifest() = pending.manifest
            override suspend fun fetchHistory(url: String) = AppUpdateHistory(1, listOf(AppUpdateCommit(pending.manifest.sha, emptyList(), "Validate in-app update", "Device validation")))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val repository = withContext(Dispatchers.Main) {
                AppUpdateRepository(remote, store, AndroidAppUpdateDownloader(context), AndroidAppUpdateVerifier(context), scope,
                    BuildConfig.UPDATE_URL, context.packageName, BuildConfig.VERSION_CODE.toLong(), BuildConfig.GIT_SHA,
                    listOf("arm64-v8a"), android.os.Build.VERSION.SDK_INT)
            }
            val ready = withTimeout(120_000) { repository.state.first { it.phase == AppUpdatePhase.Ready || it.phase == AppUpdatePhase.Failed } }
            assertEquals("Restored APK was not installable: ${ready.failure}", AppUpdatePhase.Ready, ready.phase)
            val installer = ApkInstaller(context)
            assertTrue("Validation package lacks installer authorization", installer.canInstall())
            context.startActivity(installer.installIntent(withContext(Dispatchers.Main) { repository.verifiedApk() })
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } finally { scope.cancel() }
    }

    @Test fun downloadsVerifiesAndRestoresSystemDownload() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val url = arguments.getString("updateApkUrl")
        assumeTrue("Provide updateApkUrl to run the real download test", url != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val artifact = AppUpdateArtifact("arm64-v8a", requireNotNull(url), requireNotNull(arguments.getString("updateApkSize")).toLong(), requireNotNull(arguments.getString("updateApkSha256")))
        val manifest = AppUpdateManifest(1, context.packageName, BuildConfig.VERSION_NAME,
            requireNotNull(arguments.getString("updateVersion")).toLong(), 29, "main", "b".repeat(40), "Device validation", "2026-10-20T10:00:00Z",
            url.substringBeforeLast('/') + "/history.json", listOf(artifact))
        val json = Json { ignoreUnknownKeys = true }
        val store = AppUpdateStateStore(context, json)
        val downloader = AndroidAppUpdateDownloader(context)
        val verifier = AndroidAppUpdateVerifier(context)
        val remote = object : AppUpdateRemoteSource {
            override suspend fun fetchManifest() = manifest
            override suspend fun fetchHistory(url: String) = AppUpdateHistory(1, listOf(AppUpdateCommit(manifest.sha, emptyList(), "Validate in-app update", "Device validation")))
        }
        fun repository(scope: CoroutineScope) = AppUpdateRepository(remote, store, downloader, verifier, scope,
            BuildConfig.UPDATE_URL, context.packageName, BuildConfig.VERSION_CODE.toLong(), BuildConfig.GIT_SHA, listOf("arm64-v8a"), android.os.Build.VERSION.SDK_INT)
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val first = withContext(Dispatchers.Main) { repository(scope).also { it.check(); it.download() } }
            val completed = withTimeout(120_000) { first.state.first { it.phase == AppUpdatePhase.Ready || it.phase == AppUpdatePhase.Failed } }
            assertEquals("Download failure: ${completed.failure}", AppUpdatePhase.Ready, completed.phase)
            assertEquals(artifact.size, withContext(Dispatchers.Main) { first.verifiedApk().length() })
            assertNotNull(store.read())
            scope.cancel()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val restored = withContext(Dispatchers.Main) { repository(scope) }
            assertEquals(AppUpdatePhase.Ready, withTimeout(30_000) { restored.state.first { it.phase == AppUpdatePhase.Ready || it.phase == AppUpdatePhase.Failed } }.phase)
            context.getSharedPreferences("update_validation", 0).edit().putString("preserved", "profile-and-score-data-marker").commit()
            if (arguments.getString("openInstaller") == "true") {
                val installer = ApkInstaller(context)
                val intent = if (installer.canInstall()) installer.installIntent(withContext(Dispatchers.Main) { restored.verifiedApk() }) else installer.permissionIntent()
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } finally { scope.cancel() }
    }

    @Test fun replacementInstallPreservesApplicationData() {
        val expected = InstrumentationRegistry.getArguments().getString("expectedInstalledVersion")
        assumeTrue("Provide expectedInstalledVersion after the replacement install", expected != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(requireNotNull(expected).toLong(), context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode)
        assertEquals("profile-and-score-data-marker", context.getSharedPreferences("update_validation", 0).getString("preserved", null))
    }
}
