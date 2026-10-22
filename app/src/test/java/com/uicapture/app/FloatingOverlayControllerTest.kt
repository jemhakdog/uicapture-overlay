package com.uicapture.app

import com.uicapture.app.model.ScreenCaptureData
import com.uicapture.app.model.UIElement
import com.uicapture.app.overlay.FloatingOverlayController
import com.uicapture.app.overlay.OverlayMath
import com.uicapture.app.service.OverlayService
import com.uicapture.app.service.UICaptureAccessibilityService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests verifying [FloatingOverlayController], [OverlayMath], and [OverlayService] behavior:
 * - Touch drag threshold math (discriminating short tap vs move/drag)
 * - Snap-to-edge calculation and screen boundary clamping
 * - Real-time capture counter state, incrementing, and resetting
 * - Single-tap capture triggers, haptic/visual feedback dispatches, and Toast formatting
 * - Controller show/hide/destroy lifecycle state transitions
 * - OverlayService companion state management and lifecycle hooks
 */
class FloatingOverlayControllerTest {

    @Before
    fun setUp() {
        OverlayService.resetForTesting()
        UICaptureAccessibilityService.resetForTesting()
    }

    @After
    fun tearDown() {
        OverlayService.resetForTesting()
        UICaptureAccessibilityService.resetForTesting()
    }

    // =========================================================================
    // 1. Touch Drag Threshold & Distance Math
    // =========================================================================

    @Test
    fun testTouchDragThresholdZeroDeltaIsTap() {
        val slop = 16f
        val isDrag = OverlayMath.isDragGesture(100f, 100f, 100f, 100f, slop)
        assertFalse("Zero movement must not be classified as drag", isDrag)
    }

    @Test
    fun testTouchDragThresholdSmallMovementIsTap() {
        val slop = 16f
        // hypot(5, 5) ≈ 7.07 < 16
        val isDrag = OverlayMath.isDragGesture(100f, 100f, 105f, 105f, slop)
        assertFalse("Movement within slop threshold must be classified as tap", isDrag)
    }

    @Test
    fun testTouchDragThresholdExceedingSlopIsDrag() {
        val slop = 16f
        // hypot(12, 12) ≈ 16.97 > 16
        val isDrag = OverlayMath.isDragGesture(100f, 100f, 112f, 112f, slop)
        assertTrue("Movement exceeding slop threshold must be classified as drag", isDrag)
    }

    @Test
    fun testTouchDragThresholdNegativeMovementIsDrag() {
        val slop = 16f
        // Movement in negative direction dx = -20, dy = 0
        val isDrag = OverlayMath.isDragGesture(200f, 300f, 180f, 300f, slop)
        assertTrue("Negative movement exceeding slop must be classified as drag", isDrag)
    }

    @Test
    fun testTouchDragThresholdExactBoundaryBehavior() {
        val slop = 16f
        // Exact distance = 16.0f (not strictly greater than slop)
        val isDragAtBoundary = OverlayMath.isDragGesture(0f, 0f, 16f, 0f, slop)
        assertFalse("Distance exactly equal to slop should not trigger drag", isDragAtBoundary)

        // Distance = 16.1f
        val isDragBeyondBoundary = OverlayMath.isDragGesture(0f, 0f, 16.1f, 0f, slop)
        assertTrue("Distance strictly exceeding slop must trigger drag", isDragBeyondBoundary)
    }

    // =========================================================================
    // 2. Snap-To-Edge Coordinates & Math
    // =========================================================================

    @Test
    fun testSnapToEdgeCoordinatesLeftHalf() {
        val screenWidth = 1080
        val viewWidth = 160
        val margin = 16

        // Positioned at X = 100, center = 180 (< 540)
        val targetX = OverlayMath.calculateSnapTargetX(
            currentX = 100,
            viewWidth = viewWidth,
            screenWidth = screenWidth,
            margin = margin
        )
        assertEquals("Bubble on the left half must snap to left margin", margin, targetX)
    }

    @Test
    fun testSnapToEdgeCoordinatesRightHalf() {
        val screenWidth = 1080
        val viewWidth = 160
        val margin = 16

        // Positioned at X = 700, center = 780 (>= 540)
        val targetX = OverlayMath.calculateSnapTargetX(
            currentX = 700,
            viewWidth = viewWidth,
            screenWidth = screenWidth,
            margin = margin
        )
        val expectedRight = screenWidth - viewWidth - margin // 1080 - 160 - 16 = 904
        assertEquals("Bubble on the right half must snap to right edge minus viewWidth and margin", expectedRight, targetX)
    }

