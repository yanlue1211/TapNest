package com.deskpet.engine

/**
 * Shared, deterministic scheduling and frame selection used by both the
 * editor test playback and the Android floating-overlay renderer.
 * All operations are local and keep old saved rigs on their legacy path.
 */
object TapNestLayerActions {
    private const val PULSE_MS = 700L

    fun periodicElapsed(layer: TapNestLayer, elapsedMs: Long): Long? {
        if (!layer.interactionConfigured || !layer.periodicEnabled || elapsedMs < 0L)
            return null
        val intervalMs = (layer.periodicIntervalSeconds.coerceIn(1f, 120f) * 1000)
            .toLong().coerceAtLeast(PULSE_MS + 1L)
        if (elapsedMs < intervalMs) return null
        val local = elapsedMs % intervalMs
        return local.takeIf { it < PULSE_MS }
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
        return periodicElapsed(driver, elapsedMs)
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
        allowIdleBlink: Boolean = true
    ): String {
        if (!owner.interactionConfigured) return owner.id
        val requested = when {
            owner.tapSwitchFrame && touchAgeMs in 0..999L -> owner.tapFrame
            // A periodic expression has higher priority than idle blinking,
            // otherwise the scene mixes two different action frames.
            owner.periodicAction == TapNestPeriodicAction.SWITCH_FRAME &&
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
    fun ownerVisible(ownerId: String, visibleFace: String, headMode: TapNestHeadMode): Boolean =
        when {
            ownerId == "head_blank" -> headMode == TapNestHeadMode.BLANK_HEAD_WITH_FACE
            ownerId.startsWith("head_") ->
                headMode == TapNestHeadMode.FULL_HEAD && ownerId == visibleFace
            ownerId.startsWith("face_") ->
                headMode == TapNestHeadMode.BLANK_HEAD_WITH_FACE && ownerId == visibleFace
            else -> true
        }

    fun motionPose(layer: TapNestLayer, motionAgeMs: Long, periodic: Boolean = false): TapNestPose {
        val selected = if (periodic) layer.periodicMotion ?: layer.tapMotion else layer.tapMotion
        // A mirrored clone already reverses the canvas. Use the source-side
        // clip sign to prevent reflecting left/right flap directions twice.
        val motionId = if (layer.mirrorX) layer.mirroredFromId ?: layer.id else layer.id
        val clip = when (selected) {
            TapNestPartMotion.NOD -> TapNestPresets.nod(motionId)
            TapNestPartMotion.BOUNCE -> TapNestPresets.bounce(motionId)
            TapNestPartMotion.FLAP -> TapNestPresets.flap(motionId)
            TapNestPartMotion.TWITCH -> TapNestPresets.twitch(motionId)
        }
        return TapNestMotion.sample(clip, motionId, motionAgeMs)
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
