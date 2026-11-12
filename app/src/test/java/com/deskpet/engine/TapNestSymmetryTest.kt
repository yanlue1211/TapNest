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
    @Test fun independentArtworkReflectsEveryBoneDirectionAndMotion() {
        TapNestBoneSwingDirection.entries.forEach { direction ->
            val source = TapNestLayer("ear_left", "", idleSwing = true,
                bones = listOf(TapNestBonePoint(.2f, .2f), TapNestBonePoint(.3f, .7f, direction)),
                interactionConfigured = true, tapShake = true, tapBasicConfigured = true)
            val clone = TapNestSymmetry.mirroredGroup(source, emptyList(), "ear_right", "右耳").single()
            val separate = TapNestSymmetry.independentArtwork(clone)
            assertTrue(separate.reflectedMotion)
            assertFalse(separate.mirrorX)
            val a = TapNestFlexibleMesh.vertices(source, 100, 100, 210L, 10)!!
            val b = TapNestFlexibleMesh.vertices(separate, 100, 100, 210L, 10)!!
            for (row in 0..10) for (col in 0..10) {
                val i = (row * 11 + col) * 2
                val j = (row * 11 + 10 - col) * 2
                assertEquals(direction.name, 100f - a[i], b[j], .01f)
                assertEquals(direction.name, a[i + 1], b[j + 1], .01f)
            }
            val left = TapNestLayerActions.motionPose(source, 120L)
            val right = TapNestLayerActions.motionPose(separate, 120L)
            assertEquals(-left.angle, right.angle, .001f)
            assertEquals(-left.x, right.x, .001f)
            assertEquals(left.y, right.y, .001f)
            val synced = TapNestSymmetry.synchronizedMotion(source, separate)
            assertEquals(separate.bones, synced.bones)
            assertEquals(separate.reflectedMotion, synced.reflectedMotion)
        }
    }
}
