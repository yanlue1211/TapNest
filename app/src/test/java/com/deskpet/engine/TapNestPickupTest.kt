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
        val oldRig = TapNestTemplates.manual()
        assertFalse(oldRig.pickupArtworkEnabled)
        val json = Json.encodeToString(TapNestRig.serializer(), oldRig)
        val decoded = Json.decodeFromString(TapNestRig.serializer(), json)
        assertFalse(decoded.pickupArtworkEnabled)
        assertEquals(listOf("body"), decoded.layers.map { it.id })
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
