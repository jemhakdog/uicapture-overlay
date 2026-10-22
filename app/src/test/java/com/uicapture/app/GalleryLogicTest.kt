package com.uicapture.app

import com.uicapture.app.engine.CaptureSummary
import com.uicapture.app.engine.HierarchyExporter
import com.uicapture.app.model.CaptureSessionItem
import com.uicapture.app.model.ScreenCaptureData
import com.uicapture.app.model.UIElement
import com.uicapture.app.util.GalleryLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

/**
 * JVM unit tests for [GalleryLogic] verifying capture directory scanning,
 * metadata summary parsing, fallback directory inference, timestamp formatting,
 * sorting, search filtering, and deletion operations.
 */
class GalleryLogicTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testScanNonExistentDirectoryReturnsEmptyList() {
        val nonExistent = File(tempFolder.root, "does_not_exist")
        val result = GalleryLogic.scanCapturesDirectory(nonExistent)
        assertTrue(result.isEmpty())
    }

    @Test
    fun testScanEmptyDirectoryReturnsEmptyList() {
        val emptyDir = tempFolder.newFolder("empty_captures")
        val result = GalleryLogic.scanCapturesDirectory(emptyDir)
        assertTrue(result.isEmpty())
    }

    @Test
    fun testScanDirectoryIgnoresRegularFiles() {
        val baseDir = tempFolder.newFolder("base_with_files")
        File(baseDir, "notes.txt").writeText("Hello")
        File(baseDir, "dummy.json").writeText("{}")

        val result = GalleryLogic.scanCapturesDirectory(baseDir)
        assertTrue(result.isEmpty())
    }

    @Test
    fun testParseValidCaptureDirectoryWithSummary() {
        val baseDir = tempFolder.newFolder("captures")
        val sessionDir = File(baseDir, "cap_1729567890000_com_example_target").apply { mkdirs() }

        val summary = CaptureSummary(
            captureId = "cap_1729567890000_com_example_target",
            timestamp = 1729567890000L,
            packageName = "com.example.target",
            activityName = "com.example.target.MainActivity",
            screenResolution = "1080x2400",
            elementCount = 42,
            interactiveCount = 12,
            screenshotFileName = "screenshot.png",
            hierarchyJsonFileName = "hierarchy.json",
            hierarchyXmlFileName = "hierarchy.xml"
        )

        File(sessionDir, HierarchyExporter.SUMMARY_JSON_NAME).writeText(
            HierarchyExporter.json.encodeToString(CaptureSummary.serializer(), summary)
        )
        val screenshotFile = File(sessionDir, "screenshot.png").apply { writeText("PNG_DATA") }
        val jsonFile = File(sessionDir, "hierarchy.json").apply { writeText("{}") }
        val xmlFile = File(sessionDir, "hierarchy.xml").apply { writeText("<hierarchy />") }

        val item = GalleryLogic.parseCaptureDirectory(sessionDir)
        assertNotNull(item)
        item!!
        assertEquals("cap_1729567890000_com_example_target", item.captureId)
        assertEquals(1729567890000L, item.timestamp)
        assertEquals("com.example.target", item.packageName)
        assertEquals("com.example.target.MainActivity", item.activityName)
        assertEquals("1080x2400", item.screenResolution)
        assertEquals(42, item.elementCount)
        assertEquals(12, item.interactiveCount)
        assertEquals(sessionDir.absolutePath, item.directory.absolutePath)
        assertEquals(screenshotFile.absolutePath, item.screenshotFile?.absolutePath)
        assertEquals(jsonFile.absolutePath, item.hierarchyJsonFile?.absolutePath)
        assertEquals(xmlFile.absolutePath, item.hierarchyXmlFile?.absolutePath)
        assertTrue(item.hasScreenshot)
        assertTrue(item.hasJson)
        assertTrue(item.hasXml)
        assertTrue(item.hasSummary)
    }

    @Test
    fun testParseCaptureDirectoryFallbackWithoutSummary() {
        val baseDir = tempFolder.newFolder("fallback_captures")
        val sessionDir = File(baseDir, "cap_1729567890000_com_example_fallback").apply { mkdirs() }

        val element = UIElement(
            id = "1",
            className = "android.widget.Button",
            text = "Click me",
            bounds = "[10,20][100,200]",
            center = Pair(55, 110),
            size = Pair(90, 180),
            clickable = true,
            action = "tap"
        )
        val captureData = ScreenCaptureData(
            captureId = "cap_fallback",
            timestamp = 1729567890000L,
            packageName = "com.example.fallback",
            activityName = "com.example.fallback.SettingsActivity",
            screenResolution = "1440x3120",
            elements = listOf(element),
            rawXml = "<hierarchy />"
        )
        File(sessionDir, HierarchyExporter.HIERARCHY_JSON_NAME).writeText(
            HierarchyExporter.json.encodeToString(ScreenCaptureData.serializer(), captureData)
        )
        File(sessionDir, HierarchyExporter.HIERARCHY_XML_NAME).writeText("<hierarchy />")

        val item = GalleryLogic.parseCaptureDirectory(sessionDir)
        assertNotNull(item)
        item!!
        assertEquals("cap_1729567890000_com_example_fallback", item.captureId)
        assertEquals(1729567890000L, item.timestamp)
        assertEquals("com.example.fallback", item.packageName)
        assertEquals("com.example.fallback.SettingsActivity", item.activityName)
        assertEquals("1440x3120", item.screenResolution)
        assertEquals(1, item.elementCount)
        assertEquals(1, item.interactiveCount)
        assertFalse(item.hasScreenshot)
        assertTrue(item.hasJson)
        assertTrue(item.hasXml)
        assertFalse(item.hasSummary)
    }

    @Test
    fun testParseDirectorySkipsUnrelatedFolders() {
        val baseDir = tempFolder.newFolder("unrelated_base")
        val randomDir = File(baseDir, "some_random_folder").apply { mkdirs() }
        File(randomDir, "other.txt").writeText("Not a capture")

        val item = GalleryLogic.parseCaptureDirectory(randomDir)
        assertNull(item)
    }

    @Test
    fun testScanSortsSessionsDescendingByTimestamp() {
        val baseDir = tempFolder.newFolder("sort_captures")

        fun createSession(name: String, timestamp: Long) {
            val dir = File(baseDir, name).apply { mkdirs() }
            val summary = CaptureSummary(
                captureId = name,
                timestamp = timestamp,
                packageName = "com.test",
                elementCount = 5,
                interactiveCount = 1
            )
            File(dir, HierarchyExporter.SUMMARY_JSON_NAME).writeText(
                HierarchyExporter.json.encodeToString(CaptureSummary.serializer(), summary)
            )
            File(dir, HierarchyExporter.HIERARCHY_JSON_NAME).writeText("{}")
        }

        createSession("cap_1000_test", 1000L)
        createSession("cap_3000_test", 3000L)
        createSession("cap_2000_test", 2000L)

        val scanned = GalleryLogic.scanCapturesDirectory(baseDir)
        assertEquals(3, scanned.size)
        assertEquals(3000L, scanned[0].timestamp)
        assertEquals(2000L, scanned[1].timestamp)
        assertEquals(1000L, scanned[2].timestamp)
    }

    @Test
    fun testParseTimestampFromDirName() {
        assertEquals(1729567890123L, GalleryLogic.parseTimestampFromDirName("cap_1729567890123_com_test"))
        assertEquals(500L, GalleryLogic.parseTimestampFromDirName("cap_500_pkg"))
        assertNull(GalleryLogic.parseTimestampFromDirName("invalid_dir_name"))
        assertNull(GalleryLogic.parseTimestampFromDirName("cap_notanumber_pkg"))
        assertNull(GalleryLogic.parseTimestampFromDirName("cap"))
    }

    @Test
    fun testParsePackageFromDirName() {
        assertEquals("com.android.settings", GalleryLogic.parsePackageFromDirName("cap_123_com_android_settings"))
        assertEquals("target", GalleryLogic.parsePackageFromDirName("cap_123_target"))
        assertNull(GalleryLogic.parsePackageFromDirName("cap_123"))
        assertNull(GalleryLogic.parsePackageFromDirName("not_cap_prefix"))
    }

    @Test
    fun testFilterSessionsByQuery() {
        val dummyDir = tempFolder.root
        val item1 = CaptureSessionItem(
            captureId = "cap_1_settings",
            timestamp = 1000L,
            packageName = "com.android.settings",
            activityName = "SettingsActivity",
            elementCount = 10,
            interactiveCount = 2,
            directory = dummyDir
        )
        val item2 = CaptureSessionItem(
            captureId = "cap_2_chrome",
            timestamp = 2000L,
            packageName = "com.android.chrome",
            activityName = "BrowserActivity",
            elementCount = 50,
            interactiveCount = 15,
            directory = dummyDir
        )
        val item3 = CaptureSessionItem(
            captureId = "cap_3_music",
            timestamp = 3000L,
            packageName = "com.spotify.music",
            activityName = "HomeActivity",
            elementCount = 30,
            interactiveCount = 8,
            directory = dummyDir
        )
        val all = listOf(item1, item2, item3)

        // Empty query returns all
        assertEquals(3, GalleryLogic.filterSessions(all, "").size)
        assertEquals(3, GalleryLogic.filterSessions(all, "   ").size)

        // Match package name
        val settingsResult = GalleryLogic.filterSessions(all, "settings")
        assertEquals(1, settingsResult.size)
        assertEquals("com.android.settings", settingsResult[0].packageName)

        // Match activity name
        val browserResult = GalleryLogic.filterSessions(all, "browser")
        assertEquals(1, browserResult.size)
        assertEquals("com.android.chrome", browserResult[0].packageName)

        // Case-insensitive query
        val chromeResult = GalleryLogic.filterSessions(all, "CHROME")
        assertEquals(1, chromeResult.size)
        assertEquals("com.android.chrome", chromeResult[0].packageName)

        // Match common substring
        val androidResult = GalleryLogic.filterSessions(all, "android")
        assertEquals(2, androidResult.size)

        // No match returns empty
        val noneResult = GalleryLogic.filterSessions(all, "xyznonexistent")
        assertTrue(noneResult.isEmpty())
    }

    @Test
    fun testFormatTimestamp() {
        val formatted = GalleryLogic.formatTimestamp(0L, "yyyy-MM-dd", Locale.US)
        assertNotNull(formatted)
        assertTrue(formatted.startsWith("1970"))
    }

    @Test
    fun testFormatRelativeTime() {
        val now = 1_000_000_000L

        // Future or exact match -> "Just now"
        assertEquals("Just now", GalleryLogic.formatRelativeTime(now + 5000L, now))
        assertEquals("Just now", GalleryLogic.formatRelativeTime(now, now))
        assertEquals("Just now", GalleryLogic.formatRelativeTime(now - 30_000L, now))

        // Minutes ago
        assertEquals("5m ago", GalleryLogic.formatRelativeTime(now - 5 * 60_000L, now))
        assertEquals("45m ago", GalleryLogic.formatRelativeTime(now - 45 * 60_000L, now))

        // Hours ago
        assertEquals("2h ago", GalleryLogic.formatRelativeTime(now - 2 * 3_600_000L, now))
        assertEquals("23h ago", GalleryLogic.formatRelativeTime(now - 23 * 3_600_000L, now))

        // Days ago
        assertEquals("2d ago", GalleryLogic.formatRelativeTime(now - 2 * 86_400_000L, now))
        assertEquals("6d ago", GalleryLogic.formatRelativeTime(now - 6 * 86_400_000L, now))
    }

    @Test
    fun testFormatFileSize() {
        assertEquals("500 B", GalleryLogic.formatFileSize(500L))
        assertEquals("1.0 KB", GalleryLogic.formatFileSize(1024L))
        assertEquals("1.5 KB", GalleryLogic.formatFileSize(1536L))
        assertEquals("2.0 MB", GalleryLogic.formatFileSize(2 * 1024 * 1024L))
    }

    @Test
    fun testGetDirectoryTotalSize() {
        val dir = tempFolder.newFolder("size_test")
        File(dir, "a.txt").writeText("12345") // 5 bytes
        File(dir, "b.txt").writeText("1234567890") // 10 bytes

        val totalSize = GalleryLogic.getDirectoryTotalSize(dir)
        assertEquals(15L, totalSize)
    }

    @Test
    fun testDeleteCaptureSession() {
        val baseDir = tempFolder.newFolder("delete_base")
        val sessionDir = File(baseDir, "cap_to_delete").apply { mkdirs() }
        File(sessionDir, "dummy.txt").writeText("payload")

        val item = CaptureSessionItem(
            captureId = "cap_to_delete",
            timestamp = 1234L,
            packageName = "com.delete.test",
            elementCount = 1,
            interactiveCount = 0,
            directory = sessionDir
        )

        assertTrue(sessionDir.exists())
        val deleted = GalleryLogic.deleteCaptureSession(item)
        assertTrue(deleted)
        assertFalse(sessionDir.exists())
    }

    @Test
    fun testCaptureSessionItemProperties() {
        val dir = tempFolder.newFolder("item_props")
        val screenshot = File(dir, "shot.png").apply { writeText("img") }
        val json = File(dir, "hierarchy.json").apply { writeText("{}") }
        val xml = File(dir, "hierarchy.xml").apply { writeText("<h/>") }
        val summary = File(dir, "summary.json").apply { writeText("{}") }

        val fullItem = CaptureSessionItem(
            captureId = "id_1",
            timestamp = 100L,
            packageName = "pkg",
            directory = dir,
            screenshotFile = screenshot,
            hierarchyJsonFile = json,
            hierarchyXmlFile = xml,
            summaryJsonFile = summary
        )
        assertTrue(fullItem.hasScreenshot)
        assertTrue(fullItem.hasJson)
        assertTrue(fullItem.hasXml)
        assertTrue(fullItem.hasSummary)

        val nonExistent = File(dir, "nonexistent.png")
        val emptyItem = CaptureSessionItem(
            captureId = "id_2",
            timestamp = 200L,
            packageName = "pkg",
            directory = dir,
            screenshotFile = nonExistent,
            hierarchyJsonFile = null,
            hierarchyXmlFile = null,
            summaryJsonFile = null
        )
        assertFalse(emptyItem.hasScreenshot)
        assertFalse(emptyItem.hasJson)
        assertFalse(emptyItem.hasXml)
        assertFalse(emptyItem.hasSummary)
    }

    @Test
    fun testAppScreenNavigationEnum() {
        val screens = com.uicapture.app.ui.AppScreen.values()
        assertEquals(2, screens.size)
        assertEquals("Dashboard", com.uicapture.app.ui.AppScreen.DASHBOARD.label)
        assertEquals("Gallery", com.uicapture.app.ui.AppScreen.GALLERY.label)
    }
}
