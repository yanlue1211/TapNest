package com.deskpet.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Offline bitmap compositor for small custom rigs; all image paths are local to the pet. */
class LayeredPetPlayer private constructor(
    val rig: TapNestRig,
    val width: Int,
    val height: Int,
    private val pictures: Map<String, Bitmap>
) {
    private val frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(frame)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    @Volatile private var tappedAtMs = -10000L
    @Volatile private var expression = -1
    private val launchedAtMs = SystemClock.uptimeMillis()

    fun tap() {
        expression = (expression + 1) % 4
        tappedAtMs = SystemClock.uptimeMillis()
    }

    /** Reusable render target; AnimationController tags it with a fresh sequence. */
    @Synchronized fun render(): Bitmap {
        canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        val now = SystemClock.uptimeMillis()
        val elapsed = (now - tappedAtMs).coerceAtLeast(0)
        val touched = elapsed < 520
        val activeExpression = expression.coerceAtLeast(0)
        val shown = when (rig.headMode) {
            TapNestHeadMode.FULL_HEAD -> {
                val slot = "head_$activeExpression"
                if (pictures.containsKey(slot)) slot else "head_0"
            }
            TapNestHeadMode.BLANK_HEAD_WITH_FACE -> {
                val slot = "face_$activeExpression"
                if (pictures.containsKey(slot)) slot else "face_0"
            }
        }
        val sx = width.toFloat() / rig.canvasWidth.coerceAtLeast(1)
        val sy = height.toFloat() / rig.canvasHeight.coerceAtLeast(1)
        for (layer in rig.layers.sortedBy { it.zIndex }) {
            val id = layer.id
            val visible = when {
                id.startsWith("head_") && id != "head_blank" ->
                    rig.headMode == TapNestHeadMode.FULL_HEAD && id == shown
                id == "head_blank" -> rig.headMode == TapNestHeadMode.BLANK_HEAD_WITH_FACE
                id.startsWith("face_") -> rig.headMode == TapNestHeadMode.BLANK_HEAD_WITH_FACE && id == shown
                id.startsWith("wing_") -> id == if (touched && pictures.containsKey("wing_1")) "wing_1" else "wing_0"
                id.startsWith("bow_") -> id == if (touched && pictures.containsKey("bow_1")) "bow_1" else "bow_0"
                else -> true
            }
            if (!visible) continue
            val picture = pictures[id] ?: continue
            var pose = TapNestPose()
            if (id == "tail") {
                pose = TapNestMotion.sample(TapNestPresets.wag(id), id, now - launchedAtMs)
            } else if ((id.startsWith("head_") || id == "head_blank") && touched) {
                pose = TapNestMotion.sample(TapNestPresets.nod(id), id, elapsed)
            }
            // User-defined clips can supply another motion track independently of image assets.
            rig.clips.firstOrNull { clip -> clip.tracks.any { it.layerId == id } }
                ?.let { pose = TapNestMotion.sample(it, id, if (it.loop) now - launchedAtMs else elapsed) }
            val px = layer.pivotX.coerceIn(0f, 1f) * picture.width
            val py = layer.pivotY.coerceIn(0f, 1f) * picture.height
            val x = (layer.x + pose.x) * sx
            val y = (layer.y + pose.y) * sy
            val scale = layer.restScale.coerceIn(0.1f, 4f)
            canvas.save()
            canvas.translate(x + px * sx * scale, y + py * sy * scale)
            canvas.rotate(pose.angle)
            canvas.scale(scale * pose.scaleX * sx, scale * pose.scaleY * sy)
            paint.alpha = (pose.alpha.coerceIn(0f, 1f) * 255).toInt()
            canvas.drawBitmap(picture, -px, -py, paint)
            canvas.restore()
        }
        return frame
    }

    companion object {
        fun load(configFile: File): LayeredPetPlayer? = runCatching {
            if (!configFile.isFile || configFile.length() > 1024 * 1024) return null
            val rig = Json { ignoreUnknownKeys = true }.decodeFromString<TapNestRig>(configFile.readText())
            if (rig.canvasWidth !in 1..4096 || rig.canvasHeight !in 1..4096 || rig.layers.size > 48) return null
            val dir = configFile.parentFile ?: return null
            val entries = mutableMapOf<String, Bitmap>()
            var budget = 0L
            for (layer in rig.layers) {
                val name = layer.image
                if (name.isBlank()) continue
                if (!name.endsWith(".png", ignoreCase = true) ||
                    name.contains('/') || name.contains('\\') || name.contains("..")) return null
                val file = File(dir, name)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                    bounds.outWidth.toLong() * bounds.outHeight > 16_000_000L) return null
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
                val bitmap = BitmapFactory.decodeFile(file.absolutePath,
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }) ?: return null
                budget += bitmap.width.toLong() * bitmap.height
                if (budget > 9_000_000L) return null
                entries[layer.id] = bitmap
            }
            if (!entries.containsKey("body")) return null
            val dim = min(800f / max(rig.canvasWidth, rig.canvasHeight), 1f)
            LayeredPetPlayer(rig, max(32, (rig.canvasWidth * dim).toInt()),
                max(32, (rig.canvasHeight * dim).toInt()), entries)
        }.getOrNull()
    }
}
