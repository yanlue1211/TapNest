package com.deskpet.engine

/**
 * Shared, deterministic scheduling and frame selection used by both the
 * editor test playback and the Android floating-overlay renderer.
 * All operations are local and keep old saved rigs on their legacy path.
 */
object TapNestLayerActions {
    private const val PULSE_MS = 700L

    /**
     * Hats require their whole flight to land, while ordinary shakes retain
     * their historical 680ms / 700ms touch and idle windows.
     */
    fun tapMotionDuration(layer: TapNestLayer): Long =
        if (layer.tapFlexible.enabled || layer.tapBasicConfigured) layer.tapFlexible.durationMs.coerceIn(200L, 3000L)
        else if (layer.tapMotion == TapNestPartMotion.HAT_POP) 960L else 680L

    private fun periodicPulseDuration(layer: TapNestLayer): Long =
        if (layer.periodicFlexible.enabled || layer.periodicBasicConfigured) layer.periodicFlexible.durationMs.coerceIn(200L, 3000L)
        else if (layer.periodicAction == TapNestPeriodicAction.SHAKE &&
            (layer.periodicMotion ?: layer.tapMotion) == TapNestPartMotion.HAT_POP) 960L
        else PULSE_MS

    fun periodicElapsed(layer: TapNestLayer, elapsedMs: Long): Long? {
        if (!layer.interactionConfigured || !layer.periodicEnabled || elapsedMs < 0L)
            return null
        val pulseMs = periodicPulseDuration(layer)
        val intervalMs = (layer.periodicIntervalSeconds.coerceIn(1f, 120f) * 1000)
            .toLong().coerceAtLeast(pulseMs + 1L)
        if (elapsedMs < intervalMs) return null
        val local = elapsedMs % intervalMs
        return local.takeIf { it < pulseMs }
    }

    /**
     * Use one schedule for both members of an ear or wing pair, including
     * manually imported left/right PNGs. A pair only syncs if both allow it;
     * disabling sync restores each layer's own interval and enabled state.
     * No timers, global mutable state, or changes to historical save files.
     */
    fun periodicElapsed(layer: TapNestLayer, allLayers: List<TapNestLayer>, elapsedMs: Long): Long? {
        val otherId = pairedId(layer.id) ?: return periodicElapsed(layer, elapsedMs)
        val partner = allLayers.firstOrNull { it.id == otherId && it.frameOwner == null }
            ?: return periodicElapsed(layer, elapsedMs)
        if (!layer.syncPairedPeriodic || !partner.syncPairedPeriodic)
            return periodicElapsed(layer, elapsedMs)
        val driver = listOf(layer, partner)
            .filter { it.interactionConfigured && it.periodicEnabled }
            .minByOrNull { if (it.id.endsWith("_left")) 0 else 1 }
            ?: return null
        if (!layer.interactionConfigured) return null
        if (elapsedMs < 0L) return null
        val interval = (driver.periodicIntervalSeconds.coerceIn(1f, 120f) * 1000).toLong()
            .coerceAtLeast(maxOf(periodicPulseDuration(layer), periodicPulseDuration(partner)) + 1L)
        if (elapsedMs < interval) return null
        return (elapsedMs % interval).takeIf { it < periodicPulseDuration(layer) }
    }

    fun planePose(amount: Float, plane: TapNestMotionPlane): TapNestPose {
        if (!plane.linear) return TapNestPose(angle = amount.coerceIn(-plane.negativeLimit.coerceIn(0f, 180f), plane.positiveLimit.coerceIn(0f, 180f)))
        val radians = Math.toRadians(plane.axisDegrees.toDouble())
        return TapNestPose(x = amount * kotlin.math.cos(radians).toFloat(), y = amount * kotlin.math.sin(radians).toFloat())
    }

