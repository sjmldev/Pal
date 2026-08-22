package com.example.receiver

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.model.DailyScrollRecord
import com.example.data.repository.ScrollRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReelsPalWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        private const val TAG = "ReelsPalWidgetProvider"

        fun updateAllWidgets(context: Context) {
            try {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val thisWidget = ComponentName(context, ReelsPalWidgetProvider::class.java)
                val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
                for (widgetId in allWidgetIds) {
                    updateAppWidget(context, appWidgetManager, widgetId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating all widgets: ${e.message}", e)
            }
        }

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val repository = ScrollRepository.getInstance(context)
                    val record = repository.getOrCreateTodayRecord()
                    renderWidget(context, appWidgetManager, appWidgetId, record)
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading data for widget $appWidgetId: ${e.message}", e)
                }
            }
        }

        private fun renderWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            record: DailyScrollRecord
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_reels_pal)

            val totalScrolls = record.totalScrollsToday
            val totalAllowed = record.totalAllowedToday.coerceAtLeast(1)
            val remaining = (totalAllowed - totalScrolls).coerceAtLeast(0)
            val progressPercent = ((totalScrolls.toFloat() / totalAllowed.toFloat()) * 100).toInt().coerceIn(0, 100)
            val energyRemaining = (100 - progressPercent).coerceIn(0, 100)

            val emoji = when {
                energyRemaining >= 70 -> "🧠"
                energyRemaining >= 30 -> "⚡"
                else -> "🚨"
            }

            views.setTextViewText(R.id.widget_emoji, emoji)
            views.setTextViewText(R.id.widget_energy_badge, "$energyRemaining% ⚡")
            views.setTextViewText(R.id.widget_total_scrolls_text, "$totalScrolls / $totalAllowed")
            views.setTextViewText(
                R.id.widget_remaining_text,
                if (remaining > 0) "$remaining scrolls remaining" else "Daily cap exhausted!"
            )
            views.setTextViewText(
                R.id.widget_ig_text,
                "🎬 IG: ${record.instagramCount}/${record.instagramLimit}"
            )
            views.setTextViewText(
                R.id.widget_yt_text,
                "▶️ YT: ${record.youtubeCount}/${record.youtubeLimit}"
            )
            views.setProgressBar(R.id.widget_progress_bar, 100, progressPercent, false)

            // Tap intent to launch app
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
