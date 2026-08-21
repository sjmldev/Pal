package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.model.DailyScrollRecord
import com.example.data.model.ScrollPlatform
import com.example.data.preferences.AppPreferences
import com.example.data.repository.ScrollRepository
import com.example.receiver.ReminderManager
import com.example.ui.blocked.BlockedActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReelsPalAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repository: ScrollRepository
    private lateinit var preferences: AppPreferences
    private lateinit var hudManager: HudOverlayManager

    private var currentRecord: DailyScrollRecord? = null
    private var activePlatform: ScrollPlatform? = null
    private var isInReelsOrShortsSection = false

    // Real-time synchronized in-memory counters for zero-latency live UI updates
    private var inMemoryIgCount: Int = 0
    private var inMemoryYtCount: Int = 0
    private var inMemoryIgLimit: Int = 30
    private var inMemoryYtLimit: Int = 30
    private var inMemoryDateString: String = ""

    // Tracking state to detect complete video transitions and prevent duplicates
    private val transitionLock = Any()
    private var currentActiveVideoKey: String = ""
    private var lastCountTimestamp: Long = 0L
    private val recentVideoHistory = LinkedHashMap<String, Long>()

    // Minimum cooldown between distinct swipes (400ms) to ensure single physical gesture deduplication
    private val MIN_SWIPE_COOLDOWN_MS = 400L
    private val RECENT_HISTORY_EXPIRY_MS = 30_000L // 30 seconds memory of watched reels

    // Debounce redirect to prevent rapid flickering loop
    private var lastRedirectTimestamp: Long = 0L

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        instance = this
        repository = ScrollRepository.getInstance(this)
        preferences = AppPreferences.getInstance(this)
        hudManager = HudOverlayManager.getInstance(this)

        serviceScope.launch {
            repository.getTodayRecordFlow().collect { record ->
                synchronized(this@ReelsPalAccessibilityService) {
                    currentRecord = record
                    if (inMemoryDateString != record.dateString) {
                        inMemoryDateString = record.dateString
                        inMemoryIgCount = record.instagramCount
                        inMemoryYtCount = record.youtubeCount
                    } else {
                        // Advance in-memory count if DB has a higher count
                        if (record.instagramCount > inMemoryIgCount) {
                            inMemoryIgCount = record.instagramCount
                        }
                        if (record.youtubeCount > inMemoryYtCount) {
                            inMemoryYtCount = record.youtubeCount
                        }
                    }
                    inMemoryIgLimit = record.totalInstagramAllowed
                    inMemoryYtLimit = record.totalYoutubeAllowed
                }

                // Update HUD if visible
                val platform = activePlatform
                if (isInReelsOrShortsSection && platform != null && preferences.isHudOverlayEnabled) {
                    val count = if (platform == ScrollPlatform.INSTAGRAM) inMemoryIgCount else inMemoryYtCount
                    val limit = if (platform == ScrollPlatform.INSTAGRAM) inMemoryIgLimit else inMemoryYtLimit
                    hudManager.showOrUpdateHud(platform, count, limit)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 50
            packageNames = arrayOf(
                ScrollPlatform.INSTAGRAM.packageName,
                ScrollPlatform.YOUTUBE.packageName
            )
        }
        serviceInfo = info
        Log.d(TAG, "ReelsPal Accessibility Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return

        val platform = when (packageName) {
            ScrollPlatform.INSTAGRAM.packageName -> ScrollPlatform.INSTAGRAM
            ScrollPlatform.YOUTUBE.packageName -> ScrollPlatform.YOUTUBE
            else -> {
                handleExitedTargetApp()
                return
            }
        }

        activePlatform = platform

        try {
            val rootNode = rootInActiveWindow ?: return
            handleTargetAppEvent(platform, rootNode, event)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling accessibility event: ${e.message}", e)
        }
    }

    private fun handleTargetAppEvent(
        platform: ScrollPlatform,
        rootNode: AccessibilityNodeInfo,
        event: AccessibilityEvent
    ) {
        val (isBlocked, count, limit) = synchronized(this) {
            val count = if (platform == ScrollPlatform.INSTAGRAM) inMemoryIgCount else inMemoryYtCount
            val limit = if (platform == ScrollPlatform.INSTAGRAM) inMemoryIgLimit else inMemoryYtLimit
            val blocked = count >= limit
            Triple(blocked, count, limit)
        }

        // 1. Check if the app is currently BLOCKED
        if (isBlocked) {
            // Check if user is in the blocked app / reels section
            val isInsideTarget = isInsideShortsOrReels(platform, rootNode)
            if (isInsideTarget || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                enforceBlockAndRedirect(platform)
                return
            }
        }

        // 2. Detect if user is specifically inside Reels or Shorts viewer
        val inShortsOrReels = isInsideShortsOrReels(platform, rootNode)

        if (!inShortsOrReels) {
            if (isInReelsOrShortsSection) {
                isInReelsOrShortsSection = false
                hudManager.hideHud()
                synchronized(transitionLock) {
                    currentActiveVideoKey = ""
                }
            }
            return
        }

        isInReelsOrShortsSection = true

        // 3. Ensure HUD is displayed when entering Reels section with live in-memory count
        if (preferences.isHudOverlayEnabled) {
            hudManager.showOrUpdateHud(platform, count, limit)
        }

        // 4. Check if comments section or pause / overlay sheet is currently open
        if (isCommentsSectionOpen(platform, rootNode)) {
            // Do NOT count scrolls while comment drawer is open
            return
        }

        // 5. Detect genuine page-to-page video transitions and update live count
        checkAndCountVideoTransition(platform, rootNode)
    }

    private fun isInsideShortsOrReels(platform: ScrollPlatform, rootNode: AccessibilityNodeInfo): Boolean {
        return when (platform) {
            ScrollPlatform.INSTAGRAM -> {
                // Search for Reels viewer indicators in Instagram
                findNodeByPredicate(rootNode, maxDepth = 14) { node ->
                    val resId = node.viewIdResourceName?.lowercase() ?: ""
                    val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                    val text = node.text?.toString()?.lowercase() ?: ""

                    resId.contains("clips_viewer") ||
                            resId.contains("reel_viewer") ||
                            resId.contains("clips_video_container") ||
                            resId.contains("reel_recycler") ||
                            (resId.contains("like_button") && resId.contains("clips")) ||
                            desc.contains("reel by") ||
                            desc.contains("audio used in reel") ||
                            (resId.contains("video_container") && (desc.contains("reel") || text.contains("reel"))) ||
                            text.equals("reels", ignoreCase = true) ||
                            resId.contains("clips_author") ||
                            resId.contains("reel_tag")
                }
            }
            ScrollPlatform.YOUTUBE -> {
                // Search for YouTube Shorts indicators
                findNodeByPredicate(rootNode, maxDepth = 14) { node ->
                    val resId = node.viewIdResourceName?.lowercase() ?: ""
                    val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                    val text = node.text?.toString()?.lowercase() ?: ""

                    resId.contains("reel_player") ||
                            resId.contains("shorts_container") ||
                            resId.contains("reel_recycler") ||
                            resId.contains("shorts_player_view") ||
                            desc.contains("shorts") ||
                            desc.contains("dislike this short") ||
                            desc.contains("like this short") ||
                            resId.contains("sound_button") ||
                            resId.contains("remix_button")
                }
            }
        }
    }

    private fun isCommentsSectionOpen(platform: ScrollPlatform, rootNode: AccessibilityNodeInfo): Boolean {
        return findNodeByPredicate(rootNode, maxDepth = 10) { node ->
            val resId = node.viewIdResourceName?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""

            resId.contains("comment_sheet") ||
                    resId.contains("comments_recycler") ||
                    resId.contains("comment_composer") ||
                    resId.contains("comments_list") ||
                    (resId.contains("bottom_sheet") && text.contains("comments", ignoreCase = true)) ||
                    desc.contains("close comments", ignoreCase = true) ||
                    text.contains("add a comment", ignoreCase = true) ||
                    text.contains("top comments", ignoreCase = true)
        }
    }

    private fun checkAndCountVideoTransition(
        platform: ScrollPlatform,
        rootNode: AccessibilityNodeInfo
    ) {
        val activeKey = extractActiveVideoIdentifier(platform, rootNode)
        if (activeKey.isBlank()) return

        val now = System.currentTimeMillis()

        val shouldCount: Boolean
        synchronized(transitionLock) {
            // Clean up history older than 30s
            val iterator = recentVideoHistory.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value > RECENT_HISTORY_EXPIRY_MS) {
                    iterator.remove()
                }
            }

            if (currentActiveVideoKey.isEmpty()) {
                // Initial video landing when opening viewer — register without incrementing
                currentActiveVideoKey = activeKey
                recentVideoHistory[activeKey] = now
                Log.d(TAG, "Initial video node registered: $activeKey")
                return
            }

            // Same video node as active
            if (currentActiveVideoKey == activeKey) {
                return
            }

            // Already counted in recent history buffer
            if (recentVideoHistory.containsKey(activeKey)) {
                currentActiveVideoKey = activeKey
                return
            }

            // Cooldown check (minimum 400ms after last confirmed swipe)
            if (now - lastCountTimestamp < MIN_SWIPE_COOLDOWN_MS) {
                Log.d(TAG, "Ignored duplicate transition event within cooldown: ${now - lastCountTimestamp}ms")
                return
            }

            // Verified genuine new transition
            currentActiveVideoKey = activeKey
            lastCountTimestamp = now
            recentVideoHistory[activeKey] = now

            if (recentVideoHistory.size > 25) {
                val oldest = recentVideoHistory.keys.firstOrNull()
                if (oldest != null) recentVideoHistory.remove(oldest)
            }

            shouldCount = true
        }

        if (shouldCount) {
            Log.d(TAG, "Genuine single swipe counted for ${platform.displayName}: $activeKey")
            onScrollDetected(platform, activeKey)
        }
    }

    private fun extractActiveVideoIdentifier(
        platform: ScrollPlatform,
        rootNode: AccessibilityNodeInfo
    ): String {
        val identifiers = StringBuilder()
        val textSnippets = mutableListOf<String>()

        val prefix = if (platform == ScrollPlatform.INSTAGRAM) "ig:" else "yt:"
        identifiers.append(prefix)

        traverseNodes(rootNode, maxDepth = 14) { node ->
            val resId = node.viewIdResourceName?.lowercase() ?: ""
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""

            when (platform) {
                ScrollPlatform.INSTAGRAM -> {
                    // 1. Author and handle matching (highest priority unique key)
                    if (resId.contains("user_name") || resId.contains("username") ||
                        resId.contains("profile_name") || resId.contains("author") ||
                        resId.contains("clips_author_name") || resId.contains("row_feed_photo_profile_name") ||
                        resId.contains("owner_name")
                    ) {
                        if (text.isNotEmpty() && !isIgnoredUiText(text)) {
                            identifiers.append("u:").append(text.lowercase()).append("|")
                        }
                    }
                    // 2. Audio title matching
                    else if (resId.contains("audio_title") || resId.contains("music_title") ||
                        resId.contains("sound_title") || resId.contains("audio_track")
                    ) {
                        if (text.isNotEmpty() && !isIgnoredUiText(text)) {
                            identifiers.append("a:").append(text.lowercase().take(30)).append("|")
                        }
                    }
                    // 3. Caption matching
                    else if (resId.contains("caption") || resId.contains("clips_caption") ||
                        resId.contains("caption_text_view")
                    ) {
                        if (text.isNotEmpty() && text.length >= 4 && !isIgnoredUiText(text)) {
                            identifiers.append("c:").append(text.lowercase().take(25)).append("|")
                        }
                    }
                    // 4. Accessibility descriptions
                    if (desc.startsWith("Reel by", ignoreCase = true) ||
                        desc.startsWith("Photo by", ignoreCase = true) ||
                        desc.startsWith("Video by", ignoreCase = true) ||
                        desc.contains("audio used in reel", ignoreCase = true)
                    ) {
                        identifiers.append("d:").append(desc.lowercase().take(40)).append("|")
                    }

                    // 5. Stable text fallback
                    if (text.isNotEmpty() && text.length in 3..40 && !isIgnoredUiText(text)) {
                        textSnippets.add(text.lowercase())
                    }
                }
                ScrollPlatform.YOUTUBE -> {
                    if (resId.contains("channel_name") || resId.contains("video_title") ||
                        resId.contains("sound_title") || resId.contains("title_text") ||
                        resId.contains("owner_name")
                    ) {
                        if (text.isNotEmpty() && !isIgnoredUiText(text)) {
                            identifiers.append("c:").append(text.lowercase().take(30)).append("|")
                        }
                    }
                    if (desc.contains("Short by", ignoreCase = true) || desc.contains("Video", ignoreCase = true)) {
                        identifiers.append("d:").append(desc.lowercase().take(40)).append("|")
                    }
                    if (text.isNotEmpty() && text.length in 3..40 && !isIgnoredUiText(text)) {
                        textSnippets.add(text.lowercase())
                    }
                }
            }
        }

        // If we extracted structured identifiers (more than just the prefix)
        if (identifiers.length > prefix.length) {
            return identifiers.toString()
        }

        // Fallback to top distinctive non-numeric text snippets
        return if (textSnippets.isNotEmpty()) {
            prefix + textSnippets.take(2).joinToString(separator = "|")
        } else {
            ""
        }
    }

    private fun isIgnoredUiText(rawText: String): Boolean {
        val text = rawText.trim().lowercase()
        if (text.isEmpty()) return true

        // Filter out dynamic numeric counters (views, likes, timestamps, counts)
        if (text.matches(Regex("^[0-9.,kKmMbB: ]+$"))) return true
        if (text.matches(Regex("^[0-9]+.*(likes?|views?|comments?|shares?|k|m|b)$"))) return true

        // Filter generic action buttons and UI boilerplate
        val ignoredTokens = setOf(
            "reels", "shorts", "follow", "following", "like", "liked", "dislike",
            "comment", "comments", "share", "remix", "subscribe", "subscribed",
            "save", "saved", "more", "audio", "original audio", "sponsored",
            "suggested for you", "use audio", "watch again", "reply", "see translation"
        )
        return ignoredTokens.contains(text)
    }

    private fun onScrollDetected(platform: ScrollPlatform, identifier: String) {
        var newCount: Int
        var limit: Int
        var limitExceeded: Boolean
        var dateStr: String

        synchronized(this) {
            newCount = if (platform == ScrollPlatform.INSTAGRAM) {
                inMemoryIgCount++
                inMemoryIgCount
            } else {
                inMemoryYtCount++
                inMemoryYtCount
            }
            limit = if (platform == ScrollPlatform.INSTAGRAM) inMemoryIgLimit else inMemoryYtLimit
            limitExceeded = newCount >= limit
            dateStr = currentRecord?.dateString ?: inMemoryDateString.ifEmpty { repository.getTodayDateString() }

            // Synchronously update currentRecord reference so any concurrent reads have the latest count immediately
            currentRecord = currentRecord?.let { rec ->
                if (platform == ScrollPlatform.INSTAGRAM) rec.copy(instagramCount = newCount)
                else rec.copy(youtubeCount = newCount)
            }
        }

        // 1. Live Instant UI update on Main Thread immediately for every single swipe
        if (preferences.isHudOverlayEnabled) {
            hudManager.showOrUpdateHud(platform, newCount, limit)
        }

        // 2. Check 10% milestone notifications
        checkAndTriggerMilestoneNotification(platform, newCount, limit, dateStr)

        if (limitExceeded) {
            enforceBlockAndRedirect(platform)
        }

        // 3. Persist to Database asynchronously in the background without holding up the HUD UI
        serviceScope.launch {
            try {
                val updated = repository.incrementScroll(platform, identifier)
                synchronized(this@ReelsPalAccessibilityService) {
                    if (updated.instagramCount > inMemoryIgCount) {
                        inMemoryIgCount = updated.instagramCount
                    }
                    if (updated.youtubeCount > inMemoryYtCount) {
                        inMemoryYtCount = updated.youtubeCount
                    }
                    inMemoryIgLimit = updated.totalInstagramAllowed
                    inMemoryYtLimit = updated.totalYoutubeAllowed
                    currentRecord = updated
                }
                Log.d(TAG, "Scroll counted and persisted for ${platform.displayName}! Realtime count: $newCount")
            } catch (e: Exception) {
                Log.e(TAG, "Error recording scroll: ${e.message}", e)
            }
        }
    }

    /**
     * Triggers a calm/slow notification at every 10% progress step (10%, 20%, 30%... 100%).
     */
    private fun checkAndTriggerMilestoneNotification(
        platform: ScrollPlatform,
        count: Int,
        limit: Int,
        dateString: String
    ) {
        if (!preferences.isProgressNotificationsEnabled) return
        if (limit <= 0) return

        val percent = ((count.toFloat() / limit.toFloat()) * 100).toInt()
        // Calculate current 10% milestone bracket (e.g., 10, 20, 30... 100)
        val currentMilestone = (percent / 10) * 10

        if (currentMilestone in 10..100) {
            val lastNotified = if (platform == ScrollPlatform.INSTAGRAM) {
                preferences.getLastNotifiedMilestoneIg(dateString)
            } else {
                preferences.getLastNotifiedMilestoneYt(dateString)
            }

            // Only notify if user has stepped into a new 10% bracket
            if (currentMilestone > lastNotified) {
                if (platform == ScrollPlatform.INSTAGRAM) {
                    preferences.setLastNotifiedMilestoneIg(dateString, currentMilestone)
                } else {
                    preferences.setLastNotifiedMilestoneYt(dateString, currentMilestone)
                }

                ReminderManager.sendMilestoneProgressNotification(
                    context = this,
                    platformName = platform.displayName,
                    currentCount = count,
                    limit = limit,
                    percentage = currentMilestone
                )
            }
        }
    }

    private fun enforceBlockAndRedirect(platform: ScrollPlatform) {
        val now = System.currentTimeMillis()
        if (now - lastRedirectTimestamp < 1500) return // Prevent redirect spam
        lastRedirectTimestamp = now

        hudManager.hideHud()

        // 1. Kick back to home screen immediately
        performGlobalAction(GLOBAL_ACTION_HOME)

        // 2. Open BlockedActivity overlay
        handler.postDelayed({
            try {
                val intent = Intent(this, BlockedActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(BlockedActivity.EXTRA_PLATFORM, platform.name)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error opening BlockedActivity: ${e.message}", e)
            }
        }, 300)
    }

    private fun handleExitedTargetApp() {
        if (isInReelsOrShortsSection) {
            isInReelsOrShortsSection = false
            hudManager.hideHud()
        }
        activePlatform = null
        synchronized(transitionLock) {
            currentActiveVideoKey = ""
        }
    }

    private fun findNodeByPredicate(
        node: AccessibilityNodeInfo?,
        depth: Int = 0,
        maxDepth: Int = 10,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): Boolean {
        if (node == null || depth > maxDepth) return false
        if (predicate(node)) return true

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findNodeByPredicate(child, depth + 1, maxDepth, predicate)) {
                return true
            }
        }
        return false
    }

    private fun traverseNodes(
        node: AccessibilityNodeInfo?,
        depth: Int = 0,
        maxDepth: Int = 10,
        visitor: (AccessibilityNodeInfo) -> Unit
    ) {
        if (node == null || depth > maxDepth) return
        visitor(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNodes(child, depth + 1, maxDepth, visitor)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "ReelsPal Accessibility Service interrupted")
        hudManager.hideHud()
    }

    override fun onDestroy() {
        instance = null
        hudManager.hideHud()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ReelsPalAccessibility"

        @Volatile
        var instance: ReelsPalAccessibilityService? = null
            private set

        fun isServiceRunning(): Boolean = instance != null
    }
}
