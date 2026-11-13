package com.deskpet.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.deskpet.gpt.GptModel
import kotlinx.coroutines.delay

/**
 * Small EDITABLE overlay directly attached to the floating pet service.
 * No activity switch during normal chatting; old wardrobe/animation layers remain untouched.
 */
internal class GptPetChatPanel(
    private val context: Context,
    private val wm: WindowManager,
    private val lifecycleOwner: LifecycleOwner,
    private val viewModelOwner: ViewModelStoreOwner,
    private val savedStateOwner: SavedStateRegistryOwner,
    private val onSend: (String, String) -> Boolean,
    private val onModel: (String) -> Unit,
    private val onAccount: () -> Unit,
    private val onHistory: () -> Unit,
    private val onClose: () -> Unit
) {
    private var view: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    var models by mutableStateOf<List<GptModel>>(emptyList())
        private set
    var model by mutableStateOf("")
        private set
    var status by mutableStateOf("正在检查 GPT 登录…")
        private set
    var error by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var lastUser by mutableStateOf("")
        private set
    var lastReply by mutableStateOf("")
        private set
    var partial by mutableStateOf("")
        private set
    var loginRequired by mutableStateOf(false)
        private set
    private var draft by mutableStateOf("")
    private var pendingDraft = ""
    private var style by mutableStateOf("Chat")
    fun setStyle(value: String) { style = value }

    fun show(petX: Float, petW: Int, bounds: Rect) {
        dismiss()
        // Keep the entire panel in the screen safe rectangle, including split-screen layouts.
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val width = minOf(dp(350), bounds.width() - dp(16)).coerceAtLeast(1)
        val height = minOf(dp(345), (bounds.height() * 0.47f).toInt())
            .coerceAtLeast(minOf(dp(155), bounds.height() - dp(12)))
            .coerceAtMost((bounds.height() - dp(12)).coerceAtLeast(1))
        val x = (petX + petW / 2f - width / 2f).toInt().coerceIn(
            bounds.left + dp(5), (bounds.right - width - dp(5)).coerceAtLeast(bounds.left + dp(5))
        )
        val y = bounds.top + dp(6)
        val root = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(viewModelOwner)
            setViewTreeSavedStateRegistryOwner(savedStateOwner)
            setContent {
                MaterialTheme(colorScheme = lightColorScheme(
                    primary = Color(0xFF45795E),
                    primaryContainer = Color(0xFFDFF5E5),
                    surface = Color(0xFFFBFFFC)
                )) { Content() }
            }
        }
        // Focusable and connected to the IME: NEVER FLAG_NOT_FOCUSABLE / ALT_FOCUSABLE_IM.
        // FLAG_NOT_TOUCH_MODAL preserves interaction with the app behind the small panel.
        val p = WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            this.x = x
            this.y = y
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            title = "点点栖 GPT 桌宠聊天"
        }
        try {
            wm.addView(root, p)
            view = root
            params = p
        } catch (e: Exception) {
            root.disposeComposition()
            Toast.makeText(context, "桌宠输入框打开失败：" + (e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    fun updateModels(choices: List<GptModel>, selected: String) {
        models = choices
        model = selected
        loginRequired = false
        status = if (choices.isEmpty()) "没有找到可用模型，请打开账号设置检查" else "已连接 ChatGPT"
        error = ""
    }
    fun requireLogin() {
        loginRequired = true
        models = emptyList()
        model = ""
        status = "尚未授权 ChatGPT，请先登录"
    }
    fun setLoading() {
        if (!busy) status = "正在读取账号的模型列表…"
    }
    fun updateHistory(user: String, reply: String) {
        lastUser = user
        lastReply = reply
    }
    fun updateBusy(sending: Boolean) {
        busy = sending
        if (sending) {
            status = "GPT 正在回复…"
            error = ""
            partial = ""
        } else if (models.isNotEmpty()) status = "已连接 ChatGPT"
    }
    fun updatePartial(text: String) { partial = text }
    fun complete(user: String, reply: String) {
        pendingDraft = ""
        lastUser = user
        lastReply = reply
        partial = ""
        busy = false
        status = "已保存 · 聊天与小金毛共享"
    }
    fun fail(message: String) {
        if (draft.isBlank() && pendingDraft.isNotBlank()) draft = pendingDraft
        pendingDraft = ""
        error = message
        partial = ""
        busy = false
        if (models.isNotEmpty()) status = "可以继续聊天"
    }
    fun dismiss() {
        view?.let { root ->
            runCatching { wm.removeView(root) }
            root.disposeComposition()
        }
        view = null
        params = null
    }

    @Composable
    private fun Content() {
        var menuOpen by remember { mutableStateOf(false) }
        val focus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current
        LaunchedEffect(Unit) {
            // First-frame focus on an overlay needs the WindowManager view to be attached.
            delay(240)
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
        Surface(
            modifier = Modifier.fillMaxSize()
                .border(1.dp, Color(0xFFB9DEC6), RoundedCornerShape(18.dp)),
            color = Color(0xFFFBFFFC), shape = RoundedCornerShape(18.dp),
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🐾 GPT · " + style + " 聊天", style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { keyboard?.hide(); focusManager.clearFocus(); onClose() }) {
                        Text("✕")
                    }
                }
                if (loginRequired) {
                    Text("需要先在点点栖里完成 ChatGPT 授权", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onAccount) { Text("打开账号登录") }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("模型：", style = MaterialTheme.typography.bodySmall)
                        Box(modifier = Modifier.weight(1f)) {
                            TextButton(onClick = { menuOpen = true }, enabled = models.isNotEmpty() && !busy) {
                                Text(models.firstOrNull { it.slug == model }?.name
                                    ?: "加载中…", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(" ▾")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                models.forEach { choice ->
                                    DropdownMenuItem(text = { Text(choice.name) }, onClick = {
                                        model = choice.slug
                                        menuOpen = false
                                        onModel(choice.slug)
                                    })
                                }
                            }
                        }
                        TextButton(onClick = onHistory) { Text("记录") }
                    }
                    if (lastReply.isNotBlank()) {
                        Text("上一轮：" + lastReply.takeLast(180),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (busy) Text(
                        if (partial.isBlank()) "正在想…" else partial.takeLast(200),
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFF326349)
                    )
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it.take(4000) },
                        label = { Text("和 GPT 说点什么…") },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        maxLines = 3,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (draft.isNotBlank() && model.isNotBlank() && !busy &&
                                onSend(draft.trim(), model)) {
                                pendingDraft = draft.trim()
                                draft = ""
                                keyboard?.hide()
                                focusManager.clearFocus()
                            }
                        })
                    )
                    Button(
                        onClick = {
                            if (onSend(draft.trim(), model)) {
                                pendingDraft = draft.trim()
                                draft = ""
                                keyboard?.hide()
                                focusManager.clearFocus()
                            }
                        },
                        enabled = draft.isNotBlank() && model.isNotBlank() && !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp)
                    ) { Text("发送给 GPT") }
                }
                Text(status, style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF45795E))
                if (error.isNotBlank()) Text(error, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
