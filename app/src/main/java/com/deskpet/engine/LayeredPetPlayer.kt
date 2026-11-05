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
    private val pictures: Map<String, Bitmap>,
    private val sourceSizes: Map<String, Pair<Int, Int>>
) {
    // A published Bitmap must never be cleared while the UI is still drawing it.
    // Rotate through 3 preallocated surfaces to eliminate intermittent clear-frame flashes.
    private val frames = Array(3) { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
    private var frameIndex = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    @Volatile private var tappedAtMs = -10000L
    @Volatile private var pettingAtMs = -10000L
    @Volatile private var expression = 0
    private val launchedAtMs = SystemClock.uptimeMillis()

    fun tap() {
        expression = (expression % 3) + 1 // expression 0 is always neutral
        tappedAtMs = SystemClock.uptimeMillis()
        pettingAtMs = -10000L
    }

    fun petHead() {
        pettingAtMs = SystemClock.uptimeMillis()
        tappedAtMs = -10000L
    }

    /** Triple-buffered render target. The UI never observes a bitmap being cleared. */
    @Synchronized fun render(): Bitmap {
        frameIndex = (frameIndex + 1) % frames.size
        val frame = frames[frameIndex]
        val canvas = Canvas(frame)
        canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        val now = SystemClock.uptimeMillis()
        val elapsed = (now - tappedAtMs).coerceAtLeast(0)
        val petElapsed = (now - pettingAtMs).coerceAtLeast(0)
        val touched = elapsed < 520
        val petting = petElapsed < 1450
        val activeExpression = when {
            petting && rig.pettingExpression in 0..3 -> rig.pettingExpression
            elapsed < 1800 -> expression.coerceIn(0, 3)
            else -> 0
        }
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
        val nodes = rig.layers.associateBy { it.id }
        // Parent transformations are evaluated even when a parent image is hidden or optional.
        // Drawing uses a shared canvas coordinate system so cropped PNGs keep their natural size.
        fun poseFor(node: TapNestLayer): TapNestPose {
            val id = node.id
            var p = TapNestPose()
            if (id == "tail") {
                p = TapNestMotion.sample(TapNestPresets.wag(id), id, now - launchedAtMs)
            } else if (id.startsWith("head_") || id == "head_blank") {
                if (petting) p = TapNestMotion.sample(TapNestPresets.petHead(id), id, petElapsed)
                else if (touched) p = TapNestMotion.sample(TapNestPresets.nod(id), id, elapsed)
            }
            rig.clips.firstOrNull { c -> c.tracks.any { it.layerId == id } }
                ?.let { p = TapNestMotion.sample(it, id, if (it.loop) now - launchedAtMs else elapsed) }
            return p
        }
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
            val chain = mutableListOf<TapNestLayer>()
            val visited = mutableSetOf<String>()
            var node: TapNestLayer? = layer
            while (node != null && visited.add(node.id) && chain.size < 32) {
                chain.add(node)
                node = node.parentId?.let(nodes::get)
            }
            canvas.save()
            canvas.scale(sx, sy)
            for (part in chain.asReversed()) {
                val pose = poseFor(part)
                val original = sourceSizes[part.id]
                val pivotX = part.pivotX.coerceIn(0f, 1f) * (original?.first ?: rig.canvasWidth)
                val pivotY = part.pivotY.coerceIn(0f, 1f) * (original?.second ?: rig.canvasHeight)
                val scale = part.restScale.coerceIn(0.1f, 4f)
                canvas.translate(part.x + pose.x + pivotX * scale, part.y + pose.y + pivotY * scale)
                canvas.rotate(pose.angle)
                canvas.scale(scale * pose.scaleX, scale * pose.scaleY)
                canvas.translate(-pivotX, -pivotY)
            }
            paint.alpha = (poseFor(layer).alpha.coerceIn(0f, 1f) * 255).toInt()
            val source = sourceSizes[id] ?: Pair(picture.width, picture.height)
            canvas.drawBitmap(picture, null,
                RectF(0f, 0f, source.first.toFloat(), source.second.toFloat()), paint)
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
            val sourceSizes = mutableMapOf<String, Pair<Int, Int>>()
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
                // The character is rendered at <=320 px: 640px decoded layers are ample.
                // Capping decoded pixels also keeps multi-frame templates below memory budget.
                while (max(bounds.outWidth, bounds.outHeight) / sample > 640) sample *= 2
                val bitmap = BitmapFactory.decodeFile(file.absolutePath,
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }) ?: return null
                budget += bitmap.width.toLong() * bitmap.height
                if (budget > 9_000_000L) return null
                entries[layer.id] = bitmap
                sourceSizes[layer.id] = bounds.outWidth to bounds.outHeight
            }
            if (!entries.containsKey("body")) return null
            val dim = min(320f / max(rig.canvasWidth, rig.canvasHeight), 1f)
            LayeredPetPlayer(rig, max(32, (rig.canvasWidth * dim).toInt()),
                max(32, (rig.canvasHeight * dim).toInt()), entries, sourceSizes)
        }.getOrNull()
    }
}
