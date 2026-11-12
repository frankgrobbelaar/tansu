package io.github.frankgrobbelaar.tansu.jobs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.frankgrobbelaar.tansu.MainActivity
import io.github.frankgrobbelaar.tansu.R
import io.github.frankgrobbelaar.tansu.TansuApplication
import io.github.frankgrobbelaar.tansu.ui.format.JobWords
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps tansu running while jobs run, with their progress in a notification: Android lets a
 * foreground service carry on after the app leaves the screen. It stops once nothing's left to do.
 */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs: Jobs by lazy { (application as TansuApplication).container.jobs }
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(EXTRA_JOB, -1) ?: -1
        when (intent?.action) {
            ACTION_PAUSE -> jobs.pause(id)
            ACTION_CANCEL -> jobs.cancel(id)
        }
        channel(this)
        val running = jobs.all.value.firstOrNull { it.busy }
        startForeground(
            NOTIFICATION_ID,
            running?.let(::progressNotification) ?: quietNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (!watching) {
            watching = true
            // Android limits how often a notification may change: at most once a second. A slow
            // StateFlow collector skips to the latest state.
            scope.launch {
                jobs.all.collect {
                    show(it)
                    delay(NOTIFY_EVERY_MILLIS)
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 caps a day's data sync: pause what's running and let the user resume it. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        // Everything waits: a queued job mustn't start without the service to keep it alive.
        jobs.all.value
            .filter { it.status == Job.Status.Running || it.status == Job.Status.Queued }
            .forEach { jobs.pause(it.id) }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun show(all: List<Job>) {
        val running = all.firstOrNull { it.busy }
        if (running == null) {
            // Done: the last job's ending stays in the shade; the service's own notification goes.
            all.lastOrNull { !it.busy }
                ?.let { ending ->
                    notifications.notify(ENDING_ID, endingNotification(ending))
                }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            watching = false
        } else {
            notifications.notify(NOTIFICATION_ID, progressNotification(running))
        }
    }

    private fun progressNotification(job: Job): Notification {
        val builder =
            base()
                .setContentTitle(JobWords.title(job, resources))
                .setContentText(JobWords.detail(job, resources))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(
                    PROGRESS_MAX,
                    (job.progress.fraction * PROGRESS_MAX).toInt(),
                    job.progress.counting,
                )
        if (job.kind != Job.Kind.Delete) {
            builder.addAction(0, getString(R.string.pause), action(ACTION_PAUSE, job.id))
        }
        return builder
            .addAction(0, getString(R.string.cancel), action(ACTION_CANCEL, job.id))
            .build()
    }

    /** How the last job ended; tapping it opens the folder its files went to. */
    private fun endingNotification(job: Job): Notification {
        val landed = job.to ?: job.from
        val show = MainActivity.showFolder(this, landed.drawerId, landed.folder)
        return base()
            .setContentTitle(JobWords.title(job, resources))
            .setContentText(JobWords.detail(job, resources))
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    1,
                    show,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .setAutoCancel(true)
            .build()
    }

    private fun quietNotification(): Notification =
        base().setContentTitle(getString(R.string.job_getting_ready)).build()

    private fun base() =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openApp())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN),
            PendingIntent.FLAG_IMMUTABLE,
        )

    private fun action(name: String, jobId: Long): PendingIntent =
        PendingIntent.getService(
            this,
            name.hashCode(),
            Intent(this, TransferService::class.java).setAction(name).putExtra(EXTRA_JOB, jobId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        private const val CHANNEL = "jobs"
        private const val NOTIFICATION_ID = 1
        private const val ENDING_ID = 2
        private const val PROGRESS_MAX = 1000
        private const val NOTIFY_EVERY_MILLIS = 1_000L
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_CANCEL = "cancel"
        private const val EXTRA_JOB = "job"

        /** Starts the service, if it isn't running, for jobs just started or resumed. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, TransferService::class.java))
        }

        private fun channel(context: Context) {
            val channel =
                NotificationChannel(
                        CHANNEL,
                        context.getString(R.string.notification_channel_jobs),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                    .apply {
                        description = context.getString(R.string.notification_channel_jobs_body)
                    }
            context
                .getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
}

/** Running or waiting its turn: what keeps the service going. Paused jobs wait for the user. */
private val Job.busy: Boolean
    get() = status == Job.Status.Running || status == Job.Status.Queued
