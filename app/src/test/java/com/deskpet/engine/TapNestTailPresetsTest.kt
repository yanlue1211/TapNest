package com.deskpet.engine

import org.junit.Assert.*
import org.junit.Test

class TapNestTailPresetsTest {
    @Test fun allFourPresetsHaveExpectedPointCountAndRanges() {
        assertEquals(4, TapNestTailPresets.ALL.size)
        assertEquals(listOf(4, 5, 4, 5), TapNestTailPresets.ALL.map { it.bonePercentages.size })
        assertEquals(listOf(TapNestSwingRange.CENTERED, TapNestSwingRange.CENTERED,
            TapNestSwingRange.OUTWARD_ONLY, TapNestSwingRange.OUTWARD_ONLY),
            TapNestTailPresets.ALL.map { it.swingRange })
        assertTrue(TapNestTailPresets.ALL.all { it.bonePercentages.first() == 0 &&
            it.bonePercentages.last() in 85..90 && it.bonePercentages == it.bonePercentages.sorted() })
    }

    @Test fun dogPresetMatchesHandTunedPictureValues() {
        val dog = TapNestTailPresets.ALL.first()
        assertEquals(85f, dog.amplitude, 0.001f)
        assertEquals(0.48f, dog.speed, 0.001f)
        assertEquals(0.85f, dog.softness, 0.001f)
        assertEquals(0.45f, dog.damping, 0.001f)
        assertEquals(0.30f, dog.preservation, 0.001f)
        assertEquals(listOf(0, 28, 57, 85), dog.bonePercentages)
    }

    @Test fun applyOnlySettingsNeverErasesExistingPointsOrLayerIdentity() {
        val before = TapNestLayer(id = "my_custom_tail", image = "mytail.png",
            x = 27f, y = 44f, zIndex = 4, parentId = "body",
            bones = listOf(TapNestBonePoint(.8f, .9f), TapNestBonePoint(.9f, .4f)))
        for (preset in TapNestTailPresets.ALL) {
            val result = TapNestTailPresets.applySettings(before, preset)
            assertEquals(before.bones, result.bones)
            assertEquals(before.image, result.image)
            assertEquals(before.parentId, result.parentId)
            assertEquals(before.x, result.x)
            assertEquals(before.zIndex, result.zIndex)
            assertEquals(preset.swingRange, result.swingRange)
            assertEquals(TapNestSwingMode.SPRING, result.swingMode)
            assertEquals(preset.amplitude, result.idleAmplitude)
        }
    }

    @Test fun boneSuggestionMirrorsDiagonalAndPreservesAnchors() {
        val root = TapNestBonePoint(.8f, .9f)
        val tip = TapNestBonePoint(.3f, .1f)
        for (preset in TapNestTailPresets.ALL) {
            val result = TapNestTailPresets.repositionBones(listOf(root, tip), preset)
            assertEquals(preset.bonePercentages.size, result.size)
            assertEquals(root, result.first())
            assertEquals(tip, result.last())
            assertTrue(result.zipWithNext().all { (a, b) -> a.x > b.x && a.y > b.y })
        }
    }

    @Test fun existingCurvedPolylineIsResampledAlongTheCurve() {
        val points = listOf(TapNestBonePoint(.2f,.9f), TapNestBonePoint(.7f,.9f),
            TapNestBonePoint(.7f,.2f))
        val cat = TapNestTailPresets.ALL[1]
        val result = TapNestTailPresets.repositionBones(points, cat)
        assertEquals(5, result.size)
        assertEquals(points.first(), result.first())
        assertEquals(points.last(), result.last())
        assertTrue(result[1].x > .2f)
        assertTrue(result[2].x >= result[1].x)
        assertTrue(result[2].y <= .9f)
    }

    @Test fun emptyOrDegeneratePointsRemainUntouched() {
        val preset = TapNestTailPresets.ALL[0]
        assertTrue(TapNestTailPresets.repositionBones(emptyList(), preset).isEmpty())
        val root = TapNestBonePoint(.5f, .5f)
        assertEquals(listOf(root), TapNestTailPresets.repositionBones(listOf(root), preset))
        assertEquals(listOf(root, root),
            TapNestTailPresets.repositionBones(listOf(root, root), preset))
    }
}
