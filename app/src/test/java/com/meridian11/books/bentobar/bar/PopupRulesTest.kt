package com.meridian11.books.bentobar.bar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Switch popups on hover" (off by default): with a popup open, pointing at another item opens its popup, as in a menu bar. */
class PopupRulesTest {
    @Test fun withTheSettingOnPointingAtAnotherItemOpensItsPopup() {
        assertTrue(PopupRules.hoverOpens(open = "item:weather1", hovered = "calendar1", hasPopup = true, on = true))
        // From the ‹ menu too: it is the bar's own popup.
        assertTrue(PopupRules.hoverOpens(open = "bentobar", hovered = "calendar1", hasPopup = true, on = true))
    }

    @Test fun offByDefaultAndNeverWithoutAnOpenPopup() {
        assertFalse(PopupRules.hoverOpens(open = "item:weather1", hovered = "calendar1", hasPopup = true, on = false))
        // Nothing open: hovering never opens anything (Alex: no hover surprises).
        assertFalse(PopupRules.hoverOpens(open = null, hovered = "calendar1", hasPopup = true, on = true))
    }

    @Test fun itNeverActsForAClickAndLeavesARightClickMenuAlone() {
        // An item that acts on a click (keep awake, a Shortcut) has no popup: hovering it must not act.
        assertFalse(PopupRules.hoverOpens(open = "item:weather1", hovered = "caffeine1", hasPopup = false, on = true))
        // The item whose popup is open: nothing to switch.
        assertFalse(PopupRules.hoverOpens(open = "item:weather1", hovered = "weather1", hasPopup = true, on = true))
        // A right-click menu belongs to its item: pointing elsewhere doesn't swap it for a popup.
        assertFalse(PopupRules.hoverOpens(open = "ctx:weather1", hovered = "calendar1", hasPopup = true, on = true))
    }

    @Test fun systemTrayRecognisesTopBarPanelsAndSkipsStatusBarTaskbarAndScreenshots() {
        // Quick Settings or Notification panel anchored just below the 28 px status bar.
        assertTrue(PopupRules.isSystemTray(
            isSystemWindow = true, windowId = 12, barWindowId = 1,
            top = 28, bottom = 380, barBottom = 28, barHeight = 28, maxTopGapPx = 32, title = "NotificationShade",
        ))
        // Status bar window itself is not a tray.
        assertFalse(PopupRules.isSystemTray(
            isSystemWindow = true, windowId = 1, barWindowId = 1,
            top = 0, bottom = 28, barBottom = 28, barHeight = 28, maxTopGapPx = 32, title = "StatusBar",
        ))
        // Taskbar / dock near the bottom of the screen is not a top-bar tray.
        assertFalse(PopupRules.isSystemTray(
            isSystemWindow = true, windowId = 15, barWindowId = 1,
            top = 580, bottom = 640, barBottom = 28, barHeight = 28, maxTopGapPx = 32, title = "Taskbar",
        ))
        // Screenshot preview overlay is ignored.
        assertFalse(PopupRules.isSystemTray(
            isSystemWindow = true, windowId = 18, barWindowId = 1,
            top = 0, bottom = 640, barBottom = 28, barHeight = 28, maxTopGapPx = 32, title = "Screenshot preview",
        ))
    }

    @Test fun pickBarToggleChoosesActiveOrOutermostChipOnTheTraysSide() {
        val chips = listOf(
            PopupRules.BarToggleCandidate(index = 0, left = 8, right = 88, active = false),     // clock on left
            PopupRules.BarToggleCandidate(index = 1, left = 840, right = 880, active = false),  // sound chip on right
            PopupRules.BarToggleCandidate(index = 2, left = 935, right = 1016, active = false), // battery/QS pill on far right
        )
        assertEquals(2, PopupRules.pickBarToggle(chips, trayOnRight = true, screenCenterX = 512))
        assertEquals(0, PopupRules.pickBarToggle(chips, trayOnRight = false, screenCenterX = 512))

        val withActive = chips.map { if (it.index == 1) it.copy(active = true) else it }
        assertEquals(1, PopupRules.pickBarToggle(withActive, trayOnRight = true, screenCenterX = 512))
        assertNull(PopupRules.pickBarToggle(emptyList(), trayOnRight = true, screenCenterX = 512))
    }
}
