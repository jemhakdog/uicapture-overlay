package com.uicapture.app.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * Encapsulates a complete screen capture event, including metadata, hierarchy nodes,
 * raw accessibility XML, and screenshot storage reference.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ScreenCaptureData(
    @SerialName("captureId")
    @JsonNames("capture_id", "id")
    val captureId: String,

    @SerialName("timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @SerialName("packageName")
    @JsonNames("package_name", "pkg")
    val packageName: String = "",

    @SerialName("activityName")
    @JsonNames("activity_name", "activity")
    val activityName: String? = null,

    @SerialName("screenResolution")
    @JsonNames("screen_resolution", "resolution")
    val screenResolution: String? = null,

    @SerialName("elements")
    val elements: List<UIElement> = emptyList(),

    @SerialName("rawXml")
    @JsonNames("raw_xml", "xml")
    val rawXml: String? = null,

    @SerialName("screenshotPath")
    @JsonNames("screenshot_path", "screenshot")
    val screenshotPath: String? = null
) {
    /**
     * Total count of captured UI elements in this session.
     */
    val elementCount: Int
        get() = elements.size

    /**
     * List of elements with interactive traits (clickable, scrollable, editable, checkable).
     */
    val interactiveElements: List<UIElement>
        get() = elements.filter { it.isInteractive }

    /**
     * Standard folder name formatted as `cap_<timestamp>_<pkg>` for storage exports.
     */
    val directoryName: String
        get() {
            val sanitizedPkg = packageName.ifBlank { "unknown" }.replace(".", "_")
            return "cap_${timestamp}_${sanitizedPkg}"
        }

    /**
     * Finds elements matching the given text or content description substring.
     */
    fun findByText(query: String, ignoreCase: Boolean = true): List<UIElement> {
        return elements.filter {
            it.text.contains(query, ignoreCase = ignoreCase) ||
                it.contentDesc.contains(query, ignoreCase = ignoreCase)
        }
    }

    /**
     * Finds elements matching the given resource ID, either fully qualified or by short name.
     */
    fun findById(idQuery: String): List<UIElement> {
        return elements.filter {
            it.id == idQuery ||
                it.id?.endsWith("/$idQuery") == true ||
                it.resourceId == idQuery ||
                it.resourceId?.endsWith("/$idQuery") == true
        }
    }

    /**
     * Finds the deepest/topmost element enclosing the given (x, y) coordinates.
     */
    fun findElementAt(x: Int, y: Int): UIElement? {
        val matches = elements.filter { it.contains(x, y) }
        return matches.maxByOrNull { it.depth } ?: matches.lastOrNull()
    }
}
