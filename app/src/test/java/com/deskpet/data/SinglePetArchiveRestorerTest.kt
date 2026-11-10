package com.deskpet.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.deskpet.data.model.ImportException
import com.deskpet.data.model.PetInfo
import com.deskpet.data.model.PetType
import com.deskpet.engine.TapNestForm
import com.deskpet.engine.TapNestFormCollection
import com.deskpet.engine.TapNestForms
import com.deskpet.engine.TapNestLayer
import com.deskpet.engine.TapNestTemplates
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SinglePetArchiveRestorerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = FileManager(context)

    private fun png(file: File, color: Int = android.graphics.Color.GREEN) {
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun original(): PetInfo {
        val id = "old_pet_" + UUID.randomUUID().toString().take(8)
        val dir = File(context.filesDir, "pets/$id").apply { mkdirs() }
        png(File(dir, "body.png"))
        png(File(dir, "ear_left.png"), android.graphics.Color.YELLOW)
        val rig = TapNestTemplates.manual().copy(layers = listOf(
            TapNestLayer("body", "body.png"),
            TapNestLayer("ear_left", "ear_left.png")
        ), canvasWidth = 48, canvasHeight = 48)
        File(dir, "rig.json").writeText(Json.encodeToString(rig))
        val form = TapNestFormCollection(forms = listOf(
            TapNestForm("default", "猫猫", rig),
            TapNestForm("dog", "狗狗", rig)
        ), activeFormId = "dog")
        File(dir, "forms.json").writeText(TapNestForms.encode(form))
        return PetInfo(id, PetType.LAYERED, "之前的小猫",
            petJsonPath = File(dir, "rig.json").absolutePath,
            spritesheetPath = File(dir, "body.png").absolutePath,
            tapDialogues = listOf("你好，我回来啦"))
    }

    private fun newRepo() = PetRepository(PetStore(context))

    private fun legacyZip(vararg parts: Pair<String, ByteArray>): File {
        val result = File(context.cacheDir, "legacy_" + UUID.randomUUID() + ".zip")
        ZipOutputStream(result.outputStream()).use { zip ->
            for ((name, bytes) in parts) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return result
    }

    @Test fun oldSingleLayeredZipWithoutPetsJsonRestoresAndKeepsOriginal() = runBlocking {
        val old = original()
        val home = File(old.petJsonPath!!).parentFile!!
        val zip = legacyZip(
            "rig.json" to File(home, "rig.json").readBytes(),
            "body.png" to File(home, "body.png").readBytes(),
            "ear_left.png" to File(home, "ear_left.png").readBytes(),
            "forms.json" to File(home, "forms.json").readBytes()
        )
        val repo = newRepo()
        try {
            manager.restoreFromBackup(Uri.fromFile(zip), repo)
            val all = repo.getAllPets().first()
            assertEquals(1, all.size)
            val restored = all.single()
            assertEquals(PetType.LAYERED, restored.type)
            assertEquals("legacy", restored.displayName.substringBefore('_'))
            assertTrue(File(restored.petJsonPath!!).isFile)
            assertNotEquals(old.id, restored.id)
            assertEquals("dog", TapNestForms.parse(File(restored.petJsonPath!!).resolveSibling("forms.json"))!!.activeFormId)
            assertTrue(File(home, "ear_left.png").isFile)
            assertTrue(File(restored.petJsonPath!!).parentFile.resolve("ear_left.png").isFile)
        } finally {
            zip.delete()
            home.deleteRecursively()
            repo.getAllPets().first().forEach { File(it.petJsonPath!!).parentFile?.deleteRecursively() }
        }
    }

    @Test fun newSinglePetZipPreservesNameAndDialogueAndAllForms() = runBlocking {
        val old = original()
        val home = File(old.petJsonPath!!).parentFile!!
        val repo = newRepo()
        val zip = manager.exportPetToZip(old)!!
        try {
            ZipFile(zip).use {
                assertNotNull(it.getEntry("tapnest-pet.json"))
                assertNotNull(it.getEntry("forms.json"))
                assertNotNull(it.getEntry("body.png"))
            }
            manager.restoreFromBackup(Uri.fromFile(zip), repo)
            val restored = repo.getAllPets().first().single()
            assertNotEquals(old.id, restored.id)
            assertEquals("之前的小猫", restored.displayName)
            assertEquals(old.tapDialogues, restored.tapDialogues)
            assertEquals("dog",
                TapNestForms.parse(File(restored.petJsonPath!!).parentFile.resolve("forms.json"))!!.activeFormId)
            assertTrue(File(home, "rig.json").exists())
        } finally {
            zip.delete()
            home.deleteRecursively()
            repo.getAllPets().first().forEach { File(it.petJsonPath!!).parentFile?.deleteRecursively() }
        }
    }

    @Test fun unknownZipDoesNotDeleteOrOverwriteExistingPet() = runBlocking {
        val original = original()
        val home = File(original.petJsonPath!!).parentFile!!
        val before = File(home, "rig.json").readBytes()
        val repo = newRepo()
        val zip = legacyZip("some.txt" to "not a backup".toByteArray())
        try {
            val error = runCatching { manager.restoreFromBackup(Uri.fromFile(zip), repo) }.exceptionOrNull()
            assertTrue(error is ImportException.InvalidFormat)
            assertTrue(error!!.message!!.contains("pets.json"))
            assertArrayEquals(before, File(home, "rig.json").readBytes())
            assertTrue(repo.getAllPets().first().isEmpty())
        } finally {
            zip.delete()
            home.deleteRecursively()
        }
    }
}
