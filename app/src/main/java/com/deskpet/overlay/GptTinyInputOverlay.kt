package com.deskpet.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * GPT EDITION ONLY: a single small native Android EditText + Send control.
 *
 * It is a real application overlay with no Activity, full-screen scrim, or
 * ComposeView. The user's current app remains visible behind the pet.
 * The service owns GPT requests after Send, so removing this view cannot
 * cancel an in-flight request or hide the pet's speech bubble.
 */
internal class GptTinyInputOverlay(
    private val context: Context,
    private val wm: WindowManager,
    private val onSend: (String) -> Boolean,
    private val onDismiss: () -> Unit
) {
    private val density = context.resources.displayMetrics.density
    private fun px(dp: Int): Int = (dp * density).roundToInt()
    private var root: FrameLayout? = null
    private var input: EditText? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var placedBounds: Rect = Rect()
    private var preferredTop: Int = 0
    private var closing = false

    private fun rounded(fill: Int, stroke: Int, radius: Int = 15) =
        GradientDrawable().apply {
            setColor(fill)
            setCornerRadius(px(radius).toFloat())
            setStroke(px(1).coerceAtLeast(1), stroke)
        }

    fun show(petX: Float, petY: Float, petW: Int, petH: Int, bounds: Rect): Boolean {
        dismiss(notify = false)
        closing = false
        val width = minOf(px(264), (bounds.width() - px(12)).coerceAtLeast(1))
        val height = px(58)
        if (bounds.width() < px(170) || bounds.height() < height + px(12)) return false

        val centerX = (petX + petW / 2f).roundToInt()
        val x = (centerX - width / 2).coerceIn(
            bounds.left + px(6),
            (bounds.right - width - px(6)).coerceAtLeast(bounds.left + px(6))
        )
        val above = (petY - height - px(12)).roundToInt()
        val below = (petY + petH + px(12)).roundToInt()
        // Float next to the pet, preferably above it; never make a huge bottom sheet.
        preferredTop = (if (above >= bounds.top + px(6)) above else below)
            .coerceIn(bounds.top + px(6),
                (bounds.bottom - height - px(6)).coerceAtLeast(bounds.top + px(6)))
        placedBounds = Rect(bounds)

        val outer = object : FrameLayout(context) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    dismiss()
                    return true
                }
                return super.dispatchTouchEvent(ev)
            }
        }.apply {
            background = rounded(Color.rgb(249, 255, 251), Color.rgb(165, 207, 179), 18)
            elevation = px(5).toFloat()
            setPadding(px(6), px(6), px(6), px(6))
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val textInput = EditText(context).apply {
            setSingleLine(true)
            textSize = 13.5f
            setTextColor(Color.rgb(39, 72, 55))
            setHintTextColor(Color.rgb(115, 145, 128))
            hint = "跟小金毛说点什么…"
            background = rounded(Color.WHITE, Color.rgb(103, 167, 126), 13)
            setPadding(px(10), 0, px(8), 0)
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val sendButton = TextView(context).apply {
            text = "发送"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(65, 120, 85), Color.rgb(65, 120, 85), 13)
            setOnClickListener { send(textInput) }
        }
        textInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action == KeyEvent.ACTION_DOWN)) {
                send(textInput)
                true
            } else false
        }
        row.addView(textInput, LinearLayout.LayoutParams(0, px(44), 1f))
        row.addView(sendButton, LinearLayout.LayoutParams(px(54), px(44)).apply {
            leftMargin = px(6)
        })
        outer.addView(row, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val params = WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            this.x = x
            this.y = preferredTop
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            title = "点点栖 GPT版 · 快捷输入"
        }

        return try {
            wm.addView(outer, params)
            root = outer
            input = textInput
            windowParams = params

            // Moving the keyboard must never turn the small overlay into a
            // full-screen window or place it completely behind the IME.
            outer.setOnApplyWindowInsetsListener { _, insets ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bottomIme = insets.getInsets(WindowInsets.Type.ime()).bottom
                    val maxBottom = (bounds.bottom - bottomIme).coerceAtLeast(bounds.top + height)
                    constrainToVisibleBottom(maxBottom)
                }
                insets
            }
            textInput.postDelayed({
                if (root === outer) {
                    textInput.requestFocus()
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(textInput, InputMethodManager.SHOW_IMPLICIT)
                }
            }, 120)
            true
        } catch (e: Exception) {
            runCatching { wm.removeView(outer) }
            root = null
            input = null
            windowParams = null
            Toast.makeText(context, "GPT 输入条打开失败：" + e.javaClass.simpleName,
                Toast.LENGTH_SHORT).show()
            false
        }
    }

    private fun constrainToVisibleBottom(bottom: Int) {
        val params = windowParams ?: return
        val windowHeight = params.height
        val newY = preferredTop.coerceAtMost(bottom - windowHeight - px(8))
            .coerceAtLeast(placedBounds.top + px(5))
        if (newY != params.y) {
            params.y = newY
            root?.let { runCatching { wm.updateViewLayout(it, params) } }
        }
    }

    private fun send(field: EditText) {
        if (closing) return
        val text = field.text.toString().trim()
        if (text.isEmpty()) return
        if (onSend(text)) {
            // Immediately remove keyboard and prompt, then the pet's own bubble
            // will deliver a brief thinking indicator and the actual GPT reply.
            dismiss()
        } else {
            Toast.makeText(context, "上一条消息还在回复，稍后再戳小金毛～",
                Toast.LENGTH_SHORT).show()
        }
    }

    fun dismiss(notify: Boolean = true) {
        if (closing) return
        closing = true
        val v = root
        root = null
        input = null
        windowParams = null
        if (v != null) {
            runCatching {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(v.windowToken, 0)
                wm.removeView(v)
            }
        }
        if (notify) onDismiss()
    }
}
