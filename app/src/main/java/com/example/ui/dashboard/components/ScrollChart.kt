package com.example.ui.dashboard.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DailyScrollRecord
import com.example.ui.theme.InstagramGradientMid
import com.example.ui.theme.PolishPurpleContainer
import com.example.ui.theme.PolishPurplePrimary
import com.example.ui.theme.YouTubeRed
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.max

@Composable
fun ScrollHistoryChart(
    records: List<DailyScrollRecord>,
    dailyAverage: Float,
    modifier: Modifier = Modifier
) {
    val animationProgress = remember { Animatable(0f) }

    LaunchedEffect(records) {
        animationProgress.snapTo(0f)
        animationProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 800)
        )
    }

    val inDateFormatter = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    val outDateFormatter = remember { SimpleDateFormat("EEE", Locale.US) }

    // Find max scroll count across all 7 days
    val maxScroll = remember(records) {
        val highest = records.maxOfOrNull { it.totalScrollsToday } ?: 40
        max(highest, 30).toFloat() * 1.15f
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Weekly Activity",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.2).sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Last 7 days • Avg ${"%.1f".format(dailyAverage)} scrolls/day",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Legend Pills
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(InstagramGradientMid)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Reels",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(YouTubeRed)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Shorts",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Chart Canvas with Professional Polish Pill Bars
            val textColor = MaterialTheme.colorScheme.onSurfaceVariant
            val textHighlightColor = MaterialTheme.colorScheme.primary
            val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            val trackColor = PolishPurpleContainer.copy(alpha = 0.5f)

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
            ) {
                val width = size.width
                val height = size.height
                val bottomLabelHeight = 36f
                val chartHeight = height - bottomLabelHeight
                val barCornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())

                // Draw background horizontal guide lines
                val numGridLines = 3
                for (i in 0..numGridLines) {
                    val y = chartHeight * (1f - (i.toFloat() / numGridLines))
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(width, y),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                if (records.isEmpty()) return@Canvas

                val count = records.size
                val slotWidth = width / count
                val barGroupWidth = slotWidth * 0.52f
                val individualBarWidth = barGroupWidth / 2f

                records.forEachIndexed { index, record ->
                    val slotCenterX = slotWidth * index + (slotWidth / 2f)
                    val groupStartX = slotCenterX - (barGroupWidth / 2f)
                    val isToday = index == records.size - 1

                    // Draw track background for the group
                    drawRoundRect(
                        color = trackColor,
                        topLeft = Offset(groupStartX, 0f),
                        size = Size(barGroupWidth, chartHeight),
                        cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                    )

                    val igHeight = ((record.instagramCount / maxScroll) * chartHeight * animationProgress.value).coerceAtLeast(0f)
                    val ytHeight = ((record.youtubeCount / maxScroll) * chartHeight * animationProgress.value).coerceAtLeast(0f)

                    // Instagram Bar Fill
                    val igTop = chartHeight - igHeight
                    if (igHeight > 0f) {
                        drawRoundRect(
                            color = InstagramGradientMid,
                            topLeft = Offset(groupStartX + 1.dp.toPx(), igTop),
                            size = Size(individualBarWidth - 2.dp.toPx(), igHeight),
                            cornerRadius = barCornerRadius
                        )
                    }

                    // YouTube Bar Fill
                    val ytTop = chartHeight - ytHeight
                    if (ytHeight > 0f) {
                        drawRoundRect(
                            color = YouTubeRed,
                            topLeft = Offset(groupStartX + individualBarWidth + 1.dp.toPx(), ytTop),
                            size = Size(individualBarWidth - 2.dp.toPx(), ytHeight),
                            cornerRadius = barCornerRadius
                        )
                    }

                    // Draw Day of week label
                    val dayLabel = try {
                        val parsed = inDateFormatter.parse(record.dateString)
                        if (parsed != null) outDateFormatter.format(parsed) else record.dateString.takeLast(2)
                    } catch (e: Exception) {
                        record.dateString.takeLast(2)
                    }

                    drawContext.canvas.nativeCanvas.apply {
                        val paint = android.graphics.Paint().apply {
                            this.color = if (isToday) textHighlightColor.hashCode() else textColor.hashCode()
                            this.textSize = if (isToday) 11.sp.toPx() else 10.sp.toPx()
                            this.isFakeBoldText = isToday
                            this.textAlign = android.graphics.Paint.Align.CENTER
                            this.isAntiAlias = true
                        }
                        drawText(
                            dayLabel,
                            slotCenterX,
                            height - 8f,
                            paint
                        )
                    }
                }
            }
        }
    }
}

