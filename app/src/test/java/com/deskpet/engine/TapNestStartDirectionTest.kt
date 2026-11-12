package com.deskpet.engine

import org.junit.Assert.*
import org.junit.Test

class TapNestStartDirectionTest {
    @Test fun touchAndPeriodicStartDirectionsAreIndependentForEveryShake() {
        for (motion in TapNestPartMotion.entries.filter { it != TapNestPartMotion.HAT_POP }) {
            for (configured in listOf(false, true)) {
                val original = TapNestLayer("ear_left", "", tapMotion = motion,
                    periodicMotion = motion, tapBasicConfigured = configured,
                    periodicBasicConfigured = configured)
                val reversed = original.copy(tapStartReversed = true)
                val a = TapNestLayerActions.motionPose(original, 120L)
                val b = TapNestLayerActions.motionPose(reversed, 120L)
                assertEquals(-a.angle, b.angle, .001f)
                assertEquals(-a.x, b.x, .001f)
                assertEquals(-a.y, b.y, .001f)
                assertEquals(TapNestLayerActions.motionPose(original, 120L, true),
                    TapNestLayerActions.motionPose(reversed, 120L, true))
                assertEquals(a, TapNestLayerActions.motionPose(reversed.copy(tapStartReversed = false), 120L))
            }
        }
    }

    @Test fun directionReversesFlexibleDriveAndIsCopiedWithTheChain() {
        val original = TapNestLayer("ribbon", "", idleSwing = true,
            bones = listOf(TapNestBonePoint(.2f, .2f), TapNestBonePoint(.3f, .7f)),
            interactionConfigured = true, tapShake = true,
            tapFlexible = TapNestImpulse(enabled = true))
        val reversed = original.copy(idleStartReversed = true, tapStartReversed = true)
        assertEquals(-TapNestFlexibleMesh.segmentAngles(original, 180L)[0],
            TapNestFlexibleMesh.segmentAngles(reversed, 180L)[0], .001f)
        assertEquals(-TapNestSpringChain().step(original, 180L)[0],
            TapNestSpringChain().step(reversed, 180L)[0], .001f)
        assertEquals(-TapNestLayerActions.jointAngles(original, listOf(original), 0L, 120L, null)!![0],
            TapNestLayerActions.jointAngles(reversed, listOf(reversed), 0L, 120L, null)!![0], .001f)
        val child = TapNestBoneChain(startReversed = true).asLayer(original)
        assertTrue(child.idleStartReversed)
        assertTrue(child.tapStartReversed)
        assertTrue(child.periodicStartReversed)
    }
}
