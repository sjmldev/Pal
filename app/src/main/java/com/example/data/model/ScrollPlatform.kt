package com.example.data.model

enum class ScrollPlatform(
    val displayName: String,
    val packageName: String,
    val shortName: String,
    val iconEmoji: String
) {
    INSTAGRAM("Instagram Reels", "com.instagram.android", "Instagram", "🎬"),
    YOUTUBE("YouTube Shorts", "com.google.android.youtube", "YouTube", "▶️"),
    FACEBOOK("Facebook Reels", "com.facebook.katana", "Facebook", "📘"),
    SNAPCHAT("Snapchat Spotlight", "com.snapchat.android", "Snapchat", "👻")
}
