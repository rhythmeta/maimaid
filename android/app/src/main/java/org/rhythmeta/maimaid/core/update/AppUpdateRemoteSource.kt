package org.rhythmeta.maimaid.core.update

interface AppUpdateRemoteSource {
    suspend fun fetchManifest(): AppUpdateManifest
    suspend fun fetchHistory(url: String): AppUpdateHistory
}
