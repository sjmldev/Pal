package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
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
import com.example.service.extractor.CandidateVideo
import com.example.service.extractor.InstagramIdentityExtractor
import com.example.service.extractor.VideoIdentityExtractor
import com.example.service.extractor.YouTubeIdentityExtractor
import com.example.ui.blocked.BlockedActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * AccessibilityService that monitors Instagram Reels and YouTube Shorts usage.
 *
 * Employs a strategy-pattern architecture for platform extraction:
 * 1. An event handler `when` block branching on the event package name.
 * 2. Separate strategy classes ([InstagramIdentityExtractor] and [YouTubeIdentityExtractor])
 *    implementing [VideoIdentityExtractor] to derive stable video identities.
 * 3. Safe viewport bounds filtering and multi-token canonical identity formulation.
 * 4. Serialized atomic de-duplication pipeline via Kotlin Coroutine Mutex.
 * 5. Instant zero-latency HUD update and asynchronous Room DB persistence.
 */
class ReelsPalAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repository: ScrollRepository
    private lateinit var preferences: AppPreferences
    private lateinit var hudManager: HudOverlayManager

    // Strategy extractors for supported short-video platforms
    private val instagramExtractor: VideoIdentityExtractor = InstagramIdentityExtractor()
    private val youtubeExtractor: VideoIdentityExtractor = YouTubeIdentityExtractor()

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
    private var lastCountedPlatform: ScrollPlatform? = null
    private var lastCountedVideoId: String? = null
    private var lastCountedAuthor: String? = null
    private var lastCountedTokens: Set<String> = emptySet()
    private var lastCountTimestamp: Long = 0L
    private val recentCountedVideoMap = LinkedHashMap<String, Long>()

    // Safety guards
    private val MIN_SWIPE_COOLDOWN_MS = 400L             // Physical gesture minimum time
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

    /**
     * Refactored Accessibility event handler branching on package name via a clean `when` block.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return

        when (packageName) {
            ScrollPlatform.INSTAGRAM.packageName -> {
                handleTargetPlatformEvent(
                    platform = ScrollPlatform.INSTAGRAM,
                    extractor = instagramExtractor,
                    event = event
                )
            }
            ScrollPlatform.YOUTUBE.packageName -> {
                handleTargetPlatformEvent(
                    platform = ScrollPlatform.YOUTUBE,
                    extractor = youtubeExtractor,
                    event = event
                )
            }
            else -> {
                handleExitedTargetApp()
            }
        }
    }

    /**
     * Common event processing pipeline parameterized by target platform and strategy extractor.
     */
    private fun handleTargetPlatformEvent(
        platform: ScrollPlatform,
        extractor: VideoIdentityExtractor,
        event: AccessibilityEvent
    ) {
        activePlatform = platform

        try {
            val rootNode = rootInActiveWindow ?: return

            val (isBlocked, count, limit) = synchronized(this) {
                val currentCount = if (platform == ScrollPlatform.INSTAGRAM) inMemoryIgCount else inMemoryYtCount
                val currentLimit = if (platform == ScrollPlatform.INSTAGRAM) inMemoryIgLimit else inMemoryYtLimit
                val blocked = currentCount >= currentLimit
                Triple(blocked, currentCount, currentLimit)
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
                if (DEBUG_LOGS) Log.v(TAG, "[$platform] Comments section is open — event ignored.")
                return
            }

            // 5. Extract platform-specific stable on-screen video identity using the strategy extractor
            val candidate = extractor.extractIdentity(this, rootNode) ?: return

            // 6. Execute atomic, serialized de-duplication evaluation
            serviceScope.launch {
                processVideoTransitionSerialized(platform, candidate)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling accessibility event for $platform: ${e.message}", e)
        }
    }

    /**
     * SERIALIZED SINGLE ENTRY POINT: Evaluates candidate against existing state.
     * Guarantees that exactly 1 count is added per unique video watched.
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
        val auditTag = if (platform == ScrollPlatform.INSTAGRAM) "[INSTAGRAM REEL AUDIT]" else "[YOUTUBE SHORT AUDIT]"

        deduplicationMutex.withLock {
            // A. Reset platform context if switched apps
            if (lastCountedPlatform != platform) {
                lastCountedPlatform = platform
                lastCountedVideoId = null
                lastCountedAuthor = null
                lastCountedTokens = emptySet()
            }

            // B. Clean expired entries from recent history (>60s)
            val iterator = recentCountedVideoMap.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value > RECENT_HISTORY_EXPIRY_MS) {
                    iterator.remove()
                }
            }

            // C. Initial landing video check (when entering viewer for the first time)
            val currentLastId = lastCountedVideoId
            if (currentLastId == null) {
                lastCountedVideoId = candidate.canonicalId
                lastCountedAuthor = candidate.primaryAuthor.ifEmpty { null }
                lastCountedTokens = candidate.tokens
                recentCountedVideoMap[candidate.canonicalId] = now
                if (DEBUG_LOGS) {
                    Log.d(TAG, "$auditTag INITIAL_LANDING: Registered ID='${candidate.canonicalId}', Author='${candidate.primaryAuthor}' (Count unchanged)")
                }
                return
            }

            // D. Compare against last counted video ID (Exact match)
            if (candidate.canonicalId == currentLastId) {
                if (DEBUG_LOGS) {
                    Log.v(TAG, "$auditTag SKIPPED (Exact ID Match): '${candidate.canonicalId}'")
                }
                return
            }

            // E. Substring / Progressive-loading match with current video
            if (currentLastId.contains(candidate.canonicalId) || candidate.canonicalId.contains(currentLastId)) {
                if (DEBUG_LOGS) {
                    Log.v(TAG, "$auditTag SKIPPED (Substring/Progressive Loading Match): Current='$currentLastId', Candidate='${candidate.canonicalId}'")
                }
                // Enrich active ID if candidate has more complete info without incrementing count
                if (candidate.canonicalId.length > currentLastId.length) {
                    lastCountedVideoId = candidate.canonicalId
                    if (candidate.primaryAuthor.isNotEmpty()) {
                        lastCountedAuthor = candidate.primaryAuthor
                    }
                    lastCountedTokens = candidate.tokens
                    recentCountedVideoMap[candidate.canonicalId] = now
                }
                return
            }

            // F. Author match: If the primary author is identical, it is the same video (or author profile)
            val currentAuthor = lastCountedAuthor
            if (currentAuthor != null && candidate.primaryAuthor.isNotEmpty() && currentAuthor.equals(candidate.primaryAuthor, ignoreCase = true)) {
                if (DEBUG_LOGS) {
                    Log.v(TAG, "$auditTag SKIPPED (Same Author on Screen): '$currentAuthor'")
                }
                // Update tokens to active set
                lastCountedTokens = lastCountedTokens + candidate.tokens
                return
            }

            // G. Token Set Overlap check: If 2+ secondary tokens match (e.g. caption, audio, desc), it's the same video
            if (lastCountedTokens.isNotEmpty() && candidate.tokens.isNotEmpty()) {
                val intersection = lastCountedTokens.intersect(candidate.tokens)
                if (intersection.size >= 2 || (intersection.isNotEmpty() && (lastCountedTokens.size <= 2 || candidate.tokens.size <= 2))) {
                    if (DEBUG_LOGS) {
                        Log.v(TAG, "$auditTag SKIPPED (Token Overlap Match: ${intersection.joinToString()}): Candidate='${candidate.canonicalId}'")
                    }
                    return
                }
            }

            // H. Check if candidate ID or author exists in recent history buffer (prevents back-and-forth settlement)
            if (isInRecentHistory(candidate)) {
                if (DEBUG_LOGS) {
                    Log.d(TAG, "$auditTag SKIPPED (Found in Recent 60s History): '${candidate.canonicalId}'")
                }
                lastCountedVideoId = candidate.canonicalId
                if (candidate.primaryAuthor.isNotEmpty()) {
                    lastCountedAuthor = candidate.primaryAuthor
                }
                lastCountedTokens = candidate.tokens
                return
            }

            // I. Hardware / Gesture cooldown safety guard (minimum 400ms)
            val timeSinceLastCount = now - lastCountTimestamp
            if (timeSinceLastCount < MIN_SWIPE_COOLDOWN_MS) {
                if (DEBUG_LOGS) {
                    Log.d(TAG, "$auditTag SKIPPED (Within Cooldown): ${timeSinceLastCount}ms < ${MIN_SWIPE_COOLDOWN_MS}ms")
                }
                return
            }

            // --- ALL DE-DUPLICATION CHECKS PASSED: GENUINE NEW VIDEO CONFIRMED (EXACTLY 1 COUNT) ---
            lastCountedVideoId = candidate.canonicalId
            lastCountedAuthor = candidate.primaryAuthor.ifEmpty { null }
            lastCountedTokens = candidate.tokens
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
            Log.i(TAG, "$auditTag *** COUNTED NEW VIDEO *** ID='${candidate.canonicalId}', Author='${candidate.primaryAuthor}', NewTotal=$newCount/$limit, Interval=${now - lastCountTimestamp}ms")

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
                (candidate.primaryAuthor.isNotEmpty() && historicalId.contains("c:" + candidate.primaryAuthor)) ||
                historicalId.contains(candidate.canonicalId) ||
                candidate.canonicalId.contains(historicalId)
            ) {
                return true
            }
        }
        return false
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
                            resId.contains("clips_root") ||
                            resId.contains("clips_item_root") ||
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
     * Triggers a notification at every 10% progress step (10%, 20%, 30%... 100%).
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
        private const val DEBUG_LOGS = true

        @Volatile
        var instance: ReelsPalAccessibilityService? = null
            private set

        fun isServiceRunning(): Boolean = instance != null
    }
}
