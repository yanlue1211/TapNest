package com.deskpet.gpt

import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.deskpet.overlay.OverlayState
import com.deskpet.ui.theme.DeskPetTheme
import kotlinx.coroutines.delay

/**
 * One-purpose, tiny system-managed input bar.
 *
 * On Send, synchronously transfer the prompt to the live pet service and close.
 * The service owns the request lifecycle and renders GPT's response through
 * the existing noninteractive pet speech bubble. Never await the model here:
 * finishing this Activity must not cancel or truncate a GPT request.
 *
 * A native dialog Activity (not an editable Compose system overlay) avoids the
 * previous HarmonyOS crash involving focusable TYPE_APPLICATION_OVERLAY views.
 */
class GptPetChatActivity : ComponentActivity() {
    companion object { const val EXTRA_PET_ID = "gpt_pet_id" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        )
        setFinishOnTouchOutside(true)
        setContent {
            DeskPetTheme(darkTheme = false) {
                TinyPetInput(intent.getStringExtra(EXTRA_PET_ID))
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val metrics = resources.displayMetrics
        val width = minOf(
            (metrics.widthPixels - 24f * metrics.density).toInt(),
            (390f * metrics.density).toInt()
        ).coerceAtLeast(1)
        // WRAP_CONTENT is essential: no chat history, header or empty white space.
        window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        window.attributes = window.attributes.apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (12f * metrics.density).toInt()
        }
    }

    @Composable
    private fun TinyPetInput(targetPetId: String?) {
        val focus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        var draft by remember { mutableStateOf("") }
        var error by remember { mutableStateOf("") }

        fun send() {
            val message = draft.trim()
            if (message.isEmpty()) return
            val id = targetPetId
            if (id.isNullOrBlank()) {
                error = "没找到正在运行的小金毛，请先打开桌宠。"
                return
            }
            // No network call inside this Activity. A failed handoff keeps the draft.
            val accepted = OverlayState.submitPetChat(id, message)
            if (accepted) {
                keyboard?.hide()
                finish()
            } else {
                error = "小金毛暂时没空或桌宠已关闭，消息没有发送。"
            }
        }

        LaunchedEffect(Unit) {
            delay(180)
            runCatching {
                focus.requestFocus()
                keyboard?.show()
            }
        }

        BackHandler { finish() }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFFB7DCC3), RoundedCornerShape(20.dp)),
            color = Color(0xFFFBFFFC),
            shape = RoundedCornerShape(20.dp),
            shadowElevation = 6.dp
        ) {
            Column(Modifier.padding(horizontal = 9.dp, vertical = 8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it.take(4000); error = "" },
                        modifier = Modifier.weight(1f).focusRequester(focus),
                        placeholder = { Text("跟小金毛说点什么…") },
                        shape = RoundedCornerShape(14.dp),
                        maxLines = 3,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() })
                    )
                    Button(
                        onClick = ::send,
                        enabled = draft.isNotBlank(),
                        modifier = Modifier.heightIn(min = 50.dp),
                        contentPadding = PaddingValues(horizontal = 13.dp)
                    ) { Text("发送") }
                }
                if (error.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}
