package com.deskpet.ui

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asImageBitmap
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
    editingPet: PetInfo? = null,
    formId: String? = null
) {
    val ctx = LocalContext.current
    val store = remember { TapNestLayeredStore(ctx) }
    val formStore = remember { com.deskpet.data.TapNestFormStore(ctx) }
    val formForEdit = remember(editingPet?.id, formId) {
        if (editingPet != null && formId != null)
            formStore.load(editingPet).forms.firstOrNull { it.id == formId }
        else null
    }
    val scope = rememberCoroutineScope()
    val templates = remember { store.savedTemplates() }
    val originalRig = remember(editingPet?.id, formId) {
        formForEdit?.rig ?: editingPet?.let(store::loadRig) ?: TapNestTemplates.manual()
    }
    var rig by remember(editingPet?.id, formId) { mutableStateOf(originalRig) }
    var templateChoice by remember(editingPet?.id, formId) {
        mutableStateOf(if (editingPet == null) TapNestTemplates.MANUAL else originalRig.templateId)
    }
    var name by remember(editingPet?.id, formId) { mutableStateOf(formForEdit?.name ?: editingPet?.displayName ?: "我的小伙伴") }
    var saveTemplate by remember(editingPet?.id, formId) { mutableStateOf(editingPet == null) }
    var busy by remember { mutableStateOf(false) }
    var activeLayer by remember { mutableStateOf("body") }
    var selectedBone by remember(activeLayer) { mutableIntStateOf(0) }
    val editorRequester = remember { BringIntoViewRequester() }
    var previewMode by remember(editingPet?.id, formId) { mutableStateOf(RigPreviewMode.POSITION) }
    var newLayerName by remember { mutableStateOf("") }
    val partPresets = listOf("左翅膀", "右翅膀", "左耳朵", "右耳朵", "尾巴", "头部", "帽子", "自定义图层")
    var newPartPreset by remember { mutableStateOf(partPresets.first()) }
    var partPresetMenu by remember { mutableStateOf(false) }
    var pendingRemoveLayerId by remember { mutableStateOf<String?>(null) }
    var pickupSettingsOpen by remember { mutableStateOf(false) }
    var showSettings by remember(editingPet?.id, formId) { mutableStateOf(false) }
    var showOtherParts by remember { mutableStateOf(true) }
    var showBonesHelp by remember { mutableStateOf(false) }
    val pageScroll = rememberScrollState()
    var actionPage by remember(activeLayer) { mutableStateOf<String?>(null) }
    var testRequest by remember { mutableIntStateOf(0) }
    val settingsScroll = rememberScrollState()
    val detailScroll = rememberScrollState()
    var dragUnlocked by remember(activeLayer) { mutableStateOf(false) }
    fun back() { if (actionPage != null) actionPage = null else if (showSettings) showSettings = false else onBack() }
    var error by remember { mutableStateOf<String?>(null) }
    val images = remember(editingPet?.id, formId) {
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
            val previous = images[pickingLayer]
            // A fresh PNG on a mirrored copy is already drawn for its own side.
            // Unmirror only this image's owner; retain the copied rig and bones.
            val selected = rig.layers.firstOrNull { it.id == pickingLayer }
            if (selected != null && selected.frameOwner == null &&
                selected.mirrorX && selected.mirroredFromId != null) {
                rig = rig.copy(layers = rig.layers.map { part ->
                    if (part.id == pickingLayer) TapNestSymmetry.independentArtwork(part) else part
                })
            }
            images[pickingLayer] = uri
            // A paired mirrored part shares the source art when first created.
            // Manually replaced art on the mirrored side remains independent.
            rig.layers.filter { it.mirroredFromId == pickingLayer }.forEach { mirror ->
                if (images[mirror.id] == null || images[mirror.id] == previous)
                    images[mirror.id] = uri
            }
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

    BackHandler(!busy) { back() }
    Scaffold(topBar = {
        TopAppBar(title = { Text(if (showSettings) "⚙️ ${rig.layers.firstOrNull { it.id == activeLayer }?.displayName?.ifBlank { activeLayer } ?: activeLayer}" else
                if (editingPet == null) "🌱 创建第一形态" else if (formForEdit != null) "🦴 编辑形态 · ${formForEdit.name}" else "🦴 重新编辑 · ${editingPet.displayName}") },
            navigationIcon = { TextButton(onClick = {
                back()
            }, enabled = !busy) { Text(if (showSettings) "返回素材" else "返回") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (showSettings) {
                Surface(tonalElevation = 2.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            LayeredRigPreview(
                rig = rig,
                uris = images.toMap(),
                selected = activeLayer,
                mode = previewMode,
                selectedBone = selectedBone,
                showOtherParts = showOtherParts,
                allowEditingDrag = dragUnlocked,
                testRequest = testRequest,
                testAction = actionPage,
                onSelectBone = { selectedBone = it },
                onSetBone = { index, bx, by ->
                    rig = rig.copy(layers = rig.layers.map { layer ->
                        if (layer.id == activeLayer) layer.copy(bones = layer.bones.mapIndexed { i, bone ->
                            if (i == index) bone.copy(x = bx, y = by) else bone
                        }) else layer
                    })
                },
                onSelectLayer = { id -> activeLayer = id },
                onDragLayer = { dx, dy ->
                    rig = rig.copy(layers = rig.layers.map { layer ->
                        if (layer.id == activeLayer)
                            layer.copy(x = (layer.x + if (layer.mirrorX) -dx else dx).coerceIn(-2048f, 2048f),
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

                        Row(Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { previewMode = RigPreviewMode.POSITION }) { Text("选择部位") }
                            TextButton(onClick = { previewMode = RigPreviewMode.TEST; testRequest++ }) { Text("测试动作") }
                            TextButton(onClick = { previewMode = RigPreviewMode.BONES; actionPage = "bones" }) { Text("编辑绑骨") }
                            TextButton(onClick = { previewMode = RigPreviewMode.PIVOT }) { Text("旋转中心") }
                            TextButton(onClick = { dragUnlocked = !dragUnlocked }) { Text(if (dragUnlocked) "锁定拖动" else "解锁拖动") }
                        }
                    }
                }
            }
        Column(
            Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(if (!showSettings) pageScroll else if (actionPage == null) settingsScroll else detailScroll).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!showSettings) {
            NestHero(
                title = if (formForEdit != null) "编辑「${formForEdit.name}」"
                    else if (editingPet == null) "从第一种形态开始" else "重新编辑绑骨",
                subtitle = "按部位上传素材，位置、动作和骨骼都能慢慢调整",
                emoji = "🦴", compact = true
            )
            NestSection("选择创建方式", "可以使用已保存的模板，也可以从空白开始制作")
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
            if (rig.templateId != TapNestTemplates.MANUAL &&
                rig.layers.any { it.id.startsWith("head_") || it.id.startsWith("face_") }) {
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
                label = { Text(if (formForEdit != null) "当前形态的名字" else "这只桌宠的名字") }, modifier = Modifier.fillMaxWidth())
            NestPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🌿 日常轻呼吸", modifier = Modifier.weight(1f))
                    Switch(checked = rig.idleBreathingEnabled, onCheckedChange = {
                        rig = rig.copy(idleBreathingEnabled = it)
                    })
                }
                if (rig.idleBreathingEnabled) {
                    BindingSlider("呼吸幅度（推荐 1）",
                        rig.idleBreathingStrength, 0.2f, 2f) {
                        rig = rig.copy(idleBreathingStrength = it)
                    }
                    Text("静置时整只桌宠轻微起伏，脚底位置基本不动；点触与拎起时暂停。可在下方测试预览中查看。",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            NestSection("图层与素材",
                "每个部位可以独立上传和调整。推荐所有 PNG 使用相同透明画布尺寸。")
            val slots = TapNestTemplates.slotsFor(rig).filter { slot ->
                slot.id != "pickup_pose" &&
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
                NestPanel {
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
                    if (rig.templateId == TapNestTemplates.MANUAL && slot.id != "body") {
                        TextButton(onClick = { pendingRemoveLayerId = slot.id }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        activeLayer = slot.id
                        previewMode = RigPreviewMode.POSITION
                        showSettings = true
                    }) { Text("⚙️ 设置动作／变帧 ›") }
                    if (rig.templateId == TapNestTemplates.MANUAL && slot.id != "body") {
                        TextButton(onClick = {
                            val original = rig.layers.firstOrNull { it.id == slot.id }
                            if (original != null) {
                                val suggestedId = when {
                                    original.id.endsWith("_left") ->
                                        original.id.removeSuffix("_left") + "_right"
                                    original.id.endsWith("_right") ->
                                        original.id.removeSuffix("_right") + "_left"
                                    else -> {
                                        val candidate = original.id + "_mirror"
                                        if (candidate.length <= 32) candidate
                                        else "copy_" + (1..999).first { n ->
                                            rig.layers.none { it.id == "copy_$n" }
                                        }
                                    }
                                }
                                val oppositeId = if (suggestedId.length <= 32) suggestedId
                                    else "copy_" + (1..999).first { n ->
                                        rig.layers.none { it.id == "copy_$n" }
                                    }
                                if (rig.layers.any { it.id == oppositeId }) {
                                    error = "对应的另一侧已经存在，可直接编辑它"
                                } else {
                                    val display = original.displayName.ifBlank { slot.label.removeSuffix(" · 默认帧") }
                                    val oppositeName = when {
                                        display.contains("左") -> display.replaceFirst("左", "右")
                                        display.contains("右") -> display.replaceFirst("右", "左")
                                        else -> "$display（镜像）"
                                    }
                                    val copied = TapNestSymmetry.mirroredGroup(
                                        original, rig.layers, oppositeId, oppositeName)
                                    if (rig.layers.size + copied.size > 48) {
                                        error = "图层已达到上限，无法复制"
                                    } else {
                                        copied.forEach { part ->
                                            part.mirroredFromId?.let { sourceId ->
                                                images[sourceId]?.let { images[part.id] = it }
                                            }
                                        }
                                        rig = rig.copy(layers = rig.layers + copied)
                                        error = null
                                    }
                                }
                            }
                        }) { Text("↔ 镜像复制（含动作与点位）") }
                    }
                }
                } // 当前部位卡片
            }
            if (rig.templateId == TapNestTemplates.MANUAL) {
                HorizontalDivider()
                NestSection("添加新部位",
                    "身体是唯一必需素材。可添加任何自定义配件（如蝴蝶结）；上传图片后会自动出现在长按换装菜单里。")
                Box {
                    OutlinedButton(onClick = { partPresetMenu = true }) {
                        Text(newPartPreset + " ▾")
                    }
                    DropdownMenu(expanded = partPresetMenu,
                        onDismissRequest = { partPresetMenu = false }) {
                        partPresets.forEach { option ->
                            DropdownMenuItem(text = { Text(option) }, onClick = {
                                newPartPreset = option
                                partPresetMenu = false
                            })
                        }
                    }
                }
                if (newPartPreset == "自定义图层") {
                    OutlinedTextField(value = newLayerName,
                        onValueChange = { newLayerName = it.take(40) },
                        label = { Text("图层名称（可直接输入中文）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true)
                }
                Button(onClick = {
                    fun freeId(prefix: String): String {
                        val used = rig.layers.map { it.id }.toSet()
                        return (1..999).map { "${prefix}_$it" }.first { it !in used }
                    }
                    fun freeSideId(prefix: String, suffix: String): String {
                        val used = rig.layers.map { it.id }.toSet()
                        return (listOf(prefix) + (2..999).map { "$prefix$it" })
                            .map { "${it}_$suffix" }.first { it !in used }
                    }
                    val spec = newPartPreset
                    val customLabel = newLayerName.trim()
                    val hatLike = spec == "帽子" ||
                        (spec == "自定义图层" &&
                            (customLabel.contains("帽") || customLabel.contains("hat", ignoreCase = true)))
                    val side = when {
                        spec.startsWith("左") -> "left"
                        spec.startsWith("右") -> "right"
                        else -> null
                    }
                    val kind = when {
                        spec.contains("翅膀") -> "wing"
                        spec.contains("耳朵") -> "ear"
                        spec == "尾巴" -> "tail"
                        spec == "头部" -> "head"
                        hatLike -> "hat"
                        else -> "part"
                    }
                    if (kind == "part" && customLabel.isBlank()) {
                        error = "请先填写图层名称（支持中文）"
                    } else if (kind == "head" && rig.layers.any { it.id == "head_0" }) {
                        error = "已经有头部图层了，可在上面直接调整"
                    } else {
                        val label = when (kind) {
                            "wing" -> "翅膀"
                            "ear" -> "耳朵"
                            "tail" -> "尾巴"
                            "head" -> "头部"
                            "hat" -> customLabel.ifBlank { "帽子" }
                            else -> customLabel
                        }
                        val z = when (kind) {
                            "tail" -> 0
                            "wing" -> 5
                            "ear" -> 15
                            "head" -> 20
                            "hat" -> 25
                            else -> 12
                        }
                        val id = when {
                            kind == "head" -> "head_0"
                            kind == "tail" && rig.layers.none { it.id == "tail" } -> "tail"
                            side != null -> freeSideId(kind, side)
                            else -> freeId(kind)
                        }
                        val labelName = when (side) {
                            "left" -> "左$label"
                            "right" -> "右$label"
                            else -> label
                        }
                        // Custom items are real wardrobe categories: each named
                        // accessory exists only after its user-uploaded PNG is saved.
                        // Category follows mirrored clones, survives renames, and can
                        // be hidden/reattached without deleting any artwork.
                        val wardrobeCategory = when (kind) {
                            "ear", "tail", "wing", "hat" -> kind
                            "part" -> "custom_$id"
                            else -> ""
                        }
                        val created = listOf(TapNestTemplates.newPart(id, z).copy(
                            displayName = labelName,
                            wardrobeCategory = wardrobeCategory,
                            parentId = if (kind == "hat" && rig.headMode == TapNestHeadMode.BLANK_HEAD_WITH_FACE)
                                "head_blank" else TapNestTemplates.newPart(id, z).parentId
                        ))
                        if (rig.layers.size + created.size > 48) {
                            error = "图层已满（最多 48 层，包含动作帧）"
                        } else {
                            rig = rig.copy(layers = rig.layers + created)
                            activeLayer = created.first().id
                            newLayerName = ""
                            error = null
                        }
                    }
                }) { Text("＋ 添加选中的部位") }
                Text("两侧可分别创建，也可调好一侧后使用「↔ 镜像复制」；镜像复制后换上自己画的新 PNG，会取消这张默认图的镜像并保留骨骼设置。",
                    style = MaterialTheme.typography.bodySmall)
            }
            FoldHeader("🪁 长按拎起效果", pickupSettingsOpen) {
                pickupSettingsOpen = !pickupSettingsOpen
            }
            if (pickupSettingsOpen) {
                Text("长按桌宠拎起移动时，默认全身固定、只绕画面顶部中点左右轻摇；放下立即恢复。也可启用一张单独的拎起 PNG：它同样只绕默认顶部锚点摇晃，不需要绑骨、设置点位或其他动画。",
                    style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("使用专属拎起单图", modifier = Modifier.weight(1f))
                    Switch(checked = rig.pickupArtworkEnabled, onCheckedChange = { enabled ->
                        val layer = if (enabled && rig.layers.none { it.id == "pickup_pose" })
                            rig.layers + TapNestLayer(
                                id = "pickup_pose", image = "", zIndex = 100,
                                displayName = "拎起姿势 · 整张图"
                            ) else rig.layers
                        rig = rig.copy(pickupArtworkEnabled = enabled, layers = layer)
                    })
                }
                if (rig.pickupArtworkEnabled) {
                    val pose = rig.layers.firstOrNull { it.id == "pickup_pose" }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("专属姿势 PNG", modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            if (pose == null) {
                                rig = rig.copy(layers = rig.layers + TapNestLayer(
                                    id = "pickup_pose", image = "", zIndex = 100,
                                    displayName = "拎起姿势 · 整张图"))
                            }
                            pickingLayer = "pickup_pose"
                            picker.launch(arrayOf("image/png"))
                        }) { Text(if (images["pickup_pose"] != null) "重新上传 ✓" else "上传图片") }
                        if (images["pickup_pose"] != null) {
                            TextButton(onClick = { images.remove("pickup_pose") }) { Text("清除") }
                        }
                    }
                    val scale = pose?.restScale ?: 1f
                    BindingSlider("拎起图片大小", scale, .3f, 3f) { value ->
                        rig = rig.copy(layers = rig.layers.map {
                            if (it.id == "pickup_pose") it.copy(restScale = value) else it
                        })
                    }
                    Text("拎起图片可以比普通画布更长！会保持头部大小和顶部锚点不变，画布自动向下延长，让尾巴和衣摆垂下来。推荐与普通形态保持相同宽度、头部对齐的透明 PNG。",
                        style = MaterialTheme.typography.bodySmall)
                }
                Text("🪁 拎起效果实时预览", fontWeight = FontWeight.SemiBold)
                LayeredRigPreview(
                    rig = rig, uris = images.toMap(), selected = "body",
                    mode = RigPreviewMode.POSITION, selectedBone = 0,
                    showOtherParts = true, pickupPreview = true,
                    onSelectLayer = {}, onDragLayer = { _, _ -> },
                    onSetPivot = { _, _ -> }, onSetBone = { _, _, _ -> },
                    onSelectBone = {}
                )
            }
            val toRemove = pendingRemoveLayerId
            if (toRemove != null) AlertDialog(
                onDismissRequest = { pendingRemoveLayerId = null },
                title = { Text("删除这个默认图层？") },
                text = { Text("将同时删除该部位已有的动作帧；身体不允许删除。") },
                confirmButton = { TextButton(onClick = {
                    if (toRemove != "body") {
                        val removed = rig.layers.filter {
                            it.id == toRemove || it.frameOwner == toRemove
                        }.map { it.id }.toSet()
                        removed.forEach { images.remove(it) }
                        rig = rig.copy(layers = rig.layers
                            .filterNot { it.id in removed }
                            .map { old ->
                                old.copy(
                                    parentId = if (old.parentId in removed) "body" else old.parentId,
                                    mirroredFromId = if (old.mirroredFromId in removed) null else old.mirroredFromId
                                )
                            })
                        if (activeLayer in removed) activeLayer = "body"
                    }
                    pendingRemoveLayerId = null
                }) { Text("确定删除") } },
                dismissButton = { TextButton(onClick = { pendingRemoveLayerId = null }) {
                    Text("取消")
                } }
            )
            } // Basic default-frame upload page
            if (showSettings) {
            TextButton(onClick = { back() }) { Text(if (actionPage == null) "← 返回素材列表" else "← 返回部位设置") }
            Spacer(Modifier.bringIntoViewRequester(editorRequester).height(1.dp))
            val layer = rig.layers.firstOrNull { it.id == activeLayer }
            if (layer != null) {
                var foldPreview by remember(activeLayer) { mutableStateOf(false) }
                var foldFrames by remember(activeLayer) { mutableStateOf(false) }
                var foldActions by remember(activeLayer) { mutableStateOf(false) }
                var foldPosition by remember(activeLayer) { mutableStateOf(true) }
                var foldBones by remember(activeLayer) { mutableStateOf(false) }
                var foldTailPreset by remember(activeLayer) { mutableStateOf(false) }
                var foldSwing by remember(activeLayer) { mutableStateOf(true) }
                var foldBonePoints by remember(activeLayer) { mutableStateOf(true) }
                Text("当前：${TapNestTemplates.slotsFor(rig).firstOrNull { it.id == activeLayer }?.label ?: activeLayer}")
                OutlinedTextField(
                    value = layer.displayName,
                    onValueChange = { text -> rig = rig.copy(layers = rig.layers.map {
                        if (it.id == layer.id) it.copy(displayName = text.take(40)) else it
                    }) },
                    label = { Text("中文图层名（可修改，留空显示默认名称）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )


                fun update(p: TapNestLayer) {
                    rig = rig.copy(layers = rig.layers.map { if (it.id == activeLayer) p else it })
                }
                if (actionPage == null) {
                FoldHeader("📍 A. 位置与图层", foldPosition) { foldPosition = !foldPosition }
                if (foldPosition) {
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
                OutlinedButton(onClick = { update(layer.copy(x = 0f, y = 0f)) }) {
                    Text("↺ 位置复位")
                }
                OutlinedButton(onClick = { update(layer.copy(restScale = 1f)) }) { Text("↺ 大小复位（100%）") }
                OutlinedButton(onClick = { update(layer.copy(x = 0f, y = 0f, restScale = 1f)) }) {
                    Text("↺ 位置与大小全部复位")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("对本部位应用中轴镜像", modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall)
                    Switch(checked = layer.mirrorX,
                        onCheckedChange = { update(layer.copy(mirrorX = it)) })
                }
                } // foldPosition
                }
                if (actionPage == null) {
                    Text("🎮 B. 动作与互动", style = MaterialTheme.typography.titleMedium)
                    MotionEntry("👆 点触互动", layer.tapShake || layer.tapSwitchFrame,
                        if (layer.tapFlexible.enabled) "柔性绑骨 · ${when(layer.tapFlexible.scope) { TapNestMotionScope.TIP -> "只抖尖端"; TapNestMotionScope.DISTAL -> "中后段"; TapNestMotionScope.WHOLE -> "整段" }} · ${layer.tapFlexible.count} 次" else "整体动作 / 换帧",
                        { update(layer.copy(interactionConfigured = true, tapShake = it,
                            tapSwitchFrame = if (it) layer.tapSwitchFrame else false)) }, { actionPage = "tap" })
                    MotionEntry("⏰ 定时动作", layer.periodicEnabled, "每 ${layer.periodicIntervalSeconds} 秒",
                        { update(layer.copy(interactionConfigured = true, periodicEnabled = it)) }, { actionPage = "periodic" })
                    MotionEntry("🌿 持续轻摆", layer.idleSwing, "${if (layer.swingMode == TapNestSwingMode.SPRING) "柔性弹簧" else "原版延迟"} · ${layer.idleAmplitude}°",
                        { update(layer.copy(idleSwing = it)) }, { actionPage = "swing" })
                    if (rig.layers.any { it.frameOwner == layer.id }) MotionEntry("😉 自动眨眼", layer.idleBlinkEnabled,
                        TapNestLayerActions.frameLabel(layer, rig.layers, layer.blinkFrame),
                        { update(layer.copy(interactionConfigured = true, idleBlinkEnabled = it)) }, { actionPage = "blink" })
                }
                if (actionPage == null) FoldHeader("🖼️ C. 素材与动作帧", foldFrames) { foldFrames = !foldFrames }
                if (foldFrames && actionPage == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MotionFrameThumb(images[layer.id])
                    Text("${layer.displayName.ifBlank { "默认帧" }} · 帧0", Modifier.weight(1f))
                    TextButton(onClick = { pickingLayer = layer.id; picker.launch(arrayOf("image/png")) }) { Text("替换素材") }
                }
                Text("基础素材列表只放默认帧。此处可以为当前部位再传最多 3 张动作图；不上传也可以只用晃动。图片需与默认帧使用同一透明画布。",
                    style = MaterialTheme.typography.bodySmall)
                val extraFrames = rig.layers.filter { it.frameOwner == layer.id }
                    .sortedBy { it.frameIndex }
                extraFrames.forEach { frame ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MotionFrameThumb(images[frame.id])
                        OutlinedTextField(value = frame.displayName, onValueChange = { text ->
                            rig = rig.copy(layers = rig.layers.map { if (it.id == frame.id) it.copy(displayName = text.take(80)) else it })
                        }, label = { Text("帧 ${frame.frameIndex} · ✏️ 名称") }, modifier = Modifier.weight(1f))
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
                } // foldFrames
                if (actionPage in listOf("tap", "periodic", "blink")) {
                if (!layer.interactionConfigured) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("启用自定义动作（旧版角色原动作不变）",
                            modifier = Modifier.weight(1f))
                        Switch(checked = false, onCheckedChange = {
                            if (it) update(layer.copy(interactionConfigured = true))
                        })
                    }
                } else {
                    if (actionPage == "tap") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("点触时播放本部位动作", modifier = Modifier.weight(1f))
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
                    if (layer.tapShake) {
                        Text("👆 点触动作类型", style = MaterialTheme.typography.bodySmall)
                        TapNestPartMotion.entries.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { type ->
                                    FilterChip(selected = layer.tapMotion == type, onClick = {
                                        update(layer.copy(
                                            tapMotion = type,
                                            periodicMotion = layer.periodicMotion ?: layer.tapMotion
                                        ))
                                    }, label = { Text(when (type) {
                                        TapNestPartMotion.NOD -> "摇头"
                                        TapNestPartMotion.BOUNCE -> "弹跳"
                                        TapNestPartMotion.FLAP -> "扇动"
                                        TapNestPartMotion.TWITCH -> "抖动"
                                        TapNestPartMotion.HAT_POP -> "帽帽弹起"
                                    }) })
                                }
                            }
                        }
                    }
                    val hatTap = layer.tapShake && layer.tapMotion == TapNestPartMotion.HAT_POP
                    val hatIdle = layer.periodicEnabled &&
                        layer.periodicAction == TapNestPeriodicAction.SHAKE &&
                        (layer.periodicMotion ?: layer.tapMotion) == TapNestPartMotion.HAT_POP
                    if (hatTap || hatIdle) {
                        Text("🎩 帽帽起飞：会明显离开脑袋，再落回原位",
                            style = MaterialTheme.typography.bodySmall)
                        BindingSlider("飞起高度（占整个画布的比例）",
                            layer.hatFlightHeight, 0.08f, 0.40f) {
                            update(layer.copy(hatFlightHeight = it))
                        }
                        Text("默认飞起约画布高度的 22%，约为旧动画的 4 倍。顶部空间需要同时匹配预览与悬浮窗口。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (layer.tapSwitchFrame) {
                        Text("点触时显示哪张动作帧（约 1 秒）",
                            style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (n in 1..3) FilterChip(
                                selected = layer.tapFrame == n,
                                onClick = { update(layer.copy(tapFrame = n)) },
                                label = { Text(TapNestLayerActions.frameLabel(layer, rig.layers, n)) }
                            )
                        }
                    }
                    ImpulseSettings(layer.tapFlexible) { update(layer.copy(tapFlexible = it)) }
                    }
                    if (actionPage == "periodic") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("每隔一段时间自动动作／换帧", modifier = Modifier.weight(1f))
                        Switch(checked = layer.periodicEnabled, onCheckedChange = {
                            update(layer.copy(periodicEnabled = it))
                        })
                    }
                    if (TapNestLayerActions.hasPeriodicPartner(layer, rig.layers)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("左右配件同步定时动作", modifier = Modifier.weight(1f))
                            Switch(checked = layer.syncPairedPeriodic, onCheckedChange = {
                                val pairId = if (layer.id.endsWith("_left"))
                                    layer.id.removeSuffix("_left") + "_right"
                                else layer.id.removeSuffix("_right") + "_left"
                                rig = rig.copy(layers = rig.layers.map { part ->
                                    if (part.id == layer.id || part.id == pairId)
                                        part.copy(syncPairedPeriodic = it) else part
                                })
                            })
                        }
                        Text("默认同一时刻动；只要有一侧启用定时动作，两侧都会响应。关闭后各自按自己的间隔运行。",
                            style = MaterialTheme.typography.bodySmall)
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
                        if (layer.periodicAction == TapNestPeriodicAction.SHAKE) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("动作同时切换帧", Modifier.weight(1f))
                                Switch(layer.periodicSwitchFrame, { update(layer.copy(periodicSwitchFrame = it)) })
                            }
                            Text("🌙 闲置时偶尔动作类型（与点触独立）",
                                style = MaterialTheme.typography.bodySmall)
                            TapNestPartMotion.entries.chunked(2).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.forEach { type ->
                                        FilterChip(
                                            selected = (layer.periodicMotion ?: layer.tapMotion) == type,
                                            onClick = { update(layer.copy(periodicMotion = type)) },
                                            label = { Text(when (type) {
                                                TapNestPartMotion.NOD -> "摇头"
                                                TapNestPartMotion.BOUNCE -> "弹跳"
                                                TapNestPartMotion.FLAP -> "扇动"
                                                TapNestPartMotion.TWITCH -> "抖动"
                                                TapNestPartMotion.HAT_POP -> "帽帽弹起"
                                            }) }
                                        )
                                    }
                                }
                            }
                        }
                        if (layer.periodicAction == TapNestPeriodicAction.SWITCH_FRAME || layer.periodicSwitchFrame) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (n in 1..3) FilterChip(
                                    selected = layer.periodicFrame == n,
                                    onClick = { update(layer.copy(periodicFrame = n)) },
                                    label = { Text(TapNestLayerActions.frameLabel(layer, rig.layers, n)) }
                                )
                            }
                        }
                    }
                    ImpulseSettings(layer.periodicFlexible) { update(layer.copy(periodicFlexible = it)) }
                    }
                    if (actionPage == "blink") {
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
                                label = { Text(TapNestLayerActions.frameLabel(layer, rig.layers, n)) }
                            )
                        }
                    }
                    }
                    Text("点触、定时短动作、连续摆动、眨眼分别设置；左右耳朵／翅膀默认共享定时节奏。",
                        style = MaterialTheme.typography.bodySmall)
                }
                } // foldActions
                if (actionPage == "swing" || actionPage == "bones") {
                Text("尾巴、翅膀、耳朵都可以独立绑定。2～8 个点从根到尖，选中点位后可分别设置左右、上下或旋转方向。",
                    style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("闲置时自动晃动", modifier = Modifier.weight(1f))
                    Switch(checked = layer.idleSwing, onCheckedChange = { update(layer.copy(idleSwing = it)) })
                }
                FoldHeader("🐾 弹簧参数预设", foldTailPreset) { foldTailPreset = !foldTailPreset }
                if (foldTailPreset) {
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
                } // foldTailPreset
                FoldHeader("🎐 晃动方式和参数", foldSwing) { foldSwing = !foldSwing }
                if (foldSwing) {
                if (layer.idleSwing) {
                    Text("晃动方式", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = layer.swingMode == TapNestSwingMode.DELAY,
                            onClick = { update(layer.copy(swingMode = TapNestSwingMode.DELAY)) },
                            label = { Text("原版延迟") }
                        )
                        FilterChip(
                            selected = layer.swingMode == TapNestSwingMode.SPRING,
                            onClick = { update(layer.copy(swingMode = TapNestSwingMode.SPRING, springVersion = 2)) },
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
                } // foldSwing
                FoldHeader("🦴 控制点 / 方向 / 骨骼", foldBonePoints) { foldBonePoints = !foldBonePoints }
                if (foldBonePoints) {
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
                } // foldBonePoints
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
                } // foldBones


            }
            } // End per-part settings page
            var foldEmotions by remember(activeLayer) { mutableStateOf(false) }
            var foldLegacyFaces by remember(activeLayer) { mutableStateOf(false) }
            if (showSettings && (activeLayer.startsWith("head_") ||
                activeLayer.startsWith("face_") || activeLayer == "head_blank") &&
                (rig.templateId != TapNestTemplates.MANUAL ||
                rig.layers.any { it.id.startsWith("head_") || it.id.startsWith("face_") })) {
                FoldHeader("🎭 表情与情感绑定", foldEmotions) { foldEmotions = !foldEmotions }
                if (foldEmotions) {
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
                } // foldEmotions
            }
            if (showSettings && rig.templateId != TapNestTemplates.MANUAL &&
                (activeLayer.startsWith("head_") || activeLayer.startsWith("face_"))) {
                FoldHeader("👀 旧版头部眨眼 / 被戳表情", foldLegacyFaces) { foldLegacyFaces = !foldLegacyFaces }
                if (foldLegacyFaces) {
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
                } // foldLegacyFaces
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
                                else if (formForEdit != null) formStore.saveForm(editingPet, formForEdit.id,
                                    name, rig, images.toMap())
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
                Text(if (busy) "正在保存…" else if (formForEdit != null) "保存当前形态" else if (editingPet != null) "保存当前角色的修改"
                    else "创建桌宠并保存模板")
            }
            } // Base page save button
            Spacer(Modifier.height(18.dp))
        }
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

