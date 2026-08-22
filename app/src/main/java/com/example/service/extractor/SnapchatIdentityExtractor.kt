package com.example.service.extractor

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.model.ScrollPlatform

/**
 * Strategy implementation for extracting stable video identities from Snapchat Spotlight & Stories.
 *
 * Utilizes Snapchat's view hierarchy patterns:
 * - View resource IDs: spotlight_*, story_*, snap_*, username, handle, sound_title, caption
 * - Content descriptions: "Spotlight by <creator>", "Snap by <user>", "Remix with sound"
 * - Music pill labels and creator badges
 * - Viewport bounds validation
 */
class SnapchatIdentityExtractor : VideoIdentityExtractor {

    override val platform: ScrollPlatform = ScrollPlatform.SNAPCHAT
    override val targetPackageName: String = ScrollPlatform.SNAPCHAT.packageName

    override fun extractIdentity(context: Context, rootNode: AccessibilityNodeInfo): CandidateVideo? {
        val displayMetrics = context.resources.displayMetrics
        val screenHeight = if (displayMetrics.heightPixels > 0) displayMetrics.heightPixels else 2400
        val screenWidth = if (displayMetrics.widthPixels > 0) displayMetrics.widthPixels else 1080

        var primaryAuthor = ""
        val tokens = mutableSetOf<String>()
        val keyParts = StringBuilder("sc:")

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

            // 1. Author and handle matching via Snapchat view IDs
            if (resId.contains("username") || resId.contains("handle") ||
                resId.contains("creator") || resId.contains("author") ||
                resId.contains("display_name") || resId.contains("spotlight_creator") ||
                resId.contains("story_author") || resId.contains("profile_badge")
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

            // 2. Content description parsing (e.g. "Spotlight by user", "Snap by creator")
            if (desc.isNotEmpty()) {
                val lowerDesc = desc.lowercase()
                if (lowerDesc.startsWith("spotlight by ") || lowerDesc.startsWith("snap by ") || lowerDesc.startsWith("video by ")) {
                    val parsedAuthor = lowerDesc
                        .removePrefix("spotlight by ")
                        .removePrefix("snap by ")
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
                } else if (lowerDesc.contains("'s profile picture") || lowerDesc.contains("'s spotlight")) {
                    val parsedAuthor = lowerDesc
                        .substringBefore("'s profile picture")
                        .substringBefore("'s spotlight")
                        .trim()
                        .removePrefix("@")
                    if (parsedAuthor.isNotEmpty() && parsedAuthor.length in 2..35 && !isIgnoredUiText(parsedAuthor)) {
                        if (primaryAuthor.isEmpty()) primaryAuthor = parsedAuthor
                        val tag = "u:$parsedAuthor"
                        tokens.add(tag)
                    }
                } else if (lowerDesc.contains("sound") || lowerDesc.contains("original audio") || lowerDesc.contains("music")) {
                    val audioTag = "a:" + lowerDesc.take(30)
                    tokens.add(audioTag)
                }
            }

            // 3. Sound / Music track
            if (resId.contains("sound") || resId.contains("audio") ||
                resId.contains("music_title") || resId.contains("sound_pill") ||
                resId.contains("lens_name")
            ) {
                if (text.isNotEmpty() && !isIgnoredUiText(text)) {
                    val audioTag = "a:" + text.lowercase().take(30)
                    if (!tokens.contains(audioTag)) {
                        tokens.add(audioTag)
                        keyParts.append(audioTag).append("|")
                    }
                }
            }

            // 4. Caption & Description matching
            if (resId.contains("caption") || resId.contains("description") ||
                resId.contains("title") || resId.contains("spotlight_caption")
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
            "sc:" + tokens.take(3).joinToString(separator = "|")
        } else {
            ""
        }

        if (canonicalId.isEmpty() || canonicalId == "sc:") return null

        return CandidateVideo(
            platform = ScrollPlatform.SNAPCHAT,
            primaryAuthor = primaryAuthor,
            tokens = tokens,
            canonicalId = canonicalId
        )
    }

    private fun isIgnoredUiText(rawText: String): Boolean {
        val text = rawText.trim().lowercase()
        if (text.isEmpty()) return true

        if (text.matches(Regex("^[0-9.,kKmMbB: #]+$"))) return true
        if (text.matches(Regex("^[0-9]+.*(likes?|views?|shares?|remixes?|k|m|b)$"))) return true

        val ignoredTokens = setOf(
            "spotlight", "stories", "chat", "camera", "maps", "discover", "subscribe",
            "subscribed", "like", "liked", "share", "remix", "send", "save", "saved",
            "more", "lens", "filter", "sound", "reply", "play", "pause", "mute",
            "unmute", "search", "notifications", "profile", "add friend"
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
        try {
            visitor(node)
        } catch (t: Throwable) {
            // Ignore node inspection exceptions during dynamic UI scrolling
        }
        val count = try { node.childCount } catch (t: Throwable) { 0 }
        for (i in 0 until count) {
            val child = try { node.getChild(i) } catch (t: Throwable) { null } ?: continue
            traverseNodes(child, depth + 1, maxDepth, visitor)
        }
    }
}
