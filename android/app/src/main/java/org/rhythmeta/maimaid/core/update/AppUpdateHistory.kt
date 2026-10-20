package org.rhythmeta.maimaid.core.update

import kotlinx.serialization.Serializable

@Serializable
data class AppUpdateHistory(val schemaVersion: Int, val commits: List<AppUpdateCommit>)
