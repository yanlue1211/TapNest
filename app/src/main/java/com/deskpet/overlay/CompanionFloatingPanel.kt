package com.deskpet.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.WindowManager
import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.deskpet.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Compact, editable floating menu next to the companion, not a full-screen Activity. */
internal class CompanionFloatingPanel(
    private val context: Context,
    private val wm: WindowManager,
    private val owner: LifecycleOwner,
    private val vmOwner: ViewModelStoreOwner,
    private val savedOwner: SavedStateRegistryOwner,
    private val store: CompanionStore,
    private val onAction: (CompanionAction, String, Int, String?) -> Unit,
    private val onClose: () -> Unit
) {
    private var view: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var currentPet = ""
    private val density get() = context.resources.displayMetrics.density
    private fun px(dp: Int) = (dp * density).toInt()

    fun show(petId: String, title: String, x: Float, y: Float, width: Int, bounds: Rect) {
        dismiss()
        currentPet = petId
        val root = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(vmOwner)
            setViewTreeSavedStateRegistryOwner(savedOwner)
            setContent {
                MaterialTheme(colorScheme = lightColorScheme(
                    primary = Color(0xFF45795E),
                    primaryContainer = Color(0xFFDFF5E5),
                    surface = Color(0xFFFCFFFB),
                    background = Color(0xFFFCFFFB)
                )) {
                    PanelContent(title, petId, store,
                        onAction = onAction,
                        onClose = { dismiss(); onClose() })
                }
            }
        }
        val panelWidth = px(310).coerceAtMost(bounds.width() - px(18))
        val panelHeight = px(455).coerceAtMost(bounds.height() - px(30))
        val p = WindowManager.LayoutParams(
            panelWidth, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            this.x = (x + width + px(8)).toInt().let {
                if (it + panelWidth > bounds.right) (x - panelWidth - px(8)).toInt() else it
            }.coerceIn(bounds.left + px(7), (bounds.right - panelWidth - px(7)).coerceAtLeast(bounds.left + px(7)))
            this.y = y.toInt().coerceIn(bounds.top + px(6),
                (bounds.bottom - panelHeight - px(6)).coerceAtLeast(bounds.top + px(6)))
        }
        try {
            wm.addView(root, p)
            view = root
            params = p
        } catch (_: Exception) {
            root.disposeComposition()
            view = null
            params = null
        }
    }

    fun dismiss() {
        view?.let { v ->
            runCatching { wm.removeView(v) }
            v.disposeComposition()
        }
        view = null
        params = null
        currentPet = ""
    }
}

