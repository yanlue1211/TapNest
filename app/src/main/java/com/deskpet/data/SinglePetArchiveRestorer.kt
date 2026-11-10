package com.deskpet.data

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Movie
import android.util.Log
import com.deskpet.data.model.CodexPetConfig
import com.deskpet.data.model.ImportException
import com.deskpet.data.model.PetInfo
import com.deskpet.data.model.PetType
import com.deskpet.engine.LayeredPetPlayer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * A single pet exported with the old "分享角色" button did NOT contain pets.json.
 * Those archives must not be mistaken for complete backups and rejected.
 *
 * The newly added tapnest-pet.json restores the character name, dialogue settings
 * and type for future exports. A pre-manifest archive is inferred only when a
 * recognizable pet.json, rig.json or one loose PNG/GIF is present.
 */
internal class SinglePetArchiveRestorer(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val pets = File(context.filesDir, "pets").also { it.mkdirs() }

    private fun findFile(dir: File, name: String, depth: Int = 0): File? {
        if (depth > 4) return null
        File(dir, name).takeIf { it.isFile }?.let { return it }
        return dir.listFiles()?.filter { it.isDirectory && it.name != "companion" }
            ?.firstNotNullOfOrNull { findFile(it, name, depth + 1) }
    }

    private fun singleLooseFile(dir: File, suffix: String): File? {
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(suffix, true) }
        return files?.singleOrNull()
    }

    private fun validImage(file: File): Boolean {
        if (!file.isFile || file.length() > 30L * 1024 * 1024) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096 &&
            bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000L
    }

    private fun nameFromArchive(raw: String?, type: PetType): String {
        val friendly = raw?.substringAfterLast('/')?.substringBeforeLast('.')
            ?.removeSuffix("_export")?.replace(Regex("_[a-f0-9]{8}$"), "")
            ?.trim()?.take(60)
        return friendly?.takeIf { it.isNotBlank() && it != "TapNest_backup" }
            ?: when (type) {
                PetType.LAYERED -> "恢复的绑骨桌宠"
                PetType.CODEX_PET -> "恢复的素材包桌宠"
                PetType.GIF -> "恢复的 GIF 桌宠"
                PetType.PNG -> "恢复的 PNG 桌宠"
            }
    }

    suspend fun restore(extracted: File, repo: PetRepository, archiveName: String?) {
        val manifest = findFile(extracted, "tapnest-pet.json")
        val saved = manifest?.let {
            if (it.length() > ZipExtractor.MAX_PET_JSON_BYTES)
                throw ImportException.InvalidFormat("单角色清单过大")
            try { json.decodeFromString<PetInfo>(it.readText()) }
            catch (e: Exception) { throw ImportException.InvalidFormat("单角色清单损坏：${e.message}") }
        }

        val rig = findFile(extracted, "rig.json")
        val codex = findFile(extracted, "pet.json")
        val loneGif = singleLooseFile(extracted, ".gif")
        val lonePng = singleLooseFile(extracted, ".png")
        val type = saved?.type ?: when {
            rig != null -> PetType.LAYERED
            codex != null -> PetType.CODEX_PET
            loneGif != null -> PetType.GIF
            lonePng != null -> PetType.PNG
            else -> throw ImportException.InvalidFormat(
                "此压缩包不包含可恢复的桌宠。完整备份需要 pets.json；旧版单角色包需要 rig.json 或 pet.json。")
        }
        val sourceDir: File
        val art: File
        when (type) {
            PetType.LAYERED -> {
                val config = rig ?: throw ImportException.InvalidFormat("旧版分层桌宠缺少 rig.json")
                sourceDir = config.parentFile!!
                art = File(sourceDir, "body.png")
            }
            PetType.CODEX_PET -> {
                val config = codex ?: throw ImportException.InvalidFormat("素材包缺少 pet.json")
                sourceDir = config.parentFile!!
                art = listOf("spritesheet.webp", "spritesheet.png")
                    .map { File(sourceDir, it) }.firstOrNull { it.isFile }
                    ?: throw ImportException.InvalidFormat("素材包缺少 spritesheet")
            }
            PetType.PNG, PetType.GIF -> {
                val extension = if (type == PetType.PNG) ".png" else ".gif"
                val expectedName = saved?.spritesheetPath?.takeIf {
                    it.isNotBlank() && !it.contains('/') && !it.contains('\\') && !it.contains("..")
                }
                val fromManifest = expectedName?.let { findFile(extracted, it) }
                art = fromManifest ?: (if (type == PetType.PNG) lonePng else loneGif)
                    ?: throw ImportException.InvalidFormat("压缩包内找不到桌宠图片")
                if (!art.name.endsWith(extension, true))
                    throw ImportException.InvalidFormat("桌宠图片扩展名与存档类型不一致")
                sourceDir = art.parentFile!!
            }
        }

        val id = UUID.randomUUID().toString()
        val petFolder = File(pets, id)
        val loneTarget = File(pets, id + if (type == PetType.GIF) ".gif" else ".png")
        val profileFolder = File(context.filesDir, "companion_profiles/$id")
        var registered = false
        try {
            val targetArt: File
            val targetRig: File?
            val targetCodex: File?
            if (type == PetType.LAYERED || type == PetType.CODEX_PET) {
                if (petFolder.exists() || !sourceDir.copyRecursively(petFolder, false)) {
                    throw ImportException.InvalidFormat("复制角色素材失败，请检查存储空间")
                }
                targetRig = File(petFolder, "rig.json")
                targetCodex = File(petFolder, "pet.json")
                targetArt = File(petFolder, art.name)
                if (type == PetType.LAYERED) {
                    if (!targetRig.isFile || targetRig.length() > ZipExtractor.MAX_PET_JSON_BYTES ||
                        !targetArt.isFile || !validImage(targetArt) ||
                        LayeredPetPlayer.load(targetRig) == null
                    ) throw ImportException.InvalidFormat("旧版绑骨数据或图片不完整，已保留原压缩包")
                } else {
                    if (!targetCodex.isFile ||
                        !ZipExtractor.isPetJsonSizeOk(targetCodex) ||
                        !validImage(targetArt)
                    ) throw ImportException.InvalidFormat("旧版素材包内容损坏")
                }
            } else {
                targetRig = null
                targetCodex = null
                if (loneTarget.exists()) throw ImportException.InvalidFormat("目标素材已存在")
                art.copyTo(loneTarget, overwrite = false)
                targetArt = loneTarget
                val valid = if (type == PetType.PNG) {
                    val magic = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
                    val head = ByteArray(8)
                    targetArt.inputStream().use { it.read(head) == 8 } &&
                        head.contentEquals(magic) && validImage(targetArt)
                } else {
                    @Suppress("DEPRECATION")
                    val movie = targetArt.inputStream().use { Movie.decodeStream(it) }
                    movie != null && movie.width() > 0 && movie.height() > 0
                }
                if (!valid) throw ImportException.InvalidFormat("旧版桌宠图片无效")
            }

            // Staging profile before registering prevents a half-restored record in DataStore.
            val profileSrc = File(sourceDir, "companion")
            if (profileSrc.isDirectory) {
                if (profileFolder.exists() || !profileSrc.copyRecursively(profileFolder, false))
                    throw ImportException.InvalidFormat("互动档案复制失败")
                if (type == PetType.LAYERED || type == PetType.CODEX_PET)
                    File(petFolder, "companion").deleteRecursively()
            }
            val name = saved?.displayName?.take(60)?.ifBlank { null }
                ?: if (type == PetType.CODEX_PET && targetCodex != null) {
                    runCatching { json.decodeFromString<CodexPetConfig>(targetCodex.readText()).displayName }
                        .getOrNull()?.take(60)?.ifBlank { null }
                } else null ?: nameFromArchive(archiveName, type)
            val restored = saved?.copy(
                id = id,
                petJsonPath = targetRig?.absolutePath ?: targetCodex?.absolutePath,
                spritesheetPath = targetArt.absolutePath,
                sourceId = when (type) {
                    PetType.LAYERED -> "layered:$id"
                    else -> saved.sourceId.takeIf { it.isNotBlank() } ?: "restored:$id"
                }
            ) ?: PetInfo(
                id = id, type = type, displayName = name,
                petJsonPath = targetRig?.absolutePath ?: targetCodex?.absolutePath,
                spritesheetPath = targetArt.absolutePath,
                sourceId = when (type) {
                    PetType.LAYERED -> "layered:$id"
                    else -> "restored:$id"
                }
            )
            repo.addPetDirect(restored)
            registered = true
        } catch (e: Exception) {
            // Only clean up newly generated paths. Never modify the source archive
            // or any other pet. If DataStore registration succeeded, retain the files.
            if (!registered) {
                petFolder.deleteRecursively()
                if (type == PetType.GIF || type == PetType.PNG) loneTarget.delete()
                profileFolder.deleteRecursively()
            }
            Log.w("SinglePetRestore", "single-pet restore failed", e)
            throw e
        }
    }
}
