package com.deskpet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deskpet.data.TapNestFormStore
import com.deskpet.data.model.PetInfo
import com.deskpet.engine.TapNestForm
import com.deskpet.engine.TapNestFormCollection
import com.deskpet.engine.TapNestForms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Entry screen for an existing layered pet; the original bone editor is reused per form. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayeredFormManager(
    pet: PetInfo,
    onEdit: (String) -> Unit,
    onChanged: () -> Unit,
    onBack: () -> Unit
) {
    val store = remember { TapNestFormStore(LocalContext.current) }
    val scope = rememberCoroutineScope()
    var forms by remember(pet.id) {
        mutableStateOf(runCatching { store.load(pet) }.getOrNull())
    }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<TapNestForm?>(null) }

    fun update(block: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                forms = withContext(Dispatchers.IO) { store.load(pet) }
                error = null
                onChanged()
            } catch (t: Exception) {
                error = t.message ?: "操作失败，请检查角色素材"
            } finally { busy = false }
        }
    }

    deleteTarget?.let { form ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除「" + form.name + "」？") },
            text = { Text("只删除这套形态配置，不删除其他形态或共用图片。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    update { store.remove(pet, form.id) }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("🌱 " + pet.displayName + " · 形态管理") },
            navigationIcon = { TextButton(onClick = onBack) { Text("‹ 返回") } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("一只小伙伴，多种模样", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Text("点「编辑绑骨」进入原来的骨骼编辑器。复制形态会保留全部图层、动作和图片，修改副本互不影响。",
                style = MaterialTheme.typography.bodySmall)
            if (forms == null) {
                Text(error ?: "形态配置读取失败，原始资料不会被覆盖",
                    color = MaterialTheme.colorScheme.error)
            } else {
                val snapshot = forms!!
                for (form in snapshot.forms) {
                    val selected = snapshot.activeFormId == form.id
                    val dir = pet.petJsonPath?.let { java.io.File(it).parentFile }
                    val complete = dir != null && TapNestForms.eligible(snapshot, form.id, dir)
                    ElevatedCard(shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow
                        )) {
                        Column(Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (selected) "🌿 " else "🐾 ", style = MaterialTheme.typography.titleLarge)
                                Column(Modifier.weight(1f)) {
                                    Text(form.name, fontWeight = FontWeight.SemiBold)
                                    Text(if (!complete) "待完成绑骨" else if (selected) "当前使用中" else "已保存",
                                        style = MaterialTheme.typography.labelSmall)
                                }
                                if (selected) Text("✓", color = MaterialTheme.colorScheme.primary)
                            }
                            val parts = listOf("ear" to "耳朵", "tail" to "尾巴", "wing" to "翅膀")
                                .filter { (kind, _) -> form.rig.layers.any { TapNestForms.category(it) == kind } }
                                .joinToString(" · ") { it.second }
                            Text(parts.ifBlank { "身体与基础动作" },
                                style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Button(onClick = { onEdit(form.id) }, enabled = !busy) { Text("编辑绑骨") }
                                TextButton(onClick = {
                                    update { store.add(pet, form.id, form.name + "·副本") }
                                }, enabled = !busy && snapshot.forms.size < 24) { Text("复制") }
                                if (!selected) TextButton(onClick = {
                                    update { store.setActive(pet, form.id) }
                                }, enabled = !busy && complete) { Text("使用") }
                                if (snapshot.forms.size > 1)
                                    TextButton(onClick = { deleteTarget = form }, enabled = !busy) {
                                        Text("删除", color = MaterialTheme.colorScheme.error)
                                    }
                            }
                        }
                    }
                }
                OutlinedButton(
                    onClick = { update { store.add(pet, null, "") } },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    enabled = !busy && snapshot.forms.size < 24
                ) { Text("＋ 创建空白形态") }
                Text("💡 点击「复制」会立即创建一个独立副本，随后点新卡片的「编辑绑骨」替换配件即可。",
                    style = MaterialTheme.typography.bodySmall)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
