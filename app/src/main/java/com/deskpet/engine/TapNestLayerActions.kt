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

    fun currentFrame(
        owner: TapNestLayer, allLayers: List<TapNestLayer>,
        elapsedMs: Long, touchAgeMs: Long, availableIds: Set<String>
    ): String {
        if (!owner.interactionConfigured) return owner.id
        val requested = when {
            owner.tapSwitchFrame && touchAgeMs in 0..999L -> owner.tapFrame
            owner.idleBlinkEnabled && touchAgeMs > 1400L &&
                TapNestBlink.isBlinking(elapsedMs) -> owner.blinkFrame
            owner.periodicAction == TapNestPeriodicAction.SWITCH_FRAME &&
                periodicElapsed(owner, elapsedMs) != null &&
                touchAgeMs > 1400L -> owner.periodicFrame
            else -> 0
        }
        if (requested <= 0) return owner.id
        return allLayers.firstOrNull {
            it.frameOwner == owner.id && it.frameIndex == requested && it.id in availableIds
        }?.id ?: owner.id
    }

    fun motionPose(layer: TapNestLayer, motionAgeMs: Long): TapNestPose {
        val clip = when (layer.tapMotion) {
            TapNestPartMotion.NOD -> TapNestPresets.nod(layer.id)
            TapNestPartMotion.BOUNCE -> TapNestPresets.bounce(layer.id)
            TapNestPartMotion.FLAP -> TapNestPresets.flap(layer.id)
            TapNestPartMotion.TWITCH -> TapNestPresets.twitch(layer.id)
        }
        return TapNestMotion.sample(clip, layer.id, motionAgeMs)
    }
}
