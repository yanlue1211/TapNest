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

    @Test fun mirroringExistingMirroredSideRestoresOriginalOrientation() {
        val right = TapNestLayer("ear_right", "", mirrorX = true)
        assertFalse(TapNestSymmetry.mirroredGroup(right, emptyList(), "ear_left", "左耳朵")[0].mirrorX)
    }
}
