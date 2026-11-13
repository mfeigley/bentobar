package com.meridian11.books.bentobar.items

import android.accessibilityservice.AccessibilityService
import androidx.annotation.StringRes
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.util.Sym

/**
 * What a Shortcut item does when it is clicked. [id] is what a layout keeps, so it never changes;
 * [global]: the accessibility service's global action, the Tools menu's own; null for Report a bug,
 * which opens the Googlebook's Feedback app instead. Names and glyphs are the Tools menu's where it
 * has the action, so the two say the same thing.
 */
enum class ShortcutAction(val id: String, val glyph: String, @StringRes val label: Int, val global: Int?) {
    SCREENSHOT("screenshot", Sym.SCREENSHOT_MONITOR, R.string.tools_screenshot, AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT),
    DESKTOP("desktop", Sym.DESKTOP_WINDOWS, R.string.tools_desktop, AccessibilityService.GLOBAL_ACTION_HOME),
    REPORT_BUG("bug", Sym.BUG_REPORT, R.string.shortcut_report_bug, null),
    LOCK("lock", Sym.LOCK, R.string.tools_lock, AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN),
    OVERVIEW("overview", Sym.DESKTOP_WINDOWS, R.string.tools_overview, AccessibilityService.GLOBAL_ACTION_RECENTS),
    ALL_APPS("apps", Sym.APPS, R.string.tools_all_apps, AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS),
    NOTIFICATIONS("notifications", Sym.NOTIFICATIONS, R.string.tools_notifications, AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS),
    QUICK_SETTINGS("quick_settings", Sym.TOGGLE_ON, R.string.tools_quick_settings, AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS),
}

/** The Shortcut item's rules, without Android: which action an item has, and where Report a bug is offered. */
object ShortcutRules {
    /** The Googlebook's Feedback app, which answers Android's "report a bug" request. */
    const val FEEDBACK_PACKAGE = "com.google.android.desktop.feedback"

    /** The action a layout's [id] names; a new item, or an id this version doesn't know, is a screenshot. */
    fun action(id: String?): ShortcutAction = ShortcutAction.entries.firstOrNull { it.id == id } ?: ShortcutAction.SCREENSHOT

    /** The actions an item can be given: Report a bug only where the Feedback app is ([feedback]). */
    fun offered(feedback: Boolean): List<ShortcutAction> = ShortcutAction.entries.filter { it != ShortcutAction.REPORT_BUG || feedback }

    /** Whether a click on [action] does something here: Report a bug needs the Feedback app. */
    fun usable(action: ShortcutAction, feedback: Boolean): Boolean = action != ShortcutAction.REPORT_BUG || feedback

    /** The choices in an item's settings: what is [offered], and the item's own [current] action even where it isn't. */
    fun choices(current: ShortcutAction, feedback: Boolean): List<ShortcutAction> =
        offered(feedback).let { if (current in it) it else it + current }

    /**
     * How long [action] waits after the click: a screenshot waits until BentoBar's own windows (a
     * tooltip, the menu the click came from) are gone, as long as the Tools menu waits for its menu.
     */
    fun waitMs(action: ShortcutAction): Long = if (action == ShortcutAction.SCREENSHOT) 250L else 0L

    /**
     * Whether clicking Show desktop should hide the currently [visible] app windows (saving them to
     * reopen next time) or reopen the [saved] ones from the previous click.
     */
    fun <T> shouldHideDesktop(visible: List<T>, saved: List<T>, sinceHideMs: Long): Boolean =
        visible.isNotEmpty() && (saved.isEmpty() || sinceHideMs > 600L) || saved.isEmpty()
}
