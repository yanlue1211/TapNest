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
        assertEquals(listOf("body"), rig.layers.map { it.id })
        assertTrue(rig.layers.all { it.image == "" })
        assertTrue(rig.layers.single().tapShake)
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
    @Test fun periodicActionWinsWhenBlinkWindowOverlaps() {
        val overlapping = (5000L..120_000L step 10L).first {
            TapNestBlink.isBlinking(it) &&
                TapNestLayerActions.periodicElapsed(left, it) != null
        }
        // Even during a blink interval, the explicitly scheduled expression
        // must be shown as one complete frame, not mixed with a blink frame.
        assertEquals("wing_left_f3", TapNestLayerActions.currentFrame(
            left, frames, overlapping, 3000L, assets))
    }

    @Test fun sceneWideBlinkSuppressionIsHonoredByOtherLayers() {
        val blinking = (1L..30_000L).first(TapNestBlink::isBlinking)
        val local = left.copy(periodicEnabled = false)
        assertEquals("wing_left_f2", TapNestLayerActions.currentFrame(
            local, frames, blinking, 3000L, assets))
        assertEquals("wing_left", TapNestLayerActions.currentFrame(
            local, frames, blinking, 3000L, assets, allowIdleBlink = false))
    }

    @Test fun scheduledExpressionDoesNotInterruptRecentTapExpression() {
        assertEquals("wing_left", TapNestLayerActions.currentFrame(
            left, frames, 5010L, 1700L, assets))
        assertEquals("wing_left_f3", TapNestLayerActions.currentFrame(
            left, frames, 5010L, 1800L, assets))
    }

}
