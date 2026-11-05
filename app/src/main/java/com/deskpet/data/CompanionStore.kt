package com.deskpet.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import kotlin.math.floor

@Serializable
enum class CompanionTemperament(val label: String, val dailyLoss: Int) {
    GENTLE("温柔慢热", 2), NORMAL("普通", 5), GRUMPY("小傲娇", 9)
}

@Serializable
enum class CompanionAction { FEED, BATH, PLAY }

@Serializable
data class CompanionItem(
    val id: String = UUID.randomUUID().toString(),
    val action: CompanionAction,
    val name: String,
    val emoji: String,
    /** Private icon filename in this character's companion folder; no external URLs. */
    val icon: String = "",
    val reply: String = ""
)

@Serializable
data class CompanionReminder(
    val id: String = UUID.randomUUID().toString(),
    val message: String,
    val atMillis: Long,
    val delivered: Boolean = false
)

@Serializable
data class CompanionProfile(
    val affection: Int = 70,
    val updatedAtMillis: Long = 0L,
    val temperament: CompanionTemperament = CompanionTemperament.NORMAL,
    val items: List<CompanionItem> = defaultItems(),
    val reminders: List<CompanionReminder> = emptyList(),
    /** Optional action-specific character PNG/GIF displayed for 60 seconds. */
    val actionVisuals: Map<CompanionAction, String> = emptyMap()
)

fun defaultItems(): List<CompanionItem> = listOf(
    CompanionItem(action = CompanionAction.FEED, name = "小饼干", emoji = "🍪", reply = "好吃！谢谢你给我{item}～"),
    CompanionItem(action = CompanionAction.FEED, name = "苹果", emoji = "🍎", reply = "甜甜的{item}，喜欢！"),
    CompanionItem(action = CompanionAction.FEED, name = "肉肉", emoji = "🍖", reply = "哇！是{item}！"),
    CompanionItem(action = CompanionAction.BATH, name = "泡泡浴", emoji = "🫧", reply = "洗完香香的啦！"),
    CompanionItem(action = CompanionAction.PLAY, name = "小球", emoji = "⚽", reply = "把{item}再扔一次嘛！"),
    CompanionItem(action = CompanionAction.PLAY, name = "玩偶", emoji = "🧸", reply = "这个{item}可以陪我玩吗？")
)

/** Pure, deterministic affinity math. No recurring background writes or unbounded decay. */
object CompanionAffection {
    fun at(profile: CompanionProfile, now: Long): Int {
        if (profile.updatedAtMillis <= 0L || now <= profile.updatedAtMillis) return profile.affection.coerceIn(0, 100)
        val elapsedDays = (now - profile.updatedAtMillis).toDouble() / 86_400_000.0
        val lost = floor(elapsedDays * profile.temperament.dailyLoss).toInt()
        return (profile.affection - lost).coerceIn(0, 100)
    }

    fun interact(profile: CompanionProfile, action: CompanionAction, now: Long): CompanionProfile =
        profile.copy(
            affection = (at(profile, now) + when (action) {
                CompanionAction.FEED -> 5
                CompanionAction.BATH -> 4
                CompanionAction.PLAY -> 6
            }).coerceAtMost(100),
            updatedAtMillis = now
        )
}