    fun skinForAction(layer: TapNestLayer, layers: List<TapNestLayer>, sceneMs: Long, touchMs: Long): TapNestLayer {
        val points = when {
            layer.tapShake && layer.tapFlexible.enabled && touchMs in 0 until tapMotionDuration(layer) -> layer.tapBones
            layer.periodicEnabled && layer.periodicFlexible.enabled && touchMs >= 1800L &&
                periodicElapsed(layer, layers, sceneMs) != null -> layer.periodicBones
            else -> layer.idleBones
        } ?: layer.bones
        val plane = when {
            layer.tapShake && layer.tapFlexible.enabled && touchMs in 0 until tapMotionDuration(layer) -> layer.tapPlane
            layer.periodicEnabled && layer.periodicFlexible.enabled && touchMs >= 1800L && periodicElapsed(layer, layers, sceneMs) != null -> layer.periodicPlane
            else -> layer.idlePlane
        }
        val chains = when {
            layer.tapShake && layer.tapFlexible.enabled && touchMs in 0 until tapMotionDuration(layer) -> layer.tapBoneChains
            layer.periodicEnabled && layer.periodicFlexible.enabled && touchMs >= 1800L && periodicElapsed(layer, layers, sceneMs) != null -> layer.periodicBoneChains
            else -> layer.idleBoneChains
        } ?: layer.boneChains
        val region = when {
            layer.tapShake && layer.tapFlexible.enabled && touchMs in 0 until tapMotionDuration(layer) -> layer.tapBoneRegion
            layer.periodicEnabled && layer.periodicFlexible.enabled && touchMs >= 1800L && periodicElapsed(layer, layers, sceneMs) != null -> layer.periodicBoneRegion
            else -> layer.idleBoneRegion
        } ?: layer.boneRegion
        return if (points === layer.bones && plane == layer.idlePlane && chains === layer.boneChains && region == layer.boneRegion) layer else layer.copy(bones = points, idlePlane = plane, boneChains = chains, boneRegion = region)
    }

    /** Arc length, rather than point index, defines the quarter boundaries. */
    fun scopeWeight(layer: TapNestLayer, segment: Int, scope: TapNestMotionScope): Float {
        val lengths = layer.bones.zipWithNext { a, b ->
            kotlin.math.hypot((b.x - a.x) * 1.0, (b.y - a.y) * 1.0).toFloat()
        }
        val total = lengths.sum()
        if (total < .00001f) return 0f
        val u = lengths.take(segment + 1).sum() / total
        val start = when (scope) {
            TapNestMotionScope.UPPER -> .25f
            TapNestMotionScope.MIDDLE -> .5f
            TapNestMotionScope.LOWER -> .75f
            else -> 0f
        }
        return ((u - start) / (1f - start)).coerceIn(0f, 1f)
    }

