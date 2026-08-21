package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.repository.ScrollRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MidnightResetReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d(TAG, "Received broadcast action: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                ReminderManager.scheduleMidnightAndReminderAlarms(context)
            }
            ACTION_MIDNIGHT_RESET -> {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val repository = ScrollRepository.getInstance(context)
                        // Trigger creation of new day's record carrying forward limits
                        val newRecord = repository.getOrCreateTodayRecord()
                        Log.d(TAG, "Midnight reset triggered for ${newRecord.dateString}, limits carried forward: IG=${newRecord.instagramLimit}, YT=${newRecord.youtubeLimit}")
                        // Reschedule next midnight
                        ReminderManager.scheduleMidnightAndReminderAlarms(context)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in midnight reset: ${e.message}", e)
                    }
                }
            }
            ACTION_CHECK_LIMIT_REMINDER -> {
                ReminderManager.checkAndSendReminder(context)
            }
        }
    }

    companion object {
        private const val TAG = "MidnightResetReceiver"
        const val ACTION_MIDNIGHT_RESET = "com.rls.pl.app.ACTION_MIDNIGHT_RESET"
        const val ACTION_CHECK_LIMIT_REMINDER = "com.rls.pl.app.ACTION_CHECK_LIMIT_REMINDER"
    }
}
