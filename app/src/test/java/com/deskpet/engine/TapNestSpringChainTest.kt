package com.deskpet.engine

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class TapNestSpringChainTest {
    private fun tail() = TapNestLayer(
        id = "tail", image = "tail.png",
        bones = listOf(TapNestBonePoint(0.5f, 0.8f),
            TapNestBonePoint(0.5f, 0.65f),
            TapNestBonePoint(0.5f, 0.50f),
            TapNestBonePoint(0.5f, 0.40f)),
        idleSwing = true, idleAmplitude = 65f, idleSpeed = 0.5f,
        swingMode = TapNestSwingMode.SPRING,
        springSoftness = 0.78f, springDamping = 0.38f
    )

    private fun advance(tail: TapNestLayer, frameMs: Int, endMs: Int): FloatArray {
        val spring = TapNestSpringChain()
        spring.step(tail, 0)
        var t = frameMs
        while (t < endMs) {
            spring.step(tail, t.toLong())
            t += frameMs
        }
        return spring.step(tail, endMs.toLong())
    }

    @Test fun baseLeadsTipWhenSwingStarts() {
        val angles = advance(tail(), 16, 192)
        val root = angles[0]
        val middleAbsolute = angles[0] + angles[1]
        val tipAbsolute = middleAbsolute + angles[2]
        assertTrue("root leads", root > middleAbsolute)
        assertTrue("middle leads tip", middleAbsolute > tipAbsolute)
        assertTrue("root swings noticeably", root > 25f)
    }

    @Test fun tipContinuesToTheSideWhenRootHasReversed() {
        // At 992ms for 0.5 cycles/sec the root has nearly returned to zero,
        // while tip inertia leaves its absolute angle still substantially positive.
        val angles = advance(tail(), 16, 992)
        val root = angles[0]
        val tipAbsolute = angles.sum()
        assertTrue("root has turned around", abs(root) < 5f)
        assertTrue("tip still trails on the original side", tipAbsolute > 20f)
    }

    @Test fun theSpringIsIndependentOfRenderFrameRate() {
        val at60 = advance(tail(), 16, 1700)
        val at30 = advance(tail(), 33, 1700)
        assertEquals(at60.sum(), at30.sum(), 3f)
        assertEquals(at60[0], at30[0], 0.2f)
    }

    @Test fun allModesKeepCompatibleSerializableSettings() {
        val legacy = TapNestLayer(id = "tail", image = "tail.png")
        assertEquals(TapNestSwingMode.DELAY, legacy.swingMode)
        val json = Json.encodeToString(TapNestLayer.serializer(), tail())
        val parsed = Json.decodeFromString(TapNestLayer.serializer(), json)
        assertEquals(TapNestSwingMode.SPRING, parsed.swingMode)
        assertEquals(0.78f, parsed.springSoftness, 0.001f)
        assertEquals(0.38f, parsed.springDamping, 0.001f)
    }

    @Test fun oneSidedUpRightTailNeverSwingsInward() {
        val original = tail().copy(
            swingRange = TapNestSwingRange.OUTWARD_ONLY,
            bones = listOf(TapNestBonePoint(0.6f, 0.9f),
                TapNestBonePoint(0.7f, 0.7f),
                TapNestBonePoint(0.8f, 0.5f))
        )
        val chain = TapNestSpringChain()
        var outwardMotionSeen = false
        for (time in 0..2500 step 20) {
            val angles = chain.step(original, time.toLong())
            var absolute = 0f
            for (a in angles) {
                absolute += a
                assertTrue("right tail stayed outward at $time: $absolute", absolute >= -0.001f)
            }
            if (absolute > 5f) outwardMotionSeen = true
        }
        assertTrue("single-side tail still moves", outwardMotionSeen)
    }

    @Test fun oneSidedUpLeftTailMirrorsOutwardDirection() {
        val original = tail().copy(
            swingRange = TapNestSwingRange.OUTWARD_ONLY,
            bones = listOf(TapNestBonePoint(0.4f, 0.9f),
                TapNestBonePoint(0.3f, 0.7f),
                TapNestBonePoint(0.2f, 0.5f))
        )
        val chain = TapNestSpringChain()
        for (time in 0..2500 step 20) {
            val angles = chain.step(original, time.toLong())
            var absolute = 0f
            for (a in angles) {
                absolute += a
                assertTrue("left tail stayed outward at $time: $absolute", absolute <= 0.001f)
            }
        }
    }

    @Test fun disablingAmplitudeRemainsStillAndResumingAfterPauseIsStable() {
        val layer = tail().copy(idleAmplitude = 0f)
        val chain = TapNestSpringChain()
        for (i in 0..100) {
            val angles = chain.step(layer, i * 33L)
            assertEquals(0f, angles.sum(), 0.01f)
        }
        val resumed = chain.step(tail(), 30_000L)
        assertTrue(resumed.all { it.isFinite() && abs(it) < 120f })
    }
}
