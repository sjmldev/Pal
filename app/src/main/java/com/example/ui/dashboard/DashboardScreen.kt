package com.example.ui.dashboard

import android.app.Activity
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ads.StartAppBanner
import com.example.data.model.DailyScrollRecord
import com.example.data.model.ScrollPlatform
import com.example.ui.dashboard.components.ScrollHistoryChart
import com.example.ui.theme.InstagramGradientEnd
import com.example.ui.theme.InstagramGradientMid
import com.example.ui.theme.InstagramGradientStart
import com.example.ui.theme.PolishAmber
import com.example.ui.theme.PolishError
import com.example.ui.theme.PolishGreenSafe
import com.example.ui.theme.PolishOnPurpleContainer
import com.example.ui.theme.PolishPurpleContainer
import com.example.ui.theme.PolishPurplePrimary
import com.example.ui.theme.PolishSecondaryContainer
import com.example.ui.theme.PolishStreakGold
import com.example.ui.theme.YouTubeRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToLimits: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToFeedback: () -> Unit,
    viewModel: DashboardViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val toastMessage by viewModel.message.collectAsStateWithLifecycle()

    LaunchedEffect(toastMessage) {
        toastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }

    val record = uiState.todayRecord ?: DailyScrollRecord(
        dateString = "",
        instagramCount = 0,
        youtubeCount = 0,
        instagramLimit = 30,
        youtubeLimit = 30
    )

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("dashboard_screen_scaffold"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // App Brand Icon Tile
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(PolishPurplePrimary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Reels Pal",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = (-0.5).sp,
                                        fontSize = 18.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                // Streak Pill
                                if (uiState.stats.currentStreakDays > 0) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = PolishStreakGold.copy(alpha = 0.15f)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.LocalFireDepartment,
                                                contentDescription = "Streak",
                                                tint = PolishStreakGold,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Spacer(modifier = Modifier.width(2.dp))
                                            Text(
                                                text = "${uiState.stats.currentStreakDays}d",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                color = PolishStreakGold
                                            )
                                        }
                                    }
                                }
                            }
                            Text(
                                text = "Active • Protection ON",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 10.sp,
                                    letterSpacing = 0.5.sp
                                ),
                                color = PolishPurplePrimary
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.refreshData() },
                        modifier = Modifier.testTag("refresh_dashboard_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("dashboard_settings_button"),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = PolishPurpleContainer,
                            contentColor = PolishOnPurpleContainer
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            StartAppBanner(modifier = Modifier.fillMaxWidth())
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(2.dp))

                // Daily Limit Status Lock / Banner
                if (!record.limitSetToday) {
                    Card(
                        onClick = onNavigateToLimits,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = PolishPurpleContainer.copy(alpha = 0.55f)
                        ),
                        border = BorderStroke(1.dp, PolishPurplePrimary.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(PolishPurplePrimary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Set Today's Custom Limits",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = PolishOnPurpleContainer
                                )
                                Text(
                                    text = "Carrying over ${record.instagramLimit} IG / ${record.youtubeLimit} YT limit. Tap to adjust.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Locked",
                                tint = PolishStreakGold,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Today's limits locked until 12:00 AM (${record.instagramLimit} Reels / ${record.youtubeLimit} Shorts)",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Dual Platform Cards (YouTube & Instagram Grid)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // YouTube Shorts Card
                    Box(modifier = Modifier.weight(1f)) {
                        PolishPlatformCard(
                            platform = ScrollPlatform.YOUTUBE,
                            count = record.youtubeCount,
                            limit = record.youtubeLimit,
                            bonus = record.youtubeUnlockedBonus,
                            isBlocked = record.isYoutubeBlocked,
                            backgroundColor = PolishSecondaryContainer,
                            borderColor = PolishPurpleContainer,
                            onUnlockBonusClick = {
                                val activity = context as? Activity ?: return@PolishPlatformCard
                                viewModel.unlockExtraScrolls(ScrollPlatform.YOUTUBE, activity)
                            }
                        )
                    }

                    // Instagram Reels Card
                    Box(modifier = Modifier.weight(1f)) {
                        PolishPlatformCard(
                            platform = ScrollPlatform.INSTAGRAM,
                            count = record.instagramCount,
                            limit = record.instagramLimit,
                            bonus = record.instagramUnlockedBonus,
                            isBlocked = record.isInstagramBlocked,
                            backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                            borderColor = MaterialTheme.colorScheme.outlineVariant,
                            onUnlockBonusClick = {
                                val activity = context as? Activity ?: return@PolishPlatformCard
                                viewModel.unlockExtraScrolls(ScrollPlatform.INSTAGRAM, activity)
                            }
                        )
                    }
                }
            }

            // 7-Day Activity / Weekly Activity Card
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ScrollHistoryChart(
                        records = uiState.stats.last7Days,
                        dailyAverage = uiState.stats.dailyAverage
                    )

                    // Primary Action Button: "Set Daily Limit" (matching design HTML pill button)
                    Button(
                        onClick = onNavigateToLimits,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("dashboard_set_daily_limit_pill_button"),
                        shape = RoundedCornerShape(26.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PolishPurplePrimary,
                            contentColor = Color.White
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Set Daily Limit",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        )
                    }
                }
            }

            // Platform Comparison Distribution Card
            item {
                val totalScrolls7Days = uiState.stats.totalInstagram7Days + uiState.stats.totalYoutube7Days
                val igPercent = if (totalScrolls7Days > 0) (uiState.stats.totalInstagram7Days.toFloat() / totalScrolls7Days) * 100f else 50f
                val ytPercent = if (totalScrolls7Days > 0) (uiState.stats.totalYoutube7Days.toFloat() / totalScrolls7Days) * 100f else 50f

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp)
                    ) {
                        Text(
                            text = "Platform Distribution",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        // Multi-color progress bar
                        LinearProgressIndicator(
                            progress = { igPercent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp)),
                            color = InstagramGradientMid,
                            trackColor = YouTubeRed
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Instagram: ${"%.0f".format(igPercent)}% (${uiState.stats.totalInstagram7Days})",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = InstagramGradientMid
                            )
                            Text(
                                text = "YouTube: ${"%.0f".format(ytPercent)}% (${uiState.stats.totalYoutube7Days})",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = YouTubeRed
                            )
                        }
                    }
                }
            }

            // Floating Live HUD Toggle Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(PolishPurpleContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Visibility,
                                    contentDescription = null,
                                    tint = PolishPurplePrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Floating Live HUD",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Displays badge during Reels & Shorts",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Switch(
                            checked = uiState.isHudEnabled,
                            onCheckedChange = { viewModel.toggleHudOverlay(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = PolishPurplePrimary
                            )
                        )
                    }
                }
            }

            // Quick Support & Feedback
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onNavigateToLimits,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .testTag("dashboard_edit_limits_button"),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Limits")
                    }

                    OutlinedButton(
                        onClick = onNavigateToFeedback,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .testTag("dashboard_feedback_button"),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Icon(Icons.Default.Feedback, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Help & Support")
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun PolishPlatformCard(
    platform: ScrollPlatform,
    count: Int,
    limit: Int,
    bonus: Int,
    isBlocked: Boolean,
    backgroundColor: Color,
    borderColor: Color,
    onUnlockBonusClick: () -> Unit
) {
    val totalAllowed = limit + bonus
    val percent = if (totalAllowed > 0) (count.toFloat() / totalAllowed).coerceIn(0f, 1f) else 0f
    val animatedProgress by animateFloatAsState(targetValue = percent, label = "progress")

    val isInstagram = platform == ScrollPlatform.INSTAGRAM

    val statusText = when {
        isBlocked -> "Limit reached"
        percent >= 0.85f -> "Near limit!"
        else -> if (isInstagram) "Reels today" else "Shorts today"
    }

    val statusColor = when {
        isBlocked -> PolishError
        percent >= 0.85f -> PolishError
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header with App Icon Mark & Platform Tag
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // White icon circle/tile
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isInstagram) {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(
                                            listOf(
                                                InstagramGradientStart,
                                                InstagramGradientMid,
                                                InstagramGradientEnd
                                            )
                                        )
                                    )
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(YouTubeRed)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isInstagram) "INSTAGRAM" else "YOUTUBE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            fontSize = 10.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Main Numbers
            Text(
                text = "$count/$totalAllowed",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.5).sp,
                    fontSize = 24.sp
                ),
                color = if (isBlocked) PolishError else MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (percent >= 0.85f || isBlocked) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 11.sp
                ),
                color = statusColor
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Progress bar
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (isBlocked || percent >= 0.85f) PolishError else PolishPurplePrimary,
                trackColor = Color.White.copy(alpha = 0.6f)
            )

            // Unlock Extra Button
            if (isBlocked || percent >= 0.8f) {
                Spacer(modifier = Modifier.height(10.dp))
                FilledTonalButton(
                    onClick = onUnlockBonusClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .testTag("unlock_10_bonus_${platform.name.lowercase()}"),
                    shape = RoundedCornerShape(10.dp),
                    colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color.White,
                        contentColor = PolishPurplePrimary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "+10 Bonus",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}

