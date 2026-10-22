package com.uicapture.app.engine

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.uicapture.app.model.UIElement
import com.uicapture.app.util.BoundsParser

/**
 * Result produced by [ScreenTreeBuilder], containing the flattened list of [UIElement]s
 * and the complete XML hierarchy representation.
 */
data class TreeResult(
    val elements: List<UIElement>,
    val rawXml: String
)

/**
 * Bounds abstraction to decouple screen coordinate retrieval from Android platform runtime in tests.
 */
data class NodeBounds(
    var left: Int = 0,
    var top: Int = 0,
    var right: Int = 0,
    var bottom: Int = 0
)

/**
 * Node interface abstracting [AccessibilityNodeInfo] for zero-dependency JVM unit testing.
 */
interface TreeNodeInfo {
    val packageName: CharSequence?
    val className: CharSequence?
    val text: CharSequence?
    val contentDescription: CharSequence?
    val viewIdResourceName: String?
    val isCheckable: Boolean
    val isChecked: Boolean
    val isClickable: Boolean
    val isEnabled: Boolean
    val isFocusable: Boolean
    val isFocused: Boolean
    val isScrollable: Boolean
    val isLongClickable: Boolean
    val isPassword: Boolean
    val isSelected: Boolean
    val isEditable: Boolean
    val childCount: Int

    fun getChild(index: Int): TreeNodeInfo?
    fun getBoundsInScreen(outBounds: NodeBounds)
    fun recycle()
}

/**
 * Default adapter wrapping real Android [AccessibilityNodeInfo].
 */
class AccessibilityNodeInfoAdapter(
    private val node: AccessibilityNodeInfo
) : TreeNodeInfo {
    override val packageName: CharSequence? get() = node.packageName
    override val className: CharSequence? get() = node.className
    override val text: CharSequence? get() = node.text
    override val contentDescription: CharSequence? get() = node.contentDescription
    override val viewIdResourceName: String? get() = node.viewIdResourceName
    override val isCheckable: Boolean get() = node.isCheckable
    override val isChecked: Boolean get() = node.isChecked
    override val isClickable: Boolean get() = node.isClickable
    override val isEnabled: Boolean get() = node.isEnabled
    override val isFocusable: Boolean get() = node.isFocusable
    override val isFocused: Boolean get() = node.isFocused
    override val isScrollable: Boolean get() = node.isScrollable
    override val isLongClickable: Boolean get() = node.isLongClickable
    override val isPassword: Boolean get() = node.isPassword
    override val isSelected: Boolean get() = node.isSelected
    override val isEditable: Boolean get() = node.isEditable
    override val childCount: Int get() = node.childCount

    override fun getChild(index: Int): TreeNodeInfo? {
        val child = node.getChild(index) ?: return null
        return AccessibilityNodeInfoAdapter(child)
    }

    override fun getBoundsInScreen(outBounds: NodeBounds) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        outBounds.left = rect.left
        outBounds.top = rect.top
        outBounds.right = rect.right
        outBounds.bottom = rect.bottom
    }

    override fun recycle() {
        try {
            @Suppress("DEPRECATION")
            node.recycle()
        } catch (_: Throwable) {
            // Safe ignore on newer Android versions where recycle is deprecated / no-op
        }
    }
}

/**
 * Traverses Android [AccessibilityNodeInfo] trees to produce structured [UIElement] lists
 * and standard XML hierarchy representations compatible with `andorid-mcp/aicore_bridge.py`.
 */
object ScreenTreeBuilder {
    const val OVERLAY_PACKAGE = "com.uicapture.app"
    const val ACTION_TYPE = "type"
    const val ACTION_SCROLL = "scroll"
    const val ACTION_LONGPRESS = "longpress"
    const val ACTION_TAP = "tap"
    const val ACTION_READ = "read"

    /**
     * Determines the primary action classification for a UI node based on its interaction capabilities.
     * Order of precedence:
     * 1. editable -> "type"
     * 2. scrollable -> "scroll"
     * 3. long-clickable -> "longpress"
     * 4. clickable -> "tap"
     * 5. otherwise -> "read"
     */
    fun determineAction(
        isEditable: Boolean,
        isScrollable: Boolean,
        isLongClickable: Boolean,
        isClickable: Boolean
    ): String {
        return when {
            isEditable -> ACTION_TYPE
            isScrollable -> ACTION_SCROLL
            isLongClickable -> ACTION_LONGPRESS
            isClickable -> ACTION_TAP
            else -> ACTION_READ
        }
    }

