package com.deskpet.gpt

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deskpet.overlay.OverlayState
import com.deskpet.ui.theme.DeskPetTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A compact system-managed floating Activity, intentionally NOT a focusable
 * TYPE_APPLICATION_OVERLAY ComposeView. The proven normal Activity lifecycle
 * handles the soft keyboard, Insets and IME reliably on Android / HarmonyOS.
 * The desktop pet service stays alive underneath this translucent dialog.
 */
class GptPetChatActivity : ComponentActivity() {
    companion object { const val EXTRA_PET_ID = "gpt_pet_id" }

    private val credentials by lazy { GptCredentials(applicationContext) }
    private val api by lazy { GptChatApi(GptOAuth(credentials)) }
    private val prefs by lazy { GptPetPreferences(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContent { DeskPetTheme { PetChatContent(intent.getStringExtra(EXTRA_PET_ID)) } }
    }

    override fun onStart() {
        super.onStart()
        // Dialog width and height are bounded by the actual foreground window.
        // Keep a generous gap for the pet and the system keyboard.
        val d = resources.displayMetrics
        val maxWidth = (d.widthPixels - (20 * d.density).toInt()).coerceAtLeast(1)
        val width = minOf((390 * d.density).toInt(), maxWidth)
        val height = minOf((420 * d.density).toInt(), (d.heightPixels * .68f).toInt())
            .coerceAtLeast((250 * d.density).toInt())
            .coerceAtMost(d.heightPixels.coerceAtLeast(1))
        window.setLayout(width, height)
        window.attributes = window.attributes.apply {
            gravity = Gravity.CENTER
            dimAmount = .12f
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }

    @Composable
    private fun PetChatContent(petId: String?) {
        val scope = rememberCoroutineScope()
        val scroll = rememberLazyListState()
        val inputFocus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        var session by remember { mutableStateOf<GptSession?>(null) }
        var models by remember { mutableStateOf<List<GptModel>>(emptyList()) }
        var selected by remember { mutableStateOf("") }
        var messages by remember { mutableStateOf<List<GptMessage>>(emptyList()) }
        var draft by remember { mutableStateOf("") }
        var partial by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("正在连接 GPT…") }
        var style by remember { mutableStateOf("Chat") }
        var dropdown by remember { mutableStateOf(false) }
        val mint = Color(0xFFDEF4E5)

        fun openFullChat() {
            startActivity(Intent(this, GptCompanionActivity::class.java)
                .putExtra(GptCompanionActivity.EXTRA_PET_ID, petId))
            finish()
        }

        LaunchedEffect(petId) {
            style = withContext(Dispatchers.IO) { GptStyle.current(applicationContext, petId) }
            try {
                val authorized = withContext(Dispatchers.IO) { credentials.read() }
                session = authorized
                if (authorized == null || !authorized.allowed) {
                    status = "还没登录，请先授权 ChatGPT"
                    return@LaunchedEffect
                }
                messages = withContext(Dispatchers.IO) {
                    GptHistory(applicationContext, authorized.subject).load()
                }
                val options = withContext(Dispatchers.IO) { api.models() }
                models = options
                val saved = prefs.selectedModel(authorized.subject)
                selected = saved.takeIf { slug -> options.any { it.slug == slug } }
                    ?: options.firstOrNull()?.slug.orEmpty()
                status = if (options.isEmpty()) "当前账号没有可用模型" else "已连接 · " + style
            } catch (e: Exception) {
                error = "连接失败：" + GptFriendlyErrors.text(e)
                status = "请检查登录和网络"
            }
        }

        LaunchedEffect(messages.size, partial.length, busy) {
            val count = messages.size.coerceAtMost(20) + if (busy) 1 else 0
            if (count > 0) scroll.animateScrollToItem(count - 1)
        }

        // IME focus is owned by this Activity window, never by the foreground service.
        LaunchedEffect(session?.subject, selected) {
            if (session?.allowed == true && selected.isNotBlank()) {
                delay(260)
                runCatching {
                    inputFocus.requestFocus()
                    keyboard?.show()
                }
            }
        }

        fun send() {
            val text = draft.trim()
            val authorized = session ?: return
            val model = selected
            if (busy || !authorized.allowed || model.isBlank() || text.isBlank()) return
            if (!GptRequestGate.tryAcquire(authorized.subject)) {
                error = "另一处 GPT 正在回复，等这一轮结束再发～"
                return
            }
            busy = true
            error = ""
            partial = ""
            draft = ""
            scope.launch {
                try {
                    val mode = withContext(Dispatchers.IO) { GptStyle.current(applicationContext, petId) }
                    style = mode
                    // Read at send time so chat and physical interactions see the same history.
                    val snapshot = withContext(Dispatchers.IO) {
                        GptHistory(applicationContext, authorized.subject).load()
                    }
                    val reply = withContext(Dispatchers.IO) {
                        api.reply(model, GptStyle.instructions(mode), snapshot, text) { delta ->
                            runOnUiThread { partial = delta }
                        }
                    }
                    withContext(Dispatchers.IO) {
                        GptHistory(applicationContext, authorized.subject).append(text, reply)
                    }
                    messages = withContext(Dispatchers.IO) {
                        GptHistory(applicationContext, authorized.subject).load()
                    }
                    partial = ""
                    status = "已保存聊天 · " + mode
                    if (petId != null) OverlayState.postGptReply(petId, reply)
                } catch (e: Exception) {
                    // An incomplete stream is never saved or charged again automatically.
                    draft = if (draft.isBlank()) text else draft
                    partial = ""
                    error = GptFriendlyErrors.text(e)
                } finally {
                    busy = false
                    GptRequestGate.release(authorized.subject)
                }
            }
        }

        BackHandler { finish() }
        Surface(
            modifier = Modifier.fillMaxSize()
                .border(1.dp, Color(0xFFB9DEC6), RoundedCornerShape(22.dp)),
            color = Color(0xFFFBFFFC),
            shape = RoundedCornerShape(22.dp),
            shadowElevation = 10.dp
        ) {
            Column(
                Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🐾 GPT · " + style, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = ::openFullChat) { Text("记录") }
                    TextButton(onClick = { finish() }) { Text("关闭") }
                }
                if (session == null || session?.allowed != true) {
                    Text("第一次使用需要先在点点栖的聊天页登录。", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = ::openFullChat, modifier = Modifier.fillMaxWidth()) {
                        Text("打开 ChatGPT 登录")
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("模型：", style = MaterialTheme.typography.bodySmall)
                        Box(Modifier.weight(1f)) {
                            TextButton(
                                onClick = { dropdown = true }, enabled = !busy && models.isNotEmpty()
                            ) {
                                Text(models.firstOrNull { it.slug == selected }?.name
                                    ?: "加载中…", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(" ▾")
                            }
                            DropdownMenu(expanded = dropdown, onDismissRequest = { dropdown = false }) {
                                models.forEach { option ->
                                    DropdownMenuItem(text = { Text(option.name) }, onClick = {
                                        selected = option.slug
                                        prefs.saveModel(session!!.subject, option.slug)
                                        dropdown = false
                                    })
                                }
                            }
                        }
                    }
                }
                Text(status, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
                if (error.isNotBlank()) {
                    Text(error, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
                HorizontalDivider()
                val recent = messages.takeLast(20)
                LazyColumn(
                    state = scroll, modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(recent) { msg ->
                        Surface(
                            color = if (msg.role == "user") mint else Color(0xFFF1F5F2),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(Modifier.fillMaxWidth().padding(9.dp)) {
                                Text(if (msg.role == "user") "你" else "GPT",
                                    style = MaterialTheme.typography.labelSmall)
                                Text(msg.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    if (busy) item {
                        Surface(color = Color(0xFFF1F5F2), shape = RoundedCornerShape(12.dp)) {
                            Text(partial.ifBlank { "GPT 正在想…" },
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = it.take(4000) },
                        modifier = Modifier.weight(1f).focusRequester(inputFocus),
                        maxLines = 3, enabled = !busy, placeholder = { Text("戳戳小金毛，说点什么…") }
                    )
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = ::send,
                        enabled = !busy && session?.allowed == true &&
                            selected.isNotBlank() && draft.isNotBlank(),
                    ) { Text("发送") }
                }
            }
        }
    }
}