    /** Shared skinning input. A tap replaces the timed impulse, never accumulates it. */
    fun jointAngles(layer: TapNestLayer, layers: List<TapNestLayer>, sceneMs: Long,
                    touchMs: Long, idle: FloatArray?): FloatArray? {
        val source = idle ?: if (layer.idleSwing && layer.idleFlexibleEnabled && layer.idleScope != TapNestMotionScope.WHOLE)
            TapNestFlexibleMesh.segmentAngles(layer, sceneMs) else null
        var previous = 0f
        var idleAbsolute = 0f
        val base = if (source != null && layer.idleScope != TapNestMotionScope.WHOLE)
            FloatArray(source.size) { i ->
                idleAbsolute += source[i]
                val u = if (source.size == 1) 1f else i.toFloat() / (source.size - 1)
                val weight = when (layer.idleScope) {
                    TapNestMotionScope.TIP -> u * u * u
                    TapNestMotionScope.UPPER, TapNestMotionScope.MIDDLE, TapNestMotionScope.LOWER -> scopeWeight(layer, i, layer.idleScope)
                    else -> u
                }
                val current = idleAbsolute * weight
                (current - previous).also { previous = current }
            } else source
        if (!layer.interactionConfigured || layer.bones.size !in 2..8) return base
        val timed = periodicElapsed(layer, layers, sceneMs)
        val action: TapNestImpulse
        val age: Long
        when {
            layer.tapShake && layer.tapFlexible.enabled && touchMs in 0 until tapMotionDuration(layer) -> {
                action = layer.tapFlexible; age = touchMs
            }
            layer.periodicEnabled && layer.periodicAction == TapNestPeriodicAction.SHAKE &&
                layer.periodicFlexible.enabled && timed != null && touchMs >= 1800L -> {
                action = layer.periodicFlexible; age = timed
            }
            else -> return base
        }
        val startSign = if (if (layer.tapShake && layer.tapFlexible.enabled && touchMs in 0 until tapMotionDuration(layer))
            layer.tapStartReversed else layer.periodicStartReversed) -1f else 1f
        val count = layer.bones.size - 1
        val duration = action.durationMs.coerceIn(200L, 3000L).toDouble()
        val progress = (age / duration).coerceIn(0.0, 1.0)
        // Zero displacement and velocity at both ends; bounded and restartable.
        val envelope = kotlin.math.sin(kotlin.math.PI * progress).let { it * it } *
            kotlin.math.exp(-action.damping.coerceIn(.1f, 1.3f) * progress * 2.0)
        val cycles = action.count.coerceIn(1, 6) * action.speed.coerceIn(.5f, 6f) / 3.0
        val absolute = FloatArray(count) { i ->
            val u = (i + 1f) / count
            val weight = when (action.scope) {
                TapNestMotionScope.UPPER, TapNestMotionScope.MIDDLE, TapNestMotionScope.LOWER -> scopeWeight(layer, i, action.scope)
                TapNestMotionScope.WHOLE -> u
                TapNestMotionScope.DISTAL -> ((u - .25f) / .75f).coerceIn(0f, 1f).let { it * it }
                TapNestMotionScope.TIP -> if (count == 1) 1f else (i.toFloat() / (count - 1)).let { it * it * it }
            }
            val lag = i * action.softness.coerceIn(0f, 1f) * .15
            (startSign * action.amplitude.coerceIn(0f, 55f) * weight * envelope *
                kotlin.math.sin(2 * kotlin.math.PI * cycles * progress - lag)).toFloat()
        }
        var idleTotal = 0f
        var previousTotal = 0f
        return FloatArray(count) { i ->
            idleTotal += base?.getOrNull(i) ?: 0f
            val total = (idleTotal + absolute[i]).coerceIn(-95f, 95f)
            (total - previousTotal).also { previousTotal = total }
        }
    }

    /** Extra transparent pixels ABOVE the authored canvas; authored coordinates never move. */
    fun flightTopInset(rig: TapNestRig): Int {
        val flight = rig.layers.filter { it.frameOwner == null && it.interactionConfigured &&
            ((it.tapShake && !it.tapFlexible.enabled && it.tapMotion == TapNestPartMotion.HAT_POP) ||
             (it.periodicEnabled && !it.periodicFlexible.enabled &&
              it.periodicAction == TapNestPeriodicAction.SHAKE &&
              (it.periodicMotion ?: it.tapMotion) == TapNestPartMotion.HAT_POP)) }
            .maxOfOrNull { layer ->
                // Account for cumulative parent scaling; cap malformed/cyclic rigs.
                val visited = mutableSetOf<String>()
                var parent = layer.parentId
                var scale = 1f
                while (parent != null && visited.add(parent) && visited.size <= 32) {
                    val node = rig.layers.firstOrNull { it.id == parent } ?: break
                    scale *= node.restScale.coerceIn(.1f, 4f)
                    parent = node.parentId
                }
                val amplitudeScale = maxOf(
                    if (layer.tapBasicConfigured) layer.tapFlexible.amplitude.coerceIn(0f, 55f) / 18f else 1f,
                    if (layer.periodicBasicConfigured) layer.periodicFlexible.amplitude.coerceIn(0f, 55f) / 18f else 1f)
                (layer.hatFlightHeight.coerceIn(.08f, .4f) * amplitudeScale + .12f) *
                    rig.canvasHeight * scale.coerceAtMost(4f)
            } ?: return 0
        return kotlin.math.ceil(flight.toDouble()).toInt().coerceIn(0, rig.canvasHeight * 3)
    }

