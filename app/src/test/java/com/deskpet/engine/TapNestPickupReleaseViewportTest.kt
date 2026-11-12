package com.deskpet.engine

import org.junit.Assert.*
import org.junit.Test

class TapNestPickupReleaseViewportTest {
    @Test fun holdsLongWindowUntilMultipleNormalFramesHaveBeenPublished() {
        val gate = TapNestPickupReleaseViewport()
        gate.beginRelease()
        assertFalse(gate.canCollapseAfterNormalFrame())
        assertFalse(gate.canCollapseAfterNormalFrame())
        assertTrue(gate.canCollapseAfterNormalFrame())
        assertTrue(gate.canCollapseAfterNormalFrame())
    }

    @Test fun regrabOrFormReloadCancelsOldResizeAndReleaseRestarts() {
        val gate = TapNestPickupReleaseViewport()
        gate.beginRelease()
        assertFalse(gate.canCollapseAfterNormalFrame())
        gate.cancel()
        gate.beginRelease()
        assertFalse(gate.canCollapseAfterNormalFrame())
        assertFalse(gate.canCollapseAfterNormalFrame())
        assertTrue(gate.canCollapseAfterNormalFrame())
    }

    @Test fun aSingleFramePolicyDoesNotCauseAnEndlessTallWindow() {
        val gate = TapNestPickupReleaseViewport(1)
        gate.beginRelease()
        assertTrue(gate.canCollapseAfterNormalFrame())
    }
}
