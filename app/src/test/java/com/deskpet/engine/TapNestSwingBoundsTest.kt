package com.deskpet.engine

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class TapNestSwingBoundsTest {
    @Test fun overlargeRigidSwingStillMovesEveryFrameAroundItsPeak() {
        val plane = TapNestMotionPlane(enabled = true, negativeLimit = 8f, positiveLimit = 20f)
        // A raw 60-degree swing with an 8/20-degree allowed range used to
        // stick at both walls for much of the animation.
        val angles = (0..16).map { i ->
            TapNestLayerActions.planePose((60 * sin(i * Math.PI / 16)).toFloat(),
                plane, driveAmplitude = 60f).angle
        }
        assertEquals(20f, angles[8], .001f)
        assertTrue(angles[4] < angles[6] && angles[6] < angles[7] && angles[7] < angles[8])
        assertTrue(angles[8] > angles[9] && angles[9] > angles[10])
        val negatives = (0..16).map { i ->
            TapNestLayerActions.planePose((-60 * sin(i * Math.PI / 16)).toFloat(),
                plane, driveAmplitude = 60f).angle
        }
        assertEquals(-8f, negatives[8], .001f)
        assertTrue(negatives[4] > negatives[6] && negatives[6] > negatives[7])
        assertEquals(0f, angles.first(), .001f)
    }

    @Test fun meshSoftLimitDoesNotFreezeBoneAtTheHardEdge() {
        val plane = TapNestMotionPlane(enabled = true, negativeLimit = 15f, positiveLimit = 15f)
        val layer = TapNestLayer(id = "tail", image = "tail.png", idleSwing = true,
            idlePlane = plane, bones = listOf(TapNestBonePoint(.5f, .1f), TapNestBonePoint(.5f, .9f)))
        fun mesh(degrees: Float) = TapNestFlexibleMesh.vertices(layer, 100, 100, 100L,
            jointAngles = floatArrayOf(degrees), angleDriveAmplitude = 80f)!!
        val a = mesh(45f)
        val b = mesh(70f)
        assertTrue(abs(a.last() - b.last()) > 0.05f ||
            abs(a[a.lastIndex - 1] - b[b.lastIndex - 1]) > 0.05f)
        assertTrue(TapNestSwingBounds.limitAngle(120f, plane, 80f, softStop = true) < 15f)
    }

    @Test fun oversizedSpringMotorRemainsMovingInsteadOfStickingToTheVisualWall() {
        val plane = TapNestMotionPlane(enabled = true, negativeLimit = 12f, positiveLimit = 18f)
        for (version in listOf(1, 2)) {
            val layer = TapNestLayer(id = "tail", image = "tail.png",
                swingMode = TapNestSwingMode.SPRING, springVersion = version,
                idleSwing = true, idleAmplitude = 85f, idleSpeed = .7f,
                springDamping = .42f, idlePlane = plane,
                bones = listOf(TapNestBonePoint(.5f, .8f), TapNestBonePoint(.5f, .45f),
                    TapNestBonePoint(.5f, .1f)))
            val spring = TapNestSpringChain()
            val samples = (0L..4500L step 16L).map { spring.step(layer, it)[0] }
            assertTrue(samples.all { it in -12.001f..18.001f })
            // Previously the spring kept chasing an invisible 85° target.
            // The old exponential mesh limiter hid the overshoot and produced
            // a visually motionless plateau at a narrow range boundary.
            assertTrue("version=$version", samples.distinctBy { (it * 100).toInt() }.size > 100)
            assertTrue(samples.any { it > 14f })
            assertTrue(samples.any { it < -9f })
            val frameLayer = layer.copy(shapePreservation = 0f)
            val mesh = TapNestFlexibleMesh.vertices(frameLayer, 160, 160, 200L,
                jointAngles = floatArrayOf(12f, 0f), angleDriveAmplitude = 0f)!!
            assertTrue(mesh.isNotEmpty())
        }
    }

    @Test fun springMotorResetsAfterRangeChangesAndLeavesUnlimitedLegacyUntouched() {
        val layer = TapNestLayer(id = "tail", image = "tail.png",
            swingMode = TapNestSwingMode.SPRING, springVersion = 2,
            idleAmplitude = 85f, idleSpeed = .6f,
            bones = listOf(TapNestBonePoint(.5f, .8f), TapNestBonePoint(.5f, .3f)))
        val chain = TapNestSpringChain()
        for (t in 0L..700L step 16L) chain.step(layer, t)
        val plane = TapNestMotionPlane(enabled = true, negativeLimit = 6f, positiveLimit = 9f)
        val limited = chain.step(layer.copy(idlePlane = plane), 716L)[0]
        assertTrue(limited in -6.001f..9.001f)
        val unrestricted = TapNestSpringChain()
        val old = unrestricted.step(layer, 500L)[0]
        assertTrue(kotlin.math.abs(old) > 9f)
    }

    @Test fun zeroClockwiseSideUsesWholeCycleInsteadOfFreezingForHalf() {
        val plane = TapNestMotionPlane(enabled = true, negativeLimit = 118.83f, positiveLimit = 0f)
        val frames = (0..24).map { i ->
            val raw = TapNestSwingBounds.periodicDrive(i * 2.0 * Math.PI / 24.0, 40f, plane)
            TapNestLayerActions.planePose(raw, plane, 40f).angle
        }
        assertEquals(0f, frames.first(), .001f)
        assertEquals(-40f, frames[12], .001f)
        assertEquals(0f, frames.last(), .001f)
        assertTrue(frames.drop(1).dropLast(1).all { it < -.0001f })
        assertTrue(frames[3] > frames[5] && frames[5] > frames[8])
        assertTrue(frames[16] < frames[20] && frames[20] < frames[23])
    }

    @Test fun zeroCounterclockwiseSideUsesOneFullPeriodAndLimitedAmplitude() {
        val plane = TapNestMotionPlane(enabled = true, negativeLimit = 0f, positiveLimit = 15f)
        val frames = (0..24).map { i ->
            TapNestLayerActions.planePose(
                TapNestSwingBounds.periodicDrive(i * 2.0 * Math.PI / 24.0, 85f, plane),
                plane, 85f).angle
        }
        assertEquals(15f, frames[12], .001f)
        assertTrue(frames.drop(1).dropLast(1).all { it > 0f && it <= 15f })
        assertTrue(frames[10] < frames[12] && frames[12] > frames[14])
    }

    @Test fun rigidAndBoneDriveKeepTwoSidedMovementUnchanged() {
        val plane = TapNestMotionPlane(enabled = true, negativeLimit = 40f, positiveLimit = 40f)
        val samples = (0..12).map { i ->
            TapNestSwingBounds.periodicDrive(i * 2.0 * Math.PI / 12, 35f, plane, -1f)
        }
        assertTrue(samples.take(6).all { it <= .0001f })
        assertTrue(samples.drop(7).all { it >= -.0001f })
        val tail = TapNestLayer(id = "tail", image = "tail.png", idleSwing = true,
            idleAmplitude = 35f, idlePlane = TapNestMotionPlane(
                enabled = true, negativeLimit = 30f, positiveLimit = 0f),
            bones = listOf(TapNestBonePoint(.5f, .9f), TapNestBonePoint(.5f, .1f)))
        val single = (0L..2000L step 100L).map {
            TapNestFlexibleMesh.segmentAngles(tail, it)[0]
        }
        assertTrue(single.drop(1).take(19).all { it <= 0f })
        assertTrue(single.any { it < -20f })
    }

    @Test fun oneSidedSpringMotorKeepsMovingAtBothSpringVersions() {
        for (version in listOf(1, 2)) {
            val plane = TapNestMotionPlane(enabled = true, negativeLimit = 50f, positiveLimit = 0f)
            val tail = TapNestLayer(id = "tail", image = "tail.png", idleSwing = true,
                idleAmplitude = 40f, idleSpeed = .7f, springVersion = version,
                bones = listOf(TapNestBonePoint(.5f, .9f), TapNestBonePoint(.5f, .1f)),
                swingMode = TapNestSwingMode.SPRING, idlePlane = plane)
            val engine = TapNestSpringChain()
            val samples = (0L..4000L step 20L).map { engine.step(tail, it)[0] }
            assertTrue("v=$version", samples.count { it < -8f } > 40)
            assertTrue("v=$version", samples.distinctBy { (it * 100).toInt() }.size > 100)
            assertTrue("v=$version", samples.all { it <= .001f && it >= -50.001f })
        }
    }

    @Test fun legacyUnrestrictedMotionAndStraightMotionAreUnchanged() {
        val free = TapNestMotionPlane()
        assertEquals(75f, TapNestSwingBounds.limitAngle(75f, free, 80f, true), .0001f)
        val linear = TapNestMotionPlane(enabled = true, linear = true, axisDegrees = 45f,
            negativeLimit = 3f, positiveLimit = 3f)
        assertEquals(75f, TapNestSwingBounds.limitAngle(75f, linear, 80f, true), .0001f)
        val capped = TapNestMotionPlane(enabled = true, negativeLimit = 5f, positiveLimit = 20f)
        assertEquals(-5f, TapNestLayerActions.planePose(-50f, capped).angle, .0001f)
        assertEquals(20f, TapNestLayerActions.planePose(50f, capped).angle, .0001f)
    }
}
