package com.example.service.extractor

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.model.ScrollPlatform

/**
 * Strategy implementation for extracting stable video identities from YouTube Shorts.
 *
 * Specific requirements:
 * 1. Searches specifically for the 'short_video_player' view hierarchy (and related Shorts player nodes).
 * 2. Combines the video's content description and current scroll position to form a stable unique ID.
 * 3. Deeply inspects nested node structures (depth up to 35) to guarantee a non-null candidate string
 *    even when elements are nested deep within the Shorts player view.
 */
class YouTubeIdentityExtractor : VideoIdentityExtractor {

    override val platform: ScrollPlatform = ScrollPlatform.YOUTUBE
    override val targetPackageName: String = ScrollPlatform.YOUTUBE.packageName

    override fun extractIdentity(context: Context, rootNode: AccessibilityNodeInfo): CandidateVideo? {
        val displayMetrics = context.resources.displayMetrics
        val screenHeight = if (displayMetrics.heightPixels > 0) displayMetrics.heightPixels else 2400
        val screenWidth = if (displayMetrics.widthPixels > 0) displayMetrics.widthPixels else 1080

        var primaryAuthor = ""
        var primaryTitle = ""
        var primaryAudio = ""
        var primaryContentDescription = ""
        var playerScrollPosition = -1
        var playerCenterY = -1
        var foundShortVideoPlayerNode = false

        val tokens = mutableSetOf<String>()
        val keyParts = StringBuilder("yt:")

        val nodeRect = Rect()

        // 1. Deep traversal across the entire Shorts hierarchy
        traverseNodes(rootNode, maxDepth = 35) { node ->
            node.getBoundsInScreen(nodeRect)

            val resId = node.viewIdResourceName?.lowercase() ?: ""
            val rawText = node.text?.toString()?.trim() ?: ""
            val rawDesc = node.contentDescription?.toString()?.trim() ?: ""

            // Check if this node belongs to or is the 'short_video_player' container
            val isPlayerNode = resId.contains("short_video_player") ||
                    resId.contains("shorts_player") ||
                    resId.contains("reel_player") ||
                    resId.contains("reel_watch_fragment") ||
                    resId.contains("player_view")

            if (isPlayerNode) {
                foundShortVideoPlayerNode = true
                playerCenterY = nodeRect.centerY()

                // Extract scroll item index if provided by accessibility collection info
                node.collectionItemInfo?.let { itemInfo ->
                    playerScrollPosition = itemInfo.rowIndex
                }

                // If content description is attached directly to the player node
                if (rawDesc.isNotEmpty() && !isIgnoredUiText(rawDesc)) {
                    primaryContentDescription = rawDesc
                }
            }

            // Viewport boundary check for children/subviews
            if (nodeRect.bottom <= 0 || nodeRect.top >= screenHeight ||
                nodeRect.right <= 0 || nodeRect.left >= screenWidth ||
                nodeRect.width() <= 0 || nodeRect.height() <= 0
            ) {
                return@traverseNodes
            }

            // Extract collection row index from any visible item in viewport if not set yet
            if (playerScrollPosition == -1) {
                node.collectionItemInfo?.let { itemInfo ->
                    if (itemInfo.rowIndex >= 0) {
                        playerScrollPosition = itemInfo.rowIndex
                    }
                }
            }

            // Direct YouTube Channel Handle extraction (e.g. "@creator")
            if (rawText.startsWith("@") && rawText.length in 2..45 && !isIgnoredUiText(rawText)) {
                val handle = rawText.removePrefix("@").lowercase().trim()
                if (handle.isNotEmpty() && !isIgnoredUiText(handle)) {
                    if (primaryAuthor.isEmpty()) primaryAuthor = handle
                    val tag = "c:$handle"
                    if (!tokens.contains(tag)) {
                        tokens.add(tag)
                        keyParts.append(tag).append("|")
                    }
                }
            }

            // Channel / Creator name view IDs
            if (resId.contains("channel_name") || resId.contains("owner_name") ||
                resId.contains("owner_text") || resId.contains("reel_channel_name") ||
                resId.contains("channel_title") || resId.contains("uploader_name") ||
                resId.contains("reel_channel_title") || resId.contains("reel_author") ||
                resId.contains("author_text")
            ) {
                if (rawText.isNotEmpty() && !isIgnoredUiText(rawText)) {
                    val channel = rawText.lowercase().removePrefix("@").trim()
                    if (channel.isNotEmpty() && !isIgnoredUiText(channel)) {
                        if (primaryAuthor.isEmpty()) primaryAuthor = channel
                        val tag = "c:$channel"
                        if (!tokens.contains(tag)) {
                            tokens.add(tag)
                            keyParts.append(tag).append("|")
                        }
                    }
                }
            }

            // Video Title / Caption view IDs
            if (resId.contains("video_title") || resId.contains("title_text") ||
                resId.contains("reel_video_title") || resId.contains("video_description") ||
                resId.contains("reel_player_title") || resId.contains("reel_description") ||
                resId.contains("description_text")
            ) {
                if (rawText.isNotEmpty() && rawText.length >= 3 && !isIgnoredUiText(rawText)) {
                    val title = rawText.lowercase().take(40)
                    if (primaryTitle.isEmpty()) primaryTitle = title
                    val tag = "t:$title"
                    if (!tokens.contains(tag)) {
                        tokens.add(tag)
                        keyParts.append(tag).append("|")
                    }
                }
            }

            // Audio / Sound view IDs
            if (resId.contains("sound_title") || resId.contains("audio_track") ||
                resId.contains("sound_button") || resId.contains("music_title") ||
                resId.contains("reel_sound_button") || resId.contains("reel_audio_title") ||
                resId.contains("pivot_button")
            ) {
                if (rawText.isNotEmpty() && !isIgnoredUiText(rawText)) {
                    val audio = rawText.lowercase().take(25)
                    if (primaryAudio.isEmpty()) primaryAudio = audio
                    val tag = "a:$audio"
                    if (!tokens.contains(tag)) {
                        tokens.add(tag)
                        keyParts.append(tag).append("|")
                    }
                }
            }

            // Content description parsing
            if (rawDesc.isNotEmpty()) {
                val lowerDesc = rawDesc.lowercase()

                if (primaryContentDescription.isEmpty() && !isIgnoredUiText(lowerDesc)) {
                    primaryContentDescription = lowerDesc
                }

                when {
                    lowerDesc.startsWith("short by ") -> {
                        primaryContentDescription = lowerDesc
                        val parsedChannel = lowerDesc
                            .removePrefix("short by ")
                            .substringBefore("-")
                            .substringBefore("•")
                            .substringBefore(",")
                            .trim()
                            .removePrefix("@")
                        if (parsedChannel.isNotEmpty() && parsedChannel.length in 2..35 && !isIgnoredUiText(parsedChannel)) {
                            if (primaryAuthor.isEmpty()) primaryAuthor = parsedChannel
                            val tag = "c:$parsedChannel"
                            if (!tokens.contains(tag)) {
                                tokens.add(tag)
                                keyParts.append(tag).append("|")
                            }
                        }
                    }
                    lowerDesc.startsWith("subscribe to ") -> {
                        val parsedChannel = lowerDesc
                            .removePrefix("subscribe to ")
                            .substringBefore(".")
                            .substringBefore(",")
                            .trim()
                            .removePrefix("@")
                        if (parsedChannel.isNotEmpty() && parsedChannel.length in 2..35 && !isIgnoredUiText(parsedChannel)) {
                            if (primaryAuthor.isEmpty()) primaryAuthor = parsedChannel
                            val tag = "c:$parsedChannel"
                            if (!tokens.contains(tag)) {
                                tokens.add(tag)
                                keyParts.append(tag).append("|")
                            }
                        }
                    }
                    lowerDesc.startsWith("subscribed to ") -> {
                        val parsedChannel = lowerDesc
                            .removePrefix("subscribed to ")
                            .substringBefore(".")
                            .substringBefore(",")
                            .trim()
                            .removePrefix("@")
                        if (parsedChannel.isNotEmpty() && parsedChannel.length in 2..35 && !isIgnoredUiText(parsedChannel)) {
                            if (primaryAuthor.isEmpty()) primaryAuthor = parsedChannel
                            val tag = "c:$parsedChannel"
                            tokens.add(tag)
                        }
                    }
                    lowerDesc.contains(" channel icon") || lowerDesc.contains("'s channel") || lowerDesc.startsWith("go to ") -> {
                        val parsedChannel = lowerDesc
                            .substringBefore(" channel icon")
                            .substringBefore("'s channel")
                            .removePrefix("go to ")
                            .trim()
                            .removePrefix("@")
                        if (parsedChannel.isNotEmpty() && parsedChannel.length in 2..35 && !isIgnoredUiText(parsedChannel)) {
                            if (primaryAuthor.isEmpty()) primaryAuthor = parsedChannel
                            val tag = "c:$parsedChannel"
                            tokens.add(tag)
                        }
                    }
                    lowerDesc.startsWith("play video:") -> {
                        val parsedTitle = lowerDesc.removePrefix("play video:").trim().take(40)
                        if (parsedTitle.isNotEmpty() && !isIgnoredUiText(parsedTitle)) {
                            if (primaryTitle.isEmpty()) primaryTitle = parsedTitle
                            val tag = "t:$parsedTitle"
                            tokens.add(tag)
                        }
                    }
                    lowerDesc.startsWith("sound:") || lowerDesc.startsWith("audio:") -> {
                        val parsedAudio = lowerDesc.removePrefix("sound:").removePrefix("audio:").trim().take(25)
                        if (parsedAudio.isNotEmpty() && !isIgnoredUiText(parsedAudio)) {
                            if (primaryAudio.isEmpty()) primaryAudio = parsedAudio
                            val tag = "a:$parsedAudio"
                            tokens.add(tag)
                        }
                    }
                }
            }

            // Bottom overlay text fallback for title
            if (primaryTitle.isEmpty() && rawText.isNotEmpty() && rawText.length in 4..80 &&
                !isIgnoredUiText(rawText) && !rawText.startsWith("@") &&
                nodeRect.top > screenHeight * 0.35
            ) {
                val candidateText = rawText.lowercase().take(40)
                if (candidateText.isNotEmpty() && !isIgnoredUiText(candidateText)) {
                    primaryTitle = candidateText
                    val tag = "t:$candidateText"
                    tokens.add(tag)
                    keyParts.append(tag).append("|")
                }
            }
        }

        // 2. Derive stable scroll position component
        val scrollPosKey = if (playerScrollPosition >= 0) {
            "p:$playerScrollPosition"
        } else if (playerCenterY > 0) {
            // Normalized viewport snap position
            "py:${playerCenterY / (screenHeight / 4)}"
        } else {
            "p:0"
        }
        tokens.add(scrollPosKey)

        // 3. Clean and sanitize content description component
        val cleanDesc = primaryContentDescription
            .lowercase()
            .replace(Regex("[^a-z0-9@_\\- ]"), "")
            .trim()
            .take(40)

        // 4. Formulate stable canonical ID combining content description, scroll position, and author/title
        val canonicalId = when {
            // Priority 1: Direct Content Description + Scroll Position
            cleanDesc.isNotEmpty() && (primaryAuthor.isNotEmpty() || playerScrollPosition >= 0) -> {
                "yt:desc:$cleanDesc|$scrollPosKey|"
            }
            // Priority 2: Author + Title + Scroll Position
            primaryAuthor.isNotEmpty() && primaryTitle.isNotEmpty() -> {
                "yt:c:$primaryAuthor|t:$primaryTitle|$scrollPosKey|"
            }
            // Priority 3: Author + Audio/Tokens + Scroll Position
            primaryAuthor.isNotEmpty() -> {
                if (primaryAudio.isNotEmpty()) {
                    "yt:c:$primaryAuthor|a:$primaryAudio|$scrollPosKey|"
                } else {
                    "yt:c:$primaryAuthor|$scrollPosKey|"
                }
            }
            // Priority 4: Content Description alone
            cleanDesc.isNotEmpty() -> {
                "yt:desc:$cleanDesc|$scrollPosKey|"
            }
            // Priority 5: Title + Scroll Position
            primaryTitle.isNotEmpty() -> {
                "yt:t:$primaryTitle|$scrollPosKey|"
            }
            // Priority 6: Accumulated key parts
            keyParts.length > 3 -> {
                "$keyParts$scrollPosKey|"
            }
            // Priority 7: Tokens or Short Video Player presence guarantee
            tokens.isNotEmpty() -> {
                "yt:" + tokens.take(3).joinToString(separator = "|") + "|$scrollPosKey|"
            }
            foundShortVideoPlayerNode -> {
                "yt:short_video_player|$scrollPosKey|"
            }
            else -> {
                // Fallback guarantee when inside Shorts player view hierarchy
                "yt:short_video_player:active|"
            }
        }

        Log.d(
            TAG,
            "[YOUTUBE SHORT EXTRACTOR] Extracted candidate: ID='$canonicalId', Author='$primaryAuthor', Title='$primaryTitle', Desc='$cleanDesc', ScrollPos='$scrollPosKey', FoundPlayer=$foundShortVideoPlayerNode"
        )

        return CandidateVideo(
            platform = ScrollPlatform.YOUTUBE,
            primaryAuthor = primaryAuthor,
            tokens = tokens,
            canonicalId = canonicalId
        )
    }

