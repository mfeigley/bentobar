package com.meridian11.books.bentobar.bar

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Where the status bar is and which part of it is free, read from SystemUI's status bar window
 * in the accessibility tree. This is the only window BentoBar ever reads; app windows are skipped.
 */
data class BarSnapshot(
    /** The status bar window's id, for window screenshots (colour sampling). */
    val windowId: Int,
    val bar: Rect,
    /** Empty space BentoBar may use: the desktop bar's spacer, or the widest gap between items. */
    val free: Rect,
    /** The clock text, whose colour BentoBar copies. */
    val clock: Rect?,
    /** A short fingerprint of the bar's items, for logs. */
    val summary: String,
    /** The desktop bar's spacer node: refreshing just this is a cheap "did anything move?" check. */
    val spacerNode: AccessibilityNodeInfo? = null,
    /** Where [spacerNode] was at the scan. It is [free] unless the spacer had no width left. */
    val spacer: Rect? = null,
    /** The status bar's leftmost clickable chip (Notifications / date toggle on the desktop bar). */
    val leftToggle: AccessibilityNodeInfo? = null,
    /** The status bar's rightmost clickable chip (Quick Settings / system icons toggle on the desktop bar). */
    val rightToggle: AccessibilityNodeInfo? = null,
)

object StatusBarScan {
    /** [BarSnapshot.summary] when the window had no node tree to read: its free area is unknown. */
    const val NO_TREE = "no-tree"
    private val timeRe = Regex("""\d{1,2}[:.]\d{2}""")

    /** The status bar window: a system window along the top edge, shorter than [maxHeight]. */
    fun findWindow(windows: List<AccessibilityWindowInfo>, screenWidth: Int, maxHeight: Int): AccessibilityWindowInfo? =
        windows.firstOrNull { w ->
            val r = Rect().also { w.getBoundsInScreen(it) }
            w.type == AccessibilityWindowInfo.TYPE_SYSTEM && r.top == 0 && r.height() in 1..maxHeight && r.width() >= screenWidth / 2
        }

    fun scan(w: AccessibilityWindowInfo): BarSnapshot {
        val bar = Rect().also { w.getBoundsInScreen(it) }
        // One round trip: prefetch the (small, ~35 node) tree instead of an IPC per getChild().
        val root = w.getRoot(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST or
            AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE) ?: return BarSnapshot(w.id, bar, Rect(bar), null, NO_TREE)
        var spacer: Rect? = null
        var spacerNode: AccessibilityNodeInfo? = null
        var clock: Rect? = null
        var leftToggle: AccessibilityNodeInfo? = null
        var leftEdge = Int.MAX_VALUE
        var rightToggle: AccessibilityNodeInfo? = null
        var rightEdge = Int.MIN_VALUE
        val centerX = bar.centerX()
        val parts = ArrayList<Rect>()
        val names = StringBuilder()
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.add(root to 0)
        var visited = 0
        while (stack.isNotEmpty() && visited < 400) {
            val (n, depth) = stack.removeLast()
            visited++
            val r = Rect().also { n.getBoundsInScreen(it) }
            val id = n.viewIdResourceName.orEmpty()
            if (id.endsWith("DesktopStatusBarSpacer")) { spacer = Rect(r); spacerNode = n }
            val text = n.text?.toString().orEmpty()
            if (clock == null && text.isNotEmpty() && timeRe.containsMatchIn(text)) clock = Rect(r)
            if (n.isClickable && r.width() in 1 until bar.width() * 6 / 10 && r.height() > 0) {
                if (r.centerX() <= centerX && r.left < leftEdge) {
                    leftEdge = r.left
                    leftToggle = n
                } else if (r.centerX() > centerX && r.right > rightEdge) {
                    rightEdge = r.right
                    rightToggle = n
                }
            }
            // Leaf-ish pieces that take room: anything clickable or labelled that isn't the whole bar.
            val labelled = n.isClickable || !n.contentDescription.isNullOrEmpty() || text.isNotEmpty()
            if (labelled && r.width() in 1 until bar.width() * 6 / 10 && r.height() > 0) {
                parts += Rect(r)
                if (depth <= 6 && id.isNotEmpty()) names.append(id.substringAfterLast('/')).append(' ')
            }
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let { stack.add(it to depth + 1) }
        }
        val free = spacer?.takeIf { it.width() > 0 } ?: widestGap(bar, parts)
        return BarSnapshot(w.id, bar, free, clock, names.toString().trim(), spacerNode, spacer, leftToggle, rightToggle)
    }

    /** The widest horizontal stretch of the bar that no item covers. */
    private fun widestGap(bar: Rect, parts: List<Rect>): Rect {
        val spans = parts.map { it.left to it.right }.sortedBy { it.first }
        var best = Rect(bar.left, bar.top, bar.left, bar.bottom)
        var cursor = bar.left
        for ((l, r) in spans) {
            if (l > cursor && l - cursor > best.width()) best = Rect(cursor, bar.top, l, bar.bottom)
            cursor = maxOf(cursor, r)
        }
        if (bar.right - cursor > best.width()) best = Rect(cursor, bar.top, bar.right, bar.bottom)
        return best
    }
}
