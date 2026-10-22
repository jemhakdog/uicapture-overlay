package com.uicapture.app.model

import java.io.File

/**
 * Domain representation of an exported capture session in the gallery.
 */
data class CaptureSessionItem(
    val captureId: String,
    val timestamp: Long,
    val packageName: String,
    val activityName: String? = null,
    val screenResolution: String? = null,
    val elementCount: Int = 0,
    val interactiveCount: Int = 0,
    val directory: File,
    val screenshotFile: File? = null,
    val hierarchyJsonFile: File? = null,
    val hierarchyXmlFile: File? = null,
    val summaryJsonFile: File? = null
) {
    val hasScreenshot: Boolean get() = screenshotFile?.exists() == true
    val hasJson: Boolean get() = hierarchyJsonFile?.exists() == true
    val hasXml: Boolean get() = hierarchyXmlFile?.exists() == true
    val hasSummary: Boolean get() = summaryJsonFile?.exists() == true
}
