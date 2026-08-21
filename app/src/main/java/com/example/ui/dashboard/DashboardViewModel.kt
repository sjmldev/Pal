package com.example.ui.dashboard

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ads.AdManager
import com.example.data.model.DailyScrollRecord
import com.example.data.model.ScrollPlatform
import com.example.data.preferences.AppPreferences
import com.example.data.repository.ScrollRepository
import com.example.data.repository.ScrollStats
import com.example.service.FloatingHudService
import com.example.service.HudOverlayManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DashboardUiState(
    val todayRecord: DailyScrollRecord? = null,
    val stats: ScrollStats = ScrollStats(emptyList(), 0f, 0, 0, 0),
    val isHudEnabled: Boolean = true,
    val isUnlockingBonus: Boolean = false,
    val message: String? = null
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ScrollRepository.getInstance(application)
    private val preferences = AppPreferences.getInstance(application)
    private val hudManager = HudOverlayManager.getInstance(application)

    private val _isUnlockingBonus = MutableStateFlow(false)
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _stats = MutableStateFlow(ScrollStats(emptyList(), 0f, 0, 0, 0))

    val uiState: StateFlow<DashboardUiState> = combine(
        repository.getTodayRecordFlow(),
        _stats,
        _isUnlockingBonus
    ) { today, stats, isUnlocking ->
        DashboardUiState(
            todayRecord = today,
            stats = stats,
            isHudEnabled = preferences.isHudOverlayEnabled,
            isUnlockingBonus = isUnlocking,
            message = _message.value
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DashboardUiState()
    )

    init {
        refreshData()
    }

    fun refreshData() {
        viewModelScope.launch {
            repository.getOrCreateTodayRecord()
            _stats.value = repository.calculateStats()
        }
    }

    fun unlockExtraScrolls(platform: ScrollPlatform, activity: Activity) {
        _isUnlockingBonus.value = true
        AdManager.showRewardedAdForExtraScrolls(activity) { granted ->
            viewModelScope.launch {
                val updated = repository.unlockExtraScrolls(platform, 10)
                _isUnlockingBonus.value = false
                _message.value = "🎉 +10 Extra Scrolls Unlocked for ${platform.displayName}!"
                refreshData()
            }
        }
    }

    fun toggleHudOverlay(enabled: Boolean) {
        preferences.isHudOverlayEnabled = enabled
        if (!enabled) {
            hudManager.hideHud()
            FloatingHudService.stop(getApplication())
        }
        refreshData()
    }

    fun clearMessage() {
        _message.value = null
    }
}
