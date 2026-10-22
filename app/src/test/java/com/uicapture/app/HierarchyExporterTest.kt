package com.uicapture.app

import com.uicapture.app.engine.CaptureSummary
import com.uicapture.app.engine.HierarchyExporter
import com.uicapture.app.engine.NodeBounds
import com.uicapture.app.engine.ScreenTreeBuilder
import com.uicapture.app.engine.TreeNodeInfo
import com.uicapture.app.model.ScreenCaptureData
import com.uicapture.app.model.UIElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Unit tests verifying [ScreenTreeBuilder] and [HierarchyExporter] logic:
 * - Action classification hierarchy
 * - XML entity escaping and invalid control character sanitization
 * - Tree traversal, overlay package filtering, and safe node recycling
 * - JSON and XML serialization formatting
 * - Target directory structure and lightweight summary generation
 */
class HierarchyExporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * In-memory implementation of [TreeNodeInfo] for deterministic unit testing.
     */
    class MockTreeNode(
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
        val bounds: NodeBounds = NodeBounds(0, 0, 100, 100),
        val children: List<MockTreeNode> = emptyList()
    ) : TreeNodeInfo {
        var recycled: Boolean = false

        override val childCount: Int
            get() = children.size

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

    // ---------------------------------------------------------
    // 1. Action Classification Tests
    // ---------------------------------------------------------

    @Test
    fun testActionClassification() {
        // 1. Editable -> "type"
        assertEquals(
            ScreenTreeBuilder.ACTION_TYPE,
            ScreenTreeBuilder.determineAction(
                isEditable = true,
                isScrollable = false,
                isLongClickable = false,
                isClickable = false
            )
        )
        // Editable takes precedence even if clickable and longClickable
        assertEquals(
            ScreenTreeBuilder.ACTION_TYPE,
            ScreenTreeBuilder.determineAction(
                isEditable = true,
                isScrollable = true,
                isLongClickable = true,
                isClickable = true
            )
        )

        // 2. Scrollable -> "scroll"
        assertEquals(
            ScreenTreeBuilder.ACTION_SCROLL,
            ScreenTreeBuilder.determineAction(
                isEditable = false,
                isScrollable = true,
                isLongClickable = false,
                isClickable = false
            )
        )
        // Scrollable takes precedence over click/longpress
        assertEquals(
            ScreenTreeBuilder.ACTION_SCROLL,
            ScreenTreeBuilder.determineAction(
                isEditable = false,
                isScrollable = true,
                isLongClickable = true,
                isClickable = true
            )
        )

        // 3. Long-clickable -> "longpress"
        assertEquals(
            ScreenTreeBuilder.ACTION_LONGPRESS,
            ScreenTreeBuilder.determineAction(
                isEditable = false,
                isScrollable = false,
                isLongClickable = true,
                isClickable = false
            )
        )

        // 4. Clickable -> "tap"
        assertEquals(
            ScreenTreeBuilder.ACTION_TAP,
            ScreenTreeBuilder.determineAction(
                isEditable = false,
                isScrollable = false,
                isLongClickable = false,
                isClickable = true
            )
        )

        // 5. Non-interactive -> "read"
        assertEquals(
            ScreenTreeBuilder.ACTION_READ,
            ScreenTreeBuilder.determineAction(
                isEditable = false,
                isScrollable = false,
                isLongClickable = false,
                isClickable = false
            )
        )
    }

    // ---------------------------------------------------------
    // 2. XML Sanitization & Entity Escaping Tests
    // ---------------------------------------------------------

    @Test
    fun testXmlSanitizationAndEscaping() {
        assertEquals("", ScreenTreeBuilder.escapeXml(null))
        assertEquals("", ScreenTreeBuilder.escapeXml(""))

        // Standard entity replacement
        val rawSpecial = """Fish & Chips <Salt> "Pepper" 'Vinegar'"""
        val expectedSpecial = "Fish &amp; Chips &lt;Salt&gt; &quot;Pepper&quot; &apos;Vinegar&apos;"
        assertEquals(expectedSpecial, ScreenTreeBuilder.escapeXml(rawSpecial))

        // Multiple consecutive ampersands
        assertEquals("&amp;&amp;&amp;", ScreenTreeBuilder.escapeXml("&&&"))

        // Control characters stripped (ASCII 0..8, 11, 12, 14..31)
        val dirtyString = "Clean\u0000Text\u0007With\u001FControls"
        assertEquals("CleanTextWithControls", ScreenTreeBuilder.escapeXml(dirtyString))

        // Preserves allowed whitespace: \t, \n, \r
        val allowedWhitespace = "Line 1\nLine 2\tTabbed\r"
        assertEquals("Line 1\nLine 2\tTabbed\r", ScreenTreeBuilder.escapeXml(allowedWhitespace))

        // Unicode supplementary emojis preserved
        val emojiString = "Capture \uD83D\uDCF8 Target" // 📸
        assertEquals(emojiString, ScreenTreeBuilder.escapeXml(emojiString))
    }

    // ---------------------------------------------------------
    // 3. Tree Traversal, Overlay Filtering, and Recycling Tests
    // ---------------------------------------------------------

    @Test
    fun testScreenTreeBuilderWithMockHierarchy() {
        val childText = MockTreeNode(
            className = "android.widget.TextView",
            text = "Welcome & Enjoy! <v1.0>",
            bounds = NodeBounds(50, 100, 500, 200),
            viewIdResourceName = "com.example.target:id/welcome_text"
        )
        val childInput = MockTreeNode(
            className = "android.widget.EditText",
            text = "Search \"shoes\"",
            isEditable = true,
            isClickable = true,
            bounds = NodeBounds(50, 220, 500, 320),
            viewIdResourceName = "com.example.target:id/search_box"
        )
        val childBtn = MockTreeNode(
            className = "android.widget.Button",
            text = "Submit",
            isClickable = true,
            bounds = NodeBounds(100, 350, 400, 450),
            viewIdResourceName = "com.example.target:id/submit_btn"
        )
        val childScroll = MockTreeNode(
            className = "android.widget.ScrollView",
            isScrollable = true,
            bounds = NodeBounds(0, 500, 1080, 2000)
        )

        // Overlay node from our app - must be completely filtered out
        val overlayChild = MockTreeNode(
            packageName = ScreenTreeBuilder.OVERLAY_PACKAGE,
            className = "android.widget.ImageView",
            bounds = NodeBounds(900, 100, 1000, 200)
        )
        val overlayNode = MockTreeNode(
            packageName = ScreenTreeBuilder.OVERLAY_PACKAGE,
            className = "com.uicapture.app.overlay.FloatingOverlay",
            bounds = NodeBounds(850, 50, 1050, 250),
            children = listOf(overlayChild)
        )

        val rootNode = MockTreeNode(
            packageName = "com.example.target",
            className = "android.widget.FrameLayout",
            bounds = NodeBounds(0, 0, 1080, 2400),
            children = listOf(childText, childInput, childBtn, childScroll, overlayNode)
        )

        val result = ScreenTreeBuilder.build(rootNode)

        // Verify elements: root + 4 children = 5 elements (overlayNode and its child are skipped)
        assertEquals(5, result.elements.size)

        // Root element
        val elemRoot = result.elements[0]
        assertEquals("android.widget.FrameLayout", elemRoot.className)
        assertEquals(0, elemRoot.depth)
        assertEquals("[0,0][1080,2400]", elemRoot.bounds)
        assertEquals(Pair(540, 1200), elemRoot.center)

        // Child Text
        val elemText = result.elements[1]
        assertEquals("com.example.target:id/welcome_text", elemText.id)
        assertEquals("Welcome & Enjoy! <v1.0>", elemText.text)
        assertEquals("read", elemText.action)
        assertEquals(1, elemText.depth)

        // Child Input
        val elemInput = result.elements[2]
        assertEquals("com.example.target:id/search_box", elemInput.id)
        assertEquals("type", elemInput.action)
        assertTrue(elemInput.editable)

        // Child Button
        val elemBtn = result.elements[3]
        assertEquals("tap", elemBtn.action)
        assertTrue(elemBtn.clickable)

        // Child Scroll
        val elemScroll = result.elements[4]
        assertEquals("scroll", elemScroll.action)
        assertTrue(elemScroll.scrollable)

        // Overlay verification: neither overlayNode nor overlayChild should be present in elements
        assertFalse(result.elements.any { it.className.contains("FloatingOverlay") })
        assertFalse(result.elements.any { it.bounds == "[900,100][1000,200]" })

        // Raw XML checks
        assertTrue(result.rawXml.startsWith("<hierarchy rotation=\"0\">"))
        assertTrue(result.rawXml.endsWith("</hierarchy>"))
        assertTrue(result.rawXml.contains("text=\"Welcome &amp; Enjoy! &lt;v1.0&gt;\""))
        assertTrue(result.rawXml.contains("text=\"Search &quot;shoes&quot;\""))
        assertFalse(result.rawXml.contains("FloatingOverlay"))
        assertFalse(result.rawXml.contains(ScreenTreeBuilder.OVERLAY_PACKAGE))

        // Safe recycling verification: all child nodes including overlay nodes must be recycled
        assertTrue(childText.recycled)
        assertTrue(childInput.recycled)
        assertTrue(childBtn.recycled)
        assertTrue(childScroll.recycled)
        assertTrue(overlayNode.recycled)
    }

    @Test
    fun testEmptyAndNullRootTraversal() {
        val nullResult = ScreenTreeBuilder.build(null as TreeNodeInfo?)
        assertTrue(nullResult.elements.isEmpty())
        assertEquals("<hierarchy rotation=\"0\" />", nullResult.rawXml)

        // Single empty leaf node
        val leaf = MockTreeNode(
            packageName = "com.example.app",
            className = "android.view.View",
            bounds = NodeBounds(10, 20, 30, 40)
        )
        val leafResult = ScreenTreeBuilder.build(leaf)
        assertEquals(1, leafResult.elements.size)
        assertTrue(leafResult.rawXml.contains("<node index=\"0\""))
        assertTrue(leafResult.rawXml.contains("/>"))
    }

    // ---------------------------------------------------------
    // 4. Target Directory Path Generation Tests
    // ---------------------------------------------------------

    @Test
    fun testTargetDirectoryGeneration() {
        val baseDir = File(tempFolder.root, "test_captures")
        val exporter = HierarchyExporter(baseDir)

        val target = exporter.getTargetDirectory(
            timestamp = 1729560000000L,
            packageName = "com.google.android.youtube"
        )

        assertEquals(
            File(baseDir, "cap_1729560000000_com_google_android_youtube").canonicalPath,
            target.canonicalPath
        )

        // Edge case: empty package name
        val unknownTarget = exporter.getTargetDirectory(
            timestamp = 1729560000000L,
            packageName = ""
        )
        assertEquals(
            File(baseDir, "cap_1729560000000_unknown").canonicalPath,
            unknownTarget.canonicalPath
        )
    }

    // ---------------------------------------------------------
    // 5. Complete File Export Tests (JSON, XML, Summary)
    // ---------------------------------------------------------

    @Test
    fun testHierarchyExporterFullExport() {
        val baseDir = tempFolder.newFolder("output_captures")
        val exporter = HierarchyExporter(baseDir)

        val elem1 = UIElement(
            id = "title_text",
            className = "android.widget.TextView",
            text = "Welcome & Explore",
            bounds = "[50,100][500,200]",
            center = Pair(275, 150),
            size = Pair(450, 100),
            action = "read"
        )
        val elem2 = UIElement(
            id = "action_btn",
            className = "android.widget.Button",
            text = "Click",
            bounds = "[100,300][400,400]",
            center = Pair(250, 350),
            size = Pair(300, 100),
            clickable = true,
            action = "tap"
        )

        val sampleXml = buildString {
            append("<hierarchy rotation=\"0\">\n")
            append("  <node index=\"0\" text=\"Welcome &amp; Explore\" resource-id=\"title_text\" class=\"android.widget.TextView\" package=\"com.test.target\" bounds=\"[50,100][500,200]\" />\n")
            append("  <node index=\"1\" text=\"Click\" resource-id=\"action_btn\" class=\"android.widget.Button\" package=\"com.test.target\" bounds=\"[100,300][400,400]\" />\n")
            append("</hierarchy>")
        }

        val captureData = ScreenCaptureData(
            captureId = "cap_test_12345",
            timestamp = 1729560000000L,
            packageName = "com.test.target",
            activityName = "com.test.target.MainActivity",
            screenResolution = "1080x2400",
            elements = listOf(elem1, elem2),
            rawXml = sampleXml
        )

        val result = exporter.export(captureData)

        // 1. Directory created
        assertTrue(result.directory.exists())
        assertTrue(result.directory.isDirectory)
        assertEquals("cap_1729560000000_com_test_target", result.directory.name)

        // 2. hierarchy.json verification
        assertTrue(result.hierarchyJsonFile.exists())
        val jsonText = result.hierarchyJsonFile.readText(Charsets.UTF_8)
        assertTrue(jsonText.contains("\"captureId\": \"cap_test_12345\""))
        assertTrue(jsonText.contains("\"packageName\": \"com.test.target\""))
        assertTrue(jsonText.contains("\"action_btn\""))

        // Deserialize back to ensure JSON schema fidelity
        val deserialized = HierarchyExporter.json.decodeFromString(
            ScreenCaptureData.serializer(),
            jsonText
        )
        assertEquals("cap_test_12345", deserialized.captureId)
        assertEquals(2, deserialized.elements.size)
        assertEquals("action_btn", deserialized.elements[1].id)
        assertEquals("tap", deserialized.elements[1].action)

        // 3. hierarchy.xml verification
        assertTrue(result.hierarchyXmlFile.exists())
        val xmlText = result.hierarchyXmlFile.readText(Charsets.UTF_8)
        assertTrue(xmlText.startsWith("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>"))
        assertTrue(xmlText.contains("<hierarchy rotation=\"0\">"))
        assertTrue(xmlText.contains("resource-id=\"action_btn\""))

        // Validate XML well-formedness with DOM parser
        val docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val doc = docBuilder.parse(ByteArrayInputStream(xmlText.toByteArray(Charsets.UTF_8)))
        assertNotNull(doc)
        assertEquals("hierarchy", doc.documentElement.tagName)

        // 4. summary.json verification
        assertTrue(result.summaryJsonFile.exists())
        val summaryText = result.summaryJsonFile.readText(Charsets.UTF_8)
        val summary = HierarchyExporter.json.decodeFromString(
            CaptureSummary.serializer(),
            summaryText
        )
        assertEquals("cap_test_12345", summary.captureId)
        assertEquals(1729560000000L, summary.timestamp)
        assertEquals("com.test.target", summary.packageName)
        assertEquals("com.test.target.MainActivity", summary.activityName)
        assertEquals("1080x2400", summary.screenResolution)
        assertEquals(2, summary.elementCount)
        assertEquals(1, summary.interactiveCount) // only elem2 is interactive (clickable)
        assertNull(summary.screenshotFileName)
        assertEquals("hierarchy.json", summary.hierarchyJsonFileName)
        assertEquals("hierarchy.xml", summary.hierarchyXmlFileName)
    }

    // ---------------------------------------------------------
    // 6. Screenshot Stream & File Association Tests
    // ---------------------------------------------------------

    @Test
    fun testScreenshotStreamSavingAndSummaryAssociation() {
        val baseDir = tempFolder.newFolder("output_screenshot")
        val exporter = HierarchyExporter(baseDir)
        val targetDir = exporter.getTargetDirectory(1729560000000L, "com.screenshot.test")

        // Save a mock PNG screenshot via saveScreenshotStream
        val mockPngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val screenshotFile = exporter.saveScreenshotStream(targetDir) { out ->
            out.write(mockPngBytes)
        }

        assertTrue(screenshotFile.exists())
        assertEquals(HierarchyExporter.SCREENSHOT_PNG_NAME, screenshotFile.name)
        assertEquals(8, screenshotFile.length())

        // Export data referencing the saved screenshot
        val data = ScreenCaptureData(
            captureId = "cap_screen_001",
            timestamp = 1729560000000L,
            packageName = "com.screenshot.test",
            screenshotPath = screenshotFile.absolutePath,
            elements = emptyList()
        )

        val result = exporter.export(data)
        assertEquals(screenshotFile.absolutePath, result.screenshotFile?.absolutePath)

        val summaryText = result.summaryJsonFile.readText(Charsets.UTF_8)
        val summary = HierarchyExporter.json.decodeFromString(
            CaptureSummary.serializer(),
            summaryText
        )
        assertEquals("screenshot.png", summary.screenshotFileName)
    }
}
