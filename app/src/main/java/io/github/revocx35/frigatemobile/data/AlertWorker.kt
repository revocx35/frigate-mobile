package io.github.revocx35.frigatemobile.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.revocx35.frigatemobile.FrigateMobileApp
import io.github.revocx35.frigatemobile.MainActivity
import io.github.revocx35.frigatemobile.R
import io.github.revocx35.frigatemobile.core.FrigateException
import io.github.revocx35.frigatemobile.core.ReviewItem
import io.github.revocx35.frigatemobile.core.Severity
import io.github.revocx35.frigatemobile.ui.components.labelTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Checks Frigate for new, unreviewed alerts (and optionally detections) every 15 minutes (Android's minimum for
 * periodic work) and posts a notification with the thumbnail. Frigate's own push notifications need a browser
 * (Web Push), so the app polls instead; nothing runs while the user has notifications off.
 */
class AlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as FrigateMobileApp).graph
        val conn = graph.connection.value ?: return Result.success()
        val settings = graph.settings
        if (!settings.settings.value.alertNotifications) return Result.success()
        return try {
            val since = settings.lastNotified
            val now = System.currentTimeMillis() / 1000.0
            if (since <= 0.0) {
                // First run: only notify about what happens from now on.
                settings.lastNotified = now
                return Result.success()
            }
            val severities = buildList {
                add(Severity.Alert)
                if (settings.settings.value.notifyDetections) add(Severity.Detection)
            }
            val fresh = severities.flatMap { conn.client.reviews(severity = it, reviewed = false, after = since, limit = 20) }
                .filter { it.startTime > since }
                .sortedBy { it.startTime }
            if (fresh.isEmpty()) return Result.success()
            settings.lastNotified = fresh.maxOf { it.startTime }
            val names = runCatching { conn.client.config().cameras.associate { it.name to it.title } }.getOrDefault(emptyMap())
            fresh.takeLast(MAX_PER_RUN).forEach { item ->
                val thumb = conn.client.urls.reviewThumb(item)?.let { url -> fetchBitmap(conn, url.toString()) }
                notify(applicationContext, item, names[item.camera] ?: item.camera, thumb)
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: FrigateException.Unauthorized) {
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private suspend fun fetchBitmap(conn: Connection, url: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            conn.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) null else r.body.bytes().let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
        }.getOrNull()
    }

    companion object {
        private const val WORK = "frigate-alerts"
        const val CHANNEL = "alerts"
        const val EXTRA_REVIEW_ID = "review_id"
        private const val MAX_PER_RUN = 5

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AlertWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
        }

        fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Camera alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "New alerts (and detections, if enabled) from Frigate"
                    // Thumbnails can show people's faces: hide them on the lock screen.
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                },
            )
        }

        fun canNotify(context: Context): Boolean =
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
                NotificationManagerCompat.from(context).areNotificationsEnabled()

        fun notify(context: Context, item: ReviewItem, cameraTitle: String, thumb: Bitmap?) {
            if (!canNotify(context)) return
            val what = item.labels.joinToString(", ") { labelTitle(it) }.ifBlank { "Activity" }
            val title = "$what · $cameraTitle"
            val text = if (item.isAlert) "New alert" else "New detection"
            val open = PendingIntent.getActivity(
                context, item.id.hashCode(),
                Intent(context, MainActivity::class.java).putExtra(EXTRA_REVIEW_ID, item.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val public = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(cameraTitle)
                .setContentText(text)
                .build()
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setWhen((item.startTime * 1000).toLong())
                .setShowWhen(true)
                .setAutoCancel(true)
                .setContentIntent(open)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(public)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .apply {
                    if (thumb != null) setLargeIcon(thumb).setStyle(NotificationCompat.BigPictureStyle().bigPicture(thumb).bigLargeIcon(null as Bitmap?))
                }
                .build()
            try {
                NotificationManagerCompat.from(context).notify(item.id.hashCode(), n)
            } catch (_: SecurityException) {
            }
        }
    }
}
