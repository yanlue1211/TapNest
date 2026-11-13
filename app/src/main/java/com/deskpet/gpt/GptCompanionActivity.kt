package com.deskpet.gpt

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deskpet.ui.theme.DeskPetTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-stage native ChatGPT companion. Isolated Activity avoids fragile overlay IME/focus changes.
 * Existing double-tap menu, local interactions and pet drawing continue to work without sign-in.
 */
class GptCompanionActivity : ComponentActivity() {
    companion object { const val EXTRA_PET_ID = "gpt_pet_id" }
    private val credentials by lazy { GptCredentials(applicationContext) }
    private val oauth by lazy { GptOAuth(credentials) }
    private val api by lazy { GptChatApi(oauth) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContent { DeskPetTheme { GptChatScreen(intent.getStringExtra(EXTRA_PET_ID)) } }
    }

    @Composable
    private fun GptChatScreen(petId: String?) {
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()
        var session by remember { mutableStateOf<GptSession?>(null) }
        var modelList by remember { mutableStateOf<List<GptModel>>(emptyList()) }
        var modelSlug by remember { mutableStateOf("") }
        var messages by remember { mutableStateOf<List<GptMessage>>(emptyList()) }
        var partial by remember { mutableStateOf("") }
        var draft by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("正在读取本地授权…") }
        var error by remember { mutableStateOf("") }
        var loggingIn by remember { mutableStateOf(false) }
        var sending by remember { mutableStateOf(false) }
        var loadingModels by remember { mutableStateOf(false) }
        var menuOpen by remember { mutableStateOf(false) }
        var style by remember { mutableStateOf("Chat") }

        LaunchedEffect(petId) {
            style = withContext(Dispatchers.IO) { GptStyle.current(applicationContext, petId) }
            runCatching { withContext(Dispatchers.IO) { credentials.read() } }
                .onSuccess { session = it; status = if (it == null) "未连接 ChatGPT" else "已保存登录，可继续聊天" }
                .onFailure { error = "本地授权记录无法读取：" + (it.message ?: "请检查设备密钥") }
        }
        LaunchedEffect(session?.subject) {
            val profile = session ?: run { messages = emptyList(); modelList = emptyList(); return@LaunchedEffect }
            try {
                messages = withContext(Dispatchers.IO) { GptHistory(applicationContext, profile.subject).load() }
            } catch (e: Exception) { error = "聊天记录读取失败（原文件已保留）：" + (e.message ?: "") }
            if (!profile.allowed) {
                status = "已登录，但未授权使用 ChatGPT 计划；请重新授权"
                return@LaunchedEffect
            }
            loadingModels = true
            try {
                val choices = withContext(Dispatchers.IO) { api.models() }
                modelList = choices
                modelSlug = if (choices.any { it.slug == modelSlug }) modelSlug else choices.firstOrNull()?.slug.orEmpty()
                status = if (choices.isEmpty()) "未返回可用模型，请检查账号权限" else "已连接 · 可聊天"
            } catch (e: Exception) {
                error = "获取模型失败：" + (e.message ?: "")
                status = "模型列表不可用"
            } finally { loadingModels = false }
        }
        LaunchedEffect(messages.size, partial.length) {
            val size = messages.size + if (sending) 1 else 0
            if (size > 0) listState.scrollToItem(size - 1)
        }

