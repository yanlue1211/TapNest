package com.deskpet.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.os.SystemClock
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Local PNG / GIF action artwork. Always ends after 60 seconds of uptime. */
internal class TemporaryActionVisual private constructor(
    private val png: Bitmap?,
    private val gif: Movie?,
    private val width: Int,
    private val height: Int
) {
    private val startedAt = SystemClock.uptimeMillis()
    val expiresAt: Long = startedAt + 60_000L
    private val surfaces = Array(3) { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
    private var cursor = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun expired(now: Long = SystemClock.uptimeMillis()) = now >= expiresAt

    @Synchronized fun frame(now: Long = SystemClock.uptimeMillis()): Bitmap {
        cursor = (cursor + 1) % surfaces.size
        val out = surfaces[cursor]
        val c = Canvas(out)
        c.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val sw = png?.width ?: gif?.width() ?: width
        val sh = png?.height ?: gif?.height() ?: height
        val scale = min(width.toFloat() / sw.coerceAtLeast(1), height.toFloat() / sh.coerceAtLeast(1))
        val w = sw * scale
        val h = sh * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        if (png != null) c.drawBitmap(png, null, RectF(left, top, left + w, top + h), paint)
        else if (gif != null) {
            val duration = gif.duration().takeIf { it > 0 } ?: 1000
            gif.setTime(((now - startedAt).coerceAtLeast(0) % duration).toInt())
            c.save()
            c.translate(left, top)
            c.scale(scale, scale)
            gif.draw(c, 0f, 0f, paint)
            c.restore()
        }
        return out
    }

    companion object {
        fun load(file: File?, width: Int, height: Int): TemporaryActionVisual? {
            if (file == null || !file.isFile || file.length() !in 1L..8_388_608L) return null
            val w = width.coerceIn(32, 512)
            val h = height.coerceIn(32, 512)
            return runCatching {
                when(file.extension.lowercase()) {
                    "png" -> {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.absolutePath, bounds)
                        if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096) return null
                        var sample = 1
                        while (max(bounds.outWidth, bounds.outHeight) / sample > 640) sample *= 2
                        val png = BitmapFactory.decodeFile(file.absolutePath,
                            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
                        TemporaryActionVisual(png, null, w, h)
                    }
                    "gif" -> {
                        @Suppress("DEPRECATION")
                        val movie = file.inputStream().use { Movie.decodeStream(it) } ?: return null
                        if (movie.width() !in 1..2048 || movie.height() !in 1..2048) return null
                        TemporaryActionVisual(null, movie, w, h)
                    }
                    else -> null
                }
            }.getOrNull()
        }
    }
}
