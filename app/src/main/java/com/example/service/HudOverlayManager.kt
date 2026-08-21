package com.example.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import com.example.data.model.ScrollPlatform
import kotlin.math.abs

class HudOverlayManager private constructor(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayView: View? = null
    private var isOverlayAttached = false
    private var currentPlatform: ScrollPlatform? = null

    // Track touch positions for dragging
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

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
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 200
        }
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    fun showOrUpdateHud(platform: ScrollPlatform, count: Int, limit: Int) {
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Overlay permission not granted, cannot display HUD")
            return
        }

        mainHandler.post {
            try {
                if (overlayView == null) {
                    overlayView = createOverlayView()
                }

                currentPlatform = platform
                updateViewContent(platform, count, limit)

                if (!isOverlayAttached && overlayView != null) {
                    windowManager.addView(overlayView, layoutParams)
                    isOverlayAttached = true
                    Log.d(TAG, "HUD attached for platform: ${platform.displayName}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error displaying HUD overlay: ${e.message}", e)
            }
        }
    }

    fun hideHud() {
        mainHandler.post {
            try {
                if (isOverlayAttached && overlayView != null) {
                    windowManager.removeView(overlayView)
                    isOverlayAttached = false
                    currentPlatform = null
                    Log.d(TAG, "HUD overlay removed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error hiding HUD overlay: ${e.message}", e)
                isOverlayAttached = false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun createOverlayView(): View {
        val rootLayout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
            gravity = Gravity.CENTER_VERTICAL

            // Rounded gradient pill background
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(24).toFloat()
                setColor(0xEE1E1B2E.toInt()) // Deep translucent dark
                setStroke(dpToPx(1), 0x557C4DFF.toInt()) // Subtle purple border
            }
            elevation = dpToPx(8).toFloat()
        }

        // Platform icon/dot indicator
        val dotView = View(context).apply {
            id = View.generateViewId()
            val dotSize = dpToPx(10)
            layoutParams = android.widget.LinearLayout.LayoutParams(dotSize, dotSize).apply {
                marginEnd = dpToPx(8)
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFF00E5FF.toInt())
            }
        }

        // Text label: "📸 14 / 30"
        val textView = TextView(context).apply {
            id = View.generateViewId()
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            text = "Reels: 0 / 30"
        }

        rootLayout.addView(dotView)
        rootLayout.addView(textView)

        // Drag gesture listener
        rootLayout.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()

                    if (abs(deltaX) > 10 || abs(deltaY) > 10 || isDragging) {
                        isDragging = true
                        layoutParams.x = initialX + deltaX
                        layoutParams.y = initialY + deltaY
                        if (isOverlayAttached && overlayView != null) {
                            windowManager.updateViewLayout(overlayView, layoutParams)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        // Snap to nearest screen edge
                        val displayMetrics = context.resources.displayMetrics
                        val screenWidth = displayMetrics.widthPixels
                        layoutParams.x = if (layoutParams.x + view.width / 2 < screenWidth / 2) {
                            dpToPx(16)
                        } else {
                            screenWidth - view.width - dpToPx(16)
                        }
                        if (isOverlayAttached && overlayView != null) {
                            windowManager.updateViewLayout(overlayView, layoutParams)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        return rootLayout
    }

    @SuppressLint("SetTextI18n")
    private fun updateViewContent(platform: ScrollPlatform, count: Int, limit: Int) {
        val root = overlayView as? android.widget.LinearLayout ?: return
        val dot = root.getChildAt(0)
        val text = root.getChildAt(1) as? TextView ?: return

        val icon = if (platform == ScrollPlatform.INSTAGRAM) "📸" else "▶️"
        val name = if (platform == ScrollPlatform.INSTAGRAM) "Reels" else "Shorts"

        text.text = "$icon $name: $count / $limit"

        // Update dot color based on remaining limit
        val percent = if (limit > 0) (count.toFloat() / limit) else 0f
        val dotColor = when {
            percent >= 1.0f -> 0xFFFF5252.toInt() // Red (blocked/reached)
            percent >= 0.8f -> 0xFFFFD740.toInt() // Yellow (warning)
            else -> 0xFF00E5FF.toInt()           // Cyan (safe)
        }

        (dot.background as? android.graphics.drawable.GradientDrawable)?.setColor(dotColor)
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
}
