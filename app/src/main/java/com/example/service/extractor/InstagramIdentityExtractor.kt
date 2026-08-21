package com.example.service.extractor

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.model.ScrollPlatform

/**
 * Strategy implementation for extracting stable video identities from Instagram Reels.
 *
 * Utilizes Instagram's unique view hierarchy patterns:
 * - Specific view resource IDs (e.g. clips_author_name, row_feed_photo_profile_name, clips_caption)
 * - Structured content descriptions ("Reel by <author>", "<author>'s profile picture")
 * - Audio pills and metadata tokens
 * - Viewport bounds validation using real display metrics
 */
class InstagramIdentityExtractor : VideoIdentityExtractor {

    override val platform: ScrollPlatform = ScrollPlatform.INSTAGRAM
    override val targetPackageName: String = ScrollPlatform.INSTAGRAM.packageName

    override fun extractIdentity(context: Context, rootNode: AccessibilityNodeInfo): CandidateVideo? {
        val displayMetrics = context.resources.displayMetrics
        val screenHeight = if (displayMetrics.heightPixels > 0) displayMetrics.heightPixels else 2400
        val screenWidth = if (displayMetrics.widthPixels > 0) displayMetrics.widthPixels else 1080

        var primaryAuthor = ""
        val tokens = mutableSetOf<String>()
        val keyParts = StringBuilder("ig:")

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

            // 1. Author and handle matching via Instagram view IDs
            if (resId.contains("user_name") || resId.contains("username") ||
                resId.contains("profile_name") || resId.contains("author") ||
                resId.contains("clips_author_name") || resId.contains("row_feed_photo_profile_name") ||
                resId.contains("owner_name") || resId.contains("clips_author_container") ||
                resId.contains("clips_user_name")
            ) {
                if (text.isNotEmpty() && !isIgnoredUiText(text)) {
                    val author = text.lowercase().removePrefix("@")
                    if (primaryAuthor.isEmpty()) primaryAuthor = author
                    val tag = "u:$author"
                    if (!tokens.contains(tag)) {
                        tokens.add(tag)
                        keyParts.append(tag).append("|")
                    }
                }
            }

            // 2. Content description parsing (e.g. "Reel by username", "Photo by username")
            if (desc.isNotEmpty()) {
                val lowerDesc = desc.lowercase()
                if (lowerDesc.startsWith("reel by ") || lowerDesc.startsWith("photo by ") || lowerDesc.startsWith("video by ")) {
                    val parsedAuthor = lowerDesc
                        .removePrefix("reel by ")
                        .removePrefix("photo by ")
                        .removePrefix("video by ")
                        .substringBefore("•")
                        .substringBefore(",")
                        .trim()
                        .removePrefix("@")
                    if (parsedAuthor.isNotEmpty() && parsedAuthor.length in 2..35 && !isIgnoredUiText(parsedAuthor)) {
                        if (primaryAuthor.isEmpty()) primaryAuthor = parsedAuthor
                        val tag = "u:$parsedAuthor"
                        if (!tokens.contains(tag)) {
                            tokens.add(tag)
                            keyParts.append(tag).append("|")
                        }
                    }
                } else if (lowerDesc.contains("'s profile picture") || lowerDesc.contains(" profile picture")) {
                    val parsedAuthor = lowerDesc
                        .substringBefore("'s profile picture")
                        .substringBefore(" profile picture")
                        .trim()
                        .removePrefix("@")
                    if (parsedAuthor.isNotEmpty() && parsedAuthor.length in 2..35 && !isIgnoredUiText(parsedAuthor)) {
                        if (primaryAuthor.isEmpty()) primaryAuthor = parsedAuthor
                        val tag = "u:$parsedAuthor"
                        tokens.add(tag)
                    }
                } else if (lowerDesc.contains("audio used in reel") || lowerDesc.contains("original audio")) {
                    val audioTag = "a:" + lowerDesc.take(30)
                    tokens.add(audioTag)
                }
            }

            // 3. Audio track view IDs
            if (resId.contains("audio_title") || resId.contains("music_title") ||
                resId.contains("sound_title") || resId.contains("audio_track") ||
                resId.contains("clips_audio_mix_pill") || resId.contains("audio_pill")
            ) {
                if (text.isNotEmpty() && !isIgnoredUiText(text)) {
                    val audioTag = "a:" + text.lowercase().take(30)
                    if (!tokens.contains(audioTag)) {
                        tokens.add(audioTag)
                        keyParts.append(audioTag).append("|")
                    }
                }
            }

            // 4. Caption matching
            if (resId.contains("caption") || resId.contains("clips_caption") ||
                resId.contains("caption_text_view") || resId.contains("row_feed_comment_textview_layout")
            ) {
                if (text.isNotEmpty() && text.length >= 3 && !isIgnoredUiText(text)) {
                    val capTag = "c:" + text.lowercase().take(25)
                    if (!tokens.contains(capTag)) {
                        tokens.add(capTag)
                        keyParts.append(capTag).append("|")
                    }
                }
            }

            // 5. Stable on-screen text snippet fallback
            if (text.isNotEmpty() && text.length in 3..40 && !isIgnoredUiText(text)) {
                tokens.add("t:" + text.lowercase())
            }
        }

        val canonicalId = if (keyParts.length > 3) {
            keyParts.toString()
        } else if (tokens.isNotEmpty()) {
            "ig:" + tokens.take(3).joinToString(separator = "|")
        } else {
            ""
        }

        if (canonicalId.isEmpty() || canonicalId == "ig:") return null

        return CandidateVideo(
            platform = ScrollPlatform.INSTAGRAM,
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
