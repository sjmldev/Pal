package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.data.model.DailyScrollRecord
import com.example.data.model.ScrollEventLog
import kotlinx.coroutines.flow.Flow

@Dao
interface ScrollDao {

    @Query("SELECT * FROM daily_scroll_records WHERE dateString = :date LIMIT 1")
    fun getRecordForDate(date: String): Flow<DailyScrollRecord?>

    @Query("SELECT * FROM daily_scroll_records WHERE dateString = :date LIMIT 1")
    suspend fun getRecordForDateSync(date: String): DailyScrollRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateRecord(record: DailyScrollRecord)

    @Query("SELECT * FROM daily_scroll_records ORDER BY dateString DESC LIMIT :limit")
    fun getRecentRecords(limit: Int = 14): Flow<List<DailyScrollRecord>>

    @Query("SELECT * FROM daily_scroll_records ORDER BY dateString DESC")
    fun getAllRecords(): Flow<List<DailyScrollRecord>>

    @Query("SELECT * FROM daily_scroll_records WHERE dateString < :date ORDER BY dateString DESC LIMIT 1")
    suspend fun getLatestPreviousRecord(date: String): DailyScrollRecord?

    @Query("UPDATE daily_scroll_records SET instagramCount = instagramCount + :amount, lastUpdatedTimestamp = :now WHERE dateString = :date")
    suspend fun incrementInstagramCount(date: String, amount: Int = 1, now: Long = System.currentTimeMillis())

    @Query("UPDATE daily_scroll_records SET youtubeCount = youtubeCount + :amount, lastUpdatedTimestamp = :now WHERE dateString = :date")
    suspend fun incrementYoutubeCount(date: String, amount: Int = 1, now: Long = System.currentTimeMillis())

    @Query("UPDATE daily_scroll_records SET instagramUnlockedBonus = instagramUnlockedBonus + :bonus, lastUpdatedTimestamp = :now WHERE dateString = :date")
    suspend fun addInstagramBonus(date: String, bonus: Int = 10, now: Long = System.currentTimeMillis())

    @Query("UPDATE daily_scroll_records SET youtubeUnlockedBonus = youtubeUnlockedBonus + :bonus, lastUpdatedTimestamp = :now WHERE dateString = :date")
    suspend fun addYoutubeBonus(date: String, bonus: Int = 10, now: Long = System.currentTimeMillis())

    @Query("UPDATE daily_scroll_records SET instagramLimit = :igLimit, youtubeLimit = :ytLimit, limitSetToday = 1, lastUpdatedTimestamp = :now WHERE dateString = :date")
    suspend fun updateLimitsForToday(date: String, igLimit: Int, ytLimit: Int, now: Long = System.currentTimeMillis())

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEventLog(log: ScrollEventLog)

    @Query("SELECT * FROM scroll_event_logs WHERE dateString = :date ORDER BY timestamp DESC LIMIT 50")
    fun getRecentLogsForDate(date: String): Flow<List<ScrollEventLog>>
}
