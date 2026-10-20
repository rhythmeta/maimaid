package org.rhythmeta.maimaid.core.update

import kotlinx.serialization.Serializable

@Serializable
data class AppUpdateCommit(val sha: String, val parents: List<String>, val title: String, val author: String)
