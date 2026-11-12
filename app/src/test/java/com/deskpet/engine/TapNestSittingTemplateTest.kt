package com.deskpet.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TapNestSittingTemplateTest {
    @Test
    fun sittingTailIsBehindBodyAndAttachedAtHip() {
        val rig = TapNestTemplates.manual()
        val tail = TapNestTemplates.newPart("tail", 0)
        val body = requireNotNull(rig.layers.firstOrNull { it.id == "body" })
        assertEquals("body", tail.parentId)
        assertNull(body.parentId)
        assertTrue(tail.zIndex < body.zIndex)
        assertEquals(0.75f, tail.pivotX, 0.001f)
        assertEquals(0.72f, tail.pivotY, 0.001f)
        assertEquals(0f, tail.x, 0.001f)
        assertEquals(0f, tail.y, 0.001f)
    }

    @Test
    fun publicTemplateHasNoPrivateWingsEyesOrAccessoryArt() {
        val starter = TapNestTemplates.manual()
        assertEquals(TapNestTemplates.MANUAL, starter.templateId)
        assertTrue(starter.layers.all { it.image.isBlank() })
        assertEquals(setOf("body", TapNestClothing.ID), starter.layers.map { it.id }.toSet())
        assertEquals("", starter.layers.first { it.bodyLinkedClothing }.image)
        assertTrue(starter.layers.none { it.id.contains("bow") || it.id.contains("eye") ||
            it.id.contains("hair") })
    }

    @Test
    fun copyingTemplateKeepsRigButStripsArtwork() {
        val rig = TapNestTemplates.manual().copy(
            layers = TapNestTemplates.manual().layers +
                TapNestTemplates.newPart("tail", 0).copy(image = "tail.png")
        )
        val reusable = TapNestTemplates.asReusableTemplate(rig)
        val tail = requireNotNull(reusable.layers.firstOrNull { it.id == "tail" })
        assertEquals("", tail.image)
        assertEquals(0.75f, tail.pivotX, 0.001f)
        assertEquals(0.72f, tail.pivotY, 0.001f)
    }
}
