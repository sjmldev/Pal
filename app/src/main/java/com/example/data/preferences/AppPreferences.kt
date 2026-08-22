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

    var isProgressNotificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROGRESS_NOTIFS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_PROGRESS_NOTIFS_ENABLED, value).apply()

    // Tracks which 10% milestone bracket (10, 20, 30... 100) was last notified today for any platform
    fun getLastNotifiedMilestone(platform: String, dateString: String): Int {
        return prefs.getInt("key_last_milestone_${platform.lowercase()}_$dateString", 0)
    }

    fun setLastNotifiedMilestone(platform: String, dateString: String, milestone: Int) {
        prefs.edit().putInt("key_last_milestone_${platform.lowercase()}_$dateString", milestone).apply()
    }

    fun getLastNotifiedMilestoneIg(dateString: String): Int = getLastNotifiedMilestone("INSTAGRAM", dateString)
    fun setLastNotifiedMilestoneIg(dateString: String, milestone: Int) = setLastNotifiedMilestone("INSTAGRAM", dateString, milestone)

    fun getLastNotifiedMilestoneYt(dateString: String): Int = getLastNotifiedMilestone("YOUTUBE", dateString)
    fun setLastNotifiedMilestoneYt(dateString: String, milestone: Int) = setLastNotifiedMilestone("YOUTUBE", dateString, milestone)


    companion object {
        private const val KEY_ONBOARDING_DONE = "key_onboarding_done"
        private const val KEY_REMINDER_INTERVAL = "key_reminder_interval"
        private const val KEY_REMINDER_ENABLED = "key_reminder_enabled"
        private const val KEY_HUD_ENABLED = "key_hud_enabled"
        private const val KEY_LAST_REMINDER_TIMESTAMP = "key_last_reminder_timestamp"
        private const val KEY_PROGRESS_NOTIFS_ENABLED = "key_progress_notifs_enabled"

        @Volatile
        private var INSTANCE: AppPreferences? = null

        fun getInstance(context: Context): AppPreferences {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppPreferences(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
