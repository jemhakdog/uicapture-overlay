package com.uicapture.app

import com.uicapture.app.model.ScreenCaptureData
import com.uicapture.app.model.UIElement
import com.uicapture.app.util.BoundsParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundsParserTest {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    // ---------------------------------------------------------
    // 1. BoundsParser Tests
    // ---------------------------------------------------------

    @Test
    fun testParseValidBounds() {
        val parsed = BoundsParser.parse("[0,0][1080,2400]")
        assertNotNull(parsed)
        assertEquals(0, parsed!!.left)
        assertEquals(0, parsed.top)
        assertEquals(1080, parsed.right)
        assertEquals(2400, parsed.bottom)
        assertEquals(1080, parsed.width)
        assertEquals(2400, parsed.height)
        assertEquals(Pair(540, 1200), parsed.center)
        assertEquals(Pair(1080, 2400), parsed.size)
        assertEquals("[0,0][1080,2400]", parsed.toBoundsString())
    }

    @Test
    fun testParseOddCoordinatesAndRounding() {
        val parsed = BoundsParser.parse("[1,3][6,8]")
        assertNotNull(parsed)
        assertEquals(5, parsed!!.width)
        assertEquals(5, parsed.height)
        // Center: ((1 + 6) / 2 = 3, (3 + 8) / 2 = 5)
        assertEquals(Pair(3, 5), parsed.center)
        assertEquals(Pair(5, 5), parsed.size)
    }

    @Test
    fun testBoundsParsingWithWhitespace() {
        val parsed = BoundsParser.parse(" [ 50 , 100 ] [ 250 , 400 ] ")
        assertNotNull(parsed)
        assertEquals(50, parsed!!.left)
        assertEquals(100, parsed.top)
        assertEquals(250, parsed.right)
        assertEquals(400, parsed.bottom)
        assertEquals(200, parsed.width)
        assertEquals(300, parsed.height)
        assertEquals(Pair(150, 250), parsed.center)
    }

    @Test
    fun testBoundsParsingNegativeCoordinates() {
        val parsed = BoundsParser.parse("[-100,-200][100,200]")
        assertNotNull(parsed)
        assertEquals(-100, parsed!!.left)
        assertEquals(-200, parsed.top)
        assertEquals(100, parsed.right)
        assertEquals(200, parsed.bottom)
        assertEquals(200, parsed.width)
        assertEquals(400, parsed.height)
        assertEquals(Pair(0, 0), parsed.center)
    }

    @Test
    fun testBoundsParserConvenienceMethods() {
        val bounds = "[100,200][300,400]"
        assertEquals(Pair(200, 300), BoundsParser.parseCenter(bounds))
        assertEquals(Pair(200, 200), BoundsParser.parseSize(bounds))
        assertEquals(200, BoundsParser.parseWidth(bounds))
        assertEquals(200, BoundsParser.parseHeight(bounds))
    }

    @Test
    fun testInvalidBoundsFallbacks() {
        assertNull(BoundsParser.parse(null))
        assertNull(BoundsParser.parse(""))
        assertNull(BoundsParser.parse("   "))
        assertNull(BoundsParser.parse("invalid"))
        assertNull(BoundsParser.parse("[10,20]"))
        assertNull(BoundsParser.parse("[10,20][30]"))
        assertNull(BoundsParser.parse("10,20,30,40"))
        assertNull(BoundsParser.parse("[a,b][c,d]"))

        // Null-safe convenience methods
        assertNull(BoundsParser.parseCenter(null))
        assertNull(BoundsParser.parseCenter("not_a_bounds"))
        assertNull(BoundsParser.parseSize(null))
        assertNull(BoundsParser.parseSize(""))
        assertNull(BoundsParser.parseWidth("malformed"))
        assertNull(BoundsParser.parseHeight("malformed"))
        assertFalse(BoundsParser.contains("malformed", 10, 10))
        assertFalse(BoundsParser.contains(null, 10, 10))
    }

    @Test
    fun testBoundsContainment() {
        val bounds = "[100,200][300,400]"

        // Strictly inside
        assertTrue(BoundsParser.contains(bounds, 200, 300))

        // Inclusive boundaries
        assertTrue(BoundsParser.contains(bounds, 100, 200)) // Top-left
        assertTrue(BoundsParser.contains(bounds, 300, 400)) // Bottom-right
        assertTrue(BoundsParser.contains(bounds, 100, 400)) // Bottom-left
        assertTrue(BoundsParser.contains(bounds, 300, 200)) // Top-right

        // Outside
        assertFalse(BoundsParser.contains(bounds, 99, 300)) // Left
        assertFalse(BoundsParser.contains(bounds, 301, 300)) // Right
        assertFalse(BoundsParser.contains(bounds, 200, 199)) // Top
        assertFalse(BoundsParser.contains(bounds, 200, 401)) // Bottom
    }

    @Test
    fun testBoundsFormat() {
        val formatted = BoundsParser.format(10, 20, 30, 40)
        assertEquals("[10,20][30,40]", formatted)

        val parsed = BoundsParser.parse(formatted)
        assertNotNull(parsed)
        assertEquals(10, parsed!!.left)
        assertEquals(20, parsed.top)
        assertEquals(30, parsed.right)
        assertEquals(40, parsed.bottom)
    }

    // ---------------------------------------------------------
    // 2. UIElement Model & Helper Tests
    // ---------------------------------------------------------

    @Test
    fun testUIElementDefaults() {
        val elem = UIElement()
        assertNull(elem.id)
        assertEquals("", elem.className)
        assertEquals("", elem.text)
        assertEquals("", elem.contentDesc)
        assertEquals("", elem.bounds)
        assertNull(elem.center)
        assertNull(elem.size)
        assertFalse(elem.clickable)
        assertFalse(elem.scrollable)
        assertFalse(elem.editable)
        assertFalse(elem.checkable)
        assertFalse(elem.checked)
        assertFalse(elem.focused)
        assertFalse(elem.selected)
        assertEquals(0, elem.depth)
        assertNull(elem.action)
        assertEquals("", elem.label)
        assertFalse(elem.isInteractive)
    }

    @Test
    fun testUIElementLabelPrecedence() {
        val withTextAndDesc = UIElement(text = "Hello", contentDesc = "Greeting")
        assertEquals("Hello", withTextAndDesc.label)

        val withDescOnly = UIElement(text = "", contentDesc = "Greeting")
        assertEquals("Greeting", withDescOnly.label)

        val withWhitespaceText = UIElement(text = "   ", contentDesc = "Greeting")
        assertEquals("Greeting", withWhitespaceText.label)

        val withEmptyBoth = UIElement(text = "", contentDesc = "")
        assertEquals("", withEmptyBoth.label)
    }

    @Test
    fun testUIElementInteractivity() {
        assertFalse(UIElement().isInteractive)
        assertTrue(UIElement(clickable = true).isInteractive)
        assertTrue(UIElement(scrollable = true).isInteractive)
        assertTrue(UIElement(editable = true).isInteractive)
        assertTrue(UIElement(checkable = true).isInteractive)
    }

    @Test
    fun testUIElementResolvedBounds() {
        val elem = UIElement(bounds = "[100,200][300,400]")
        assertEquals(Pair(200, 300), elem.resolvedCenter)
        assertEquals(Pair(200, 200), elem.resolvedSize)
        assertEquals(200, elem.width)
        assertEquals(200, elem.height)
        assertTrue(elem.contains(200, 300))
        assertFalse(elem.contains(50, 50))
    }

    @Test
    fun testUIElementFromBoundsFactory() {
        val elem = UIElement.fromBounds(
            bounds = "[50,100][250,500]",
            id = "btn_submit",
            className = "android.widget.Button",
            text = "Submit",
            clickable = true,
            action = "tap"
        )
        assertEquals("btn_submit", elem.id)
        assertEquals("btn_submit", elem.resourceId)
        assertEquals("android.widget.Button", elem.className)
        assertEquals("Submit", elem.text)
        assertEquals("Submit", elem.label)
        assertEquals(Pair(150, 300), elem.center)
        assertEquals(Pair(200, 400), elem.size)
        assertTrue(elem.clickable)
        assertEquals("tap", elem.action)
    }

    @Test
    fun testUIElementToAicoreMap() {
        val elem = UIElement(
            id = "com.app:id/submit",
            className = "android.widget.Button",
            text = "Apply Now",
            contentDesc = "Job Application Button",
            bounds = "[100,200][500,300]",
            center = Pair(300, 250),
            clickable = true,
            depth = 2,
            action = "tap"
        )
        val map = elem.toAicoreMap()
        assertEquals("android.widget.Button", map["class"])
        assertEquals("Apply Now", map["text"])
        assertEquals("Job Application Button", map["content_desc"])
        assertEquals("com.app:id/submit", map["resource_id"])
        assertEquals(true, map["clickable"])
        assertEquals(listOf(300, 250), map["center"])
        assertEquals("[100,200][500,300]", map["bounds"])
        assertEquals("tap", map["action"])
    }

    // ---------------------------------------------------------
    // 3. Serialization and Deserialization Tests
    // ---------------------------------------------------------

    @Test
    fun testUIElementJsonRoundtrip() {
        val original = UIElement(
            id = "com.test:id/nav_btn",
            className = "android.widget.ImageButton",
            text = "Back",
            contentDesc = "Navigate Up",
            bounds = "[0,100][120,220]",
            center = Pair(60, 160),
            size = Pair(120, 120),
            clickable = true,
            scrollable = false,
            editable = false,
            checkable = false,
            checked = false,
            focused = true,
            selected = false,
            depth = 3,
            action = "tap"
        )

        val serialized = json.encodeToString(UIElement.serializer(), original)
        assertTrue(serialized.contains("\"id\":\"com.test:id/nav_btn\""))
        assertTrue(serialized.contains("\"className\":\"android.widget.ImageButton\""))
        assertTrue(serialized.contains("\"center\":[60,160]"))
        assertTrue(serialized.contains("\"size\":[120,120]"))

        val deserialized = json.decodeFromString(UIElement.serializer(), serialized)
        assertEquals(original, deserialized)
    }

    @Test
    fun testUIElementDeserializationFromAicoreJsonFormat() {
        // Simulates JSON exported by aicore_bridge.py or andorid-mcp with snake_case keys
        val aicoreJson = """
            {
                "class": "android.widget.TextView",
                "resource_id": "com.linkedin.android:id/headline",
                "text": "Senior Android Engineer",
                "content_desc": "Headline description",
                "bounds": "[54,320][1026,450]",
                "center": [540, 385],
                "size": [972, 130],
                "clickable": true
            }
        """.trimIndent()

        val element = json.decodeFromString(UIElement.serializer(), aicoreJson)
        assertEquals("com.linkedin.android:id/headline", element.id)
        assertEquals("com.linkedin.android:id/headline", element.resourceId)
        assertEquals("android.widget.TextView", element.className)
        assertEquals("Senior Android Engineer", element.text)
        assertEquals("Headline description", element.contentDesc)
        assertEquals("Senior Android Engineer", element.label)
        assertEquals(Pair(540, 385), element.center)
        assertEquals(Pair(972, 130), element.size)
        assertTrue(element.clickable)
    }

    @Test
    fun testUIElementDeserializationWithObjectCenter() {
        // Verifies deserialization tolerance when center is formatted as {"first": x, "second": y}
        val objectCenterJson = """
            {
                "className": "android.view.View",
                "bounds": "[10,20][30,40]",
                "center": {"first": 20, "second": 30},
                "size": {"width": 20, "height": 20}
            }
        """.trimIndent()

        val element = json.decodeFromString(UIElement.serializer(), objectCenterJson)
        assertEquals(Pair(20, 30), element.center)
        assertEquals(Pair(20, 20), element.size)
    }

    // ---------------------------------------------------------
    // 4. ScreenCaptureData Model & Query Tests
    // ---------------------------------------------------------

    @Test
    fun testScreenCaptureDataQueries() {
        val root = UIElement(
            className = "android.widget.FrameLayout",
            bounds = "[0,0][1080,2400]",
            depth = 0
        )
        val button = UIElement(
            id = "com.app:id/submit_button",
            className = "android.widget.Button",
            text = "Submit Application",
            bounds = "[100,500][980,620]",
            clickable = true,
            depth = 1,
            action = "tap"
        )
        val leaf = UIElement(
            id = "com.app:id/submit_label",
            className = "android.widget.TextView",
            text = "Submit",
            bounds = "[400,530][680,590]",
            depth = 2
        )

        val capture = ScreenCaptureData(
            captureId = "cap_1729567890",
            timestamp = 1729567890000L,
            packageName = "com.jobstreet.app",
            activityName = "JobDetailActivity",
            screenResolution = "1080x2400",
            elements = listOf(root, button, leaf),
            rawXml = "<hierarchy><node/></hierarchy>",
            screenshotPath = "/sdcard/Download/UICaptures/cap_1729567890/screenshot.png"
        )

        assertEquals(3, capture.elementCount)
        assertEquals(1, capture.interactiveElements.size)
        assertEquals(button, capture.interactiveElements.first())
        assertEquals("cap_1729567890000_com_jobstreet_app", capture.directoryName)

        // findByText
        val searchSubmit = capture.findByText("Submit")
        assertEquals(2, searchSubmit.size)
        assertTrue(searchSubmit.contains(button))
        assertTrue(searchSubmit.contains(leaf))

        // findById
        val searchByIdFull = capture.findById("com.app:id/submit_button")
        assertEquals(1, searchByIdFull.size)
        assertEquals(button, searchByIdFull.first())

        val searchByIdShort = capture.findById("submit_button")
        assertEquals(1, searchByIdShort.size)
        assertEquals(button, searchByIdShort.first())

        // findElementAt: (500, 560) is covered by all 3; should return the deepest leaf (depth 2)
        val hit = capture.findElementAt(500, 560)
        assertNotNull(hit)
        assertEquals(leaf, hit)

        // Point outside leaf but inside button (150, 520)
        val hitButton = capture.findElementAt(150, 520)
        assertEquals(button, hitButton)

        // Point outside everything
        val hitNone = capture.findElementAt(5000, 5000)
        assertNull(hitNone)
    }

    @Test
    fun testScreenCaptureDataJsonSerialization() {
        val element = UIElement(
            id = "btn_ok",
            className = "android.widget.Button",
            text = "OK",
            bounds = "[10,20][100,120]",
            center = Pair(55, 70),
            clickable = true
        )
        val capture = ScreenCaptureData(
            captureId = "test_cap_001",
            timestamp = 1700000000000L,
            packageName = "com.test.pkg",
            elements = listOf(element)
        )

        val serialized = json.encodeToString(ScreenCaptureData.serializer(), capture)
        assertTrue(serialized.contains("\"captureId\":\"test_cap_001\""))
        assertTrue(serialized.contains("\"packageName\":\"com.test.pkg\""))
        assertTrue(serialized.contains("\"elements\":["))

        val deserialized = json.decodeFromString(ScreenCaptureData.serializer(), serialized)
        assertEquals(capture.captureId, deserialized.captureId)
        assertEquals(capture.timestamp, deserialized.timestamp)
        assertEquals(capture.packageName, deserialized.packageName)
        assertNull(deserialized.activityName)
        assertNull(deserialized.rawXml)
        assertNull(deserialized.screenshotPath)
        assertEquals(1, deserialized.elements.size)
        assertEquals("btn_ok", deserialized.elements[0].id)
    }
}
