package org.rhythmeta.maimaid.core.update

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class AppUpdatePolicyTest {
    @Test fun sameVersionNameCanHaveANewerBuildButRepeatedCommitCannot() {
        val manifest = manifest()
        assertTrue(AppUpdatePolicy.isNewer(manifest, 10, sha(1)))
        assertFalse(AppUpdatePolicy.isNewer(manifest, 11, sha(1)))
        assertFalse(AppUpdatePolicy.isNewer(manifest, 12, sha(1)))
        assertFalse(AppUpdatePolicy.isNewer(manifest, 10, manifest.sha))
    }

    @Test fun choosesAbiInDevicePreferenceOrderAndRejectsIncompatibleDevices() {
        val manifest = manifest().copy(artifacts = listOf(artifact("armeabi-v7a"), artifact("arm64-v8a")))
        assertEquals("arm64-v8a", AppUpdatePolicy.selectArtifact(manifest, listOf("arm64-v8a", "armeabi-v7a"), 36).abi)
        expectFailure(AppUpdateFailure.UnsupportedDevice) { AppUpdatePolicy.selectArtifact(manifest, listOf("x86"), 36) }
        expectFailure(AppUpdateFailure.UnsupportedDevice) { AppUpdatePolicy.selectArtifact(manifest, listOf("arm64-v8a"), 28) }
    }

    @Test fun validatesVersionedManifestAndTrustedDownloads() {
        AppUpdatePolicy.validate(manifest(), UPDATE_URL, PACKAGE)
        val invalid = listOf(
            manifest().copy(schemaVersion = 2), manifest().copy(packageName = "other"),
            manifest().copy(branch = "feature"), manifest().copy(sha = "bad"), manifest().copy(builtAt = "yesterday"),
            manifest().copy(historyUrl = "https://example.org/android/ci/builds/1-1/history.json"),
            manifest().copy(artifacts = listOf(artifact().copy(url = "http://assets.rhythmeta.org/android/ci/builds/1-1/app.apk"))),
            manifest().copy(artifacts = listOf(artifact().copy(sha256 = "bad"))),
            manifest().copy(artifacts = listOf(artifact(), artifact())),
        )
        invalid.forEach { expectFailure(AppUpdateFailure.InvalidManifest) { AppUpdatePolicy.validate(it, UPDATE_URL, PACKAGE) } }
    }

    @Test fun computesCommitDifferenceAcrossMergesExcludingAlreadyInstalledAncestry() {
        val history = AppUpdateHistory(1, listOf(commit(4, 3, 2), commit(3, 1), commit(2, 1), commit(1)))
        val changes = AppUpdatePolicy.changes(history, sha(2), sha(4))
        assertEquals(listOf(sha(4), sha(3)), changes.commits.map { it.sha })
        assertFalse(changes.incomplete)
    }

    @Test fun limitsChangesAndMarksMissingOrOldBase() {
        val history = AppUpdateHistory(1, (30 downTo 1).map { commit(it, *(if (it > 1) intArrayOf(it - 1) else intArrayOf())) })
        val changes = AppUpdatePolicy.changes(history, sha(1), sha(30))
        assertEquals(20, changes.commits.size)
        assertTrue(changes.incomplete)
        val missingBase = AppUpdatePolicy.changes(history, sha(99), sha(30))
        assertTrue(missingBase.incomplete)
        assertEquals(emptyList<AppUpdateCommit>(), AppUpdatePolicy.changes(history, sha(30), sha(30)).commits)
    }

    @Test fun hashValidationRejectsTruncationAndSameLengthCorruption() = runTest {
        val file = File.createTempFile("update", ".apk")
        try {
            file.writeText("valid APK bytes")
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
            val artifact = artifact().copy(size = file.length(), sha256 = digest)
            AppUpdateFileIntegrity.verify(file, artifact)
            file.writeText("invalid content")
            try { AppUpdateFileIntegrity.verify(file, artifact); fail("Corrupt file accepted") }
            catch (error: AppUpdateException) { assertEquals(AppUpdateFailure.Integrity, error.failure) }
            file.writeText("short")
            try { AppUpdateFileIntegrity.verify(file, artifact); fail("Truncated file accepted") }
            catch (error: AppUpdateException) { assertEquals(AppUpdateFailure.Integrity, error.failure) }
        } finally { file.delete() }
    }

    private fun expectFailure(expected: AppUpdateFailure, action: () -> Unit) {
        try { action(); fail("Expected $expected") }
        catch (error: AppUpdateException) { assertEquals(expected, error.failure) }
    }

    private fun commit(value: Int, vararg parents: Int) = AppUpdateCommit(sha(value), parents.map(::sha), "Commit $value", "Author")

    companion object {
        const val UPDATE_URL = "https://assets.rhythmeta.org/android/ci/main/latest.json"
        const val PACKAGE = "org.rhythmeta.maimaid"
        fun sha(value: Int) = value.toString(16).padStart(40, '0')
        fun artifact(abi: String = "arm64-v8a") = AppUpdateArtifact(abi, "https://assets.rhythmeta.org/android/ci/builds/1-1/maimaid-$abi.apk", 10, "a".repeat(64))
        fun manifest() = AppUpdateManifest(1, PACKAGE, "1.2.8", 11, 29, "main", sha(2), "Author", "2026-10-20T10:00:00Z", "https://assets.rhythmeta.org/android/ci/builds/1-1/history.json", listOf(artifact()))
    }
}
