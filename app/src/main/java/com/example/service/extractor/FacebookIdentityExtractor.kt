package com.example.service.extractor

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.model.ScrollPlatform

/**
 * Strategy implementation for extracting stable video identities from Facebook Reels.
 *
 * Utilizes Facebook's unique view hierarchy patterns:
 * - View resource IDs: fb_shorts_*, reels_*, video_player_*, author_name, actor_name
 * - Content descriptions: "Reel by <author>", "<author>'s reel", "Play video by <author>"
 * - Audio pills, captions, and hashtag tokens
 * - Viewport bounds filtering
 */
class FacebookIdentityExtractor : VideoIdentityExtractor {

    override val platform: ScrollPlatform = ScrollPlatform.FACEBOOK
    override val targetPackageName: String = ScrollPlatform.FACEBOOK.packageName

    override fun extractIdentity(context: Context, rootNode: AccessibilityNodeInfo): CandidateVideo? {
        val displayMetrics = context.resources.displayMetrics
        val screenHeight = if (displayMetrics.heightPixels > 0) displayMetrics.heightPixels else 2400
        val screenWidth = if (displayMetrics.widthPixels > 0) displayMetrics.widthPixels else 1080

        var primaryAuthor = ""
        val tokens = mutableSetOf<String>()
        val keyParts = StringBuilder("fb:")

        val nodeRect = Rect()

        traverseNodes(rootNode, maxDepth = 18) { node ->
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

            // 1. Author and handle matching via Facebook view IDs
            if (resId.contains("author") || resId.contains("actor_name") ||
                resId.contains("profile_name") || resId.contains("creator_name") ||
                resId.contains("fb_shorts_author") || resId.contains("reels_author") ||
                resId.contains("video_channel_name") || resId.contains("page_name")
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

            // 2. Content description parsing (e.g. "Reel by username", "Video by page")
            if (desc.isNotEmpty()) {
                val lowerDesc = desc.lowercase()
                if (lowerDesc.startsWith("reel by ") || lowerDesc.startsWith("video by ") || lowerDesc.startsWith("reels by ")) {
                    val parsedAuthor = lowerDesc
                        .removePrefix("reel by ")
                        .removePrefix("reels by ")
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
                } else if (lowerDesc.contains("'s profile picture") || lowerDesc.contains("'s reel")) {
                    val parsedAuthor = lowerDesc
                        .substringBefore("'s profile picture")
                        .substringBefore("'s reel")
                        .trim()
                        .removePrefix("@")
                    if (parsedAuthor.isNotEmpty() && parsedAuthor.length in 2..35 && !isIgnoredUiText(parsedAuthor)) {
                        if (primaryAuthor.isEmpty()) primaryAuthor = parsedAuthor
                        val tag = "u:$parsedAuthor"
                        tokens.add(tag)
                    }
                } else if (lowerDesc.contains("original audio") || lowerDesc.contains("music") || lowerDesc.contains("sound")) {
                    val audioTag = "a:" + lowerDesc.take(30)
                    tokens.add(audioTag)
                }
            }

            // 3. Audio track view IDs
            if (resId.contains("audio") || resId.contains("sound") ||
                resId.contains("music") || resId.contains("song_title")
            ) {
                if (text.isNotEmpty() && !isIgnoredUiText(text)) {
                    val audioTag = "a:" + text.lowercase().take(30)
                    if (!tokens.contains(audioTag)) {
                        tokens.add(audioTag)
                        keyParts.append(audioTag).append("|")
                    }
                }
            }

            // 4. Caption & Post text matching
            if (resId.contains("caption") || resId.contains("description") ||
                resId.contains("post_text") || resId.contains("reels_text") ||
                resId.contains("fb_shorts_description")
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
            "fb:" + tokens.take(3).joinToString(separator = "|")
        } else {
            ""
        }

        if (canonicalId.isEmpty() || canonicalId == "fb:") return null

        return CandidateVideo(
            platform = ScrollPlatform.FACEBOOK,
            primaryAuthor = primaryAuthor,
            tokens = tokens,
            canonicalId = canonicalId
        )
    }

    private fun isIgnoredUiText(rawText: String): Boolean {
        val text = rawText.trim().lowercase()
        if (text.isEmpty()) return true

        if (text.matches(Regex("^[0-9.,kKmMbB: #]+$"))) return true
        if (text.matches(Regex("^[0-9]+.*(likes?|views?|comments?|shares?|reactions?|k|m|b)$"))) return true

        val ignoredTokens = setOf(
            "reels", "watch", "follow", "following", "like", "liked", "react", "comment",
            "comments", "share", "remix", "send", "save", "saved", "more", "audio",
            "original audio", "sponsored", "suggested for you", "use audio", "reply",
            "see translation", "play", "pause", "mute", "unmute", "search", "home",
            "feed", "notifications", "menu", "see more", "hide"
        )
        return ignoredTokens.contains(text)
    }

    private fun traverseNodes(
        node: AccessibilityNodeInfo?,
        depth: Int = 0,
        maxDepth: Int = 18,
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