    private fun isIgnoredUiText(rawText: String): Boolean {
        val text = rawText.trim().lowercase()
        if (text.isEmpty()) return true

        // Numeric counters & stats (views, likes, subscriber counts)
        if (text.matches(Regex("^[0-9.,kKmMbB: #+]+$"))) return true
        if (text.matches(Regex("^[0-9]+.*(likes?|views?|comments?|shares?|subscribers?|k|m|b)$"))) return true

        val ignoredTokens = setOf(
            "reels", "shorts", "follow", "following", "like", "liked", "dislike",
            "comment", "comments", "share", "remix", "subscribe", "subscribed",
            "save", "saved", "more", "audio", "original audio", "sponsored",
            "suggested for you", "use audio", "watch again", "reply", "see translation",
            "play", "pause", "mute", "unmute", "search", "home", "subscriptions",
            "library", "notifications", "join", "shop", "products", "paid promotion",
            "tap to unmute", "sound", "use sound", "create", "you", "explore",
            "trending", "history", "all", "back", "close", "menu"
        )
        return ignoredTokens.contains(text)
    }

    private fun traverseNodes(
        node: AccessibilityNodeInfo?,
        depth: Int = 0,
        maxDepth: Int = 35,
        visitor: (AccessibilityNodeInfo) -> Unit
    ) {
        if (node == null || depth > maxDepth) return
        try {
            visitor(node)
        } catch (t: Throwable) {
            // Ignore node inspection exceptions during rapid scrolling
        }
        val count = try { node.childCount } catch (t: Throwable) { 0 }
        for (i in 0 until count) {
            val child = try { node.getChild(i) } catch (t: Throwable) { null } ?: continue
            traverseNodes(child, depth + 1, maxDepth, visitor)
        }
    }

    companion object {
        private const val TAG = "YouTubeExtractor"
    }
}
