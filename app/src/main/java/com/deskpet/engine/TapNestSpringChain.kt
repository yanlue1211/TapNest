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
        fun driver(timeMs: Double) = (amplitude * sin(driveOmega * timeMs / 1000.0)).toFloat()

        val resetNeeded = lastLayer != layer || count != orientations.size ||
            previousTimeMs < 0L || now < previousTimeMs ||
            now - previousTimeMs > 600L
        if (resetNeeded) {
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
                orientations[0] = driver(t)
                for (i in 1 until count) {
                    val target = orientations[i - 1].toDouble()
                    val angle = orientations[i].toDouble()
                    val velocity = angularVelocities[i].toDouble()
                    val acceleration = springOmega * springOmega * (target - angle) -
                        2.0 * damping * springOmega * velocity
                    val newVelocity = (velocity + acceleration * dt).coerceIn(-550.0, 550.0)
                    val newAngle = (angle + newVelocity * dt).coerceIn(-100.0, 100.0)
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
