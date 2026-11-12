package com.deskpet.engine

import org.junit.Assert.*
import org.junit.Test

class TapNestBasicMotionTest {
    @Test fun rigidAndFlexibleShareBasicParametersWithoutEnablingBones() {
        val part = TapNestLayer("ear_left", "ear.png", tapShake = true,
            tapBasicConfigured = true, tapFlexible = TapNestImpulse(amplitude = 10f))
        val bigger = part.copy(tapFlexible = part.tapFlexible.copy(amplitude = 20f))
        assertFalse(part.tapFlexible.enabled)
        assertEquals(2f * TapNestLayerActions.motionPose(part, 100L).angle,
            TapNestLayerActions.motionPose(bigger, 100L).angle, .001f)
        assertEquals(TapNestPose(), TapNestLayerActions.motionPose(part, 680L))
        assertEquals(TapNestPose(), TapNestLayerActions.motionPose(
            part.copy(tapFlexible = part.tapFlexible.copy(amplitude = 0f)), 100L))
        assertNotEquals(TapNestLayerActions.motionPose(part, 100L),
            TapNestLayerActions.motionPose(part.copy(tapFlexible = part.tapFlexible.copy(speed = 5f)), 100L))
    }
    @Test fun tapEditingDoesNotAlterPeriodicLegacyPose() {
        val part = TapNestLayer("wing_left", "wing.png", tapBasicConfigured = true,
            tapFlexible = TapNestImpulse(amplitude = 40f))
        val legacy = part.copy(tapBasicConfigured = false)
        assertEquals(TapNestLayerActions.motionPose(legacy, 100L, periodic = true),
            TapNestLayerActions.motionPose(part, 100L, periodic = true))
    }
}
