package com.deskpet.engine

import org.junit.Assert.*
import org.junit.Test

class TapNestTapSequenceTest {
    @Test fun fifthTapTriggersAndStrictThreeSecondGapResets() {
        val sequence = TapNestTapSequence()
        for (i in 0..3) assertFalse(sequence.tap(i * 2999L))
        assertTrue(sequence.tap(4 * 2999L))
        assertTrue(sequence.tap(5 * 2999L))
        assertFalse(sequence.tap(5 * 2999L + 3000L))
        sequence.reset()
        assertFalse(sequence.tap(0L))
    }

    @Test fun separatePetsAndTimeReversalDoNotShareCounts() {
        val a = TapNestTapSequence()
        val b = TapNestTapSequence()
        repeat(4) { a.tap(100L + it) }
        assertFalse(b.tap(104L))
        assertTrue(a.tap(104L))
        assertFalse(a.tap(10L))
    }

    @Test fun angryFrameOverridesOnlyItsOwnerAndExpiresSafely() {
        val owner = TapNestLayer("head", "", interactionConfigured = false)
        val frame = TapNestLayer("head_f1", "", frameOwner = "head", frameIndex = 1)
        val other = TapNestLayer("ear", "")
        val layers = listOf(owner, frame, other)
        val available = layers.map { it.id }.toSet()
        assertEquals(frame.id, TapNestLayerActions.currentFrame(owner, layers, 0L, 10L, available, rapidTapAngryFrameId = frame.id))
        assertEquals(other.id, TapNestLayerActions.currentFrame(other, layers, 0L, 10L, available, rapidTapAngryFrameId = frame.id))
        assertEquals(owner.id, TapNestLayerActions.currentFrame(owner, layers, 0L, 1800L, available, rapidTapAngryFrameId = frame.id))
        assertEquals(owner.id, TapNestLayerActions.currentFrame(owner, layers, 0L, 10L, setOf(owner.id), rapidTapAngryFrameId = frame.id))
    }
    @Test fun alternativeFramePinsItsLegacyHeadOwnerInsteadOfCyclingAway() {
        val rig = TapNestRig(canvasWidth = 64, canvasHeight = 64,
            rapidTapAngryFrameId = "head_0_f1", layers = listOf(
                TapNestLayer("head_0", ""),
                TapNestLayer("head_0_f1", "", frameOwner = "head_0", frameIndex = 1)))
        assertEquals(0, TapNestLayerActions.legacyAngryExpression(rig, setOf("head_0_f1")))
        assertNull(TapNestLayerActions.legacyAngryExpression(rig, emptySet()))
    }
}
