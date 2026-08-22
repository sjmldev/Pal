package com.example.data.model

data class BattleParticipant(
    val id: String,
    val name: String,
    val avatarEmoji: String,
    val scrollsToday: Int,
    val limit: Int,
    val isCurrentUser: Boolean = false,
    val status: DuelStatus = DuelStatus.SAFE
) {
    val progressFraction: Float
        get() = (scrollsToday.toFloat() / limit.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)

    val remainingScrolls: Int
        get() = (limit - scrollsToday).coerceAtLeast(0)
}

enum class DuelStatus(val label: String, val emoji: String) {
    LEADING("Focus Master", "👑"),
    SAFE("Under Limit", "🛡️"),
    WARNING("Near Limit", "⚡"),
    EXCEEDED("Limit Breached", "🚨")
}

data class BattleRoom(
    val roomCode: String,
    val roomName: String,
    val createdAt: Long,
    val participants: List<BattleParticipant>,
    val targetDailyLimit: Int = 50
)
