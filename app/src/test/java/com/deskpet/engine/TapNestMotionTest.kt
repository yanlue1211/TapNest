package com.deskpet.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class TapNestMotionTest {
    @Test fun interpolatesLinearAndClamps() {
        val clip = TapNestClip(
            "nod", 1000L, tracks = listOf(TapNestTrack("head", listOf(
                TapNestKeyframe(0, TapNestPose(angle = 0f), TapNestEase.LINEAR),
                TapNestKeyframe(1000, TapNestPose(angle = 20f))
            )))
        )
        assertEquals(10f, TapNestMotion.sample(clip, "head", 500).angle, 0.01f)
        assertEquals(0f, TapNestMotion.sample(clip, "head", -1).angle, 0.01f)
        assertEquals(20f, TapNestMotion.sample(clip, "head", 5000).angle, 0.01f)
    }

    @Test fun missingTrackDefaultsToNeutralPose() {
        assertEquals(TapNestPose(), TapNestMotion.sample(TapNestClip("idle", 500), "eyes", 100))
    }

    @Test fun holdKeyframeDoesNotTween() {
        val clip = TapNestClip("blink", 1000, tracks = listOf(TapNestTrack("eye", listOf(
            TapNestKeyframe(0, TapNestPose(scaleY = 1f), TapNestEase.HOLD),
            TapNestKeyframe(1000, TapNestPose(scaleY = 0f))
        ))))
        assertEquals(1f, TapNestMotion.sample(clip, "eye", 900).scaleY, 0.01f)
    }

    @Test fun loopsWithinDuration() {
        val clip = TapNestClip("wag", 1000, loop = true, tracks = listOf(TapNestTrack("tail", listOf(
            TapNestKeyframe(0, TapNestPose(angle = 0f), TapNestEase.LINEAR),
            TapNestKeyframe(1000, TapNestPose(angle = 10f))
        ))))
        assertEquals(5f, TapNestMotion.sample(clip, "tail", 1500).angle, 0.01f)
    }
}
