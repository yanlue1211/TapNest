package com.deskpet.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.deskpet.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

internal enum class RigPreviewMode { POSITION, PIVOT, TEST }
private data class PreviewAsset(val bitmap: Bitmap, val width: Int, val height: Int)

/**
 * The art's natural (pre-downsampling) pixel dimensions define pivots/coordinates.
 * This is shared by touch hit-testing, anchor positioning and the preview renderer.
 */
private fun previewLocalPoint(
    point: Offset, viewport: IntSize, rig: TapNestRig, layer: TapNestLayer,
    assets: Map<String, PreviewAsset>
): Offset {
    val factor = min(
        viewport.width / rig.canvasWidth.coerceAtLeast(1).toFloat(),
        viewport.height / rig.canvasHeight.coerceAtLeast(1).toFloat()
    ).coerceAtLeast(0.001f)
    var x = (point.x - (viewport.width - rig.canvasWidth * factor) / 2f) / factor
    var y = (point.y - (viewport.height - rig.canvasHeight * factor) / 2f) / factor
    val nodes = rig.layers.associateBy { it.id }
    val ancestors = mutableListOf<TapNestLayer>()
    val visited = mutableSetOf<String>()
    var current: TapNestLayer? = layer
    while (current != null && visited.add(current.id) && ancestors.size < 32) {
        ancestors.add(current)
        current = current.parentId?.let(nodes::get)
    }
    // Invert the root-to-child transforms in that same order.
    for (joint in ancestors.asReversed()) {
        val art = assets[joint.id]
        val px = joint.pivotX.coerceIn(0f, 1f) * (art?.width ?: rig.canvasWidth)
        val py = joint.pivotY.coerceIn(0f, 1f) * (art?.height ?: rig.canvasHeight)
        val scale = joint.restScale.coerceIn(0.1f, 4f)
        x = (x - joint.x - px * scale) / scale + px
        y = (y - joint.y - py * scale) / scale + py
    }
    return Offset(x, y)
}

private fun visibleLayer(id: String, headMode: TapNestHeadMode, face: String, touched: Boolean,
    assets: Map<String, PreviewAsset>): Boolean = when {
    id.startsWith("head_") && id != "head_blank" ->
        headMode == TapNestHeadMode.FULL_HEAD && id == face
    id == "head_blank" -> headMode == TapNestHeadMode.BLANK_HEAD_WITH_FACE
    id.startsWith("face_") -> headMode == TapNestHeadMode.BLANK_HEAD_WITH_FACE && id == face
    id.startsWith("wing_") -> id == if (touched && assets.containsKey("wing_1")) "wing_1" else "wing_0"
    id.startsWith("bow_") -> id == if (touched && assets.containsKey("bow_1")) "bow_1" else "bow_0"
    else -> true
}

