package com.deskpet.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
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
    val availableExpressions by produceState<List<Int>>(emptyList(), pet.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (pet.type != com.deskpet.data.model.PetType.LAYERED) emptyList() else {
                    val forms = TapNestFormStore(context).load(pet)
                    val rig = com.deskpet.engine.TapNestForms.resolve(forms)
                    val prefix = if (rig.headMode == com.deskpet.engine.TapNestHeadMode.FULL_HEAD) "head_" else "face_"
                    val folder = java.io.File(pet.petJsonPath!!).parentFile
                    (0..3).filter { index -> rig.layers.any { it.id == prefix + index && java.io.File(folder, it.image).isFile } }
                }
            }.getOrDefault(emptyList())
        }
    }

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
            navigationIcon = { TextButton(onClick = { finish() }) { Text("返回") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            NestHero(
                title = pet.displayName + "的小小日常",
                subtitle = "陪它玩、收集专属道具，记下想提醒的事",
                emoji = "💚",
                compact = true
            )
            NestPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("亲密指数", style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f))
                    Text("${currentAffection} / 100 ♡",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold)
                }
                LinearProgressIndicator(progress = { currentAffection / 100f },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                Text("离线天数会缓慢影响好感度，互动可以增加。不同性格的变化速度不同。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            NestSection("伙伴性格", "选择你喜欢的相处节奏")
            Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
            Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("🍪 互动") })
                FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("⏰ 备忘录") })
            }
            if (tab == 0) {
                NestSection("选择互动", "点击道具，和小伙伴一起玩")
                Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        CompanionAction.FEED to "🍪 喂食",
                        CompanionAction.BATH to "🫧 洗澡",
                        CompanionAction.PLAY to "🧸 玩耍"
                    ).forEach { (kind, label) ->
                        FilterChip(selected = action == kind, onClick = { action = kind },
                            label = { Text(label, maxLines = 1) })
                    }
                }
                val animation = profile.interactionAnimations[action] ?: InteractionAnimationSettings()
                fun changeAnimation(next: InteractionAnimationSettings) {
                    profile = profile.copy(interactionAnimations = profile.interactionAnimations + (action to next))
                }
                NestPanel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("互动动画设置", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        Switch(checked = animation.enabled, onCheckedChange = { changeAnimation(animation.copy(enabled = it)) })
                    }
                    Text("动画速度", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        InteractionSpeed.entries.forEach { speed ->
                            FilterChip(selected = animation.speed == speed,
                                onClick = { changeAnimation(animation.copy(speed = speed)) }, label = { Text(speed.label) })
                        }
                    }
                    val stageLabels = when (action) {
                        CompanionAction.FEED -> listOf("被砸表情", "吃饭表情", "吃完表情")
                        CompanionAction.BATH -> listOf("被淋湿表情", "洗澡表情", "洗完表情")
                        CompanionAction.PLAY -> listOf("发现玩具表情", "玩耍表情", "玩完表情")
                    }
                    stageLabels.forEachIndexed { index, label ->
                        Text(label, style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            (listOf(-1) + availableExpressions).forEach { frame ->
                                FilterChip(selected = (animation.expressions.getOrNull(index) ?: -1) == frame,
                                    onClick = {
                                        val frames = (0..2).map { animation.expressions.getOrNull(it) ?: -1 }.toMutableList()
                                        frames[index] = frame
                                        changeAnimation(animation.copy(expressions = frames))
                                    }, label = { Text(if (frame == -1) "不绑定" else if (frame == 0) "默认帧" else "表情 $frame") })
                            }
                        }
                    }
                    Text("只使用当前形态已有的头部／面部表情帧；未绑定或切换后帧不存在时保持正常表情。无需新增素材。",
                        style = MaterialTheme.typography.bodySmall)
                }
                Text("点击道具进行互动；名称、Emoji、图片和专属台词都可以自己修改。",
                    style = MaterialTheme.typography.bodySmall)
                Surface(color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("🎬 本类互动专属动作素材", fontWeight = FontWeight.Bold)
                        Text("导入 PNG 或 GIF 后，互动时整只伙伴暂时显示该素材，配合本类互动动画播放，互动结束自动恢复正常。没有上传则使用现有桌宠。",
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
                        ElevatedCard(shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = MaterialTheme.colorScheme.surface),
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
                                                    result.first.affection, item.emoji)
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
                NestSection("添加自定义道具", "名字、图片和小台词都能自己设置")
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
                NestSection("定时备忘录", "把想起的小事写在这里")
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
