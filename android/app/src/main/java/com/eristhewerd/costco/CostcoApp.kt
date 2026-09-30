package com.eristhewerd.costco

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.eristhewerd.costco.data.Api
import com.eristhewerd.costco.notify.EventWorker

class CostcoApp : Application() {
    lateinit var api: Api
        private set

    override fun onCreate() {
        super.onCreate()
        api = Api(this)
        getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(EventWorker.CHANNEL_RECEIPTS, "New receipts", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(EventWorker.CHANNEL_SAVINGS, "Price adjustments", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(EventWorker.CHANNEL_ACCOUNT, "Costco sign-in", NotificationManager.IMPORTANCE_HIGH),
            )
        )
        if (api.signedIn) EventWorker.schedule(this)
    }
}
