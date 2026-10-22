package com.uicapture.app.model

import com.uicapture.app.util.BoundsParser
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonNames

/**
 * Custom serializer for [Pair] of [Int] to support both array representation `[x, y]`
 * (matching Python / `aicore_bridge.py`) and object representation `{"first": x, "second": y}`.
 */
object IntPairSerializer : KSerializer<Pair<Int, Int>> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("IntPair") {
            element("first", Int.serializer().descriptor)
            element("second", Int.serializer().descriptor)
        }

    override fun serialize(encoder: Encoder, value: Pair<Int, Int>) {
        if (encoder is JsonEncoder) {
            encoder.encodeJsonElement(
                JsonArray(listOf(JsonPrimitive(value.first), JsonPrimitive(value.second)))
            )
        } else {
            val composite = encoder.beginStructure(descriptor)
            composite.encodeIntElement(descriptor, 0, value.first)
            composite.encodeIntElement(descriptor, 1, value.second)
            composite.endStructure(descriptor)
        }
    }

    override fun deserialize(decoder: Decoder): Pair<Int, Int> {
        if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            return when (element) {
                is JsonArray -> {
                    val first = element.getOrNull(0)?.jsonPrimitive?.intOrNull ?: 0
                    val second = element.getOrNull(1)?.jsonPrimitive?.intOrNull ?: 0
                    Pair(first, second)
                }
                is JsonObject -> {
                    val first = (element["first"] ?: element["x"] ?: element["width"] ?: element["left"])
                        ?.jsonPrimitive?.intOrNull ?: 0
                    val second = (element["second"] ?: element["y"] ?: element["height"] ?: element["top"])
                        ?.jsonPrimitive?.intOrNull ?: 0
                    Pair(first, second)
                }
                else -> Pair(0, 0)
            }
        }
        val composite = decoder.beginStructure(descriptor)
        var first = 0
        var second = 0
        while (true) {
            when (val index = composite.decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break
                0 -> first = composite.decodeIntElement(descriptor, 0)
                1 -> second = composite.decodeIntElement(descriptor, 1)
                else -> break
            }
        }
        composite.endStructure(descriptor)
        return Pair(first, second)
    }
}

/**
 * Represents a single node / element in the Android screen accessibility hierarchy.
 * Fully serializable to JSON and compatible with data structures from `andorid-mcp/aicore_bridge.py`.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UIElement(
    @SerialName("id")
    @JsonNames("resource_id", "resource-id")
    val id: String? = null,

    @SerialName("className")
    @JsonNames("class", "cls")
    val className: String = "",

    @SerialName("text")
    val text: String = "",

    @SerialName("contentDesc")
    @JsonNames("content_desc", "content-desc", "desc")
    val contentDesc: String = "",

    @SerialName("bounds")
    val bounds: String = "",

    @Serializable(with = IntPairSerializer::class)
    @SerialName("center")
    val center: Pair<Int, Int>? = null,

    @Serializable(with = IntPairSerializer::class)
    @SerialName("size")
    val size: Pair<Int, Int>? = null,

    @SerialName("clickable")
    val clickable: Boolean = false,

    @SerialName("scrollable")
    val scrollable: Boolean = false,

    @SerialName("editable")
    val editable: Boolean = false,

    @SerialName("checkable")
    val checkable: Boolean = false,

    @SerialName("checked")
    val checked: Boolean = false,

    @SerialName("focused")
    val focused: Boolean = false,

    @SerialName("selected")
    val selected: Boolean = false,

    @SerialName("depth")
    val depth: Int = 0,

    @SerialName("action")
    val action: String? = null
) {
    /**
     * Convenient display label combining text and content description,
     * matching the behavior of `aicore_bridge.py`.
     */
    val label: String
        get() = text.ifBlank { contentDesc }

    /**
     * Alias for resource ID matching Android accessibility nomenclature.
     */
    val resourceId: String?
        get() = id

    /**
     * Returns true if this element has interactive capabilities.
     */
    val isInteractive: Boolean
        get() = clickable || scrollable || editable || checkable

    /**
     * Width calculated from size or resolved from bounds string.
     */
    val width: Int?
        get() = size?.first ?: BoundsParser.parseWidth(bounds)

    /**
     * Height calculated from size or resolved from bounds string.
     */
    val height: Int?
        get() = size?.second ?: BoundsParser.parseHeight(bounds)

    /**
     * Center coordinates resolved either from [center] property or parsed from [bounds].
     */
    val resolvedCenter: Pair<Int, Int>?
        get() = center ?: BoundsParser.parseCenter(bounds)

    /**
     * Size resolved either from [size] property or parsed from [bounds].
     */
    val resolvedSize: Pair<Int, Int>?
        get() = size ?: BoundsParser.parseSize(bounds)

    /**
     * Checks if the given screen coordinate (x, y) is inside this element's bounds.
     */
    fun contains(x: Int, y: Int): Boolean = BoundsParser.contains(bounds, x, y)

    /**
     * Returns a dictionary representation matching the format produced by `aicore_bridge.py`.
     */
    fun toAicoreMap(): Map<String, Any?> = buildMap {
        put("class", className)
        put("text", text)
        put("content_desc", contentDesc)
        put("resource_id", id ?: "")
        put("clickable", clickable)
        put("center", (center ?: BoundsParser.parseCenter(bounds))?.let { listOf(it.first, it.second) })
        put("bounds", bounds)
        put("scrollable", scrollable)
        put("editable", editable)
        put("checkable", checkable)
        put("checked", checked)
        put("focused", focused)
        put("selected", selected)
        put("depth", depth)
        action?.let { put("action", it) }
    }

    companion object {
        /**
         * Creates a [UIElement] auto-populating [center] and [size] from [bounds]
         * if they are not explicitly supplied.
         */
        fun fromBounds(
            bounds: String,
            id: String? = null,
            className: String = "",
            text: String = "",
            contentDesc: String = "",
            clickable: Boolean = false,
            scrollable: Boolean = false,
            editable: Boolean = false,
            checkable: Boolean = false,
            checked: Boolean = false,
            focused: Boolean = false,
            selected: Boolean = false,
            depth: Int = 0,
            action: String? = null
        ): UIElement {
            val parsed = BoundsParser.parse(bounds)
            return UIElement(
                id = id,
                className = className,
                text = text,
                contentDesc = contentDesc,
                bounds = bounds,
                center = parsed?.center,
                size = parsed?.size,
                clickable = clickable,
                scrollable = scrollable,
                editable = editable,
                checkable = checkable,
                checked = checked,
                focused = focused,
                selected = selected,
                depth = depth,
                action = action
            )
        }
    }
}
