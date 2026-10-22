package com.uicapture.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.uicapture.app.engine.AccessibilityNodeInfoAdapter
import com.uicapture.app.engine.ExportResult
import com.uicapture.app.engine.HierarchyExporter
import com.uicapture.app.engine.NodeBounds
import com.uicapture.app.engine.ScreenTreeBuilder
import com.uicapture.app.engine.TreeNodeInfo
import com.uicapture.app.engine.TreeResult
import com.uicapture.app.model.ScreenCaptureData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Callback invoked upon screen capture completion or failure.
 */
interface CaptureCallback {
    /**
     * Called when UI hierarchy and optional screenshot have been captured and exported successfully.
     *
     * @param data The captured screen data and elements list.
     * @param screenshot The captured bitmap, or null if screenshot capture was disabled/unsupported.
     * @param exportResult The export result containing file paths on disk.
     */
    fun onSuccess(data: ScreenCaptureData, screenshot: Bitmap?, exportResult: ExportResult)

    /**
     * Called when capture fails at any stage.
     *
     * @param errorMessage Detailed description of the error.
     */
    fun onError(errorMessage: String)
}

/**
 * Functional factory helper for [CaptureCallback].
 */
fun captureCallback(
    onError: (String) -> Unit = {},
    onSuccess: (ScreenCaptureData, Bitmap?, ExportResult) -> Unit
): CaptureCallback = object : CaptureCallback {
    override fun onSuccess(data: ScreenCaptureData, screenshot: Bitmap?, exportResult: ExportResult) {
        onSuccess(data, screenshot, exportResult)
    }

    override fun onError(errorMessage: String) {
        onError(errorMessage)
    }
}

/**
 * Native Android [AccessibilityService] that monitors active windows, tracks foreground applications,
 * inspects UI hierarchy via [ScreenTreeBuilder], captures screen bitmaps via native Android 11+ APIs,
 * and coordinates export to shared device storage via [HierarchyExporter].
 */
class UICaptureAccessibilityService : AccessibilityService() {

    // Foreground window tracking
    var lastActivePackageName: String = ""
        internal set

    var lastActiveClassName: String = ""
        internal set

    var lastActiveWindowTitle: String = ""
        internal set

    // Pluggable dependencies with real production defaults for maximum testability
    internal var rootNodeProvider: () -> TreeNodeInfo? = {
        rootInActiveWindow?.let { AccessibilityNodeInfoAdapter(it) }
    }

    internal var treeBuilder: (TreeNodeInfo?) -> TreeResult = { node ->
        ScreenTreeBuilder.build(node, ScreenTreeBuilder.OVERLAY_PACKAGE)
    }

    internal var screenshotProvider: (((Bitmap?) -> Unit) -> Unit)? = null

    internal var hierarchyExporter: HierarchyExporter = HierarchyExporter()

