package com.deskpet.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.sin
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
    private val springChains = mutableMapOf<String, TapNestSpringChain>()
    @Volatile private var tappedAtMs = -10000L
    @Volatile private var pickedUp = false
    @Volatile private var pickedUpAtMs = 0L
    @Volatile private var expression = 0
    @Volatile private var actionAtMs = -10000L
    @Volatile private var lastAction: com.deskpet.data.CompanionAction? = null
    fun interact(action: com.deskpet.data.CompanionAction) {
        lastAction = action
        actionAtMs = SystemClock.uptimeMillis()
    }
    @Volatile private var affection = 70
    fun setAffection(value: Int) { affection = value.coerceIn(0, 100) }

    private fun naturalExpression(): Int {
        val desired = when {
            affection < 25 -> TapNestEmotion.ANGRY
            affection < 45 -> TapNestEmotion.SAD
            affection >= 90 -> TapNestEmotion.HAPPY
            else -> TapNestEmotion.NEUTRAL
        }
        return rig.expressionEmotions.entries
            .firstOrNull { it.value == desired && it.key in 0..3 }?.key
            ?: if (affection < 45) rig.expressionEmotions.entries.firstOrNull {
                it.value == TapNestEmotion.SAD && it.key in 0..3
            }?.key ?: 0 else 0
    }
    private val launchedAtMs = SystemClock.uptimeMillis()

    fun tap() {
        expression = if (rig.tapExpression in 0..3) rig.tapExpression else (expression % 3) + 1
        tappedAtMs = SystemClock.uptimeMillis()
    }

    fun setPickedUp(held: Boolean) {
        if (held && !pickedUp) pickedUpAtMs = SystemClock.uptimeMillis()
        pickedUp = held
    }

    /** Triple-buffered render target. The UI never observes a bitmap being cleared. */
    @Synchronized fun render(): Bitmap {
        frameIndex = (frameIndex + 1) % frames.size
        val frame = frames[frameIndex]
        val canvas = Canvas(frame)
        canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        val now = SystemClock.uptimeMillis()
        val held = pickedUp
        val heldMs = if (held) (now - pickedUpAtMs).coerceAtLeast(0) else 0L
        val sx = width.toFloat() / rig.canvasWidth.coerceAtLeast(1)
        val sy = height.toFloat() / rig.canvasHeight.coerceAtLeast(1)
        val pickupAngle = if (held) TapNestPickupMotion.angle(
            heldMs, 8f, 1.25f) else 0f // Fixed, lightweight pickup motion.
        val pickupPicture = if (held && rig.pickupArtworkEnabled)
            pictures["pickup_pose"] else null
        if (pickupPicture != null) {
            val source = sourceSizes["pickup_pose"]
                ?: (pickupPicture.width to pickupPicture.height)
            val scale = rig.layers.firstOrNull { it.id == "pickup_pose" }
                ?.restScale?.coerceIn(.3f, 3f) ?: 1f
            canvas.save()
            canvas.scale(sx, sy)
            canvas.rotate(pickupAngle, rig.canvasWidth * .5f, 0f)
            // Single full-frame art; only shared top-centre pivot and size control.
            canvas.scale(scale, scale, rig.canvasWidth * .5f, 0f)
            paint.alpha = 255
            canvas.drawBitmap(pickupPicture, null,
                RectF(0f, 0f, source.first.toFloat(), source.second.toFloat()), paint)
            canvas.restore()
            return frame
        }
        val elapsed = (now - tappedAtMs).coerceAtLeast(0)
        val actionElapsed = (now - actionAtMs).coerceAtLeast(0)
        val reacting = actionElapsed < 950
        val touched = !held && elapsed < 680
        val sceneElapsed = now - launchedAtMs
        // Scene-wide arbitration: if any scheduled facial/part animation is
        // active, suppress the spontaneous blink across all owners AND the
        // legacy rig.blinkExpression. Otherwise two asynchronous expression
        // systems can show mismatched partial face frames at the same time.
        val periodicScene = !held && !reacting && elapsed >= 1800 &&
            rig.layers.any { layer ->
                layer.frameOwner == null && pictures.containsKey(layer.id) &&
                    TapNestLayerActions.periodicElapsed(layer, sceneElapsed) != null
            }
        val idleBlinking = !periodicScene && !held && !reacting &&
            TapNestBlink.isBlinking(sceneElapsed)
        val activeExpression = when {
            held -> naturalExpression() // No additional expression/animation controls while lifted.
            reacting -> rig.expressionEmotions.entries.firstOrNull {
                it.value == TapNestEmotion.HAPPY && it.key in 0..3
            }?.key ?: naturalExpression()
            elapsed < 1800 -> expression.coerceIn(0, 3)
            rig.blinkExpression in 0..3 && idleBlinking -> rig.blinkExpression
            else -> naturalExpression()
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
        val nodes = rig.layers.associateBy { it.id }
        val selectedFrameByOwner = rig.layers.filter { it.frameOwner == null }
            .associate { owner ->
                owner.id to if (held) owner.id else TapNestLayerActions.currentFrame(
                    owner, rig.layers, sceneElapsed, elapsed, pictures.keys,
                    allowIdleBlink = !periodicScene
                )
            }

        // Parent transformations are evaluated even when a parent image is hidden or optional.
        // Drawing uses a shared canvas coordinate system so cropped PNGs keep their natural size.
        fun poseFor(node: TapNestLayer): TapNestPose {
            val id = node.id
            if (held) return TapNestPose() // All child parts freeze; only the whole pet rocks.
            var p = TapNestPose()
            if (id == "body" && reacting) {
                lastAction?.let {
                    p = TapNestMotion.sample(TapNestPresets.companionAction(it, id), id, actionElapsed)
                }
            } else if (node.frameOwner != null) {
                // Alternate art follows its owner transform, not a second nod.
            } else if (node.interactionConfigured) {
                val periodic = TapNestLayerActions.periodicElapsed(node, now - launchedAtMs)
                if (node.tapShake && touched) {
                    p = TapNestLayerActions.motionPose(node, elapsed)
                } else if (periodic != null && !held && !touched &&
                    elapsed >= 1800 && !reacting &&
                    node.periodicAction == TapNestPeriodicAction.SHAKE) {
                    p = TapNestLayerActions.motionPose(node, periodic)
                }
            } else if (id == "tail" && !node.idleSwing && !node.interactionConfigured) {
                p = TapNestMotion.sample(TapNestPresets.wag(id), id, now - launchedAtMs)
            } else if (id.startsWith("head_") || id == "head_blank") {
                if (touched) p = TapNestMotion.sample(TapNestPresets.nod(id), id, elapsed)
            }
            rig.clips.firstOrNull { c -> c.tracks.any { it.layerId == id } }
                ?.let { p = TapNestMotion.sample(it, id, if (it.loop) now - launchedAtMs else elapsed) }
            // A single-point or rigid limb rocks around its chosen pivot.
            // A multi-point limb is skinned by the mesh instead.
            if (node.idleSwing && node.bones.size < 2) {
                val wave = sin(((now - launchedAtMs) / 1000.0 *
                    node.idleSpeed.coerceIn(0.1f, 3f) * 2 * PI)).toFloat()
                val swingSign = TapNestSymmetry.idleSwingSign(node)
                p = p.copy(angle = p.angle + wave * node.idleAmplitude.coerceIn(0f, 40f) * swingSign)
            }
            return p
        }
        for (layer in rig.layers.sortedBy { it.zIndex }) {
            val id = layer.id
            val visible = when {
                id == "pickup_pose" -> false // Dedicated art is visible only while held.
                layer.frameOwner != null ->
                    selectedFrameByOwner[layer.frameOwner] == id &&
                    TapNestLayerActions.ownerVisible(layer.frameOwner, shown, rig.headMode)
                selectedFrameByOwner[id] != null && selectedFrameByOwner[id] != id -> false
                id.startsWith("head_") && id != "head_blank" ->
                    rig.headMode == TapNestHeadMode.FULL_HEAD && id == shown
                id == "head_blank" -> rig.headMode == TapNestHeadMode.BLANK_HEAD_WITH_FACE
                id.startsWith("face_") -> rig.headMode == TapNestHeadMode.BLANK_HEAD_WITH_FACE && id == shown
                id == "wing_0" || id == "wing_1" -> id == if (touched && pictures.containsKey("wing_1")) "wing_1" else "wing_0"
                id == "bow_0" || id == "bow_1" -> id == if (touched && pictures.containsKey("bow_1")) "bow_1" else "bow_0"
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
            if (held) canvas.rotate(pickupAngle,
                rig.canvasWidth * .5f, 0f)
            // Reflect once around the whole character, including bones and alternate frames.
            val reflectionOwner = layer.frameOwner?.let(nodes::get) ?: layer
            if (reflectionOwner.mirrorX) {
                canvas.translate(rig.canvasWidth.toFloat(), 0f)
                canvas.scale(-1f, 1f)
            }
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
            // Alternate frames inherit their default owner's bone skinning
            // as well as the parent transform; changing a frame must not freeze
            // the wing/tail while its idle spring continues to run.
            val skinLayer = layer.frameOwner?.let(nodes::get) ?: layer
            val spring = skinLayer.idleSwing && skinLayer.bones.size >= 2 &&
                skinLayer.swingMode == TapNestSwingMode.SPRING
            val mesh = if (spring) TapNestFlexibleMesh.SPRING_GRID else TapNestFlexibleMesh.GRID
            val jointAngles = if (spring && !held) {
                springChains.getOrPut(skinLayer.id) { TapNestSpringChain() }.step(skinLayer, now - launchedAtMs)
            } else null
            if (spring && skinLayer.shapePreservation >= 0.999f &&
                skinLayer.bones.all { it.swingDirection == TapNestBoneSwingDirection.AUTO } &&
                jointAngles != null && jointAngles.isNotEmpty()) {
                // True rigid mode: draw the intact bitmap instead of running it
                // through mesh cells, preserving heavy artwork outlines exactly.
                val root = skinLayer.bones.first()
                canvas.save()
                canvas.rotate(jointAngles[0], root.x.coerceIn(0f, 1f) * source.first,
                    root.y.coerceIn(0f, 1f) * source.second)
                canvas.drawBitmap(picture, null,
                    RectF(0f, 0f, source.first.toFloat(), source.second.toFloat()), paint)
                canvas.restore()
            } else {
                val flexible = if (held) null else TapNestFlexibleMesh.vertices(
                    skinLayer, source.first, source.second, now - launchedAtMs, mesh, jointAngles
                )
                if (flexible != null) {
                    canvas.drawBitmapMesh(picture, mesh, mesh, flexible, 0, null, 0, paint)
                } else {
                    canvas.drawBitmap(picture, null,
                        RectF(0f, 0f, source.first.toFloat(), source.second.toFloat()), paint)
                }
            }
            canvas.restore()
        }
        return frame
    }

    companion object {
        fun load(configFile: File): LayeredPetPlayer? = runCatching {
            if (!configFile.isFile || configFile.length() > 1024 * 1024) return null
            val legacy = Json { ignoreUnknownKeys = true }.decodeFromString<TapNestRig>(configFile.readText())
            val formsFile = File(configFile.parentFile, "forms.json")
            // An invalid forms file must not silently replace the player's current choice.
            val rig = if (formsFile.exists()) TapNestForms.parse(formsFile)?.let(TapNestForms::resolve)
                ?: return null else legacy
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
