package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_scroll_records")
data class DailyScrollRecord(
    @PrimaryKey
    val dateString: String, // format "yyyy-MM-dd"
    val instagramCount: Int = 0,
    val youtubeCount: Int = 0,
    val instagramLimit: Int = 30, // Default limit
    val youtubeLimit: Int = 30,  // Default limit
    val instagramUnlockedBonus: Int = 0,
    val youtubeUnlockedBonus: Int = 0,
    val limitSetToday: Boolean = false,
    val lastUpdatedTimestamp: Long = System.currentTimeMillis()
) {
    val totalInstagramAllowed: Int
        get() = instagramLimit + instagramUnlockedBonus

    val totalYoutubeAllowed: Int
        get() = youtubeLimit + youtubeUnlockedBonus

    val isInstagramBlocked: Boolean
        get() = totalInstagramAllowed in 1..instagramCount

    val isYoutubeBlocked: Boolean
        get() = totalYoutubeAllowed in 1..youtubeCount

    val totalScrollsToday: Int
        get() = instagramCount + youtubeCount

    val totalAllowedToday: Int
        get() = totalInstagramAllowed + totalYoutubeAllowed
}

@Entity(tableName = "scroll_event_logs")
data class ScrollEventLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val dateString: String,
    val platform: String, // "INSTAGRAM" or "YOUTUBE"
    val timestamp: Long = System.currentTimeMillis(),
    val itemIdentifier: String = "",
    val runningCount: Int = 0
)
