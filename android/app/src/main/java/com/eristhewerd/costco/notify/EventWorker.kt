package com.eristhewerd.costco.notify

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import com.eristhewerd.costco.CostcoApp
import com.eristhewerd.costco.MainActivity
import com.eristhewerd.costco.R
import com.eristhewerd.costco.data.Event
import java.util.concurrent.TimeUnit

/**
 * Polls our server for new events (receipts, price drops, sign-in trouble) and shows them as
 * notifications. Polling keeps Google's push service out of the loop; the cost is up to
 * ~30 minutes of delay.
 */
class EventWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val api = (applicationContext as CostcoApp).api
        if (!api.signedIn) return Result.success()
        return try {
            check(applicationContext, api.prefs.lastEventId)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val CHANNEL_RECEIPTS = "receipts"
        const val CHANNEL_SAVINGS = "savings"
        const val CHANNEL_ACCOUNT = "account"
        const val EXTRA_OPEN = "open"
        private const val WORK_NAME = "events"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<EventWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        suspend fun check(context: Context, after: Long) {
            val api = (context.applicationContext as CostcoApp).api
            val events = api.events(maxOf(after, 0))
            // On a fresh sign-in, don't replay history as a burst of notifications.
            if (after >= 0) events.forEach { notify(context, it) }
            val newest = events.maxOfOrNull { it.id } ?: maxOf(after, 0)
            api.prefs.lastEventId = newest
        }

        fun destination(kind: String, ref: String): String = when (kind) {
            "new_receipt" -> "receipt/$ref"
            "new_order" -> "order/$ref"
            "price_drop" -> "savings"
            else -> "settings"
        }

        private fun notify(context: Context, event: Event) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) return
            val channel = when (event.kind) {
                "price_drop" -> CHANNEL_SAVINGS
                "relink_needed", "relink_soon" -> CHANNEL_ACCOUNT
                else -> CHANNEL_RECEIPTS
            }
            val intent = Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN, destination(event.kind, event.ref))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pending = PendingIntent.getActivity(
                context, event.id.toInt(), intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(event.title)
                .setContentText(event.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(event.body))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(event.id.toInt(), notification)
        }
    }
}
