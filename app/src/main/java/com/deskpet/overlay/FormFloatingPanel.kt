package com.deskpet.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.deskpet.engine.TapNestFormCollection
import com.deskpet.engine.TapNestForms

/** Small no-keyboard overlay for changing full outfits or just ears/tail/wings. */
internal class FormFloatingPanel(
    private val context: Context,
    private val wm: WindowManager,
    private val lifecycleOwner: LifecycleOwner,
    private val vmOwner: ViewModelStoreOwner,
    private val stateOwner: SavedStateRegistryOwner
) {
    private var view: ComposeView? = null
    private fun px(dp: Int) = (dp * context.resources.displayMetrics.density).toInt()

    fun show(forms: TapNestFormCollection, x: Float, y: Float, petW: Int, petH: Int,
             bounds: Rect, onForm: (String) -> Unit, onPart: (String, String?) -> Unit,
             onClose: () -> Unit) {
        dismiss()
        val root = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(vmOwner)
            setViewTreeSavedStateRegistryOwner(stateOwner)
            setContent {
                MaterialTheme(colorScheme = lightColorScheme(
                    primary = Color(0xFF46785B), surface = Color(0xFFFCFFFB),
                    primaryContainer = Color(0xFFDFF5E5)
                )) { FormMenu(forms, onForm, onPart, onClose) }
            }
        }
        val width = px(266).coerceAtMost(bounds.width() - px(20))
        val height = px(368).coerceAtMost(bounds.height() - px(28))
        val gap = px(10)
        val centerX = x + petW / 2f
        val desiredX = centerX - width / 2f
        // Prefer underneath, but never cover the pet's head near the bottom edge.
        val below = y + petH + gap
        val top = if (below + height <= bounds.bottom) below else y - height - gap
        val params = WindowManager.LayoutParams(
            width, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            this.x = desiredX.toInt().coerceIn(bounds.left + px(5),
                (bounds.right - width - px(5)).coerceAtLeast(bounds.left + px(5)))
            this.y = top.toInt().coerceIn(bounds.top + px(5),
                (bounds.bottom - height - px(5)).coerceAtLeast(bounds.top + px(5)))
        }
        try { wm.addView(root, params); view = root }
        catch (_: Exception) { root.disposeComposition() }
    }

    fun dismiss() {
        view?.let { root ->
            runCatching { wm.removeView(root) }
            root.disposeComposition()
        }
        view = null
    }
}

@Composable
private fun FormMenu(
    forms: TapNestFormCollection,
    onForm: (String) -> Unit,
    onPart: (String, String?) -> Unit,
    onClose: () -> Unit
) {
    val active = forms.forms.firstOrNull { it.id == forms.activeFormId } ?: forms.forms.first()
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(max = 368.dp)
            .border(1.dp, Color(0xFFB9DEC6), RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 12.dp, color = Color(0xFFFCFFFB)
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🌱 给小伙伴换装", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("✕") }
            }
            Text("完整形态", style = MaterialTheme.typography.labelMedium)
            for (form in forms.forms) {
                val chosen = active.id == form.id
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { onForm(form.id) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (chosen) Color(0xFFDFF5E5) else Color(0xFFF4F9F5)
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (chosen) "✓  " else "🐾 ", color = Color(0xFF355A42))
                        Text(form.name, modifier = Modifier.weight(1f))
                        if (chosen) Text("使用中", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            HorizontalDivider()
            Text("自由搭配", style = MaterialTheme.typography.labelMedium)
            for ((cat, label) in listOf("ear" to "🐱 耳朵", "tail" to "🎀 尾巴", "wing" to "🪽 翅膀")) {
                val selected = forms.overrides[cat] ?: active.id
                var expanded by remember(cat) { mutableStateOf(false) }
                val choiceName = when (selected) {
                    "none" -> "隐藏"
                    else -> forms.forms.firstOrNull { it.id == selected }?.name ?: active.name
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall)
                    Box {
                        OutlinedButton(onClick = { expanded = true }) {
                            Text(choiceName + " ▾", style = MaterialTheme.typography.labelMedium)
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            DropdownMenuItem(text = { Text("不显示") }, onClick = {
                                expanded = false; onPart(cat, "none")
                            })
                            for (form in forms.forms) {
                                // Source form's owner and action frames are swapped together.
                                if (form.rig.layers.none { TapNestForms.category(it) == cat }) continue
                                DropdownMenuItem(text = { Text(form.name) }, onClick = {
                                    expanded = false; onPart(cat, form.id)
                                })
                            }
                        }
                    }
                }
            }
            Text("长按 1～5 秒松手换装；原地按住超过 5 秒关闭桌宠。",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF5C7564))
        }
    }
}
