package com.deskpet.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.deskpet.data.model.ImportException
import com.deskpet.data.model.PetInfo
import com.deskpet.data.model.PetType
import com.deskpet.engine.TapNestRig
import com.deskpet.engine.TapNestSavedTemplate
import com.deskpet.engine.TapNestTemplates
import com.deskpet.engine.TapNestHeadMode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Imported artwork and reusable binding layouts are local, with no URLs and no AI. */
class TapNestLayeredStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val templates get() = File(context.filesDir, "tapnest_templates").also { it.mkdirs() }
    private val pets get() = File(context.filesDir, "pets").also { it.mkdirs() }

    fun savedTemplates(): List<TapNestSavedTemplate> =
        templates.listFiles { f -> f.isFile && f.extension == "json" }
            ?.mapNotNull { runCatching { json.decodeFromString<TapNestSavedTemplate>(it.readText()) }.getOrNull() }
            ?.sortedBy { it.name } ?: emptyList()

    fun saveTemplate(name: String, rig: TapNestRig): TapNestSavedTemplate {
        val template = TapNestSavedTemplate(
            id = UUID.randomUUID().toString(),
            name = name.trim().take(50).ifBlank { "我的模板" },
            rig = TapNestTemplates.asReusableTemplate(rig)
        )
        File(templates, template.id + ".json").writeText(json.encodeToString(template))
        return template
    }

    suspend fun createPet(
        name: String, rig: TapNestRig, inputs: Map<String, Uri>, saveAsTemplate: Boolean
    ): PetInfo {
        if (!inputs.containsKey("body") ||
            (rig.templateId != TapNestTemplates.MANUAL &&
             !inputs.containsKey(if (rig.headMode == TapNestHeadMode.FULL_HEAD) "head_0" else "head_blank"))) {
            throw ImportException.InvalidFormat("至少要导入身体和第一张完整头部（或空白头部）")
        }
        val id = UUID.randomUUID().toString()
        val dest = File(pets, id).also { it.mkdirs() }
        try {
            val copied = rig.layers.map { layer ->
                val uri = inputs[layer.id]
                if (uri == null) layer.copy(image = "")
                else {
                    val target = File(dest, layer.id + ".png")
                    context.contentResolver.openInputStream(uri)?.use {
                        ZipExtractor.copyWithLimit(it, target, 10L * 1024 * 1024)
                    } ?: throw ImportException.FileNotFound("读取图片失败：${layer.id}")
                    val signature = byteArrayOf(137.toByte(),80,78,71,13,10,26,10)
                    val header = ByteArray(8)
                    target.inputStream().use { if (it.read(header) != 8 || !header.contentEquals(signature)) {
                        throw ImportException.InvalidFormat("非 PNG 图片：${layer.id}")
                    } }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(target.absolutePath, bounds)
                    val pixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
                    if (pixels <= 0 || pixels > 16_000_000L) {
                        throw ImportException.InvalidFormat("PNG 尺寸无效或过大：${layer.id}")
                    }
                    layer.copy(image = target.name)
                }
            }
            val body = File(dest, "body.png")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(body.absolutePath, bounds)
            val saved = rig.copy(
                formatVersion = 1,
                canvasWidth = bounds.outWidth,
                canvasHeight = bounds.outHeight,
                layers = copied
            )
            val config = File(dest, "rig.json")
            config.writeText(json.encodeToString(saved))
            if (saveAsTemplate) saveTemplate(name, saved)
            return PetInfo(
                id = id, type = PetType.LAYERED,
                displayName = name.trim().take(60).ifBlank { "我的分层宠物" },
                petJsonPath = config.absolutePath,
                spritesheetPath = body.absolutePath,
                sourceId = "layered:" + id
            )
        } catch (e: Exception) {
            dest.deleteRecursively()
            throw e
        }
    }

    /** Existing rig stays intact until a complete and verified replacement is staged. */
    suspend fun updatePet(
        old: PetInfo, name: String, rig: TapNestRig,
        inputs: Map<String, Uri>, saveAsTemplate: Boolean
    ): PetInfo {
        require(old.type == PetType.LAYERED && ZipExtractor.isSafePetId(old.id))
        val originalDir = File(pets, old.id)
        if (!originalDir.isDirectory) throw ImportException.FileNotFound("原有图层目录不存在")
        val stage = File(pets, ".editing-" + UUID.randomUUID()).also { it.mkdirs() }
        val archive = File(pets, ".previous-" + UUID.randomUUID())
        var archived = false
        try {
            if (!inputs.containsKey("body") ||
                (rig.templateId != TapNestTemplates.MANUAL &&
                    !inputs.containsKey(if (rig.headMode == TapNestHeadMode.FULL_HEAD) "head_0" else "head_blank"))) {
                throw ImportException.InvalidFormat("请至少提供身体和当前模式的第一张头部")
            }
            val copied = rig.layers.map { layer ->
                val uri = inputs[layer.id] ?: return@map layer.copy(image = "")
                if (!layer.id.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,34}"))) {
                    throw ImportException.InvalidFormat("图层名不合法")
                }
                val file = File(stage, layer.id + ".png")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    ZipExtractor.copyWithLimit(input, file, 10L * 1024 * 1024)
                } ?: throw ImportException.FileNotFound("无法读取：${layer.id}")
                val sign = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
                val header = ByteArray(8)
                file.inputStream().use {
                    if (it.read(header) != 8 || !header.contentEquals(sign))
                        throw ImportException.InvalidFormat("不是 PNG：${layer.id}")
                }
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, options)
                if (options.outWidth <= 0 || options.outHeight <= 0 ||
                    options.outWidth.toLong() * options.outHeight > 16_000_000L)
                    throw ImportException.InvalidFormat("图片尺寸无效：${layer.id}")
                layer.copy(image = file.name)
            }
            val body = File(stage, "body.png")
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(body.absolutePath, options)
            if (options.outWidth <= 0 || options.outHeight <= 0)
                throw ImportException.InvalidFormat("身体图片不可用")
            val savedRig = rig.copy(canvasWidth = options.outWidth, canvasHeight = options.outHeight, layers = copied)
            File(stage, "rig.json").writeText(json.encodeToString(savedRig))
            // Validate the actual new rig and image files before modifying the old directory.
            if (com.deskpet.engine.LayeredPetPlayer.load(File(stage, "rig.json")) == null)
                throw ImportException.InvalidFormat("图层无法合成，请检查尺寸和素材")
            if (!originalDir.renameTo(archive)) throw IllegalStateException("无法保留旧版角色")
            archived = true
            if (!stage.renameTo(originalDir)) throw IllegalStateException("新图层保存失败")
            archive.deleteRecursively()
            if (saveAsTemplate) runCatching { saveTemplate(name, savedRig) }
                .onFailure { android.util.Log.w("TapNestEditor", "Save template failed", it) }
            return old.copy(
                displayName = name.trim().take(60).ifBlank { old.displayName },
                revision = System.currentTimeMillis(),
                petJsonPath = File(originalDir, "rig.json").absolutePath,
                spritesheetPath = File(originalDir, "body.png").absolutePath
            )
        } catch (e: Exception) {
            if (archived && archive.exists()) {
                if (originalDir.exists()) originalDir.deleteRecursively()
                archive.renameTo(originalDir)
            }
            throw e
        } finally { stage.deleteRecursively() }
    }

    fun loadRig(pet: PetInfo): TapNestRig? =
        pet.petJsonPath?.let { file ->
            runCatching { json.decodeFromString<TapNestRig>(File(file).readText()) }.getOrNull()
        }

    companion object {
        /** Only file names inside the character directory are accepted, never absolute paths. */
        fun safeAsset(fileName: String): Boolean =
            fileName.isNotBlank() && fileName.endsWith(".png", true) &&
            !fileName.contains('/') && !fileName.contains('\\') &&
            !fileName.contains("..")
    }
}
