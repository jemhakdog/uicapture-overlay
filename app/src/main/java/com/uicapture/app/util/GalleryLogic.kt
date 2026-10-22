package com.uicapture.app.util

import com.uicapture.app.engine.CaptureSummary
import com.uicapture.app.engine.HierarchyExporter
import com.uicapture.app.model.CaptureSessionItem
import com.uicapture.app.model.ScreenCaptureData
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure JVM business logic for scanning, parsing, sorting, filtering, and formatting
 * capture sessions stored on disk under `Download/UICaptures/`.
 */
object GalleryLogic {

    /**
     * Scans the base directory for capture sessions and returns them sorted by timestamp descending.
     */
    fun scanCapturesDirectory(baseDir: File): List<CaptureSessionItem> {
        if (!baseDir.exists() || !baseDir.isDirectory) {
            return emptyList()
        }

        val entries = baseDir.listFiles() ?: return emptyList()
        return entries
            .filter { it.isDirectory }
            .mapNotNull { parseCaptureDirectory(it) }
            .sortedByDescending { it.timestamp }
    }

    /**
     * Parses a single session directory into a [CaptureSessionItem].
     * Prefers reading `summary.json` for fast indexing, with safe fallback parsing from directory
     * naming and contents if summary is missing or corrupt.
     */
    fun parseCaptureDirectory(dir: File): CaptureSessionItem? {
        if (!dir.exists() || !dir.isDirectory) return null

        val summaryFile = File(dir, HierarchyExporter.SUMMARY_JSON_NAME)
        if (summaryFile.exists() && summaryFile.length() > 0) {
            try {
                val jsonString = summaryFile.readText(Charsets.UTF_8)
                val summary = HierarchyExporter.json.decodeFromString<CaptureSummary>(jsonString)
                val screenshotFile = summary.screenshotFileName?.let { File(dir, it) }
                    ?: File(dir, HierarchyExporter.SCREENSHOT_PNG_NAME)
                val jsonFile = File(dir, summary.hierarchyJsonFileName)
                val xmlFile = File(dir, summary.hierarchyXmlFileName)

                return CaptureSessionItem(
                    captureId = summary.captureId,
                    timestamp = summary.timestamp,
                    packageName = summary.packageName,
                    activityName = summary.activityName,
                    screenResolution = summary.screenResolution,
                    elementCount = summary.elementCount,
                    interactiveCount = summary.interactiveCount,
                    directory = dir,
                    screenshotFile = screenshotFile.takeIf { it.exists() },
                    hierarchyJsonFile = jsonFile.takeIf { it.exists() },
                    hierarchyXmlFile = xmlFile.takeIf { it.exists() },
                    summaryJsonFile = summaryFile
                )
            } catch (_: Throwable) {
                // Fall through to fallback parser
            }
        }

        // Fallback: Infer from directory contents and naming convention (cap_<timestamp>_<pkg>)
        val screenshotFile = File(dir, HierarchyExporter.SCREENSHOT_PNG_NAME).takeIf { it.exists() }
        val jsonFile = File(dir, HierarchyExporter.HIERARCHY_JSON_NAME).takeIf { it.exists() }
        val xmlFile = File(dir, HierarchyExporter.HIERARCHY_XML_NAME).takeIf { it.exists() }

        // If no relevant capture files exist in directory, skip
        if (screenshotFile == null && jsonFile == null && xmlFile == null) {
            return null
        }

        val dirTimestamp = parseTimestampFromDirName(dir.name) ?: dir.lastModified()
        val dirPackage = parsePackageFromDirName(dir.name) ?: "unknown"

        var elementCount = 0
        var interactiveCount = 0
        var activityName: String? = null
        var screenResolution: String? = null

        if (jsonFile != null) {
            try {
                val data = HierarchyExporter.json.decodeFromString<ScreenCaptureData>(
                    jsonFile.readText(Charsets.UTF_8)
                )
                elementCount = data.elementCount
                interactiveCount = data.interactiveElements.size
                activityName = data.activityName
                screenResolution = data.screenResolution
            } catch (_: Throwable) {
                // Safe ignore
            }
        }

        return CaptureSessionItem(
            captureId = dir.name,
            timestamp = dirTimestamp,
            packageName = dirPackage,
            activityName = activityName,
            screenResolution = screenResolution,
            elementCount = elementCount,
            interactiveCount = interactiveCount,
            directory = dir,
            screenshotFile = screenshotFile,
            hierarchyJsonFile = jsonFile,
            hierarchyXmlFile = xmlFile,
            summaryJsonFile = null
        )
    }

    /**
     * Extracts unix epoch timestamp from directory name formatted as `cap_<timestamp>_<pkg>`.
     */
    fun parseTimestampFromDirName(dirName: String): Long? {
        val parts = dirName.split("_")
        if (parts.size >= 2 && parts[0] == "cap") {
            return parts[1].toLongOrNull()
        }
        return null
    }

    /**
     * Extracts package name from directory name formatted as `cap_<timestamp>_<pkg>`.
     */
    fun parsePackageFromDirName(dirName: String): String? {
        val parts = dirName.split("_")
        if (parts.size >= 3 && parts[0] == "cap") {
            return parts.drop(2).joinToString(".")
        }
        return null
    }

    /**
     * Filters list of sessions matching query against packageName, activityName, or captureId.
     */
    fun filterSessions(items: List<CaptureSessionItem>, query: String): List<CaptureSessionItem> {
        if (query.isBlank()) return items
        val q = query.trim().lowercase(Locale.ROOT)
        return items.filter { item ->
            item.packageName.lowercase(Locale.ROOT).contains(q) ||
                    (item.activityName?.lowercase(Locale.ROOT)?.contains(q) == true) ||
                    item.captureId.lowercase(Locale.ROOT).contains(q)
        }
    }

