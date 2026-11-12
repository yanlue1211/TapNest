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
import kotlin.math.PI
import kotlin.math.sin

internal enum class RigPreviewMode { POSITION, PIVOT, BONES, RANGE, TEST }
internal data class PreviewAsset(val bitmap: Bitmap, val width: Int, val height: Int)

/**
 * The art's natural (pre-downsampling) pixel dimensions define pivots/coordinates.
 * This is shared by touch hit-testing, anchor positioning and the preview renderer.
 */
internal fun previewLocalPoint(
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
        // Undo each inherited reflection only after undoing its ancestors.
        if (TapNestSymmetry.reflectsLocalCoordinates(joint)) x = rig.canvasWidth - x
        val art = assets[joint.id]
        val px = joint.pivotX.coerceIn(0f, 1f) * (art?.width ?: rig.canvasWidth)
        val py = joint.pivotY.coerceIn(0f, 1f) * (art?.height ?: rig.canvasHeight)
        val scale = joint.restScale.coerceIn(0.1f, 4f)
        x = (x - joint.x - px * scale) / scale + px
        y = (y - joint.y - py * scale) / scale + py
    }
    return Offset(x, y)
}

private fun visibleLayer(
    layer: TapNestLayer, headMode: TapNestHeadMode, face: String, touched: Boolean,
    assets: Map<String, PreviewAsset>, selectedFrames: Map<String, String>
): Boolean {
    val id = layer.id
    return when {
    layer.frameOwner != null ->
        selectedFrames[layer.frameOwner] == id &&
            TapNestLayerActions.ownerVisible(layer.frameOwner, face, headMode)
    selectedFrames[id] != null && selectedFrames[id] != id -> false
    id.startsWith("head_") && id != "head_blank" ->
        headMode == TapNestHeadMode.FULL_HEAD && id == face
    id == "head_blank" -> headMode == TapNestHeadMode.BLANK_HEAD_WITH_FACE
    id.startsWith("face_") -> headMode == TapNestHeadMode.BLANK_HEAD_WITH_FACE && id == face
    id == "wing_0" || id == "wing_1" -> id == if (touched && assets.containsKey("wing_1")) "wing_1" else "wing_0"
    id == "bow_0" || id == "bow_1" -> id == if (touched && assets.containsKey("bow_1")) "bow_1" else "bow_0"
    else -> true
    }
}

