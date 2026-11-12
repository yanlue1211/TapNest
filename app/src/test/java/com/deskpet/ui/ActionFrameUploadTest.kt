package com.deskpet.ui

import com.deskpet.engine.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ActionFrameUploadTest {
    @Test fun localUploadsBindTheirOwnActionWithoutChangingOtherActionsOrCombinedMotion() {
        val owner = TapNestLayer("head_0", "head.png", interactionConfigured = true,
            tapShake = true, tapSwitchFrame = true, periodicEnabled = true,
            periodicSwitchFrame = true, idleBlinkEnabled = true)
        var rig = TapNestRig(canvasWidth = 64, canvasHeight = 64, layers = listOf(owner))
        val tap = ActionFrameUpload(owner.id, PartTrigger.TAP).apply(rig)
        rig = tap.rig
        val tapIndex = rig.layers.first { it.id == owner.id }.tapFrame
        val blink = ActionFrameUpload(owner.id, PartTrigger.BLINK).apply(rig)
        rig = blink.rig
        val idle = ActionFrameUpload(owner.id, PartTrigger.IDLE).apply(rig)
        rig = idle.rig
        val updated = rig.layers.first { it.id == owner.id }
        assertEquals(tapIndex, updated.tapFrame)
        assertNotEquals(updated.tapFrame, updated.blinkFrame)
        assertNotEquals(updated.blinkFrame, updated.periodicFrame)
        assertTrue(updated.tapShake && updated.tapSwitchFrame)
        assertTrue(updated.periodicSwitchFrame)
        assertTrue(rig.layers.contains(tap.rig.layers.last()))
        assertEquals(listOf(idle.rig.layers.last().id), idle.imageIds)
        val angry = ActionFrameUpload(owner.id, angry = true).apply(rig)
        assertEquals(angry.imageIds.first(), angry.rig.rapidTapAngryFrameId)
        assertEquals(updated, angry.rig.layers.first { it.id == owner.id })
    }

    @Test fun twentyFramesKeepStableIdentifiersAndRoundTripBeyondTheOldThreeFrameLimit() {
        val owner = TapNestLayer("abcdefghijklmnopqrstuvwxabcdefgh", "head.png")
        var rig = TapNestRig(canvasWidth = 64, canvasHeight = 64, layers = listOf(owner))
        repeat(20) { rig = ActionFrameUpload(owner.id, PartTrigger.BLINK).apply(rig).rig }
        val frames = rig.layers.filter { it.frameOwner == owner.id }
        assertEquals(20, frames.size)
        assertEquals((1..20).toList(), frames.map { it.frameIndex })
        assertEquals(rig.layers.size, rig.layers.map { it.id }.distinct().size)
        assertTrue(frames.all { it.id.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,34}")) })
        assertEquals(20, rig.layers.first().blinkFrame)
        assertEquals(rig, Json.decodeFromString<TapNestRig>(Json.encodeToString(rig)))
        assertThrows(IllegalArgumentException::class.java) { ActionFrameUpload(owner.id, PartTrigger.TAP).apply(rig) }
    }

    @Test fun directUploadOnSynchronizedMirrorCreatesTheMissingPartnerFrameWithoutOverwritingArt() {
        val left = TapNestLayer("ear_left", "left.png", syncPairedAll = true, tapShake = true)
        val right = TapNestLayer("ear_right", "right.png", mirrorX = true, mirroredFromId = left.id, syncPairedAll = true)
        val rig = TapNestRig(canvasWidth = 64, canvasHeight = 64, layers = listOf(left, right))
        val plan = ActionFrameUpload(left.id, PartTrigger.TAP).apply(rig)
        assertEquals(2, plan.imageIds.size)
        val other = plan.rig.layers.first { it.frameOwner == right.id }
        assertEquals(right.id, other.parentId)
        assertEquals(left.image, plan.rig.layers.first { it.id == left.id }.image)
        assertEquals(right.image, plan.rig.layers.first { it.id == right.id }.image)
        assertEquals(plan.rig.layers.first { it.id == left.id }.tapFrame,
            plan.rig.layers.first { it.id == right.id }.tapFrame)
    }
}
