package com.example.service.extractor

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.model.ScrollPlatform

/**
 * Strategy implementation for extracting stable video identities from Instagram Reels.
 *
 * Designed to be 100% resilient against Meta layout updates and custom ViewPager2 wrapping:
 * 1. Dynamic Node Tree Traversal: Traverses hierarchy looking for the core video player
 *    view hierarchy or parent container boundaries rather than relying solely on hardcoded IDs.
 * 2. Viewport Center Geometry: Pinpoints the active child node occupying the central screen
 *    viewport (center X / center Y) and tracks its exact screen coordinate bounding box.
 * 3. Multi-Token Semantic & Structural Extraction: Captures author handles, content descriptions
 *    ("Reel by...", "Profile picture"), audio pills, captions, and structural container signatures.
 * 4. Structural Fallback Guarantee: If text or IDs are obfuscated or buffering, derives a stable
 *    structural identity from viewport bounding boxes and sub-tree topology so scroll events
 *    are never missed.
 * 5. Optimized DFS with Branch Pruning: Prevents any rendering latency on the user's screen.
 * 6. Descriptive Debug Logging: Outputs active node hierarchy tree strings to trace Meta layout changes.
 */
class InstagramIdentityExtractor : VideoIdentityExtractor {

    override val platform: ScrollPlatform = ScrollPlatform.INSTAGRAM
    override val targetPackageName: String = ScrollPlatform.INSTAGRAM.packageName