    fun frameLabel(owner: TapNestLayer, layers: List<TapNestLayer>, index: Int): String {
        val frame = if (index == 0) owner else layers.firstOrNull {
            it.frameOwner == owner.id && it.frameIndex == index
        }
        return frame?.displayName?.takeIf { it.isNotBlank() }
            ?: if (index == 0) "默认帧" else "动作帧 $index"
    }

    fun hasPeriodicPartner(layer: TapNestLayer, layers: List<TapNestLayer>): Boolean =
        pairedId(layer.id)?.let { id ->
            layers.any { it.id == id && it.frameOwner == null }
        } ?: false

    private fun pairedId(id: String): String? {
        if (!id.startsWith("wing_") && !id.startsWith("ear_")) return null
        return when {
            id.endsWith("_left") -> id.removeSuffix("_left") + "_right"
            id.endsWith("_right") -> id.removeSuffix("_right") + "_left"
            else -> null
        }
    }

    fun currentFrame(
        owner: TapNestLayer, allLayers: List<TapNestLayer>,
        elapsedMs: Long, touchAgeMs: Long, availableIds: Set<String>,
        allowIdleBlink: Boolean = true,
        rapidTapAngryFrameId: String? = null
    ): String {
        if (touchAgeMs in 0..1799L && rapidTapAngryFrameId in availableIds) {
            allLayers.firstOrNull { it.id == rapidTapAngryFrameId && it.frameOwner == owner.id }
                ?.let { return it.id }
        }
        if (!owner.interactionConfigured) return owner.id
        val requested = when {
            owner.tapSwitchFrame && touchAgeMs in 0 until
                (if (owner.tapShake) maxOf(1000L, tapMotionDuration(owner)) else 1000L) -> owner.tapFrame
            // A periodic expression has higher priority than idle blinking,
            // otherwise the scene mixes two different action frames.
            (owner.periodicAction == TapNestPeriodicAction.SWITCH_FRAME || owner.periodicSwitchFrame) &&
                periodicElapsed(owner, allLayers, elapsedMs) != null &&
                touchAgeMs >= 1800L -> owner.periodicFrame
            owner.idleBlinkEnabled && allowIdleBlink && touchAgeMs > 1400L &&
                TapNestBlink.isBlinking(elapsedMs) -> owner.blinkFrame
            else -> 0
        }
        if (requested <= 0) return owner.id
        return allLayers.firstOrNull {
            it.frameOwner == owner.id && it.frameIndex == requested && it.id in availableIds
        }?.id ?: owner.id
    }

    /**
     * An alternate frame inherits the visibility of its owning head/face.
     * Without this check, head_0_f1 (blink/periodic frame) can be drawn
     * ON TOP of an active head_2 expression, producing a mixed face.
     */
    fun legacyAngryExpression(rig: TapNestRig, availableIds: Set<String>): Int? {
        val id = rig.rapidTapAngryFrameId ?: return null
        val prefix = if (rig.headMode == TapNestHeadMode.FULL_HEAD) "head_" else "face_"
        val target = rig.layers.firstOrNull { it.id == id } ?: return null
        val ownerId = target.frameOwner ?: id
        return ownerId.removePrefix(prefix).toIntOrNull()?.takeIf {
            ownerId.startsWith(prefix) && it in 0..3 && id in availableIds &&
                rig.layers.any { layer -> layer.id == ownerId && layer.frameOwner == null }
        }
    }

    fun ownerVisible(ownerId: String, visibleFace: String, headMode: TapNestHeadMode): Boolean =
        when {
            ownerId == "head_blank" -> headMode == TapNestHeadMode.BLANK_HEAD_WITH_FACE
            ownerId.startsWith("head_") ->
                headMode == TapNestHeadMode.FULL_HEAD && ownerId == visibleFace
            ownerId.startsWith("face_") ->
                headMode == TapNestHeadMode.BLANK_HEAD_WITH_FACE && ownerId == visibleFace
            else -> true
        }

