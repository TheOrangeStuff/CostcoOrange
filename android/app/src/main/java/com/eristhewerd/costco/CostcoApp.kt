package com.eristhewerd.costco

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.eristhewerd.costco.data.Api
import com.eristhewerd.costco.notify.EventWorker
import java.io.File

class CostcoApp : Application() {
    lateinit var api: Api
        private set

    override fun onCreate() {
        super.onCreate()
        api = Api(this)
        reportCrashes()
        getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(EventWorker.CHANNEL_RECEIPTS, "New receipts", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(EventWorker.CHANNEL_SAVINGS, "Price adjustments", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(EventWorker.CHANNEL_ACCOUNT, "Costco sign-in", NotificationManager.IMPORTANCE_HIGH),
            )
        )
        if (api.signedIn) EventWorker.schedule(this)
    }

    /** A crash kills the process before a report could be sent, so save it and send it next launch. */
    private fun reportCrashes() {
        val file = File(filesDir, "last-crash.txt")
        if (file.exists()) {
            runCatching { api.report("Crash: " + file.readText()) }
            file.delete()
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { file.writeText("on ${thread.name}: ${e.stackTraceToString()}") }
            previous?.uncaughtException(thread, e)
        }
    }
}
