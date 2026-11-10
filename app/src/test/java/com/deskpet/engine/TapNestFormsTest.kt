package com.deskpet.engine

import org.junit.Assert.*
import org.junit.Test

class TapNestFormsTest {
    private val base = TapNestTemplates.manual().copy(layers = listOf(
        TapNestLayer("body", "body.png"),
        TapNestLayer("ear_left", "cat-ear.png", bones = listOf(TapNestBonePoint(.1f, .3f))),
        TapNestLayer("ear_left_frame_1", "blink-ear.png", frameOwner = "ear_left"),
        TapNestLayer("tail", "cat-tail.png"),
        TapNestLayer("wing_left", "demon.png")
    ))
    private val dog = base.copy(layers = base.layers.map {
        if (it.id == "ear_left") it.copy(image = "dog-ear.png",
            bones = listOf(TapNestBonePoint(.2f, .7f)))
        else if (it.id == "tail") it.copy(image = "dog-tail.png")
        else it
    })

    @Test fun legacyRigLoadsAsOneForm() {
        val f = TapNestForms.starting(base)
        assertEquals(1, f.forms.size)
        assertEquals("default", f.activeFormId)
        assertEquals(base, TapNestForms.resolve(f))
    }

    @Test fun fullSwitchResetsMix() {
        val forms = TapNestFormCollection(forms = listOf(
            TapNestForm("cat", "猫", base), TapNestForm("dog", "狗", dog)),
            activeFormId = "cat", overrides = mapOf("tail" to "dog"))
        val updated = TapNestForms.choose(forms, "dog")
        assertTrue(updated.overrides.isEmpty())
        assertEquals("dog-tail.png",
            TapNestForms.resolve(updated).layers.first { it.id == "tail" }.image)
    }

    @Test fun partMixCopiesBoneAndFrameTogether() {
        val forms = TapNestFormCollection(forms = listOf(
            TapNestForm("cat", "猫", base), TapNestForm("dog", "狗", dog)),
            activeFormId = "cat", overrides = mapOf("ear" to "dog"))
        val rig = TapNestForms.resolve(forms)
        assertEquals("dog-ear.png", rig.layers.first { it.id == "ear_left" }.image)
        assertEquals(.7f, rig.layers.first { it.id == "ear_left" }.bones.first().y)
        assertEquals("blink-ear.png",
            rig.layers.first { it.frameOwner == "ear_left" }.image)
        assertEquals("cat-tail.png", rig.layers.first { it.id == "tail" }.image)
    }

    @Test fun hidePartKeepsOtherParts() {
        val f = TapNestForms.starting(base).copy(overrides = mapOf("wing" to "none"))
        assertFalse(TapNestForms.resolve(f).layers.any { it.id == "wing_left" })
        assertTrue(TapNestForms.resolve(f).layers.any { it.id == "body" })
    }

    @Test fun serializationPreservesMultipleForms() {
        val forms = TapNestFormCollection(forms = listOf(
            TapNestForm("cat", "猫", base), TapNestForm("dog", "狗", dog)),
            activeFormId = "cat", overrides = mapOf("tail" to "dog"))
        val file = java.io.File.createTempFile("tapnest-forms-", ".json")
        try {
            file.writeText(TapNestForms.encode(forms))
            assertEquals(forms, TapNestForms.parse(file))
        } finally { file.delete() }
    }
}