        fun login() {
            if (loggingIn || sending) return
            scope.launch {
                loggingIn = true
                error = ""
                status = "即将打开浏览器授权，请在完成后返回点点栖"
                try {
                    val pending = withContext(Dispatchers.IO) { oauth.begin() }
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, pending.browserUri))
                        session = withContext(Dispatchers.IO) { oauth.complete(pending) }
                        status = if (session?.allowed == true) "账号授权成功" else "已登录，但未授予计划额度使用权限"
                    } finally { pending.close() }
                } catch (e: Exception) {
                    error = "登录失败：" + (e.message ?: "请重试")
                    status = "授权未完成"
                } finally { loggingIn = false }
            }
        }

        fun send() {
            val text = draft.trim()
            val connected = session ?: return
            val model = modelSlug
            if (sending || text.isBlank() || model.isBlank() || !connected.allowed) return
            if (text.length > 4000) { error = "单条消息最多 4000 字"; return }
            scope.launch {
                sending = true; error = ""; draft = ""; partial = ""
                try {
                    val mode = withContext(Dispatchers.IO) { GptStyle.current(applicationContext, petId) }
                    style = mode
                    val contextSnapshot = messages
                    val reply = withContext(Dispatchers.IO) {
                        api.reply(model, GptStyle.instructions(mode), contextSnapshot, text) { soFar ->
                            runOnUiThread { partial = soFar }
                        }
                    }
                    withContext(Dispatchers.IO) {
                        GptHistory(applicationContext, connected.subject).append(text, reply)
                    }
                    messages = withContext(Dispatchers.IO) { GptHistory(applicationContext, connected.subject).load() }
                    partial = ""
                    status = "已保存聊天 · " + mode
                } catch (e: Exception) {
                    // Failed/incomplete streaming never enters chat history.
                    if (draft.isBlank()) draft = text
                    error = "发送失败：" + (e.message ?: "未知错误")
                    partial = ""
                } finally { sending = false }
            }
        }

        val mint = Color(0xFFDFF5E5)
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🐾 GPT · " + style, style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { finish() }) { Text("关闭") }
            }
            Text("Chat / Work 跟随桌宠当前形态；同一个 GPT，共享聊天记录。",
                style = MaterialTheme.typography.bodySmall)
            if (session == null) {
                Surface(color = mint, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text("使用 ChatGPT 账号登录", style = MaterialTheme.typography.titleMedium)
                        Text("通过官方页面授权，不需要 API Key；仅在你同意后使用订阅额度。",
                            style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = ::login, enabled = !loggingIn) {
                            Text(if (loggingIn) "等待浏览器授权…" else "继续使用 ChatGPT")
                        }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(session?.email?.ifBlank { "ChatGPT 已连接" } ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = ::login, enabled = !loggingIn && !sending) { Text("重新授权") }
                    TextButton(onClick = {
                        if (!sending && !loggingIn) scope.launch {
                            try {
                                withContext(Dispatchers.IO) { oauth.signOut() }
                                session = null
                                error = ""; status = "已退出；原聊天记录仍保留"
                            } catch (e: Exception) { error = e.message ?: "退出失败" }
                        }
                    }, enabled = !sending && !loggingIn) { Text("退出") }
                }
                if (session?.allowed == true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("模型", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(8.dp))
                        Box {
                            OutlinedButton(onClick = { menuOpen = true },
                                enabled = modelList.isNotEmpty() && !sending) {
                                Text(modelList.firstOrNull { it.slug == modelSlug }?.name
                                    ?: if (loadingModels) "加载中…" else "无可用模型",
                                    maxLines = 1)
                                Text(" ▾")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                modelList.forEach { model ->
                                    DropdownMenuItem(text = { Text(model.name) }, onClick = {
                                        modelSlug = model.slug
                                        menuOpen = false
                                    })
                                }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            scope.launch {
                                loadingModels = true
                                try {
                                    modelList = withContext(Dispatchers.IO) { api.models() }
                                    if (modelList.none { it.slug == modelSlug })
                                        modelSlug = modelList.firstOrNull()?.slug.orEmpty()
                                    error = ""
                                } catch (e: Exception) { error = e.message ?: "无法更新模型" }
                                finally { loadingModels = false }
                            }
                        }, enabled = !sending && !loadingModels) { Text("刷新") }
                    }
                }
            }
            Text(status, color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            LazyColumn(
                state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                items(messages) { msg ->
                    Surface(
                        color = if (msg.role == "user") mint else MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(if (msg.role == "user") "你" else "GPT",
                                style = MaterialTheme.typography.labelSmall)
                            Text(msg.text)
                        }
                    }
                }
                if (sending) item {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium) {
                        Text(if (partial.isBlank()) "GPT 正在回复…" else partial,
                            Modifier.fillMaxWidth().padding(12.dp))
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it.take(4000) },
                    label = { Text("和小金毛说点什么…") },
                    modifier = Modifier.weight(1f), maxLines = 4, enabled = !sending
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = ::send, enabled = !sending && session?.allowed == true &&
                    modelSlug.isNotBlank() && draft.isNotBlank()) { Text("发送") }
            }
            Text("聊天暂存在本机；目前不会自动同步 ChatGPT 内置记忆。断网不会清空聊天。",
                style = MaterialTheme.typography.labelSmall)
        }
    }
}
