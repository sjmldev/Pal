package com.example.data.preferences

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("reels_pal_settings", Context.MODE_PRIVATE)

    var hasCompletedOnboarding: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_DONE, value).apply()

    var reminderIntervalMinutes: Int
        get() = prefs.getInt(KEY_REMINDER_INTERVAL, 60) // default 60 mins
        set(value) = prefs.edit().putInt(KEY_REMINDER_INTERVAL, value).apply()

    var isReminderEnabled: Boolean
        get() = prefs.getBoolean(KEY_REMINDER_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_REMINDER_ENABLED, value).apply()

    var isHudOverlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_HUD_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_HUD_ENABLED, value).apply()

    var lastReminderSentTimestamp: Long
        get() = prefs.getLong(KEY_LAST_REMINDER_TIMESTAMP, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_REMINDER_TIMESTAMP, value).apply()

    companion object {
        private const val KEY_ONBOARDING_DONE = "key_onboarding_done"
        private const val KEY_REMINDER_INTERVAL = "key_reminder_interval"
        private const val KEY_REMINDER_ENABLED = "key_reminder_enabled"
        private const val KEY_HUD_ENABLED = "key_hud_enabled"
        private const val KEY_LAST_REMINDER_TIMESTAMP = "key_last_reminder_timestamp"

        @Volatile
        private var INSTANCE: AppPreferences? = null

        fun getInstance(context: Context): AppPreferences {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppPreferences(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
