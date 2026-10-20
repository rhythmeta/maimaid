package org.rhythmeta.maimaid.core.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.rhythmeta.maimaid.R
import java.io.File

class AndroidAppUpdateDownloader(private val context: Context) : AppUpdateDownloader {
    private val manager = context.getSystemService(DownloadManager::class.java)
    private fun directory(): File = context.getExternalFilesDir(null)?.let { File(it, "updates") }
        ?: throw AppUpdateException(AppUpdateFailure.Storage)
    private fun filename(manifest: AppUpdateManifest, artifact: AppUpdateArtifact) =
        "${manifest.versionCode}-${manifest.sha}-${artifact.abi}.apk"

    override fun file(update: PendingAppUpdate): File = File(directory(), filename(update.manifest, update.artifact))

    override suspend fun enqueue(manifest: AppUpdateManifest, artifact: AppUpdateArtifact): Long = withContext(Dispatchers.IO) {
        val directory = directory()
        if (!directory.isDirectory && !directory.mkdirs()) throw AppUpdateException(AppUpdateFailure.Storage)
        if (StatFs(directory.path).availableBytes < artifact.size * 2) throw AppUpdateException(AppUpdateFailure.Storage)
        val destination = File(directory, filename(manifest, artifact))
        if (destination.exists() && !destination.delete()) throw AppUpdateException(AppUpdateFailure.Storage)
        manager.enqueue(
            DownloadManager.Request(artifact.url.toUri())
                .setTitle(context.getString(R.string.app_name))
                .setDescription(context.getString(R.string.update_downloading))
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationUri(Uri.fromFile(destination)),
        )
    }

    override suspend fun query(id: Long): AppDownloadSnapshot = withContext(Dispatchers.IO) {
        manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (!cursor.moveToFirst()) return@withContext AppDownloadSnapshot(AppDownloadSnapshot.Status.Missing)
            fun long(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
            val status = when (long(DownloadManager.COLUMN_STATUS).toInt()) {
                DownloadManager.STATUS_SUCCESSFUL -> AppDownloadSnapshot.Status.Complete
                DownloadManager.STATUS_FAILED -> AppDownloadSnapshot.Status.Failed
                else -> AppDownloadSnapshot.Status.Running
            }
            val failure = when (long(DownloadManager.COLUMN_REASON).toInt()) {
                DownloadManager.ERROR_INSUFFICIENT_SPACE, DownloadManager.ERROR_DEVICE_NOT_FOUND,
                DownloadManager.ERROR_FILE_ERROR -> AppUpdateFailure.Storage
                else -> AppUpdateFailure.Download
            }
            AppDownloadSnapshot(status, long(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR).coerceAtLeast(0),
                long(DownloadManager.COLUMN_TOTAL_SIZE_BYTES).coerceAtLeast(0), failure)
        }
    }

    override suspend fun remove(update: PendingAppUpdate) = withContext(Dispatchers.IO) {
        manager.remove(update.downloadId)
        file(update).delete()
        Unit
    }
}