    @Test
    fun testSnapToEdgeCenterBoundary() {
        val screenWidth = 1080
        val viewWidth = 160
        val margin = 20

        // Screen center is 540.
        // If currentX = 459, center = 459 + 80 = 539 (< 540) -> snaps left
        val snapLeft = OverlayMath.calculateSnapTargetX(459, viewWidth, screenWidth, margin)
        assertEquals(margin, snapLeft)

        // If currentX = 460, center = 460 + 80 = 540 (>= 540) -> snaps right
        val snapRight = OverlayMath.calculateSnapTargetX(460, viewWidth, screenWidth, margin)
        val expectedRight = screenWidth - viewWidth - margin // 1080 - 160 - 20 = 900
        assertEquals(expectedRight, snapRight)
    }

    @Test
    fun testSnapToEdgeZeroOrNegativeScreenWidthFallback() {
        val targetXZero = OverlayMath.calculateSnapTargetX(100, 160, 0, 16)
        assertEquals(16, targetXZero)

        val targetXNegative = OverlayMath.calculateSnapTargetX(100, 160, -100, 16)
        assertEquals(16, targetXNegative)
    }

    @Test
    fun testSnapToEdgeNarrowScreenClamping() {
        // Screen width is smaller than viewWidth + margin
        val screenWidth = 150
        val viewWidth = 160
        val margin = 16
        val targetX = OverlayMath.calculateSnapTargetX(80, viewWidth, screenWidth, margin)
        // Coerced at least to margin
        assertEquals(margin, targetX)
    }

    // =========================================================================
    // 3. Vertical Y Clamping
    // =========================================================================

    @Test
    fun testClampYWithinBounds() {
        val screenHeight = 2400
        val viewHeight = 160
        val topMargin = 50
        val bottomMargin = 50

        // Y = 500 is safely within [50, 2400 - 160 - 50 = 2190]
        val clamped = OverlayMath.clampY(500, viewHeight, screenHeight, topMargin, bottomMargin)
        assertEquals(500, clamped)
    }

    @Test
    fun testClampYAboveTopMargin() {
        val screenHeight = 2400
        val viewHeight = 160
        val topMargin = 60
        val bottomMargin = 60

        val clamped = OverlayMath.clampY(-20, viewHeight, screenHeight, topMargin, bottomMargin)
        assertEquals("Y coordinate above top margin must clamp to top margin", topMargin, clamped)
    }

    @Test
    fun testClampYBelowBottomMargin() {
        val screenHeight = 2400
        val viewHeight = 160
        val topMargin = 50
        val bottomMargin = 50
        val maxAllowed = screenHeight - viewHeight - bottomMargin // 2190

        val clamped = OverlayMath.clampY(2350, viewHeight, screenHeight, topMargin, bottomMargin)
        assertEquals("Y coordinate below bottom margin must clamp to max allowed Y", maxAllowed, clamped)
    }

    @Test
    fun testClampYZeroScreenHeightFallback() {
        val clamped = OverlayMath.clampY(300, 160, 0, 40, 40)
        assertEquals(40, clamped)
    }

    // =========================================================================
    // 4. Badge & Toast Text Formatting
    // =========================================================================

    @Test
    fun testFormatBadgeText() {
        assertEquals("#0", OverlayMath.formatBadgeText(0))
        assertEquals("#1", OverlayMath.formatBadgeText(1))
        assertEquals("#25", OverlayMath.formatBadgeText(25))
        assertEquals("#1000", OverlayMath.formatBadgeText(1000))
    }

    @Test
    fun testFormatSuccessToast() {
        val message = OverlayMath.formatSuccessToast(48, "com.target.application")
        assertEquals("Captured 48 elements for com.target.application", message)

        val zeroMessage = OverlayMath.formatSuccessToast(0, "unknown")
        assertEquals("Captured 0 elements for unknown", zeroMessage)
    }

    // =========================================================================
    // 5. Controller State, Counter, and Increments
    // =========================================================================

