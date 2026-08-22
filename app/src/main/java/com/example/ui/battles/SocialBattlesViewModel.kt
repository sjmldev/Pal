package com.example.ui.battles

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.BattleRoom
import com.example.data.repository.BattleRepository
import com.example.data.repository.ScrollRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SocialBattlesViewModel(application: Application) : AndroidViewModel(application) {

    private val battleRepository = BattleRepository.getInstance(application)
    private val scrollRepository = ScrollRepository.getInstance(application)

    val activeRoom: StateFlow<BattleRoom?> = battleRepository.activeRoom

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            scrollRepository.getTodayRecordFlow().collect { record ->
                battleRepository.syncUserScrolls(
                    userScrolls = record.totalScrollsToday,
                    userLimit = record.totalAllowedToday
                )
            }
        }
    }

    fun createNewRoom(roomName: String) {
        viewModelScope.launch {
            try {
                val record = scrollRepository.getOrCreateTodayRecord()
                val room = battleRepository.createRoom(
                    roomName = roomName.ifBlank { "Focus Champions" },
                    userScrolls = record.totalScrollsToday,
                    userLimit = record.totalAllowedToday
                )
                _message.value = "Created Room ${room.roomCode}! Invite your friends."
            } catch (e: Exception) {
                _message.value = "Error creating room: ${e.message}"
            }
        }
    }

    fun joinRoom(code: String) {
        if (code.isBlank()) {
            _message.value = "Please enter a valid room code"
            return
        }
        viewModelScope.launch {
            try {
                val record = scrollRepository.getOrCreateTodayRecord()
                val room = battleRepository.joinRoom(
                    roomCode = code,
                    userScrolls = record.totalScrollsToday,
                    userLimit = record.totalAllowedToday
                )
                _message.value = "Joined ${room.roomName}!"
            } catch (e: Exception) {
                _message.value = "Error joining room: ${e.message}"
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
