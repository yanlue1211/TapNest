package com.deskpet.ui

import com.deskpet.engine.*
import org.junit.Assert.*
import org.junit.Test

class PartEditorFlowTest {
    @Test fun frameBranchReturnsThroughTheStepsActuallyVisited() {
        val route = PartEditorFlow().next(PartEditorStep.ENABLE).next(PartEditorStep.TYPE)
            .next(PartEditorStep.FRAME).next(PartEditorStep.SUMMARY)
        assertEquals(PartEditorStep.FRAME, route.back().step)
        assertEquals(PartEditorStep.TYPE, route.back().back().step)
        assertEquals(PartEditorStep.ENABLE, route.back().back().back().step)
        assertEquals(PartEditorStep.SELECT, route.back().back().back().back().step)
        assertFalse(route.history.contains(PartEditorStep.BONES))
    }

    @Test fun flexibleMotionBranchHasItsOwnBackPath() {
        val route = PartEditorFlow(trigger = PartTrigger.TAP).next(PartEditorStep.ENABLE)
            .next(PartEditorStep.TYPE).next(PartEditorStep.ANCHOR).next(PartEditorStep.MOTION).next(PartEditorStep.FLEX)
            .next(PartEditorStep.BONES).next(PartEditorStep.SUMMARY)
        assertEquals(PartEditorStep.BONES, route.back().step)
        assertEquals(PartEditorStep.FLEX, route.back().back().step)
        assertEquals(PartEditorStep.MOTION, route.back().back().back().step)
        assertEquals(PartTrigger.TAP, route.back().trigger)
    }

    @Test fun configuringIdleFramesDoesNotChangeTouchOrSharedBones() {
        val original = TapNestLayer("ear_left", "ear.png", tapShake = true,
            tapFlexible = TapNestImpulse(enabled = true, amplitude = 27f),
            bones = listOf(TapNestBonePoint(.5f, .8f), TapNestBonePoint(.5f, .2f)))
        val changed = PartTrigger.IDLE.chooseFrame(PartTrigger.IDLE.setEnabled(original, true))
        assertEquals(TapNestPeriodicAction.SWITCH_FRAME, changed.periodicAction)
        assertTrue(changed.periodicEnabled)
        assertTrue(changed.tapShake)
        assertEquals(original.tapFlexible, changed.tapFlexible)
        assertEquals(original.bones, changed.bones)
        val rigid = PartTrigger.CONTINUOUS.setFlexible(changed, false)
        assertEquals(original.bones, rigid.bones)
        assertFalse(rigid.idleFlexibleEnabled)
        assertTrue(rigid.tapFlexible.enabled)
    }
    @Test fun independentPointsDoNotOverwritePublicSkeleton() {
        val original = TapNestLayer("ear", "e.png", bones = listOf(TapNestBonePoint(.5f, .5f), TapNestBonePoint(.5f, 1f)))
        val independent = PartTrigger.TAP.withSeparate(original, true)
        val changed = PartTrigger.TAP.withBones(independent, listOf(TapNestBonePoint(.1f, .1f), TapNestBonePoint(.2f, .2f)))
        assertEquals(original.bones, changed.bones)
        assertEquals(original.bones, PartTrigger.IDLE.bones(changed))
        assertNotEquals(original.bones, PartTrigger.TAP.bones(changed))
        assertEquals(original.bones, PartTrigger.TAP.bones(PartTrigger.TAP.withSeparate(changed, false)))
    }

    @Test fun copyingPreviousRibbonKeepsAllEffectsAndTriggerIsolation() {
        for (trigger in listOf(PartTrigger.TAP, PartTrigger.IDLE, PartTrigger.CONTINUOUS)) {
            val source = TapNestLayer("bow", "b.png",
                bones = listOf(TapNestBonePoint(.2f, .2f), TapNestBonePoint(.3f, .8f)),
                idlePlane = TapNestMotionPlane(true, true, 25f),
                tapPlane = TapNestMotionPlane(true, false, 0f, 17f, 29f),
                springSoftness = .82f, shapePreservation = .66f, idleLagSeconds = .31f,
                swingMode = TapNestSwingMode.SPRING, springVersion = 2,
                tapFlexible = TapNestImpulse(softness = .24f, damping = .55f))
            val independent = trigger.withSeparate(source, true)
            val original = trigger.withChains(independent, listOf(TapNestBoneChain(name = "第二条"), TapNestBoneChain(name = "第三条")))
            val copied = trigger.copyPreviousChain(original, 1)
            val chain = trigger.chains(copied)[0]
            assertEquals("第二条", chain.name)
            assertEquals(trigger.bones(source), chain.bones)
            assertEquals(trigger.plane(source), chain.plane)
            assertEquals(.66f, chain.shapePreservation!!, .001f)
            assertEquals(.31f, chain.idleLagSeconds!!, .001f)
            assertEquals(source.bones, copied.bones)
            assertEquals(original.boneChains, copied.boneChains)
            val third = trigger.copyPreviousChain(copied, 2)
            assertEquals(chain.copy(name = "第三条"), trigger.chains(third)[1])
            val edited = trigger.withChains(copied, trigger.chains(copied).mapIndexed { index, c ->
                if (index == 0) c.copy(bones = listOf(TapNestBonePoint(.9f, .9f)), softness = .1f) else c
            })
            assertEquals(source.bones, edited.bones)
            assertEquals(trigger.bones(source), chain.bones)
            assertEquals(TapNestBoneChain(name = "第三条"), trigger.chains(edited)[1])
        }
    }

}
