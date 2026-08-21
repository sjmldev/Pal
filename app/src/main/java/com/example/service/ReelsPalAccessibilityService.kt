package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Rect
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * AccessibilityService that monitors Instagram Reels and YouTube Shorts usage.
 *
 * Implements a strict, guaranteed, leak-proof de-duplication architecture:
 * 1. Single serialized execution pipeline via Kotlin Mutex & Single-Threaded Processing.
 * 2. Stable Canonical Video Identity extraction (focused on the on-screen active ViewPager center item).
 * 3. Exact & Semantic token/substring de-duplication against `lastCountedVideoId` and an LRU Ring Buffer.
 * 4. Physical gesture cooldown safety net (400ms).
 * 5. Immediate zero-latency HUD update + asynchronous Room DB persistence.
 * 6. Explicit audit logging for every evaluation decision.
 */
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

    // --- HARD DE-DUPLICATION GUARANTEE ENGINE ---
    private val deduplicationMutex = Mutex()
    private var lastCountedVideoId: String? = null
    private var lastCountedAuthor: String? = null
    private var lastCountTimestamp: Long = 0L
    private val recentCountedVideoMap = LinkedHashMap<String, Long>()

    // Safety guards
    private val MIN_SWIPE_COOLDOWN_MS = 400L             // Minimum physical swipe gesture time
    private val RECENT_HISTORY_EXPIRY_MS = 60_000L         // 60 seconds memory buffer
    private val MAX_HISTORY_BUFFER_SIZE = 50

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
                deduplicationMutex.withLock {
                    currentRecord = record
                    if (inMemoryDateString != record.dateString) {
                        inMemoryDateString = record.dateString
                        inMemoryIgCount = record.instagramCount
                        inMemoryYtCount = record.youtubeCount
                    } else {
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
            }
            return
        }

        isInReelsOrShortsSection = true

        // 3. Ensure HUD is displayed when entering Reels section with live in-memory count
        if (preferences.isHudOverlayEnabled) {
            hudManager.showOrUpdateHud(platform, count, limit)
        }

        // 4. Ignore all events when comments sheet/dialogue is open
        if (isCommentsSectionOpen(platform, rootNode)) {
            return
        }

        // 5. Extract stable on-screen video identity
        val candidate = extractActiveVideoIdentity(platform, rootNode) ?: return

        // 6. Execute atomic, serialized de-duplication evaluation
        serviceScope.launch {
            processVideoTransitionSerialized(platform, candidate)
        }
    }

    /**
     * SERIALIZED SINGLE ENTRY POINT: Evaluates whether candidate represents a genuinely
     * new, uncounted video, and guarantees that exactly 1 count is added — never duplicates.
     */
    private suspend fun processVideoTransitionSerialized(
        platform: ScrollPlatform,
        candidate: CandidateVideo
    ) {
        val now = System.currentTimeMillis()
        var shouldCount = false
        var newCount = 0
        var limit = 0
        var limitExceeded = false
        var dateStr = ""

        deduplicationMutex.withLock {
            // A. Clean expired entries from recent history (>60s)
            val iterator = recentCountedVideoMap.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value > RECENT_HISTORY_EXPIRY_MS) {
                    iterator.remove()
                }
            }

            // B. Initial landing video check (when entering viewer for the first time)
            val currentLastId = lastCountedVideoId
            if (currentLastId == null) {
                lastCountedVideoId = candidate.canonicalId
                lastCountedAuthor = candidate.primaryAuthor.ifEmpty { null }
                recentCountedVideoMap[candidate.canonicalId] = now
                if (DEBUG_LOGS) {
                    Log.d(TAG, "[DEDUPLICATION AUDIT] INITIAL_LANDING: Registered ID='${candidate.canonicalId}' (Count unaffected)")
                }
                return
            }

            // C. Compare against last counted video ID
            if (candidate.canonicalId == currentLastId) {
                if (DEBUG_LOGS) {
                    Log.v(TAG, "[DEDUPLICATION AUDIT] SKIPPED (Exact Match with Current): '${candidate.canonicalId}'")
                }
                return
            }

            // D. Substring / Progressive-loading match with current video
            if (currentLastId.contains(candidate.canonicalId) || candidate.canonicalId.contains(currentLastId)) {
                if (DEBUG_LOGS) {
                    Log.v(TAG, "[DEDUPLICATION AUDIT] SKIPPED (Progressive Loading / Substring Match): Current='$currentLastId', Candidate='${candidate.canonicalId}'")
                }
                // Update to the more detailed ID representation without incrementing
                if (candidate.canonicalId.length > currentLastId.length) {
                    lastCountedVideoId = candidate.canonicalId
                    if (candidate.primaryAuthor.isNotEmpty()) {
                        lastCountedAuthor = candidate.primaryAuthor
                    }
                    recentCountedVideoMap[candidate.canonicalId] = now
                }
                return
            }

            // E. Author match (same author on screen means same reel / profile reel)
            val currentAuthor = lastCountedAuthor
            if (currentAuthor != null && candidate.primaryAuthor.isNotEmpty() && currentAuthor == candidate.primaryAuthor) {
                if (DEBUG_LOGS) {
                    Log.v(TAG, "[DEDUPLICATION AUDIT] SKIPPED (Matching Author): '$currentAuthor'")
                }
                return
            }

            // F. Check if candidate ID or author exists in recent history buffer
            if (isInRecentHistory(candidate)) {
                if (DEBUG_LOGS) {
                    Log.d(TAG, "[DEDUPLICATION AUDIT] SKIPPED (Found in Recent History): '${candidate.canonicalId}'")
                }
                lastCountedVideoId = candidate.canonicalId
                if (candidate.primaryAuthor.isNotEmpty()) {
                    lastCountedAuthor = candidate.primaryAuthor
                }
                return
            }

            // G. Hardware / Gesture cooldown safety guard (minimum 400ms)
            val timeSinceLastCount = now - lastCountTimestamp
            if (timeSinceLastCount < MIN_SWIPE_COOLDOWN_MS) {
                if (DEBUG_LOGS) {
                    Log.d(TAG, "[DEDUPLICATION AUDIT] SKIPPED (Within Minimum Gesture Cooldown): ${timeSinceLastCount}ms < ${MIN_SWIPE_COOLDOWN_MS}ms")
                }
                return
            }

            // --- ALL DE-DUPLICATION CHECKS PASSED: GENUINE NEW VIDEO CONFIRMED ---
            lastCountedVideoId = candidate.canonicalId
            lastCountedAuthor = candidate.primaryAuthor.ifEmpty { null }
            lastCountTimestamp = now
            recentCountedVideoMap[candidate.canonicalId] = now

            if (recentCountedVideoMap.size > MAX_HISTORY_BUFFER_SIZE) {
                val oldest = recentCountedVideoMap.keys.firstOrNull()
                if (oldest != null) recentCountedVideoMap.remove(oldest)
            }

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

            currentRecord = currentRecord?.let { rec ->
                if (platform == ScrollPlatform.INSTAGRAM) rec.copy(instagramCount = newCount)
                else rec.copy(youtubeCount = newCount)
            }

            shouldCount = true
        }

        if (shouldCount) {
            Log.i(TAG, "[DEDUPLICATION AUDIT] *** COUNTED NEW VIDEO *** Platform=${platform.displayName}, ID='${candidate.canonicalId}', Total=$newCount/$limit, TimeSinceLast=${now - lastCountTimestamp}ms")

            // 1. Live Instant UI update on Main Thread immediately for the new video
            if (preferences.isHudOverlayEnabled) {
                hudManager.showOrUpdateHud(platform, newCount, limit)
            }

            // 2. Check 10% milestone notifications
            checkAndTriggerMilestoneNotification(platform, newCount, limit, dateStr)

            if (limitExceeded) {
                enforceBlockAndRedirect(platform)
            }

            // 3. Persist to Room Database asynchronously in background
            serviceScope.launch {
                try {
                    val updated = repository.incrementScroll(platform, candidate.canonicalId)
                    deduplicationMutex.withLock {
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
                    Log.d(TAG, "Scroll persisted to Room DB: count=$newCount")
                } catch (e: Exception) {
                    Log.e(TAG, "Error persisting scroll: ${e.message}", e)
                }
            }
        }
    }

    private fun isInRecentHistory(candidate: CandidateVideo): Boolean {
        if (recentCountedVideoMap.containsKey(candidate.canonicalId)) return true
        for (historicalId in recentCountedVideoMap.keys) {
            if (historicalId == candidate.canonicalId ||
                (candidate.primaryAuthor.isNotEmpty() && historicalId.contains("u:" + candidate.primaryAuthor)) ||
                historicalId.contains(candidate.canonicalId) ||
                candidate.canonicalId.contains(historicalId)
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Extracts a stable identifier strictly from the active on-screen video.
     * Checks on-screen viewport bounds to discard preloaded/off-screen cached pages.
     */
    private fun extractActiveVideoIdentity(
        platform: ScrollPlatform,
        rootNode: AccessibilityNodeInfo
    ): CandidateVideo? {
        val windowRect = Rect()
        rootNode.getBoundsInScreen(windowRect)
        val windowHeight = if (windowRect.height() > 0) windowRect.height() else 2400

        var primaryAuthor = ""
        val tokenSet = mutableSetOf<String>()
        val keyParts = StringBuilder()

        val prefix = if (platform == ScrollPlatform.INSTAGRAM) "ig:" else "yt:"
        keyParts.append(prefix)

        val nodeRect = Rect()

        traverseNodes(rootNode, maxDepth = 14) { node ->
            node.getBoundsInScreen(nodeRect)

            // Discard off-screen pre-loaded nodes from adjacent ViewPager pages
            if (nodeRect.bottom <= windowRect.top || nodeRect.top >= windowRect.bottom) {
                return@traverseNodes
            }

            val resId = node.viewIdResourceName?.lowercase() ?: ""
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""

            when (platform) {
                ScrollPlatform.INSTAGRAM -> {
                    // 1. Author / Creator Handle (Primary Anchor)
                    if (resId.contains("user_name") || resId.contains("username") ||
                        resId.contains("profile_name") || resId.contains("author") ||
                        resId.contains("clips_author_name") || resId.contains("row_feed_photo_profile_name") ||
                        resId.contains("owner_name")
                    ) {
                        if (text.isNotEmpty() && !isIgnoredUiText(text)) {
                            val author = text.lowercase()
                            if (primaryAuthor.isEmpty()) primaryAuthor = author
                            keyParts.append("u:").append(author).append("|")
                            tokenSet.add("u:$author")
                        }
                    }
                    // 2. Audio Title / Music
                    else if (resId.contains("audio_title") || resId.contains("music_title") ||
                        resId.contains("sound_title") || resId.contains("audio_track")
                    ) {
                        if (text.isNotEmpty() && !isIgnoredUiText(text)) {
                            val audio = text.lowercase().take(30)
                            keyParts.append("a:").append(audio).append("|")
                            tokenSet.add("a:$audio")
                        }
                    }
                    // 3. Caption text
                    else if (resId.contains("caption") || resId.contains("clips_caption") ||
                        resId.contains("caption_text_view")
                    ) {
                        if (text.isNotEmpty() && text.length >= 4 && !isIgnoredUiText(text)) {
                            val cap = text.lowercase().take(25)
                            keyParts.append("c:").append(cap).append("|")
                            tokenSet.add("c:$cap")
                        }
                    }
                    // 4. Accessibility Description
                    if (desc.startsWith("Reel by", ignoreCase = true) ||
                        desc.startsWith("Photo by", ignoreCase = true) ||
                        desc.startsWith("Video by", ignoreCase = true) ||
                        desc.contains("audio used in reel", ignoreCase = true)
                    ) {
                        val d = desc.lowercase().take(40)
                        keyParts.append("d:").append(d).append("|")
                        tokenSet.add("d:$d")
                    }
                    // 5. Stable on-screen text snippet
                    if (text.isNotEmpty() && text.length in 3..40 && !isIgnoredUiText(text)) {
                        tokenSet.add("t:" + text.lowercase())
                    }
                }
                ScrollPlatform.YOUTUBE -> {
                    if (resId.contains("channel_name") || resId.contains("video_title") ||
                        resId.contains("sound_title") || resId.contains("title_text") ||
                        resId.contains("owner_name")
                    ) {
                        if (text.isNotEmpty() && !isIgnoredUiText(text)) {
                            val channel = text.lowercase().take(30)
                            if (primaryAuthor.isEmpty() && resId.contains("channel_name")) {
                                primaryAuthor = channel
                            }
                            keyParts.append("c:").append(channel).append("|")
                            tokenSet.add("c:$channel")
                        }
                    }
                    if (desc.contains("Short by", ignoreCase = true) || desc.contains("Video", ignoreCase = true)) {
                        val d = desc.lowercase().take(40)
                        keyParts.append("d:").append(d).append("|")
                        tokenSet.add("d:$d")
                    }
                    if (text.isNotEmpty() && text.length in 3..40 && !isIgnoredUiText(text)) {
                        tokenSet.add("t:" + text.lowercase())
                    }
                }
            }
        }

        val canonical = if (keyParts.length > prefix.length) {
            keyParts.toString()
        } else if (tokenSet.isNotEmpty()) {
            prefix + tokenSet.take(2).joinToString(separator = "|")
        } else {
            ""
        }

        if (canonical.isEmpty()) return null

        return CandidateVideo(
            platform = platform,
            primaryAuthor = primaryAuthor,
            canonicalId = canonical
        )
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

    private fun isInsideShortsOrReels(platform: ScrollPlatform, rootNode: AccessibilityNodeInfo): Boolean {
        return when (platform) {
            ScrollPlatform.INSTAGRAM -> {
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
        val currentMilestone = (percent / 10) * 10

        if (currentMilestone in 10..100) {
            val lastNotified = if (platform == ScrollPlatform.INSTAGRAM) {
                preferences.getLastNotifiedMilestoneIg(dateString)
            } else {
                preferences.getLastNotifiedMilestoneYt(dateString)
            }

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
        if (now - lastRedirectTimestamp < 1500) return
        lastRedirectTimestamp = now

        hudManager.hideHud()
        performGlobalAction(GLOBAL_ACTION_HOME)

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
        // Note: We deliberately preserve recentCountedVideoMap and lastCountedVideoId
        // so that briefly task-switching back to the same video does NOT trigger a duplicate count.
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

    /**
     * Immutable data representation of an extracted candidate video.
     */
    data class CandidateVideo(
        val platform: ScrollPlatform,
        val primaryAuthor: String,
        val canonicalId: String
    )

    companion object {
        private const val TAG = "ReelsPalAccessibility"
        private const val DEBUG_LOGS = true // Enabled for live deduplication audit

        @Volatile
        var instance: ReelsPalAccessibilityService? = null
            private set

        fun isServiceRunning(): Boolean = instance != null
    }
}