    fun motionPose(layer: TapNestLayer, motionAgeMs: Long,
                   periodic: Boolean = false, canvasHeight: Int = 512): TapNestPose {
        if ((if (periodic) layer.periodicFlexible else layer.tapFlexible).enabled &&
            ((if (periodic) layer.periodicBones ?: layer.bones else layer.tapBones ?: layer.bones).size >= 2 ||
                (if (periodic) layer.periodicBoneChains ?: layer.boneChains else layer.tapBoneChains ?: layer.boneChains).any { it.bones.size >= 2 })) return TapNestPose()
        val selected = if (periodic) layer.periodicMotion ?: layer.tapMotion else layer.tapMotion
        val startReversed = (if (periodic) layer.periodicStartReversed else layer.tapStartReversed) && selected != TapNestPartMotion.HAT_POP
        // A mirrored clone already reverses the canvas. Use the source-side
        // clip sign to prevent reflecting left/right flap directions twice.
        val motionId = if (layer.mirrorX || layer.reflectedMotion) layer.mirroredFromId ?: layer.id else layer.id
        val clip = when (selected) {
            TapNestPartMotion.NOD -> TapNestPresets.nod(motionId)
            TapNestPartMotion.BOUNCE -> TapNestPresets.bounce(motionId)
            TapNestPartMotion.FLAP -> TapNestPresets.flap(motionId)
            TapNestPartMotion.TWITCH -> TapNestPresets.twitch(motionId)
            TapNestPartMotion.HAT_POP -> TapNestPresets.hatPop(
                motionId, canvasHeight.toFloat(), layer.hatFlightHeight)
        }
        val configured = if (periodic) layer.periodicBasicConfigured else layer.tapBasicConfigured
        if (!configured)
            return TapNestMotion.sample(clip, motionId, motionAgeMs).let {
                val horizontalSign = if (layer.reflectedMotion != startReversed) -1f else 1f
                it.copy(x = if (it.x == 0f) 0f else it.x * horizontalSign,
                    y = if (it.y == 0f) 0f else it.y * if (startReversed) -1f else 1f,
                    angle = if (it.angle == 0f) 0f else it.angle * horizontalSign)
            }
        val action = if (periodic) layer.periodicFlexible else layer.tapFlexible
        val duration = action.durationMs.coerceIn(200L, 3000L)
        if (motionAgeMs !in 0 until duration) return TapNestPose()
        val progress = motionAgeMs.toDouble() / duration
        if (selected == TapNestPartMotion.HAT_POP) {
            val cycle = (progress * action.count.coerceIn(1, 6)) % 1.0
            val curved = Math.pow(cycle, 3.0 / action.speed.coerceIn(.5f, 6f))
            val pose = TapNestMotion.sample(clip, motionId, (curved * 960L).toLong())
            return pose.copy(y = pose.y * (action.amplitude.coerceIn(0f, 55f) / 18f))
        }
        val envelope = kotlin.math.sin(kotlin.math.PI * progress).let { it * it }
        val wave = (kotlin.math.sin(2 * kotlin.math.PI * action.count.coerceIn(1, 6) *
            action.speed.coerceIn(.5f, 6f) / 3.0 * progress) * envelope).toFloat()
        val amount = wave * action.amplitude.coerceIn(0f, 55f) * if (layer.reflectedMotion != startReversed) -1f else 1f
        val plane = if (periodic) layer.periodicPlane else layer.tapPlane
        if (plane.enabled) return planePose(amount, plane)
        return when (selected) {
            TapNestPartMotion.BOUNCE -> TapNestPose(y = if (layer.reflectedMotion) amount else -amount)
            else -> TapNestPose(angle = amount)
        }
    }
}

/** Slow, tiny breath around the feet; composed once with the entire layered rig. */
object TapNestBreathing {
    private const val BREATH_CYCLE_MS = 4000L

    fun pose(elapsedMs: Long, strength: Float = 1f): TapNestPose {
        val wave = kotlin.math.sin(
            elapsedMs.coerceAtLeast(0L).toDouble() * 2.0 * kotlin.math.PI / BREATH_CYCLE_MS
        ).toFloat() * strength.coerceIn(0.2f, 2f)
        return TapNestPose(scaleX = 1f + wave * 0.004f, scaleY = 1f + wave * 0.012f)
    }
}
