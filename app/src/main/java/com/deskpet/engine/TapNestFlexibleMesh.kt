package com.deskpet.engine

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Lightweight 2D skinning of a single PNG. Users place 2..8 points along a limb,
 * root to tip. The original image remains one file; mesh vertices bend around
 * the animated polyline instead of rotating the whole tail as a rigid rectangle.
 */
object TapNestFlexibleMesh {
    const val GRID = 16
    private data class Point(val x: Float, val y: Float)
    private fun rotate(p: Point, rad: Float) = Point(
        (p.x * cos(rad) - p.y * sin(rad)), (p.x * sin(rad) + p.y * cos(rad))
    )
    private operator fun Point.plus(o: Point) = Point(x + o.x, y + o.y)
    private operator fun Point.minus(o: Point) = Point(x - o.x, y - o.y)
    private operator fun Point.times(k: Float) = Point(x * k, y * k)

    fun vertices(layer: TapNestLayer, width: Int, height: Int, elapsedMs: Long,
                 mesh: Int = GRID): FloatArray? {
        if (!layer.idleSwing || layer.bones.size !in 2..8 ||
            width !in 1..4096 || height !in 1..4096 || mesh !in 2..32) return null
        val base = layer.bones.map { Point(it.x.coerceIn(0f, 1f) * width,
                                          it.y.coerceIn(0f, 1f) * height) }
        val transformed = ArrayList<Point>(base.size)
        transformed.add(base.first())
        val t = (elapsedMs.coerceAtLeast(0) / 1000.0 * layer.idleSpeed.coerceIn(0.1f, 3f) *
                2.0 * PI).toFloat()
        var accumulated = 0f
        val phaseShift = 0.7f
        for (i in 1 until base.size) {
            accumulated += Math.toRadians(
                (layer.idleAmplitude.coerceIn(0f, 40f) * sin(t + i * phaseShift) /
                 (base.size - 1)).toDouble()
            ).toFloat()
            val source = base[i] - base[i - 1]
            transformed.add(transformed.last() + rotate(source, accumulated))
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
            val angleDelta = atan2(newV.y, newV.x) - atan2(oldV.y, oldV.x)
            val result = newProjection + rotate(vertex - originalProjection, angleDelta)
            coords[index++] = result.x
            coords[index++] = result.y
        }
        return coords
    }
}
