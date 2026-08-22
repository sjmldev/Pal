package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_scroll_records")
data class DailyScrollRecord(
    @PrimaryKey
    val dateString: String, // format "yyyy-MM-dd"
    val instagramCount: Int = 0,
    val youtubeCount: Int = 0,
    val facebookCount: Int = 0,
    val snapchatCount: Int = 0,
    val instagramLimit: Int = 30, // Default limit
    val youtubeLimit: Int = 30,  // Default limit
    val facebookLimit: Int = 30, // Default limit
    val snapchatLimit: Int = 30, // Default limit
    val instagramUnlockedBonus: Int = 0,
    val youtubeUnlockedBonus: Int = 0,
    val facebookUnlockedBonus: Int = 0,
    val snapchatUnlockedBonus: Int = 0,
    val limitSetToday: Boolean = false,
    val lastUpdatedTimestamp: Long = System.currentTimeMillis()
) {
    val totalInstagramAllowed: Int
        get() = instagramLimit + instagramUnlockedBonus

    val totalYoutubeAllowed: Int
        get() = youtubeLimit + youtubeUnlockedBonus

    val totalFacebookAllowed: Int
        get() = facebookLimit + facebookUnlockedBonus

    val totalSnapchatAllowed: Int
        get() = snapchatLimit + snapchatUnlockedBonus

    val isInstagramBlocked: Boolean
        get() = totalInstagramAllowed in 1..instagramCount

    val isYoutubeBlocked: Boolean
        get() = totalYoutubeAllowed in 1..youtubeCount

    val isFacebookBlocked: Boolean
        get() = totalFacebookAllowed in 1..facebookCount

    val isSnapchatBlocked: Boolean
        get() = totalSnapchatAllowed in 1..snapchatCount

    fun isPlatformBlocked(platform: ScrollPlatform): Boolean {
        return when (platform) {
            ScrollPlatform.INSTAGRAM -> isInstagramBlocked
            ScrollPlatform.YOUTUBE -> isYoutubeBlocked
            ScrollPlatform.FACEBOOK -> isFacebookBlocked
            ScrollPlatform.SNAPCHAT -> isSnapchatBlocked
        }
    }

    fun getCount(platform: ScrollPlatform): Int {
        return when (platform) {
            ScrollPlatform.INSTAGRAM -> instagramCount
            ScrollPlatform.YOUTUBE -> youtubeCount
            ScrollPlatform.FACEBOOK -> facebookCount
            ScrollPlatform.SNAPCHAT -> snapchatCount
        }
    }

    fun getLimit(platform: ScrollPlatform): Int {
        return when (platform) {
            ScrollPlatform.INSTAGRAM -> instagramLimit
            ScrollPlatform.YOUTUBE -> youtubeLimit
            ScrollPlatform.FACEBOOK -> facebookLimit
            ScrollPlatform.SNAPCHAT -> snapchatLimit
        }
    }

    fun getBonus(platform: ScrollPlatform): Int {
        return when (platform) {
            ScrollPlatform.INSTAGRAM -> instagramUnlockedBonus
            ScrollPlatform.YOUTUBE -> youtubeUnlockedBonus
            ScrollPlatform.FACEBOOK -> facebookUnlockedBonus
            ScrollPlatform.SNAPCHAT -> snapchatUnlockedBonus
        }
    }

    fun getTotalAllowed(platform: ScrollPlatform): Int {
        return when (platform) {
            ScrollPlatform.INSTAGRAM -> totalInstagramAllowed
            ScrollPlatform.YOUTUBE -> totalYoutubeAllowed
            ScrollPlatform.FACEBOOK -> totalFacebookAllowed
            ScrollPlatform.SNAPCHAT -> totalSnapchatAllowed
        }
    }

    val totalScrollsToday: Int
        get() = instagramCount + youtubeCount + facebookCount + snapchatCount

    val totalAllowedToday: Int
        get() = totalInstagramAllowed + totalYoutubeAllowed + totalFacebookAllowed + totalSnapchatAllowed
}

@Entity(tableName = "scroll_event_logs")
data class ScrollEventLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val dateString: String,
    val platform: String, // "INSTAGRAM", "YOUTUBE", "FACEBOOK", "SNAPCHAT"
    val timestamp: Long = System.currentTimeMillis(),
    val itemIdentifier: String = "",
    val runningCount: Int = 0
)

