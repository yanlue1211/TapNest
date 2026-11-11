package com.deskpet.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.deskpet.data.model.ImportException
import com.deskpet.data.model.PetType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Live2DImportBackupTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun pack(name: String, bad: Boolean = false): File {
        val archive = tmp.newFile(name)
        val manifest = """{
          "FileReferences":{
            "Moc":"girl.moc3",
            "Textures":["textures/t.png"],
            "Motions":{"Idle":[{"File":"idle.motion3.json"}]}
          }
        }""".trimIndent().toByteArray()
        ZipOutputStream(archive.outputStream()).use { zip ->
            val files = mapOf(
                "pet/my.model3.json" to manifest,
                "pet/girl.moc3" to byteArrayOf(77,79,67,51),
                "pet/textures/t.png" to byteArrayOf(137.toByte(),80,78,71),
                "pet/idle.motion3.json" to "{}".toByteArray()
            )
            files.filterKeys { !bad || !it.endsWith("idle.motion3.json") }.forEach { (key, data) ->
                zip.putNextEntry(ZipEntry(key))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return archive
    }

    @Test fun importedModelFilesSurviveWholeBackupRestoreAndRevalidation() = runBlocking {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val fm = FileManager(ctx)
        val imported = fm.importLive2D(Uri.fromFile(pack("live2d.zip")))
        assertEquals(PetType.LIVE2D, imported.type)
        assertTrue(imported.petJsonPath!!.endsWith("my.model3.json"))
        val first = Live2DModelFiles.validate(File(imported.petJsonPath!!))
        assertTrue(File(first.root, "textures/t.png").exists())

        val backup = fm.exportAllToZip(listOf(imported))
        assertNotNull(backup)
        val safeCopy = tmp.newFile("safe_backup.zip")
        backup!!.copyTo(safeCopy, overwrite = true)
        fm.deletePet(imported)
        assertFalse(File(imported.petJsonPath!!).exists())

        val repository = PetRepository(PetStore(ctx))
        fm.restoreFromBackup(Uri.fromFile(safeCopy), repository)
        val restored = repository.getPet(imported.id)
        assertNotNull("完整备份必须恢复 Live2D", restored)
        assertEquals(PetType.LIVE2D, restored!!.type)
        val second = Live2DModelFiles.validate(File(restored.petJsonPath!!))
        assertTrue(File(second.root, "textures/t.png").isFile)
        fm.deletePet(restored)
    }

    @Test fun incompleteModelRejectsImportWithoutOrphanDirectory() = runBlocking {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val fm = FileManager(ctx)
        val path = File(ctx.filesDir, "pets")
        val before = path.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val err = runCatching { fm.importLive2D(Uri.fromFile(pack("bad.zip", bad=true))) }.exceptionOrNull()
        assertTrue(err is ImportException.InvalidFormat)
        val after = path.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        assertEquals(before, after)
    }
}