@Composable
private fun PanelContent(
    title: String, petId: String, store: CompanionStore,
    onAction: (CompanionAction, String, Int, String?) -> Unit, onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var page by remember(petId) { mutableStateOf<CompanionAction?>(null) }
    var notes by remember(petId) { mutableStateOf(false) }
    var profile by remember(petId) { mutableStateOf(store.load(petId)) }
    var error by remember { mutableStateOf("") }
    val format = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).apply { isLenient = false } }
    var noteText by remember { mutableStateOf("") }
    var noteDate by remember { mutableStateOf(format.format(Date(System.currentTimeMillis() + 3_600_000L))) }
    val foreground = Color(0xFF314B3B)
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(max = 455.dp)
            .border(1.dp, Color(0xFFB8DEC5), RoundedCornerShape(22.dp)),
        shape = RoundedCornerShape(22.dp),
        shadowElevation = 12.dp,
        color = Color(0xFFFCFFFB)
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🌱 $title", style = MaterialTheme.typography.titleMedium,
                    color = foreground, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    if (page != null || notes) { page = null; notes = false }
                    else onClose()
                }) { Text(if (page != null || notes) "‹ 返回" else "✕ 关闭") }
            }
            if (notes) {
                Text("⏰ 小小备忘录", style = MaterialTheme.typography.titleMedium)
                Text("悬浮伙伴运行期间按时提醒；离线保存。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(noteText, { noteText = it.take(120) },
                    label = { Text("提醒内容") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(noteDate, { noteDate = it.take(24) },
                        label = { Text("提醒时间") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                OutlinedButton(onClick = {
                    val cal = java.util.Calendar.getInstance().apply {
                        runCatching { format.parse(noteDate) }.getOrNull()?.let { time = it }
                    }
                    android.app.DatePickerDialog(context, { _, year, month, day ->
                        val chosen = java.util.Calendar.getInstance().apply {
                            set(java.util.Calendar.YEAR, year)
                            set(java.util.Calendar.MONTH, month)
                            set(java.util.Calendar.DAY_OF_MONTH, day)
                        }
                        android.app.TimePickerDialog(context, { _, hour, minute ->
                            chosen.set(java.util.Calendar.HOUR_OF_DAY, hour)
                            chosen.set(java.util.Calendar.MINUTE, minute)
                            chosen.set(java.util.Calendar.SECOND, 0)
                            noteDate = format.format(chosen.time)
                        }, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), true).show()
                    }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH),
                        cal.get(java.util.Calendar.DAY_OF_MONTH)).show()
                }, modifier = Modifier.fillMaxWidth()) { Text("📆 选择日期与时间") }
                Button(onClick = {
                    val time = runCatching { format.parse(noteDate)?.time }.getOrNull()
                    if (time == null || time <= System.currentTimeMillis()) error = "请输入未来的有效时间"
                    else if (noteText.isNotBlank() && profile.reminders.size < 100) {
                        val next = profile.copy(reminders = profile.reminders +
                            CompanionReminder(message = noteText.trim(), atMillis = time))
                        store.save(petId, next)
                        profile = next
                        noteText = ""; error = ""
                    }
                }, modifier = Modifier.fillMaxWidth(), enabled = noteText.isNotBlank()) {
                    Text("＋ 添加提醒")
                }
                for (note in profile.reminders.sortedBy { it.atMillis }) {
                    Surface(color = Color(0xFFF2F9F3), shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(note.message, color = foreground)
                                Text("${format.format(Date(note.atMillis))} · ${if (note.delivered) "已提醒" else "待提醒"}",
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = {
                                val next = profile.copy(reminders =
                                    profile.reminders.filterNot { it.id == note.id })
                                store.save(petId, next)
                                profile = next
                            }) { Text("删除") }
                        }
                    }
                }
            } else if (page != null) {
                val kind = page!!
                val label = when(kind) {
                    CompanionAction.FEED -> "🍪 喂食"
                    CompanionAction.BATH -> "🫧 洗澡"
                    CompanionAction.PLAY -> "🧸 玩耍"
                }
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text("选一个喜欢的道具～", style = MaterialTheme.typography.bodySmall)
                for (item in profile.items.filter { it.action == kind }) {
                    Surface(
                        onClick = {
                            scope.launch {
                                runCatching {
                                    val (updated, reply) = withContext(Dispatchers.IO) {
                                        store.interact(petId, kind, item.id)
                                    }
                                    profile = updated
                                    onAction(kind, reply, updated.affection, updated.actionVisuals[kind])
                                    onClose()
                                }.onFailure { error = it.message ?: "互动失败" }
                            }
                        },
                        color = Color(0xFFEDF8F0), shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            val customIcon = remember(item.icon) {
                                store.iconFile(petId, item.icon)?.let { file ->
                                    val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                                    runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }.getOrNull()
                                }
                            }
                            if (customIcon != null) Image(customIcon.asImageBitmap(),
                                contentDescription = item.name, modifier = Modifier.size(34.dp))
                            else Text(item.emoji.ifBlank { "🎁" }, style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.width(10.dp))
                            Text(item.name, color = foreground, modifier = Modifier.weight(1f))
                            Text("互动 ›", color = Color(0xFF53836A))
                        }
                    }
                }
            } else {
                val affection = CompanionAffection.at(profile, System.currentTimeMillis())
                Text("💕 好感度 $affection / 100", style = MaterialTheme.typography.bodyMedium,
                    color = foreground)
                LinearProgressIndicator(progress = { affection / 100f }, modifier = Modifier.fillMaxWidth())
                Text("今天想和我做什么？", color = foreground)
                for ((kind, text) in listOf(
                    CompanionAction.FEED to "🍪 喂食",
                    CompanionAction.BATH to "🫧 洗澡",
                    CompanionAction.PLAY to "🧸 玩耍"
                )) {
                    Surface(onClick = { page = kind },
                        shape = RoundedCornerShape(14.dp), color = Color(0xFFEBF7EE)) {
                        Row(Modifier.fillMaxWidth().padding(13.dp)) {
                            Text(text, color = foreground, modifier = Modifier.weight(1f))
                            Text("›", color = Color(0xFF5D9172))
                        }
                    }
                }
                OutlinedButton(onClick = { notes = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("📝 查看／新增定时备忘录")
                }
                Text("道具图片、专属动画、性格及台词可在主界面长按角色设置。",
                    style = MaterialTheme.typography.labelSmall)
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
