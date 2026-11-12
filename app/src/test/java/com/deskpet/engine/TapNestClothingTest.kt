package com.deskpet.engine

import org.junit.Assert.*
import org.junit.Test

class TapNestClothingTest {
    @Test fun manualOutfitIsBlankAndInheritsBodyByDefault() {
        val rig = TapNestTemplates.manual()
        val body = requireNotNull(rig.layers.firstOrNull { it.id == "body" })
        val clothing = requireNotNull(rig.layers.firstOrNull { it.bodyLinkedClothing })
        assertEquals(TapNestClothing.ID, clothing.id)
        assertEquals("", clothing.image)
        assertEquals(body.id, clothing.parentId)
        assertEquals(1f, clothing.restScale)
        assertEquals(0f, clothing.x)
        assertEquals(0f, clothing.y)
        assertFalse(clothing.idleSwing)
        assertFalse(clothing.interactionConfigured)
        assertEquals(body.zIndex + 1, TapNestClothing.sortZ(clothing, rig.layers.associateBy { it.id }))
    }

    @Test fun oldRigGainsAnEmptySlotWithoutTouchingExistingData() {
        val body = TapNestLayer("body", "original.png", x = 13f,
            pivotY = .4f, idleSwing = true, idleAmplitude = 25f,
            interactionConfigured = true, tapShake = true)
        val ear = TapNestLayer("ear_left", "ear.png", parentId = "body", mirrorX = true)
        val old = TapNestRig(canvasWidth = 500, canvasHeight = 800, layers = listOf(body, ear))
        val upgraded = TapNestClothing.withSlot(old)
        assertEquals(old.layers, upgraded.layers.take(2))
        assertEquals(3, upgraded.layers.size)
        assertEquals(upgraded, TapNestClothing.withSlot(upgraded))
        assertEquals("original.png", upgraded.layers[0].image)
        assertEquals("ear.png", upgraded.layers[1].image)
        assertEquals("", upgraded.layers[2].image)
    }

    @Test fun bodyMotionAndGeometryAlwaysResolveLiveAfterEdits() {
        val first = TapNestTemplates.manual()
        val clothing = first.layers.first { it.bodyLinkedClothing }
        val moved = first.copy(layers = first.layers.map {
            if (it.id == "body") it.copy(x = 27f, restScale = 1.6f,
                idleAmplitude = 40f, idleSpeed = 1.7f, tapShake = false,
                bones = listOf(TapNestBonePoint(.1f, .1f), TapNestBonePoint(.8f, .7f)),
                idlePlane = TapNestMotionPlane(enabled = true, negativeLimit = 13f))
            else it
        })
        val active = TapNestClothing.skinOwner(clothing, moved.layers.associateBy { it.id })
        assertEquals(27f, active.x)
        assertEquals(1.6f, active.restScale)
        assertEquals(40f, active.idleAmplitude)
        assertEquals(1.7f, active.idleSpeed)
        assertFalse(active.tapShake)
        assertEquals(2, active.bones.size)
        assertEquals(13f, active.idlePlane.negativeLimit)
        assertTrue(clothing.bones.isEmpty()) // no shadow memory to become stale
    }

    @Test fun linkedOutfitUsesTheBodyArtCanvasAndFollowsZOrder() {
        val rig = TapNestTemplates.manual()
        val clothing = rig.layers.first { it.bodyLinkedClothing }
        val changedBody = rig.layers.first { it.id == "body" }.copy(zIndex = 80)
        val nodes = mapOf("body" to changedBody, clothing.id to clothing)
        assertEquals(81, TapNestClothing.sortZ(clothing, nodes))
        assertEquals(512 to 512, TapNestClothing.drawingSize(clothing, 300, 700, 512 to 512))
        assertEquals(300 to 700, TapNestClothing.drawingSize(
            clothing.copy(bodyLinkedClothing = false), 300, 700, 512 to 512))
    }

    @Test fun missingBodyAndExistingCustomLayersAreNotClobbered() {
        val withoutBody = TapNestRig(canvasWidth = 64, canvasHeight = 64,
            layers = listOf(TapNestLayer("head_0", "head.png")))
        assertEquals(withoutBody, TapNestClothing.withSlot(withoutBody))
        val custom = TapNestRig(canvasWidth = 64, canvasHeight = 64,
            layers = listOf(TapNestLayer("body", "body.png"),
                TapNestLayer(TapNestClothing.ID, "my_custom.png", parentId = null)))
        assertEquals(custom, TapNestClothing.withSlot(custom))
    }
}