    /**
     * Sanitizes strings for safe XML attribute usage:
     * - Strips illegal XML 1.0 control characters
     * - Escapes XML entities: &, <, >, ", '
     */
    fun escapeXml(input: CharSequence?): String {
        if (input == null || input.isEmpty()) return ""
        val str = input.toString()
        val sb = StringBuilder(str.length + 16)
        var i = 0
        val len = str.length
        while (i < len) {
            val cp = str.codePointAt(i)
            when (cp) {
                '&'.code -> sb.append("&amp;")
                '<'.code -> sb.append("&lt;")
                '>'.code -> sb.append("&gt;")
                '"'.code -> sb.append("&quot;")
                '\''.code -> sb.append("&apos;")
                0x9, 0xA, 0xD, in 0x20..0xD7FF, in 0xE000..0xFFFD, in 0x10000..0x10FFFF -> {
                    sb.appendCodePoint(cp)
                }
                else -> {
                    // Drop illegal XML 1.0 control characters (e.g. \u0000..\u0008, \u000B, \u000C, \u000E..\u001F)
                }
            }
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /**
     * Builds hierarchy [TreeResult] from an Android [AccessibilityNodeInfo] root.
     * Nodes belonging to [ignorePackage] (the floating overlay) are skipped along with their subtrees.
     */
    fun build(
        root: AccessibilityNodeInfo?,
        ignorePackage: String = OVERLAY_PACKAGE,
        rotation: Int = 0
    ): TreeResult {
        if (root == null) {
            return TreeResult(emptyList(), "<hierarchy rotation=\"$rotation\" />")
        }
        return build(AccessibilityNodeInfoAdapter(root), ignorePackage, rotation)
    }

    /**
     * Builds hierarchy [TreeResult] from an abstract [TreeNodeInfo] instance.
     * Nodes belonging to [ignorePackage] are skipped along with their subtrees.
     * Child nodes retrieved during traversal are safely recycled in a try/finally block.
     */
    fun build(
        root: TreeNodeInfo?,
        ignorePackage: String = OVERLAY_PACKAGE,
        rotation: Int = 0
    ): TreeResult {
        if (root == null) {
            return TreeResult(emptyList(), "<hierarchy rotation=\"$rotation\" />")
        }
        val elements = mutableListOf<UIElement>()
        val xmlBuilder = StringBuilder()
        xmlBuilder.append("<hierarchy rotation=\"").append(rotation).append("\">\n")

        val rootPkg = root.packageName?.toString() ?: ""
        if (ignorePackage.isEmpty() || !rootPkg.equals(ignorePackage, ignoreCase = true)) {
            traverse(
                node = root,
                depth = 0,
                index = 0,
                ignorePackage = ignorePackage,
                elements = elements,
                xml = xmlBuilder,
                indent = "  "
            )
        }

        xmlBuilder.append("</hierarchy>")
        return TreeResult(elements, xmlBuilder.toString())
    }

    private fun traverse(
        node: TreeNodeInfo,
        depth: Int,
        index: Int,
        ignorePackage: String,
        elements: MutableList<UIElement>,
        xml: StringBuilder,
        indent: String
    ) {
        val bounds = NodeBounds()
        node.getBoundsInScreen(bounds)
        val boundsStr = BoundsParser.format(bounds.left, bounds.top, bounds.right, bounds.bottom)
        val parsed = BoundsParser.parse(boundsStr)

        val className = node.className?.toString() ?: ""
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val resId = node.viewIdResourceName
        val pkg = node.packageName?.toString() ?: ""

        val action = determineAction(
            isEditable = node.isEditable,
            isScrollable = node.isScrollable,
            isLongClickable = node.isLongClickable,
            isClickable = node.isClickable
        )

        val element = UIElement(
            id = resId,
            className = className,
            text = text,
            contentDesc = desc,
            bounds = boundsStr,
            center = parsed?.center,
            size = parsed?.size,
            clickable = node.isClickable,
            scrollable = node.isScrollable,
            editable = node.isEditable,
            checkable = node.isCheckable,
            checked = node.isChecked,
            focused = node.isFocused,
            selected = node.isSelected,
            depth = depth,
            action = action
        )
        elements.add(element)

        xml.append(indent).append("<node")
        xml.append(" index=\"").append(index).append("\"")
        xml.append(" text=\"").append(escapeXml(text)).append("\"")
        xml.append(" resource-id=\"").append(escapeXml(resId ?: "")).append("\"")
        xml.append(" class=\"").append(escapeXml(className)).append("\"")
        xml.append(" package=\"").append(escapeXml(pkg)).append("\"")
        xml.append(" content-desc=\"").append(escapeXml(desc)).append("\"")
        xml.append(" checkable=\"").append(node.isCheckable).append("\"")
        xml.append(" checked=\"").append(node.isChecked).append("\"")
        xml.append(" clickable=\"").append(node.isClickable).append("\"")
        xml.append(" enabled=\"").append(node.isEnabled).append("\"")
        xml.append(" focusable=\"").append(node.isFocusable).append("\"")
        xml.append(" focused=\"").append(node.isFocused).append("\"")
        xml.append(" scrollable=\"").append(node.isScrollable).append("\"")
        xml.append(" long-clickable=\"").append(node.isLongClickable).append("\"")
        xml.append(" password=\"").append(node.isPassword).append("\"")
        xml.append(" selected=\"").append(node.isSelected).append("\"")
        xml.append(" bounds=\"").append(boundsStr).append("\"")

        val childCount = node.childCount
        val validChildren = mutableListOf<TreeNodeInfo>()
        try {
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                val childPkg = child.packageName?.toString() ?: ""
                if (ignorePackage.isNotEmpty() && childPkg.equals(ignorePackage, ignoreCase = true)) {
                    // Safely recycle skipped overlay node immediately
                    try {
                        child.recycle()
                    } catch (_: Throwable) {
                        // ignore
                    }
                } else {
                    validChildren.add(child)
                }
            }

            if (validChildren.isEmpty()) {
                xml.append(" />\n")
            } else {
                xml.append(">\n")
                for ((childIndex, child) in validChildren.withIndex()) {
                    traverse(
                        node = child,
                        depth = depth + 1,
                        index = childIndex,
                        ignorePackage = ignorePackage,
                        elements = elements,
                        xml = xml,
                        indent = "$indent  "
                    )
                }
                xml.append(indent).append("</node>\n")
            }
        } finally {
            for (child in validChildren) {
                try {
                    child.recycle()
                } catch (_: Throwable) {
                    // ignore
                }
            }
        }
    }
}
