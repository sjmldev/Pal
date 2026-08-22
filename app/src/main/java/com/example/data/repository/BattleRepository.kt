package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.data.model.BattleParticipant
import com.example.data.model.BattleRoom
import com.example.data.model.DuelStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.random.Random

class BattleRepository private constructor(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("reels_pal_battles", Context.MODE_PRIVATE)

    private val _activeRoom = MutableStateFlow<BattleRoom?>(null)
    val activeRoom: StateFlow<BattleRoom?> = _activeRoom.asStateFlow()

    init {
        loadSavedRoom()
    }

    private fun loadSavedRoom() {
        val savedCode = prefs.getString(KEY_ACTIVE_ROOM_CODE, null)
        if (savedCode != null) {
            val savedName = prefs.getString(KEY_ROOM_NAME, "Focus Champions Duel") ?: "Focus Champions Duel"
            _activeRoom.value = generateRoomWithCode(savedCode, savedName, 0)
        } else {
            // Default sample public friendly battle room
            _activeRoom.value = generateRoomWithCode("FOCUS-784", "Anti-Doomscroll Squad", 0)
        }
    }

    fun syncUserScrolls(userScrolls: Int, userLimit: Int) {
        val current = _activeRoom.value ?: return
        val updatedParticipants = current.participants.map { participant ->
            if (participant.isCurrentUser) {
                val status = calculateStatus(userScrolls, userLimit)
                participant.copy(
                    scrollsToday = userScrolls,
                    limit = userLimit,
                    status = status
                )
            } else {
                participant
            }
        }.sortedBy { it.scrollsToday } // Rank 1 is least scrolls (Most Focused)

        _activeRoom.value = current.copy(participants = updatedParticipants)
    }

    suspend fun createRoom(roomName: String, userScrolls: Int, userLimit: Int): BattleRoom = withContext(Dispatchers.IO) {
        val code = "PAL-" + (100..999).random()
        prefs.edit()
            .putString(KEY_ACTIVE_ROOM_CODE, code)
            .putString(KEY_ROOM_NAME, roomName)
            .apply()

        val room = generateRoomWithCode(code, roomName, userScrolls, userLimit)
        _activeRoom.value = room
        room
    }

    suspend fun joinRoom(roomCode: String, userScrolls: Int, userLimit: Int): BattleRoom = withContext(Dispatchers.IO) {
        val normalizedCode = roomCode.trim().uppercase()
        val roomName = "Battle Room #$normalizedCode"
        prefs.edit()
            .putString(KEY_ACTIVE_ROOM_CODE, normalizedCode)
            .putString(KEY_ROOM_NAME, roomName)
            .apply()

        val room = generateRoomWithCode(normalizedCode, roomName, userScrolls, userLimit)
        _activeRoom.value = room
        room
    }

    private fun generateRoomWithCode(
        code: String,
        roomName: String,
        userScrolls: Int,
        userLimit: Int = 60
    ): BattleRoom {
        val seed = code.hashCode().toLong()
        val random = Random(seed)

        val friendNames = listOf("Alex (Friend)", "Maya (Study Buddy)", "Liam (Focus Partner)")
        val friendEmojis = listOf("🚀", "🧘‍♀️", "🎯")

        val friendParticipants = friendNames.mapIndexed { index, name ->
            val friendScrolls = (random.nextInt(15, 55))
            val friendLimit = 50
            BattleParticipant(
                id = "friend_$index",
                name = name,
                avatarEmoji = friendEmojis[index],
                scrollsToday = friendScrolls,
                limit = friendLimit,
                isCurrentUser = false,
                status = calculateStatus(friendScrolls, friendLimit)
            )
        }

        val userParticipant = BattleParticipant(
            id = "user_me",
            name = "You (Current Device)",
            avatarEmoji = "🧠",
            scrollsToday = userScrolls,
            limit = userLimit,
            isCurrentUser = true,
            status = calculateStatus(userScrolls, userLimit)
        )

        val allParticipants = (friendParticipants + userParticipant).sortedBy { it.scrollsToday }

        return BattleRoom(
            roomCode = code,
            roomName = roomName,
            createdAt = System.currentTimeMillis(),
            participants = allParticipants,
            targetDailyLimit = userLimit
        )
    }

    private fun calculateStatus(scrolls: Int, limit: Int): DuelStatus {
        val fraction = scrolls.toFloat() / limit.coerceAtLeast(1).toFloat()
        return when {
            fraction >= 1.0f -> DuelStatus.EXCEEDED
            fraction >= 0.75f -> DuelStatus.WARNING
            fraction <= 0.35f -> DuelStatus.LEADING
            else -> DuelStatus.SAFE
        }
    }

    companion object {
        private const val KEY_ACTIVE_ROOM_CODE = "active_room_code"
        private const val KEY_ROOM_NAME = "active_room_name"

        @Volatile
        private var INSTANCE: BattleRepository? = null

        fun getInstance(context: Context): BattleRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BattleRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
