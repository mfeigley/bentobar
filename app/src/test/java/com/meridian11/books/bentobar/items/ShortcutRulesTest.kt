package com.meridian11.books.bentobar.items

import android.accessibilityservice.AccessibilityService
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.util.Sym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Shortcut item: which actions there are, what a layout keeps of one, and where Report a bug is offered. */
class ShortcutRulesTest {

    @Test fun eachActionKeepsTheIdALayoutStoresForIt() {
        // A layout keeps the id, and a copied or backed-up layout comes back with it: an id never changes.
        assertEquals(listOf("screenshot", "desktop", "bug", "lock", "overview", "apps", "notifications", "quick_settings"),
            ShortcutAction.entries.map { it.id })
    }

    @Test fun aMissingOrUnknownActionIsAScreenshot() {
        // A new item, a layout from a newer version, or one typed by hand: the button still does something.
        assertEquals(ShortcutAction.SCREENSHOT, ShortcutRules.action(null))
        assertEquals(ShortcutAction.SCREENSHOT, ShortcutRules.action(""))
        assertEquals(ShortcutAction.SCREENSHOT, ShortcutRules.action("record"))
        assertEquals(ShortcutAction.DESKTOP, ShortcutRules.action("desktop"))
        assertEquals(ShortcutAction.REPORT_BUG, ShortcutRules.action("bug"))
        assertEquals(ShortcutAction.QUICK_SETTINGS, ShortcutRules.action("quick_settings"))
    }

    @Test fun theSystemActionsAreTheToolsMenusOwn() {
        assertEquals(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, ShortcutAction.SCREENSHOT.global)
        assertEquals(AccessibilityService.GLOBAL_ACTION_HOME, ShortcutAction.DESKTOP.global)
        assertEquals(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, ShortcutAction.LOCK.global)
        assertEquals(AccessibilityService.GLOBAL_ACTION_RECENTS, ShortcutAction.OVERVIEW.global)
        assertEquals(AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS, ShortcutAction.ALL_APPS.global)
        assertEquals(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, ShortcutAction.NOTIFICATIONS.global)
        assertEquals(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, ShortcutAction.QUICK_SETTINGS.global)
        // Report a bug opens an app instead.
        assertNull(ShortcutAction.REPORT_BUG.global)
        // The same words and glyphs as the Tools menu, so the two say the same thing.
        assertEquals(R.string.tools_screenshot, ShortcutAction.SCREENSHOT.label)
        assertEquals(R.string.tools_desktop, ShortcutAction.DESKTOP.label)
        assertEquals(Sym.SCREENSHOT_MONITOR, ShortcutAction.SCREENSHOT.glyph)
        assertEquals(Sym.DESKTOP_WINDOWS, ShortcutAction.DESKTOP.glyph)
        assertEquals(Sym.BUG_REPORT, ShortcutAction.REPORT_BUG.glyph)
    }

    @Test fun reportABugIsOfferedOnlyWhereTheFeedbackAppIs() {
        assertTrue(ShortcutAction.REPORT_BUG in ShortcutRules.offered(feedback = true))
        assertFalse(ShortcutAction.REPORT_BUG in ShortcutRules.offered(feedback = false))
        // Everything else is always offered, in the list's order.
        assertEquals(ShortcutAction.entries - ShortcutAction.REPORT_BUG, ShortcutRules.offered(feedback = false))
        assertEquals(ShortcutAction.entries, ShortcutRules.offered(feedback = true))
    }

    @Test fun reportABugWithoutTheFeedbackAppDoesNothingAndSaysSo() {
        // A layout pasted from a Googlebook onto another device: the button is there, dimmed, and a click is no click.
        assertFalse(ShortcutRules.usable(ShortcutAction.REPORT_BUG, feedback = false))
        assertTrue(ShortcutRules.usable(ShortcutAction.REPORT_BUG, feedback = true))
        assertTrue(ShortcutAction.entries.filter { it != ShortcutAction.REPORT_BUG }.all { ShortcutRules.usable(it, feedback = false) })
    }

    @Test fun anItemsOwnActionStaysInItsChoicesEvenWhereItIsNotOffered() {
        // Report a bug copied onto a device without the Feedback app: its settings still show it chosen (and dimmed in the bar).
        assertEquals(ShortcutRules.offered(feedback = false) + ShortcutAction.REPORT_BUG,
            ShortcutRules.choices(ShortcutAction.REPORT_BUG, feedback = false))
        assertEquals(ShortcutRules.offered(feedback = true), ShortcutRules.choices(ShortcutAction.REPORT_BUG, feedback = true))
        assertEquals(ShortcutRules.offered(feedback = false), ShortcutRules.choices(ShortcutAction.LOCK, feedback = false))
    }

    @Test fun aScreenshotWaitsForBentoBarsOwnWindowsToGo() {
        // A tooltip or the menu the click came from must not be in the picture: as long as the Tools menu waits.
        assertEquals(250L, ShortcutRules.waitMs(ShortcutAction.SCREENSHOT))
        assertTrue(ShortcutAction.entries.filter { it != ShortcutAction.SCREENSHOT }.all { ShortcutRules.waitMs(it) == 0L })
    }

    @Test fun desktopItemHasExpectedMetadataAndNoMenu() {
        assertEquals("desktop", DesktopItem.type)
        assertEquals(R.string.item_desktop_title, DesktopItem.titleRes)
        assertEquals(R.string.item_desktop_desc, DesktopItem.blurbRes)
        assertEquals(Sym.DESKTOP_WINDOWS, DesktopItem.icon)
        assertNull(DesktopItem.menu)
    }
}
