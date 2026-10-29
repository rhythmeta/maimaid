package org.rhythmeta.maimaid.core.sync

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.rhythmeta.maimaid.MaimaidApplication

/**
 * Keeps [CatalogAssetSyncer] running after the user leaves the app, so cover and preset-avatar
 * downloads are no longer tied to the onboarding screen or to the foreground process.
 *
 * The service owns the ongoing notification; it disappears as soon as the run ends.
 */
class CatalogAssetSyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var notifier: CatalogAssetSyncNotifier
    private var syncJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        notifier = CatalogAssetSyncNotifier(this) { notification ->
            startForeground(NOTIFICATION_ID, notification)
        }
        notifier.createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (syncJob?.isActive == true) {
            // A repeated start must keep the existing progress instead of resetting to indeterminate.
            notifier.repostCurrent()
            return START_NOT_STICKY
        }
        // Must happen within a few seconds of startForegroundService.
        notifier.showIndeterminate()

        val syncer = (application as MaimaidApplication).container.catalogAssetSyncer
        syncJob = scope.launch {
            val progressJob = launch {
                syncer.progress.collect { progress ->
                    if (progress != null) notifier.update(progress)
                }
            }
            try {
                syncer.sync()
            } finally {
                // Join before tearing the notification down, otherwise a late progress update could
                // re-post it after stopForeground.
                progressJob.cancelAndJoin()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ caps dataSync foreground services at a few hours per day.
        stopSelf(startId)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 4201

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CatalogAssetSyncService::class.java),
            )
        }
    }
}
