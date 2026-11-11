package com.deskpet.data

import com.deskpet.data.model.ImportException
import kotlinx.serialization.json.*
import java.io.File

/**
 * Pure file-format checks shared by importing, restoring and starting a Live2D.
 * Only model assets inside the selected model directory can be served to WebView.
 * The proprietary Cubism Core is NEVER stored in imported model archives.
 */
object Live2DModelFiles {
    data class Model(val manifest: File, val root: File, val title: String)

    fun findAndValidate(dir: File): Model {
        val manifests = dir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".model3.json", ignoreCase = true) &&
                it.relativeTo(dir).invariantSeparatorsPath.split('/').none { p ->
                    p == "__MACOSX" || p.startsWith(".")
                } }
            .take(3).toList()
        if (manifests.size != 1) throw ImportException.InvalidFormat(
            if (manifests.isEmpty()) "Live2D ZIP 缺少 .model3.json" else "压缩包包含多个模型，请每次只导入一只"
        )
        return validate(manifests.single())
    }

    fun validate(manifest: File): Model {
        if (!manifest.isFile || manifest.length() > 1_000_000L)
            throw ImportException.InvalidFormat("Live2D 模型配置丢失或文件过大")
        val root = manifest.parentFile ?: throw ImportException.InvalidFormat("无法读取模型目录")
        val data = try { Json.parseToJsonElement(manifest.readText()).jsonObject }
            catch (_: Exception) { throw ImportException.InvalidFormat("model3.json 内容不是有效 JSON") }
        val refs = data["FileReferences"] as? JsonObject
            ?: throw ImportException.InvalidFormat("model3.json 缺少 FileReferences")
        fun str(node: JsonElement?): String? = (node as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        val moc = str(refs["Moc"]) ?: throw ImportException.InvalidFormat("缺少 Moc 模型引用")
        if (!moc.endsWith(".moc3", true)) throw ImportException.InvalidFormat("仅支持 Cubism .moc3 模型")
        val textures = refs["Textures"] as? JsonArray
            ?: throw ImportException.InvalidFormat("缺少模型贴图数组")
        if (textures.isEmpty()) throw ImportException.InvalidFormat("模型没有贴图")
        val paths = mutableListOf(moc)
        paths.addAll(textures.map {
            str(it)?.takeIf { s -> s.endsWith(".png", true) }
                ?: throw ImportException.InvalidFormat("模型贴图路径必须是 PNG")
        })
        listOf("Physics", "Pose", "DisplayInfo", "UserData").forEach { key ->
            str(refs[key])?.let(paths::add)
        }
        (refs["Motions"] as? JsonObject)?.values?.forEach { list ->
            (list as? JsonArray)?.forEach { v ->
                (v as? JsonObject)?.let { obj ->
                    str(obj["File"])?.let(paths::add)
                    str(obj["Sound"])?.let(paths::add)
                }
            }
        }
        (refs["Expressions"] as? JsonArray)?.forEach { e ->
            str((e as? JsonObject)?.get("File"))?.let(paths::add)
        }
        if (paths.size > 400) throw ImportException.InvalidFormat("模型引用文件过多")
        for (path in paths) resolveAsset(root, path)
            ?: throw ImportException.InvalidFormat("模型引用文件不完整或路径不安全：${path.take(100)}")
        return Model(manifest, root, manifest.name.removeSuffix(".model3.json").take(80).ifBlank { "Live2D 小伙伴" })
    }

    /** Reject traversal and any URL/absolute path, including Windows separators. */
    fun resolveAsset(root: File, relative: String): File? {
        if (relative.isBlank() || relative.length > 350 || relative.startsWith('/') ||
            relative.contains('\\') || relative.contains(':') || relative.contains('?') ||
            relative.contains('#') || relative.any { it.isISOControl() }) return null
        if (relative.split('/').any { it.isBlank() || it == "." || it == ".." }) return null
        return runCatching {
            val base = root.canonicalPath
            val target = File(root, relative).canonicalFile
            target.takeIf { it.isFile && it.canonicalPath.startsWith(base + File.separator) }
        }.getOrNull()
    }
}
