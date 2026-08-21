package com.example.service.extractor

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.model.ScrollPlatform

/**
 * Strategy implementation for extracting stable video identities from YouTube Shorts.
 *
 * Utilizes YouTube's unique view hierarchy and event lifecycle patterns:
 * - Content description parsing ("Short by <Channel>", "<Channel> channel icon") to capture creator
 *   identities during early Phase 1 event dispatch before child views are bound.
 * - Explicit YouTube view resource IDs (channel_name, video_title, reel_video_title, sound_button).
 * - Multi-token normalization guaranteeing equivalence between early description and late child-view binding.
 * - Viewport bounds validation using display metrics.
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
        val tokens = mutableSetOf<String>()
        val keyParts = StringBuilder("yt:")

        val nodeRect = Rect()

        traverseNodes(rootNode, maxDepth = 15) { node ->
            node.getBoundsInScreen(nodeRect)

            // Viewport boundary check
            if (nodeRect.bottom <= 0 || nodeRect.top >= screenHeight ||
                nodeRect.right <= 0 || nodeRect.left >= screenWidth ||
                nodeRect.width() <= 0 || nodeRect.height() <= 0
            ) {
                return@traverseNodes
            }

            val resId = node.viewIdResourceName?.lowercase() ?: ""
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""

            // 1. Channel / Creator name view IDs
            if (resId.contains("channel_name") || resId.contains("owner_name") ||
                resId.contains("owner_text") || resId.contains("reel_channel_name") ||
                resId.contains("channel_title") || resId.contains("uploader_name")
            ) {
                if (text.isNotEmpty() && !isIgnoredUiText(text)) {
                    val channel = text.lowercase().removePrefix("@")
                    if (primaryAuthor.isEmpty()) primaryAuthor = channel
                    val tag = "c:$channel"
                    if (!tokens.contains(tag)) {
                        tokens.add(tag)
                        keyParts.append(tag).append("|")
                    }
                }
            }

            // 2. Video Title / Description view IDs
            if (resId.contains("video_title") || resId.contains("title_text") ||
                resId.contains("reel_video_title") || resId.contains("video_description")
            ) {
                if (text.isNotEmpty() && text.length >= 3 && !isIgnoredUiText(text)) {
                    val title = text.lowercase().take(30)
                    if (primaryTitle.isEmpty()) primaryTitle = title
                    val tag = "t:$title"
                    if (!tokens.contains(tag)) {
                        tokens.add(tag)
                        keyParts.append(tag).append("|")
                    }
                }
            }

            // 3. Audio / Sound view IDs
            if (resId.contains("sound_title") || resId.contains("audio_track") ||
                resId.contains("sound_button") || resId.contains("music_title")
            ) {
                if (text.isNotEmpty() && !isIgnoredUiText(text)) {
                    val audio = text.lowercase().take(25)
                    val tag = "a:$audio"
                    if (!tokens.contains(tag)) {
                        tokens.add(tag)
                        keyParts.append(tag).append("|")
                    }
                }
            }

            // 4. Content description parsing (Matches early Phase 1 events with late Phase 2 child views)
            if (desc.isNotEmpty()) {
                val lowerDesc = desc.lowercase()
                if (lowerDesc.startsWith("short by ")) {
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
                } else if (lowerDesc.contains(" channel icon") || lowerDesc.contains("'s channel")) {
                    val parsedChannel = lowerDesc
                        .substringBefore(" channel icon")
                        .substringBefore("'s channel")
                        .trim()
                        .removePrefix("@")
                    if (parsedChannel.isNotEmpty() && parsedChannel.length in 2..35 && !isIgnoredUiText(parsedChannel)) {
                        if (primaryAuthor.isEmpty()) primaryAuthor = parsedChannel
                        val tag = "c:$parsedChannel"
                        tokens.add(tag)
                    }
                }
            }

            // 5. Stable on-screen text snippet
            if (text.isNotEmpty() && text.length in 3..40 && !isIgnoredUiText(text)) {
                tokens.add("s:" + text.lowercase())
            }
        }

        val canonicalId = if (keyParts.length > 3) {
            keyParts.toString()
        } else if (tokens.isNotEmpty()) {
            "yt:" + tokens.take(3).joinToString(separator = "|")
        } else {
            ""
        }

        if (canonicalId.isEmpty() || canonicalId == "yt:") return null

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

        if (text.matches(Regex("^[0-9.,kKmMbB: #]+$"))) return true
        if (text.matches(Regex("^[0-9]+.*(likes?|views?|comments?|shares?|subscribers?|k|m|b)$"))) return true

        val ignoredTokens = setOf(
            "reels", "shorts", "follow", "following", "like", "liked", "dislike",
            "comment", "comments", "share", "remix", "subscribe", "subscribed",
            "save", "saved", "more", "audio", "original audio", "sponsored",
            "suggested for you", "use audio", "watch again", "reply", "see translation",
            "play", "pause", "mute", "unmute", "search", "home", "subscriptions",
            "library", "notifications"
        )
        return ignoredTokens.contains(text)
    }

    private fun traverseNodes(
        node: AccessibilityNodeInfo?,
        depth: Int = 0,
        maxDepth: Int = 15,
        visitor: (AccessibilityNodeInfo) -> Unit
    ) {
        if (node == null || depth > maxDepth) return
        visitor(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNodes(child, depth + 1, maxDepth, visitor)
        }
    }
}
