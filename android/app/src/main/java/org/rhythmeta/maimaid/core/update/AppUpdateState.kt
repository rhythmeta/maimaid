package org.rhythmeta.maimaid.core.update

data class AppUpdateState(
    val phase: AppUpdatePhase = AppUpdatePhase.Idle,
    val manifest: AppUpdateManifest? = null,
    val artifact: AppUpdateArtifact? = null,
    val changes: AppUpdateChanges = AppUpdateChanges(),
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val failure: AppUpdateFailure? = null,
)
