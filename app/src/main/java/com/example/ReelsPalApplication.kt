package com.example

import android.app.Application
import com.example.ads.AdManager
import com.example.receiver.ReminderManager

class ReelsPalApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 1. Initialize Start.io Ads SDK with live production App ID (207395789)
        AdManager.initialize(this)

        // 2. Setup Notification Channels & Periodic Alarms
        ReminderManager.createNotificationChannel(this)
        ReminderManager.scheduleMidnightAndReminderAlarms(this)
    }
}
