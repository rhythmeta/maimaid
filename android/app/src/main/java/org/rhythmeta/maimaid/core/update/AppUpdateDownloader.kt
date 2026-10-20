package org.rhythmeta.maimaid.core.update

import java.io.File

interface AppUpdateDownloader {
    suspend fun enqueue(manifest: AppUpdateManifest, artifact: AppUpdateArtifact): Long
    suspend fun query(id: Long): AppDownloadSnapshot
    suspend fun remove(update: PendingAppUpdate)
    fun file(update: PendingAppUpdate): File
}
