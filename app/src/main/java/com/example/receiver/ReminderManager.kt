package com.example.receiver

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.R
import com.example.data.preferences.AppPreferences
import com.example.data.repository.ScrollRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

object ReminderManager {
    private const val TAG = "ReminderManager"
    const val CHANNEL_ID = "reels_pal_reminders"
    private const val NOTIFICATION_ID = 2001

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.notification_channel_reminders)
            val descriptionText = context.getString(R.string.notification_channel_reminders_desc)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableVibration(true)
            }
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun scheduleMidnightAndReminderAlarms(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val prefs = AppPreferences.getInstance(context)

        // 1. Midnight Reset Alarm
        val midnightIntent = Intent(context, MidnightResetReceiver::class.java).apply {
            action = MidnightResetReceiver.ACTION_MIDNIGHT_RESET
        }
        val midnightPendingIntent = PendingIntent.getBroadcast(
            context,
            101,
            midnightIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val midnightCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 1)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
        }

        try {
            alarmManager.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                midnightCal.timeInMillis,
                AlarmManager.INTERVAL_DAY,
                midnightPendingIntent
            )
            Log.d(TAG, "Midnight reset alarm scheduled for: ${midnightCal.time}")
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling midnight alarm: ${e.message}", e)
        }

        // 2. Periodic Reminder Alarm if enabled
        if (prefs.isReminderEnabled) {
            val intervalMinutes = prefs.reminderIntervalMinutes.coerceAtLeast(15)
            val reminderIntent = Intent(context, MidnightResetReceiver::class.java).apply {
                action = MidnightResetReceiver.ACTION_CHECK_LIMIT_REMINDER
            }
            val reminderPendingIntent = PendingIntent.getBroadcast(
                context,
                102,
                reminderIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val triggerTime = System.currentTimeMillis() + (intervalMinutes * 60 * 1000L)
            try {
                alarmManager.setInexactRepeating(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    intervalMinutes * 60 * 1000L,
                    reminderPendingIntent
                )
                Log.d(TAG, "Periodic limit reminder scheduled every $intervalMinutes minutes")
            } catch (e: Exception) {
                Log.e(TAG, "Error scheduling reminder alarm: ${e.message}", e)
            }
        }
    }

    fun checkAndSendReminder(context: Context) {
        val prefs = AppPreferences.getInstance(context)
        if (!prefs.isReminderEnabled) return

        val repository = ScrollRepository.getInstance(context)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val todayRecord = repository.getOrCreateTodayRecord()
                // Reminder is only sent if user has NOT set limits for today yet
                if (!todayRecord.limitSetToday) {
                    val now = System.currentTimeMillis()
                    val intervalMs = prefs.reminderIntervalMinutes * 60 * 1000L
                    if (now - prefs.lastReminderSentTimestamp >= (intervalMs - 60000L)) {
                        sendLimitReminderNotification(context)
                        prefs.lastReminderSentTimestamp = now
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in checkAndSendReminder: ${e.message}", e)
            }
        }
    }

    private fun sendLimitReminderNotification(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("⏰ Set Today's Reels & Shorts Limit")
            .setContentText("You haven't set today's Reels/Shorts limit yet. Tap to set your goal and protect your focus!")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("You haven't set today's Reels/Shorts limit yet. Tap to set your goal and protect your focus before scrolling!")
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            Log.d(TAG, "Limit reminder notification sent")
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission not granted: ${e.message}")
        }
    }
}