    @Test
    fun testCaptureCounterInitialState() {
        val controller = FloatingOverlayController()
        assertEquals(0, controller.captureCount)
        assertEquals(0, controller.captureCountState.value)
        assertFalse(controller.isShowing)
        assertFalse(controller.isShowingState.value)
    }

    @Test
    fun testCaptureCounterIncrementMonotonically() {
        val controller = FloatingOverlayController()
        assertEquals(1, controller.incrementCounter())
        assertEquals(1, controller.captureCount)
        assertEquals(1, controller.captureCountState.value)

        assertEquals(2, controller.incrementCounter())
        assertEquals(2, controller.captureCount)

        assertEquals(3, controller.incrementCounter())
        assertEquals(3, controller.captureCount)
    }

    @Test
    fun testCaptureCounterReset() {
        val controller = FloatingOverlayController()
        controller.incrementCounter()
        controller.incrementCounter()
        assertEquals(2, controller.captureCount)

        controller.resetCounter()
        assertEquals(0, controller.captureCount)
        assertEquals(0, controller.captureCountState.value)
    }

    // =========================================================================
    // 6. Single-Tap Capture Actions & Toast Feedback
    // =========================================================================

    @Test
    fun testPerformCaptureTapSuccessFlow() {
        var hapticCalled = false
        var pulseCount = 0
        var displayedToast: String? = null

        val sampleData = ScreenCaptureData(
            captureId = "cap_test_123",
            timestamp = 1700000000000L,
            packageName = "com.sample.store",
            activityName = "StoreActivity",
            screenResolution = "1080x2400",
            elements = listOf(
                UIElement(id = "1", className = "android.widget.TextView", text = "Item", bounds = "[0,0][100,100]"),
                UIElement(id = "2", className = "android.widget.Button", text = "Buy", bounds = "[100,0][200,100]")
            ),
            rawXml = "<node/>"
        )

        val controller = FloatingOverlayController().apply {
            hapticFeedbackProvider = { hapticCalled = true }
            pulseAnimator = { pulseCount++ }
            toastDisplayer = { msg -> displayedToast = msg }
            captureTrigger = { onSuccess, _ ->
                onSuccess(sampleData, null)
            }
        }

        controller.performCaptureTap()

        assertTrue("Haptic feedback must be triggered on capture tap", hapticCalled)
        assertEquals("Pulse animation must be triggered on immediate tap and again on capture completion", 2, pulseCount)
        assertEquals("Capture counter must increment on successful capture", 1, controller.captureCount)
        assertEquals(
            "Toast must display formatted capture element count and package name",
            "Captured 2 elements for com.sample.store",
            displayedToast
        )
    }

    @Test
    fun testPerformCaptureTapInactiveServiceErrorFlow() {
        var displayedToast: String? = null

        val controller = FloatingOverlayController().apply {
            toastDisplayer = { msg -> displayedToast = msg }
            captureTrigger = { _, onError ->
                onError(UICaptureAccessibilityService.ERROR_SERVICE_NOT_RUNNING)
            }
        }

        controller.performCaptureTap()

        assertEquals("Capture counter must not increment on capture failure", 0, controller.captureCount)
        assertNotNull("Toast must be displayed", displayedToast)
        assertTrue(
            "Toast must prompt user to enable accessibility service in settings",
            displayedToast!!.contains("UICapture Accessibility Service is disabled")
        )
    }

    @Test
    fun testPerformCaptureTapGenericErrorFlow() {
        var displayedToast: String? = null

        val controller = FloatingOverlayController().apply {
            toastDisplayer = { msg -> displayedToast = msg }
            captureTrigger = { _, onError ->
                onError("Active window root node is null or inaccessible.")
            }
        }

        controller.performCaptureTap()

        assertEquals(0, controller.captureCount)
        assertEquals(
            "Capture failed: Active window root node is null or inaccessible.",
            displayedToast
        )
    }

    // =========================================================================
    // 7. Touch Gesture Sequences (Click vs Drag)
    // =========================================================================

