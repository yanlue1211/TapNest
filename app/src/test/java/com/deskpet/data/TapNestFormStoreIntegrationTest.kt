package com.deskpet.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.deskpet.data.model.PetInfo
import com.deskpet.data.model.PetType
import com.deskpet.engine.*
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
import java.util.zip.ZipFile

/** Ensures copying and editing a form never destroys a different form's artwork. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TapNestFormStoreIntegrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun png(file: File, color: Int) {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun fixture(): PetInfo {
        val id = "form_test_" + UUID.randomUUID().toString().take(8)
        val home = File(context.filesDir, "pets/$id").also { it.mkdirs() }
        png(File(home, "body.png"), android.graphics.Color.GREEN)
        png(File(home, "cat_ear.png"), android.graphics.Color.YELLOW)
        val rig = TapNestTemplates.manual().copy(layers = listOf(
            TapNestLayer("body", "body.png"),
            TapNestLayer("ear_left", "cat_ear.png", bones = listOf(TapNestBonePoint(.2f, .4f)))
        ))
        File(home, "rig.json").writeText(Json.encodeToString(rig))
        return PetInfo(id = id, type = PetType.LAYERED, displayName = "形态测试",
            petJsonPath = File(home, "rig.json").absolutePath,
            spritesheetPath = File(home, "body.png").absolutePath)
    }

    @Test fun cloneCanChangeEarsWithoutOverwritingOriginalAndBackupIncludesForms() = runBlocking {
        val pet = fixture()
        val dir = File(pet.petJsonPath!!).parentFile!!
        try {
            val store = TapNestFormStore(context)
            val original = store.load(pet)
            assertEquals(1, original.forms.size)
            assertFalse(File(dir, "forms.json").exists()) // no migration until first edit

            val clone = store.add(pet, "default", "狗狗")
            assertEquals(2, store.load(pet).forms.size)
            val newEar = File(dir, "incoming_ear.png")
            png(newEar, android.graphics.Color.BLUE)
            val changedRig = clone.rig.copy(layers = clone.rig.layers.map {
                if (it.id == "ear_left") it.copy(
                    bones = listOf(TapNestBonePoint(.6f, .7f))) else it
            })
            val edited = store.saveForm(pet, clone.id, "狗狗", changedRig,
                mapOf("body" to Uri.fromFile(File(dir, "body.png")),
                    "ear_left" to Uri.fromFile(newEar)))
            val loaded = store.load(edited)
            assertEquals("cat_ear.png", loaded.forms.first().rig.layers.first { it.id == "ear_left" }.image)
            val ear = loaded.forms.last().rig.layers.first { it.id == "ear_left" }
            assertTrue(ear.image.startsWith(clone.id))
            assertTrue(File(dir, ear.image).exists())
            assertEquals(.7f, ear.bones.first().y)
            store.setActive(pet, clone.id)
            assertEquals(clone.id, store.load(pet).activeFormId)
            assertNotNull(LayeredPetPlayer.load(File(dir, "rig.json")))

            val backup = FileManager(context).exportAllToZip(listOf(pet))
            assertNotNull(backup)
            ZipFile(backup!!).use { zip ->
                assertNotNull(zip.getEntry("pets/" + pet.id + "/forms.json"))
                assertNotNull(zip.getEntry("pets/" + pet.id + "/" + ear.image))
            }

            store.remove(pet, clone.id)
            assertEquals("default", store.load(pet).activeFormId)
            assertTrue(File(dir, "cat_ear.png").exists())
            assertTrue(File(dir, ear.image).exists()) // retained; no unsafe asset cleanup
        } finally { dir.deleteRecursively() }
    }
}
