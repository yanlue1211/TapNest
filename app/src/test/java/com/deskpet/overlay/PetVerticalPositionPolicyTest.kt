package com.deskpet.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class PetVerticalPositionPolicyTest {
    @Test fun transparentTopCanMovePastWindowSafeBound() {
        // Artwork starts 120px into a 320px tall rig.
        val f = 120f / 320f
        assertEquals(-92f, PetVerticalPositionPolicy.minWindowY(28f, 320, f), .001f)
        assertEquals(-92f, PetVerticalPositionPolicy.clampY(-500f, 28f, 800f, 320, f), .001f)
        assertEquals(28f, -92f + 320f * f, .001f)
    }

    @Test fun fullyOpaqueArtStillStaysBelowStatusBar() {
        assertEquals(28f, PetVerticalPositionPolicy.clampY(-500f, 28f, 800f, 320, 0f), .001f)
    }

    @Test fun oldLowerClampIsPreserved() {
        assertEquals(480f, PetVerticalPositionPolicy.clampY(10000f, 28f, 800f, 320, .25f), .001f)
    }

    @Test fun safeFractionClampsInvalidValues() {
        assertEquals(28f, PetVerticalPositionPolicy.minWindowY(28f, 320, -1f), .001f)
        assertEquals(-276f, PetVerticalPositionPolicy.minWindowY(28f, 320, 9f), .001f)
    }

    @Test fun zoomScalesTransparentOffsetWithWindow() {
        assertEquals(-92f, PetVerticalPositionPolicy.minWindowY(28f, 320, .375f), .001f)
        assertEquals(-212f, PetVerticalPositionPolicy.minWindowY(28f, 640, .375f), .001f)
    }
}
