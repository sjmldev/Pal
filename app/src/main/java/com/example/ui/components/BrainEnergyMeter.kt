package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DailyScrollRecord

@Composable
fun BrainEnergyMeter(
    record: DailyScrollRecord,
    modifier: Modifier = Modifier
) {
    val totalScrolls = record.totalScrollsToday
    val totalAllowed = record.totalAllowedToday.coerceAtLeast(1)

    // Calculate energy % remaining (100% down to 0%)
    val remainingScrolls = (totalAllowed - totalScrolls).coerceAtLeast(0)
    val rawEnergyFraction = remainingScrolls.toFloat() / totalAllowed.toFloat()
    val energyPercentage = (rawEnergyFraction * 100f).coerceIn(0f, 100f)

    val animatedEnergyFraction by animateFloatAsState(
        targetValue = rawEnergyFraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "brain_energy_fraction"
    )

    // Stage theme
    val (primaryGlow, secondaryGlow, stageTitle, stageSubtitle, stageEmoji) = when {
        energyPercentage >= 70f -> BrainStateTheme(
            primaryColor = Color(0xFF00E676),
            secondaryColor = Color(0xFF00E5FF),
            title = "Sharp & Focused",
            subtitle = "Cognitive bandwidth is fresh. Attention span is intact!",
            emoji = "🧠"
        )
        energyPercentage >= 30f -> BrainStateTheme(
            primaryColor = Color(0xFFFFB300),
            secondaryColor = Color(0xFFFF9100),
            title = "Dopamine Fatigue",
            subtitle = "Working memory depleting. Consider taking a 15-min walk.",
            emoji = "⚡"
        )
        else -> BrainStateTheme(
            primaryColor = Color(0xFFFF1744),
            secondaryColor = Color(0xFFFF5252),
            title = "Brain Drain Warning",
            subtitle = "Daily limits nearly exhausted. Time to unplug and recharge!",
            emoji = "🚨"
        )
    }

    val animatedPrimaryColor by animateColorAsState(
        targetValue = primaryGlow,
        animationSpec = tween(600),
        label = "primary_color_anim"
    )

    // Infinite breathing pulse for brain vitality
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("brain_energy_meter_card"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.5.dp, animatedPrimaryColor.copy(alpha = 0.35f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            // Header Row: Brain Avatar + Stage Title + Percentage Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Pulsing Brain Avatar
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        animatedPrimaryColor.copy(alpha = 0.35f),
                                        animatedPrimaryColor.copy(alpha = 0.08f)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stageEmoji,
                            fontSize = 24.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column {
                        Text(
                            text = "Brain Energy Meter",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stageTitle,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Energy Percentage Pill
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = animatedPrimaryColor.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, animatedPrimaryColor.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = "${energyPercentage.toInt()}%",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = animatedPrimaryColor,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Subtitle Guidance
            Text(
                text = stageSubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Smooth Multi-Gradient Progress Track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedEnergyFraction)
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    animatedPrimaryColor,
                                    secondaryGlow
                                )
                            )
                        )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Summary Stats Footer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Total Scrolls: $totalScrolls / $totalAllowed",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (remainingScrolls > 0) "$remainingScrolls remaining" else "Daily cap reached!",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (remainingScrolls > 0) animatedPrimaryColor else Color(0xFFFF1744)
                )
            }
        }
    }
}

private data class BrainStateTheme(
    val primaryColor: Color,
    val secondaryColor: Color,
    val title: String,
    val subtitle: String,
    val emoji: String
)
