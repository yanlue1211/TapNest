package com.deskpet.engine

import kotlin.math.abs
import kotlin.math.exp

/**
 * A motion range is a TURNING range, not a hard wall.
 *
 * First scale the authored wave by its NOMINAL peak; this preserves its timing
 * even when the amplitude slider exceeds a smaller angle range. Skinning can
 * also use a short exponential shoulder to absorb spring overshoot smoothly
 * instead of holding several animation frames at exactly the same angle.
 *
 * The helper is stateless: overlay playback and editor preview agree exactly.
 */
internal object TapNestSwingBounds {
    fun limitAngle(amount: Float, plane: TapNestMotionPlane,
                   driveAmplitude: Float = 0f, softStop: Boolean = false): Float {
        if (!plane.enabled || plane.linear) return amount
        val maxAngle = (if (amount < 0f) plane.negativeLimit else plane.positiveLimit).coerceIn(0f, 180f)
        if (maxAngle == 0f) return 0f
        val peak = driveAmplitude.coerceAtLeast(0f)
        val scaled = if (peak > maxAngle) amount * (maxAngle / peak) else amount
        if (!softStop) return scaled.coerceIn(-maxAngle, maxAngle)

        // A soft 15% shoulder is inactive for small movements. It leaves
        // continuous velocity at the boundary and never flattens a spring
        // overshoot into a straight line of identical angle samples.
        val shoulder = maxAngle * .85f
        val magnitude = abs(scaled)
        if (magnitude <= shoulder) return scaled
        val width = maxAngle - shoulder
        val eased = shoulder + width * (1f - exp(-(magnitude - shoulder) / width))
        return if (scaled < 0f) -eased else eased
    }
}
