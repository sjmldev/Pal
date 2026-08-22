package com.example.ui.limits

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ads.AdManager
import com.example.ads.StartAppBanner
import com.example.data.preferences.AppPreferences
import com.example.data.repository.ScrollRepository
import com.example.ui.theme.FacebookBlue
import com.example.ui.theme.InstagramGradientMid
import com.example.ui.theme.PolishOnPurpleContainer
import com.example.ui.theme.PolishPurpleContainer
import com.example.ui.theme.PolishPurplePrimary
import com.example.ui.theme.PolishStreakGold
import com.example.ui.theme.SnapchatYellow
import com.example.ui.theme.YouTubeRed
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LimitSetupScreen(
    onNavigateBack: () -> Unit,
    onLimitsSaved: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val repository = remember { ScrollRepository.getInstance(context) }
    val preferences = remember { AppPreferences.getInstance(context) }

    val todayRecord by repository.getTodayRecordFlow().collectAsStateWithLifecycle(initialValue = null)

    var instagramLimit by remember(todayRecord) {
        mutableIntStateOf(todayRecord?.instagramLimit ?: 30)
    }
    var youtubeLimit by remember(todayRecord) {
        mutableIntStateOf(todayRecord?.youtubeLimit ?: 25)
    }
    var facebookLimit by remember(todayRecord) {
        mutableIntStateOf(todayRecord?.facebookLimit ?: 25)
    }
    var snapchatLimit by remember(todayRecord) {
        mutableIntStateOf(todayRecord?.snapchatLimit ?: 30)
    }
    var isLoadingAd by remember { mutableStateOf(false) }

    val isLockedToday = todayRecord?.limitSetToday == true

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("limit_setup_scaffold"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Daily Scroll Limits",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.2).sp
                        )
                    )
                },
                navigationIcon = {
                    if (preferences.hasCompletedOnboarding) {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.testTag("limit_setup_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
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

                if (isLockedToday) {
                    // Lock Rule Banner
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = PolishStreakGold.copy(alpha = 0.12f)
                        ),
                        border = BorderStroke(1.dp, PolishStreakGold.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Locked",
                                tint = PolishStreakGold,
                                modifier = Modifier.size(26.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Today's Limit is Locked",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "To protect your focus, limits cannot be changed before 12:00 AM midnight. You can set new limits tomorrow!",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    // Header card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = PolishPurpleContainer.copy(alpha = 0.55f)
                        ),
                        border = BorderStroke(1.dp, PolishPurplePrimary.copy(alpha = 0.25f))
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(PolishPurplePrimary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Custom Scroll Limits (No Cap)",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = PolishOnPurpleContainer
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Set any daily limit you want (10, 50, 500, 1000+). You can type any custom number or use quick presets.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Instagram Reels Limit Card
            item {
                PlatformLimitCard(
                    platformName = "Instagram Reels",
                    accentColor = InstagramGradientMid,
                    currentLimit = instagramLimit,
                    isEnabled = !isLockedToday,
                    onLimitChanged = { instagramLimit = it }
                )
            }

            // YouTube Shorts Limit Card
            item {
                PlatformLimitCard(
                    platformName = "YouTube Shorts",
                    accentColor = YouTubeRed,
                    currentLimit = youtubeLimit,
                    isEnabled = !isLockedToday,
                    onLimitChanged = { youtubeLimit = it }
                )
            }

            // Facebook Reels Limit Card
            item {
                PlatformLimitCard(
                    platformName = "Facebook Reels",
                    accentColor = FacebookBlue,
                    currentLimit = facebookLimit,
                    isEnabled = !isLockedToday,
                    onLimitChanged = { facebookLimit = it }
                )
            }

            // Snapchat Spotlight Limit Card
            item {
                PlatformLimitCard(
                    platformName = "Snapchat Spotlight",
                    accentColor = SnapchatYellow,
                    currentLimit = snapchatLimit,
                    isEnabled = !isLockedToday,
                    onLimitChanged = { snapchatLimit = it }
                )
            }

            // Ad-gating & Lock notice
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayCircleOutline,
                            contentDescription = null,
                            tint = PolishPurplePrimary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Watching a quick sponsor ad is required to confirm and lock in today's limits across all platforms.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Confirm Button (Ad-gated)
            item {
                Spacer(modifier = Modifier.height(2.dp))
                Button(
                    onClick = {
                        val activity = context as? Activity ?: return@Button
                        isLoadingAd = true
                        AdManager.showInterstitialAd(activity) { success ->
                            coroutineScope.launch {
                                repository.setTodayLimits(
                                    instagramLimit = instagramLimit,
                                    youtubeLimit = youtubeLimit,
                                    facebookLimit = facebookLimit,
                                    snapchatLimit = snapchatLimit
                                )
                                preferences.hasCompletedOnboarding = true
                                isLoadingAd = false
                                Toast.makeText(
                                    context,
                                    "🔒 Daily limits saved and locked for today!",
                                    Toast.LENGTH_SHORT
                                ).show()
                                onLimitsSaved()
                            }
                        }
                    },
                    enabled = !isLockedToday && !isLoadingAd,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("confirm_limits_button"),
                    shape = RoundedCornerShape(26.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PolishPurplePrimary,
                        contentColor = Color.White
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                ) {
                    Text(
                        text = when {
                            isLoadingAd -> "Loading Sponsor Ad..."
                            isLockedToday -> "Limits Locked Until 12:00 AM"
                            else -> "Watch Ad & Lock Today's Limits"
                        },
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }

                if (isLockedToday && preferences.hasCompletedOnboarding) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onNavigateBack,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("return_to_dashboard_button"),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text(
                            text = "Return to Dashboard",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

@Composable
fun PlatformLimitCard(
    platformName: String,
    accentColor: androidx.compose.ui.graphics.Color,
    currentLimit: Int,
    isEnabled: Boolean,
    onLimitChanged: (Int) -> Unit
) {
    var textInput by remember(currentLimit) {
        mutableStateOf(currentLimit.toString())
    }

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
            // Header Row: Platform Name + Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = platformName,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = PolishPurpleContainer.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AllInclusive,
                            contentDescription = null,
                            tint = PolishPurplePrimary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "No Max Limit",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = PolishPurplePrimary
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Direct Number Input & Big Display Box
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Enter Any Custom Limit:",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Type any exact number of videos",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                OutlinedTextField(
                    value = textInput,
                    onValueChange = { input ->
                        val filtered = input.filter { it.isDigit() }
                        textInput = filtered
                        val parsed = filtered.toIntOrNull()
                        if (parsed != null && parsed >= 1) {
                            onLimitChanged(parsed)
                        }
                    },
                    modifier = Modifier
                        .width(130.dp)
                        .height(56.dp)
                        .testTag("custom_limit_input_${platformName.lowercase().replace(" ", "_")}"),
                    enabled = isEnabled,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = PolishPurplePrimary
                    ),
                    suffix = {
                        Text(
                            text = "vids",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PolishPurplePrimary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                    )
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Quick Preset Chips (10, 25, 50, 100, 250, 500, 1000)
            Text(
                text = "Quick Presets:",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            val presets = listOf(15, 30, 50, 100, 200, 500, 1000)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                presets.forEach { presetValue ->
                    val isSelected = (currentLimit == presetValue)
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            if (isEnabled) {
                                onLimitChanged(presetValue)
                                textInput = presetValue.toString()
                            }
                        },
                        label = {
                            Text(
                                text = "$presetValue",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            )
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PolishPurplePrimary,
                            selectedLabelColor = Color.White,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = isEnabled,
                            selected = isSelected,
                            borderColor = if (isSelected) PolishPurplePrimary else MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Adaptive Slider
            val sliderMax = maxOf(100f, (currentLimit * 1.35f).coerceAtLeast(150f))
            Slider(
                value = currentLimit.toFloat().coerceIn(1f, sliderMax),
                onValueChange = {
                    val intVal = it.toInt().coerceAtLeast(1)
                    onLimitChanged(intVal)
                    textInput = intVal.toString()
                },
                valueRange = 1f..sliderMax,
                enabled = isEnabled,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = PolishPurplePrimary,
                    activeTrackColor = PolishPurplePrimary,
                    inactiveTrackColor = PolishPurpleContainer
                )
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Quick Steppers (-50, -10, -1, +1, +10, +50, +100)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Quick Adjust:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    FilledIconButton(
                        onClick = {
                            val newVal = (currentLimit - 50).coerceAtLeast(1)
                            onLimitChanged(newVal)
                            textInput = newVal.toString()
                        },
                        enabled = isEnabled && currentLimit > 50,
                        modifier = Modifier.size(34.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text("-50", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp))
                    }

                    FilledIconButton(
                        onClick = {
                            val newVal = (currentLimit - 10).coerceAtLeast(1)
                            onLimitChanged(newVal)
                            textInput = newVal.toString()
                        },
                        enabled = isEnabled && currentLimit > 10,
                        modifier = Modifier.size(34.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text("-10", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp))
                    }

                    FilledIconButton(
                        onClick = {
                            val newVal = (currentLimit - 1).coerceAtLeast(1)
                            onLimitChanged(newVal)
                            textInput = newVal.toString()
                        },
                        enabled = isEnabled && currentLimit > 1,
                        modifier = Modifier.size(34.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Decrease 1", modifier = Modifier.size(14.dp))
                    }

                    FilledIconButton(
                        onClick = {
                            val newVal = currentLimit + 1
                            onLimitChanged(newVal)
                            textInput = newVal.toString()
                        },
                        enabled = isEnabled,
                        modifier = Modifier.size(34.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Increase 1", modifier = Modifier.size(14.dp))
                    }

                    FilledIconButton(
                        onClick = {
                            val newVal = currentLimit + 10
                            onLimitChanged(newVal)
                            textInput = newVal.toString()
                        },
                        enabled = isEnabled,
                        modifier = Modifier.size(34.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text("+10", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp))
                    }

                    FilledIconButton(
                        onClick = {
                            val newVal = currentLimit + 50
                            onLimitChanged(newVal)
                            textInput = newVal.toString()
                        },
                        enabled = isEnabled,
                        modifier = Modifier.size(34.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text("+50", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp))
                    }

                    FilledIconButton(
                        onClick = {
                            val newVal = currentLimit + 100
                            onLimitChanged(newVal)
                            textInput = newVal.toString()
                        },
                        enabled = isEnabled,
                        modifier = Modifier.size(34.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text("+100", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 9.sp))
                    }
                }
            }
        }
    }
}

