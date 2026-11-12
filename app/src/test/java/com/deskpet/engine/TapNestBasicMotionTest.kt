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
    @Test fun quarterScopesFollowLengthRatherThanPointCount() {
        val part = TapNestLayer("ribbon", "r.png", bones = listOf(
            TapNestBonePoint(.5f, 0f), TapNestBonePoint(.5f, .1f),
            TapNestBonePoint(.5f, .5f), TapNestBonePoint(.5f, 1f)))
        assertEquals(0f, TapNestLayerActions.scopeWeight(part, 0, TapNestMotionScope.MIDDLE), .001f)
        assertEquals(0f, TapNestLayerActions.scopeWeight(part, 1, TapNestMotionScope.MIDDLE), .001f)
        assertEquals(1f, TapNestLayerActions.scopeWeight(part, 2, TapNestMotionScope.MIDDLE), .001f)
    }
    @Test fun tapEditingDoesNotAlterPeriodicLegacyPose() {
        val part = TapNestLayer("wing_left", "wing.png", tapBasicConfigured = true,
            tapFlexible = TapNestImpulse(amplitude = 40f))
        val legacy = part.copy(tapBasicConfigured = false)
        assertEquals(TapNestLayerActions.motionPose(legacy, 100L, periodic = true),
            TapNestLayerActions.motionPose(part, 100L, periodic = true))
    }
    @Test fun arbitraryPlaneSupportsDiagonalAndAsymmetricRotation() {
        val diagonal = TapNestLayerActions.planePose(10f, TapNestMotionPlane(enabled = true, linear = true, axisDegrees = 45f))
        assertEquals(diagonal.x, diagonal.y, .001f)
        assertTrue(diagonal.x > 0f)
        val plane = TapNestMotionPlane(enabled = true, negativeLimit = 5f, positiveLimit = 20f)
        assertEquals(-5f, TapNestLayerActions.planePose(-50f, plane).angle, .001f)
        assertEquals(20f, TapNestLayerActions.planePose(50f, plane).angle, .001f)
        assertEquals(TapNestPose(), TapNestLayerActions.planePose(0f, plane))
    }

    @Test fun separateRibbonRegionLeavesOtherSideAndBowCenterFixed() {
        val extra = TapNestBoneChain(bones = listOf(TapNestBonePoint(.75f, .4f), TapNestBonePoint(.75f, .9f)),
            region = TapNestBoneRegion(.6f, .4f, .95f, .95f))
        val layer = TapNestLayer("bow", "b.png", idleSwing = true, boneChains = listOf(extra))
        val result = TapNestFlexibleMesh.vertices(layer, 100, 100, 100L, 20, extraAngles = listOf(floatArrayOf(30f)))!!
        val left = (15 * 21 + 5) * 2
        assertEquals(25f, result[left], .001f)
        assertEquals(75f, result[left + 1], .001f)
        val right = (15 * 21 + 15) * 2
        assertTrue(kotlin.math.abs(result[right] - 75f) > 1f)
        val center = (8 * 21 + 10) * 2
        assertEquals(50f, result[center], .001f)
        assertEquals(40f, result[center + 1], .001f)
    }

    @Test fun synchronizationPreservesPartnerArtworkAndPlacement() {
        val source = TapNestLayer("ear_left", "l.png", idleSpeed = 1.8f, tapFlexible = TapNestImpulse(amplitude = 32f))
        val target = TapNestLayer("ear_right", "r.png", x = 80f, mirrorX = true, mirroredFromId = source.id)
        val synced = TapNestSymmetry.synchronizedMotion(source, target)
        assertEquals(target.image, synced.image)
        assertEquals(target.x, synced.x)
        assertEquals(target.mirrorX, synced.mirrorX)
        assertEquals(source.tapFlexible, synced.tapFlexible)
        assertEquals(source.idleSpeed, synced.idleSpeed)
    }

}
