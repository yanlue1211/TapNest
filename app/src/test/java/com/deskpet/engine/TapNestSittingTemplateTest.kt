package com.deskpet.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TapNestSittingTemplateTest {
    @Test
    fun sittingTailIsBehindBodyAndAttachedAtHip() {
        val rig = TapNestTemplates.fairy()
        val tail = requireNotNull(rig.layers.firstOrNull { it.id == "tail" })
        val body = requireNotNull(rig.layers.firstOrNull { it.id == "body" })
        assertEquals("body", tail.parentId)
        assertNull(body.parentId)
        assertTrue(tail.zIndex < body.zIndex)
        assertEquals(0.77f, tail.pivotX, 0.001f)
        assertEquals(0.73f, tail.pivotY, 0.001f)
        assertEquals(0f, tail.x, 0.001f)
        assertEquals(0f, tail.y, 0.001f)
    }

    @Test
    fun copyingTemplateKeepsRigButStripsArtwork() {
        val rig = TapNestTemplates.fairy().copy(
            layers = TapNestTemplates.fairy().layers.map {
                if (it.id == "tail") it.copy(image = "tail.png") else it
            }
        )
        val reusable = TapNestTemplates.asReusableTemplate(rig)
        val tail = requireNotNull(reusable.layers.firstOrNull { it.id == "tail" })
        assertEquals("", tail.image)
        assertEquals(TapNestTemplates.SITTING_TAIL_PIVOT_X, tail.pivotX, 0.001f)
        assertEquals(TapNestTemplates.SITTING_TAIL_PIVOT_Y, tail.pivotY, 0.001f)
    }
}
