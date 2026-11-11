package com.deskpet.data

import com.deskpet.data.model.ImportException
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class Live2DModelFilesTest {
    @Test fun acceptsNestedModelAndValidatesEveryReference() {
        val temp = Files.createTempDirectory("l2d-valid").toFile()
        try {
            val model = File(temp, "小伙伴/l2d").apply { mkdirs() }
            File(model, "sample.moc3").writeBytes(byteArrayOf(0, 1, 2, 3))
            File(model, "textures").mkdir()
            File(model, "textures/texture.png").writeBytes(byteArrayOf(1))
            File(model, "idle.motion3.json").writeText("{}")
            File(model, "my.model3.json").writeText("""
                {"FileReferences":{"Moc":"sample.moc3",
                 "Textures":["textures/texture.png"],
                 "Motions":{"Idle":[{"File":"idle.motion3.json"}]}}}
            """.trimIndent())
            val result = Live2DModelFiles.findAndValidate(temp)
            assertEquals("my", result.title)
            assertEquals(model.canonicalFile, result.root.canonicalFile)
        } finally { temp.deleteRecursively() }
    }

    @Test fun blocksTraversalAndMissingResources() {
        val temp = Files.createTempDirectory("l2d-bad").toFile()
        try {
            val m = File(temp, "a.model3.json")
            m.writeText("""{"FileReferences":{"Moc":"../outside.moc3","Textures":["t.png"]}}""")
            assertThrows(ImportException.InvalidFormat::class.java) { Live2DModelFiles.findAndValidate(temp) }
            assertNull(Live2DModelFiles.resolveAsset(temp, "../outside.png"))
            assertNull(Live2DModelFiles.resolveAsset(temp, "https://evil.test/a.png"))
            assertNull(Live2DModelFiles.resolveAsset(temp, "C:/root/a.png"))
            assertNull(Live2DModelFiles.resolveAsset(temp, "%2e%2e/inside.png"))
        } finally { temp.deleteRecursively() }
    }

    @Test fun multipleModelsAreRejectedInsteadOfPickingRandomPet() {
        val temp = Files.createTempDirectory("l2d-multi").toFile()
        try {
            File(temp, "a.model3.json").writeText("{}")
            File(temp, "b.model3.json").writeText("{}")
            assertThrows(ImportException.InvalidFormat::class.java) { Live2DModelFiles.findAndValidate(temp) }
        } finally { temp.deleteRecursively() }
    }
}
