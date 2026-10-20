package org.rhythmeta.maimaid.core.update

import kotlinx.serialization.Serializable

@Serializable
data class PendingAppUpdate(val manifest: AppUpdateManifest, val artifact: AppUpdateArtifact, val downloadId: Long)
