package com.deskpet.ui

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
@OptIn(ExperimentalMaterial3Api::class)
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
        editingPet?.let(store::loadRig) ?: TapNestTemplates.fairy()
    }
    var rig by remember(editingPet?.id) { mutableStateOf(originalRig) }
    var templateChoice by remember(editingPet?.id) {
        mutableStateOf(if (editingPet == null) TapNestTemplates.FAIRY else originalRig.templateId)
    }
    var name by remember(editingPet?.id) { mutableStateOf(editingPet?.displayName ?: "我的小伙伴") }
    var saveTemplate by remember(editingPet?.id) { mutableStateOf(editingPet == null) }
    var busy by remember { mutableStateOf(false) }
    var activeLayer by remember { mutableStateOf("body") }
    var selectedBone by remember(activeLayer) { mutableIntStateOf(0) }
    val editorRequester = remember { BringIntoViewRequester() }
    var previewMode by remember(editingPet?.id) { mutableStateOf(RigPreviewMode.POSITION) }
    var newLayerName by remember { mutableStateOf("") }
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
            TapNestTemplates.FAIRY -> TapNestTemplates.fairy()
            TapNestTemplates.MANUAL -> TapNestRig(
                canvasWidth = 512, canvasHeight = 512,
                templateId = TapNestTemplates.MANUAL,
                layers = listOf(TapNestLayer("body", "", zIndex = 10))
            )
            else -> templates.firstOrNull { it.id == key }?.rig ?: TapNestTemplates.fairy()
        }
        images.clear()
        activeLayer = rig.layers.firstOrNull()?.id ?: "body"
        previewMode = RigPreviewMode.POSITION
        error = null
    }

    BackHandler(!busy) { onBack() }
    Scaffold(topBar = {
        TopAppBar(title = { Text(if (editingPet == null) "🦋 制作桌面伙伴" else "🦴 重新编辑 · ${editingPet.displayName}") },
            navigationIcon = { TextButton(onClick = onBack, enabled = !busy) { Text("返回") } })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("选择创建方式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("可直接套用模板，也可以完全手动制作；只读取你从手机选择的 PNG。",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = templateChoice == TapNestTemplates.FAIRY,
                    onClick = { select(TapNestTemplates.FAIRY) }, label = { Text("🦋 蝴蝶结小精灵") })
                FilterChip(selected = templateChoice == TapNestTemplates.MANUAL,
                    onClick = { select(TapNestTemplates.MANUAL) }, label = { Text("🦴 完全手动") })
            }
            for (template in templates) {
                FilterChip(selected = templateChoice == template.id,
                    onClick = { select(template.id) },
                    label = { Text("我的模板 · ${template.name}") })
            }
            if (rig.templateId != TapNestTemplates.MANUAL) {
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
            val slots = TapNestTemplates.slotsFor(rig)
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
                if (activeLayer != slot.id || uri != null) {
                    TextButton(onClick = {
                        activeLayer = slot.id
                        previewMode = RigPreviewMode.POSITION
                        scope.launch { delay(80); editorRequester.bringIntoView() }
                    }) { Text("🦴 设置本部位的绑骨、大小与日常动作 ›") }
                }
            }
            if (rig.templateId == TapNestTemplates.MANUAL) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = newLayerName,
                        onValueChange = { newLayerName = it.take(35) },
                        label = { Text("新图层名称（如 left_ear）") },
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val id = newLayerName.trim()
                        if (id.matches(Regex("[a-zA-Z][a-zA-Z0-9_]{0,34}")) &&
                            rig.layers.none { it.id == id } && rig.layers.size < 32) {
                            rig = rig.copy(layers = rig.layers +
                                TapNestLayer(id, "", zIndex = rig.layers.size + 10))
                            activeLayer = id
                            newLayerName = ""
                            error = null
                        } else error = "图层名请用英文字母、数字及下划线，且不能重复"
                    }) { Text("＋添加") }
                }
            }
            HorizontalDivider()
            Text("🦴 当前部位绑骨编辑", modifier = Modifier.bringIntoViewRequester(editorRequester),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val layer = rig.layers.firstOrNull { it.id == activeLayer }
            if (layer != null) {
                Text("当前：${TapNestTemplates.slotsFor(rig).firstOrNull { it.id == activeLayer }?.label ?: activeLayer}")
            Text("🖼️ 实时合成预览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                Text("这里的设置仅作用于「${layer.id}」这张图片。单点会围绕锚点摆动；添加 2～8 个点则可让整张 PNG 像柔软尾巴一样弯曲。",
                    style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("闲置时自动晃动", modifier = Modifier.weight(1f))
                    Switch(checked = layer.idleSwing, onCheckedChange = { update(layer.copy(idleSwing = it)) })
                }
                if (layer.idleSwing) {
                    BindingSlider("晃动幅度（度）", layer.idleAmplitude, 0f, 40f) {
                        update(layer.copy(idleAmplitude = it))
                    }
                    BindingSlider("晃动速度（次／秒）", layer.idleSpeed, 0.1f, 3f) {
                        update(layer.copy(idleSpeed = it))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("控制点 ${layer.bones.size}/8", modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        if (layer.bones.size < 8) {
                            val end = layer.bones.lastOrNull()
                            val point = if (end == null) TapNestBonePoint(layer.pivotX, layer.pivotY)
                                else TapNestBonePoint((end.x + 0.09f).coerceIn(0f, 1f),
                                    (end.y - 0.08f).coerceIn(0f, 1f))
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

            }
            if (rig.templateId != TapNestTemplates.MANUAL ||
                rig.layers.any { it.id.startsWith("head_") || it.id.startsWith("face_") }) {
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
            Text("4 个头部表情在点击后短暂出现，再恢复默认表情。蝴蝶结、翅膀会短暂换帧。尾巴按旋转锚点摆动。",
                style = MaterialTheme.typography.bodySmall)
            if (rig.templateId != TapNestTemplates.MANUAL) {
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
