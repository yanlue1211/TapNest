package com.deskpet.engine

import android.graphics.*
import com.deskpet.data.*
import kotlin.math.*

/** One immutable session, sampled by the existing frame loop; no jobs or timers. */
internal class LifeInteractionAnimation(
    val action: CompanionAction,
    private val emoji: String,
    private val settings: InteractionAnimationSettings,
    private val startedAt: Long
) {
    private val middle = if (action == CompanionAction.FEED) 1250f else 1750f
    private val end = if (action == CompanionAction.BATH) 600f else 500f
    private val duration = 500f + middle + end
    private var surfaces: Array<Bitmap>? = null
    private var topFraction: Float? = null
    private var cursor = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    fun elapsed(now: Long) = (now - startedAt).coerceAtLeast(0) * settings.speed.factor
    fun active(now: Long) = settings.enabled && elapsed(now) < duration
    fun stage(now: Long): Int = when {
        elapsed(now) < 500f -> 0
        elapsed(now) < 500f + middle -> 1
        else -> 2
    }
    fun expression(now: Long): Int? = settings.expressions.getOrNull(stage(now))?.takeIf { it in 0..3 }

    fun render(source: Bitmap, now: Long, topInset: Int = 0): Bitmap {
        if (!active(now)) return source
        val existing = surfaces
        val buffers = if (existing != null && existing[0].width == source.width &&
            existing[0].height == source.height) existing else
            Array(3) { Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888) }
                .also { surfaces = it }
        cursor = (cursor + 1) % 3
        val out = buffers[cursor]
        val c = Canvas(out)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val w = source.width.toFloat()
        val padding = topInset.coerceIn(0, source.height - 1).toFloat()
        val h = source.height - padding
        c.translate(0f, padding)
        val t = elapsed(now); val phase = stage(now)
        val p = when (phase) { 0 -> t / 500f; 1 -> (t - 500f) / middle; else -> (t - 500f - middle) / end }.coerceIn(0f, 1f)
        // A short smooth inset reserves space even for edge-to-edge PNG art.
        // Both ends sample identity; no persistent position/scale is modified.
        val envelope = min(t / 180f, (duration - t) / 180f).coerceIn(0f, 1f)
        val top = topFraction ?: ((PetVisibleBounds.transparentTopFraction(source) * source.height - padding) /
            h).coerceIn(0f, 1f).also { topFraction = it }
        val inset = if (top >= .18f) .08f else .32f
        val fit = 1f - inset * envelope
        val wave = sin(p * PI).toFloat()
        val y = when (phase) {
            0 -> if (p > .55f) sin((p - .55f) / .45f * 2 * PI).toFloat() * h * .018f else 0f
            1 -> -abs(sin(p * 3 * PI)).toFloat() * h * .018f
            else -> -wave * h * .045f
        }
        val angle = when {
            phase == 1 -> sin(p * 6 * PI).toFloat() * 2f
            phase == 2 && action == CompanionAction.BATH -> sin(p * 6 * PI).toFloat() * 4f * (1f-p)
            else -> 0f
        }
        val squash = if (phase == 0 && p > .55f) wave * .025f else 0f
        c.save()
        c.translate(w * .5f, h * .5f + y)
        c.rotate(angle)
        c.scale(fit * (1f + squash), fit * (1f - squash))
        c.drawBitmap(source, -w * .5f, -h * .5f - padding, paint)
        c.restore()
        val size = min(w, h) * .14f
        fun effect(text: String, x: Float, yCenter: Float, scale: Float = 1f, alpha: Float = 1f) {
            if (scale <= 0f) return
            textPaint.textSize = size * scale
            textPaint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
            val fm = textPaint.fontMetrics
            // Clamp the measured glyph bounds rather than just its centre.
            val half = max(textPaint.measureText(text) / 2f, size * scale / 2f)
            val cx = x.coerceIn(half.coerceAtMost(w/2), (w-half).coerceAtLeast(w/2))
            val baseline = (yCenter - (fm.ascent + fm.descent) / 2f)
                .coerceIn(-fm.top, (h - fm.bottom).coerceAtLeast(-fm.top))
            c.drawText(text, cx, baseline, textPaint)
        }
        val headY = h * .5f + (top * h - h * .5f) * fit + y
        val item = if (action == CompanionAction.BATH) "💧" else emoji.ifBlank { if (action == CompanionAction.FEED) "🍪" else "🧸" }
        when (phase) {
            0 -> {
                val fall = (p / .65f).coerceIn(0f, 1f)
                val cy = size * .6f + (headY - size * 1.0f).coerceAtLeast(0f) * fall * fall - if (p > .65f) sin((p-.65f)/.35f*PI).toFloat()*h*.045f else 0f
                val shrink = if (p < .7f || action == CompanionAction.PLAY) 1f else ((1f-p)/.3f).coerceIn(0f,1f)
                effect(item, w*.5f, cy, shrink)
            }
            1 -> if (action == CompanionAction.PLAY) {
                effect(item, w*(.5f+.29f*sin(p*4*PI).toFloat()), headY + h*(.12f+.09f*abs(sin(p*6*PI)).toFloat()))
            } else if (action == CompanionAction.BATH) {
                repeat(3) { i ->
                    val drift = (p * 2f + i / 3f) % 1f
                    effect("🫧", w*(.22f+i*.28f), h*(.78f-.55f*drift), .7f, sin(drift*PI).toFloat())
                }
            }
            else -> {
                if (action == CompanionAction.PLAY) effect(item, w*.5f, headY + h*.12f, 1f-p, 1f-p)
                if (action == CompanionAction.BATH) repeat(3) { i ->
                    effect("💦", w*(.25f+i*.25f), h*(.3f+p*.18f), .7f, 1f-p)
                }
            }
        }
        if (action == CompanionAction.BATH && phase == 0 && p > .7f)
            effect("💦", w*.5f, headY, .9f, (1f-p)/.3f)
        return out
    }
}
