package com.deskpet.engine

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json

class TapNestPickupTest {
    @Test fun swayStartsAtTopAnchorNeutralAngle() {
        assertEquals(0f, TapNestPickupMotion.angle(0L, 8f, 1.25f), .0001f)
        assertEquals(8f, TapNestPickupMotion.angle(200L, 8f, 1.25f), .01f)
        assertEquals(0f, TapNestPickupMotion.angle(400L, 8f, 1.25f), .01f)
        assertEquals(-8f, TapNestPickupMotion.angle(600L, 8f, 1.25f), .01f)
    }

    @Test fun ignoresExcessiveWobbleArguments() {
        val excessive = TapNestPickupMotion.angle(125L, 100f, 999f)
        assertTrue(kotlin.math.abs(excessive) <= 24f)
        assertEquals(0f, TapNestPickupMotion.angle(-100L, 8f, 1.25f), .0001f)
    }

    @Test fun oldManualRigRemainsPickupArtDisabled() {
        // Old imported 1-layer rigs stay readable, even though new templates
        // now offer an additional empty, non-destructive clothing slot.
        val oldRig = TapNestRig(canvasWidth = 512, canvasHeight = 512,
            layers = listOf(TapNestLayer("body", "")))
        assertFalse(oldRig.pickupArtworkEnabled)
        val json = Json.encodeToString(TapNestRig.serializer(), oldRig)
        val decoded = Json.decodeFromString(TapNestRig.serializer(), json)
        assertFalse(decoded.pickupArtworkEnabled)
        assertEquals(listOf("body"), decoded.layers.map { it.id })
    }

    @Test fun pickupDefaultHeadroomCoversBothSwayDirectionsAndAnEarAtTopEdge() {
        val width = 512
        val inset = TapNestPickupCanvas.pickupTopClearance(width, 512)
        val rotatedAboveTop = width * .5f *
            kotlin.math.sin(Math.toRadians(8.0)).toFloat()
        assertTrue("negative y of extreme ear fits buffer", inset > rotatedAboveTop + 40f)
        assertEquals(inset, TapNestPickupCanvas.pickupTopClearance(width, 512))
        assertEquals(0, TapNestPickupCanvas.pickupTopClearance(0, 512))
    }

    @Test fun oversizedDedicatedPickupArtGetsExtraHeadroomWithoutHeadResizing() {
        val normal = TapNestPickupCanvas.pickupTopClearance(320, 320)
        val bigArt = TapNestPickupCanvas.pickupTopClearance(320, 320, 500, 1.7f)
        assertTrue("larger separately uploaded art gets extra room above", bigArt > normal)
        assertTrue(TapNestPickupCanvas.pickupTopClearance(64, 64) >= 10)
    }

    @Test fun tallPickupExtendsOnlyBelowTheOriginalTopAnchor() {
        val oldHeight = 320
        val tall = TapNestPickupCanvas.requiredHeight(
            canvasWidth = 320, canvasHeight = oldHeight,
            imageWidth = 320, imageHeight = 700, imageScale = 1f)
        assertTrue("tail plus sway margin must fit", tall > 700)
        assertTrue("the normal canvas height must not change", tall > oldHeight)
        assertEquals(oldHeight, TapNestPickupCanvas.requiredHeight(
            320, 320, 320, 200, 1f))
        assertTrue("larger artwork scale extends only downward",
            TapNestPickupCanvas.requiredHeight(320, 320, 320, 700, 1.5f) > tall)
    }

    @Test fun invalidImageSizesNeverShrinkTheNormalCanvas() {
        assertEquals(420, TapNestPickupCanvas.requiredHeight(320, 420, 0, 700, 1f))
        assertEquals(420, TapNestPickupCanvas.requiredHeight(320, 420, 320, 0, 1f))
    }

    @Test fun pickupArtworkLayerIsIndependentAndHasNoBoneDefaults() {
        val layer = TapNestLayer("pickup_pose", "", zIndex = 100)
        assertTrue(layer.bones.isEmpty())
        assertNull(layer.parentId)
        assertFalse(layer.idleSwing)
        assertFalse(layer.tapShake)
    }
}