@Composable
private fun FoldHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        color = if (expanded) colors.primaryContainer else colors.surface,
        border = BorderStroke(1.dp,
            if (expanded) colors.primary.copy(alpha = 0.32f) else colors.outlineVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                color = if (expanded) colors.onPrimaryContainer else colors.onSurface,
                fontWeight = FontWeight.SemiBold)
            Surface(
                color = if (expanded) colors.surface.copy(alpha = 0.78f)
                    else colors.surfaceContainerLow,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(9.dp)
            ) {
                Text(if (expanded) "收起  −" else "展开  ＋",
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    color = colors.primary, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun MotionEntry(title: String, enabled: Boolean, summary: String,
                        onToggle: (Boolean) -> Unit, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f)); Text(if (enabled) "开启" else "关闭")
                Switch(enabled, onToggle)
            }
            Text(summary, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onOpen) { Text("进入设置 →") }
        }
    }
}

@Composable
private fun ImpulseSettings(value: TapNestImpulse, update: (TapNestImpulse) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("柔性绑骨（复用当前控制点）", Modifier.weight(1f))
        Switch(value.enabled, { update(value.copy(enabled = it)) })
    }
    if (value.enabled) {
        TapNestMotionScope.entries.forEach { scope ->
            FilterChip(value.scope == scope, { update(value.copy(scope = scope)) }, label = {
                Text(when(scope) { TapNestMotionScope.WHOLE -> "整段运动"; TapNestMotionScope.DISTAL -> "中后段运动"; TapNestMotionScope.TIP -> "只抖尖端" })
            })
        }
        BindingSlider("摆动幅度", value.amplitude, 0f, 55f) { update(value.copy(amplitude = it)) }
        BindingSlider("动作速度", value.speed, .5f, 6f) { update(value.copy(speed = it)) }
        BindingSlider("柔软度", value.softness, 0f, 1f) { update(value.copy(softness = it)) }
        BindingSlider("回弹阻尼", value.damping, .1f, 1.3f) { update(value.copy(damping = it)) }
        BindingSlider("抖动次数", value.count.toFloat(), 1f, 6f) { update(value.copy(count = it.toInt())) }
        BindingSlider("持续时间（毫秒）", value.durationMs.toFloat(), 200f, 3000f) { update(value.copy(durationMs = it.toLong())) }
    }
}

@Composable
private fun MotionFrameThumb(uri: Uri?) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (uri == null) return@runCatching null
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (kotlin.math.max(bounds.outWidth, bounds.outHeight) / sample > 80) sample *= 2
                context.contentResolver.openInputStream(uri)?.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }.getOrNull()
        }
    }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    image?.let { androidx.compose.foundation.Image(it, "动作帧缩略图", Modifier.size(40.dp)) }
}
