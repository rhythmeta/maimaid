package org.rhythmeta.maimaid.core.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import org.rhythmeta.maimaid.MainActivity
import org.rhythmeta.maimaid.R
import org.rhythmeta.maimaid.core.data.CatalogSyncProgress
import org.rhythmeta.maimaid.core.data.CatalogSyncStage

/**
 * Builds the ongoing notification shown while [CatalogAssetSyncService] warms the image cache.
 *
 * The channel is deliberately low importance: the progress must be visible when the user pulls down
 * the shade, but it must never make a sound, vibrate or pop up as a heads-up banner.
 */
class CatalogAssetSyncNotifier(
    private val context: Context,
    private val post: (Notification) -> Unit,
) {
    private var lastPostedAtMillis = 0L
    private var lastPercent = -1
    private var lastNotification: Notification? = null

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.catalog_asset_sync_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.catalog_asset_sync_channel_description)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    fun showIndeterminate() {
        post(buildNotification(title = channelTitle(), text = preparingText(), percent = null))
    }

    /** Re-posts the current state, for a repeated start request that must not reset progress. */
    fun repostCurrent() {
        post(lastNotification ?: buildNotification(channelTitle(), preparingText(), percent = null))
    }

    fun update(progress: CatalogSyncProgress) {
        val now = SystemClock.elapsedRealtime()
        val totalBytes = progress.totalBytes
        // With a manifest-provided total the bar can track the same quantity as the MB text, so the
        // notification stays internally consistent; without one it falls back to the stage fraction.
        val fraction = if (totalBytes != null && totalBytes > 0L) {
            progress.downloadedBytes.toFloat() / totalBytes.toFloat()
        } else {
            progress.overallFraction
        }
        val percent = (fraction.coerceIn(0f, 1f) * 100f).toInt()
        // Byte progress is reported every 64 KiB, so gate on whole-percent changes plus a hard floor
        // to keep the shade quiet without ever showing a stale percentage.
        if (percent == lastPercent) return
        if (now - lastPostedAtMillis < MIN_UPDATE_INTERVAL_MILLIS) return
        lastPostedAtMillis = now
        lastPercent = percent
        post(buildNotification(title = stageTitle(progress.stage), text = progressText(progress), percent = percent))
    }

    private fun buildNotification(title: String, text: String, percent: Int?): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(PROGRESS_MAX, percent ?: 0, percent == null)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
            .also { lastNotification = it }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun channelTitle(): String = context.getString(R.string.catalog_asset_sync_channel_name)

    private fun preparingText(): String = context.getString(R.string.catalog_asset_sync_notification_preparing)

    private fun stageTitle(stage: CatalogSyncStage): String = context.getString(
        when (stage) {
            CatalogSyncStage.PresetAvatars -> R.string.catalog_sync_stage_avatars
            else -> R.string.catalog_sync_stage_covers
        },
    )

    private fun progressText(progress: CatalogSyncProgress): String {
        val parts = buildList {
            val totalBytes = progress.totalBytes
            if (totalBytes != null && totalBytes > 0L) {
                add(
                    context.getString(
                        R.string.catalog_sync_byte_progress,
                        // Retried downloads can report the same bytes twice, so clamp to the total.
                        Formatter.formatShortFileSize(context, progress.downloadedBytes.coerceIn(0L, totalBytes)),
                        Formatter.formatShortFileSize(context, totalBytes),
                    ),
                )
            }
            if (progress.totalItems > 0) {
                add(
                    context.getString(
                        R.string.catalog_sync_item_progress,
                        progress.completedItems,
                        progress.totalItems,
                    ),
                )
            }
        }
        return parts.joinToString("  ·  ").ifEmpty { preparingText() }
    }

    companion object {
        const val CHANNEL_ID = "catalog_asset_sync"

        private const val PROGRESS_MAX = 100
        private const val MIN_UPDATE_INTERVAL_MILLIS = 400L
    }
}
