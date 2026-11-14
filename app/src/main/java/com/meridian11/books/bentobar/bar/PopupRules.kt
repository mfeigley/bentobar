package com.meridian11.books.bentobar.bar

/** When pointing at an item opens its popup, and how an open system tray is recognised and closed. Pure, so the rules are tested (`PopupRulesTest`). */
internal object PopupRules {
    data class BarToggleCandidate(
        val index: Int,
        val left: Int,
        val right: Int,
        val active: Boolean,
    )

    /**
     * Whether the pointer coming onto item [hovered] opens its popup: only with "Switch popups on hover" [on] (off by
     * default: nothing opens on hover by itself), only while an item's popup or the ‹ menu is [open] (a key as
     * `toggleMenu` has it: "item:ID", "ctx:ID", "bentobar"), never for the item already open, never for a right-click
     * menu, and only for an item that [hasPopup]: one that acts on a click (keep awake, a Shortcut) must not act on a hover.
     */
    fun hoverOpens(open: String?, hovered: String, hasPopup: Boolean, on: Boolean): Boolean {
        if (!on || open == null || !hasPopup) return false
        if (open != "bentobar" && !open.startsWith("item:")) return false
        return open != "item:$hovered"
    }

    /**
     * Whether a window is an open system tray/panel (Quick Settings, Notifications, or a status-bar panel) anchored at or
     * just below the status bar: a `TYPE_SYSTEM` window other than the status bar itself, starting within [maxTopGapPx]
     * of the status bar's bottom edge, at least three times as tall as the bar, and not the screenshot preview overlay.
     */
    fun isSystemTray(
        isSystemWindow: Boolean,
        windowId: Int,
        barWindowId: Int,
        top: Int,
        bottom: Int,
        barBottom: Int,
        barHeight: Int,
        maxTopGapPx: Int,
        title: String?,
    ): Boolean {
        if (!isSystemWindow || windowId == barWindowId || barHeight <= 0) return false
        if (top > barBottom + maxTopGapPx) return false
        if (bottom - top < barHeight * 3) return false
        if (title?.contains("Screenshot", ignoreCase = true) == true) return false
        return true
    }

    /**
     * Picks the clickable status-bar chip that toggles a system tray on the given side of the screen ([trayOnRight]):
     * prefers a chip on that side marked active/selected by SystemUI, falling back to the outermost clickable chip
     * (rightmost for Quick Settings, leftmost for Clock/Notifications).
     */
    fun pickBarToggle(
        candidates: List<BarToggleCandidate>,
        trayOnRight: Boolean,
        screenCenterX: Int,
    ): Int? {
        val side = candidates.filter { if (trayOnRight) it.right > screenCenterX else it.left < screenCenterX }
        if (side.isEmpty()) return null
        val pool = side.filter { it.active }.ifEmpty { side }
        val chosen = if (trayOnRight) pool.maxByOrNull { it.right } else pool.minByOrNull { it.left }
        return chosen?.index
    }
}
