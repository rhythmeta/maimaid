package org.rhythmeta.maimaid.core.update

data class AppUpdateChanges(val commits: List<AppUpdateCommit> = emptyList(), val incomplete: Boolean = false)
