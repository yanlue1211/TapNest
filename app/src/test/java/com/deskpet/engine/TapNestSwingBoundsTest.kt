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
