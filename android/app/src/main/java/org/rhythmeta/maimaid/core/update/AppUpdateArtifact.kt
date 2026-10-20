package org.rhythmeta.maimaid.core.update

import kotlinx.serialization.Serializable

@Serializable
data class AppUpdateArtifact(val abi: String, val url: String, val size: Long, val sha256: String)
