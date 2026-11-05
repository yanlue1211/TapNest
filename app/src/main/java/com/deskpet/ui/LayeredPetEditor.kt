package com.deskpet.ui

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val store = remember { TapNestLayeredStore(ctx) }
    val scope = rememberCoroutineScope()
    val templates = remember { store.savedTemplates() }
    var rig by remember { mutableStateOf(TapNestTemplates.fairy()) }
    var templateChoice by remember { mutableStateOf(TapNestTemplates.FAIRY) }
    var name by remember { mutableStateOf("我的小桌宠") }
    var saveTemplate by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var activeLayer by remember { mutableStateOf("body") }
    var newLayerName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val images = remember { mutableStateMapOf<String, Uri>() }
    var pickingLayer by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && pickingLayer.isNotEmpty()) {
            images[pickingLayer] = uri
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
        error = null
    }

    BackHandler(!busy) { onBack() }
    Scaffold(topBar = {
        TopAppBar(title = { Text("🦋 制作分层桌宠") },
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
            Text("调整图层位置和绑骨锚点", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
            val layer = rig.layers.firstOrNull { it.id == activeLayer }
            if (layer != null) {
                Text("当前：${TapNestTemplates.slotsFor(rig).firstOrNull { it.id == activeLayer }?.label ?: activeLayer}")
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
            }
            Text("尾巴可用上面的旋转中心设置根部，点击后自动执行局部动画。4 个头部表情、蝴蝶结两帧、翅膀两帧会按触摸切换。",
                style = MaterialTheme.typography.bodySmall)
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
                            val newPet = withContext(Dispatchers.IO) {
                                store.createPet(name, rig, images.toMap(), saveTemplate)
                            }
                            petRepository.addPetDirect(newPet)
                            onCreated(newPet)
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
                Text(if (busy) "正在保存…" else "创建桌宠并保存模板")
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
