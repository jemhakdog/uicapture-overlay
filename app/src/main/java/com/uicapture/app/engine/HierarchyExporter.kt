package com.uicapture.app.engine

import android.graphics.Bitmap
import android.os.Environment
import com.uicapture.app.model.ScreenCaptureData
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Lightweight metadata summary written to `summary.json`.
 */
@Serializable
data class CaptureSummary(
    @SerialName("captureId")
    val captureId: String,

    @SerialName("timestamp")
    val timestamp: Long,

    @SerialName("packageName")
    val packageName: String,

    @SerialName("activityName")
    val activityName: String? = null,

    @SerialName("screenResolution")
    val screenResolution: String? = null,

    @SerialName("elementCount")
    val elementCount: Int,

    @SerialName("interactiveCount")
    val interactiveCount: Int,

    @SerialName("screenshotFileName")
    val screenshotFileName: String? = null,

    @SerialName("hierarchyJsonFileName")
    val hierarchyJsonFileName: String = HierarchyExporter.HIERARCHY_JSON_NAME,

    @SerialName("hierarchyXmlFileName")
    val hierarchyXmlFileName: String = HierarchyExporter.HIERARCHY_XML_NAME
)

/**
 * Result details containing references to all persisted capture artifacts.
 */
data class ExportResult(
    val directory: File,
    val hierarchyJsonFile: File,
    val hierarchyXmlFile: File,
    val summaryJsonFile: File,
    val screenshotFile: File? = null
)

/**
 * File exporter responsible for writing hierarchy JSON, hierarchy XML, summary metadata,
 * and screenshot images into shared device storage under `Download/UICaptures/cap_<timestamp>_<pkg>/`.
 */
class HierarchyExporter(
    private val baseDirProvider: () -> File = { defaultBaseDirectory() }
) {
    /**
     * Primary convenience constructor allowing tests and caller services to specify
     * an explicit base output directory.
     */
    constructor(baseDir: File) : this({ baseDir })

    companion object {
        const val HIERARCHY_JSON_NAME = "hierarchy.json"
        const val HIERARCHY_XML_NAME = "hierarchy.xml"
        const val SUMMARY_JSON_NAME = "summary.json"
        const val SCREENSHOT_PNG_NAME = "screenshot.png"
        const val UICAPTURES_FOLDER = "UICaptures"
        const val XML_DECLARATION = "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n"

        @OptIn(ExperimentalSerializationApi::class)
        val json: Json = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        /**
         * Resolves the default base storage directory:
         * `/sdcard/Download/UICaptures/`
         * Safely falls back if Environment is not available in non-Android environments.
         */
        fun defaultBaseDirectory(): File {
            val downloadDir = try {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            } catch (_: Throwable) {
                null
            } ?: File("/sdcard/Download")
            return File(downloadDir, UICAPTURES_FOLDER)
        }
    }

    /**
     * Computes the target session directory: `baseDir/cap_<timestamp>_<sanitized_pkg>`
     */
    fun getTargetDirectory(timestamp: Long, packageName: String): File {
        val base = baseDirProvider()
        val sanitizedPkg = packageName.ifBlank { "unknown" }.replace(".", "_")
        return File(base, "cap_${timestamp}_${sanitizedPkg}")
    }

    /**
     * Computes the target session directory from a [ScreenCaptureData] instance.
     */
    fun getTargetDirectory(data: ScreenCaptureData): File {
        return getTargetDirectory(data.timestamp, data.packageName)
    }

    /**
     * Saves a [Bitmap] screenshot to the target directory as a PNG file.
     */
    fun saveScreenshot(
        dir: File,
        bitmap: Bitmap,
        fileName: String = SCREENSHOT_PNG_NAME
    ): File {
        return saveScreenshotStream(dir, fileName) { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    /**
     * Stream writer for saving screenshots, decoupling compression mechanism from Android Bitmap.
     */
    fun saveScreenshotStream(
        dir: File,
        fileName: String = SCREENSHOT_PNG_NAME,
        writeAction: (OutputStream) -> Unit
    ): File {
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val file = File(dir, fileName)
        FileOutputStream(file).use { out ->
            writeAction(out)
        }
        return file
    }

    /**
     * Exports a complete capture session to disk:
     * 1. `hierarchy.json`: Pretty-printed JSON of [ScreenCaptureData] including all elements.
     * 2. `hierarchy.xml`: Valid XML document with XML declaration and indented hierarchy tags.
     * 3. `summary.json`: Lightweight metadata summary.
     * 4. Optional `screenshot.png` if bitmap or screenshot stream is provided.
     *
     * @param data Screen hierarchy and session metadata.
     * @param screenshot Optional [Bitmap] screenshot to compress and save.
     * @param customDir Optional target directory override.
     * @return [ExportResult] referencing all generated files.
     */
    fun export(
        data: ScreenCaptureData,
        screenshot: Bitmap? = null,
        customDir: File? = null
    ): ExportResult {
        val dir = customDir ?: getTargetDirectory(data)
        if (!dir.exists()) {
            dir.mkdirs()
        }

        var screenshotFile: File? = null
        if (screenshot != null) {
            screenshotFile = saveScreenshot(dir, screenshot)
        } else if (data.screenshotPath != null) {
            val existing = File(data.screenshotPath)
            if (existing.exists() && existing.parentFile?.canonicalPath == dir.canonicalPath) {
                screenshotFile = existing
            }
        }

        val updatedData = if (screenshotFile != null && data.screenshotPath == null) {
            data.copy(screenshotPath = screenshotFile.absolutePath)
        } else {
            data
        }

        // 1. Write hierarchy.json
        val hierarchyJsonFile = File(dir, HIERARCHY_JSON_NAME)
        val jsonString = json.encodeToString(ScreenCaptureData.serializer(), updatedData)
        hierarchyJsonFile.writeText(jsonString, Charsets.UTF_8)

        // 2. Write hierarchy.xml
        val hierarchyXmlFile = File(dir, HIERARCHY_XML_NAME)
        val rawXml = (updatedData.rawXml?.trim()?.ifEmpty { null } ?: "<hierarchy rotation=\"0\" />")
        val fullXml = if (rawXml.startsWith("<?xml", ignoreCase = true)) {
            rawXml
        } else {
            XML_DECLARATION + rawXml
        }
        hierarchyXmlFile.writeText(fullXml, Charsets.UTF_8)

        // 3. Write summary.json
        val summaryJsonFile = File(dir, SUMMARY_JSON_NAME)
        val summary = CaptureSummary(
            captureId = updatedData.captureId,
            timestamp = updatedData.timestamp,
            packageName = updatedData.packageName,
            activityName = updatedData.activityName,
            screenResolution = updatedData.screenResolution,
            elementCount = updatedData.elementCount,
            interactiveCount = updatedData.interactiveElements.size,
            screenshotFileName = screenshotFile?.name ?: updatedData.screenshotPath?.let { File(it).name },
            hierarchyJsonFileName = HIERARCHY_JSON_NAME,
            hierarchyXmlFileName = HIERARCHY_XML_NAME
        )
        val summaryString = json.encodeToString(CaptureSummary.serializer(), summary)
        summaryJsonFile.writeText(summaryString, Charsets.UTF_8)

        return ExportResult(
            directory = dir,
            hierarchyJsonFile = hierarchyJsonFile,
            hierarchyXmlFile = hierarchyXmlFile,
            summaryJsonFile = summaryJsonFile,
            screenshotFile = screenshotFile
        )
    }
}