    /**
     * Formats a unix millisecond timestamp into a human-readable date/time string.
     */
    fun formatTimestamp(
        timestamp: Long,
        pattern: String = "yyyy-MM-dd HH:mm:ss",
        locale: Locale = Locale.getDefault()
    ): String {
        return try {
            SimpleDateFormat(pattern, locale).format(Date(timestamp))
        } catch (_: Throwable) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestamp))
        }
    }

    /**
     * Formats a relative human-friendly time description ("Just now", "5m ago", "2h ago", "3d ago").
     */
    fun formatRelativeTime(
        timestamp: Long,
        now: Long = System.currentTimeMillis()
    ): String {
        val diff = now - timestamp
        return when {
            diff < 0L -> "Just now"
            diff < 60_000L -> "Just now"
            diff < 3_600_000L -> "${diff / 60_000L}m ago"
            diff < 86_400_000L -> "${diff / 3_600_000L}h ago"
            diff < 7L * 86_400_000L -> "${diff / 86_400_000L}d ago"
            else -> try {
                SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
            } catch (_: Throwable) {
                SimpleDateFormat("MMM d", Locale.US).format(Date(timestamp))
            }
        }
    }

    /**
     * Recursively deletes a capture session directory and all its files from disk.
     */
    fun deleteCaptureSession(item: CaptureSessionItem): Boolean {
        return try {
            item.directory.deleteRecursively()
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Calculates total byte size of all files contained in a directory.
     */
    fun getDirectoryTotalSize(dir: File): Long {
        if (!dir.exists() || !dir.isDirectory) return 0L
        return try {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (_: Throwable) {
            0L
        }
    }

    /**
     * Formats raw byte size into human-readable representation ("120 KB", "2.4 MB").
     */
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    /**
     * Groups a list of capture sessions by their target package name into [AppCaptureGroup]s,
     * sorted by the latest capture timestamp descending.
     */
    fun groupSessionsByApp(sessions: List<CaptureSessionItem>): List<AppCaptureGroup> {
        return sessions
            .groupBy { it.packageName }
            .map { (pkg, appSessions) ->
                AppCaptureGroup(
                    packageName = pkg,
                    sessions = appSessions.sortedByDescending { it.timestamp }
                )
            }
            .sortedByDescending { it.latestTimestamp }
    }

    /**
     * Filters list of [AppCaptureGroup]s by query matching package name, app name, or activity names.
     */
    fun filterAppGroups(groups: List<AppCaptureGroup>, query: String): List<AppCaptureGroup> {
        if (query.isBlank()) return groups
        val q = query.trim().lowercase(Locale.ROOT)
        return groups.filter { group ->
            group.packageName.lowercase(Locale.ROOT).contains(q) ||
                    group.appName.lowercase(Locale.ROOT).contains(q) ||
                    group.sessions.any { it.activityName?.lowercase(Locale.ROOT)?.contains(q) == true }
        }
    }

    /**
     * Packages all files in the given [sessions] into a single ZIP archive.
     * Preserves directory names as top-level folders in the ZIP (e.g. `cap_<ts>_<pkg>/screenshot.png`).
     */
    fun createZipArchive(sessions: List<CaptureSessionItem>, zipFile: File): File {
        val parent = zipFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }

        java.util.zip.ZipOutputStream(java.io.FileOutputStream(zipFile)).use { zos ->
            val buffer = ByteArray(8192)
            for (session in sessions) {
                val dir = session.directory
                if (!dir.exists() || !dir.isDirectory) continue
                val dirName = dir.name
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && file.length() > 0 && !file.name.endsWith(".zip")) {
                        val entryName = "$dirName/${file.name}"
                        val entry = java.util.zip.ZipEntry(entryName).apply {
                            time = file.lastModified()
                        }
                        zos.putNextEntry(entry)
                        file.inputStream().use { input ->
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                zos.write(buffer, 0, read)
                            }
                        }
                        zos.closeEntry()
                    }
                }
            }
        }
        return zipFile
    }

    /**
     * Creates a timestamped export ZIP containing all capture sessions.
     */
    fun exportAllToZip(baseDir: File, sessions: List<CaptureSessionItem>): File {
        val timestamp = System.currentTimeMillis()
        val zipFile = File(baseDir, "UICapture_All_${timestamp}.zip")
        return createZipArchive(sessions, zipFile)
    }

    /**
     * Creates a timestamped export ZIP containing all capture sessions for a specific app.
     */
    fun exportAppToZip(packageName: String, baseDir: File, sessions: List<CaptureSessionItem>): File {
        val sanitizedPkg = packageName.ifBlank { "unknown" }.replace(".", "_")
        val timestamp = System.currentTimeMillis()
        val zipFile = File(baseDir, "UICapture_${sanitizedPkg}_${timestamp}.zip")
        return createZipArchive(sessions, zipFile)
    }
}

/**
 * Data class representing an application group containing all its captured sessions.
 */
data class AppCaptureGroup(
    val packageName: String,
    val appName: String = packageName.substringAfterLast(".").replaceFirstChar { it.uppercase() },
    val sessions: List<CaptureSessionItem>,
    val latestTimestamp: Long = sessions.maxOfOrNull { it.timestamp } ?: 0L,
    val totalElements: Int = sessions.sumOf { it.elementCount },
    val screenshotCount: Int = sessions.count { it.hasScreenshot }
)