    @Test
    fun testTouchSequenceTapDetectionTriggersCapture() {
        var captureTapExecuted = false

        val controller = FloatingOverlayController().apply {
            touchSlop = 16f
            captureTrigger = { onSuccess, _ ->
                captureTapExecuted = true
                onSuccess(
                    ScreenCaptureData(
                        captureId = "c1",
                        timestamp = 100L,
                        packageName = "com.tap.test",
                        activityName = null,
                        screenResolution = "1080x2400",
                        elements = emptyList(),
                        rawXml = ""
                    ),
                    null
                )
            }
        }

        // 1. Touch Down
        controller.handleTouchDown(100f, 100f)
        assertFalse(controller.isDragging)

        // 2. Touch Move within slop (dx=3, dy=2)
        val isDraggingDuringMove = controller.handleTouchMove(103f, 102f)
        assertFalse("Small move should not trigger dragging", isDraggingDuringMove)
        assertFalse(controller.isDragging)

        // 3. Touch Up (Release)
        val tapTriggered = controller.handleTouchUp()
        assertTrue("handleTouchUp must return true for tap gesture", tapTriggered)
        assertTrue("Capture tap must be executed on tap gesture", captureTapExecuted)
        assertEquals(1, controller.captureCount)
    }

    @Test
    fun testTouchSequenceDragDetectionUpdatesPositionAndSnaps() {
        var snapInvoked = false

        val controller = FloatingOverlayController(initialX = 100, initialY = 300).apply {
            touchSlop = 16f
            screenSizeProvider = { Pair(1080, 2400) }
            defaultMargin = 20
            defaultViewWidth = 160
            defaultViewHeight = 160
        }

        // 1. Touch Down at (100, 100)
        controller.handleTouchDown(100f, 100f)
        assertEquals(100, controller.initialParamX)
        assertEquals(300, controller.initialParamY)
        assertFalse(controller.isDragging)

        // 2. Touch Move beyond slop (dx=50 > 16)
        val isDraggingMove = controller.handleTouchMove(150f, 120f)
        assertTrue("Movement exceeding slop must set isDragging = true", isDraggingMove)
        assertTrue(controller.isDragging)
        // Position updated: initialX (100) + dx (50) = 150
        assertEquals(150, controller.layoutParams.x)
        // Position updated: initialY (300) + dy (20) = 320
        assertEquals(320, controller.layoutParams.y)

        // 3. Touch Up (Release)
        val tapTriggered = controller.handleTouchUp()
        assertFalse("handleTouchUp must return false for drag gesture", tapTriggered)
        // Center is 150 + 80 = 230 < 540 (left half) -> snaps to margin (20)
        assertEquals(20, controller.layoutParams.x)
    }

    @Test
    fun testTouchSequenceDragToRightHalfSnapsToRightEdge() {
        val controller = FloatingOverlayController(initialX = 500, initialY = 400).apply {
            touchSlop = 16f
            screenSizeProvider = { Pair(1080, 2400) }
            defaultMargin = 20
            defaultViewWidth = 160
            defaultViewHeight = 160
        }

        // Touch down at (500, 400)
        controller.handleTouchDown(500f, 400f)
        // Drag to right: dx = +300 (total X = 800)
        controller.handleTouchMove(800f, 400f)
        assertTrue(controller.isDragging)
        assertEquals(800, controller.layoutParams.x)

        // Release: center is 800 + 80 = 880 >= 540 -> snaps right
        val tapTriggered = controller.handleTouchUp()
        assertFalse(tapTriggered)
        val expectedRight = 1080 - 160 - 20 // 900
        assertEquals(expectedRight, controller.layoutParams.x)
    }

    @Test
    fun testCustomSnapAnimatorHandler() {
        var animatedTargetX = -1
        val controller = FloatingOverlayController(initialX = 500, initialY = 400).apply {
            touchSlop = 16f
            screenSizeProvider = { Pair(1080, 2400) }
            defaultMargin = 20
            defaultViewWidth = 160
            defaultViewHeight = 160
            snapAnimatorHandler = { targetX, onUpdate ->
                animatedTargetX = targetX
                onUpdate(targetX)
            }
        }

        controller.handleTouchDown(500f, 400f)
        controller.handleTouchMove(200f, 400f) // dx = -300 -> left half
        controller.handleTouchUp()

        assertEquals(20, animatedTargetX)
        assertEquals(20, controller.layoutParams.x)
    }

