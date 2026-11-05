package com.deskpet.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deskpet.data.*
import com.deskpet.data.model.PetInfo
import com.deskpet.overlay.OverlayState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** All character activities and scheduled notes stay on this phone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionScreen(pet: PetInfo, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { CompanionStore(context) }
    var profile by remember(pet.id) { mutableStateOf(store.load(pet.id)) }
    val scope = rememberCoroutineScope()
    var tab by remember(pet.id) { mutableIntStateOf(0) }
    var action by remember { mutableStateOf(CompanionAction.FEED) }
    var iconItemId by remember { mutableStateOf("") }
    var visualAction by remember { mutableStateOf(CompanionAction.FEED) }
    val visualPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val selectedAction = visualAction
            val snapshot = profile
            scope.launch {
                runCatching {
                    val imageName = withContext(Dispatchers.IO) {
                        store.save(pet.id, snapshot)
                        store.importActionVisual(pet.id, selectedAction, uri)
                    }
                    profile = snapshot.copy(actionVisuals = snapshot.actionVisuals + (selectedAction to imageName))
                }.onFailure { Toast.makeText(context, it.message ?: "动画导入失败", Toast.LENGTH_LONG).show() }
            }
        }
    }
    val iconPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val itemId = iconItemId
        if (uri != null && itemId.isNotBlank()) {
            scope.launch {
                runCatching {
                    val filename = withContext(Dispatchers.IO) { store.importIcon(pet.id, itemId, uri) }
                    profile = profile.copy(items = profile.items.map {
                        if (it.id == itemId) it.copy(icon = filename) else it
                    })
                }.onFailure { Toast.makeText(context, it.message ?: "上传失败", Toast.LENGTH_LONG).show() }
            }
        }
    }
    // Debounced disk persistence. Cancel stale writes when the user continues typing.
    LaunchedEffect(pet.id, profile) {
        delay(320)
        withContext(Dispatchers.IO) { store.save(pet.id, profile) }
    }
    var itemName by remember { mutableStateOf("") }
    var itemEmoji by remember { mutableStateOf("🍪") }
    var noteText by remember { mutableStateOf("") }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).apply { isLenient = false } }
    var noteWhen by remember { mutableStateOf(fmt.format(Date(System.currentTimeMillis() + 3_600_000L))) }
    val currentAffection = remember(profile) { CompanionAffection.at(profile, System.currentTimeMillis()) }

    fun finish() {
        // Protect the latest edit when the user backs out before the debounce delay.
        runCatching { store.save(pet.id, profile) }
        onBack()
    }
    BackHandler { finish() }
    Scaffold(topBar = {
        TopAppBar(title = { Text("💚 ${pet.displayName} · 伙伴小屋") },
            navigationIcon = { TextButton(onClick = { finish() }) { Text("返回") } })
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("好感度 ${currentAffection}/100  ♡", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
            LinearProgressIndicator(progress = { currentAffection / 100f }, modifier = Modifier.fillMaxWidth())
            Text("好感度会随未互动的天数缓慢下降；温柔性格下降更慢，傲娇性格下降更快。互动可以提升好感度。",
                style = MaterialTheme.typography.bodySmall)
            Text("伙伴性格", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (temperament in CompanionTemperament.entries) {
                    FilterChip(
                        selected = profile.temperament == temperament,
                        onClick = {
                            profile = profile.copy(affection = currentAffection,
                                updatedAtMillis = System.currentTimeMillis(), temperament = temperament)
                        },
                        label = { Text(temperament.label, maxLines = 1) }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("🍪 互动") })
                FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("⏰ 备忘录") })
            }
            if (tab == 0) {
                Text("选择互动", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        CompanionAction.FEED to "🍪 喂食",
                        CompanionAction.BATH to "🫧 洗澡",
                        CompanionAction.PLAY to "🧸 玩耍"
                    ).forEach { (kind, label) ->
                        FilterChip(selected = action == kind, onClick = { action = kind },
                            label = { Text(label, maxLines = 1) })
                    }
                }
                Text("点击道具进行互动；名称、Emoji、图片和专属台词都可以自己修改。",
                    style = MaterialTheme.typography.bodySmall)
                Surface(color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("🎬 本类互动专属动作素材", fontWeight = FontWeight.Bold)
                        Text("导入 PNG 或 GIF 后，互动时整只伙伴暂时显示该素材，持续一分钟自动恢复正常。没有上传则使用轻量骨骼动作。",
                            style = MaterialTheme.typography.bodySmall)
                        Text("当前：${profile.actionVisuals[action] ?: "默认动作"}",
                            style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = {
                                visualAction = action
                                visualPicker.launch(arrayOf("image/png", "image/gif"))
                            }) { Text("上传 PNG / GIF") }
                            TextButton(onClick = {
                                profile = profile.copy(actionVisuals = profile.actionVisuals - action)
                            }, enabled = profile.actionVisuals[action] != null) { Text("恢复默认") }
                        }
                    }
                }
                for (item in profile.items.filter { it.action == action }) {
                    key(item.id) {
                        ElevatedCard(shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val imageFile = remember(item.icon) { store.iconFile(pet.id, item.icon) }
                                    val bitmap = remember(imageFile?.absolutePath, imageFile?.lastModified()) {
                                        imageFile?.let { file ->
                                            BitmapFactory.decodeFile(file.absolutePath,
                                                BitmapFactory.Options().apply { inSampleSize = 2 })
                                        }
                                    }
                                    if (bitmap != null) Image(bitmap.asImageBitmap(),
                                        contentDescription = item.name, modifier = Modifier.size(48.dp))
                                    else Text(item.emoji.ifBlank { "🎁" },
                                        style = MaterialTheme.typography.headlineMedium)
                                    Spacer(Modifier.width(10.dp))
                                    Button(onClick = {
                                        scope.launch {
                                            runCatching {
                                                val result = withContext(Dispatchers.IO) {
                                                    // Flush latest editor values before interacting.
                                                    store.save(pet.id, profile)
                                                    store.interact(pet.id, action, item.id)
                                                }
                                                profile = result.first
                                                OverlayState.postInteraction(pet.id, action, result.second,
                                                    result.first.affection)
                                                Toast.makeText(context, result.second, Toast.LENGTH_SHORT).show()
                                            }.onFailure {
                                                Toast.makeText(context, it.message ?: "互动失败", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }) { Text("使用 ${item.name}") }
                                    Spacer(Modifier.weight(1f))
                                    TextButton(onClick = {
                                        profile = profile.copy(items = profile.items.filterNot { it.id == item.id })
                                    }) { Text("删除") }
                                }
                                OutlinedTextField(value = item.name, singleLine = true,
                                    onValueChange = { v ->
                                        profile = profile.copy(items = profile.items.map {
                                            if (it.id == item.id) it.copy(name = v.take(35)) else it
                                        })
                                    }, label = { Text("道具名称") }, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(value = item.emoji, singleLine = true,
                                    onValueChange = { v ->
                                        profile = profile.copy(items = profile.items.map {
                                            if (it.id == item.id) it.copy(emoji = v.take(8)) else it
                                        })
                                    }, label = { Text("Emoji 图标") }, modifier = Modifier.fillMaxWidth())
                                TextButton(onClick = {
                                    iconItemId = item.id
                                    iconPicker.launch(arrayOf("image/png"))
                                }) { Text("🖼️ ${if (item.icon.isBlank()) "导入本地图标（可选）" else "更换图片图标"}") }
                                OutlinedTextField(value = item.reply,
                                    onValueChange = { v ->
                                        profile = profile.copy(items = profile.items.map {
                                            if (it.id == item.id) it.copy(reply = v.take(240)) else it
                                        })
                                    }, minLines = 1, maxLines = 3, label = { Text("使用时台词（{item} 会替换为道具名）") },
                                    modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                HorizontalDivider()
                Text("＋ 添加自定义道具", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(value = itemName, onValueChange = { itemName = it.take(35) },
                    label = { Text("道具名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = itemEmoji, onValueChange = { itemEmoji = it.take(8) },
                    label = { Text("Emoji 图标") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Button(onClick = {
                    if (itemName.isNotBlank() && profile.items.size < 80) {
                        profile = profile.copy(items = profile.items + CompanionItem(
                            id = UUID.randomUUID().toString(), action = action,
                            name = itemName.trim(), emoji = itemEmoji.ifBlank { "🎁" }))
                        itemName = ""
                    }
                }, enabled = itemName.isNotBlank() && profile.items.size < 80) {
                    Text("添加到${when(action) { CompanionAction.FEED -> "喂食"; CompanionAction.BATH -> "洗澡"; CompanionAction.PLAY -> "玩耍" }}")
                }
            } else {
                Text("⏰ 定时备忘录", style = MaterialTheme.typography.titleMedium)
                Text("悬浮伙伴正在运行时，到点自动弹出提醒气泡。未运行期间错过的提醒，会在下次启动悬浮伙伴时显示。",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = noteText, onValueChange = { noteText = it.take(120) },
                    label = { Text("提醒内容") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = noteWhen, onValueChange = { noteWhen = it.take(24) },
                    label = { Text("提醒时间：yyyy-MM-dd HH:mm") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    val time = runCatching { fmt.parse(noteWhen)?.time }.getOrNull()
                    if (time == null || time < System.currentTimeMillis()) {
                        Toast.makeText(context, "请输入未来的有效时间", Toast.LENGTH_SHORT).show()
                    } else if (noteText.isNotBlank() && profile.reminders.size < 100) {
                        profile = profile.copy(reminders = profile.reminders +
                            CompanionReminder(message = noteText.trim(), atMillis = time))
                        noteText = ""
                    }
                }, enabled = noteText.isNotBlank()) { Text("添加定时提醒") }
                for (note in profile.reminders.sortedBy { it.atMillis }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(note.message, style = MaterialTheme.typography.bodyLarge)
                            Text("${fmt.format(Date(note.atMillis))} · ${if (note.delivered) "已提醒" else "待提醒"}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = {
                            profile = profile.copy(reminders = profile.reminders.filterNot { it.id == note.id })
                        }) { Text("删除") }
                    }
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}
