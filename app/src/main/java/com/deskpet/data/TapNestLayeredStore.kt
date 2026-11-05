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
            !inputs.containsKey(if (rig.headMode == TapNestHeadMode.FULL_HEAD) "head_0" else "head_blank")) {
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
                sourceId = "layered:$"+"id"
            )
        } catch (e: Exception) {
            dest.deleteRecursively()
            throw e
        }
    }

    companion object {
        /** Only file names inside the character directory are accepted, never absolute paths. */
        fun safeAsset(fileName: String): Boolean =
            fileName.isNotBlank() && fileName.endsWith(".png", true) &&
            !fileName.contains('/') && !fileName.contains('\\') &&
            !fileName.contains("..")
    }
}
