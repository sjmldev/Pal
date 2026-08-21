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

    // Tracking state to detect complete video transitions
    private var lastInstagramVideoKey: String = ""
    private var lastYoutubeVideoKey: String = ""
    private var lastCountTimestamp: Long = 0L

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
            repository.getTodayRecordFlow().collectLatest { record ->
                currentRecord = record
                // Update HUD if visible
                val platform = activePlatform
                if (isInReelsOrShortsSection && platform != null && preferences.isHudOverlayEnabled) {
                    val count = if (platform == ScrollPlatform.INSTAGRAM) record.instagramCount else record.youtubeCount
                    val limit = if (platform == ScrollPlatform.INSTAGRAM) record.totalInstagramAllowed else record.totalYoutubeAllowed
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
        val record = currentRecord ?: return

        // 1. Check if the app is currently BLOCKED
        val isBlocked = when (platform) {
            ScrollPlatform.INSTAGRAM -> record.isInstagramBlocked
            ScrollPlatform.YOUTUBE -> record.isYoutubeBlocked
        }

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
            }
            return
        }

        isInReelsOrShortsSection = true

        // 3. Update HUD immediately if not yet showing
        if (preferences.isHudOverlayEnabled) {
            val count = if (platform == ScrollPlatform.INSTAGRAM) record.instagramCount else record.youtubeCount
            val limit = if (platform == ScrollPlatform.INSTAGRAM) record.totalInstagramAllowed else record.totalYoutubeAllowed
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
        val now = System.currentTimeMillis()
        // Debounce 600ms between transitions to prevent double-count while allowing snappy scrolls
        if (now - lastCountTimestamp < 600) return

        val activeKey = extractActiveVideoIdentifier(platform, rootNode)
        if (activeKey.isBlank()) return

        when (platform) {
            ScrollPlatform.INSTAGRAM -> {
                if (lastInstagramVideoKey.isNotEmpty() && lastInstagramVideoKey != activeKey) {
                    // Full settled transition completed!
                    lastInstagramVideoKey = activeKey
                    lastCountTimestamp = now
                    onScrollDetected(platform, activeKey)
                } else if (lastInstagramVideoKey.isEmpty()) {
                    // Initial video recorded
                    lastInstagramVideoKey = activeKey
                }
            }
            ScrollPlatform.YOUTUBE -> {
                if (lastYoutubeVideoKey.isNotEmpty() && lastYoutubeVideoKey != activeKey) {
                    // Full settled transition completed!
                    lastYoutubeVideoKey = activeKey
                    lastCountTimestamp = now
                    onScrollDetected(platform, activeKey)
                } else if (lastYoutubeVideoKey.isEmpty()) {
                    // Initial video recorded
                    lastYoutubeVideoKey = activeKey
                }
            }
        }
    }

    private fun extractActiveVideoIdentifier(
        platform: ScrollPlatform,
        rootNode: AccessibilityNodeInfo
    ): String {
        val identifiers = StringBuilder()
        val textSnippets = mutableListOf<String>()

        traverseNodes(rootNode, maxDepth = 14) { node ->
            val resId = node.viewIdResourceName?.lowercase() ?: ""
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""

            when (platform) {
                ScrollPlatform.INSTAGRAM -> {
                    // 1. Author and handle matching
                    if (resId.contains("user_name") || resId.contains("username") ||
                        resId.contains("profile_name") || resId.contains("author") ||
                        resId.contains("clips_author_name") || resId.contains("row_feed_photo_profile_name") ||
                        resId.contains("owner_name")
                    ) {
                        if (text.isNotEmpty()) identifiers.append("user:").append(text).append("|")
                    }
                    // 2. Audio title matching
                    else if (resId.contains("audio_title") || resId.contains("music_title") ||
                        resId.contains("sound_title") || resId.contains("audio_track")
                    ) {
                        if (text.isNotEmpty()) identifiers.append("audio:").append(text).append("|")
                    }
                    // 3. Caption matching
                    else if (resId.contains("caption") || resId.contains("clips_caption") ||
                        resId.contains("caption_text_view")
                    ) {
                        if (text.isNotEmpty()) identifiers.append("cap:").append(text.take(30)).append("|")
                    }
                    // 4. Accessibility descriptions
                    if (desc.startsWith("Reel by", ignoreCase = true) ||
                        desc.startsWith("Photo by", ignoreCase = true) ||
                        desc.startsWith("Video by", ignoreCase = true) ||
                        desc.contains("audio used in reel", ignoreCase = true)
                    ) {
                        identifiers.append("desc:").append(desc).append("|")
                    }

                    // 5. General text fallback for unique reel labels
                    if (text.isNotEmpty() && text.length in 2..50 &&
                        !text.equals("Reels", ignoreCase = true) &&
                        !text.equals("Follow", ignoreCase = true) &&
                        !text.equals("Following", ignoreCase = true) &&
                        !text.equals("Like", ignoreCase = true) &&
                        !text.equals("Comment", ignoreCase = true) &&
                        !text.equals("Share", ignoreCase = true)
                    ) {
                        textSnippets.add(text)
                    }
                }
                ScrollPlatform.YOUTUBE -> {
                    if (resId.contains("channel_name") || resId.contains("video_title") ||
                        resId.contains("sound_title") || resId.contains("title_text") ||
                        resId.contains("owner_name")
                    ) {
                        if (text.isNotEmpty()) identifiers.append("yt:").append(text).append("|")
                    }
                    if (desc.contains("Short by", ignoreCase = true) || desc.contains("Video", ignoreCase = true)) {
                        identifiers.append("desc:").append(desc).append("|")
                    }
                    if (text.isNotEmpty() && text.length in 2..50 &&
                        !text.equals("Shorts", ignoreCase = true) &&
                        !text.equals("Subscribe", ignoreCase = true) &&
                        !text.equals("Subscribed", ignoreCase = true)
                    ) {
                        textSnippets.add(text)
                    }
                }
            }
        }

        if (identifiers.isNotEmpty()) {
            return identifiers.toString()
        }

        // Fallback to top distinctive text snippets on the screen
        return if (textSnippets.isNotEmpty()) {
            textSnippets.take(3).joinToString(separator = "|")
        } else {
            ""
        }
    }

    private fun onScrollDetected(platform: ScrollPlatform, identifier: String) {
        val record = currentRecord ?: return

        // 1. Live Instant UI update on Main Thread
        val currentCount = if (platform == ScrollPlatform.INSTAGRAM) record.instagramCount else record.youtubeCount
        val limit = if (platform == ScrollPlatform.INSTAGRAM) record.totalInstagramAllowed else record.totalYoutubeAllowed
        val optimisticCount = currentCount + 1

        if (preferences.isHudOverlayEnabled) {
            hudManager.showOrUpdateHud(platform, optimisticCount, limit)
        }

        // 2. Persist to Database asynchronously and enforce limits
        serviceScope.launch {
            try {
                val updated = repository.incrementScroll(platform, identifier)
                currentRecord = updated
                Log.d(TAG, "Scroll counted for ${platform.displayName}! Realtime count: " +
                        if (platform == ScrollPlatform.INSTAGRAM) updated.instagramCount else updated.youtubeCount)

                // Check if limit reached or exceeded
                val limitExceeded = when (platform) {
                    ScrollPlatform.INSTAGRAM -> updated.isInstagramBlocked
                    ScrollPlatform.YOUTUBE -> updated.isYoutubeBlocked
                }

                if (limitExceeded) {
                    enforceBlockAndRedirect(platform)
                } else if (preferences.isHudOverlayEnabled) {
                    val count = if (platform == ScrollPlatform.INSTAGRAM) updated.instagramCount else updated.youtubeCount
                    val finalLimit = if (platform == ScrollPlatform.INSTAGRAM) updated.totalInstagramAllowed else updated.totalYoutubeAllowed
                    hudManager.showOrUpdateHud(platform, count, finalLimit)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error recording scroll: ${e.message}", e)
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
