package com.uicapture.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.uicapture.app.model.ScreenCaptureData
import com.uicapture.app.service.UICaptureAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Pure mathematical, geometric, and text formatting helpers for the floating overlay.
 * Decoupled from Android runtime components to enable deterministic unit testing.
 */
object OverlayMath {

    /**
     * Determines whether user touch movement delta exceeds the touch slop threshold,
     * differentiating a click/tap gesture from a drag gesture.
     *
     * @param startX Starting touch down X coordinate.
     * @param startY Starting touch down Y coordinate.
     * @param currentX Current touch move X coordinate.
     * @param currentY Current touch move Y coordinate.
     * @param touchSlop Minimum pixel movement threshold to qualify as a drag.
     * @return True if distance exceeds touchSlop, false otherwise.
     */
    fun isDragGesture(startX: Float, startY: Float, currentX: Float, currentY: Float, touchSlop: Float): Boolean {
        val dx = (currentX - startX).toDouble()
        val dy = (currentY - startY).toDouble()
        return hypot(dx, dy) > touchSlop
    }

    /**
     * Computes the snap-to-edge target X coordinate.
     * If the horizontal center of the bubble is in the left half of the screen, snaps to [margin].
     * Otherwise, snaps to [screenWidth] - [viewWidth] - [margin].
     *
     * @param currentX Current X position of the view.
     * @param viewWidth Measured or estimated width of the overlay view.
     * @param screenWidth Total display width in pixels.
     * @param margin Padding from the edge of the screen in pixels.
     * @return The target snap X coordinate.
     */
    fun calculateSnapTargetX(currentX: Int, viewWidth: Int, screenWidth: Int, margin: Int = 16): Int {
        if (screenWidth <= 0) return margin
        val safeMargin = margin.coerceAtLeast(0)
        val centerX = currentX + viewWidth / 2
        val screenCenterX = screenWidth / 2
        return if (centerX < screenCenterX) {
            safeMargin
        } else {
            (screenWidth - viewWidth - safeMargin).coerceAtLeast(safeMargin)
        }
    }

    /**
     * Clamps the Y coordinate within safe vertical screen bounds, ensuring the overlay bubble
     * remains accessible and doesn't get pushed off-screen behind status bar or navigation bars.
     *
     * @param currentY Current Y position of the view.
     * @param viewHeight Measured or estimated height of the overlay view.
     * @param screenHeight Total display height in pixels.
     * @param topMargin Safe top margin (e.g. status bar offset).
     * @param bottomMargin Safe bottom margin (e.g. navigation bar offset).
     * @return Clamped Y coordinate.
     */
    fun clampY(currentY: Int, viewHeight: Int, screenHeight: Int, topMargin: Int = 0, bottomMargin: Int = 0): Int {
        if (screenHeight <= 0) return topMargin
        val minY = topMargin.coerceAtLeast(0)
        val maxY = (screenHeight - viewHeight - bottomMargin).coerceAtLeast(minY)
        return currentY.coerceIn(minY, maxY)
    }

    /**
     * Formats the capture count badge text (`#1`, `#2`, ...).
     */
    fun formatBadgeText(counter: Int): String {
        return "#$counter"
    }

    /**
     * Formats the user-facing capture summary Toast message.
     */
    fun formatSuccessToast(elementCount: Int, packageName: String): String {
        return "Captured $elementCount elements for $packageName"
    }
}

/**
 * Controller managing the draggable floating bubble overlay window.
 *
 * Provides:
 * - WindowManager overlay attachment using [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY]
 *   and flags [WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE] | [WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN].
 * - Programmatic floating bubble UI with camera icon and capture counter badge (`#1`, `#2`, ...).
 * - Touch drag listener supporting smooth screen repositioning, touch threshold (discriminating click vs drag),
 *   and snap-to-edge calculation.
 * - Single-tap capture trigger coordinating with [UICaptureAccessibilityService], haptic feedback,
 *   visual pulse animation, counter increment, and Toast feedback.
 * - Clean lifecycle management: [show], [hide], [destroy], and [resetCounter].
 */