@Composable
internal fun LayeredRigPreview(
    rig: TapNestRig, uris: Map<String, Uri>, selected: String, mode: RigPreviewMode,
    onSelectLayer: (String) -> Unit,
    onDragLayer: (Float, Float) -> Unit,
    onSetPivot: (Float, Float) -> Unit
) {
    val context = LocalContext.current
    val assets by produceState<Map<String, PreviewAsset>>(emptyMap(), uris) {
        value = withContext(Dispatchers.IO) {
            // Bound the preview to a small decoded copy, never to the 4K source bitmap.
            var pixels = 0L
            uris.mapNotNull { (id, uri) ->
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, bounds)
                    }
                    if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096 ||
                        bounds.outWidth.toLong() * bounds.outHeight > 16_000_000L) return@runCatching null
                    var sample = 1
                    while (max(bounds.outWidth, bounds.outHeight) / sample > 640) sample *= 2
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null,
                            BitmapFactory.Options().apply { inSampleSize = sample })
                    }?.let {
                        pixels += it.width.toLong() * it.height
                        if (pixels <= 9_000_000L) id to PreviewAsset(it, bounds.outWidth, bounds.outHeight)
                        else null
                    }
                }.getOrNull()
            }.toMap()
        }
    }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var expression by remember { mutableIntStateOf(0) }
    var tappedAt by remember { mutableLongStateOf(-10000L) }
    var pettedAt by remember { mutableLongStateOf(-10000L) }
    var clock by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }

    LaunchedEffect(mode) {
        if (mode == RigPreviewMode.TEST) {
            while (isActive) {
                clock = SystemClock.uptimeMillis()
                delay(50L) // preview-only 20 FPS; it stops immediately on leaving TEST mode
            }
        } else {
            tappedAt = -10000L
            pettedAt = -10000L
            expression = 0
            clock = SystemClock.uptimeMillis()
        }
    }

    val newestRig by rememberUpdatedState(rig)
    val newestAssets by rememberUpdatedState(assets)
    val newestSelect by rememberUpdatedState(onSelectLayer)
    val newestMove by rememberUpdatedState(onDragLayer)
    val newestPivot by rememberUpdatedState(onSetPivot)

    val dragModifier = if (mode == RigPreviewMode.POSITION)
        Modifier.pointerInput(selected) {
            detectDragGestures { change, amount ->
                change.consume()
                val current = newestRig
                val factor = min(
                    viewport.width / current.canvasWidth.coerceAtLeast(1).toFloat(),
                    viewport.height / current.canvasHeight.coerceAtLeast(1).toFloat()
                ).coerceAtLeast(0.01f)
                newestMove(amount.x / factor, amount.y / factor)
            }
        } else Modifier

    Canvas(
        Modifier.fillMaxWidth().height(270.dp).clip(RoundedCornerShape(18.dp))
            .background(Color(0xFFE9F8EF))
            .onSizeChanged { viewport = it }
            .then(dragModifier)
            .pointerInput(mode, selected) {
                detectTapGestures(
                    onTap = { point ->
                        val current = newestRig
                        val pictures = newestAssets
                        when (mode) {
                            RigPreviewMode.POSITION -> {
                                // Pick the frontmost *visible* opaque layer, not just its transparent rectangle.
                                val top = current.layers.sortedByDescending { it.zIndex }.firstOrNull { layer ->
                                    val art = pictures[layer.id] ?: return@firstOrNull false
                                    val head = if (current.headMode == TapNestHeadMode.FULL_HEAD) "head_0" else "face_0"
                                    if (!visibleLayer(layer.id, current.headMode, head, false, pictures))
                                        return@firstOrNull false
                                    val local = previewLocalPoint(point, viewport, current, layer, pictures)
                                    val x = local.x / art.width * art.bitmap.width
                                    val y = local.y / art.height * art.bitmap.height
                                    if (x < 0 || y < 0 || x >= art.bitmap.width || y >= art.bitmap.height)
                                        return@firstOrNull false
                                    android.graphics.Color.alpha(art.bitmap.getPixel(x.toInt(), y.toInt())) > 32
                                }
                                top?.let { newestSelect(it.id) }
                            }
                            RigPreviewMode.PIVOT -> {
                                val layer = current.layers.firstOrNull { it.id == selected }
                                val art = pictures[selected]
                                if (layer != null && art != null) {
                                    val local = previewLocalPoint(point, viewport, current, layer, pictures)
                                    newestPivot((local.x / art.width).coerceIn(0f, 1f),
                                        (local.y / art.height).coerceIn(0f, 1f))
                                }
                            }
                            RigPreviewMode.TEST -> {
                                expression = (expression % 3) + 1
                                tappedAt = SystemClock.uptimeMillis()
                                pettedAt = -10000L
                            }
                        }
                    },
                    onLongPress = { point ->
                        if (mode == RigPreviewMode.TEST) {
                            val current = newestRig
                            val factor = min(
                                viewport.width / current.canvasWidth.coerceAtLeast(1).toFloat(),
                                viewport.height / current.canvasHeight.coerceAtLeast(1).toFloat()
                            ).coerceAtLeast(0.01f)
                            val canvasY = (point.y -
                                (viewport.height - current.canvasHeight * factor) / 2f) / factor
                            if (canvasY <= current.canvasHeight * 0.45f) {
                                pettedAt = SystemClock.uptimeMillis()
                                tappedAt = -10000L
                            }
                        }
                    }
                )
            }
    ) {
        val factor = min(
            size.width / rig.canvasWidth.coerceAtLeast(1),
            size.height / rig.canvasHeight.coerceAtLeast(1)
        )
        val native = drawContext.canvas.nativeCanvas
        val now = if (mode == RigPreviewMode.TEST) clock else -10000L
        val touchElapsed = now - tappedAt
        val petElapsed = now - pettedAt
        val touched = mode == RigPreviewMode.TEST && touchElapsed in 0..519L
        val petting = mode == RigPreviewMode.TEST && petElapsed in 0..1449L
        val expressionIndex = when {
            petting && rig.pettingExpression in 0..3 -> rig.pettingExpression
            mode == RigPreviewMode.TEST && touchElapsed in 0..1799L -> expression
            else -> 0
        }
        val facePrefix = if (rig.headMode == TapNestHeadMode.FULL_HEAD) "head_" else "face_"
        val chosen = "$"+"{facePrefix}$"+"{expressionIndex}"
        val face = if (assets.containsKey(chosen)) chosen else facePrefix + "0"
        val nodes = rig.layers.associateBy { it.id }
        fun poseFor(id: String): TapNestPose {
            val phase = if (mode == RigPreviewMode.TEST) clock else 0L
            var pose = TapNestPose()
            if (id == "tail" && mode == RigPreviewMode.TEST) {
                pose = TapNestMotion.sample(TapNestPresets.wag(id), id, phase)
            } else if (id.startsWith("head_") || id == "head_blank") {
                if (petting) pose = TapNestMotion.sample(TapNestPresets.petHead(id), id, petElapsed)
                else if (touched) pose = TapNestMotion.sample(TapNestPresets.nod(id), id, touchElapsed)
            }
            rig.clips.firstOrNull { it.tracks.any { track -> track.layerId == id } }?.let {
                if (mode == RigPreviewMode.TEST) pose = TapNestMotion.sample(
                    it, id, if (it.loop) phase else (if (petting) petElapsed else touchElapsed).coerceAtLeast(0L))
            }
            return pose
        }
        native.save()
        native.translate((size.width - rig.canvasWidth * factor) / 2f,
            (size.height - rig.canvasHeight * factor) / 2f)
        native.scale(factor, factor)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        for (layer in rig.layers.sortedBy { it.zIndex }) {
            if (!visibleLayer(layer.id, rig.headMode, face, touched, assets)) continue
            val art = assets[layer.id] ?: continue
            val ancestors = mutableListOf<TapNestLayer>()
            val visited = mutableSetOf<String>()
            var node: TapNestLayer? = layer
            while (node != null && visited.add(node.id) && ancestors.size < 32) {
                ancestors.add(node)
                node = node.parentId?.let(nodes::get)
            }
            native.save()
            for (joint in ancestors.asReversed()) {
                val parentArt = assets[joint.id]
                val pivotX = joint.pivotX.coerceIn(0f, 1f) * (parentArt?.width ?: rig.canvasWidth)
                val pivotY = joint.pivotY.coerceIn(0f, 1f) * (parentArt?.height ?: rig.canvasHeight)
                val sc = joint.restScale.coerceIn(0.1f, 4f)
                val motion = poseFor(joint.id)
                native.translate(joint.x + motion.x + pivotX * sc,
                    joint.y + motion.y + pivotY * sc)
                native.rotate(motion.angle)
                native.scale(sc * motion.scaleX, sc * motion.scaleY)
                native.translate(-pivotX, -pivotY)
            }
            paint.alpha = (poseFor(layer.id).alpha.coerceIn(0f, 1f) * 255).toInt()
            native.drawBitmap(art.bitmap, null,
                RectF(0f, 0f, art.width.toFloat(), art.height.toFloat()), paint)
            if (mode != RigPreviewMode.TEST && selected == layer.id) {
                val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.rgb(46, 158, 97)
                    style = Paint.Style.STROKE
                    strokeWidth = 2f / factor.coerceAtLeast(0.1f)
                }
                native.drawRect(0f, 0f, art.width.toFloat(), art.height.toFloat(), accent)
                accent.style = Paint.Style.FILL
                native.drawCircle(art.width * layer.pivotX, art.height * layer.pivotY,
                    5f / factor.coerceAtLeast(0.1f), accent)
            }
            native.restore()
        }
        native.restore()
    }
}