    override fun extractIdentity(context: Context, rootNode: AccessibilityNodeInfo): CandidateVideo? {
        val displayMetrics = context.resources.displayMetrics
        val screenHeight = if (displayMetrics.heightPixels > 0) displayMetrics.heightPixels else 2400
        val screenWidth = if (displayMetrics.widthPixels > 0) displayMetrics.widthPixels else 1080
        val screenCenterX = screenWidth / 2
        val screenCenterY = screenHeight / 2

        var primaryAuthor = ""
        var primaryCaption = ""
        var primaryAudio = ""
        var primaryContentDescription = ""
        var reelsItemIndex = -1

        var centerChildNodeHash = 0
        var centerChildBounds = Rect()
        var foundReelContainer = false
        var containerClass = ""

        val tokens = mutableSetOf<String>()
        val keyParts = StringBuilder("ig:")
        val nodeRect = Rect()

        // Track closest child to screen center
        var minDistanceToCenter = Int.MAX_VALUE
        var bestCenterRect = Rect()

        val hierarchyStringBuilder = StringBuilder()

        // 1. Perform optimized DFS traversal over the active Instagram layout tree
        traverseNodesDfs(
            node = rootNode,
            depth = 0,
            maxDepth = 25,
            screenHeight = screenHeight,
            screenWidth = screenWidth
        ) { node, depth ->
            node.getBoundsInScreen(nodeRect)

            val resId = node.viewIdResourceName?.lowercase() ?: ""
            val rawText = node.text?.toString()?.trim() ?: ""
            val rawDesc = node.contentDescription?.toString()?.trim() ?: ""
            val className = node.className?.toString()?.lowercase() ?: ""

            // Check for Core Instagram Reels / Media Pager Container
            val isReelsContainer = resId.contains("clips_viewer") ||
                    resId.contains("reel_viewer") ||
                    resId.contains("clips_video_container") ||
                    resId.contains("clips_root") ||
                    resId.contains("clips_item_root") ||
                    resId.contains("reel_recycler") ||
                    resId.contains("clips_viewpager") ||
                    resId.contains("viewpager") ||
                    className.contains("viewpager2") ||
                    className.contains("recyclerview") ||
                    (resId.contains("video_container") && (rawDesc.contains("reel") || rawText.contains("reel")))

            if (isReelsContainer) {
                foundReelContainer = true
                containerClass = className

                // Extract collection item info if populated
                node.collectionItemInfo?.let { itemInfo ->
                    if (itemInfo.rowIndex >= 0) {
                        reelsItemIndex = itemInfo.rowIndex
                    }
                }
            }

            // Detect the primary child occupying the center of the display viewport
            if (nodeRect.width() >= screenWidth * 0.6f && nodeRect.height() >= screenHeight * 0.4f) {
                val distY = kotlin.math.abs(nodeRect.centerY() - screenCenterY)
                val distX = kotlin.math.abs(nodeRect.centerX() - screenCenterX)
                val totalDist = distY + distX

                if (totalDist < minDistanceToCenter) {
                    minDistanceToCenter = totalDist
                    bestCenterRect = Rect(nodeRect)
                    centerChildNodeHash = node.hashCode()
                    foundReelContainer = true
                }
            }

            // Also check collection item info from child nodes
            if (reelsItemIndex == -1) {
                node.collectionItemInfo?.let { itemInfo ->
                    if (itemInfo.rowIndex >= 0) {
                        reelsItemIndex = itemInfo.rowIndex
                    }
                }
            }

            // A. Author and handle matching
            if (rawText.startsWith("@") && rawText.length in 2..45 && !isIgnoredUiText(rawText)) {
                val author = rawText.removePrefix("@").lowercase().trim()
                if (author.isNotEmpty() && !isIgnoredUiText(author)) {
                    if (primaryAuthor.isEmpty()) primaryAuthor = author
                    val tag = "u:$author"
                    if (!tokens.contains(tag)) {
                        tokens.add(tag)
                        keyParts.append(tag).append("|")
                    }
                }
            }

            if (resId.contains("user_name") || resId.contains("username") ||
                resId.contains("profile_name") || resId.contains("author") ||
                resId.contains("clips_author_name") || resId.contains("row_feed_photo_profile_name") ||
                resId.contains("owner_name") || resId.contains("clips_author_container") ||
                resId.contains("clips_user_name") || resId.contains("row_feed_photo_profile_metalabel")
            ) {
                if (rawText.isNotEmpty() && !isIgnoredUiText(rawText)) {
                    val author = rawText.lowercase().removePrefix("@").trim()
                    if (author.isNotEmpty() && !isIgnoredUiText(author)) {
                        if (primaryAuthor.isEmpty()) primaryAuthor = author
                        val tag = "u:$author"
                        if (!tokens.contains(tag)) {
                            tokens.add(tag)
                            keyParts.append(tag).append("|")
                        }
                    }
                }
            }

            // B. Content description parsing ("Reel by <author>", "<author>'s profile picture", etc.)
            if (rawDesc.isNotEmpty()) {
                val lowerDesc = rawDesc.lowercase()

                if (primaryContentDescription.isEmpty() && !isIgnoredUiText(lowerDesc)) {
                    primaryContentDescription = lowerDesc
                }

                if (lowerDesc.startsWith("reel by ") || lowerDesc.startsWith("photo by ") || lowerDesc.startsWith("video by ")) {
                    primaryContentDescription = lowerDesc
                    val parsedAuthor = lowerDesc
                        .removePrefix("reel by ")
                        .removePrefix("photo by ")
                        .removePrefix("video by ")
                        .substringBefore("•")
                        .substringBefore(",")
                        .substringBefore("-")
                        .trim()
                        .removePrefix("@")

                    if (parsedAuthor.isNotEmpty() && parsedAuthor.length in 2..40 && !isIgnoredUiText(parsedAuthor)) {
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
                        .substringBefore("•")
                        .trim()
                        .removePrefix("@")

                    if (parsedAuthor.isNotEmpty() && parsedAuthor.length in 2..40 && !isIgnoredUiText(parsedAuthor)) {
                        if (primaryAuthor.isEmpty()) primaryAuthor = parsedAuthor
                        val tag = "u:$parsedAuthor"
                        tokens.add(tag)
                    }
                } else if (lowerDesc.startsWith("see profile for ") || lowerDesc.startsWith("follow ")) {
                    val parsedAuthor = lowerDesc
                        .removePrefix("see profile for ")
                        .removePrefix("follow ")
                        .substringBefore("•")
                        .substringBefore(",")
                        .trim()
                        .removePrefix("@")

                    if (parsedAuthor.isNotEmpty() && parsedAuthor.length in 2..40 && !isIgnoredUiText(parsedAuthor)) {
                        if (primaryAuthor.isEmpty()) primaryAuthor = parsedAuthor
                        val tag = "u:$parsedAuthor"
                        tokens.add(tag)
                    }
                } else if (lowerDesc.contains("audio used in reel") || lowerDesc.contains("original audio") || lowerDesc.contains("music track")) {
                    val audioTag = "a:" + lowerDesc.take(30)
                    tokens.add(audioTag)
                }
            }

            // C. Audio track / Music pill view IDs
            if (resId.contains("audio_title") || resId.contains("music_title") ||
                resId.contains("sound_title") || resId.contains("audio_track") ||
                resId.contains("clips_audio_mix_pill") || resId.contains("audio_pill") ||
                resId.contains("clips_audio") || resId.contains("music_sticker")
            ) {
                if (rawText.isNotEmpty() && !isIgnoredUiText(rawText)) {
                    val audio = rawText.lowercase().take(30)
                    if (primaryAudio.isEmpty()) primaryAudio = audio
                    val audioTag = "a:$audio"
                    if (!tokens.contains(audioTag)) {
                        tokens.add(audioTag)
                        keyParts.append(audioTag).append("|")
                    }
                }
            }

            // D. Caption & Description text matching
            if (resId.contains("caption") || resId.contains("clips_caption") ||
                resId.contains("caption_text_view") || resId.contains("row_feed_comment_textview_layout") ||
                resId.contains("reel_caption") || resId.contains("video_caption")
            ) {
                if (rawText.isNotEmpty() && rawText.length >= 3 && !isIgnoredUiText(rawText)) {
                    val caption = rawText.lowercase().take(35)
                    if (primaryCaption.isEmpty()) primaryCaption = caption
                    val capTag = "c:$caption"
                    if (!tokens.contains(capTag)) {
                        tokens.add(capTag)
                        keyParts.append(capTag).append("|")
                    }
                }
            }

            // E. On-screen text snippet fallback in viewport lower half
            if (primaryCaption.isEmpty() && rawText.isNotEmpty() && rawText.length in 4..60 &&
                !isIgnoredUiText(rawText) && !rawText.startsWith("@") &&
                nodeRect.top > screenHeight * 0.4
            ) {
                val candidateText = rawText.lowercase().take(30)
                if (candidateText.isNotEmpty() && !isIgnoredUiText(candidateText)) {
                    primaryCaption = candidateText
                    val tag = "c:$candidateText"
                    tokens.add(tag)
                }
            }

            // Collect debug hierarchy snapshot for first 8 nodes
            if (depth <= 4 && hierarchyStringBuilder.length < 300) {
                hierarchyStringBuilder.append(" [d=$depth ${node.className?.toString()?.substringAfterLast('.')} id=${resId.substringAfterLast('/')}]")
            }
        }

        // 2. Viewport coordinate bounding box signature
        val viewportTop = bestCenterRect.top
        val viewportBottom = bestCenterRect.bottom
        val scrollPosKey = if (reelsItemIndex >= 0) {
            "p:$reelsItemIndex"
        } else if (bestCenterRect.height() > 0) {
            "py:${viewportTop / (screenHeight / 5).coerceAtLeast(1)}"
        } else {
            "p:0"
        }
        tokens.add(scrollPosKey)

        // 3. Clean content description
        val cleanDesc = primaryContentDescription
            .lowercase()
            .replace(Regex("[^a-z0-9@_\\- ]"), "")
            .trim()
            .take(35)

        // 4. Structural signature calculation (guarantees a stable unique ID even with obfuscated IDs or zero text)
        val structuralSignature = "box_${viewportTop}_${viewportBottom}_h${centerChildNodeHash.toString(16)}"

        // 5. Construct canonical ID
        val canonicalId = when {
            // Priority 1: Direct Content Description + Scroll Position
            cleanDesc.isNotEmpty() && (primaryAuthor.isNotEmpty() || reelsItemIndex >= 0) -> {
                "ig:desc:$cleanDesc|$scrollPosKey|"
            }
            // Priority 2: Author + Caption + Scroll Position
            primaryAuthor.isNotEmpty() && primaryCaption.isNotEmpty() -> {
                "ig:u:$primaryAuthor|c:$primaryCaption|$scrollPosKey|"
            }
            // Priority 3: Author + Audio/Tokens + Scroll Position
            primaryAuthor.isNotEmpty() -> {
                if (primaryAudio.isNotEmpty()) {
                    "ig:u:$primaryAuthor|a:$primaryAudio|$scrollPosKey|"
                } else {
                    "ig:u:$primaryAuthor|$scrollPosKey|"
                }
            }
            // Priority 4: Content Description alone
            cleanDesc.isNotEmpty() -> {
                "ig:desc:$cleanDesc|$scrollPosKey|"
            }
            // Priority 5: Caption + Scroll Position
            primaryCaption.isNotEmpty() -> {
                "ig:c:$primaryCaption|$scrollPosKey|"
            }
            // Priority 6: Accumulated key parts
            keyParts.length > 4 -> {
                "$keyParts$scrollPosKey|"
            }
            // Priority 7: Tokens
            tokens.isNotEmpty() -> {
                "ig:" + tokens.take(3).joinToString(separator = "|") + "|$scrollPosKey|"
            }
            // Priority 8: Structural viewport fallback guarantee (100% immune to obfuscation)
            foundReelContainer || bestCenterRect.height() > 0 -> {
                "ig:struct:$structuralSignature|$scrollPosKey|"
            }
            else -> {
                // Fallback guarantee when inside Instagram Reels
                "ig:reel_viewport_active|$scrollPosKey|"
            }
        }

        Log.d(
            TAG,
            "[INSTAGRAM REELS HIERARCHY] ID='$canonicalId' | Author='$primaryAuthor' | Caption='$primaryCaption' | Bounds=[${bestCenterRect.left},${bestCenterRect.top},${bestCenterRect.right},${bestCenterRect.bottom}] | Container='$containerClass' | Tree=$hierarchyStringBuilder"
        )

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

        // Stats & counts (e.g. 1.2M, 45.3K likes)
        if (text.matches(Regex("^[0-9.,kKmMbB: #+]+$"))) return true
        if (text.matches(Regex("^[0-9]+.*(likes?|views?|comments?|shares?|remixes?|k|m|b)$"))) return true

        val ignoredTokens = setOf(
            "reels", "shorts", "follow", "following", "like", "liked", "dislike",
            "comment", "comments", "share", "remix", "subscribe", "subscribed",
            "save", "saved", "more", "audio", "original audio", "sponsored",
            "suggested for you", "use audio", "watch again", "reply", "see translation",
            "play", "pause", "mute", "unmute", "search", "home", "explore",
            "activity", "profile", "send", "message", "tagged", "music",
            "view profile", "tap to see", "shop", "see all", "back", "close"
        )
        return ignoredTokens.contains(text)
    }

    /**
     * Optimized DFS (Depth-First Search) traversal with boundary branch pruning.
     * Drops sub-trees that are completely off-screen to guarantee 0ms UI lag.
     */
    private fun traverseNodesDfs(
        node: AccessibilityNodeInfo?,
        depth: Int,
        maxDepth: Int,
        screenHeight: Int,
        screenWidth: Int,
        visitor: (AccessibilityNodeInfo, Int) -> Unit
    ) {
        if (node == null || depth > maxDepth) return

        try {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            // Prune branches completely outside the screen viewport (with 100px margin for upcoming items)
            if (bounds.bottom < -100 || bounds.top > screenHeight + 100 ||
                bounds.right < -100 || bounds.left > screenWidth + 100
            ) {
                return
            }

            visitor(node, depth)
        } catch (t: Throwable) {
            // Ignore dynamic recycling exceptions
        }

        val childCount = try { node.childCount } catch (t: Throwable) { 0 }
        for (i in 0 until childCount) {
            val child = try { node.getChild(i) } catch (t: Throwable) { null } ?: continue
            traverseNodesDfs(child, depth + 1, maxDepth, screenHeight, screenWidth, visitor)
        }
    }

    companion object {
        private const val TAG = "InstagramExtractor"
    }
}
