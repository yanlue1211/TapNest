package com.deskpet.ui

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deskpet.data.PetRepository
import com.deskpet.data.TapNestLayeredStore
import com.deskpet.data.model.PetInfo
import com.deskpet.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Entirely on-device character creation: pick artwork, reuse a layout, adjust anchors,
 * or start with a blank manual rig. Does not require a desktop or Cubism.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LayeredPetEditor(
    petRepository: PetRepository,
    onCreated: (PetInfo) -> Unit,
    onBack: () -> Unit,
    editingPet: PetInfo? = null
) {
    val ctx = LocalContext.current
    val store = remember { TapNestLayeredStore(ctx) }
    val scope = rememberCoroutineScope()
    val templates = remember { store.savedTemplates() }
    val originalRig = remember(editingPet?.id) {
        editingPet?.let(store::loadRig) ?: TapNestTemplates.manual()
    }
    var rig by remember(editingPet?.id) { mutableStateOf(originalRig) }
    var templateChoice by remember(editingPet?.id) {
        mutableStateOf(if (editingPet == null) TapNestTemplates.MANUAL else originalRig.templateId)
    }
    var name by remember(editingPet?.id) { mutableStateOf(editingPet?.displayName ?: "我的小伙伴") }
    var saveTemplate by remember(editingPet?.id) { mutableStateOf(editingPet == null) }
    var busy by remember { mutableStateOf(false) }
    var activeLayer by remember { mutableStateOf("body") }
    var selectedBone by remember(activeLayer) { mutableIntStateOf(0) }
    val editorRequester = remember { BringIntoViewRequester() }
    var previewMode by remember(editingPet?.id) { mutableStateOf(RigPreviewMode.POSITION) }
    var newLayerName by remember { mutableStateOf("") }
    var symmetricNewLayer by remember { mutableStateOf(false) }
    var showSettings by remember(editingPet?.id) { mutableStateOf(false) }
    var showOtherParts by remember { mutableStateOf(true) }
    var showBonesHelp by remember { mutableStateOf(false) }
    val pageScroll = rememberScrollState()
    LaunchedEffect(showSettings, activeLayer) { pageScroll.scrollTo(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val images = remember(editingPet?.id) {
        mutableStateMapOf<String, Uri>().apply {
            val dir = editingPet?.petJsonPath?.let { java.io.File(it).parentFile }
            if (dir != null) originalRig.layers.forEach { layer ->
                if (TapNestLayeredStore.safeAsset(layer.image)) {
                    val file = java.io.File(dir, layer.image)
                    if (file.isFile) put(layer.id, Uri.fromFile(file))
                }
            }
        }
    }
    var pickingLayer by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && pickingLayer.isNotEmpty()) {
            images[pickingLayer] = uri
        }
    }

    // Keep the preview canvas dimensions consistent with the body PNG actually saved.
    LaunchedEffect(images["body"]) {
        val uri = images["body"] ?: return@LaunchedEffect
        val dimensions = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, bounds)
                }
                if (bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096)
                    bounds.outWidth to bounds.outHeight else null
            }.getOrNull()
        }
        if (dimensions != null && (rig.canvasWidth != dimensions.first ||
                rig.canvasHeight != dimensions.second)) {
            rig = rig.copy(canvasWidth = dimensions.first, canvasHeight = dimensions.second)
        }
    }

    fun select(key: String) {
        templateChoice = key
        rig = when (key) {
            TapNestTemplates.MANUAL -> TapNestTemplates.manual()
            else -> templates.firstOrNull { it.id == key }?.rig ?: TapNestTemplates.manual()
        }
        images.clear()
        activeLayer = rig.layers.firstOrNull()?.id ?: "body"
        previewMode = RigPreviewMode.POSITION
        showSettings = false
        error = null
    }

    BackHandler(!busy) { if (showSettings) showSettings = false else onBack() }
    Scaffold(topBar = {
        TopAppBar(title = { Text(if (showSettings) "⚙️ 当前部位 · 动作帧" else
                if (editingPet == null) "🌱 制作桌面伙伴" else "🦴 重新编辑 · ${editingPet.displayName}") },
            navigationIcon = { TextButton(onClick = {
                if (showSettings) showSettings = false else onBack()
            }, enabled = !busy) { Text(if (showSettings) "返回素材" else "返回") } })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(pageScroll).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!showSettings) {
            Text("选择创建方式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("可直接套用模板，也可以完全手动制作；只读取你从手机选择的 PNG。",
                style = MaterialTheme.typography.bodySmall)
            FilterChip(
                selected = templateChoice == TapNestTemplates.MANUAL,
                onClick = { select(TapNestTemplates.MANUAL) },
                label = { Text("🌱 通用空白模板 · 可删改部位") }
            )
            for (template in templates) {
                FilterChip(selected = templateChoice == template.id,
                    onClick = { select(template.id) },
                    label = { Text("我的模板 · ${template.name}") })
            }
            if (rig.layers.any { it.id.startsWith("head_") || it.id.startsWith("face_") }) {
                Text("头部展示方式", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = rig.headMode == TapNestHeadMode.FULL_HEAD,
                        onClick = { rig = rig.copy(headMode = TapNestHeadMode.FULL_HEAD) },
                        label = { Text("4张完整头部") }
                    )
                    FilterChip(
                        selected = rig.headMode == TapNestHeadMode.BLANK_HEAD_WITH_FACE,
                        onClick = { rig = rig.copy(headMode = TapNestHeadMode.BLANK_HEAD_WITH_FACE) },
                        label = { Text("空白头＋独立表情") }
                    )
                }
            }
            OutlinedTextField(value = name, onValueChange = { name = it.take(60) },
                label = { Text("这只桌宠的名字") }, modifier = Modifier.fillMaxWidth())
            Text("导入图片到对应图层", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("推荐所有 PNG 使用相同透明画布尺寸；也可选中图层调位置、缩放、旋转中心。带 ①② 的素材是两帧切换，不需要绘制中间帧。",
                style = MaterialTheme.typography.bodySmall)
            val slots = TapNestTemplates.slotsFor(rig).filter { slot ->
                rig.layers.firstOrNull { it.id == slot.id }?.frameOwner == null
            }
            for (slot in slots) {
                val required = TapNestTemplates.isRequired(slot.id, rig.headMode).let {
                    if (rig.templateId == TapNestTemplates.MANUAL) slot.id == "body" else it
                }
                val relevant = when {
                    rig.headMode == TapNestHeadMode.FULL_HEAD ->
                        slot.id != "head_blank" && !slot.id.startsWith("face_")
                    else -> !slot.id.matches(Regex("head_[0-9]+"))
                }
                if (!relevant) continue
                val uri = images[slot.id]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text((if (required) "★ " else "") + slot.label,
                        modifier = Modifier.weight(1f).clickable { activeLayer = slot.id },
                        style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = {
                        activeLayer = slot.id
                        pickingLayer = slot.id
                        picker.launch(arrayOf("image/png"))
                    }) { Text(if (uri == null) "导入" else "已选 ✓") }
                    if (uri != null) TextButton(onClick = { images.remove(slot.id) }) { Text("清除") }
                }
                TextButton(onClick = {
                    activeLayer = slot.id
                    previewMode = RigPreviewMode.POSITION
                    showSettings = true
                }) { Text("⚙️ 设置本默认帧的动作／变帧 ›") }
            }
            if (rig.templateId == TapNestTemplates.MANUAL) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("对称建立两个独立图层", modifier = Modifier.weight(1f))
                    Switch(checked = symmetricNewLayer,
                        onCheckedChange = { symmetricNewLayer = it })
                }
                Text("例如输入 wing / ear / eye，勾选对称后自动创建 _left 和 _right；图片分别上传，参数也可以分别修改。",
                    style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = newLayerName,
                        onValueChange = { newLayerName = it.take(35) },
                        label = { Text("新图层名称（如 left_ear）") },
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val base = newLayerName.trim()
                            .removeSuffix("_left").removeSuffix("_right")
                        val ids = if (symmetricNewLayer)
                            listOf("${base}_left", "${base}_right")
                            else listOf(newLayerName.trim())
                        if (ids.all { it.matches(Regex("[a-zA-Z][a-zA-Z0-9_]{0,34}")) } &&
                            ids.none { id -> rig.layers.any { it.id == id } } &&
                            rig.layers.size + ids.size <= 32) {
                            rig = rig.copy(layers = rig.layers + ids.mapIndexed { i, id ->
                                TapNestTemplates.newPart(id, rig.layers.size + 10 + i)
                            })
                            activeLayer = ids.first()
                            newLayerName = ""
                            error = null
                        } else error = "名称请用英文字母、数字或下划线且不可重复；对称图层需要预留 _left/_right 后缀"
                    }) { Text("＋添加") }
                }
            }
            } // Basic default-frame upload page
            if (showSettings) {
            TextButton(onClick = { showSettings = false }) { Text("← 返回默认帧素材列表") }
            HorizontalDivider()
            Text("🦴 当前部位绑骨编辑", modifier = Modifier.bringIntoViewRequester(editorRequester),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val layer = rig.layers.firstOrNull { it.id == activeLayer }
            if (layer != null) {
                Text("当前：${TapNestTemplates.slotsFor(rig).firstOrNull { it.id == activeLayer }?.label ?: activeLayer}")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🖼️ 实时合成预览", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("其他部位", style = MaterialTheme.typography.bodySmall)
                Switch(checked = showOtherParts, onCheckedChange = { showOtherParts = it })
            }
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = previewMode == RigPreviewMode.POSITION,
                    onClick = { previewMode = RigPreviewMode.POSITION },
                    label = { Text("移动图层", maxLines = 1) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = previewMode == RigPreviewMode.PIVOT,
                    onClick = { previewMode = RigPreviewMode.PIVOT },
                    label = { Text("点设锚点", maxLines = 1) },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = previewMode == RigPreviewMode.BONES,
                    onClick = { previewMode = RigPreviewMode.BONES },
                    label = { Text("编辑骨骼点", maxLines = 1) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = previewMode == RigPreviewMode.TEST,
                    onClick = { previewMode = RigPreviewMode.TEST },
                    label = { Text("试玩互动", maxLines = 1) },
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                when (previewMode) {
                    RigPreviewMode.POSITION ->
                        "点击预览上的角色部件可选中图层，再拖动改变 X/Y；下方也能精细调整。"
                    RigPreviewMode.PIVOT ->
                        "直接点击当前部件的旋转中心，绿点会移动到点击位置。"
                    RigPreviewMode.BONES ->
                        "点击橙色骨骼点可选中；点击图上其他位置可移动选中的点。至少 2 个点才能柔性弯曲。"
                    RigPreviewMode.TEST ->
                        "点击小伙伴试表情、翅膀和蝴蝶结；长按上半部试摸摸头。点击后会恢复默认表情。"
                },
                style = MaterialTheme.typography.bodySmall
            )
            LayeredRigPreview(
                rig = rig,
                uris = images.toMap(),
                selected = activeLayer,
                mode = previewMode,
                selectedBone = selectedBone,
                showOtherParts = showOtherParts,
                onSelectBone = { selectedBone = it },
                onSetBone = { index, bx, by ->
                    rig = rig.copy(layers = rig.layers.map { layer ->
                        if (layer.id == activeLayer) layer.copy(bones = layer.bones.mapIndexed { i, bone ->
                            if (i == index) TapNestBonePoint(bx, by) else bone
                        }) else layer
                    })
                },
                onSelectLayer = { id -> activeLayer = id },
                onDragLayer = { dx, dy ->
                    rig = rig.copy(layers = rig.layers.map { layer ->
                        if (layer.id == activeLayer)
                            layer.copy(x = (layer.x + dx).coerceIn(-2048f, 2048f),
                                y = (layer.y + dy).coerceIn(-2048f, 2048f))
                        else layer
                    })
                },
                onSetPivot = { x, y ->
                    rig = rig.copy(layers = rig.layers.map { layer ->
                        if (layer.id == activeLayer) layer.copy(pivotX = x, pivotY = y)
                        else layer
                    })
                }
            )

                fun update(p: TapNestLayer) {
                    rig = rig.copy(layers = rig.layers.map { if (it.id == activeLayer) p else it })
                }
                Text("🎞️ 本部位的其他动作帧",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("基础素材列表只放默认帧。此处可以为当前部位再传最多 3 张动作图；不上传也可以只用晃动。图片需与默认帧使用同一透明画布。",
                    style = MaterialTheme.typography.bodySmall)
                val extraFrames = rig.layers.filter { it.frameOwner == layer.id }
                    .sortedBy { it.frameIndex }
                extraFrames.forEach { frame ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("动作帧 ${frame.frameIndex}", modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            pickingLayer = frame.id
                            picker.launch(arrayOf("image/png"))
                        }) { Text(if (images[frame.id] == null) "上传图片" else "替换图片 ✓") }
                        TextButton(onClick = {
                            images.remove(frame.id)
                            rig = rig.copy(layers = rig.layers.filterNot { it.id == frame.id })
                        }) { Text("移除") }
                    }
                }
                TextButton(onClick = {
                    val next = (1..3).firstOrNull { n ->
                        extraFrames.none { it.frameIndex == n }
                    }
                    if (next != null && rig.layers.size < 48) {
                        val frameId = "${layer.id}_f${next}"
                        if (frameId.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,34}")) &&
                            rig.layers.none { it.id == frameId }) {
                            rig = rig.copy(layers = rig.layers + TapNestLayer(
                                id = frameId, image = "", frameOwner = layer.id,
                                frameIndex = next, parentId = layer.id,
                                pivotX = 0f, pivotY = 0f, zIndex = layer.zIndex
                            ))
                        } else error = "图层名太长，无法添加动作帧；请缩短部位名称"
                    }
                }, enabled = extraFrames.size < 3) { Text("＋ 给本默认帧添加动作帧") }
                HorizontalDivider()
                Text("🎮 本部位的点触、定时和表情动作",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (!layer.interactionConfigured) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("启用自定义动作（旧版角色原动作不变）",
                            modifier = Modifier.weight(1f))
                        Switch(checked = false, onCheckedChange = {
                            if (it) update(layer.copy(interactionConfigured = true))
                        })
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("点触时晃动本部位", modifier = Modifier.weight(1f))
                        Switch(checked = layer.tapShake, onCheckedChange = {
                            update(layer.copy(tapShake = it))
                        })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("点触时切换动作帧", modifier = Modifier.weight(1f))
                        Switch(checked = layer.tapSwitchFrame, onCheckedChange = {
                            update(layer.copy(tapSwitchFrame = it))
                        })
                    }
                    if (layer.tapShake || (layer.periodicEnabled &&
                        layer.periodicAction == TapNestPeriodicAction.SHAKE)) {
                        Text("晃动方式（头部点头、身体弹跳、翅膀扇动、耳朵抖动）",
                            style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TapNestPartMotion.entries.forEach { type ->
                                FilterChip(selected = layer.tapMotion == type, onClick = {
                                    update(layer.copy(tapMotion = type))
                                }, label = { Text(when (type) {
                                    TapNestPartMotion.NOD -> "摇头"
                                    TapNestPartMotion.BOUNCE -> "弹跳"
                                    TapNestPartMotion.FLAP -> "扇动"
                                    TapNestPartMotion.TWITCH -> "抖动"
                                }) })
                            }
                        }
                    }
                    if (layer.tapSwitchFrame) {
                        Text("点触时显示哪张动作帧（约 1 秒）",
                            style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (n in 1..3) FilterChip(
                                selected = layer.tapFrame == n,
                                onClick = { update(layer.copy(tapFrame = n)) },
                                label = { Text("帧 ${n}") }
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("每隔一段时间自动动作／换帧", modifier = Modifier.weight(1f))
                        Switch(checked = layer.periodicEnabled, onCheckedChange = {
                            update(layer.copy(periodicEnabled = it))
                        })
                    }
                    if (layer.periodicEnabled) {
                        BindingSlider("自动动作间隔（秒）",
                            layer.periodicIntervalSeconds, 2f, 60f) {
                            update(layer.copy(periodicIntervalSeconds = it))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = layer.periodicAction == TapNestPeriodicAction.SHAKE,
                                onClick = { update(layer.copy(periodicAction = TapNestPeriodicAction.SHAKE)) },
                                label = { Text("短暂晃动") })
                            FilterChip(
                                selected = layer.periodicAction == TapNestPeriodicAction.SWITCH_FRAME,
                                onClick = { update(layer.copy(periodicAction = TapNestPeriodicAction.SWITCH_FRAME)) },
                                label = { Text("短暂换帧") })
                        }
                        if (layer.periodicAction == TapNestPeriodicAction.SWITCH_FRAME) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (n in 1..3) FilterChip(
                                    selected = layer.periodicFrame == n,
                                    onClick = { update(layer.copy(periodicFrame = n)) },
                                    label = { Text("帧 ${n}") }
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("正常静置时偶尔眨眼（需上传闭眼动作帧）",
                            modifier = Modifier.weight(1f))
                        Switch(checked = layer.idleBlinkEnabled, onCheckedChange = {
                            update(layer.copy(idleBlinkEnabled = it))
                        })
                    }
                    if (layer.idleBlinkEnabled) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (n in 1..3) FilterChip(
                                selected = layer.blinkFrame == n,
                                onClick = { update(layer.copy(blinkFrame = n)) },
                                label = { Text("眨眼帧 ${n}") }
                            )
                        }
                    }
                    Text("默认连续晃动请在下方「闲置时自动晃动」开关中设置；与定时抖一下是两个独立功能。",
                        style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
                var parentMenu by remember(activeLayer) { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { parentMenu = true }) {
                        Text("绑定到父图层：${layer.parentId ?: "无（独立）"}  ▾")
                    }
                    DropdownMenu(expanded = parentMenu, onDismissRequest = { parentMenu = false }) {
                        DropdownMenuItem(text = { Text("无（独立）") }, onClick = {
                            update(layer.copy(parentId = null)); parentMenu = false
                        })
                        for (parent in rig.layers.filter { it.id != layer.id }) {
                            val lineage = mutableSetOf<String>()
                            var n: TapNestLayer? = parent
                            while (n != null && lineage.add(n.id)) {
                                n = rig.layers.firstOrNull { it.id == n?.parentId }
                            }
                            if (layer.id in lineage) continue
                            DropdownMenuItem(text = { Text(parent.id) }, onClick = {
                                update(layer.copy(parentId = parent.id)); parentMenu = false
                            })
                        }
                    }
                }
                BindingSlider("水平位置 X", layer.x, -512f, 512f) { update(layer.copy(x = it)) }
                BindingSlider("垂直位置 Y", layer.y, -512f, 512f) { update(layer.copy(y = it)) }
                BindingSlider("旋转中心 X", layer.pivotX, 0f, 1f) { update(layer.copy(pivotX = it)) }
                BindingSlider("旋转中心 Y", layer.pivotY, 0f, 1f) { update(layer.copy(pivotY = it)) }
                BindingSlider("图层原始大小", layer.restScale, 0.2f, 3f) { update(layer.copy(restScale = it)) }
                BindingSlider("前后层级", layer.zIndex.toFloat(), 0f, 50f) {
                    update(layer.copy(zIndex = it.toInt()))
                }
                HorizontalDivider()
                Text("🌿 多点柔性绑骨与日常动作", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
                Text("尾巴、翅膀、耳朵都可以独立绑定。2～8 个点从根到尖，选中点位后可分别设置左右、上下或旋转方向。",
                    style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("闲置时自动晃动", modifier = Modifier.weight(1f))
                    Switch(checked = layer.idleSwing, onCheckedChange = { update(layer.copy(idleSwing = it)) })
                }
                Text("🐾 弹簧尾巴预设", fontWeight = FontWeight.SemiBold)
                var chosenTailPreset by remember(activeLayer) {
                    mutableStateOf(TapNestTailPresets.ALL.first())
                }
                var tailPresetMenu by remember(activeLayer) { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { tailPresetMenu = true }) {
                        Text("${chosenTailPreset.title}  ▾")
                    }
                    DropdownMenu(expanded = tailPresetMenu,
                        onDismissRequest = { tailPresetMenu = false }) {
                        TapNestTailPresets.ALL.forEach { choice ->
                            DropdownMenuItem(text = { Text(choice.title) }, onClick = {
                                chosenTailPreset = choice
                                tailPresetMenu = false
                            })
                        }
                    }
                }
                Text(chosenTailPreset.description, style = MaterialTheme.typography.bodySmall)
                Text("建议骨骼：${chosenTailPreset.bonePercentages.joinToString(" → ") { "${it}%" }}（尾根到尾尖，沿图形中线）",
                    style = MaterialTheme.typography.bodySmall)
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        update(TapNestTailPresets.applySettings(layer, chosenTailPreset))
                    }, modifier = Modifier.weight(1f)) { Text("应用弹簧参数") }
                    OutlinedButton(onClick = {
                        if (layer.bones.size >= 2) {
                            val newBones = TapNestTailPresets.repositionBones(
                                layer.bones, chosenTailPreset
                            )
                            update(layer.copy(bones = newBones))
                            selectedBone = 0
                            previewMode = RigPreviewMode.BONES
                        }
                    }, modifier = Modifier.weight(1f),
                        enabled = layer.bones.size >= 2) { Text("按首尾重排点位") }
                }
                Text(
                    if (layer.bones.size < 2)
                        "先放两个点：P1 在尾根、P2 在尾尖内侧（约 85%～90%），然后才能自动补齐中间点。"
                    else "应用参数不会改动你已绑好的点；「按首尾重排点位」会沿现有骨骼轨迹重新取点，保留首尾坐标。",
                    style = MaterialTheme.typography.bodySmall
                )
                if (chosenTailPreset.swingRange == TapNestSwingRange.OUTWARD_ONLY) {
                    Text("单侧模式：沿身体一侧斜放，从根到尖排点；程序根据方向自动选择向外摇（不跨过原始姿势向内甩）。",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (layer.idleSwing) {
                    Text("🎐 晃动方式", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = layer.swingMode == TapNestSwingMode.DELAY,
                            onClick = { update(layer.copy(swingMode = TapNestSwingMode.DELAY)) },
                            label = { Text("原版延迟") }
                        )
                        FilterChip(
                            selected = layer.swingMode == TapNestSwingMode.SPRING,
                            onClick = { update(layer.copy(swingMode = TapNestSwingMode.SPRING)) },
                            label = { Text("🌀 弹簧惯性") }
                        )
                    }
                    if (layer.swingMode == TapNestSwingMode.SPRING) {
                        Text("尾根带头左右摇，上半段像弹簧一样延迟跟随，转向时会回弹。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    BindingSlider("左右摆动幅度（度）", layer.idleAmplitude, 0f,
                        if (layer.swingMode == TapNestSwingMode.SPRING) 85f else 40f
                    ) { update(layer.copy(idleAmplitude = it)) }
                    BindingSlider("晃动速度（次／秒）", layer.idleSpeed, 0.1f, 3f) {
                        update(layer.copy(idleSpeed = it))
                    }
                    if (layer.swingMode == TapNestSwingMode.SPRING) {
                        BindingSlider("弹簧柔软度（越高越软）", layer.springSoftness, 0f, 1f) {
                            update(layer.copy(springSoftness = it))
                        }
                        BindingSlider("回弹阻尼（越低越弹）", layer.springDamping, 0.1f, 1.3f) {
                            update(layer.copy(springDamping = it))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("单侧向外摇晃", modifier = Modifier.weight(1f))
                            Switch(
                                checked = layer.swingRange == TapNestSwingRange.OUTWARD_ONLY,
                                onCheckedChange = {
                                    update(layer.copy(swingRange = if (it)
                                        TapNestSwingRange.OUTWARD_ONLY else TapNestSwingRange.CENTERED))
                                }
                            )
                        }
                        BindingSlider("🖍️ 粗线条保形（越高越不扭曲）", layer.shapePreservation, 0f, 1f) {
                            update(layer.copy(shapePreservation = it))
                        }
                        Text("粗轮廓或短胖尾巴建议保形 0.7～0.85；保形为 1 时整张图刚性摇摆，不再弯曲。幅度控制左右大摆，柔软度与阻尼控制末端跟随。",
                            style = MaterialTheme.typography.bodySmall)
                        Text("建议至少 3 个点，按根→中→尖排列；大脑袋挡住的尾巴需要在图层位置上调整可见部分。",
                            style = MaterialTheme.typography.bodySmall)
                    } else if (layer.bones.size >= 3) {
                        BindingSlider("上段跟随延迟（秒）", layer.idleLagSeconds, 0f, 0.45f) {
                            update(layer.copy(idleLagSeconds = it))
                        }
                        Text("延迟模式靠近根部先摆、上段稍晚跟上。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("控制点 ${layer.bones.size}/8", modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        if (layer.bones.size < 8) {
                            val end = layer.bones.lastOrNull()
                            val point = if (end == null) TapNestBonePoint(layer.pivotX, layer.pivotY)
                                else TapNestBonePoint(end.x, (end.y - 0.06f).coerceIn(0f, 1f))
                            update(layer.copy(bones = layer.bones + point))
                            selectedBone = layer.bones.size
                            previewMode = RigPreviewMode.BONES
                        }
                    }, enabled = layer.bones.size < 8) { Text("＋ 添加点位") }
                }
                if (layer.bones.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        layer.bones.indices.forEach { index ->
                            FilterChip(selected = selectedBone == index, onClick = {
                                selectedBone = index
                                previewMode = RigPreviewMode.BONES
                            }, label = { Text("${index + 1}") })
                        }
                    }
                    val boneIndex = selectedBone.coerceIn(0, layer.bones.lastIndex)
                    val bone = layer.bones[boneIndex]
                    var dirMenu by remember(activeLayer, boneIndex) { mutableStateOf(false) }
                    fun directionLabel(d: TapNestBoneSwingDirection): String = when(d) {
                        TapNestBoneSwingDirection.AUTO -> "自动旋转"
                        TapNestBoneSwingDirection.HORIZONTAL -> "左右 →"
                        TapNestBoneSwingDirection.HORIZONTAL_REVERSE -> "左右 ←（反向）"
                        TapNestBoneSwingDirection.VERTICAL -> "上下 ↓（翅膀）"
                        TapNestBoneSwingDirection.VERTICAL_REVERSE -> "上下 ↑（反向）"
                        TapNestBoneSwingDirection.ROTATION_REVERSE -> "逆向旋转"
                        TapNestBoneSwingDirection.FIXED -> "固定"
                    }
                    Box {
                        OutlinedButton(onClick = { dirMenu = true }) {
                            Text("第 ${boneIndex + 1} 点运动方向：${directionLabel(bone.swingDirection)} ▾")
                        }
                        DropdownMenu(expanded = dirMenu, onDismissRequest = { dirMenu = false }) {
                            TapNestBoneSwingDirection.entries.forEach { option ->
                                DropdownMenuItem(text = { Text(directionLabel(option)) }, onClick = {
                                    update(layer.copy(bones = layer.bones.mapIndexed { i, original ->
                                        if (i == boneIndex) original.copy(swingDirection = option) else original
                                    }))
                                    dirMenu = false
                                })
                            }
                        }
                    }
                    Text(
                        if (boneIndex == 0)
                            "P1 为根部锚点。它的方向可作为下一点选「自动」时的默认值。"
                        else "本点控制前一个点到本点的骨骼段。横向/纵向位移在大幅度时可能拉伸贴图，可配合保形调整。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    BindingSlider("点位 ${boneIndex + 1} · 横向 X", bone.x, 0f, 1f) {
                        update(layer.copy(bones = layer.bones.mapIndexed { i, old ->
                            if (i == boneIndex) old.copy(x = it) else old
                        }))
                    }
                    BindingSlider("点位 ${boneIndex + 1} · 纵向 Y", bone.y, 0f, 1f) {
                        update(layer.copy(bones = layer.bones.mapIndexed { i, old ->
                            if (i == boneIndex) old.copy(y = it) else old
                        }))
                    }
                    TextButton(onClick = {
                        update(layer.copy(bones = layer.bones.filterIndexed { i, _ -> i != boneIndex }))
                        selectedBone = (boneIndex - 1).coerceAtLeast(0)
                    }) { Text("删除选中的骨骼点") }
                }
                if (layer.bones.size >= 2 && layer.idleSwing) {
                    Text("✨ 已启用多点柔性变形。可点上方「试玩互动」查看动态效果。",
                        color = MaterialTheme.colorScheme.primary)
                }
                OutlinedButton(onClick = { showBonesHelp = !showBonesHelp }) {
                    Text(if (showBonesHelp) "收起点位说明 ▲" else "❓ 控制点怎么绑？展开说明 ▾")
                }
                if (showBonesHelp) {
                    Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("控制点是部件的活动骨架，不是描外轮廓。",
                                fontWeight = FontWeight.SemiBold)
                            Text("① P1 放在连接身体的根部；② 按根→尖沿部位中线排点；③ P2、P3 等控制相邻段的方向；④ 点位越多弯曲层次越多，但太密容易扭曲。")
                            Text("尾巴：从屁股后方一直排到尾尖，粗尾优先调「保形」。")
                            Text("翅膀：从背部连接点朝翅尖排，可选上下扇动；左右翅膀分别导入并各自设点。")
                            Text("耳朵：从耳根排向耳尖，适合少量点位和「每隔几秒抖一下」。")
                            Text("想调整画面：关闭「其他部位」暂时只看本图层；试玩时再打开，检查整体配合。")
                            Text("方向菜单决定单段如何位移；大幅上下／左右位移可能拉长 PNG，不能模拟三维折翼。")
                        }
                    }
                }
                if (layer.id != "body" && layer.frameOwner == null) {
                    var confirmRemovePart by remember(layer.id) { mutableStateOf(false) }
                    TextButton(onClick = { confirmRemovePart = true }) {
                        Text("删除不需要的默认部位", color = MaterialTheme.colorScheme.error)
                    }
                    if (confirmRemovePart) AlertDialog(
                        onDismissRequest = { confirmRemovePart = false },
                        title = { Text("删除这个部位？") },
                        text = { Text("将删除此部位及其添加的动作帧，不会影响其他独立部位。保存前仍可返回模板重新创建。") },
                        confirmButton = { TextButton(onClick = {
                            val removed = rig.layers.filter {
                                it.id == layer.id || it.frameOwner == layer.id
                            }.map { it.id }.toSet()
                            removed.forEach { images.remove(it) }
                            rig = rig.copy(layers = rig.layers
                                .filterNot { it.id in removed }
                                .map { if (it.parentId in removed) it.copy(parentId = "body") else it })
                            activeLayer = "body"
                            showSettings = false
                            confirmRemovePart = false
                        }) { Text("确定删除") } },
                        dismissButton = { TextButton(onClick = {
                            confirmRemovePart = false
                        }) { Text("取消") } }
                    )
                }

            }
            } // End per-part settings page
            if (showSettings && (activeLayer.startsWith("head_") ||
                activeLayer.startsWith("face_") || activeLayer == "head_blank") &&
                (rig.templateId != TapNestTemplates.MANUAL ||
                rig.layers.any { it.id.startsWith("head_") || it.id.startsWith("face_") })) {
                Text("🎭 表情与情感绑定", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
                Text("好感度下降会自动使用「不开心」或「生气」表情；点击互动仍可临时换表情。",
                    style = MaterialTheme.typography.bodySmall)
                for (frameIndex in 0..3) {
                    val current = rig.expressionEmotions[frameIndex] ?: TapNestEmotion.NEUTRAL
                    var opened by remember(frameIndex) { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("第 ${frameIndex + 1} 张表情", modifier = Modifier.weight(1f))
                        Box {
                            OutlinedButton(onClick = { opened = true }) { Text(when(current) {
                                TapNestEmotion.NEUTRAL -> "平常"
                                TapNestEmotion.HAPPY -> "开心"
                                TapNestEmotion.SAD -> "不开心"
                                TapNestEmotion.ANGRY -> "生气"
                                TapNestEmotion.SHY -> "害羞"
                            } + " ▾") }
                            DropdownMenu(expanded = opened, onDismissRequest = { opened = false }) {
                                for (emotion in TapNestEmotion.entries) {
                                    DropdownMenuItem(text = { Text(when(emotion) {
                                        TapNestEmotion.NEUTRAL -> "平常"
                                        TapNestEmotion.HAPPY -> "开心"
                                        TapNestEmotion.SAD -> "不开心"
                                        TapNestEmotion.ANGRY -> "生气"
                                        TapNestEmotion.SHY -> "害羞"
                                    }) }, onClick = {
                                        rig = rig.copy(expressionEmotions =
                                            rig.expressionEmotions + (frameIndex to emotion))
                                        opened = false
                                    })
                                }
                            }
                        }
                    }
                }
            }
            if (showSettings && rig.templateId != TapNestTemplates.MANUAL &&
                (activeLayer.startsWith("head_") || activeLayer.startsWith("face_"))) {
                Text("👀 旧版头部眨眼 / 被戳表情", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
                Text("从现有的 4 张头部表情（或独立脸图）里指定。眨眼仅在静置时偶尔短暂出现；被戳表情优先，不会被眨眼盖掉。",
                    style = MaterialTheme.typography.bodySmall)
                var blinkMenu by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { blinkMenu = true }) {
                        Text("静置眨眼：${if (rig.blinkExpression < 0) "关闭眨眼" else "第 ${rig.blinkExpression + 1} 帧"} ▾")
                    }
                    DropdownMenu(expanded = blinkMenu, onDismissRequest = { blinkMenu = false }) {
                        for (i in -1..3) {
                            DropdownMenuItem(
                                text = { Text(if (i == -1) "关闭眨眼" else "第 ${i + 1} 帧") },
                                onClick = { rig = rig.copy(blinkExpression = i); blinkMenu = false }
                            )
                        }
                    }
                }
                var tapMenu by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { tapMenu = true }) {
                        Text("被戳时表情：${if (rig.tapExpression < 0) "原版循环换脸" else "第 ${rig.tapExpression + 1} 帧"} ▾")
                    }
                    DropdownMenu(expanded = tapMenu, onDismissRequest = { tapMenu = false }) {
                        for (i in -1..3) {
                            DropdownMenuItem(
                                text = { Text(if (i == -1) "原版循环换脸" else "第 ${i + 1} 帧") },
                                onClick = { rig = rig.copy(tapExpression = i); tapMenu = false }
                            )
                        }
                    }
                }
                Text("请确认指定的表情帧已导入闭眼或戳戳对应图片；未导入时仍会回退到默认脸。",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (showSettings) Text("所有部件的静置、点触和定时动作在各自默认帧内分别设置。",
                style = MaterialTheme.typography.bodySmall)
            if (showSettings && (activeLayer.startsWith("head") || activeLayer.startsWith("face")) &&
                rig.templateId != TapNestTemplates.MANUAL) {
                var pettingMenu by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { pettingMenu = true }) {
                        Text("摸摸头专属表情：${if (rig.pettingExpression < 0) "不单独指定" else "第 ${rig.pettingExpression + 1} 帧"} ▾")
                    }
                    DropdownMenu(expanded = pettingMenu, onDismissRequest = { pettingMenu = false }) {
                        for (i in -1..3) {
                            DropdownMenuItem(
                                text = { Text(if (i < 0) "不单独指定" else "第 ${i + 1} 帧") },
                                onClick = {
                                    rig = rig.copy(pettingExpression = i)
                                    pettingMenu = false
                                }
                            )
                        }
                    }
                }
            }
            if (!showSettings) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("把本次绑骨布局存为以后可复用的模板", Modifier.weight(1f))
                Switch(checked = saveTemplate, onCheckedChange = { saveTemplate = it })
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val resultPet = withContext(Dispatchers.IO) {
                                if (editingPet == null) store.createPet(name, rig, images.toMap(), saveTemplate)
                                else store.updatePet(editingPet, name, rig, images.toMap(), saveTemplate)
                            }
                            if (editingPet == null) petRepository.addPetDirect(resultPet)
                            else petRepository.replacePet(resultPet)
                            onCreated(resultPet)
                        } catch (e: Exception) {
                            Log.e("TapNestEditor", "createPet failed", e)
                            error = e.message ?: "制作失败"
                            Toast.makeText(ctx, "创建失败：${e.message}", Toast.LENGTH_LONG).show()
                        } finally { busy = false }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (busy) "正在保存…" else if (editingPet != null) "保存当前角色的修改"
                    else "创建桌宠并保存模板")
            }
            } // Base page save button
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun BindingSlider(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Text("%.2f".format(value), style = MaterialTheme.typography.bodySmall)
        }
        Slider(value = value.coerceIn(min, max), onValueChange = onChange, valueRange = min..max)
    }
}
