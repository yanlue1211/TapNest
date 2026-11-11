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
    @Test fun userNamedBowAppearsOnlyAfterArtworkAndCanBeRemovedOrReattached() {
        val rig = TapNestTemplates.manual().copy(layers = listOf(
            TapNestLayer("body", "body.png"),
            TapNestLayer("part_1", "bow.png", displayName = "蝴蝶结",
                wardrobeCategory = "custom_part_1"),
            TapNestLayer("part_1_f1", "bow_tap.png", frameOwner = "part_1",
                parentId = "part_1", frameIndex = 1)
        ))
        val f = TapNestForms.starting(rig)
        assertEquals(listOf("蝴蝶结"), TapNestForms.availableParts(f).map { it.label })
        val hidden = TapNestForms.choosePart(f, "custom_part_1", "none")
        assertEquals(listOf("body"), TapNestForms.resolve(hidden).layers.map { it.id })
        assertEquals(3, TapNestForms.resolve(
            TapNestForms.choosePart(hidden, "custom_part_1", "default")
        ).layers.size)
        // New fields must roundtrip without changing any old rig.
        val file = java.io.File.createTempFile("wardrobe-", ".json")
        try {
            file.writeText(TapNestForms.encode(hidden))
            assertEquals(hidden, TapNestForms.parse(file))
        } finally { file.delete() }
    }

    @Test fun noDefaultAccessoriesExistUntilUserActuallyUploadsPNG() {
        val blank = TapNestTemplates.manual().copy(layers = listOf(
            TapNestLayer("body", "body.png"),
            TapNestLayer("ear_left", "", displayName = "左耳朵"),
            TapNestLayer("wing_right", "", displayName = "右翅膀"),
            TapNestLayer("part_1", "", displayName = "蝴蝶结")
        ))
        assertTrue(TapNestForms.availableParts(TapNestForms.starting(blank)).isEmpty())
        val withBow = blank.copy(layers = blank.layers.map {
            if (it.id == "part_1") it.copy(image = "bow.png") else it
        })
        assertEquals(listOf("蝴蝶结"),
            TapNestForms.availableParts(TapNestForms.starting(withBow)).map { it.label })
    }

    @Test fun mixingCustomPartAcrossFormsPreservesActionFrames() {
        val hat = TapNestTemplates.manual().copy(layers = listOf(
            TapNestLayer("body", "body.png"),
            TapNestLayer("part_1", "hat.png", wardrobeCategory = "custom_part_1",
                displayName = "帽子"),
            TapNestLayer("part_1_f1", "hat_wave.png", frameOwner = "part_1",
                frameIndex = 1)
        ))
        val plain = TapNestTemplates.manual().copy(layers = listOf(TapNestLayer("body", "body.png")))
        val forms = TapNestFormCollection(forms = listOf(
            TapNestForm("plain", "普通", plain), TapNestForm("hat", "戴帽子", hat)
        ), activeFormId = "plain")
        assertEquals(listOf("帽子"), TapNestForms.availableParts(forms).map { it.label })
        val mixed = TapNestForms.choosePart(forms, "custom_part_1", "hat")
        assertEquals(setOf("body", "part_1", "part_1_f1"),
            TapNestForms.resolve(mixed).layers.map { it.id }.toSet())
    }

    @Test fun legacyBowFramesCanBeHiddenTogether() {
        val b = TapNestTemplates.manual().copy(layers = listOf(
            TapNestLayer("body", "body.png"),
            TapNestLayer("bow_0", "bow_idle.png"),
            TapNestLayer("bow_1", "bow_tap.png")
        ))
        val forms = TapNestForms.starting(b)
        assertEquals(listOf("蝴蝶结"), TapNestForms.availableParts(forms).map { it.label })
        assertEquals(listOf("body"), TapNestForms.resolve(
            TapNestForms.choosePart(forms, "bow", "none")
        ).layers.map { it.id })
    }

}
