package com.example.service

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AnticipateOvershootInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.data.model.ScrollPlatform
import kotlin.math.abs

/**
 * Dynamic Island / Pill Capsule HUD Overlay.
 *
 * Sits elegantly at the top-center of the screen (under notch/status bar) during Reels/Shorts playback.
 * Features:
 * - Dynamic Island Pill aesthetics (obsidian glass, adaptive gradient accent border)
 * - Live micro-bounce & glow pulse upon each reel transition
 * - Integrated 2dp bottom progress stripe and percentage badge
 * - Tap-to-expand interactive card with full breakdown (auto-collapses after 4s)
 * - Magnetic drag-and-snap gesture support
 */
class HudOverlayManager private constructor(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayRootView: FrameLayout? = null
    private var pillContainer: LinearLayout? = null
    private var expandedContainer: LinearLayout? = null
    private var progressStripeView: ProgressBarStripeView? = null

    private var platformIconText: TextView? = null
    private var counterText: TextView? = null
    private var percentBadgeText: TextView? = null

    // Expanded views
    private var expTitleText: TextView? = null
    private var expCounterText: TextView? = null
    private var expRemainingText: TextView? = null
    private var expProgressStripe: ProgressBarStripeView? = null

    private var isOverlayAttached = false
    private var currentPlatform: ScrollPlatform? = null
    private var lastDisplayedCount: Int = -1
    private var lastDisplayedLimit: Int = -1
    private var isExpanded: Boolean = false

    // Drag tracking
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var hasMoved = false

    private val collapseRunnable = Runnable {
        if (isExpanded) {
            collapseIsland()
        }
    }

    private val layoutParams: WindowManager.LayoutParams by lazy {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dpToPx(38) // Beautiful top margin right below front camera / notch
        }
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    fun showOrUpdateHud(platform: ScrollPlatform, count: Int, limit: Int) {
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Overlay permission not granted, cannot display HUD")
            return
        }

        val updateAction = Runnable {
            try {
                if (overlayRootView == null) {
                    overlayRootView = createDynamicIslandOverlay()
                }

                currentPlatform = platform
                updateIslandContent(platform, count, limit)

                if (!isOverlayAttached && overlayRootView != null) {
                    windowManager.addView(overlayRootView, layoutParams)
                    isOverlayAttached = true
                    Log.d(TAG, "Dynamic Island HUD attached for: ${platform.displayName}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error displaying Dynamic Island HUD: ${e.message}", e)
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            updateAction.run()
        } else {
            mainHandler.post(updateAction)
        }
    }

    fun hideHud() {
        mainHandler.post {
            try {
                mainHandler.removeCallbacks(collapseRunnable)
                if (isOverlayAttached && overlayRootView != null) {
                    windowManager.removeView(overlayRootView)
                    isOverlayAttached = false
                    currentPlatform = null
                    lastDisplayedCount = -1
                    lastDisplayedLimit = -1
                    isExpanded = false
                    Log.d(TAG, "Dynamic Island HUD removed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error hiding Dynamic Island HUD: ${e.message}", e)
                isOverlayAttached = false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun createDynamicIslandOverlay(): FrameLayout {
        val root = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            clipChildren = false
            clipToPadding = false
        }

        // --- 1. Main Collapsed Island Pill ---
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpToPx(14), dpToPx(7), dpToPx(14), dpToPx(7))

            background = createIslandBackground(0xFF7C4DFF.toInt())
            elevation = dpToPx(10).toFloat()
        }
        pillContainer = pill

        // Horizontal Row inside pill
        val innerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Platform icon emoji (🎬 / ▶️)
        val iconTv = TextView(context).apply {
            text = "🎬"
            textSize = 14f
            setPadding(0, 0, dpToPx(6), 0)
        }
        platformIconText = iconTv

        // Count Text: "14 / 50"
        val countTv = TextView(context).apply {
            text = "0 / 30"
            textSize = 13.5f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        counterText = countTv

        // Percentage Badge: "28%"
        val percentTv = TextView(context).apply {
            text = "0%"
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFF00E5FF.toInt())
            setPadding(dpToPx(8), dpToPx(2), dpToPx(8), dpToPx(2))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(12).toFloat()
                setColor(0x3300E5FF.toInt())
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dpToPx(8)
            }
            layoutParams = lp
        }
        percentBadgeText = percentTv

        innerRow.addView(iconTv)
        innerRow.addView(countTv)
        innerRow.addView(percentTv)
        pill.addView(innerRow)

        // Micro Progress Stripe at the bottom of the pill
        val stripe = ProgressBarStripeView(context).apply {
            val lp = LinearLayout.LayoutParams(dpToPx(90), dpToPx(3)).apply {
                topMargin = dpToPx(5)
            }
            layoutParams = lp
        }
        progressStripeView = stripe
        pill.addView(stripe)

        // --- 2. Expanded Detail Card (Hidden by default) ---
        val expandedCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dpToPx(18), dpToPx(14), dpToPx(18), dpToPx(14))
            background = createIslandBackground(0xFF7C4DFF.toInt())
            elevation = dpToPx(14).toFloat()

            val lp = FrameLayout.LayoutParams(
                dpToPx(240),
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = lp
        }
        expandedContainer = expandedCard

        // Expanded Header: Title + Collapse hint
        val expHeaderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            weightSum = 1f
        }
        val expTitle = TextView(context).apply {
            text = "🎬 Instagram Reels"
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFD1C4E9.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        expTitleText = expTitle

        val expClose = TextView(context).apply {
            text = "✕"
            textSize = 12f
            setTextColor(0xAAFFFFFF.toInt())
            setPadding(dpToPx(4), dpToPx(2), dpToPx(4), dpToPx(2))
        }
        expHeaderRow.addView(expTitle)
        expHeaderRow.addView(expClose)
        expandedCard.addView(expHeaderRow)

        // Expanded Big Counter
        val expCount = TextView(context).apply {
            text = "14 / 50 Videos"
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, dpToPx(8), 0, 0)
        }
        expCounterText = expCount
        expandedCard.addView(expCount)

        // Expanded Remaining subtitle
        val expRemaining = TextView(context).apply {
            text = "36 videos remaining today"
            textSize = 12f
            setTextColor(0xCCB0BEC5.toInt())
            setPadding(0, dpToPx(2), 0, dpToPx(8))
        }
        expRemainingText = expRemaining
        expandedCard.addView(expRemaining)

        // Expanded Wide Progress Stripe
        val expStripe = ProgressBarStripeView(context).apply {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(6)
            ).apply {
                topMargin = dpToPx(4)
            }
            layoutParams = lp
        }
        expProgressStripe = expStripe
        expandedCard.addView(expStripe)

        root.addView(pill)
        root.addView(expandedCard)

        // Touch & Drag Handling on Root
        setupTouchAndDrag(root)

        return root
    }

    private fun setupTouchAndDrag(root: FrameLayout) {
        root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    hasMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()

                    if (abs(deltaX) > 12 || abs(deltaY) > 12 || isDragging) {
                        isDragging = true
                        hasMoved = true
                        layoutParams.x = initialX + deltaX
                        layoutParams.y = (initialY + deltaY).coerceAtLeast(dpToPx(20))
                        if (isOverlayAttached && overlayRootView != null) {
                            windowManager.updateViewLayout(overlayRootView, layoutParams)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        // Magnetic Snap
                        val displayMetrics = context.resources.displayMetrics
                        val screenWidth = displayMetrics.widthPixels
                        val viewWidth = overlayRootView?.width ?: dpToPx(160)

                        // Snap to center if dragged near center, else snap to left/right
                        val centerX = (screenWidth - viewWidth) / 2
                        val currentLeft = layoutParams.x

                        if (abs(currentLeft - centerX) < screenWidth * 0.22f) {
                            // Snap to center
                            layoutParams.x = 0
                            layoutParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        } else if (currentLeft + viewWidth / 2 < screenWidth / 2) {
                            // Snap to left edge
                            layoutParams.gravity = Gravity.TOP or Gravity.START
                            layoutParams.x = dpToPx(16)
                        } else {
                            // Snap to right edge
                            layoutParams.gravity = Gravity.TOP or Gravity.START
                            layoutParams.x = screenWidth - viewWidth - dpToPx(16)
                        }

                        if (isOverlayAttached && overlayRootView != null) {
                            windowManager.updateViewLayout(overlayRootView, layoutParams)
                        }
                    } else if (!hasMoved) {
                        // Clean Tap -> Toggle Expand / Collapse
                        toggleExpandCollapse()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleExpandCollapse() {
        if (isExpanded) {
            collapseIsland()
        } else {
            expandIsland()
        }
    }

    private fun expandIsland() {
        isExpanded = true
        mainHandler.removeCallbacks(collapseRunnable)

        pillContainer?.visibility = View.GONE
        expandedContainer?.apply {
            visibility = View.VISIBLE
            alpha = 0f
            scaleX = 0.85f
            scaleY = 0.85f
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(220)
                .setInterpolator(OvershootInterpolator(1.2f))
                .start()
        }

        if (isOverlayAttached && overlayRootView != null) {
            windowManager.updateViewLayout(overlayRootView, layoutParams)
        }

        // Auto-collapse after 4 seconds of inactivity
        mainHandler.postDelayed(collapseRunnable, 4000)
    }

    private fun collapseIsland() {
        isExpanded = false
        mainHandler.removeCallbacks(collapseRunnable)

        expandedContainer?.animate()
            ?.alpha(0f)
            ?.scaleX(0.85f)
            ?.scaleY(0.85f)
            ?.setDuration(160)
            ?.setInterpolator(AnticipateOvershootInterpolator(1.0f))
            ?.withEndAction {
                expandedContainer?.visibility = View.GONE
                pillContainer?.apply {
                    visibility = View.VISIBLE
                    alpha = 0f
                    scaleX = 0.9f
                    scaleY = 0.9f
                    animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(180)
                        .setInterpolator(OvershootInterpolator(1.1f))
                        .start()
                }
                if (isOverlayAttached && overlayRootView != null) {
                    windowManager.updateViewLayout(overlayRootView, layoutParams)
                }
            }
            ?.start()
    }

    @SuppressLint("SetTextI18n")
    private fun updateIslandContent(platform: ScrollPlatform, count: Int, limit: Int) {
        val icon = platform.iconEmoji
        val platformName = platform.displayName

        val countChanged = (count != lastDisplayedCount && lastDisplayedCount != -1)
        lastDisplayedCount = count
        lastDisplayedLimit = limit

        val percent = if (limit > 0) ((count.toFloat() / limit.toFloat()) * 100).toInt() else 0
        val clampedPercent = percent.coerceIn(0, 100)

        // Accent & Glow Colors based on usage and platform
        val (accentColor, badgeBgColor) = when {
            percent >= 100 -> Pair(0xFFFF3B30.toInt(), 0x44FF3B30.toInt()) // Crimson Red
            percent >= 80 -> Pair(0xFFFFB300.toInt(), 0x44FFB300.toInt())  // Warning Amber
            platform == ScrollPlatform.FACEBOOK -> Pair(0xFF1877F2.toInt(), 0x331877F2.toInt()) // Facebook Blue
            platform == ScrollPlatform.SNAPCHAT -> Pair(0xFFFFFC00.toInt(), 0x33FFFC00.toInt()) // Snapchat Yellow
            platform == ScrollPlatform.YOUTUBE -> Pair(0xFFFF0000.toInt(), 0x33FF0000.toInt())  // YouTube Red
            else -> Pair(0xFF00E5FF.toInt(), 0x3300E5FF.toInt())           // Electric Cyan / Violet
        }

        // Update Collapsed Pill Elements
        platformIconText?.text = icon
        counterText?.text = "$count / $limit"
        percentBadgeText?.apply {
            text = "$percent%"
            setTextColor(if (platform == ScrollPlatform.SNAPCHAT && percent < 80) Color.WHITE else accentColor)
            (background as? android.graphics.drawable.GradientDrawable)?.setColor(badgeBgColor)
        }

        progressStripeView?.setProgress(clampedPercent, accentColor)

        // Update Dynamic Island Pill Border Color
        pillContainer?.background = createIslandBackground(accentColor)
        expandedContainer?.background = createIslandBackground(accentColor)

        // Update Expanded Card Elements
        expTitleText?.text = "$icon $platformName"
        expCounterText?.text = "$count / $limit Videos"
        val remaining = (limit - count).coerceAtLeast(0)
        expRemainingText?.text = if (remaining > 0) "$remaining videos remaining today" else "Daily limit completed!"
        expProgressStripe?.setProgress(clampedPercent, accentColor)

        // Dynamic Island Micro-Pulse & Spring Bounce Animation on live scroll count!
        if (countChanged) {
            triggerMicroBounceAnimation()
        }

        overlayRootView?.requestLayout()
        overlayRootView?.invalidate()
    }


    /**
     * Executes a spring bounce on the Dynamic Island pill when a new video is counted.
     */
    private fun triggerMicroBounceAnimation() {
        val targetView = if (isExpanded) expandedContainer else pillContainer
        targetView?.let { view ->
            val scaleXAnim = ObjectAnimator.ofFloat(view, "scaleX", 1.0f, 1.15f, 0.96f, 1.0f).apply {
                duration = 260
                interpolator = OvershootInterpolator(2.2f)
            }
            val scaleYAnim = ObjectAnimator.ofFloat(view, "scaleY", 1.0f, 1.15f, 0.96f, 1.0f).apply {
                duration = 260
                interpolator = OvershootInterpolator(2.2f)
            }
            AnimatorSet().apply {
                playTogether(scaleXAnim, scaleYAnim)
                start()
            }
        }
    }

    private fun createIslandBackground(accentStrokeColor: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(28).toFloat()
            // Deep translucent obsidian black glass
            setColor(0xF00F0E17.toInt())
            // Refined glowing border stroke
            setStroke(dpToPx(1), (accentStrokeColor and 0x00FFFFFF) or 0x66000000)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val TAG = "HudOverlayManager"

        @Volatile
        private var INSTANCE: HudOverlayManager? = null

        fun getInstance(context: Context): HudOverlayManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: HudOverlayManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    /**
     * Custom lightweight canvas view for rendering the smooth gradient progress stripe.
     */
    private class ProgressBarStripeView(context: Context) : View(context) {
        private var progressPercent: Int = 0
        private var strokeColor: Int = 0xFF00E5FF.toInt()

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x33FFFFFF.toInt()
            style = Paint.Style.FILL
        }

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val rectF = RectF()

        fun setProgress(percent: Int, color: Int) {
            this.progressPercent = percent.coerceIn(0, 100)
            this.strokeColor = color
            fillPaint.color = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val radius = h / 2f

            // Draw track background
            rectF.set(0f, 0f, w, h)
            canvas.drawRoundRect(rectF, radius, radius, bgPaint)

            // Draw filled progress
            if (progressPercent > 0) {
                val fillW = (w * (progressPercent / 100f)).coerceAtLeast(radius * 2f).coerceAtMost(w)
                rectF.set(0f, 0f, fillW, h)
                canvas.drawRoundRect(rectF, radius, radius, fillPaint)
            }
        }
    }
}