/** Stores each character's preferences independently, outside the original pet repository. */
class CompanionStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val root = File(context.filesDir, "companion_profiles").also { it.mkdirs() }
    private fun safe(id: String): String {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,100}"))) { "角色标识无效" }
        return id
    }
    private fun folder(id: String) = File(root, safe(id)).also { it.mkdirs() }
    private fun profileFile(id: String) = File(folder(id), "profile.json")

    @Synchronized fun load(id: String): CompanionProfile {
        val file = profileFile(id)
        if (file.isFile && file.length() in 1..524_288) {
            val saved = runCatching { json.decodeFromString<CompanionProfile>(file.readText()) }.getOrNull()
            if (saved != null) return saved
        }
        // Start the inactivity clock at first use, not at first feeding.
        val initial = CompanionProfile(updatedAtMillis = System.currentTimeMillis())
        save(id, initial)
        return initial
    }

    @Synchronized fun save(id: String, profile: CompanionProfile) {
        val dest = profileFile(id)
        // A reminder delivered by the overlay must never become pending again when
        // the editor saves a stale Compose snapshot a moment later.
        val delivered = runCatching {
            if (dest.isFile) json.decodeFromString<CompanionProfile>(dest.readText())
                .reminders.filter { it.delivered }.map { it.id }.toSet()
            else emptySet()
        }.getOrDefault(emptySet())
        val sanitized = profile.copy(
            affection = profile.affection.coerceIn(0, 100),
            items = profile.items.take(80).map { it.copy(name = it.name.take(35), emoji = it.emoji.take(8), reply = it.reply.take(240)) },
            reminders = profile.reminders.take(100).map {
                it.copy(message = it.message.take(120), delivered = it.delivered || it.id in delivered)
            }
        )
        val tmp = File(dest.parentFile, "profile.tmp")
        tmp.writeText(json.encodeToString(sanitized))
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            error("保存伙伴数据失败")
        }
    }

    @Synchronized fun interact(id: String, action: CompanionAction, itemId: String, now: Long = System.currentTimeMillis()): Pair<CompanionProfile, String> {
        val profile = load(id)
        val item = profile.items.firstOrNull { it.action == action && it.id == itemId } ?: error("互动道具不存在")
        val next = CompanionAffection.interact(profile, action, now)
        save(id, next)
        val text = item.reply.ifBlank {
            when(action) {
                CompanionAction.FEED -> "好吃！谢谢你给我{item}～"
                CompanionAction.BATH -> "洗得干干净净！"
                CompanionAction.PLAY -> "再玩一次吧！"
            }
        }.replace("{item}", item.name).take(60)
        return next to text
    }

    /** Mark as delivered before displaying; a restart cannot replay the same reminder. */
    @Synchronized fun due(id: String, now: Long = System.currentTimeMillis()): List<CompanionReminder> {
        val p = load(id)
        // Show at most one per poll; multiple notes scheduled at the same time
        // should never overwrite each other before the user can read them.
        val due = p.reminders.filter { !it.delivered && it.atMillis in 1..now }
            .sortedBy { it.atMillis }.take(1)
        if (due.isNotEmpty()) {
            val ids = due.map { it.id }.toSet()
            save(id, p.copy(reminders = p.reminders.map { if (it.id in ids) it.copy(delivered = true) else it }))
        }
        return due
    }

    /** Import user-selected image to private storage, never upload or retain content URI. */
    @Synchronized fun importIcon(id: String, itemId: String, uri: Uri): String {
        require(itemId.matches(Regex("[a-zA-Z0-9_-]{1,100}")))
        val file = File(folder(id), "icon_" + itemId + ".png")
        val temp = File(folder(id), "icon_" + itemId + ".tmp")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytes = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        bytes += n
                        require(bytes <= 3L * 1024 * 1024) { "图标图片不能超过 3MB" }
                        output.write(buffer, 0, n)
                    }
                }
            } ?: error("无法读取图片")
            val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
            val header = ByteArray(8)
            temp.inputStream().use { require(it.read(header) == 8 && header.contentEquals(png)) { "仅支持 PNG 图标" } }
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temp.absolutePath, opts)
            require(opts.outWidth in 1..1024 && opts.outHeight in 1..1024) { "请使用不大于 1024×1024 的图标" }
            if (!temp.renameTo(file)) error("保存图标失败")
            return file.name
        } finally { temp.delete() }
    }

    /** Save a temporary character-expression image (PNG or GIF), never upload it. */
    @Synchronized fun importActionVisual(id: String, action: CompanionAction, uri: Uri): String {
        val tmp = File(folder(id), "action_upload.tmp")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= 8L * 1024 * 1024) { "动画素材不能超过 8MB" }
                        out.write(buffer, 0, n)
                    }
                }
            } ?: error("无法打开素材")
            val header = ByteArray(8)
            tmp.inputStream().use { require(it.read(header) >= 6) { "图片文件不完整" } }
            val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
            val isPng = header.contentEquals(png)
            val isGif = String(header.copyOfRange(0, 6), Charsets.US_ASCII) in
                listOf("GIF87a", "GIF89a")
            require(isPng || isGif) { "仅支持 PNG / GIF 动画" }
            val suffix = if (isPng) "png" else "gif"
            if (isPng) {
                val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(tmp.absolutePath, info)
                require(info.outWidth in 1..4096 && info.outHeight in 1..4096) { "图片尺寸超出限制" }
            } else {
                @Suppress("DEPRECATION")
                val movie = tmp.inputStream().use { android.graphics.Movie.decodeStream(it) }
                require(movie != null && movie.width() in 1..2048 &&
                    movie.height() in 1..2048) { "GIF 动画无法解码或尺寸过大" }
            }
            val target = File(folder(id), "action_" + action.name.lowercase() + "." + suffix)
            if (!tmp.renameTo(target)) error("保存动画失败")
            // Only this action can use this asset; old format is not referenced.
            val updated = load(id).copy(actionVisuals = load(id).actionVisuals + (action to target.name))
            save(id, updated)
            return target.name
        } finally { tmp.delete() }
    }

    fun actionVisualFile(id: String, action: CompanionAction, fileName: String?): File? {
        val name = fileName ?: return null
        val expected = "action_" + action.name.lowercase() + "."
        if (!name.startsWith(expected) || (!name.endsWith(".png") && !name.endsWith(".gif"))) return null
        return File(folder(id), name).takeIf { it.isFile }
    }

    fun iconFile(id: String, fileName: String): File? {
        if (!fileName.matches(Regex("icon_[a-zA-Z0-9_-]{1,100}\\.png"))) return null
        return File(folder(id), fileName).takeIf { it.isFile }
    }

    @Synchronized fun delete(id: String) { folder(id).deleteRecursively() }
}
