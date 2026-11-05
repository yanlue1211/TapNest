package com.deskpet.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * Display-only overlay: NEVER receives taps, cannot steal focus or drag/scale gestures.
 * Kept separate from the pet canvas so the character's hitbox and saved size never change.
 * No images, TTS, APIs or network resources involved.
 */
internal class TapSpeechBubble(
    private val context: Context,
    private val wm: WindowManager
) {
    private var view: TextView? = null
    private val density get() = context.resources.displayMetrics.density
    private fun px(dp: Float) = (dp * density).toInt()

    fun show(message: String) {
        val safe = message.trim().take(60)
        if (safe.isEmpty()) { dismiss(); return }
        val bubble = view ?: TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.rgb(53, 75, 64))
            setLineSpacing(px(2f).toFloat(), 1f)
            setPadding(px(12f), px(9f), px(12f), px(9f))
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.rgb(250, 255, 251))
                setCornerRadius(px(16f).toFloat())
                setStroke(px(1f).coerceAtLeast(1), Color.rgb(179, 216, 196))
            }
            elevation = px(3f).toFloat()
        }
        bubble.text = safe
        // Android 12+ blocks touch-through of untrusted overlays above the opacity threshold.
        // Use a sub-0.8 window alpha so underlying apps remain usable when a bubble is visible.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            alpha = 0.75f
            gravity = Gravity.TOP or Gravity.LEFT
        }
        try {
            if (view == null) {
                wm.addView(bubble, params)
                view = bubble
            } else {
                wm.updateViewLayout(bubble, params)
            }
        } catch (_: Exception) {
            view = null
        }
    }

    fun follow(petX: Float, petY: Float, petWidth: Int, petHeight: Int, safe: Rect) {
        val v = view ?: return
        val maxBubbleWidth = min(px(245f), max(px(130f), safe.width() - px(16f)))
        v.maxWidth = maxBubbleWidth
        v.measure(
            View.MeasureSpec.makeMeasureSpec(maxBubbleWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val width = v.measuredWidth.coerceAtLeast(1)
        val height = v.measuredHeight.coerceAtLeast(1)
        val margin = px(8f)
        val xMax = (safe.right - margin - width).coerceAtLeast(safe.left + margin)
        val x = (petX + petWidth / 2f - width / 2f).toInt()
            .coerceIn(safe.left + margin, xMax)
        val above = petY.toInt() - height - px(10f)
        val below = (petY + petHeight + px(10f)).toInt()
        val intendedY = if (above >= safe.top) above else below
        val y = intendedY.coerceIn(safe.top, (safe.bottom - height).coerceAtLeast(safe.top))
        (v.layoutParams as? WindowManager.LayoutParams)?.let { p ->
            p.x = x
            p.y = y
            p.width = width
            p.height = height
            try { wm.updateViewLayout(v, p) } catch (_: Exception) { dismiss() }
        }
    }

    fun dismiss() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }
}
