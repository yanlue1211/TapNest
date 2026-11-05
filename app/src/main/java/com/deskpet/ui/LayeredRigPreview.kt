package com.deskpet.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.consume
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.deskpet.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/** Canvas preview shares parent, pivot, scale and z-order logic with the floating player. */
@Composable
internal fun LayeredRigPreview(rig: TapNestRig, uris: Map<String, Uri>, selected: String,
    onDragLayer: (Float, Float) -> Unit
) {
    val ctx = LocalContext.current
    val bitmaps by produceState<Map<String, Bitmap>>(emptyMap(), uris) {
        value = withContext(Dispatchers.IO) {
            uris.mapNotNull { (id, uri) ->
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    ctx.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, bounds)
                    }
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                        bounds.outWidth.toLong() * bounds.outHeight > 16000000L) return@runCatching null
                    var sample = 1
                    while (max(bounds.outWidth, bounds.outHeight) / sample > 700) sample *= 2
                    ctx.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null,
                            BitmapFactory.Options().apply { inSampleSize = sample })
                    }?.let { id to it }
                }.getOrNull()
            }.toMap()
        }
    }
    var bounds by remember { mutableStateOf(IntSize.Zero) }
    val currentMove by rememberUpdatedState(onDragLayer)
    val currentRig by rememberUpdatedState(rig)
    Canvas(Modifier.fillMaxWidth().height(270.dp).clip(RoundedCornerShape(18.dp))
        .background(Color(0xFFE9F8EF)).onSizeChanged { bounds = it }
        .pointerInput(selected) {
            detectDragGestures { change, amount ->
                change.consume()
                val r = currentRig
                val factor = min(bounds.width / r.canvasWidth.coerceAtLeast(1).toFloat(),
                    bounds.height / r.canvasHeight.coerceAtLeast(1).toFloat()).coerceAtLeast(0.01f)
                currentMove(amount.x / factor, amount.y / factor)
            }
        }) {
        val factor = min(size.width / rig.canvasWidth.coerceAtLeast(1),
            size.height / rig.canvasHeight.coerceAtLeast(1))
        val c = drawContext.canvas.nativeCanvas
        val nodes = rig.layers.associateBy { it.id }
        c.save()
        c.translate((size.width - rig.canvasWidth * factor) / 2f,
            (size.height - rig.canvasHeight * factor) / 2f)
        c.scale(factor, factor)
        for (layer in rig.layers.sortedBy { it.zIndex }) {
            val visible = when {
                layer.id.startsWith("head_") && layer.id != "head_blank" ->
                    rig.headMode == TapNestHeadMode.FULL_HEAD && layer.id == "head_0"
                layer.id == "head_blank" -> rig.headMode == TapNestHeadMode.BLANK_HEAD_WITH_FACE
                layer.id.startsWith("face_") ->
                    rig.headMode == TapNestHeadMode.BLANK_HEAD_WITH_FACE && layer.id == "face_0"
                layer.id.startsWith("wing_") -> layer.id == "wing_0"
                layer.id.startsWith("bow_") -> layer.id == "bow_0"
                else -> true
            }
            val art = bitmaps[layer.id]
            if (!visible || art == null) continue
            val chain = mutableListOf<TapNestLayer>()
            val seen = mutableSetOf<String>()
            var node: TapNestLayer? = layer
            while (node != null && seen.add(node.id) && chain.size < 32) {
                chain.add(node); node = node.parentId?.let(nodes::get)
            }
            c.save()
            for (joint in chain.asReversed()) {
                val jointArt = bitmaps[joint.id]
                val px = joint.pivotX.coerceIn(0f, 1f) * (jointArt?.width ?: rig.canvasWidth)
                val py = joint.pivotY.coerceIn(0f, 1f) * (jointArt?.height ?: rig.canvasHeight)
                val scale = joint.restScale.coerceIn(0.1f, 4f)
                c.translate(joint.x + px * scale, joint.y + py * scale)
                c.scale(scale, scale)
                c.translate(-px, -py)
            }
            c.drawBitmap(art, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            if (selected == layer.id) {
                val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.rgb(63, 163, 99)
                    style = Paint.Style.STROKE
                    strokeWidth = 2f / factor.coerceAtLeast(0.1f)
                }
                c.drawRect(0f, 0f, art.width.toFloat(), art.height.toFloat(), border)
                border.style = Paint.Style.FILL
                c.drawCircle(art.width * layer.pivotX, art.height * layer.pivotY,
                    5f / factor.coerceAtLeast(0.1f), border)
            }
            c.restore()
        }
        c.restore()
    }
}
