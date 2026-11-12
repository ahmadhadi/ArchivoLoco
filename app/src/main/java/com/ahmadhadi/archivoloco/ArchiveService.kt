package com.ahmadhadi.archivoloco

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ArchiveService : Service() {
    companion object {
        private const val CHANNEL = "archive"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "com.ahmadhadi.archivoloco.STOP"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_SAVED = "saved"
        const val EXTRA_COMMENTS = "comments"
        const val EXTRA_REPLIES = "replies"
        const val EXTRA_UNSAVE = "unsave"
        const val EXTRA_REDOWNLOAD = "redownload"

        fun stop(context: Context) {
            context.startService(Intent(context, ArchiveService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            job?.cancel()
            return START_NOT_STICKY
        }
        if (job?.isActive == true || intent == null) return START_NOT_STICKY

        val saved = intent.getBooleanExtra(EXTRA_SAVED, false)
        val username = intent.getStringExtra(EXTRA_USERNAME).orEmpty()
        val opts = ArchiveOptions(
            comments = intent.getBooleanExtra(EXTRA_COMMENTS, false) || intent.getBooleanExtra(EXTRA_REPLIES, false),
            replies = intent.getBooleanExtra(EXTRA_REPLIES, false),
            unsave = saved && intent.getBooleanExtra(EXTRA_UNSAVE, false),
            redownload = intent.getBooleanExtra(EXTRA_REDOWNLOAD, false),
        )
        val title = if (saved) "Saved posts" else "@$username"

        createChannel()
        startForeground(NOTIF_ID, buildNotification(title, "Starting…", ongoing = true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        JobBus.start(title)
        val updater = scope.launch {
            JobBus.state.collectLatest { s ->
                if (s.running) notify(buildNotification(title, s.stats.summary(), ongoing = true))
            }
        }
        job = scope.launch {
            var outcome: String
            try {
                val archiver = Archiver(applicationContext, opts)
                if (saved) archiver.archiveSaved() else archiver.archiveProfile(username)
                outcome = "Done"
            } catch (e: CancellationException) {
                outcome = "Stopped"
            } catch (e: Exception) {
                outcome = "Error: ${e.message ?: e.javaClass.simpleName}"
            }
            JobBus.log("■ $outcome — ${JobBus.state.value.stats.summary()}")
            JobBus.finish()
            updater.cancel()
            stopForeground(STOP_FOREGROUND_DETACH)
            notify(buildNotification("$title: $outcome", JobBus.state.value.stats.summary(), ongoing = false))
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notify(n: Notification) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)

    private fun buildNotification(title: String, text: String, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
        if (ongoing) {
            val stop = PendingIntent.getService(
                this, 1, Intent(this, ArchiveService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            b.addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
        }
        return b.build()
    }
}