    internal var ioExecutor: Executor = Executors.newSingleThreadExecutor()

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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceRunningState.value = true
        Log.i(TAG, "UICaptureAccessibilityService connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
            _isServiceRunningState.value = false
        }
        try {
            if (ioExecutor is ExecutorService) {
                (ioExecutor as ExecutorService).shutdown()
            }
        } catch (_: Throwable) {
            // Safe ignore
        }
        Log.i(TAG, "UICaptureAccessibilityService destroyed")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) {
            instance = null
            _isServiceRunningState.value = false
        }
        Log.i(TAG, "UICaptureAccessibilityService unbound")
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        Log.w(TAG, "UICaptureAccessibilityService interrupted")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        updateActiveWindowState(
            packageName = event.packageName,
            className = event.className,
            text = event.text
        )
    }

    /**
     * Updates active window state tracking, skipping the app's own overlay package
     * so that touching the floating bubble does not overwrite target application metadata.
     */
    internal fun updateActiveWindowState(
        packageName: CharSequence?,
        className: CharSequence? = null,
        text: List<CharSequence?>? = null
    ) {
        val pkg = packageName?.toString()
        if (!pkg.isNullOrBlank() && !pkg.equals(ScreenTreeBuilder.OVERLAY_PACKAGE, ignoreCase = true)) {
            lastActivePackageName = pkg
        }

        val cls = className?.toString()
        if (!cls.isNullOrBlank()) {
            lastActiveClassName = cls
        }

        if (!text.isNullOrEmpty()) {
            val title = text.filterNotNull().joinToString(" ").trim()
            if (title.isNotEmpty()) {
                lastActiveWindowTitle = title
            }
        }
    }

    /**
     * Captures the active screen hierarchy, optional screenshot, and writes all artifacts
     * to disk via [HierarchyExporter].
     *
     * @param context Optional [Context] used to resolve display metrics.
     * @param takeScreenshot Whether to acquire a screen bitmap (API 30+).
     * @param callback Completion and error handler dispatched on the main thread.
     */
    fun captureActiveScreen(
        context: Context? = null,
        takeScreenshot: Boolean = true,
        callback: CaptureCallback
    ) {
        // 1. Retrieve active window root node
        val rootNode = try {
            rootNodeProvider()
        } catch (e: Throwable) {
            dispatchError(callback, "Failed to retrieve active window root: ${e.message}")
            return
        }

        if (rootNode == null) {
            dispatchError(callback, "Active window root node is null or inaccessible.")
            return
        }

        // 2. Build tree and capture metadata
        val treeResult: TreeResult
        val targetPackage: String
        val resolution: String
        try {
            targetPackage = rootNode.packageName?.toString()
                ?.ifBlank { lastActivePackageName }
                ?: lastActivePackageName.ifBlank { "unknown" }

            resolution = resolveScreenResolution(rootNode, context)
            treeResult = treeBuilder(rootNode)
        } catch (e: Throwable) {
            dispatchError(callback, "Error traversing UI hierarchy: ${e.message}")
            return
        } finally {
            try {
                rootNode.recycle()
            } catch (_: Throwable) {
                // Safe ignore
            }
        }

        val timestamp = System.currentTimeMillis()
        val captureId = "cap_${timestamp}_${targetPackage.replace(".", "_")}"
        val captureData = ScreenCaptureData(
            captureId = captureId,
            timestamp = timestamp,
            packageName = targetPackage,
            activityName = lastActiveClassName.ifBlank { null },
            screenResolution = resolution,
            elements = treeResult.elements,
            rawXml = treeResult.rawXml
        )

        // 3. Acquire screenshot if requested and supported
        if (takeScreenshot) {
            acquireScreenshot { bitmap ->
                ioExecutor.execute {
                    try {
                        val exportResult = hierarchyExporter.export(captureData, bitmap)
                        dispatchSuccess(callback, captureData, bitmap, exportResult)
                    } catch (e: Throwable) {
                        dispatchError(callback, "Failed to export capture data: ${e.message}")
                    }
                }
            }
        } else {
            ioExecutor.execute {
                try {
                    val exportResult = hierarchyExporter.export(captureData, null)
                    dispatchSuccess(callback, captureData, null, exportResult)
                } catch (e: Throwable) {
                    dispatchError(callback, "Failed to export capture data: ${e.message}")
                }
            }
        }
    }

    /**
     * Convenience overload taking functional lambdas.
     */
    fun captureActiveScreen(
        takeScreenshot: Boolean = true,
        onSuccess: (ScreenCaptureData, Bitmap?) -> Unit,
        onError: (String) -> Unit
    ) {
        captureActiveScreen(
            context = null,
            takeScreenshot = takeScreenshot,
            callback = object : CaptureCallback {
                override fun onSuccess(
                    data: ScreenCaptureData,
                    screenshot: Bitmap?,
                    exportResult: ExportResult
                ) {
                    onSuccess(data, screenshot)
                }

                override fun onError(errorMessage: String) {
                    onError(errorMessage)
                }
            }
        )
    }

    /**
     * Convenience overload taking functional lambdas including [ExportResult].
     */
    fun captureActiveScreen(
        context: Context? = null,
        takeScreenshot: Boolean = true,
        onSuccess: (ScreenCaptureData, Bitmap?, ExportResult) -> Unit,
        onError: (String) -> Unit
    ) {
        captureActiveScreen(
            context = context,
            takeScreenshot = takeScreenshot,
            callback = object : CaptureCallback {
                override fun onSuccess(
                    data: ScreenCaptureData,
                    screenshot: Bitmap?,
                    exportResult: ExportResult
                ) {
                    onSuccess(data, screenshot, exportResult)
                }

                override fun onError(errorMessage: String) {
                    onError(errorMessage)
                }
            }
        )
    }

    /**
     * Resolves display resolution string (`<width>x<height>`), safely falling back
     * between display metrics, root node screen coordinates, and `"unknown"`.
     */
    private fun resolveScreenResolution(root: TreeNodeInfo?, context: Context?): String {
        try {
            val ctx = context ?: this
            val res = try { ctx.resources } catch (_: Throwable) { null }
            val dm = res?.displayMetrics
            if (dm != null && dm.widthPixels > 0 && dm.heightPixels > 0) {
                return "${dm.widthPixels}x${dm.heightPixels}"
            }
        } catch (_: Throwable) {
            // Fallback
        }

        if (root != null) {
            try {
                val bounds = NodeBounds()
                root.getBoundsInScreen(bounds)
                val w = kotlin.math.abs(bounds.right - bounds.left)
                val h = kotlin.math.abs(bounds.bottom - bounds.top)
                if (w > 0 && h > 0) {
                    return "${w}x${h}"
                }
            } catch (_: Throwable) {
                // Fallback
            }
        }

        return "unknown"
    }

    /**
     * Acquires screen bitmap via native Android 11+ (API 30+) [takeScreenshot] API,
     * converting hardware buffer to a software bitmap for PNG compression.
     */
    private fun acquireScreenshot(onResult: (Bitmap?) -> Unit) {
        val custom = screenshotProvider
        if (custom != null) {
            custom(onResult)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    ioExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            var finalBitmap: Bitmap? = null
                            val hardwareBuffer = screenshotResult.hardwareBuffer
                            val colorSpace = screenshotResult.colorSpace
                            try {
                                val hwBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                                if (hwBitmap != null) {
                                    val copy = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                    if (copy != null && copy != hwBitmap) {
                                        hwBitmap.recycle()
                                    }
                                    finalBitmap = copy ?: hwBitmap
                                }
                            } catch (e: Throwable) {
                                Log.w(TAG, "Failed to wrap hardware buffer into bitmap: ${e.message}")
                            } finally {
                                try {
                                    hardwareBuffer.close()
                                } catch (_: Throwable) {
                                    // Safe ignore
                                }
                            }
                            onResult(finalBitmap)
                        }

                        override fun onFailure(errorCode: Int) {
                            val reason = when (errorCode) {
                                ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "INTERNAL_ERROR"
                                ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "NO_ACCESSIBILITY_ACCESS"
                                ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "INTERVAL_TIME_SHORT"
                                ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "INVALID_DISPLAY"
                                5 -> "SECURE_WINDOW"
                                else -> "UNKNOWN ($errorCode)"
                            }
                            Log.w(TAG, "Native takeScreenshot failed: $reason ($errorCode)")
                            onResult(null)
                        }
                    }
                )
            } catch (e: Throwable) {
                Log.w(TAG, "Exception invoking native takeScreenshot: ${e.message}")
                onResult(null)
            }
        } else {
            // Below Android 11 (API 30), native takeScreenshot is not supported
            onResult(null)
        }
    }

    private fun dispatchSuccess(
        callback: CaptureCallback,
        data: ScreenCaptureData,
        screenshot: Bitmap?,
        exportResult: ExportResult
    ) {
        mainDispatcher {
            callback.onSuccess(data, screenshot, exportResult)
        }
    }

    private fun dispatchError(callback: CaptureCallback, message: String) {
        mainDispatcher {
            callback.onError(message)
        }
    }

    companion object {
        private const val TAG = "UICaptureAccessibilityService"

        const val ERROR_SERVICE_NOT_RUNNING =
            "UICaptureAccessibilityService is not running. Please enable the accessibility service in System Settings."

        /**
         * Active singleton instance of the service when connected and running.
         */
        @Volatile
        var instance: UICaptureAccessibilityService? = null
            internal set

        private val _isServiceRunningState = MutableStateFlow(false)

        /**
         * Observable [StateFlow] representing whether the accessibility service is active.
         */
        val isServiceRunningState: StateFlow<Boolean> = _isServiceRunningState.asStateFlow()

        /**
         * Reactive flow alias for [isServiceRunningState].
         */
        val isServiceRunningFlow: StateFlow<Boolean> get() = isServiceRunningState

        /**
         * Direct boolean property check indicating if the service is currently running.
         */
        val isServiceRunning: Boolean get() = instance != null

        /**
         * App-wide capture trigger helper. Dispatches to [instance] if active,
         * or invokes [callback.onError] if service is inactive.
         */
        fun captureActiveScreen(
            context: Context? = null,
            takeScreenshot: Boolean = true,
            callback: CaptureCallback
        ) {
            val currentInstance = instance
            if (currentInstance == null) {
                callback.onError(ERROR_SERVICE_NOT_RUNNING)
                return
            }
            currentInstance.captureActiveScreen(context, takeScreenshot, callback)
        }

        /**
         * App-wide capture trigger overload using lambdas.
         */
        fun captureActiveScreen(
            takeScreenshot: Boolean = true,
            onSuccess: (ScreenCaptureData, Bitmap?) -> Unit,
            onError: (String) -> Unit
        ) {
            captureActiveScreen(
                context = null,
                takeScreenshot = takeScreenshot,
                callback = object : CaptureCallback {
                    override fun onSuccess(
                        data: ScreenCaptureData,
                        screenshot: Bitmap?,
                        exportResult: ExportResult
                    ) {
                        onSuccess(data, screenshot)
                    }

                    override fun onError(errorMessage: String) {
                        onError(errorMessage)
                    }
                }
            )
        }

        /**
         * App-wide capture trigger overload using lambdas with [ExportResult].
         */
        fun captureActiveScreen(
            context: Context? = null,
            takeScreenshot: Boolean = true,
            onSuccess: (ScreenCaptureData, Bitmap?, ExportResult) -> Unit,
            onError: (String) -> Unit
        ) {
            captureActiveScreen(
                context = context,
                takeScreenshot = takeScreenshot,
                callback = object : CaptureCallback {
                    override fun onSuccess(
                        data: ScreenCaptureData,
                        screenshot: Bitmap?,
                        exportResult: ExportResult
                    ) {
                        onSuccess(data, screenshot, exportResult)
                    }

                    override fun onError(errorMessage: String) {
                        onError(errorMessage)
                    }
                }
            )
        }

        /**
         * Test utility method to set or clear the singleton instance.
         */
        internal fun setInstanceForTesting(service: UICaptureAccessibilityService?) {
            instance = service
            _isServiceRunningState.value = (service != null)
        }

        /**
         * Test utility method to reset all companion state.
         */
        internal fun resetForTesting() {
            instance = null
            _isServiceRunningState.value = false
        }
    }
}
