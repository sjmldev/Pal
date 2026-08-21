package com.example.service.extractor

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.model.ScrollPlatform

/**
 * Strategy implementation for extracting stable video identities from YouTube Shorts.
 *
 * Utilizes YouTube's unique view hierarchy and event lifecycle patterns:
 * - Direct channel handle extraction (`@creator` handle pattern).
 * - Channel avatar and subscribe button content descriptions (`"Subscribe to <Channel>"`, `"<Channel> channel icon"`).
 * - Explicit YouTube view resource IDs (`reel_channel_name`, `channel_name`, `reel_video_title`, `video_title`, `sound_button`).
 * - Video title / description parsing from both view properties and accessibility content descriptions.
 * - Multi-token normalization guaranteeing equivalence between early description and late child-view binding.
 * - Viewport bounds validation using real display metrics.
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
            val rawText = node.text?.toString()?.trim() ?: ""
            val rawDesc = node.contentDescription?.toString()?.trim() ?: ""

            // 1. Direct YouTube Channel Handle extraction (e.g. "@creator")
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

            // 2. Channel / Creator name view IDs (e.g. reel_channel_name, channel_name, owner_name)
            if (resId.contains("channel_name") || resId.contains("owner_name") ||
                resId.contains("owner_text") || resId.contains("reel_channel_name") ||
                resId.contains("channel_title") || resId.contains("uploader_name") ||
                resId.contains("author") || resId.contains("creator")
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

            // 3. Video Title / Description view IDs (e.g. reel_video_title, video_title, title_text)
            if (resId.contains("video_title") || resId.contains("title_text") ||
                resId.contains("reel_video_title") || resId.contains("video_description") ||
                resId.contains("reel_player_title") || resId.contains("reel_description")
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

            // 4. Audio / Sound view IDs
            if (resId.contains("sound_title") || resId.contains("audio_track") ||
                resId.contains("sound_button") || resId.contains("music_title") ||
                resId.contains("reel_sound_button") || resId.contains("reel_audio_title")
            ) {
                if (rawText.isNotEmpty() && !isIgnoredUiText(rawText)) {
                    val audio = rawText.lowercase().take(25)
                    val tag = "a:$audio"
                    if (!tokens.contains(tag)) {
                        tokens.add(tag)
                        keyParts.append(tag).append("|")
                    }
                }
            }

            // 5. Content description parsing (Handles early Phase 1 events and subscribe/avatar accessibility labels)
            if (rawDesc.isNotEmpty()) {
                val lowerDesc = rawDesc.lowercase()

                when {
                    lowerDesc.startsWith("short by ") -> {
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
                }
            }

            // 6. Stable on-screen text snippet fallback
            if (rawText.isNotEmpty() && rawText.length in 3..60 && !isIgnoredUiText(rawText)) {
                val snippetTag = "s:" + rawText.lowercase().take(30)
                tokens.add(snippetTag)
            }
        }

        // Formulate a robust, canonical video identity string
        val canonicalId = when {
            primaryAuthor.isNotEmpty() && primaryTitle.isNotEmpty() -> "yt:c:$primaryAuthor|t:$primaryTitle|"
            primaryAuthor.isNotEmpty() && tokens.isNotEmpty() -> {
                val extra = tokens.filter { !it.startsWith("c:") }.take(2).joinToString("|")
                if (extra.isNotEmpty()) "yt:c:$primaryAuthor|$extra|" else "yt:c:$primaryAuthor|"
            }
            primaryAuthor.isNotEmpty() -> "yt:c:$primaryAuthor|"
            primaryTitle.isNotEmpty() && tokens.isNotEmpty() -> {
                val extra = tokens.filter { !it.startsWith("t:") }.take(2).joinToString("|")
                if (extra.isNotEmpty()) "yt:t:$primaryTitle|$extra|" else "yt:t:$primaryTitle|"
            }
            primaryTitle.isNotEmpty() -> "yt:t:$primaryTitle|"
            keyParts.length > 3 -> keyParts.toString()
            tokens.isNotEmpty() -> "yt:" + tokens.take(3).joinToString(separator = "|")
            else -> ""
        }

        if (canonicalId.isEmpty() || canonicalId == "yt:") {
            Log.v(TAG, "[YOUTUBE SHORT EXTRACTOR] No identifiable video attributes found on screen.")
            return null
        }

        Log.d(TAG, "[YOUTUBE SHORT EXTRACTOR] Extracted candidate: ID='$canonicalId', Author='$primaryAuthor', Title='$primaryTitle', TokenCount=${tokens.size}")

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
            "tap to unmute", "sound", "use sound"
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

    companion object {
        private const val TAG = "YouTubeExtractor"
    }
}
