package com.deskpet.engine

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TapNestIdleCompanionTest {
    @Test fun pairedEarsShareOnePulseEvenWhenOldSaveHadDifferentIntervals() {
        val left = TapNestTemplates.newPart("ear_left", 12).copy(periodicIntervalSeconds = 8f)
        val right = TapNestTemplates.newPart("ear_right", 13).copy(periodicIntervalSeconds = 9f)
        val layers = listOf(left, right)
        assertEquals(0L, TapNestLayerActions.periodicElapsed(left, layers, 8000L))
        assertEquals(0L, TapNestLayerActions.periodicElapsed(right, layers, 8000L))
        assertEquals(300L, TapNestLayerActions.periodicElapsed(left, layers, 8300L))
        assertEquals(300L, TapNestLayerActions.periodicElapsed(right, layers, 8300L))
        assertNull(TapNestLayerActions.periodicElapsed(right, layers, 9000L))
    }

    @Test fun pairedWingCanFollowOnlyEnabledSideAndOptOut() {
        val left = TapNestTemplates.newPart("wing_left", 9)
            .copy(periodicEnabled = true, periodicIntervalSeconds = 6f)
        val right = TapNestTemplates.newPart("wing_right", 10)
            .copy(periodicEnabled = false, periodicIntervalSeconds = 11f)
        assertEquals(0L, TapNestLayerActions.periodicElapsed(right, listOf(left, right), 6000L))
        val independent = right.copy(syncPairedPeriodic = false)
        assertNull(TapNestLayerActions.periodicElapsed(independent, listOf(left, independent), 6000L))
        assertEquals(0L, TapNestLayerActions.periodicElapsed(left, listOf(left, independent), 6000L))
    }

    @Test fun tapAndIdleUseSeparateMotionSelectors() {
        val wing = TapNestTemplates.newPart("wing_left", 8).copy(
            tapMotion = TapNestPartMotion.FLAP,
            periodicMotion = TapNestPartMotion.TWITCH
        )
        assertEquals(
            TapNestMotion.sample(TapNestPresets.flap("wing_left"), "wing_left", 160L),
            TapNestLayerActions.motionPose(wing, 160L)
        )
        assertEquals(
            TapNestMotion.sample(TapNestPresets.twitch("wing_left"), "wing_left", 160L),
            TapNestLayerActions.motionPose(wing, 160L, periodic = true)
        )
        val legacy = wing.copy(periodicMotion = null)
        assertEquals(TapNestLayerActions.motionPose(legacy, 160L),
            TapNestLayerActions.motionPose(legacy, 160L, periodic = true))
    }

    @Test fun mirroredWingUsesOriginalMotionSignUntilIndependentArtwork() {
        val left = TapNestTemplates.newPart("wing_left", 5)
        val right = TapNestSymmetry.mirroredGroup(
            left, emptyList(), "wing_right", "右翅膀").single()
        assertEquals(TapNestLayerActions.motionPose(left, 160L),
            TapNestLayerActions.motionPose(right, 160L))
        val detached = TapNestSymmetry.independentArtwork(right)
        assertEquals(TapNestMotion.sample(TapNestPresets.flap("wing_right"), "wing_right", 160L),
            TapNestLayerActions.motionPose(detached, 160L))
    }

    @Test fun replacingMirroredCloneArtKeepsBindingsButStopsAutoFlip() {
        val left = TapNestTemplates.newPart("ear_left", 6).copy(
            x = 18f, pivotX = .21f,
            bones = listOf(TapNestBonePoint(.18f, .20f), TapNestBonePoint(.83f, .09f)),
            tapMotion = TapNestPartMotion.TWITCH,
            periodicMotion = TapNestPartMotion.FLAP
        )
        val right = TapNestSymmetry.mirroredGroup(
            left, emptyList(), "ear_right", "右耳朵").single()
        assertTrue(right.mirrorX)
        val replaced = TapNestSymmetry.independentArtwork(right)
        assertFalse(replaced.mirrorX)
        assertEquals(left.id, replaced.mirroredFromId)
        assertTrue(replaced.reflectedMotion)
        assertEquals(-18f, replaced.x, .001f)
        assertEquals(.79f, replaced.pivotX, .001f)
        assertEquals(.82f, replaced.bones[0].x, .001f)
        assertEquals(.17f, replaced.bones[1].x, .001f)
        assertEquals(right.tapMotion, replaced.tapMotion)
        assertEquals(right.periodicMotion, replaced.periodicMotion)
        assertEquals(right.bones.map { it.y }, replaced.bones.map { it.y })
        assertEquals(replaced, TapNestSymmetry.independentArtwork(replaced))
    }

    @Test fun freshHatHasTapPopButNoSpontaneousMovementByDefault() {
        val hat = TapNestTemplates.newPart("hat_1", 25)
        assertEquals("head_0", hat.parentId)
        assertEquals(TapNestPartMotion.HAT_POP, hat.tapMotion)
        assertTrue(hat.tapShake)
        assertTrue(hat.interactionConfigured)
        assertFalse(hat.periodicEnabled)
        assertFalse(hat.idleSwing)
        val takeoff = TapNestLayerActions.motionPose(hat, 100L)
        val airborne = TapNestLayerActions.motionPose(hat, 345L)
        val falling = TapNestLayerActions.motionPose(hat, 670L)
        val landed = TapNestLayerActions.motionPose(hat, 810L)
        assertTrue("hat must visibly take off", takeoff.y < -35f)
        assertTrue("hat must rise by over 100 pixels on a 512px canvas", airborne.y < -100f)
        assertTrue("hat must fall back down", falling.y > airborne.y)
        assertTrue("hat must land and gently rebound", landed.y > 0f)
        assertEquals(TapNestPose(), TapNestLayerActions.motionPose(hat, 960L))
        assertEquals(960L, TapNestLayerActions.tapMotionDuration(hat))
        assertEquals(680L, TapNestLayerActions.tapMotionDuration(
            TapNestTemplates.newPart("ear_left", 6)))
        // Same relative lift on large PNG canvases. Old absolute -29px
        // animation was almost invisible on 1024px / 2048px assets.
        assertTrue(TapNestLayerActions.motionPose(hat, 345L,
            canvasHeight = 1024).y < -200f)
        val flyingIdle = hat.copy(periodicEnabled = true,
            periodicMotion = TapNestPartMotion.HAT_POP, periodicIntervalSeconds = 8f)
        assertEquals(950L, TapNestLayerActions.periodicElapsed(flyingIdle, 8950L))
        assertNull(TapNestLayerActions.periodicElapsed(flyingIdle, 8960L))
        val option = hat.copy(periodicMotion = TapNestPartMotion.TWITCH)
        assertEquals(TapNestMotion.sample(TapNestPresets.twitch("hat_1"),
            "hat_1", 160L), TapNestLayerActions.motionPose(option, 160L, periodic = true))
        val stored = Json.decodeFromString(TapNestLayer.serializer(),
            Json.encodeToString(TapNestLayer.serializer(), option))
        assertEquals(option, stored)
    }

    @Test fun breathingIsOptionalSubtleAndSavesWithOldRigs() {
        val older = """{"canvasWidth":512,"canvasHeight":512,"layers":[]}"""
        val oldRig = Json.decodeFromString(TapNestRig.serializer(), older)
        assertFalse(oldRig.idleBreathingEnabled)
        assertEquals(1f, oldRig.idleBreathingStrength, .0001f)
        val zero = TapNestBreathing.pose(0L)
        val inhale = TapNestBreathing.pose(1000L)
        val exhale = TapNestBreathing.pose(3000L)
        assertEquals(1f, zero.scaleX, .0001f)
        assertEquals(1f, zero.scaleY, .0001f)
        assertTrue(inhale.scaleY > 1f && inhale.scaleY < 1.03f)
        assertTrue(exhale.scaleY < 1f && exhale.scaleY > .97f)
        val customized = oldRig.copy(idleBreathingEnabled = true, idleBreathingStrength = .7f)
        val restored = Json.decodeFromString(TapNestRig.serializer(),
            Json.encodeToString(TapNestRig.serializer(), customized))
        assertEquals(customized, restored)
    }
}
