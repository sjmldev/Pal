package com.example.data.repository

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.local.ScrollDao
import com.example.data.model.DailyScrollRecord
import com.example.data.model.ScrollEventLog
import com.example.data.model.ScrollPlatform
import com.example.receiver.ReelsPalWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ScrollRepository(
    private val context: Context,
    private val scrollDao: ScrollDao
) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun getTodayDateString(): String {
        return dateFormat.format(Date())
    }

    fun getTodayRecordFlow(): Flow<DailyScrollRecord> {
        val today = getTodayDateString()
        return scrollDao.getRecordForDate(today)
            .map { existing ->
                existing ?: getOrCreateTodayRecordSync(today)
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)
    }

    suspend fun getOrCreateTodayRecord(): DailyScrollRecord = withContext(Dispatchers.IO) {
        val today = getTodayDateString()
        getOrCreateTodayRecordSync(today)
    }

    private suspend fun getOrCreateTodayRecordSync(today: String): DailyScrollRecord {
        val existing = scrollDao.getRecordForDateSync(today)
        if (existing != null) {
            return existing
        }

        // Apply carry-forward rule from previous day
        val previousRecord = scrollDao.getLatestPreviousRecord(today)
        val carriedIgLimit = previousRecord?.instagramLimit ?: 30
        val carriedYtLimit = previousRecord?.youtubeLimit ?: 30
        val carriedFbLimit = previousRecord?.facebookLimit ?: 30
        val carriedScLimit = previousRecord?.snapchatLimit ?: 30

        val newRecord = DailyScrollRecord(
            dateString = today,
            instagramCount = 0,
            youtubeCount = 0,
            facebookCount = 0,
            snapchatCount = 0,
            instagramLimit = carriedIgLimit,
            youtubeLimit = carriedYtLimit,
            facebookLimit = carriedFbLimit,
            snapchatLimit = carriedScLimit,
            instagramUnlockedBonus = 0,
            youtubeUnlockedBonus = 0,
            facebookUnlockedBonus = 0,
            snapchatUnlockedBonus = 0,
            limitSetToday = false,
            lastUpdatedTimestamp = System.currentTimeMillis()
        )
        scrollDao.insertOrUpdateRecord(newRecord)
        return newRecord
    }

    suspend fun incrementScroll(platform: ScrollPlatform, itemIdentifier: String = ""): DailyScrollRecord =
        withContext(Dispatchers.IO) {
            val today = getTodayDateString()
            val currentRecord = getOrCreateTodayRecordSync(today)

            when (platform) {
                ScrollPlatform.INSTAGRAM -> scrollDao.incrementInstagramCount(today, 1)
                ScrollPlatform.YOUTUBE -> scrollDao.incrementYoutubeCount(today, 1)
                ScrollPlatform.FACEBOOK -> scrollDao.incrementFacebookCount(today, 1)
                ScrollPlatform.SNAPCHAT -> scrollDao.incrementSnapchatCount(today, 1)
            }

            val updatedRecord = scrollDao.getRecordForDateSync(today) ?: currentRecord
            val runningCount = when (platform) {
                ScrollPlatform.INSTAGRAM -> updatedRecord.instagramCount
                ScrollPlatform.YOUTUBE -> updatedRecord.youtubeCount
                ScrollPlatform.FACEBOOK -> updatedRecord.facebookCount
                ScrollPlatform.SNAPCHAT -> updatedRecord.snapchatCount
            }

            scrollDao.insertEventLog(
                ScrollEventLog(
                    dateString = today,
                    platform = platform.name,
                    timestamp = System.currentTimeMillis(),
                    itemIdentifier = itemIdentifier,
                    runningCount = runningCount
                )
            )

            // Live refresh all Home Screen Widgets
            ReelsPalWidgetProvider.updateAllWidgets(context)

            updatedRecord
        }

    suspend fun unlockExtraScrolls(platform: ScrollPlatform, amount: Int = 10): DailyScrollRecord =
        withContext(Dispatchers.IO) {
            val today = getTodayDateString()
            getOrCreateTodayRecordSync(today)

            when (platform) {
                ScrollPlatform.INSTAGRAM -> scrollDao.addInstagramBonus(today, amount)
                ScrollPlatform.YOUTUBE -> scrollDao.addYoutubeBonus(today, amount)
                ScrollPlatform.FACEBOOK -> scrollDao.addFacebookBonus(today, amount)
                ScrollPlatform.SNAPCHAT -> scrollDao.addSnapchatBonus(today, amount)
            }

            val result = scrollDao.getRecordForDateSync(today) ?: getOrCreateTodayRecordSync(today)
            ReelsPalWidgetProvider.updateAllWidgets(context)
            result
        }

    suspend fun setTodayLimits(
        instagramLimit: Int,
        youtubeLimit: Int,
        facebookLimit: Int = 30,
        snapchatLimit: Int = 30
    ): Boolean = withContext(Dispatchers.IO) {
        val today = getTodayDateString()
        val current = getOrCreateTodayRecordSync(today)

        // Lock rule: once limit is set today, it cannot be modified before midnight
        if (current.limitSetToday) {
            return@withContext false
        }

        scrollDao.updateLimitsForToday(
            date = today,
            igLimit = instagramLimit.coerceAtLeast(1),
            ytLimit = youtubeLimit.coerceAtLeast(1),
            fbLimit = facebookLimit.coerceAtLeast(1),
            scLimit = snapchatLimit.coerceAtLeast(1)
        )
        ReelsPalWidgetProvider.updateAllWidgets(context)
        true
    }

    fun getLast7DaysRecords(): Flow<List<DailyScrollRecord>> {
        return scrollDao.getRecentRecords(7)
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)
    }

    fun getAllRecords(): Flow<List<DailyScrollRecord>> {
        return scrollDao.getAllRecords()
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)
    }

    suspend fun calculateStats(): ScrollStats = withContext(Dispatchers.IO) {
        val records = mutableListOf<DailyScrollRecord>()
        val calendar = Calendar.getInstance()

        // Fetch past 7 days explicitly to ensure contiguous dates
        for (i in 0 until 7) {
            val dateStr = dateFormat.format(calendar.time)
            val rec = scrollDao.getRecordForDateSync(dateStr) ?: DailyScrollRecord(
                dateString = dateStr,
                instagramCount = 0,
                youtubeCount = 0,
                facebookCount = 0,
                snapchatCount = 0,
                instagramLimit = 30,
                youtubeLimit = 30,
                facebookLimit = 30,
                snapchatLimit = 30
            )
            records.add(rec)
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }

        // Streak calculation (days staying under limit across all platforms)
        var streak = 0
        val checkCal = Calendar.getInstance()
        checkCal.add(Calendar.DAY_OF_YEAR, -1) // check from yesterday backwards for streak
        for (i in 0 until 60) {
            val dateStr = dateFormat.format(checkCal.time)
            val r = scrollDao.getRecordForDateSync(dateStr)
            if (r != null) {
                val igExceeded = r.instagramCount > r.totalInstagramAllowed
                val ytExceeded = r.youtubeCount > r.totalYoutubeAllowed
                val fbExceeded = r.facebookCount > r.totalFacebookAllowed
                val scExceeded = r.snapchatCount > r.totalSnapchatAllowed
                if (!igExceeded && !ytExceeded && !fbExceeded && !scExceeded) {
                    streak++
                } else {
                    break
                }
            } else {
                break
            }
            checkCal.add(Calendar.DAY_OF_YEAR, -1)
        }

        val totalLast7Days = records.sumOf { it.totalScrollsToday }
        val dailyAverage = if (records.isNotEmpty()) totalLast7Days.toFloat() / records.size else 0f
        val totalIg = records.sumOf { it.instagramCount }
        val totalYt = records.sumOf { it.youtubeCount }
        val totalFb = records.sumOf { it.facebookCount }
        val totalSc = records.sumOf { it.snapchatCount }

        ScrollStats(
            last7Days = records.reversed(), // oldest to newest for charts
            dailyAverage = dailyAverage,
            currentStreakDays = streak,
            totalInstagram7Days = totalIg,
            totalYoutube7Days = totalYt,
            totalFacebook7Days = totalFb,
            totalSnapchat7Days = totalSc
        )
    }

    companion object {
        @Volatile
        private var INSTANCE: ScrollRepository? = null

        fun getInstance(context: Context): ScrollRepository {
            return INSTANCE ?: synchronized(this) {
                val appCtx = context.applicationContext
                INSTANCE ?: ScrollRepository(
                    appCtx,
                    AppDatabase.getDatabase(appCtx).scrollDao()
                ).also { INSTANCE = it }
            }
        }
    }
}

data class ScrollStats(
    val last7Days: List<DailyScrollRecord>,
    val dailyAverage: Float,
    val currentStreakDays: Int,
    val totalInstagram7Days: Int,
    val totalYoutube7Days: Int,
    val totalFacebook7Days: Int = 0,
    val totalSnapchat7Days: Int = 0
)

