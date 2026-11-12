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
    private val sourceSizes: Map<String, Pair<Int, Int>>,
    val formDialogues: List<String>? = null
) {
    /**
     * Extended artwork uses a separate buffer height; NEVER squeeze it into
     * the ordinary frame, which would either crop the tail or shrink the head.
     * The extra pixels exist only below the original top-anchored canvas.
     */
    val pickupHeight: Int = if (rig.pickupArtworkEnabled && pictures.containsKey("pickup_pose")) {
        val source = sourceSizes["pickup_pose"] ?: (0 to 0)
        val scale = rig.layers.firstOrNull { it.id == "pickup_pose" }
            ?.restScale?.coerceIn(.3f, 3f) ?: 1f
        val canvasHeight = TapNestPickupCanvas.requiredHeight(
            rig.canvasWidth, rig.canvasHeight, source.first, source.second, scale)
        kotlin.math.ceil(canvasHeight.toDouble() * width / rig.canvasWidth.coerceAtLeast(1))
            .toInt().coerceIn(height, 2048)
    } else height

    val topInset: Int = kotlin.math.ceil(TapNestLayerActions.flightTopInset(rig).toDouble() *
        width / rig.canvasWidth.coerceAtLeast(1)).toInt()

    // A published Bitmap must never be cleared while the UI still draws it.
    // Two triple buffers avoid resizing/recycling frames during a drag.
    private val frames = Array(3) { Bitmap.createBitmap(width, height + topInset, Bitmap.Config.ARGB_8888) }
    private val pickupFrames = if (pickupHeight > height)
        Array(3) { Bitmap.createBitmap(width, pickupHeight + topInset, Bitmap.Config.ARGB_8888) }
    else frames
    /** Immutable first held frame, decoded/composited/upload-prepared during load only. */
    internal var preparedPickupFrame: Bitmap? = null
        private set
    private var frameIndex = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val nodes = rig.layers.associateBy { it.id }
    private val drawingLayers = rig.layers.sortedBy { it.zIndex }
    private val springChains = mutableMapOf<String, TapNestSpringChain>()
    @Volatile private var tappedAtMs = -10000L
    @Volatile private var pickedUp = false
    @Volatile private var pickedUpAtMs = 0L
    @Volatile private var expression = 0
    @Volatile internal var lifeInteraction: LifeInteractionAnimation? = null
    fun interact(action: com.deskpet.data.CompanionAction) {
        lifeInteraction = LifeInteractionAnimation(action, "", com.deskpet.data.InteractionAnimationSettings(), SystemClock.uptimeMillis())
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

    private val tapSequence = TapNestTapSequence()
    @Volatile private var rapidTapAngry = false
    fun recordPhysicalTap(): Boolean {
        val now = SystemClock.uptimeMillis()
        rapidTapAngry = tapSequence.tap(now)
        if (rapidTapAngry) tappedAtMs = now
        return rapidTapAngry && rig.rapidTapAngryFrameId in pictures.keys
    }

    fun tap(countForBurst: Boolean = true) {
        lifeInteraction = null
        if (countForBurst) recordPhysicalTap()
        expression = if (rig.tapExpression in 0..3) rig.tapExpression else (expression % 3) + 1
        tappedAtMs = SystemClock.uptimeMillis()
    }

    fun setPickedUp(held: Boolean) {
        if (held) {
            lifeInteraction = null
            tapSequence.reset()
            rapidTapAngry = false
        }
        if (held && !pickedUp) pickedUpAtMs = SystemClock.uptimeMillis()
        pickedUp = held
    }

    /** Warm both buffer sets off the UI thread, before the first long-press resize. */
    internal fun preparePickupFrames() {
        if (rig.pickupArtworkEnabled && pictures.containsKey("pickup_pose")) {
            setPickedUp(true)
            repeat(3) { render().prepareToDraw() }
            preparedPickupFrame = render().copy(Bitmap.Config.ARGB_8888, false)?.also { it.prepareToDraw() }
            setPickedUp(false)
        }
    }

    /** Triple-buffered render target. The UI never observes a bitmap being cleared. */
    @Synchronized fun render(): Bitmap {
        frameIndex = (frameIndex + 1) % frames.size
        val now = SystemClock.uptimeMillis()
        val held = pickedUp
        val heldMs = if (held) (now - pickedUpAtMs).coerceAtLeast(0) else 0L
        val pickupPicture = if (held && rig.pickupArtworkEnabled)
            pictures["pickup_pose"] else null
        val frame = if (pickupPicture != null) pickupFrames[frameIndex] else frames[frameIndex]
        val canvas = Canvas(frame)
        canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        canvas.translate(0f, topInset.toFloat())
        val sx = width.toFloat() / rig.canvasWidth.coerceAtLeast(1)
        val sy = height.toFloat() / rig.canvasHeight.coerceAtLeast(1)
        val pickupAngle = if (held) TapNestPickupMotion.angle(
            heldMs, 8f, 1.25f) else 0f // Fixed, lightweight pickup motion.
        if (pickupPicture != null) {
            val source = sourceSizes["pickup_pose"]
                ?: (pickupPicture.width to pickupPicture.height)
            val scale = rig.layers.firstOrNull { it.id == "pickup_pose" }
                ?.restScale?.coerceIn(.3f, 3f) ?: 1f
            canvas.save()
            // Use original horizontal pixel scale on BOTH axes: extending the
            // destination height must not stretch or shrink the puppy's head.
            canvas.scale(sx, sx)
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
        val interaction = lifeInteraction?.takeIf { it.active(now) }
        val reacting = interaction != null
        val touched = !held && !reacting && elapsed < 680
        val sceneElapsed = now - launchedAtMs
        // Scene-wide arbitration: if any scheduled facial/part animation is
        // active, suppress the spontaneous blink across all owners AND the
        // legacy rig.blinkExpression. Otherwise two asynchronous expression
        // systems can show mismatched partial face frames at the same time.
        val periodicScene = !held && !reacting && elapsed >= 1800 &&
            rig.layers.any { layer ->
                layer.frameOwner == null && pictures.containsKey(layer.id) &&
                    TapNestLayerActions.periodicElapsed(layer, rig.layers, sceneElapsed) != null
            }
        val idleBlinking = !periodicScene && !held && !reacting &&
            TapNestBlink.isBlinking(sceneElapsed)
        val selectedFrameByOwner = rig.layers.filter { it.frameOwner == null }
            .associate { owner ->
                owner.id to if (held || reacting) owner.id else TapNestLayerActions.currentFrame(
                    owner, rig.layers, sceneElapsed, elapsed, pictures.keys,
                    allowIdleBlink = !periodicScene,
                    rapidTapAngryFrameId = rig.rapidTapAngryFrameId.takeIf { rapidTapAngry }
                )
            }

        val baseExpression = when {
            held -> naturalExpression() // No additional expression/animation controls while lifted.
            reacting -> interaction?.expression(now)?.takeIf { frame ->
                pictures.containsKey((if (rig.headMode == TapNestHeadMode.FULL_HEAD) "head_" else "face_") + frame)
            } ?: naturalExpression()
            elapsed < 1800 && rapidTapAngry -> TapNestLayerActions.legacyAngryExpression(
                rig, pictures.keys) ?: expression.coerceIn(0, 3)
            elapsed < 1800 -> expression.coerceIn(0, 3)
            rig.blinkExpression in 0..3 && idleBlinking -> rig.blinkExpression
            else -> naturalExpression()
        }
        val forcedAngry = elapsed < 1800 && rapidTapAngry &&
            TapNestLayerActions.legacyAngryExpression(rig, pictures.keys) != null
        val activeExpression = if (!held && !reacting && !forcedAngry)
            TapNestLayerActions.legacyActionExpression(rig, selectedFrameByOwner, baseExpression) ?: baseExpression
            else baseExpression
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
        val breath = if (rig.idleBreathingEnabled && !held && !reacting &&
            elapsed >= 1800L) TapNestBreathing.pose(sceneElapsed, rig.idleBreathingStrength)
            else null

        // Parent transformations are evaluated even when a parent image is hidden or optional.
        // Drawing uses a shared canvas coordinate system so cropped PNGs keep their natural size.
        fun poseFor(node: TapNestLayer): TapNestPose {
            val id = node.id
            if (held) return TapNestPose() // All child parts freeze; only the whole pet rocks.
            var p = TapNestPose()
            if (reacting) {
                // Whole-pet interaction motion is composed once by the existing controller.
            } else if (node.frameOwner != null) {
                // Alternate art follows its owner transform, not a second nod.
            } else if (node.interactionConfigured) {
                val periodic = TapNestLayerActions.periodicElapsed(node, rig.layers, sceneElapsed)
                if (node.tapShake && !held && elapsed <
                    TapNestLayerActions.tapMotionDuration(node)) {
                    p = TapNestLayerActions.motionPose(
                        node, elapsed, canvasHeight = rig.canvasHeight)
                } else if (periodic != null && !held && !touched &&
                    elapsed >= 1800 && !reacting &&
                    node.periodicAction == TapNestPeriodicAction.SHAKE) {
                    p = TapNestLayerActions.motionPose(
                        node, periodic, periodic = true, canvasHeight = rig.canvasHeight)
                }
            } else if (id == "tail" && !node.idleSwing && !node.interactionConfigured) {
                p = TapNestMotion.sample(TapNestPresets.wag(id), id, now - launchedAtMs)
            } else if (id.startsWith("head_") || id == "head_blank") {
                if (touched) p = TapNestMotion.sample(TapNestPresets.nod(id), id, elapsed)
            }
            if (!reacting) rig.clips.firstOrNull { c -> c.tracks.any { it.layerId == id } }
                ?.let { p = TapNestMotion.sample(it, id, if (it.loop) now - launchedAtMs else elapsed) }
            // A single-point or rigid limb rocks around its chosen pivot.
            // A multi-point limb is skinned by the mesh instead.
            if (node.idleSwing && (((node.idleBones ?: node.bones).size < 2 && (node.idleBoneChains ?: node.boneChains).none { it.bones.size >= 2 }) || !node.idleFlexibleEnabled)) {
                val wave = sin(((now - launchedAtMs) / 1000.0 *
                    node.idleSpeed.coerceIn(0.1f, 3f) * 2 * PI)).toFloat()
                val swingSign = TapNestSymmetry.idleSwingSign(node)
                val amount = wave * node.idleAmplitude.coerceIn(0f, 85f) * swingSign * if (node.reflectedMotion != node.idleStartReversed) -1f else 1f
                val motion = if (node.idlePlane.enabled) TapNestLayerActions.planePose(amount, node.idlePlane) else TapNestPose(angle = amount)
                p = p.copy(x = p.x + motion.x, y = p.y + motion.y, angle = p.angle + motion.angle)
            }
            return p
        }
        for (layer in drawingLayers) {
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
            if (breath != null) canvas.scale(
                breath.scaleX, breath.scaleY, rig.canvasWidth * .5f, rig.canvasHeight * .88f)
            if (held) canvas.rotate(pickupAngle,
                rig.canvasWidth * .5f, 0f)
            // Parent transforms stay outside the child's local reflection (P * R * L).
            for (part in chain.asReversed()) {
                if (TapNestSymmetry.reflectsLocalCoordinates(part)) {
                    canvas.translate(rig.canvasWidth.toFloat(), 0f)
                    canvas.scale(-1f, 1f)
                }
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
            val skinLayer = TapNestLayerActions.skinForAction(layer.frameOwner?.let(nodes::get) ?: layer, rig.layers, now - launchedAtMs, elapsed)
            val spring = skinLayer.idleSwing && skinLayer.idleFlexibleEnabled && skinLayer.bones.size >= 2 &&
                skinLayer.swingMode == TapNestSwingMode.SPRING
            val mesh = if (spring) TapNestFlexibleMesh.SPRING_GRID else TapNestFlexibleMesh.GRID
            val idleAngles = if (spring && !held) {
                springChains.getOrPut(skinLayer.id) { TapNestSpringChain() }.step(skinLayer, now - launchedAtMs)
            } else null
            val jointAngles = if (!held && !reacting) TapNestLayerActions.jointAngles(
                skinLayer, rig.layers, now - launchedAtMs, elapsed, idleAngles) else null
            val extraAngles = skinLayer.boneChains.mapIndexed { index, chain ->
                val child = chain.asLayer(skinLayer)
                val idle = if (!held && !reacting && child.idleSwing && child.idleFlexibleEnabled && child.swingMode == TapNestSwingMode.SPRING && child.bones.size >= 2)
                    springChains.getOrPut("${skinLayer.id}#${index}") { TapNestSpringChain() }.step(child, now - launchedAtMs) else null
                if (!held && !reacting) TapNestLayerActions.jointAngles(child, rig.layers, now - launchedAtMs, elapsed, idle) else null
            }
            val impulseActive = jointAngles !== idleAngles
            if (spring && skinLayer.boneChains.isEmpty() && skinLayer.boneRegion == TapNestBoneRegion() && !impulseActive && !skinLayer.idlePlane.enabled && skinLayer.shapePreservation >= 0.999f &&
                skinLayer.bones.all { it.swingDirection == TapNestBoneSwingDirection.AUTO } &&
                jointAngles != null && jointAngles.isNotEmpty()) {
                // True rigid mode: draw the intact bitmap instead of running it
                // through mesh cells, preserving heavy artwork outlines exactly.
                val root = skinLayer.bones.first()
                canvas.save()
                canvas.rotate(jointAngles[0] * if (skinLayer.reflectedMotion) -1f else 1f, root.x.coerceIn(0f, 1f) * source.first,
                    root.y.coerceIn(0f, 1f) * source.second)
                canvas.drawBitmap(picture, null,
                    RectF(0f, 0f, source.first.toFloat(), source.second.toFloat()), paint)
                canvas.restore()
            } else {
                val flexible = if (held) null else TapNestFlexibleMesh.vertices(
                    if (impulseActive) skinLayer.copy(shapePreservation = 0f) else skinLayer, source.first, source.second, now - launchedAtMs, mesh, jointAngles, extraAngles
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
        private data class CachedImage(val bitmap: Bitmap, val size: Pair<Int, Int>)
        private val imageCache = object : android.util.LruCache<String, CachedImage>(12 * 1024 * 1024) {
            override fun sizeOf(key: String, value: CachedImage) = value.bitmap.byteCount
        }

        fun load(configFile: File): LayeredPetPlayer? = runCatching {
            if (!configFile.isFile || configFile.length() > 1024 * 1024) return null
            val legacy = Json { ignoreUnknownKeys = true }.decodeFromString<TapNestRig>(configFile.readText())
            val formsFile = File(configFile.parentFile, "forms.json")
            // An invalid forms file must not silently replace the player's current choice.
            val forms = if (formsFile.exists()) TapNestForms.parse(formsFile) ?: return null else null
            val rig = forms?.let(TapNestForms::resolve) ?: legacy
            val formDialogues = forms?.let { collection ->
                (collection.forms.firstOrNull { it.id == collection.activeFormId } ?: collection.forms.first()).tapDialogues
            }?.let(com.deskpet.data.model.TapDialogue::sanitize)
            if (rig.canvasWidth !in 1..4096 || rig.canvasHeight !in 1..4096 || rig.layers.size > TapNestFrames.MAX_LAYERS) return null
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
                val cacheKey = file.absolutePath + ":" + file.lastModified() + ":" + file.length()
                val cached = imageCache.get(cacheKey)
                val image = cached ?: run {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                        bounds.outWidth.toLong() * bounds.outHeight > 16_000_000L) return null
                    var sample = 1
                    while (max(bounds.outWidth, bounds.outHeight) / sample > 640) sample *= 2
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath,
                        BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return null
                    CachedImage(bitmap, bounds.outWidth to bounds.outHeight).also { imageCache.put(cacheKey, it) }
                }
                budget += image.bitmap.width.toLong() * image.bitmap.height
                if (budget > 9_000_000L) return null
                entries[layer.id] = image.bitmap
                sourceSizes[layer.id] = image.size
            }
            if (!entries.containsKey("body")) return null
            val dim = min(320f / max(rig.canvasWidth, rig.canvasHeight), 1f)
            LayeredPetPlayer(rig, max(32, (rig.canvasWidth * dim).toInt()),
                max(32, (rig.canvasHeight * dim).toInt()), entries, sourceSizes, formDialogues).also { it.preparePickupFrames() }
        }.getOrNull()
    }
}
