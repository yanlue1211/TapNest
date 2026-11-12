package com.deskpet.engine

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lightweight 2D skinning of a single PNG. Users place 2..8 points along a limb,
 * root to tip. The original image remains one file; mesh vertices bend around
 * the animated polyline instead of rotating the whole tail as a rigid rectangle.
 */
object TapNestFlexibleMesh {
    const val GRID = 16
    /** Finer mesh for tiny spring-driven tails on a full transparent canvas. */
    const val SPRING_GRID = 32
    private data class Point(val x: Float, val y: Float)
    private fun rotate(p: Point, rad: Float) = Point(
        (p.x * cos(rad) - p.y * sin(rad)), (p.x * sin(rad) + p.y * cos(rad))
    )
    private operator fun Point.plus(o: Point) = Point(x + o.x, y + o.y)
    private operator fun Point.minus(o: Point) = Point(x - o.x, y - o.y)
    private operator fun Point.times(k: Float) = Point(x * k, y * k)

    /**
     * Per-bone 2D motion steering. AUTO remains the original rotation.
     * HORIZONTAL and VERTICAL apply controlled tip displacement, so the
     * opposite axis stays put and thin wings can flap up/down.
     */
    internal fun moveSegment(x: Float, y: Float, angle: Float,
        direction: TapNestBoneSwingDirection): Pair<Float, Float> {
        val offset = sqrt(x * x + y * y) * sin(angle)
        val output = when (direction) {
            TapNestBoneSwingDirection.AUTO -> rotate(Point(x, y), angle)
            TapNestBoneSwingDirection.ROTATION_REVERSE -> rotate(Point(x, y), -angle)
            TapNestBoneSwingDirection.HORIZONTAL -> Point(x + offset, y)
            TapNestBoneSwingDirection.HORIZONTAL_REVERSE -> Point(x - offset, y)
            TapNestBoneSwingDirection.VERTICAL -> Point(x, y + offset)
            TapNestBoneSwingDirection.VERTICAL_REVERSE -> Point(x, y - offset)
            TapNestBoneSwingDirection.FIXED -> Point(x, y)
        }
        return output.x to output.y
    }

    /** Interpolate wrap-safe rotations at both sides of a joint to avoid a seam. */
    private fun mixAngle(from: Float, to: Float, fraction: Float): Float {
        val difference = atan2(sin(to - from), cos(to - from))
        return from + difference * fraction
    }

    /**
     * Root-driven propagation: the first segment is the driver, then successively
     * distal segments respond with a DELAY (phase minus), not phase advance.
     * This is an inexpensive periodic approximation of follow-through, not a full
     * spring / mass simulation. Keeps existing artwork and saved rig compatible.
     */
    fun segmentAngles(layer: TapNestLayer, elapsedMs: Long): FloatArray {
        val count = (layer.bones.size - 1).coerceAtLeast(0)
        if (count == 0) return floatArrayOf()
        val amplitude = layer.idleAmplitude.coerceIn(0f, 40f)
        val speed = layer.idleSpeed.coerceIn(0.1f, 3f)
        val lagSeconds = layer.idleLagSeconds.coerceIn(0f, 0.45f)
        val time = elapsedMs.coerceAtLeast(0) / 1000.0
        val omega = 2.0 * PI * speed
        // In a short upright tail, the lower section supplies most of the turn;
        // the soft upper section bends and catches up a little later.
        val rootWeight = if (count == 1) 1f else 0.62f
        val distalWeight = if (count == 1) 0f else (1f - rootWeight) / (count - 1)
        return FloatArray(count) { index ->
            val weight = if (index == 0) rootWeight else distalWeight
            val delayedTime = time - index * lagSeconds
            (amplitude * weight * sin(omega * delayedTime)).toFloat()
        }
    }

    private fun regionWeight(region: TapNestBoneRegion, x: Float, y: Float): Float {
        if (x < region.left || x > region.right || y < region.top || y > region.bottom) return 0f
        if (region.left == 0f && region.top == 0f && region.right == 1f && region.bottom == 1f) return 1f
        val distance = minOf(x - region.left, region.right - x, y - region.top, region.bottom - y)
        return (distance / .025f).coerceIn(0f, 1f)
    }

    fun vertices(layer: TapNestLayer, width: Int, height: Int, elapsedMs: Long,
                 mesh: Int = GRID, jointAngles: FloatArray? = null,
                 extraAngles: List<FloatArray?> = emptyList()): FloatArray? {
        val main = singleVertices(layer, width, height, elapsedMs, mesh, jointAngles)
        if (layer.boneChains.isEmpty() && layer.boneRegion == TapNestBoneRegion()) return main
        if (width !in 1..4096 || height !in 1..4096 || mesh !in 2..32) return null
        val deformations = listOf(main) + layer.boneChains.mapIndexed { i, chain ->
            singleVertices(chain.asLayer(layer), width, height, elapsedMs, mesh, extraAngles.getOrNull(i))
        }
        if (deformations.all { it == null }) return null
        val regions = listOf(layer.boneRegion) + layer.boneChains.map { it.region }
        val result = FloatArray((mesh + 1) * (mesh + 1) * 2)
        for (row in 0..mesh) for (col in 0..mesh) {
            val x = col.toFloat() / mesh
            val y = row.toFloat() / mesh
            val index = (row * (mesh + 1) + col) * 2
            val px = x * width
            val py = y * height
            var dx = 0f; var dy = 0f; var total = 0f
            deformations.forEachIndexed { i, vertices ->
                if (vertices != null) {
                    val weight = regionWeight(regions[i], x, y)
                    dx += (vertices[index] - px) * weight
                    dy += (vertices[index + 1] - py) * weight
                    total += weight
                }
            }
            val divisor = total.coerceAtLeast(1f)
            result[index] = px + dx / divisor
            result[index + 1] = py + dy / divisor
        }
        return result
    }

