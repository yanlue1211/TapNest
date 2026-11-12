package com.deskpet.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import com.deskpet.engine.AnimationController

@Composable
fun PetOverlayRenderer(
    anim: AnimationController,
    modifier: Modifier = Modifier
) {
    val presentation by anim.presentation.collectAsState()
    PetOverlayPresentation(presentation, modifier)
}

@Composable
internal fun PetOverlayPresentation(presentation: AnimationController.Presentation, modifier: Modifier = Modifier) {
    val frame = presentation.frame
    val w = presentation.width
    val h = presentation.height
    val d = LocalDensity.current
    // asImageBitmap() 每次调用都会新建一层包装对象；这个组件 5FPS 重组，
    // 不 remember 就是每秒 5 个没必要的分配（像素数据不复制，但包装对象和
    // 绘制描述符会进垃圾堆）。按帧序号缓存，位图本身由引擎双缓冲复用。
    val image = remember(frame) { frame?.bitmap?.asImageBitmap() }
    Box(modifier.size(with(d) { w.toDp() }, with(d) { (h + presentation.topInset).toDp() }), Alignment.TopStart) {
        Canvas(Modifier.fillMaxSize()) {
            image?.let {
                // A pickup/resume size change can precede the next 30 FPS frame
                // by one tick. Always preserve bitmap aspect ratio instead of
                // briefly stretching the old head into the taller window.
                val wPx = w.coerceAtLeast(1)
                val hPx = (wPx.toLong() * it.height / it.width.coerceAtLeast(1))
                    .toInt().coerceAtLeast(1)
                val sourceInset = (wPx.toLong() * (frame?.topInset ?: 0) / it.width.coerceAtLeast(1)).toInt()
                drawImage(it, dstOffset = IntOffset(0, presentation.topInset - sourceInset),
                    dstSize = IntSize(wPx, hPx))
            }
        }
    }
}