@Composable
internal fun LayeredRigPreview(
    rig: TapNestRig, uris: Map<String, Uri>, selected: String, mode: RigPreviewMode,
    selectedBone: Int, showOtherParts: Boolean = true,
    allowEditingDrag: Boolean = false,
    pickupPreview: Boolean = false,
    testRequest: Int = 0,
    testAction: String? = null,
    motionPlane: TapNestMotionPlane? = null,
    onSetMotionPlane: (TapNestMotionPlane) -> Unit = {},
    onSelectLayer: (String) -> Unit,
    onDragLayer: (Float, Float) -> Unit,
    onSetPivot: (Float, Float) -> Unit,
    onSetBone: (Int, Float, Float) -> Unit,
    onSelectBone: (Int) -> Unit
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
    val tapSequence = remember(rig.rapidTapAngryFrameId) { TapNestTapSequence() }
    var rapidTapAngry by remember(rig.rapidTapAngryFrameId) { mutableStateOf(false) }
    var tappedAt by remember { mutableLongStateOf(-10000L) }
    var clock by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    val springChains = remember { mutableMapOf<String, TapNestSpringChain>() }
    var previewStartedAt by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }

    LaunchedEffect(mode, pickupPreview) {
        if (mode == RigPreviewMode.TEST || pickupPreview) {
            springChains.clear()
            previewStartedAt = SystemClock.uptimeMillis()
            while (isActive) {
                clock = SystemClock.uptimeMillis()
                delay(33L) // 30 FPS for smooth spring preview; stops on leaving TEST mode
            }
        } else {
            tappedAt = -10000L
            expression = 0
            tapSequence.reset()
            rapidTapAngry = false
            clock = SystemClock.uptimeMillis()
        }
    }

    LaunchedEffect(testRequest) {
        if (testRequest > 0) {
            val now = SystemClock.uptimeMillis()
            if (testAction == "periodic") {
                val layer = rig.layers.firstOrNull { it.id == selected }
                previewStartedAt = now - ((layer?.periodicIntervalSeconds ?: 7f) * 1000).toLong()
                tappedAt = -10000L
            } else if (testAction == "tap") tappedAt = now
            clock = now
        }
    }

    val newestRig by rememberUpdatedState(rig)
    val newestAssets by rememberUpdatedState(assets)
    val newestSelect by rememberUpdatedState(onSelectLayer)
    val newestMove by rememberUpdatedState(onDragLayer)
    val newestPlane by rememberUpdatedState(motionPlane)
    val newestPlaneChange by rememberUpdatedState(onSetMotionPlane)
    val newestPivot by rememberUpdatedState(onSetPivot)
    val newestBone by rememberUpdatedState(onSetBone)
    val newestBoneSelect by rememberUpdatedState(onSelectBone)
    val newestBoneIndex by rememberUpdatedState(selectedBone)

    // Match LayeredPetPlayer exactly: extra transparent headroom must be
    // visible in pickup preview, even when an ear touches the original top.
    val pickupArtWidth = if (rig.pickupArtworkEnabled) assets["pickup_pose"]?.width else null
    val pickupArtScale = if (pickupArtWidth != null)
        rig.layers.firstOrNull { it.id == "pickup_pose" }?.restScale ?: 1f else 1f
    val insetUnits = maxOf(
        TapNestLayerActions.flightTopInset(rig),
        TapNestPickupCanvas.pickupTopClearance(
            rig.canvasWidth, rig.canvasHeight,
            pickupArtWidth ?: rig.canvasWidth, pickupArtScale))
    val insetDp = (270f * insetUnits / rig.canvasHeight.coerceAtLeast(1)).dp
    val density = androidx.compose.ui.platform.LocalDensity.current
    val insetPx = with(density) { insetDp.toPx() }
    val logicalViewport = IntSize(viewport.width, (viewport.height - insetPx).toInt().coerceAtLeast(1))

    val dragModifier = if (allowEditingDrag &&
        (mode == RigPreviewMode.POSITION || mode == RigPreviewMode.BONES))
        Modifier.pointerInput(selected, mode, insetPx, logicalViewport) {
            var dragBone = -1
            detectDragGestures(
                onDragStart = { point ->
                    if (mode == RigPreviewMode.BONES) {
                        val current = newestRig
                        val layer = current.layers.firstOrNull { it.id == selected }
                        val art = newestAssets[selected]
                        dragBone = -1
                        if (layer != null && art != null && layer.bones.isNotEmpty()) {
                            val local = previewLocalPoint(point.copy(y = point.y - insetPx), logicalViewport, current, layer, newestAssets)
                            val px = local.x / art.width
                            val py = local.y / art.height
                            val chosen = layer.bones.indices.minByOrNull { i ->
                                val dx = layer.bones[i].x - px
                                val dy = layer.bones[i].y - py
                                dx * dx + dy * dy
                            }
                            if (chosen != null) {
                                val hit = layer.bones[chosen]
                                val dx = hit.x - px
                                val dy = hit.y - py
                                if (dx * dx + dy * dy < 0.012f) {
                                    dragBone = chosen
                                    newestBoneSelect(chosen)
                                }
                            }
                        }
                    }
                },
                onDrag = { change, amount ->
                    change.consume()
                    val current = newestRig
                    if (mode == RigPreviewMode.BONES) {
                        val layer = current.layers.firstOrNull { it.id == selected }
                        val art = newestAssets[selected]
                        if (dragBone >= 0 && layer != null && art != null &&
                            dragBone < layer.bones.size) {
                            val local = previewLocalPoint(change.position.copy(y = change.position.y - insetPx), logicalViewport, current, layer, newestAssets)
                            newestBone(dragBone,
                                (local.x / art.width).coerceIn(0f, 1f),
                                (local.y / art.height).coerceIn(0f, 1f))
                        }
                    } else {
                        val layer = current.layers.firstOrNull { it.id == selected }
                        if (layer != null) {
                            val space = layer.copy(x = 0f, y = 0f, pivotX = 0f, pivotY = 0f, restScale = 1f)
                            val to = previewLocalPoint(change.position.copy(y = change.position.y - insetPx), logicalViewport, current, space, newestAssets)
                            val from = previewLocalPoint((change.position - amount).let { it.copy(y = it.y - insetPx) }, logicalViewport, current, space, newestAssets)
                            newestMove(to.x - from.x, to.y - from.y)
                        }
                    }
                }
            )
        } else Modifier

    // Keep the ORIGINAL preview scale and top anchor. A longer pickup PNG
    // increases the preview's height instead of shrinking/cropping the head.
    val pickupAsset = if (pickupPreview && rig.pickupArtworkEnabled)
        assets["pickup_pose"] else null
    val previewCanvasHeight = if (pickupAsset != null) {
        val sc = rig.layers.firstOrNull { it.id == "pickup_pose" }
            ?.restScale?.coerceIn(.3f, 3f) ?: 1f
        TapNestPickupCanvas.requiredHeight(rig.canvasWidth, rig.canvasHeight,
            pickupAsset.width, pickupAsset.height, sc)
    } else rig.canvasHeight
    val previewHeight = if (pickupAsset != null)
        (270f * previewCanvasHeight / rig.canvasHeight.coerceAtLeast(1))
            .coerceAtMost(2400f).dp
    else 270.dp

    Canvas(
        Modifier.fillMaxWidth().height(previewHeight + insetDp).clip(RoundedCornerShape(18.dp))
            .background(Color(0xFFE9F8EF))
            .onSizeChanged { viewport = it }
            .then(dragModifier)
            .pointerInput(mode, selected, showOtherParts, insetPx, logicalViewport) {
                detectTapGestures(
                    onTap = { point ->
                        val current = newestRig
                        val pictures = newestAssets
                        val defaultFrames = current.layers.filter { it.frameOwner == null }
                            .associate { it.id to it.id }
                        when (mode) {
                            RigPreviewMode.POSITION -> {
                                // Pick the frontmost *visible* opaque layer, not just its transparent rectangle.
                                val top = current.layers.sortedByDescending { it.zIndex }.firstOrNull { layer ->
                                    val art = pictures[layer.id] ?: return@firstOrNull false
                                    val head = if (current.headMode == TapNestHeadMode.FULL_HEAD) "head_0" else "face_0"
                                    if ((!showOtherParts && layer.id != selected &&
                                            layer.frameOwner != selected) ||
                                        !visibleLayer(layer, current.headMode, head, false,
                                            pictures, defaultFrames))
                                        return@firstOrNull false
                                    val local = previewLocalPoint(point.copy(y = point.y - insetPx), logicalViewport, current, layer, pictures)
                                    val x = local.x / art.width * art.bitmap.width
                                    val y = local.y / art.height * art.bitmap.height
                                    if (x < 0 || y < 0 || x >= art.bitmap.width || y >= art.bitmap.height)
                                        return@firstOrNull false
                                    android.graphics.Color.alpha(art.bitmap.getPixel(x.toInt(), y.toInt())) > 32
                                }
                                top?.let { newestSelect(it.id) }
                            }
                            RigPreviewMode.RANGE -> {
                                val layer = current.layers.firstOrNull { it.id == selected }
                                val art = pictures[selected]
                                val plane = newestPlane
                                if (layer != null && art != null && plane != null) {
                                    val local = previewLocalPoint(point.copy(y = point.y - insetPx), logicalViewport, current, layer, pictures)
                                    val degrees = Math.toDegrees(kotlin.math.atan2((local.y - layer.pivotY * art.height).toDouble(), (local.x - layer.pivotX * art.width).toDouble())).toFloat()
                                    if (plane.linear) newestPlaneChange(plane.copy(enabled = true, axisDegrees = degrees))
                                    else {
                                        val reference = plane.axisDegrees
                                        val relative = ((degrees - reference + 540f) % 360f) - 180f
                                        newestPlaneChange(if (relative < 0) plane.copy(enabled = true, negativeLimit = -relative) else plane.copy(enabled = true, positiveLimit = relative))
                                    }
                                }
                            }
                            RigPreviewMode.PIVOT -> {
                                val layer = current.layers.firstOrNull { it.id == selected }
                                val art = pictures[selected]
                                if (layer != null && art != null) {
                                    val local = previewLocalPoint(point.copy(y = point.y - insetPx), logicalViewport, current, layer, pictures)
                                    newestPivot((local.x / art.width).coerceIn(0f, 1f),
                                        (local.y / art.height).coerceIn(0f, 1f))
                                }
                            }
                            RigPreviewMode.BONES -> {
                                val layer = current.layers.firstOrNull { it.id == selected }
                                val art = pictures[selected]
                                if (layer != null && art != null && layer.bones.isNotEmpty()) {
                                    val local = previewLocalPoint(point.copy(y = point.y - insetPx), logicalViewport, current, layer, pictures)
                                    val nx = (local.x / art.width).coerceIn(0f, 1f)
                                    val ny = (local.y / art.height).coerceIn(0f, 1f)
                                    val nearest = layer.bones.indices.minByOrNull { i ->
                                        val dx = layer.bones[i].x - nx
                                        val dy = layer.bones[i].y - ny
                                        dx * dx + dy * dy
                                    } ?: 0
                                    // Click an existing handle to select; click elsewhere to move the selected handle.
                                    val distance = layer.bones[nearest].let {
                                        val dx = it.x - nx
                                        val dy = it.y - ny
                                        dx * dx + dy * dy
                                    }
                                    if (distance < 0.0025f) newestBoneSelect(nearest)
                                    else newestBone(newestBoneIndex.coerceIn(0, layer.bones.lastIndex), nx, ny)
                                }
                            }
                            RigPreviewMode.TEST -> {
                                expression = if (newestRig.tapExpression in 0..3) newestRig.tapExpression else (expression % 3) + 1
                                tappedAt = SystemClock.uptimeMillis()
                                rapidTapAngry = tapSequence.tap(tappedAt)
                                                }
                        }
                    },
                    onLongPress = { /* Petting removed: hold is for lifting/moving. */ }
                )
            }
    ) {
        val baselineHeightPx = 270.dp.toPx()
        val factor = min(
            size.width / rig.canvasWidth.coerceAtLeast(1),
            baselineHeightPx / rig.canvasHeight.coerceAtLeast(1)
        )
        val native = drawContext.canvas.nativeCanvas
        val now = if (mode == RigPreviewMode.TEST) clock else -10000L
        val touchElapsed = now - tappedAt
        val touched = mode == RigPreviewMode.TEST && touchElapsed in 0..679L
        val sceneElapsed = (clock - previewStartedAt).coerceAtLeast(0L)
        val periodicScene = mode == RigPreviewMode.TEST && touchElapsed >= 1800L &&
            rig.layers.any { layer ->
                layer.frameOwner == null && assets.containsKey(layer.id) &&
                    TapNestLayerActions.periodicElapsed(layer, rig.layers, sceneElapsed) != null
            }
        val idleBlinking = mode == RigPreviewMode.TEST && !periodicScene &&
            TapNestBlink.isBlinking(sceneElapsed)
        val baseExpressionIndex = when {
            mode == RigPreviewMode.TEST && touchElapsed in 0..1799L && rapidTapAngry ->
                TapNestLayerActions.legacyAngryExpression(rig, assets.keys) ?: expression
            mode == RigPreviewMode.TEST && touchElapsed in 0..1799L -> expression
            mode == RigPreviewMode.TEST && rig.blinkExpression in 0..3 &&
                idleBlinking -> rig.blinkExpression
            else -> 0
        }
        val nodes = rig.layers.associateBy { it.id }
        val selectedFrames = rig.layers.filter { it.frameOwner == null }.associate { owner ->
            owner.id to if (mode == RigPreviewMode.TEST)
                TapNestLayerActions.currentFrame(owner, rig.layers,
                    sceneElapsed, touchElapsed, assets.keys, allowIdleBlink = !periodicScene,
                    rapidTapAngryFrameId = rig.rapidTapAngryFrameId.takeIf { rapidTapAngry })
                else owner.id
        }
        val forcedAngry = mode == RigPreviewMode.TEST && touchElapsed in 0..1799L && rapidTapAngry &&
            TapNestLayerActions.legacyAngryExpression(rig, assets.keys) != null
        val expressionIndex = if (forcedAngry) baseExpressionIndex else
            TapNestLayerActions.legacyActionExpression(rig, selectedFrames, baseExpressionIndex) ?: baseExpressionIndex
        val facePrefix = if (rig.headMode == TapNestHeadMode.FULL_HEAD) "head_" else "face_"
        val chosen = facePrefix + expressionIndex
        val face = if (assets.containsKey(chosen)) chosen else facePrefix + "0"
        fun poseFor(id: String): TapNestPose {
            val phase = if (mode == RigPreviewMode.TEST) clock else 0L
            var pose = TapNestPose()
            val layer = nodes[id]
            if (layer?.frameOwner != null) {
                // Alternate frame follows its default image through the parent chain.
            } else if (mode == RigPreviewMode.TEST && layer?.interactionConfigured == true) {
                val periodic = TapNestLayerActions.periodicElapsed(
                    layer, rig.layers, sceneElapsed)
                if (layer.tapShake && touchElapsed >= 0L &&
                    touchElapsed < TapNestLayerActions.tapMotionDuration(layer)) {
                    pose = TapNestLayerActions.motionPose(
                        layer, touchElapsed, canvasHeight = rig.canvasHeight)
                } else if (periodic != null && !touched && touchElapsed >= 1800L &&
                    layer.periodicAction == TapNestPeriodicAction.SHAKE) {
                    pose = TapNestLayerActions.motionPose(
                        layer, periodic, periodic = true, canvasHeight = rig.canvasHeight)
                }
            } else if (id == "tail" && mode == RigPreviewMode.TEST &&
                layer?.idleSwing != true && layer?.interactionConfigured != true) {
                pose = TapNestMotion.sample(TapNestPresets.wag(id), id, phase)
            } else if (id.startsWith("head_") || id == "head_blank") {
                if (touched) pose = TapNestMotion.sample(TapNestPresets.nod(id), id, touchElapsed)
            }
            rig.clips.firstOrNull { it.tracks.any { track -> track.layerId == id } }?.let {
                if (mode == RigPreviewMode.TEST) pose = TapNestMotion.sample(
                    it, id, if (it.loop) phase else touchElapsed.coerceAtLeast(0L))
            }
            if (mode == RigPreviewMode.TEST && layer?.idleSwing == true && (((layer.idleBones ?: layer.bones).size < 2 && (layer.idleBoneChains ?: layer.boneChains).none { it.bones.size >= 2 }) || !layer.idleFlexibleEnabled)) {
                val swingSign = TapNestSymmetry.idleSwingSign(layer) * if (layer.reflectedMotion != layer.idleStartReversed) -1f else 1f
                val angle = TapNestSwingBounds.periodicDrive(
                    phase / 1000.0 * layer.idleSpeed.coerceIn(0.1f, 3f) * 2 * PI,
                    layer.idleAmplitude.coerceIn(0f, 85f), layer.idlePlane, swingSign)
                val motion = if (layer.idlePlane.enabled) TapNestLayerActions.planePose(angle, layer.idlePlane, layer.idleAmplitude.coerceIn(0f, 85f)) else TapNestPose(angle = angle)
                pose = pose.copy(x = pose.x + motion.x, y = pose.y + motion.y, angle = pose.angle + motion.angle)
            }
            return pose
        }
        native.save()
        native.translate((size.width - rig.canvasWidth * factor) / 2f,
            insetPx + (baselineHeightPx - rig.canvasHeight * factor) / 2f)
        native.scale(factor, factor)
        if (mode == RigPreviewMode.TEST && !pickupPreview && rig.idleBreathingEnabled &&
            touchElapsed >= 1800L) {
            val breath = TapNestBreathing.pose(sceneElapsed, rig.idleBreathingStrength)
            native.scale(breath.scaleX, breath.scaleY,
                rig.canvasWidth * .5f, rig.canvasHeight * .88f)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (pickupPreview) {
            native.rotate(TapNestPickupMotion.angle(
                (clock - previewStartedAt).coerceAtLeast(0L), 8f, 1.25f),
                rig.canvasWidth * .5f, 0f)
            val pickupArt = if (rig.pickupArtworkEnabled) assets["pickup_pose"] else null
            if (pickupArt != null) {
                val sc = rig.layers.firstOrNull { it.id == "pickup_pose" }
                    ?.restScale?.coerceIn(.3f, 3f) ?: 1f
                native.scale(sc, sc, rig.canvasWidth * .5f, 0f)
                native.drawBitmap(pickupArt.bitmap, null,
                    RectF(0f, 0f, pickupArt.width.toFloat(), pickupArt.height.toFloat()), paint)
                native.restore()
                return@Canvas
            }
        }
        for (layer in rig.layers.sortedBy { TapNestClothing.sortZ(it, nodes) }) {
            if (layer.id == "pickup_pose") continue
            if (!showOtherParts && layer.id != selected && layer.frameOwner != selected &&
                !(selected == "body" && TapNestClothing.isLinked(layer))) continue
            if (!visibleLayer(layer, rig.headMode, face, touched, assets, selectedFrames)) continue
            val art = assets[layer.id] ?: continue
            val drawSize = TapNestClothing.drawingSize(layer, art.width, art.height,
                assets["body"]?.let { it.width to it.height })
            val ancestors = mutableListOf<TapNestLayer>()
            val visited = mutableSetOf<String>()
            var node: TapNestLayer? = layer
            while (node != null && visited.add(node.id) && ancestors.size < 32) {
                ancestors.add(node)
                node = node.parentId?.let(nodes::get)
            }
            native.save()
            for (joint in ancestors.asReversed()) {
                if (TapNestSymmetry.reflectsLocalCoordinates(joint)) {
                    native.translate(rig.canvasWidth.toFloat(), 0f)
                    native.scale(-1f, 1f)
                }
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
            val bodyOpacity = if (TapNestClothing.isLinked(layer)) poseFor("body").alpha else 1f
            paint.alpha = (TapNestClothing.combinedAlpha(layer, poseFor(layer.id).alpha, bodyOpacity) * 255).toInt()
            val skinLayer = TapNestLayerActions.skinForAction(TapNestClothing.skinOwner(layer, nodes), rig.layers, (now - previewStartedAt).coerceAtLeast(0L), touchElapsed)
            val spring = mode == RigPreviewMode.TEST && skinLayer.idleSwing && skinLayer.idleFlexibleEnabled &&
                skinLayer.bones.size >= 2 && skinLayer.swingMode == TapNestSwingMode.SPRING
            val resolution = if (spring) TapNestFlexibleMesh.SPRING_GRID else TapNestFlexibleMesh.GRID
            val elapsed = (now - previewStartedAt).coerceAtLeast(0L)
            val idleAngles = if (spring) {
                springChains.getOrPut(skinLayer.id) { TapNestSpringChain() }.step(skinLayer, elapsed)
            } else null
            val jointAngles = if (mode == RigPreviewMode.TEST && !pickupPreview) TapNestLayerActions.jointAngles(
                skinLayer, rig.layers, elapsed, touchElapsed, idleAngles) else null
            val extraAngles = skinLayer.boneChains.mapIndexed { index, chain ->
                val child = chain.asLayer(skinLayer)
                val idle = if (mode == RigPreviewMode.TEST && !pickupPreview && child.idleSwing && child.idleFlexibleEnabled && child.swingMode == TapNestSwingMode.SPRING && child.bones.size >= 2)
                    springChains.getOrPut("${skinLayer.id}#${index}") { TapNestSpringChain() }.step(child, elapsed) else null
                if (mode == RigPreviewMode.TEST && !pickupPreview) TapNestLayerActions.jointAngles(child, rig.layers, elapsed, touchElapsed, idle) else null
            }
            val impulseActive = jointAngles !== idleAngles
            if (spring && skinLayer.boneChains.isEmpty() && skinLayer.boneRegion == TapNestBoneRegion() && !impulseActive && !skinLayer.idlePlane.enabled && skinLayer.shapePreservation >= 0.999f &&
                skinLayer.bones.all { it.swingDirection == TapNestBoneSwingDirection.AUTO } &&
                jointAngles != null && jointAngles.isNotEmpty()) {
                val root = skinLayer.bones.first()
                native.save()
                native.rotate(jointAngles[0] * if (skinLayer.reflectedMotion) -1f else 1f, root.x.coerceIn(0f, 1f) * drawSize.first,
                    root.y.coerceIn(0f, 1f) * drawSize.second)
                native.drawBitmap(art.bitmap, null,
                    RectF(0f, 0f, drawSize.first.toFloat(), drawSize.second.toFloat()), paint)
                native.restore()
            } else {
                val mesh = if (mode == RigPreviewMode.TEST) TapNestFlexibleMesh.vertices(
                    if (impulseActive) skinLayer.copy(shapePreservation = 0f) else skinLayer, drawSize.first, drawSize.second, elapsed, resolution, jointAngles, extraAngles,
                    // Identical motion motor/mesh path as the floating overlay.
                    angleDriveAmplitude = if (spring && !impulseActive &&
                        skinLayer.idlePlane.enabled && !skinLayer.idlePlane.linear) 0f
                    else TapNestLayerActions.activeFlexibleDriveAmplitude(
                        skinLayer, rig.layers, elapsed, touchElapsed)) else null
                if (mesh != null) {
                    native.drawBitmapMesh(art.bitmap, resolution, resolution, mesh, 0, null, 0, paint)
                } else native.drawBitmap(art.bitmap, null,
                    RectF(0f, 0f, drawSize.first.toFloat(), drawSize.second.toFloat()), paint)
            }
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
                if (mode == RigPreviewMode.RANGE && motionPlane?.enabled == true) {
                    val cx = art.width * layer.pivotX
                    val cy = art.height * layer.pivotY
                    val radius = min(art.width, art.height) * .24f
                    val reference = motionPlane.axisDegrees
                    val directions = if (motionPlane.linear) listOf(reference, reference + 180f) else
                        listOf(reference - motionPlane.negativeLimit, reference + motionPlane.positiveLimit)
                    accent.style = Paint.Style.STROKE
                    directions.forEach { degrees ->
                        val radians = Math.toRadians(degrees.toDouble())
                        native.drawLine(cx, cy, cx + radius * kotlin.math.cos(radians).toFloat(), cy + radius * kotlin.math.sin(radians).toFloat(), accent)
                    }
                    if (!motionPlane.linear) {
                        accent.style = Paint.Style.FILL
                        accent.alpha = 38
                        native.drawArc(RectF(cx - radius, cy - radius, cx + radius, cy + radius),
                            reference - motionPlane.negativeLimit, motionPlane.negativeLimit + motionPlane.positiveLimit, true, accent)
                        accent.alpha = 255
                    }
                }
                if (mode == RigPreviewMode.BONES && layer.boneRegion != TapNestBoneRegion()) {
                    accent.style = Paint.Style.STROKE
                    native.drawRect(layer.boneRegion.left * art.width, layer.boneRegion.top * art.height,
                        layer.boneRegion.right * art.width, layer.boneRegion.bottom * art.height, accent)
                }
                if (mode == RigPreviewMode.BONES && layer.bones.isNotEmpty()) {
                    val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.rgb(223, 139, 40)
                        strokeWidth = 2f / factor.coerceAtLeast(0.1f)
                        style = Paint.Style.STROKE
                    }
                    val path = android.graphics.Path()
                    layer.bones.forEachIndexed { i, bone ->
                        val px = bone.x * art.width
                        val py = bone.y * art.height
                        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    native.drawPath(path, track)
                    layer.bones.forEachIndexed { i, bone ->
                        accent.color = if (i == selectedBone)
                            android.graphics.Color.rgb(234, 116, 35)
                        else android.graphics.Color.rgb(58, 146, 114)
                        native.drawCircle(art.width * bone.x, art.height * bone.y,
                            (if (i == selectedBone) 7f else 5f) / factor.coerceAtLeast(0.1f), accent)
                    }
                }
            }
            native.restore()
        }
        native.restore()
    }
}
