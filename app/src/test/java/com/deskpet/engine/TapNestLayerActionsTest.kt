package com.deskpet.engine

import org.junit.Assert.*
import org.junit.Test

class TapNestLayerActionsTest {
    private val left = TapNestLayer(
        id = "wing_left", image = "wing_left.png",
        interactionConfigured = true, tapSwitchFrame = true,
        tapFrame = 1, idleBlinkEnabled = true, blinkFrame = 2,
        periodicEnabled = true, periodicIntervalSeconds = 5f,
        periodicAction = TapNestPeriodicAction.SWITCH_FRAME, periodicFrame = 3
    )
    private val frames = listOf(left,
        TapNestLayer("wing_left_f1", "one.png", parentId = "wing_left",
            frameOwner = "wing_left", frameIndex = 1),
        TapNestLayer("wing_left_f2", "two.png", parentId = "wing_left",
            frameOwner = "wing_left", frameIndex = 2),
        TapNestLayer("wing_left_f3", "three.png", parentId = "wing_left",
            frameOwner = "wing_left", frameIndex = 3)
    )
    private val assets = frames.map { it.id }.toSet()

    @Test fun publicManualTemplateOnlyIncludesEverydayMovingParts() {
        val rig = TapNestTemplates.manual()
        assertEquals(TapNestTemplates.MANUAL, rig.templateId)
        assertEquals(setOf("body", "head_0", "tail", "wing_left", "wing_right",
            "ear_left", "ear_right"), rig.layers.map { it.id }.toSet())
        assertTrue(rig.layers.all { it.image == "" })
        assertTrue(rig.layers.any { it.id == "wing_left" && it.idleSwing })
        assertTrue(rig.layers.any { it.id == "ear_left" && it.periodicEnabled })
        assertTrue(rig.layers.any { it.id == "body" && it.tapShake })
    }

    @Test fun symmetricOptionalPartCreationNeverIncludesArtwork() {
        val a = TapNestTemplates.newPart("eye_left", 22)
        val b = TapNestTemplates.newPart("eye_right", 23)
        assertEquals("", a.image)
        assertEquals("", b.image)
        assertEquals("head_0", a.parentId)
        assertEquals("head_0", b.parentId)
        assertEquals(1f, a.pivotX + b.pivotX, .001f)
    }

    @Test fun tapSelectsAlternateAndMissingImageFallsBack() {
        assertEquals("wing_left_f1", TapNestLayerActions.currentFrame(
            left, frames, 2500L, 240L, assets))
        assertEquals("wing_left", TapNestLayerActions.currentFrame(
            left, frames, 2500L, 240L, setOf("wing_left")))
    }

    @Test fun periodicDoesNotStartImmediately() {
        assertNull(TapNestLayerActions.periodicElapsed(left, 0L))
        assertNull(TapNestLayerActions.periodicElapsed(left, 4999L))
        assertEquals(0L, TapNestLayerActions.periodicElapsed(left, 5000L))
        assertNull(TapNestLayerActions.periodicElapsed(left, 5750L))
        assertEquals("wing_left_f3", TapNestLayerActions.currentFrame(
            left, frames, 5010L, 3000L, assets))
    }

    @Test fun oldRigsRetainOriginalNoAutoSwitchBehavior() {
        val old = left.copy(interactionConfigured = false)
        assertEquals("wing_left", TapNestLayerActions.currentFrame(
            old, frames, 5010L, 100L, assets))
        assertNull(TapNestLayerActions.periodicElapsed(old, 5000L))
    }
}
