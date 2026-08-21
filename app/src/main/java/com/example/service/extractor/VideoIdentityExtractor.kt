package com.example.service.extractor

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.model.ScrollPlatform

/**
 * Immutable representation of a video candidate extracted from accessibility node trees.
 */
data class CandidateVideo(
    val platform: ScrollPlatform,
    val primaryAuthor: String,
    val tokens: Set<String>,
    val canonicalId: String
)

/**
 * Strategy interface for deriving a stable video identity based on a platform's
 * unique view hierarchy and accessibility properties.
 */
interface VideoIdentityExtractor {
    val platform: ScrollPlatform
    val targetPackageName: String

    /**
     * Inspects the active accessibility node hierarchy and derives a stable video identity.
     * Returns null if no valid on-screen video is identified or the view is unmeasured.
     */
    fun extractIdentity(context: Context, rootNode: AccessibilityNodeInfo): CandidateVideo?
}
