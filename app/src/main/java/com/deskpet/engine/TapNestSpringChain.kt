package com.deskpet.engine

import kotlin.math.PI
import kotlin.math.sin

/**
 * A small damped spring chain for image-mesh tails, ears and hair.
 *
 * The base segment is directly driven by an idle sinusoid, while each further
 * segment follows the previous segment through a velocity-bearing damped
 * spring. Angles are stored in world/absolute space and converted to relative
 * joint angles for skinning; this prevents the tip from receiving every
 * segment's full rotation and folding over unnaturally.
 *
 * Pure Kotlin, frame-rate independent, one chain per layer per renderer.
 */
class TapNestSpringChain {
    private var lastLayer: TapNestLayer? = null
    private var previousTimeMs = -1L
    private var orientations = FloatArray(0)
    private var angularVelocities = FloatArray(0)
    private var drivePhase = 0.0
    private var driveAmplitude = 0.0

    fun reset() {
        previousTimeMs = -1L
        lastLayer = null
        orientations = FloatArray(0)
        angularVelocities = FloatArray(0)
    }

    fun step(layer: TapNestLayer, elapsedMs: Long): FloatArray {
        val count = (layer.bones.size - 1).coerceAtLeast(0)
        if (count == 0) return floatArrayOf()
        val now = elapsedMs.coerceAtLeast(0L)
        val speed = layer.idleSpeed.coerceIn(0.1f, 3f).toDouble()
        val amplitude = layer.idleAmplitude.coerceIn(0f, 85f).toDouble()
        val softness = layer.springSoftness.coerceIn(0f, 1f).toDouble()
        val damping = layer.springDamping.coerceIn(0.1f, 1.3f).toDouble()
        val naturalFrequency = 3.3 - 2.6 * softness
        val springOmega = 2.0 * PI * naturalFrequency
        val driveOmega = 2.0 * PI * speed
        // For the one-sided presets, choose the direction AWAY from the body
        // based on the user's root-to-tip line, not the image's fixed orientation.
        // Android screen coordinates are x-right, y-down.
        val first = layer.bones.first()
        val last = layer.bones.last()
        val dx = (last.x - first.x) * if (layer.reflectedMotion) -1f else 1f
        val dy = last.y - first.y
        val startSign = if (layer.idleStartReversed) -1f else 1f
        val outwardSign = (if ((if (dx >= 0f) 1 else -1) *
            (if (dy <= 0f) 1 else -1) >= 0) 1f else -1f) * startSign
        val oneSided = layer.swingRange == TapNestSwingRange.OUTWARD_ONLY
        // Bound the MOTOR, not the final textured tail. If a 75° spring is
        // driven into a 15° range and only clipped after spring integration,
        // its hidden 75° inertia makes the visible tail stick to the boundary.
        // Scaling each signed half-cycle keeps the same phase and changes only
        // its reach; one-sided, reverse and asymmetric ranges all share it.
        fun motor(phase: Double, targetAmplitude: Double): Float {
            val raw = if (oneSided)
                outwardSign * targetAmplitude * .5 * (1.0 - kotlin.math.cos(phase))
            else startSign * targetAmplitude * sin(phase)
            return TapNestSwingBounds.limitAngle(raw.toFloat(), layer.idlePlane,
                targetAmplitude.toFloat())
        }
        fun driver(timeMs: Double) = motor(driveOmega * timeMs / 1000.0, amplitude)

        val resetNeeded = lastLayer?.bones != layer.bones ||
            lastLayer?.idleStartReversed != layer.idleStartReversed ||
            lastLayer?.idlePlane != layer.idlePlane ||
            lastLayer?.swingRange != layer.swingRange ||
            lastLayer?.springVersion != layer.springVersion ||
            lastLayer?.idleSpeed != layer.idleSpeed || count != orientations.size ||
            previousTimeMs < 0L || now < previousTimeMs ||
            now - previousTimeMs > 600L
        if (resetNeeded) {
            drivePhase = driveOmega * now / 1000.0
            driveAmplitude = amplitude
            val initial = driver(now.toDouble())
            orientations = FloatArray(count) { initial }
            angularVelocities = FloatArray(count)
            lastLayer = layer
            previousTimeMs = now
        } else if (now > previousTimeMs) {
            // Semi-implicit Euler integration, fixed <=8ms substeps. Rendering at
            // 15 / 30 / 60fps gives almost the same motion without exploding
            // when the system stalls for a few frames.
            val totalSeconds = (now - previousTimeMs) / 1000.0
            val steps = kotlin.math.ceil(totalSeconds / 0.008).toInt().coerceIn(1, 80)
            val dt = totalSeconds / steps
            val start = previousTimeMs.toDouble()
            for (step in 1..steps) {
                val t = start + step.toDouble() / steps * (now - previousTimeMs)
                val improved = layer.springVersion >= 2
                if (improved) {
                    drivePhase += driveOmega * dt
                    driveAmplitude += (amplitude - driveAmplitude) * (1.0 - kotlin.math.exp(-8.0 * dt))
                } else orientations[0] = driver(t)
                for (i in (if (improved) 0 else 1) until count) {
                    val target = if (i == 0) motor(drivePhase, driveAmplitude).toDouble()
                        else orientations[i - 1].toDouble()
                    val omega = if (improved) springOmega *
                        (if (i == 0) 1.8 else 1.0 - .25 * i / count) else springOmega
                    val angle = orientations[i].toDouble()
                    val velocity = angularVelocities[i].toDouble()
                    val acceleration = omega * omega * (target - angle) -
                        2.0 * damping * omega * velocity
                    var newVelocity = (velocity + acceleration * dt).coerceIn(-550.0, 550.0)
                    var newAngle = (angle + newVelocity * dt).coerceIn(-100.0, 100.0)
                    // Spring overshoot should rebound on contact rather than
                    // spend dozens of frames at the same post-mesh hard limit.
                    // Do not run this on unbounded/linear legacy motions.
                    val plane = layer.idlePlane
                    if (plane.enabled && !plane.linear) {
                        val upper = plane.positiveLimit.coerceIn(0f, 180f).toDouble()
                        val lower = -plane.negativeLimit.coerceIn(0f, 180f).toDouble()
                        if (newAngle > upper) {
                            newAngle = upper
                            if (newVelocity > 0.0) newVelocity *= -.22
                        } else if (newAngle < lower) {
                            newAngle = lower
                            if (newVelocity < 0.0) newVelocity *= -.22
                        }
                    }
                    if (oneSided) {
                        // One-sided presets also rebound at their rest pose.
                        if (newAngle * outwardSign < 0.0) {
                            newAngle = 0.0
                            if (newVelocity * outwardSign < 0.0) newVelocity *= -.22
                        }
                    }
                    orientations[i] = newAngle.toFloat()
                    angularVelocities[i] = newVelocity.toFloat()
                }
            }
            previousTimeMs = now
        }
        // The first segment rotates around its root. Other segments contribute
        // only the DIFFERENCE from the previous absolute orientation.
        return FloatArray(count) { i ->
            if (i == 0) orientations[i] else orientations[i] - orientations[i - 1]
        }
    }
}