class FloatingOverlayController(
    val context: Context? = null,
    val initialX: Int = 16,
    val initialY: Int = 300
) {

    // Pluggable dependencies with real production defaults for testability
    internal var windowManager: WindowManager? = try {
        context?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    } catch (_: Throwable) {
        null
    }

    internal var screenSizeProvider: () -> Pair<Int, Int> = {
        context?.let { getDefaultScreenMetrics(it) } ?: Pair(1080, 2400)
    }

    internal var captureTrigger: (onSuccess: (ScreenCaptureData, Bitmap?) -> Unit, onError: (String) -> Unit) -> Unit =
        { onSuccess, onError ->
            UICaptureAccessibilityService.captureActiveScreen(onSuccess, onError)
        }

    internal var toastDisplayer: (String) -> Unit = { message ->
        mainDispatcher {
            try {
                if (context != null) {
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            } catch (_: Throwable) {
                // Safe ignore in unit test environments
            }
        }
    }

    internal var hapticFeedbackProvider: () -> Unit = {
        try {
            rootView?.performHapticFeedback(
                HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        } catch (_: Throwable) {
            // Safe ignore
        }
    }

    internal var pulseAnimator: (View) -> Unit = { view ->
        try {
            view.animate()
                .scaleX(1.15f)
                .scaleY(1.15f)
                .setDuration(120L)
                .withEndAction {
                    try {
                        view.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(120L)
                            .start()
                    } catch (_: Throwable) {
                        // Safe ignore
                    }
                }
                .start()
        } catch (_: Throwable) {
            // Safe ignore
        }
    }

    internal var mainDispatcher: ((() -> Unit) -> Unit) = { action ->
        try {
            val looper = Looper.getMainLooper()
            if (looper != null && Looper.myLooper() == looper) {
                action()
            } else if (looper != null) {
                Handler(looper).post(action)
            } else {
                action()
            }
        } catch (_: Throwable) {
            action()
        }
    }

    internal var touchSlop: Float = try {
        if (context != null) {
            ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        } else {
            16f
        }
    } catch (_: Throwable) {
        16f
    }

    internal var permissionChecker: () -> Boolean = {
        try {
            if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
        } catch (_: Throwable) {
            true
        }
    }

    internal var viewFactory: (() -> Triple<ViewGroup, TextView, ViewGroup>)? = null

    // UI state
    private val _captureCount = MutableStateFlow(0)
    val captureCountState: StateFlow<Int> = _captureCount.asStateFlow()
    val captureCount: Int get() = _captureCount.value

    private val _isShowingState = MutableStateFlow(false)
    val isShowingState: StateFlow<Boolean> = _isShowingState.asStateFlow()
    val isShowing: Boolean get() = _isShowingState.value

    // View references
    internal var rootView: ViewGroup? = null
    internal var badgeView: TextView? = null
    internal var bubbleContainer: ViewGroup? = null
    internal var snapAnimator: ValueAnimator? = null

    // Touch gesture tracking
    internal var touchDownRawX: Float = 0f
    internal var touchDownRawY: Float = 0f
    internal var initialParamX: Int = 0
    internal var initialParamY: Int = 0
    internal var isDragging: Boolean = false

    // Layout configuration constants
    internal var defaultMargin: Int = 16
    internal var defaultViewWidth: Int = 160
    internal var defaultViewHeight: Int = 160

    /**
     * WindowManager layout parameters configured for non-focusable system overlay.
     */
    val layoutParams: WindowManager.LayoutParams = WindowManager.LayoutParams().apply {
        type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        format = PixelFormat.TRANSLUCENT
        gravity = Gravity.TOP or Gravity.START
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        x = initialX
        y = initialY
    }

    /**
     * Displays the floating bubble overlay over active applications.
     *
     * @return True if overlay is successfully shown, false if permission missing or WindowManager error.
     */
    fun show(): Boolean {
        if (_isShowingState.value) return true

        if (!permissionChecker()) {
            toastDisplayer("Overlay permission required. Please enable display over other apps.")
            return false
        }

        val view = ensureViewCreated()
        return try {
            if (view != null && windowManager != null) {
                windowManager?.addView(view, layoutParams)
            }
            _isShowingState.value = true
            true
        } catch (e: Throwable) {
            toastDisplayer("Failed to display overlay: ${e.message}")
            false
        }
    }

    /**
     * Temporarily hides the floating overlay bubble.
     */
    fun hide() {
        if (!_isShowingState.value) return
        try {
            val view = rootView
            if (view != null && windowManager != null) {
                windowManager?.removeView(view)
            }
        } catch (_: Throwable) {
            // Safe ignore
        } finally {
            _isShowingState.value = false
        }
    }

    /**
     * Completely destroys the floating overlay, removing views and clearing all animation resources.
     */
    fun destroy() {
        hide()
        try {
            snapAnimator?.cancel()
            snapAnimator = null
        } catch (_: Throwable) {
            // Safe ignore
        }
        rootView = null
        badgeView = null
        bubbleContainer = null
    }

    /**
     * Increments the real-time capture counter and updates the badge UI.
     *
     * @return The updated capture count.
     */
    fun incrementCounter(): Int {
        val newCount = _captureCount.value + 1
        _captureCount.value = newCount
        mainDispatcher {
            updateBadgeUI(newCount)
        }
        return newCount
    }

    /**
     * Resets the capture counter back to 0 and updates the badge UI.
     */
    fun resetCounter() {
        _captureCount.value = 0
        mainDispatcher {
            updateBadgeUI(0)
        }
    }

    /**
     * Triggers screen capture when the floating bubble is tapped.
     * Provides haptic and visual feedback, coordinates with [UICaptureAccessibilityService],
     * increments the counter on success, and shows Toast summaries.
     */
    fun performCaptureTap() {
        hapticFeedbackProvider()
        bubbleContainer?.let { pulseAnimator(it) } ?: rootView?.let { pulseAnimator(it) }

        captureTrigger(
            { data, _ ->
                incrementCounter()
                val message = OverlayMath.formatSuccessToast(data.elementCount, data.packageName)
                toastDisplayer(message)
            },
            { errorMessage ->
                val displayError = if (errorMessage.contains(UICaptureAccessibilityService.ERROR_SERVICE_NOT_RUNNING)) {
                    "UICapture Accessibility Service is disabled. Please enable it in Settings."
                } else {
                    "Capture failed: $errorMessage"
                }
                toastDisplayer(displayError)
            }
        )
    }

    /**
     * Touch event entry point handling touch down gesture.
     */
    internal fun handleTouchDown(rawX: Float, rawY: Float) {
        touchDownRawX = rawX
        touchDownRawY = rawY
        initialParamX = layoutParams.x
        initialParamY = layoutParams.y
        isDragging = false
    }

    /**
     * Touch event entry point handling touch move gesture.
     * Discriminate between short tap and drag based on [touchSlop].
     *
     * @return True if currently dragging, false if movement is within tap threshold.
     */
    internal fun handleTouchMove(rawX: Float, rawY: Float): Boolean {
        val dx = rawX - touchDownRawX
        val dy = rawY - touchDownRawY
        if (!isDragging && OverlayMath.isDragGesture(touchDownRawX, touchDownRawY, rawX, rawY, touchSlop)) {
            isDragging = true
        }
        if (isDragging) {
            layoutParams.x = initialParamX + dx.toInt()
            layoutParams.y = initialParamY + dy.toInt()
            updateViewLayout()
        }
        return isDragging
    }

    /**
     * Touch event entry point handling touch release (UP/CANCEL) gesture.
     * Triggers capture tap if movement remained under [touchSlop], or snaps to the nearest
     * edge if dragging was in progress.
     *
     * @return True if tap was performed, false if snap-to-edge was performed.
     */
    internal fun handleTouchUp(): Boolean {
        return if (!isDragging) {
            performCaptureTap()
            true
        } else {
            snapToEdge()
            false
        }
    }

    /**
     * Snaps the floating bubble to the closest horizontal screen edge and clamps Y within bounds.
     */
    internal fun snapToEdge() {
        val (screenWidth, screenHeight) = screenSizeProvider()
        val viewWidth = rootView?.width?.takeIf { it > 0 } ?: defaultViewWidth
        val viewHeight = rootView?.height?.takeIf { it > 0 } ?: defaultViewHeight

        val targetX = OverlayMath.calculateSnapTargetX(
            currentX = layoutParams.x,
            viewWidth = viewWidth,
            screenWidth = screenWidth,
            margin = defaultMargin
        )
        val targetY = OverlayMath.clampY(
            currentY = layoutParams.y,
            viewHeight = viewHeight,
            screenHeight = screenHeight,
            topMargin = defaultMargin,
            bottomMargin = defaultMargin
        )

        layoutParams.y = targetY
        animateSnapToX(targetX)
    }

    internal var snapAnimatorHandler: ((targetX: Int, onUpdate: (Int) -> Unit) -> Unit)? = null

    /**
     * Smoothly animates the layoutParams X coordinate to [targetX].
     */
    internal fun animateSnapToX(targetX: Int) {
        val customHandler = snapAnimatorHandler
        if (customHandler != null) {
            customHandler(targetX) { x ->
                layoutParams.x = x
                updateViewLayout()
            }
            return
        }

        try {
            val looper = Looper.getMainLooper()
            if (looper == null) {
                layoutParams.x = targetX
                updateViewLayout()
                return
            }
            snapAnimator?.cancel()
            val startX = layoutParams.x
            val animator = ValueAnimator.ofInt(startX, targetX).apply {
                duration = 200L
                interpolator = DecelerateInterpolator()
                addUpdateListener { va ->
                    layoutParams.x = va.animatedValue as Int
                    updateViewLayout()
                }
            }
            snapAnimator = animator
            animator.start()
        } catch (_: Throwable) {
            layoutParams.x = targetX
            updateViewLayout()
        }
    }

    /**
     * Synchronizes updated layout parameters with the active [WindowManager].
     */
    internal fun updateViewLayout() {
        try {
            val view = rootView ?: return
            val wm = windowManager ?: return
            if (_isShowingState.value) {
                wm.updateViewLayout(view, layoutParams)
            }
        } catch (_: Throwable) {
            // Safe ignore if view is detached or in testing
        }
    }

    /**
     * Updates badge text and visibility based on capture count.
     */
    internal fun updateBadgeUI(count: Int) {
        val badge = badgeView ?: return
        if (count > 0) {
            badge.text = OverlayMath.formatBadgeText(count)
            badge.visibility = View.VISIBLE
        } else {
            badge.text = OverlayMath.formatBadgeText(0)
            badge.visibility = View.GONE
        }
    }

    /**
     * Lazily builds the floating bubble view hierarchy or uses injected [viewFactory].
     */
    private fun ensureViewCreated(): View? {
        val existing = rootView
        if (existing != null) return existing

        val custom = viewFactory
        if (custom != null) {
            val (root, badge, bubble) = custom()
            rootView = root
            badgeView = badge
            bubbleContainer = bubble
            attachTouchListener(root)
            updateBadgeUI(_captureCount.value)
            return root
        }

        val ctx = context ?: return null
        val (root, badge, bubble) = createDefaultViews(ctx)
        rootView = root
        badgeView = badge
        bubbleContainer = bubble

        attachTouchListener(root)
        updateBadgeUI(_captureCount.value)
        return root
    }

    /**
     * Attaches touch gesture listener to the root overlay view.
     */
    private fun attachTouchListener(view: View) {
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    handleTouchDown(event.rawX, event.rawY)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    handleTouchMove(event.rawX, event.rawY)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handleTouchUp()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Programmatically constructs the bubble view layout:
     * - Circular card/container with elevation and camera icon.
     * - Top-right badge counter displaying `#1`, `#2`, ...
     */
    private fun createDefaultViews(ctx: Context): Triple<ViewGroup, TextView, ViewGroup> {
        val density = try { ctx.resources.displayMetrics.density } catch (_: Throwable) { 1f }
        val bubbleSize = (56 * density).roundToInt().coerceAtLeast(48)
        val iconSize = (28 * density).roundToInt().coerceAtLeast(24)
        val badgePaddingH = (6 * density).roundToInt().coerceAtLeast(4)
        val badgePaddingV = (2 * density).roundToInt().coerceAtLeast(2)

        // Outer container with padding so badge does not clip
        val root = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 0, (8 * density).roundToInt(), 0)
        }

        // Circular bubble button container
        val bubble = FrameLayout(ctx).apply {
            val lp = FrameLayout.LayoutParams(bubbleSize, bubbleSize).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                topMargin = (6 * density).roundToInt()
            }
            layoutParams = lp

            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1E293B")) // Slate 800
                setStroke((2 * density).roundToInt().coerceAtLeast(1), Color.parseColor("#3B82F6")) // Blue 500
            }
            background = bg
            elevation = 8f * density
        }

        // Camera Icon inside bubble
        val icon = ImageView(ctx).apply {
            val lp = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER
            }
            layoutParams = lp
            setImageResource(android.R.drawable.ic_menu_camera)
            setColorFilter(Color.WHITE)
        }
        bubble.addView(icon)
        root.addView(bubble)

        // Badge counter TextView
        val badge = TextView(ctx).apply {
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
            }
            layoutParams = lp

            val badgeBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10f * density
                setColor(Color.parseColor("#EF4444")) // Red 500
                setStroke((1 * density).roundToInt().coerceAtLeast(1), Color.WHITE)
            }
            background = badgeBg
            setPadding(badgePaddingH, badgePaddingV, badgePaddingH, badgePaddingV)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.DEFAULT_BOLD
            text = OverlayMath.formatBadgeText(0)
            visibility = View.GONE
        }
        root.addView(badge)

        return Triple(root, badge, bubble)
    }

    companion object {
        /**
         * Resolves display dimensions from [WindowManager] or [DisplayMetrics].
         */
        fun getDefaultScreenMetrics(context: Context): Pair<Int, Int> {
            return try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bounds = wm?.currentWindowMetrics?.bounds
                    if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                        Pair(bounds.width(), bounds.height())
                    } else {
                        val dm = context.resources.displayMetrics
                        Pair(dm.widthPixels, dm.heightPixels)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val display = wm?.defaultDisplay
                    val dm = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    display?.getRealMetrics(dm)
                    if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                        Pair(dm.widthPixels, dm.heightPixels)
                    } else {
                        Pair(1080, 2400)
                    }
                }
            } catch (_: Throwable) {
                Pair(1080, 2400)
            }
        }
    }
}
