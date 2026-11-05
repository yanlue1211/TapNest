package com.deskpet.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class TapNestPresetsTest {
    @Test fun blinkOnlyChangesItsOwnEyeTrack() {
        val clip = TapNestPresets.blink("my-eyes")
        assertEquals(1, clip.tracks.size)
        assertEquals("my-eyes", clip.tracks[0].layerId)
        assertEquals(0.08f, TapNestMotion.sample(clip, "my-eyes", 145).scaleY, 0.01f)
        assertEquals(1f, TapNestMotion.sample(clip, "body", 145).scaleY, 0.01f)
    }

    @Test fun wagLoopsWithoutArtwork() {
        val clip = TapNestPresets.wag("my-tail")
        assertEquals(15f, TapNestMotion.sample(clip, "my-tail", 400).angle, 0.01f)
        assertEquals(15f, TapNestMotion.sample(clip, "my-tail", 1200).angle, 0.01f)
    }

    @Test fun nodAndEarsReturnToNeutral() {
        assertEquals(0f, TapNestMotion.sample(TapNestPresets.nod("head"), "head", 560).angle, 0.01f)
        val ears = TapNestPresets.earTwitch("L", "R")
        assertEquals(0f, TapNestMotion.sample(ears, "L", 460).angle, 0.01f)
        assertEquals(0f, TapNestMotion.sample(ears, "R", 460).angle, 0.01f)
    }
}
