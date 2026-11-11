package com.deskpet.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import com.deskpet.data.model.ImportException
import com.deskpet.data.model.PetInfo
import com.deskpet.data.model.PetType
import com.deskpet.engine.TapNestForm
import com.deskpet.engine.TapNestFormAssets
import com.deskpet.engine.TapNestFormCollection
import com.deskpet.engine.TapNestForms
import com.deskpet.engine.TapNestRig
import com.deskpet.engine.TapNestTemplates
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Multi-form companion data live next to rig.json, so legacy pets and backups still work. */
class TapNestFormStore(private val context: Context) {
    // Shared across the UI and overlay service: prevents lost update/read while committing.
    private companion object { val writeLock = Any() }
    private val json = Json { ignoreUnknownKeys = true }

    private fun dir(pet: PetInfo): File {
        require(pet.type == PetType.LAYERED && ZipExtractor.isSafePetId(pet.id))
        val base = File(context.filesDir, "pets").canonicalFile
        val path = File(base, pet.id).canonicalFile
        require(path.parentFile == base && path.isDirectory) { "角色素材目录不可用" }
        return path
    }

    fun load(pet: PetInfo): TapNestFormCollection = synchronized(writeLock) {
        val home = dir(pet)
        val formsFile = File(home, "forms.json")
        if (formsFile.exists()) return TapNestForms.parse(formsFile)
            ?: error("形态存档损坏，已保留原始文件，请先恢复备份")
        val rigFile = File(home, "rig.json")
        val rig = json.decodeFromString<TapNestRig>(rigFile.readText())
        return TapNestForms.starting(rig)
    }

    private fun commit(pet: PetInfo, collection: TapNestFormCollection) {
        val dest = File(dir(pet), "forms.json")
        val serialized = TapNestForms.encode(collection)
        require(serialized.length <= 2_000_000 && collection.forms.size in 1..24)
        val atomic = AtomicFile(dest)
        val stream = atomic.startWrite()
        try {
            stream.write(serialized.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }

    fun add(pet: PetInfo, sourceFormId: String?, label: String): TapNestForm = synchronized(writeLock) {
        val current = load(pet)
        require(current.forms.size < 24) { "每只桌宠最多24种形态" }
        val from = current.forms.firstOrNull { it.id == sourceFormId }
        val form = TapNestForm(
            "form_" + UUID.randomUUID().toString().replace("-", "").take(12),
            label.trim().take(40).ifBlank { "形态" + (current.forms.size + 1) },
            from?.rig ?: TapNestTemplates.manual(), from?.tapDialogues?.toList()
        )
        commit(pet, current.copy(forms = current.forms + form))
        return form
    }

    /** Update only speech metadata; keep artwork, bones, active form and overrides intact. */
    fun setDialogues(pet: PetInfo, id: String, lines: List<String>?) = synchronized(writeLock) {
        val current = load(pet)
        require(current.forms.any { it.id == id }) { "形态不存在" }
        val sanitized = lines?.let(com.deskpet.data.model.TapDialogue::sanitize)
        commit(pet, current.copy(forms = current.forms.map {
            if (it.id == id) it.copy(tapDialogues = sanitized) else it
        }))
    }

    fun remove(pet: PetInfo, id: String) = synchronized(writeLock) {
        val forms = load(pet)
        require(forms.forms.size > 1) { "至少保留一种形态" }
        require(forms.forms.any { it.id == id })
        val remaining = forms.forms.filterNot { it.id == id }
        commit(pet, forms.copy(
            forms = remaining,
            activeFormId = if (forms.activeFormId == id) remaining.first().id else forms.activeFormId,
            overrides = forms.overrides.filterValues { it != id }
        ))
        // Deliberately retain referenced PNGs. Asset cleanup must be a separate safe operation.
    }

    fun setActive(pet: PetInfo, id: String): TapNestFormCollection = synchronized(writeLock) {
        val forms = load(pet)
        require(TapNestForms.eligible(forms, id, dir(pet))) { "该形态还没有身体图片，请先完成绑骨" }
        val changed = TapNestForms.choose(forms, id)
        commit(pet, changed)
        return changed
    }

    fun setPart(pet: PetInfo, category: String, sourceFormId: String?): TapNestFormCollection = synchronized(writeLock) {
        val forms = load(pet)
        val changed = TapNestForms.choosePart(forms, category, sourceFormId)
        if (sourceFormId != null && sourceFormId != "none") {
            require(TapNestForms.eligible(forms, sourceFormId, dir(pet))) { "请先完成来源形态的绑骨" }
        }
        commit(pet, changed)
        return changed
    }

    fun saveForm(pet: PetInfo, id: String, label: String, rig: TapNestRig,
                 inputs: Map<String, Uri>): PetInfo = synchronized(writeLock) {
        val forms = load(pet)
        val previous = forms.forms.firstOrNull { it.id == id } ?: error("形态不存在")
        require(rig.layers.size <= 48)
        val home = dir(pet)
        val created = mutableListOf<File>()
        try {
            val updatedLayers = rig.layers.map { layer ->
                require(layer.id.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,34}"))) { "图层标识不合法" }
                val source = inputs[layer.id] ?: return@map layer.copy(image = "")
                val previousFile = previous.rig.layers.firstOrNull { it.id == layer.id }?.image
                val knownFile = previousFile?.takeIf(TapNestFormAssets::safe)?.let { File(home, it) }
                if (knownFile?.isFile == true && source.scheme == "file" &&
                    source.path?.let { File(it).canonicalFile == knownFile.canonicalFile } == true
                ) return@map layer.copy(image = previousFile)
                val filename = id + "_" + layer.id + "_" + UUID.randomUUID().toString().take(8) + ".png"
                val target = File(home, filename)
                val temp = File(home, filename + ".tmp")
                try {
                    context.contentResolver.openInputStream(source)?.use { input ->
                        ZipExtractor.copyWithLimit(input, temp, 10L * 1024 * 1024)
                    } ?: throw ImportException.FileNotFound("无法读取：" + layer.id)
                    val header = ByteArray(8)
                    temp.inputStream().use { if (it.read(header) != 8 ||
                        !header.contentEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10)))
                        throw ImportException.InvalidFormat("不是PNG图片：" + layer.id) }
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(temp.absolutePath, opts)
                    if (opts.outWidth !in 1..4096 || opts.outHeight !in 1..4096 ||
                        opts.outWidth.toLong() * opts.outHeight > 16_000_000L)
                        throw ImportException.InvalidFormat("图片尺寸无效：" + layer.id)
                    if (!temp.renameTo(target)) error("图片保存失败")
                    created.add(target)
                } finally { temp.delete() }
                layer.copy(image = filename)
            }
            val body = updatedLayers.firstOrNull { it.id == "body" }
            require(body != null && body.image.isNotBlank()) { "请先上传身体默认帧" }
            val bodyOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(File(home, body.image).absolutePath, bodyOptions)
            require(bodyOptions.outWidth in 1..4096 && bodyOptions.outHeight in 1..4096)
            val updatedRig = rig.copy(
                canvasWidth = bodyOptions.outWidth, canvasHeight = bodyOptions.outHeight,
                layers = updatedLayers
            )
            val next = forms.copy(forms = forms.forms.map {
                if (it.id == id) it.copy(name = label.trim().take(40).ifBlank { it.name }, rig = updatedRig)
                else it
            })
            commit(pet, next)
            return pet.copy(revision = System.currentTimeMillis())
        } catch (e: Exception) {
            created.forEach { it.delete() }
            throw e
        }
    }
}
