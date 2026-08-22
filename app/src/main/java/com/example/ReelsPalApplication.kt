package com.example

import android.app.Application
import com.example.ads.AdManager
import com.example.data.preferences.AppPreferences
import com.example.receiver.ReminderManager
import com.example.service.FloatingHudService

class ReelsPalApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 1. Initialize Start.io Ads SDK with live production App ID (207395789)
        AdManager.initialize(this)

        // 2. Setup Notification Channels & Periodic Alarms
        ReminderManager.createNotificationChannel(this)
        ReminderManager.scheduleMidnightAndReminderAlarms(this)

        // 3. Keep background protection process active
        val preferences = AppPreferences.getInstance(this)
        if (preferences.hasCompletedOnboarding) {
            FloatingHudService.start(this)
        }
    }
}
