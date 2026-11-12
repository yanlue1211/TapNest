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
    var workingPet by remember(editingPet?.id, formId) { mutableStateOf(editingPet) }
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
    var partFlow by remember(activeLayer) { mutableStateOf(PartEditorFlow()) }
    val actionPage = when (partFlow.trigger) {
        PartTrigger.TAP -> "tap"
        PartTrigger.IDLE -> "periodic"
        PartTrigger.CONTINUOUS -> "swing"
        PartTrigger.BLINK -> "blink"
    }
    var testRequest by remember { mutableIntStateOf(0) }
    val settingsScroll = rememberScrollState()
    val detailScroll = rememberScrollState()
    var dragUnlocked by remember(activeLayer) { mutableStateOf(false) }
    fun back() {
        if (showSettings && partFlow.history.isNotEmpty()) {
            partFlow = partFlow.back()
            previewMode = when (partFlow.step) {
                PartEditorStep.BONES -> RigPreviewMode.BONES
                PartEditorStep.ANCHOR -> RigPreviewMode.PIVOT
                PartEditorStep.POSITION -> RigPreviewMode.POSITION
                else -> RigPreviewMode.TEST
            }
            dragUnlocked = partFlow.step in listOf(PartEditorStep.BONES, PartEditorStep.ANCHOR, PartEditorStep.POSITION)
        } else if (showSettings) showSettings = false else onBack()
    }
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

    fun saveEditor(returnToParts: Boolean) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val existing = workingPet
                val resultPet = withContext(Dispatchers.IO) {
                    if (existing == null) store.createPet(name, rig, images.toMap(), saveTemplate)
                    else if (formForEdit != null) formStore.saveForm(existing, formForEdit.id, name, rig, images.toMap())
                    else store.updatePet(existing, name, rig, images.toMap(), saveTemplate)
                }
                if (existing == null) petRepository.addPetDirect(resultPet) else petRepository.replacePet(resultPet)
                workingPet = resultPet
                if (returnToParts) {
                    showSettings = true
                    partFlow = PartEditorFlow()
                    Toast.makeText(ctx, "当前部位已保存", Toast.LENGTH_SHORT).show()
                } else onCreated(resultPet)
            } catch (e: Exception) {
                Log.e("TapNestEditor", "save failed", e)
                error = e.message ?: "保存失败"
                Toast.makeText(ctx, "保存失败：${e.message}", Toast.LENGTH_LONG).show()
            } finally { busy = false }
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
            }, enabled = !busy) { Text(if (showSettings) "上一步" else "返回") } },
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
                            TextButton(onClick = { previewMode = RigPreviewMode.TEST; testRequest++ }) { Text("测试动作") }
                            TextButton(onClick = { showOtherParts = !showOtherParts }) { Text(if (showOtherParts) "隐藏其他部位" else "显示其他部位") }
                            TextButton(onClick = { saveEditor(true) }, enabled = !busy && images["body"] != null) { Text("保存回部位主页") }
                            if (partFlow.step == PartEditorStep.ANCHOR) TextButton(onClick = { previewMode = RigPreviewMode.PIVOT; dragUnlocked = true }) { Text("继续放锚点") }
                            if (partFlow.step == PartEditorStep.BONES) TextButton(onClick = { previewMode = RigPreviewMode.BONES; dragUnlocked = true }) { Text("继续绑点") }


                            if (partFlow.step in listOf(PartEditorStep.BONES, PartEditorStep.POSITION, PartEditorStep.ANCHOR)) TextButton(onClick = { dragUnlocked = !dragUnlocked }) { Text(if (dragUnlocked) "锁定拖动" else "解锁拖动") }
                        }
                    }
                }
            }
        Column(
            Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(if (!showSettings) pageScroll else if (partFlow.step == PartEditorStep.SELECT) settingsScroll else detailScroll).padding(16.dp),
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
                        partFlow = PartEditorFlow()
                        previewMode = RigPreviewMode.TEST
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
                val layer = rig.layers.firstOrNull { it.id == activeLayer }
                if (layer != null) {
                    fun update(p: TapNestLayer) { rig = rig.copy(layers = rig.layers.map { if (it.id == activeLayer) p else it }) }
                    fun go(step: PartEditorStep) {
                        partFlow = partFlow.next(step)
                        previewMode = when (step) {
                            PartEditorStep.BONES -> RigPreviewMode.BONES
                            PartEditorStep.ANCHOR -> RigPreviewMode.PIVOT
                            PartEditorStep.POSITION -> RigPreviewMode.POSITION
                            else -> RigPreviewMode.TEST
                        }
                        dragUnlocked = step in listOf(PartEditorStep.BONES, PartEditorStep.POSITION, PartEditorStep.ANCHOR)
                        scope.launch { detailScroll.scrollTo(0) }
                    }
                    PartFlowHeader(partFlow)
                    when (partFlow.step) {
                        PartEditorStep.SELECT -> {
                            Text("先选要设置的动作", style = MaterialTheme.typography.titleMedium)
                            PartTrigger.entries.forEach { trigger ->
                                FlowChoice(trigger.label, trigger.description) {
                                    partFlow = partFlow.copy(trigger = trigger)
                                    go(PartEditorStep.ENABLE)
                                }
                            }
                            TextButton(onClick = { go(PartEditorStep.POSITION) }) { Text("调整位置与大小 →") }
                            TextButton(onClick = { go(PartEditorStep.MATERIAL) }) { Text("管理素材与名称 →") }
                        }
                        PartEditorStep.ENABLE -> {
                            val enabled = partFlow.trigger.enabled(layer)
                            Text("是否开启${partFlow.trigger.label}？", style = MaterialTheme.typography.titleMedium)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (enabled) "已开启" else "已关闭", Modifier.weight(1f))
                                Switch(enabled, { update(partFlow.trigger.setEnabled(layer, it)) })
                            }
                            if (partFlow.trigger == PartTrigger.IDLE && enabled) BindingSlider("每隔几秒触发", layer.periodicIntervalSeconds, 1f, 120f) {
                                update(layer.copy(periodicIntervalSeconds = it))
                            }
                            if (partFlow.trigger == PartTrigger.IDLE && enabled && TapNestLayerActions.hasPeriodicPartner(layer, rig.layers)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("左右配件同步触发", Modifier.weight(1f))
                                    Switch(layer.syncPairedPeriodic, { update(layer.copy(syncPairedPeriodic = it)) })
                                }
                            }
                            Button(onClick = { go(if (!enabled) PartEditorStep.SUMMARY else when(partFlow.trigger) {
                                PartTrigger.CONTINUOUS -> PartEditorStep.ANCHOR
                                PartTrigger.BLINK -> PartEditorStep.FRAME
                                else -> PartEditorStep.TYPE
                            }) }) { Text(if (enabled) "下一步 →" else "确认关闭 →") }
                        }
                        PartEditorStep.TYPE -> {
                            Text("这次动作要怎么表现？", style = MaterialTheme.typography.titleMedium)
                            FlowChoice("🖼️ 换帧", "选择或上传这个动作要显示的图片") {
                                update(partFlow.trigger.chooseFrame(layer)); go(PartEditorStep.FRAME)
                            }
                            FlowChoice("🎐 摇晃", "下一步只调整怎么摇") {
                                update(partFlow.trigger.chooseMotion(layer)); go(PartEditorStep.ANCHOR)
                            }
                        }
                        PartEditorStep.FRAME, PartEditorStep.MATERIAL -> {
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

                            if (partFlow.step == PartEditorStep.FRAME) {
                                Text("选择这次动作使用的帧")
                                val target = when(partFlow.trigger) { PartTrigger.TAP -> layer.tapFrame; PartTrigger.BLINK -> layer.blinkFrame; else -> layer.periodicFrame }
                                extraFrames.forEach { frame ->
                                    FilterChip(target == frame.frameIndex, {
                                        update(when(partFlow.trigger) {
                                            PartTrigger.TAP -> layer.copy(tapFrame = frame.frameIndex)
                                            PartTrigger.BLINK -> layer.copy(blinkFrame = frame.frameIndex)
                                            else -> layer.copy(periodicFrame = frame.frameIndex)
                                        })
                                    }, label = { Text(TapNestLayerActions.frameLabel(layer, rig.layers, frame.frameIndex)) })
                                }
                                Text("帧编号和内部绑定不会因为改名而改变。", style = MaterialTheme.typography.bodySmall)
                                Button(onClick = { go(PartEditorStep.SUMMARY) }, enabled = extraFrames.any { it.frameIndex == target && images[it.id] != null }) { Text("下一步 →") }
                            } else {
                                OutlinedTextField(layer.displayName, { update(layer.copy(displayName = it.take(80))) }, label = { Text("默认帧名称") })
                                Button(onClick = { go(PartEditorStep.SUMMARY) }) { Text("完成素材设置 →") }
                            }
                        }
                        PartEditorStep.ANCHOR -> {
                            Text("在预览中点击部位的固定位置，例如耳根或翅膀根部。")
                            BindingSlider("锚点 X", layer.pivotX, 0f, 1f) { update(layer.copy(pivotX = it)) }
                            BindingSlider("锚点 Y", layer.pivotY, 0f, 1f) { update(layer.copy(pivotY = it)) }
                            Button(onClick = { go(PartEditorStep.MOTION) }) { Text("下一步：设置运动 →") }
                        }
                        PartEditorStep.MOTION -> {
                            if (partFlow.trigger == PartTrigger.CONTINUOUS) {
                                BindingSlider("摆动幅度（度）", layer.idleAmplitude, 0f, 85f) { update(layer.copy(idleAmplitude = it)) }
                                BindingSlider("摆动速度（次／秒）", layer.idleSpeed, .1f, 3f) { update(layer.copy(idleSpeed = it)) }
                            } else {
                                PartMotionSettings(layer, partFlow.trigger, ::update)
                                val impulse = if (partFlow.trigger == PartTrigger.TAP) layer.tapFlexible else layer.periodicFlexible
                                fun change(value: TapNestImpulse) { update(if (partFlow.trigger == PartTrigger.TAP) layer.copy(tapFlexible = value) else layer.copy(periodicFlexible = value)) }
                                BindingSlider("动作幅度", impulse.amplitude, 0f, 55f) { change(impulse.copy(amplitude = it)) }
                                BindingSlider("动作速度", impulse.speed, .5f, 6f) { change(impulse.copy(speed = it)) }
                                BindingSlider("动作次数", impulse.count.toFloat(), 1f, 6f) { change(impulse.copy(count = it.toInt())) }
                                BindingSlider("持续时间（毫秒）", impulse.durationMs.toFloat(), 200f, 3000f) { change(impulse.copy(durationMs = it.toLong())) }
                            }
                            Button(onClick = { go(PartEditorStep.FLEX) }) { Text("下一步：是否需要柔性弯曲 →") }
                        }
                        PartEditorStep.FLEX -> {
                            Text("是否需要柔性点？", style = MaterialTheme.typography.titleMedium)
                            Text("柔性点让耳根稳定、耳尖弯动。同一部位只绑一次，其他动作也能使用。")
                            if (partFlow.trigger == PartTrigger.IDLE || partFlow.trigger == PartTrigger.TAP) {
                                val sourceName = if (partFlow.trigger == PartTrigger.IDLE) "点触互动" else "闲置抖动"
                                OutlinedButton(onClick = {
                                    update(partFlow.trigger.setFlexible(layer, true))
                                    Toast.makeText(ctx, "已复用${sourceName}绑骨，动作参数独立保留", Toast.LENGTH_SHORT).show()
                                    go(PartEditorStep.SUMMARY)
                                }, enabled = layer.bones.size >= 2, modifier = Modifier.fillMaxWidth()) {
                                    Text("复用${sourceName}绑骨")
                                }
                                Text(if (layer.bones.size >= 2) "沿用当前部位的 ${layer.bones.size} 个点，不改位置和动作参数。"
                                    else "当前部位还没有可复用的绑骨，请先放置至少两个点。",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            FlowChoice("使用柔性点", if (layer.bones.size >= 2) "已有 ${layer.bones.size} 个点，可继续调整" else "下一步在完整角色上放置控制点") {
                                update(partFlow.trigger.setFlexible(layer, true)); go(PartEditorStep.BONES)
                            }
                            FlowChoice("整体摇晃", "整个部位一起动，保留已经绑好的点") {
                                update(partFlow.trigger.setFlexible(layer, false)); go(PartEditorStep.SUMMARY)
                            }
                        }
                        PartEditorStep.BONES -> {
                            Text("在上方完整角色上放置控制点", style = MaterialTheme.typography.titleMedium)
                            Text("P1 放耳根，后面的点沿耳朵排到耳尖。点选下方编号，再拖上方橙色点。")
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

                            if (layer.bones.size >= 2) {
                                if (partFlow.trigger == PartTrigger.CONTINUOUS) {
                                    BindingSlider("柔软度", layer.springSoftness, 0f, 1f) { update(layer.copy(springSoftness = it)) }
                                    BindingSlider("阻尼", layer.springDamping, .1f, 1.3f) { update(layer.copy(springDamping = it)) }
                                    BindingSlider("保形", layer.shapePreservation, 0f, 1f) { update(layer.copy(shapePreservation = it)) }
                                } else {
                                    val impulse = if (partFlow.trigger == PartTrigger.TAP) layer.tapFlexible else layer.periodicFlexible
                                    ImpulseSettings(impulse, showEnable = false) {
                                        update(if (partFlow.trigger == PartTrigger.TAP) layer.copy(tapFlexible = it) else layer.copy(periodicFlexible = it))
                                    }
                                }
                            }
                            Button(onClick = { go(PartEditorStep.SUMMARY) }, enabled = layer.bones.size >= 2) { Text("绑好了，下一步 →") }
                        }
                        PartEditorStep.POSITION -> {
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

                            Button(onClick = { go(PartEditorStep.SUMMARY) }) { Text("完成位置设置 →") }
                        }
                        PartEditorStep.SUMMARY -> {
                            Text("当前部位设置完成", style = MaterialTheme.typography.titleMedium)
                            Text("可以在上方测试动作，或返回上一步继续调整。保存后回到部位主页面。")
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            Button(onClick = { saveEditor(true) }, enabled = !busy && images["body"] != null, modifier = Modifier.fillMaxWidth()) {
                                Text(if (busy) "正在保存…" else "保存该部位并回到主页面")
                            }
                            TextButton(onClick = { partFlow = PartEditorFlow() }) { Text("继续设置这个部位的其他动作 →") }
                        }
                    }
                }
            } // End per-part settings page
            var foldEmotions by remember(activeLayer) { mutableStateOf(false) }
            var foldLegacyFaces by remember(activeLayer) { mutableStateOf(false) }
            if (showSettings && partFlow.step == PartEditorStep.MATERIAL && (activeLayer.startsWith("head_") ||
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
            if (showSettings && partFlow.step == PartEditorStep.MATERIAL && rig.templateId != TapNestTemplates.MANUAL &&
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
                onClick = { saveEditor(false) },
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
internal fun BindingSlider(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
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
private fun ImpulseSettings(value: TapNestImpulse, showEnable: Boolean = true, update: (TapNestImpulse) -> Unit) {
    if (showEnable) Row(verticalAlignment = Alignment.CenterVertically) {
        Text("柔性绑骨（复用当前控制点）", Modifier.weight(1f))
        Switch(value.enabled, { update(value.copy(enabled = it)) })
    }
    if (value.enabled) {
        TapNestMotionScope.entries.forEach { scope ->
            FilterChip(value.scope == scope, { update(value.copy(scope = scope)) }, label = {
                Text(when(scope) { TapNestMotionScope.WHOLE -> "整段运动"; TapNestMotionScope.DISTAL -> "中后段运动"; TapNestMotionScope.TIP -> "只抖尖端" })
            })
        }
        BindingSlider("柔软度", value.softness, 0f, 1f) { update(value.copy(softness = it)) }
        BindingSlider("回弹阻尼", value.damping, .1f, 1.3f) { update(value.copy(damping = it)) }

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
