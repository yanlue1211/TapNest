package com.deskpet.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Long hold (>1s, <5s without dragging) opens a first-level action choice.
 * No legacy costume/interaction menu is removed: they stay behind separate buttons.
 */
internal class PetQuickActionPanel(
    private val context: Context, private val wm: WindowManager,
    private val owner: LifecycleOwner,
    private val vmOwner: ViewModelStoreOwner,
    private val savedOwner: SavedStateRegistryOwner,
    private val onWardrobe: () -> Unit,
    private val onLife: () -> Unit,
    private val onChat: () -> Unit,
    private val onAiChange: (Boolean) -> Unit,
    private val onClose: () -> Unit
) {
    private var view: ComposeView? = null
    private var enabled by mutableStateOf(false)

    fun show(petX: Float, petY: Float, petW: Int, petH: Int,
             bounds: Rect, showWardrobe: Boolean, aiEnabled: Boolean) {
        dismiss()
        enabled = aiEnabled
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val width = minOf(dp(252), (bounds.width() - dp(16)).coerceAtLeast(1))
        val height = minOf(dp(258), (bounds.height() - dp(18)).coerceAtLeast(1))
        val x = (petX + petW / 2f - width / 2f).toInt().coerceIn(
            bounds.left + dp(4), (bounds.right - width - dp(4)).coerceAtLeast(bounds.left + dp(4)))
        val below = petY + petH + dp(10)
        val above = petY - height - dp(10)
        val y = (if (below + height < bounds.bottom) below else above).toInt().coerceIn(
            bounds.top + dp(4), (bounds.bottom - height - dp(4)).coerceAtLeast(bounds.top + dp(4)))
        val root = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(vmOwner)
            setViewTreeSavedStateRegistryOwner(savedOwner)
            setContent {
                MaterialTheme(colorScheme = lightColorScheme(
                    primary = Color(0xFF45795E), primaryContainer = Color(0xFFDFF5E5),
                    surface = Color(0xFFFBFFFC)
                )) {
                    Surface(
                        modifier = Modifier.fillMaxSize()
                            .border(1.dp, Color(0xFFB9DEC6), RoundedCornerShape(18.dp)),
                        shape = RoundedCornerShape(18.dp),
                        shadowElevation = 10.dp, color = Color(0xFFFBFFFC)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("🐾 想做什么？", style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f))
                                TextButton(onClick = onClose) { Text("✕") }
                            }
                            if (showWardrobe) {
                                OutlinedButton(onClick = onWardrobe, modifier = Modifier.fillMaxWidth()) {
                                    Text("🎀 换装 / 更换配件")
                                }
                            }
                            OutlinedButton(onClick = onLife, modifier = Modifier.fillMaxWidth()) {
                                Text("🍪 喂食 / 洗澡 / 玩耍")
                            }
                            Button(onClick = onChat, modifier = Modifier.fillMaxWidth()) {
                                Text("💬 和 GPT 聊天")
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("AI 回复戳戳与生活互动",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f))
                                Switch(checked = enabled, onCheckedChange = {
                                    enabled = it
                                    onAiChange(it)
                                })
                            }
                        }
                    }
                }
            }
        }
        val p = WindowManager.LayoutParams(
            width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            this.x = x; this.y = y
            title = "点点栖互动菜单"
        }
        try { wm.addView(root, p); view = root }
        catch (_: Exception) { root.disposeComposition() }
    }

    fun dismiss() {
        view?.let {
            runCatching { wm.removeView(it) }
            it.disposeComposition()
        }
        view = null
    }
}
