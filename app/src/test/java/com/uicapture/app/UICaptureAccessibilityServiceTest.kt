package com.uicapture.app

import com.uicapture.app.engine.ExportResult
import com.uicapture.app.engine.HierarchyExporter
import com.uicapture.app.engine.NodeBounds
import com.uicapture.app.engine.TreeNodeInfo
import com.uicapture.app.model.ScreenCaptureData
import com.uicapture.app.service.CaptureCallback
import com.uicapture.app.service.UICaptureAccessibilityService
import com.uicapture.app.service.captureCallback
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executor

/**
 * JVM unit tests verifying [UICaptureAccessibilityService] behavior:
 * - Companion singleton lifecycle, StateFlow emissions, and state checks
 * - Capture error propagation when service is inactive
 * - Active window state tracking from accessibility events
 * - Root node retrieval, error handling, and safe node recycling
 * - UI hierarchy capture, metadata extraction, and storage export
 * - Functional callbacks and companion delegation
 */
class UICaptureAccessibilityServiceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * Test implementation of [TreeNodeInfo] for deterministic, platform-independent JVM tests.
     */
    class TestTreeNode(
        override val packageName: CharSequence? = "com.example.target",
        override val className: CharSequence? = "android.view.View",
        override val text: CharSequence? = null,
        override val contentDescription: CharSequence? = null,
        override val viewIdResourceName: String? = null,
        override val isCheckable: Boolean = false,
        override val isChecked: Boolean = false,
        override val isClickable: Boolean = false,
        override val isEnabled: Boolean = true,
        override val isFocusable: Boolean = false,
        override val isFocused: Boolean = false,
        override val isScrollable: Boolean = false,
        override val isLongClickable: Boolean = false,
        override val isPassword: Boolean = false,
        override val isSelected: Boolean = false,
        override val isEditable: Boolean = false,
        val bounds: NodeBounds = NodeBounds(0, 0, 1080, 2400),
        val children: List<TestTreeNode> = emptyList()
    ) : TreeNodeInfo {
        var recycled: Boolean = false

        override val childCount: Int get() = children.size

        override fun getChild(index: Int): TreeNodeInfo? = children.getOrNull(index)

        override fun getBoundsInScreen(outBounds: NodeBounds) {
            outBounds.left = bounds.left
            outBounds.top = bounds.top
            outBounds.right = bounds.right
            outBounds.bottom = bounds.bottom
        }

        override fun recycle() {
            recycled = true
        }
    }

    @Before
    fun setUp() {
        UICaptureAccessibilityService.resetForTesting()
    }

    @After
    fun tearDown() {
        UICaptureAccessibilityService.resetForTesting()
    }

    // ---------------------------------------------------------------------
    // 1. Service State Tracking and StateFlow
    // ---------------------------------------------------------------------

    @Test
    fun testInitialServiceState() {
        assertNull(UICaptureAccessibilityService.instance)
        assertFalse(UICaptureAccessibilityService.isServiceRunning)
        assertFalse(UICaptureAccessibilityService.isServiceRunningState.value)
        assertFalse(UICaptureAccessibilityService.isServiceRunningFlow.value)
    }

    @Test
    fun testServiceLifecycleConnectionAndDisconnection() {
        val service = UICaptureAccessibilityService()

        // Simulate service connection
        service.onServiceConnected()
        assertEquals(service, UICaptureAccessibilityService.instance)
        assertTrue(UICaptureAccessibilityService.isServiceRunning)
        assertTrue(UICaptureAccessibilityService.isServiceRunningState.value)
        assertTrue(UICaptureAccessibilityService.isServiceRunningFlow.value)

        // Simulate service unbind / destroy
        service.onDestroy()
        assertNull(UICaptureAccessibilityService.instance)
        assertFalse(UICaptureAccessibilityService.isServiceRunning)
        assertFalse(UICaptureAccessibilityService.isServiceRunningState.value)
        assertFalse(UICaptureAccessibilityService.isServiceRunningFlow.value)
    }

    @Test
    fun testServiceOnUnbindResetsState() {
        val service = UICaptureAccessibilityService()
        service.onServiceConnected()
        assertTrue(UICaptureAccessibilityService.isServiceRunning)

        service.onUnbind(null)
        assertNull(UICaptureAccessibilityService.instance)
        assertFalse(UICaptureAccessibilityService.isServiceRunning)
        assertFalse(UICaptureAccessibilityService.isServiceRunningState.value)
    }

    // ---------------------------------------------------------------------
    // 2. Companion Error Propagation When Inactive
    // ---------------------------------------------------------------------

    @Test
    fun testCompanionCaptureActiveScreenFailsWhenInactiveInterfaceCallback() {
        var errorReported: String? = null
        var successReported = false

        UICaptureAccessibilityService.captureActiveScreen(
            context = null,
            takeScreenshot = false,
            callback = object : CaptureCallback {
                override fun onSuccess(data: ScreenCaptureData, screenshot: android.graphics.Bitmap?, exportResult: ExportResult) {
                    successReported = true
                }

                override fun onError(errorMessage: String) {
                    errorReported = errorMessage
                }
            }
        )

        assertFalse(successReported)
        assertNotNull(errorReported)
        assertTrue(errorReported!!.contains("UICaptureAccessibilityService is not running"))
    }

    @Test
    fun testCompanionCaptureActiveScreenFailsWhenInactiveLambdaOverloads() {
        var error1: String? = null
        UICaptureAccessibilityService.captureActiveScreen(
            takeScreenshot = false,
            onSuccess = { _, _ -> fail("Should not succeed when service is inactive") },
            onError = { error1 = it }
        )
        assertNotNull(error1)
        assertTrue(error1!!.contains("UICaptureAccessibilityService is not running"))

        var error2: String? = null
        UICaptureAccessibilityService.captureActiveScreen(
            context = null,
            takeScreenshot = false,
            onSuccess = { _, _, _ -> fail("Should not succeed when service is inactive") },
            onError = { error2 = it }
        )
        assertNotNull(error2)
        assertTrue(error2!!.contains("UICaptureAccessibilityService is not running"))
    }

    // ---------------------------------------------------------------------
    // 3. Foreground Window & Package Tracking
    // ---------------------------------------------------------------------

    @Test
    fun testActiveWindowStateTrackingFromEvents() {
        val service = UICaptureAccessibilityService()

        service.updateActiveWindowState(
            packageName = "com.google.android.youtube",
            className = "com.google.android.youtube.WatchActivity",
            text = listOf("Trending", "Gaming Videos")
        )

        assertEquals("com.google.android.youtube", service.lastActivePackageName)
        assertEquals("com.google.android.youtube.WatchActivity", service.lastActiveClassName)
        assertEquals("Trending Gaming Videos", service.lastActiveWindowTitle)
    }

    @Test
    fun testActiveWindowStateSkipsAppOverlayPackage() {
        val service = UICaptureAccessibilityService()

        // Set initial target application
        service.updateActiveWindowState(
            packageName = "com.android.settings",
            className = "com.android.settings.SettingsActivity",
            text = listOf("Settings")
        )
        assertEquals("com.android.settings", service.lastActivePackageName)
        assertEquals("com.android.settings.SettingsActivity", service.lastActiveClassName)
        assertEquals("Settings", service.lastActiveWindowTitle)

        // Event from app's floating bubble / overlay should return early immediately
        // and NEVER overwrite package name, class name, or window title.
        service.updateActiveWindowState(
            packageName = "com.uicapture.app",
            className = "com.uicapture.app.overlay.BubbleView",
            text = listOf("Overlay Bubble")
        )

        // All state fields remain from target application
        assertEquals("com.android.settings", service.lastActivePackageName)
        assertEquals("com.android.settings.SettingsActivity", service.lastActiveClassName)
        assertEquals("Settings", service.lastActiveWindowTitle)

        // Case-insensitive check
        service.updateActiveWindowState(
            packageName = "COM.UICAPTURE.APP",
            className = "com.uicapture.app.ui.MainActivity",
            text = listOf("UICapture Dashboard")
        )
        assertEquals("com.android.settings", service.lastActivePackageName)
        assertEquals("com.android.settings.SettingsActivity", service.lastActiveClassName)
        assertEquals("Settings", service.lastActiveWindowTitle)
    }

    @Test
    fun testNullAndBlankEventsDoNotCorruptState() {
        val service = UICaptureAccessibilityService()
        service.updateActiveWindowState("com.initial.app", "com.initial.Activity", listOf("Initial"))

        service.updateActiveWindowState(null, null, null)
        assertEquals("com.initial.app", service.lastActivePackageName)
        assertEquals("com.initial.Activity", service.lastActiveClassName)
        assertEquals("Initial", service.lastActiveWindowTitle)

        service.updateActiveWindowState("", "", emptyList())
        assertEquals("com.initial.app", service.lastActivePackageName)
        assertEquals("com.initial.Activity", service.lastActiveClassName)
        assertEquals("Initial", service.lastActiveWindowTitle)
    }

    // ---------------------------------------------------------------------
    // 4. Capture Failure and Exception Handling
    // ---------------------------------------------------------------------

    @Test
    fun testCaptureFailsWhenRootNodeIsNull() {
        val service = UICaptureAccessibilityService()
        service.rootNodeProvider = { null }
        service.mainDispatcher = { it() }

        var errorMessage: String? = null
        service.captureActiveScreen(
            takeScreenshot = false,
            onSuccess = { _, _ -> fail("Expected failure on null root node") },
            onError = { errorMessage = it }
        )

        assertNotNull(errorMessage)
        assertEquals("Active window root node is null or inaccessible.", errorMessage)
    }

    @Test
    fun testCaptureFailsWhenRootNodeRetrievalThrows() {
        val service = UICaptureAccessibilityService()
        service.rootNodeProvider = { throw RuntimeException("SecurityException: Permission denied") }
        service.mainDispatcher = { it() }

        var errorMessage: String? = null
        service.captureActiveScreen(
            takeScreenshot = false,
            onSuccess = { _, _ -> fail("Expected failure when root retrieval throws") },
            onError = { errorMessage = it }
        )

        assertNotNull(errorMessage)
        assertTrue(errorMessage!!.contains("Failed to retrieve active window root"))
        assertTrue(errorMessage!!.contains("Permission denied"))
    }

    @Test
    fun testRootNodeIsRecycledEvenWhenTreeTraversalFails() {
        val service = UICaptureAccessibilityService()
        val mockRoot = TestTreeNode(packageName = "com.fail.test")

        service.rootNodeProvider = { mockRoot }
        service.treeBuilder = { throw IllegalStateException("Simulated parsing crash") }
        service.mainDispatcher = { it() }

        var errorMessage: String? = null
        service.captureActiveScreen(
            takeScreenshot = false,
            onSuccess = { _, _ -> fail("Expected failure on tree traversal crash") },
            onError = { errorMessage = it }
        )

        assertNotNull(errorMessage)
        assertTrue(errorMessage!!.contains("Error traversing UI hierarchy"))
        assertTrue("Root node must be recycled even on failure", mockRoot.recycled)
    }

    // ---------------------------------------------------------------------
    // 5. Successful Hierarchy Capture and File Export
    // ---------------------------------------------------------------------

    @Test
    fun testSuccessfulCaptureHierarchyAndExportWithoutScreenshot() {
        val baseDir = tempFolder.newFolder("service_captures_no_screenshot")
        val service = UICaptureAccessibilityService()

        val child1 = TestTreeNode(
            packageName = "com.sample.target",
            className = "android.widget.TextView",
            text = "Welcome to Sample App",
            bounds = NodeBounds(50, 100, 500, 200)
        )
        val child2 = TestTreeNode(
            packageName = "com.sample.target",
            className = "android.widget.Button",
            text = "Submit",
            isClickable = true,
            bounds = NodeBounds(50, 300, 400, 450)
        )
        val mockRoot = TestTreeNode(
            packageName = "com.sample.target",
            className = "android.widget.FrameLayout",
            bounds = NodeBounds(0, 0, 1080, 2400),
            children = listOf(child1, child2)
        )

        service.rootNodeProvider = { mockRoot }
        service.hierarchyExporter = HierarchyExporter(baseDir)
        service.ioExecutor = Executor { it.run() } // Synchronous for JVM test
        service.mainDispatcher = { it() }
        service.lastActiveClassName = "com.sample.target.MainActivity"

        var capturedData: ScreenCaptureData? = null
        var exportedResult: ExportResult? = null
        var screenshotBitmap: android.graphics.Bitmap? = null

        service.captureActiveScreen(
            context = null,
            takeScreenshot = false,
            callback = object : CaptureCallback {
                override fun onSuccess(
                    data: ScreenCaptureData,
                    screenshot: android.graphics.Bitmap?,
                    exportResult: ExportResult
                ) {
                    capturedData = data
                    screenshotBitmap = screenshot
                    exportedResult = exportResult
                }

                override fun onError(errorMessage: String) {
                    fail("Capture should succeed: $errorMessage")
                }
            }
        )

        assertNotNull(capturedData)
        assertNotNull(exportedResult)
        assertNull(screenshotBitmap)

        val data = capturedData!!
        assertEquals("com.sample.target", data.packageName)
        assertEquals("com.sample.target.MainActivity", data.activityName)
        assertEquals("1080x2400", data.screenResolution)
        assertEquals(3, data.elementCount) // Root frame + 2 children
        assertEquals(1, data.interactiveElements.size) // Only Submit button is clickable

        // Verify root was recycled
        assertTrue(mockRoot.recycled)

        // Verify persisted files
        val res = exportedResult!!
        assertTrue("Target directory must exist", res.directory.exists())
        assertTrue("hierarchy.json must exist", res.hierarchyJsonFile.exists())
        assertTrue("hierarchy.xml must exist", res.hierarchyXmlFile.exists())
        assertTrue("summary.json must exist", res.summaryJsonFile.exists())
        assertNull("No screenshot file when takeScreenshot = false", res.screenshotFile)

        // Verify JSON content roundtrip
        val jsonText = res.hierarchyJsonFile.readText(Charsets.UTF_8)
        assertTrue(jsonText.contains("com.sample.target"))
        assertTrue(jsonText.contains("Welcome to Sample App"))
        assertTrue(jsonText.contains("Submit"))

        // Verify XML content
        val xmlText = res.hierarchyXmlFile.readText(Charsets.UTF_8)
        assertTrue(xmlText.contains("<hierarchy"))
        assertTrue(xmlText.contains("package=\"com.sample.target\""))
        assertTrue(xmlText.contains("text=\"Submit\""))
    }

    @Test
    fun testSuccessfulCaptureWithScreenshotMockProvider() {
        val baseDir = tempFolder.newFolder("service_captures_screenshot_mock")
        val service = UICaptureAccessibilityService()
        val mockRoot = TestTreeNode(packageName = "com.screenshot.test")

        service.rootNodeProvider = { mockRoot }
        service.hierarchyExporter = HierarchyExporter(baseDir)
        service.ioExecutor = Executor { it.run() }
        service.mainDispatcher = { it() }
        service.screenshotProvider = { callback ->
            // In unit tests, return null to verify the callback plumbing without native Bitmap
            callback(null)
        }

        var successCalled = false
        service.captureActiveScreen(
            takeScreenshot = true,
            onSuccess = { data, screenshot ->
                successCalled = true
                assertEquals("com.screenshot.test", data.packageName)
                assertNull(screenshot)
            },
            onError = { fail("Should not fail: $it") }
        )

        assertTrue(successCalled)
        assertTrue(mockRoot.recycled)
    }

    @Test
    fun testExportFailurePropagatesError() {
        val service = UICaptureAccessibilityService()
        val mockRoot = TestTreeNode(packageName = "com.test.invalid")

        // Exporter targeted at an uncreatable / invalid path
        val invalidDir = File("/proc/nonexistent_folder_abc_123/captures")
        service.rootNodeProvider = { mockRoot }
        service.hierarchyExporter = HierarchyExporter(invalidDir)
        service.ioExecutor = Executor { it.run() }
        service.mainDispatcher = { it() }

        var errorReported: String? = null
        service.captureActiveScreen(
            takeScreenshot = false,
            onSuccess = { _, _ -> fail("Should fail when export cannot write to disk") },
            onError = { errorReported = it }
        )

        assertNotNull(errorReported)
        assertTrue(errorReported!!.contains("Failed to export capture data"))
        assertTrue(mockRoot.recycled)
    }

    // ---------------------------------------------------------------------
    // 6. Companion Static Delegation
    // ---------------------------------------------------------------------

    @Test
    fun testTargetPackageResolutionFallsBackWhenRootIsOverlayPackage() {
        val baseDir = tempFolder.newFolder("overlay_root_fallback")
        val service = UICaptureAccessibilityService()
        val mockRoot = TestTreeNode(packageName = "com.uicapture.app")

        service.rootNodeProvider = { mockRoot }
        service.hierarchyExporter = HierarchyExporter(baseDir)
        service.ioExecutor = Executor { it.run() }
        service.mainDispatcher = { it() }
        service.lastActivePackageName = "com.target.foreground"

        var capturedPackage: String? = null
        service.captureActiveScreen(
            takeScreenshot = false,
            onSuccess = { data, _ ->
                capturedPackage = data.packageName
            },
            onError = { fail("Should not fail: $it") }
        )

        assertEquals("com.target.foreground", capturedPackage)
    }

    @Test
    fun testCompanionCaptureDelegatesToActiveInstance() {
        val baseDir = tempFolder.newFolder("companion_captures")
        val service = UICaptureAccessibilityService()
        val mockRoot = TestTreeNode(packageName = "com.companion.test")

        service.rootNodeProvider = { mockRoot }
        service.hierarchyExporter = HierarchyExporter(baseDir)
        service.ioExecutor = Executor { it.run() }
        service.mainDispatcher = { it() }

        UICaptureAccessibilityService.setInstanceForTesting(service)
        assertTrue(UICaptureAccessibilityService.isServiceRunning)

        var companionSuccess = false
        UICaptureAccessibilityService.captureActiveScreen(
            takeScreenshot = false,
            onSuccess = { data, _ ->
                companionSuccess = true
                assertEquals("com.companion.test", data.packageName)
            },
            onError = { fail("Companion capture should succeed: $it") }
        )

        assertTrue(companionSuccess)
    }

    @Test
    fun testPositionalTwoArgumentCaptureActiveScreenOnService() {
        val baseDir = tempFolder.newFolder("positional_service_captures")
        val service = UICaptureAccessibilityService()
        val mockRoot = TestTreeNode(packageName = "com.positional.service")

        service.rootNodeProvider = { mockRoot }
        service.hierarchyExporter = HierarchyExporter(baseDir)
        service.ioExecutor = Executor { it.run() }
        service.mainDispatcher = { it() }
        service.screenshotProvider = { callback -> callback(null) }

        var successCalled = false
        // Positional 2-argument call: captureActiveScreen({ data, bmp -> ... }, { err -> ... })
        service.captureActiveScreen(
            { data, screenshot ->
                successCalled = true
                assertEquals("com.positional.service", data.packageName)
                assertNull(screenshot)
            },
            { err ->
                fail("Positional capture on service should not fail: $err")
            }
        )

        assertTrue(successCalled)
    }

    @Test
    fun testPositionalTwoArgumentCaptureActiveScreenOnCompanion() {
        val baseDir = tempFolder.newFolder("positional_companion_captures")
        val service = UICaptureAccessibilityService()
        val mockRoot = TestTreeNode(packageName = "com.positional.companion")

        service.rootNodeProvider = { mockRoot }
        service.hierarchyExporter = HierarchyExporter(baseDir)
        service.ioExecutor = Executor { it.run() }
        service.mainDispatcher = { it() }
        service.screenshotProvider = { callback -> callback(null) }

        UICaptureAccessibilityService.setInstanceForTesting(service)

        var successCalled = false
        // Positional 2-argument call: UICaptureAccessibilityService.captureActiveScreen({ data, bmp -> ... }, { err -> ... })
        UICaptureAccessibilityService.captureActiveScreen(
            { data, screenshot ->
                successCalled = true
                assertEquals("com.positional.companion", data.packageName)
                assertNull(screenshot)
            },
            { err ->
                fail("Positional capture on companion should not fail: $err")
            }
        )

        assertTrue(successCalled)
    }

    // ---------------------------------------------------------------------
    // 7. CaptureCallback Factory Helper
    // ---------------------------------------------------------------------

    @Test
    fun testCaptureCallbackFactoryHelper() {
        var successData: ScreenCaptureData? = null
        var errorReported: String? = null

        val cb = captureCallback(
            onError = { errorReported = it },
            onSuccess = { data, _, _ -> successData = data }
        )

        cb.onError("Test error")
        assertEquals("Test error", errorReported)

        val testData = ScreenCaptureData(captureId = "test_1", packageName = "com.test")
        val dummyResult = ExportResult(
            directory = File("."),
            hierarchyJsonFile = File("."),
            hierarchyXmlFile = File("."),
            summaryJsonFile = File(".")
        )
        cb.onSuccess(testData, null, dummyResult)
        assertEquals("test_1", successData?.captureId)
    }
}