    private fun singleVertices(layer: TapNestLayer, width: Int, height: Int, elapsedMs: Long,
                 mesh: Int = GRID, jointAngles: FloatArray? = null): FloatArray? {
        if ((!layer.idleSwing && jointAngles == null) || (!layer.idleFlexibleEnabled && jointAngles == null) || layer.bones.size !in 2..8 ||
            width !in 1..4096 || height !in 1..4096 || mesh !in 2..32) return null
        val base = layer.bones.map { Point(it.x.coerceIn(0f, 1f) * width,
                                          it.y.coerceIn(0f, 1f) * height) }
        val transformed = ArrayList<Point>(base.size)
        transformed.add(base.first())
        val angles = jointAngles?.takeIf { it.size == base.size - 1 }
            ?: segmentAngles(layer, elapsedMs)
        var accumulated = 0f
        for (i in 1 until base.size) {
            accumulated += Math.toRadians(angles[i - 1].toDouble()).toFloat()
            val source = base[i] - base[i - 1]
            val explicit = layer.bones[i].swingDirection
            val direction = if (i == 1 && explicit == TapNestBoneSwingDirection.AUTO)
                layer.bones.first().swingDirection else explicit
            val plane = layer.idlePlane
            val (dx, dy) = if (plane.enabled && plane.linear) {
                val offset = sqrt(source.x * source.x + source.y * source.y) * sin(accumulated)
                val axis = Math.toRadians(plane.axisDegrees.toDouble()).toFloat()
                (source.x + offset * cos(axis)) to (source.y + offset * sin(axis))
            } else {
                val bounded = if (plane.enabled) accumulated.coerceIn(
                    -Math.toRadians(plane.negativeLimit.coerceIn(0f, 180f).toDouble()).toFloat(),
                    Math.toRadians(plane.positiveLimit.coerceIn(0f, 180f).toDouble()).toFloat()) else accumulated
                moveSegment(source.x, source.y, bounded, direction)
            }
            transformed.add(transformed.last() + Point(dx, dy))
        }
        // At a thick outline, an abrupt switch between two adjacent segment
        // transforms stretches the pixels either side of the joint. Interpolate
        // their rotation at each joint so the skin remains continuous.
        val segments = base.size - 1
        val segmentRotations = FloatArray(segments) { i ->
            val original = base[i + 1] - base[i]
            val deformed = transformed[i + 1] - transformed[i]
            atan2(deformed.y, deformed.x) - atan2(original.y, original.x)
        }
        val jointRotations = FloatArray(base.size) { index ->
            when (index) {
                0 -> segmentRotations[0]
                base.lastIndex -> segmentRotations[segments - 1]
                else -> mixAngle(segmentRotations[index - 1], segmentRotations[index], 0.5f)
            }
        }
        val preserve = if (layer.swingMode == TapNestSwingMode.SPRING)
            layer.shapePreservation.coerceIn(0f, 1f) else 0f
        val root = base.first()
        val rootRotation = jointRotations.first()
        // Horizontal/vertical flapping must not be suppressed by a high
        // outline-preservation setting. Only rotation has a rigid equivalent.
        val containsLinearSteering = (layer.idlePlane.enabled && layer.idlePlane.linear) || layer.bones.any {
            it.swingDirection in setOf(
                TapNestBoneSwingDirection.HORIZONTAL,
                TapNestBoneSwingDirection.HORIZONTAL_REVERSE,
                TapNestBoneSwingDirection.VERTICAL,
                TapNestBoneSwingDirection.VERTICAL_REVERSE
            )
        }
        val coords = FloatArray((mesh + 1) * (mesh + 1) * 2)
        var index = 0
        for (row in 0..mesh) for (col in 0..mesh) {
            val vertex = Point(col.toFloat() / mesh * width, row.toFloat() / mesh * height)
            var nearestSegment = 0
            var nearestProgress = 0f
            var best = Float.POSITIVE_INFINITY
            for (j in 0 until base.size - 1) {
                val start = base[j]
                val direction = base[j + 1] - start
                val len2 = direction.x * direction.x + direction.y * direction.y
                val u = if (len2 < 0.0001f) 0f else (
                    ((vertex.x - start.x) * direction.x +
                     (vertex.y - start.y) * direction.y) / len2).coerceIn(0f, 1f)
                val projection = start + direction * u
                val dx = vertex.x - projection.x
                val dy = vertex.y - projection.y
                val d = dx * dx + dy * dy
                if (d < best) {
                    best = d
                    nearestSegment = j
                    nearestProgress = u
                }
            }
            val i = nearestSegment
            val oldA = base[i]
            val oldV = base[i + 1] - oldA
            val newA = transformed[i]
            val newV = transformed[i + 1] - newA
            val originalProjection = oldA + oldV * nearestProgress
            val newProjection = newA + newV * nearestProgress
            // Same angle on either side of a joint, including at the tail tip.
            val uSmooth = nearestProgress * nearestProgress * (3f - 2f * nearestProgress)
            val angleDelta = if (layer.swingMode == TapNestSwingMode.SPRING)
                mixAngle(jointRotations[i], jointRotations[i + 1], uSmooth)
            else segmentRotations[i] // Keep classic DELAY appearance unchanged.
            val soft = newProjection + rotate(vertex - originalProjection, angleDelta)
            val rigid = if (containsLinearSteering) soft
                else root + rotate(vertex - root, rootRotation)
            val result = soft * (1f - preserve) + rigid * preserve
            coords[index++] = result.x
            coords[index++] = result.y
        }
        return coords
    }
}
