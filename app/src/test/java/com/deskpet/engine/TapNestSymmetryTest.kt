package com.deskpet.engine

import org.junit.Assert.*
import org.junit.Test

class TapNestSymmetryTest {
    @Test fun clonePreservesEverySettingAndCopiesActionFrames() {
        val source = TapNestTemplates.newPart("wing_left", 12).copy(
            image = "wing_left.png", displayName = "左边翅膀",
            x = 25f, pivotX = .18f, idleAmplitude = 27f,
            bones = listOf(TapNestBonePoint(.2f, .3f), TapNestBonePoint(.9f, .1f)),
            tapSwitchFrame = true, periodicEnabled = true
        )
        val frame = TapNestLayer("wing_left_f1", "a.png",
            parentId = "wing_left", frameOwner = "wing_left", frameIndex = 1)
        val result = TapNestSymmetry.mirroredGroup(source, listOf(frame), "wing_right", "右边翅膀")
        assertEquals(2, result.size)
        val right = result[0]
        assertEquals("wing_right", right.id)
        assertEquals("右边翅膀", right.displayName)
        assertTrue(right.mirrorX)
        assertEquals(source.x, right.x, .001f)
        assertEquals(source.bones, right.bones)
        assertEquals(source.idleAmplitude, right.idleAmplitude, .001f)
        assertEquals(source.tapSwitchFrame, right.tapSwitchFrame)
        assertEquals(source.periodicEnabled, right.periodicEnabled)
        assertEquals("wing_left", right.mirroredFromId)
        assertEquals("wing_right_f1", result[1].id)
        assertEquals("wing_right", result[1].frameOwner)
        assertTrue(result[1].mirrorX)
    }

    @Test fun copiedLeftWingSharesLocalPhaseBeforeCanvasReflection() {
        val left = TapNestTemplates.newPart("wing_left", 5)
        val right = TapNestSymmetry.mirroredGroup(
            left, emptyList(), "wing_right", "右翅膀"
        ).single()
        assertEquals(-1f, TapNestSymmetry.idleSwingSign(left), 0f)
        assertEquals(-1f, TapNestSymmetry.idleSwingSign(right), 0f)
        assertTrue(right.mirrorX)
        // Non-mirrored wings uploaded independently preserve legacy signs.
        val separateRight = TapNestTemplates.newPart("wing_right", 5)
        assertEquals(1f, TapNestSymmetry.idleSwingSign(separateRight), 0f)
    }

    @Test fun replacingCopiedArtworkDoesNotChangeBoneOrAnimationSettings() {
        val left = TapNestLayer("wing_left", "", bones = listOf(
            TapNestBonePoint(.24f, .31f),
            TapNestBonePoint(.80f, .17f)
        ), idleAmplitude = 26f, pivotX = .24f, tapSwitchFrame = true)
        val right = TapNestSymmetry.mirroredGroup(
            left, emptyList(), "wing_right", "右翅膀"
        ).single()
        // Editor's PNG picker updates the independent URI map, never the rig.
        val withDifferentArt = right.copy(image = "different_right_wing.png")
        assertEquals(right.bones, withDifferentArt.bones)
        assertEquals(right.pivotX, withDifferentArt.pivotX, 0.0001f)
        assertEquals(right.idleAmplitude, withDifferentArt.idleAmplitude, 0.0001f)
        assertEquals(right.mirrorX, withDifferentArt.mirrorX)
        assertEquals(right.tapSwitchFrame, withDifferentArt.tapSwitchFrame)
    }

    @Test fun mirroringExistingMirroredSideRestoresOriginalOrientation() {
        val right = TapNestLayer("ear_right", "", mirrorX = true)
        assertFalse(TapNestSymmetry.mirroredGroup(right, emptyList(), "ear_left", "左耳朵")[0].mirrorX)
    }
}