    @Test
    fun testTouchCancelWhenNotDraggingDoesNotPerformCapture() {
        var captureTapExecuted = false
        val controller = FloatingOverlayController().apply {
            captureTrigger = { _, _ -> captureTapExecuted = true }
        }

        controller.handleTouchDown(100f, 100f)
        controller.handleTouchCancel()

        assertFalse("Capture tap must NOT be executed on touch cancel", captureTapExecuted)
        assertFalse(controller.isDragging)
        assertEquals(0f, controller.touchDownRawX, 0.001f)
        assertEquals(0f, controller.touchDownRawY, 0.001f)
        assertEquals(0, controller.captureCount)
    }

    @Test
    fun testTouchCancelWhenDraggingSnapsToEdge() {
        var captureTapExecuted = false
        val controller = FloatingOverlayController(initialX = 100, initialY = 300).apply {
            touchSlop = 16f
            screenSizeProvider = { Pair(1080, 2400) }
            defaultMargin = 20
            defaultViewWidth = 160
            defaultViewHeight = 160
            captureTrigger = { _, _ -> captureTapExecuted = true }
        }

        controller.handleTouchDown(100f, 100f)
        controller.handleTouchMove(160f, 100f) // dx = 60 > 16 -> isDragging = true
        assertTrue(controller.isDragging)

        controller.handleTouchCancel()

        assertFalse("Capture tap must NOT be executed on touch cancel", captureTapExecuted)
        assertFalse("isDragging must be reset after cancel", controller.isDragging)
        // Center is 160 + 80 = 240 < 540 -> snapped to left margin 20
        assertEquals(20, controller.layoutParams.x)
        assertEquals(0, controller.captureCount)
    }

    // =========================================================================
    // 8. Controller Show, Hide, Destroy Lifecycle
    // =========================================================================

    @Test
    fun testShowHideDestroyLifecycleState() {
        val controller = FloatingOverlayController().apply {
            permissionChecker = { true }
        }

        assertFalse("Overlay must initially be hidden", controller.isShowing)

        val showResult = controller.show()
        assertTrue("show() must return true when permitted", showResult)
        assertTrue("isShowing must be true after show()", controller.isShowing)
        assertTrue("isShowingState must emit true", controller.isShowingState.value)

        // Calling show() again should be idempotent
        assertTrue("Subsequent show() call must return true", controller.show())

        // Hide overlay
        controller.hide()
        assertFalse("isShowing must be false after hide()", controller.isShowing)
        assertFalse("isShowingState must emit false after hide()", controller.isShowingState.value)

        // Destroy overlay
        controller.destroy()
        assertFalse(controller.isShowing)
        assertNull(controller.rootView)
        assertNull(controller.badgeView)
        assertNull(controller.bubbleContainer)
    }

    @Test
    fun testShowFailsWhenPermissionDenied() {
        var toastMessage: String? = null
        val controller = FloatingOverlayController().apply {
            permissionChecker = { false }
            toastDisplayer = { msg -> toastMessage = msg }
        }

        val showResult = controller.show()
        assertFalse("show() must return false when permission is denied", showResult)
        assertFalse("isShowing must remain false", controller.isShowing)
        assertNotNull(toastMessage)
        assertTrue("Toast must inform user about missing overlay permission", toastMessage!!.contains("Overlay permission required"))
    }

    // =========================================================================
    // 9. OverlayService Companion State & Constants
    // =========================================================================

    @Test
    fun testOverlayServiceInitialState() {
        assertFalse("OverlayService must initially not be running", OverlayService.isRunning.value)
        assertFalse("isServiceRunning must initially be false", OverlayService.isServiceRunning)
        assertNull("instance must initially be null", OverlayService.instance)
    }

    @Test
    fun testOverlayServiceSetInstanceAndReset() {
        val dummyService = OverlayService()
        OverlayService.setInstanceForTesting(dummyService)

        assertTrue(OverlayService.isRunning.value)
        assertTrue(OverlayService.isServiceRunning)
        assertEquals(dummyService, OverlayService.instance)

        OverlayService.resetForTesting()
        assertFalse(OverlayService.isRunning.value)
        assertFalse(OverlayService.isServiceRunning)
        assertNull(OverlayService.instance)
    }

    @Test
    fun testOverlayServiceConstants() {
        assertEquals("com.uicapture.app.action.STOP_OVERLAY", OverlayService.ACTION_STOP)
        assertEquals(1001, OverlayService.NOTIFICATION_ID)
        assertEquals("uicapture_overlay_channel", OverlayService.CHANNEL_ID)
    }
}
