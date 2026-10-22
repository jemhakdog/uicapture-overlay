package com.uicapture.app.util

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Structured representation of parsed rectangular screen bounds [left, top, right, bottom].
 */
@Serializable
data class ParsedBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    /**
     * Width of the bounded region.
     */
    val width: Int
        get() = abs(right - left)

    /**
     * Height of the bounded region.
     */
    val height: Int
        get() = abs(bottom - top)

    /**
     * Center coordinates (x, y). Matches aicore_bridge: ((x1 + x2) // 2, (y1 + y2) // 2).
     */
    val center: Pair<Int, Int>
        get() = Pair((left + right) / 2, (top + bottom) / 2)

    /**
     * Size as a Pair of (width, height).
     */
    val size: Pair<Int, Int>
        get() = Pair(width, height)

    /**
     * Checks if the coordinate (x, y) falls within this bounded rectangle (inclusive).
     */
    fun contains(x: Int, y: Int): Boolean {
        val minX = min(left, right)
        val maxX = max(left, right)
        val minY = min(top, bottom)
        val maxY = max(top, bottom)
        return x in minX..maxX && y in minY..maxY
    }

    /**
     * Formats this bounds into standard [x1,y1][x2,y2] string.
     */
    fun toBoundsString(): String = "[$left,$top][$right,$bottom]"
}

/**
 * Utility for parsing and calculating UI hierarchy boundary coordinate strings formatted as `[x1,y1][x2,y2]`.
 * Designed for compatibility with Android accessibility node dumps and `andorid-mcp/aicore_bridge.py`.
 */
object BoundsParser {
    /**
     * Regex matching `[x1,y1][x2,y2]` with optional whitespace and support for negative coordinates.
     */
    private val BOUNDS_REGEX = Regex("""\[\s*(-?\d+)\s*,\s*(-?\d+)\s*\]\s*\[\s*(-?\d+)\s*,\s*(-?\d+)\s*\]""")

    /**
     * Parses a bounds string formatted as `[x1,y1][x2,y2]` into a [ParsedBounds] instance.
     * Returns null if the string is null, blank, or malformed.
     */
    fun parse(boundsStr: String?): ParsedBounds? {
        if (boundsStr.isNullOrBlank()) return null
        val match = BOUNDS_REGEX.find(boundsStr.trim()) ?: return null
        val (x1, y1, x2, y2) = match.destructured
        val left = x1.toIntOrNull() ?: return null
        val top = y1.toIntOrNull() ?: return null
        val right = x2.toIntOrNull() ?: return null
        val bottom = y2.toIntOrNull() ?: return null
        return ParsedBounds(left, top, right, bottom)
    }

    /**
     * Computes the center (x, y) coordinates of the given bounds string.
     * Matches the behavior of `aicore_bridge.py`'s `parse_bounds`.
     */
    fun parseCenter(boundsStr: String?): Pair<Int, Int>? = parse(boundsStr)?.center

    /**
     * Computes the size (width, height) of the given bounds string.
     */
    fun parseSize(boundsStr: String?): Pair<Int, Int>? = parse(boundsStr)?.size

    /**
     * Computes the width of the given bounds string.
     */
    fun parseWidth(boundsStr: String?): Int? = parse(boundsStr)?.width

    /**
     * Computes the height of the given bounds string.
     */
    fun parseHeight(boundsStr: String?): Int? = parse(boundsStr)?.height

    /**
     * Checks if the given screen coordinate (x, y) is contained within the bounds string.
     */
    fun contains(boundsStr: String?, x: Int, y: Int): Boolean {
        return parse(boundsStr)?.contains(x, y) ?: false
    }

    /**
     * Formats four coordinates into the standard `[x1,y1][x2,y2]` string representation.
     */
    fun format(left: Int, top: Int, right: Int, bottom: Int): String {
        return "[$left,$top][$right,$bottom]"
    }
}
