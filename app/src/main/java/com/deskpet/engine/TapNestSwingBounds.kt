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
    /** A zero-width half of the range is NOT a request to hold the tail
     * still for half of every cycle. It means that the whole swing should
     * happen on the remaining side of the resting pose. */
    fun hasOnlyOneSide(plane: TapNestMotionPlane): Boolean = plane.enabled && !plane.linear &&
        ((plane.negativeLimit <= 0.001f) != (plane.positiveLimit <= 0.001f))

    /** A single-sided swing completes one smooth 0 -> limit -> 0 cycle.
     * Shifting/clamping a centered sine would instead freeze half its frames
     * at 0°; abs(sine) avoids the freeze but incorrectly doubles the speed. */
    fun periodicDrive(phaseRadians: Double, amplitude: Float,
                      plane: TapNestMotionPlane, directionSign: Float = 1f): Float {
        val positive = plane.positiveLimit.coerceIn(0f, 180f) > 0.001f
        if (hasOnlyOneSide(plane)) {
            val side = if (positive) 1f else -1f
            val progress = .5 * (1.0 - kotlin.math.cos(phaseRadians))
            return (side * amplitude.coerceAtLeast(0f) * progress).toFloat()
        }
        val peak = amplitude.coerceAtLeast(0f)
        // The shorter allowed half of a swing should NOT consume half its
        // period: that makes a 3-degree movement look like a long pause.
        // Split each cycle by its actual angular excursion. Both halves
        // then have matching speeds as they pass through the rest pose.
        // Equal limits retain the historical sine, and a zero limit still
        // follows the single-sided, full-period path above.
        if (plane.enabled && !plane.linear && peak > 0f) {
            val positiveReach = minOf(peak, plane.positiveLimit.coerceIn(0f, 180f))
            val negativeReach = minOf(peak, plane.negativeLimit.coerceIn(0f, 180f))
            if (positiveReach > .001f && negativeReach > .001f &&
                kotlin.math.abs(positiveReach - negativeReach) > .001f) {
                val phase = ((phaseRadians / (2.0 * kotlin.math.PI)) % 1.0 + 1.0) % 1.0
                val positiveFirst = directionSign >= 0f
                val firstReach = if (positiveFirst) positiveReach else negativeReach
                val firstDuration = firstReach / (positiveReach + negativeReach)
                val first = phase < firstDuration
                val local = if (first) phase / firstDuration
                    else (phase - firstDuration) / (1.0 - firstDuration)
                val sign = if (first == positiveFirst) 1f else -1f
                return (sign * peak * kotlin.math.sin(kotlin.math.PI * local)).toFloat()
            }
        }
        return (directionSign * peak * kotlin.math.sin(phaseRadians)).toFloat()
    }

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
