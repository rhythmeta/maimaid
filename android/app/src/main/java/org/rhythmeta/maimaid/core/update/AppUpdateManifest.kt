package org.rhythmeta.maimaid.core.update

import kotlinx.serialization.Serializable

@Serializable
data class AppUpdateManifest(
    val schemaVersion: Int,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val branch: String,
    val sha: String,
    val author: String,
    val builtAt: String,
    val historyUrl: String,
    val artifacts: List<AppUpdateArtifact>,
)
