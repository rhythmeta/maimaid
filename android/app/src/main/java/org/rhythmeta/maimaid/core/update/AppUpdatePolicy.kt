package org.rhythmeta.maimaid.core.update

import java.net.URI
import java.time.Instant

object AppUpdatePolicy {
    private val shaPattern = Regex("[a-f0-9]{40}")
    private val digestPattern = Regex("[a-f0-9]{64}")
    private val supportedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    fun validate(manifest: AppUpdateManifest, updateUrl: String, packageName: String) {
        val base = URI(updateUrl)
        fun trusted(url: String): Boolean = runCatching {
            val target = URI(url)
            target.scheme == "https" && target.host == base.host && target.port == base.port &&
                target.userInfo == null && target.fragment == null && target.query == null &&
                target.path.startsWith("/android/ci/builds/") && !target.path.contains("..")
        }.getOrDefault(false)
        val valid = manifest.schemaVersion == 1 && manifest.packageName == packageName &&
            manifest.branch == "main" && manifest.versionCode > 0 && manifest.versionName.isNotBlank() &&
            manifest.minSdk > 0 && shaPattern.matches(manifest.sha) &&
            runCatching { Instant.parse(manifest.builtAt) }.isSuccess && trusted(manifest.historyUrl) &&
            manifest.artifacts.isNotEmpty() && manifest.artifacts.map { it.abi }.distinct().size == manifest.artifacts.size &&
            manifest.artifacts.all { it.abi in supportedAbis && it.size > 0 && digestPattern.matches(it.sha256) && trusted(it.url) }
        if (!valid) throw AppUpdateException(AppUpdateFailure.InvalidManifest)
    }

    fun isNewer(manifest: AppUpdateManifest, installedCode: Long, installedSha: String): Boolean =
        manifest.versionCode > installedCode && manifest.sha != installedSha

    fun selectArtifact(manifest: AppUpdateManifest, abis: List<String>, sdk: Int): AppUpdateArtifact {
        if (manifest.minSdk > sdk) throw AppUpdateException(AppUpdateFailure.UnsupportedDevice)
        return abis.firstNotNullOfOrNull { abi -> manifest.artifacts.firstOrNull { it.abi == abi } }
            ?: throw AppUpdateException(AppUpdateFailure.UnsupportedDevice)
    }

    fun changes(history: AppUpdateHistory, installedSha: String, latestSha: String): AppUpdateChanges {
        if (history.schemaVersion != 1 || history.commits.map { it.sha }.distinct().size != history.commits.size ||
            history.commits.any { !shaPattern.matches(it.sha) || it.parents.any { parent -> !shaPattern.matches(parent) } }) {
            throw AppUpdateException(AppUpdateFailure.InvalidManifest)
        }
        val commits = history.commits.associateBy { it.sha }
        fun ancestors(sha: String): Set<String> {
            val seen = mutableSetOf<String>()
            val queue = ArrayDeque<String>()
            queue.add(sha)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                if (seen.add(current)) commits[current]?.parents?.forEach(queue::addLast)
            }
            return seen
        }
        if (latestSha !in commits) throw AppUpdateException(AppUpdateFailure.InvalidManifest)
        val target = ancestors(latestSha)
        val knownBase = installedSha in commits
        val excluded = if (knownBase) ancestors(installedSha) else emptySet()
        val changes = history.commits.filter { it.sha in target && it.sha !in excluded }
        return AppUpdateChanges(changes.take(20), !knownBase || changes.size > 20 || target.any { it !in commits })
    }

    fun compareUrl(installedSha: String, latestSha: String): String =
        if (shaPattern.matches(installedSha) && shaPattern.matches(latestSha)) {
            "https://github.com/rhythmeta/maimaid/compare/$installedSha...$latestSha"
        } else {
            "https://github.com/rhythmeta/maimaid/commits/main/"
        }
}
