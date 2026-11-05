package com.deskpet.engine

import kotlinx.serialization.Serializable

/** Local rig format. Paths are relative to a character pack, not remote URLs. */
@Serializable
data class TapNestRig(
    val formatVersion: Int = 1,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val layers: List<TapNestLayer> = emptyList(),
    val clips: List<TapNestClip> = emptyList()
)

@Serializable
data class TapNestLayer(
    val id: String,
    val image: String,
    val parentId: String? = null,
    val zIndex: Int = 0,
    val pivotX: Float = 0.5f,
    val pivotY: Float = 0.5f,
    val x: Float = 0f,
    val y: Float = 0f
)

@Serializable
data class TapNestPose(
    val x: Float = 0f,
    val y: Float = 0f,
    val angle: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val alpha: Float = 1f
)

@Serializable
enum class TapNestEase { LINEAR, SMOOTH, HOLD }

@Serializable
data class TapNestKeyframe(
    val atMs: Long,
    val pose: TapNestPose,
    val easeToNext: TapNestEase = TapNestEase.SMOOTH
)

@Serializable
data class TapNestTrack(val layerId: String, val frames: List<TapNestKeyframe>)

@Serializable
data class TapNestClip(
    val name: String,
    val durationMs: Long,
    val loop: Boolean = false,
    val tracks: List<TapNestTrack> = emptyList()
)

/** Platform-independent interpolation: the future mobile editor and overlay share this. */
object TapNestMotion {
    fun sample(clip: TapNestClip, layerId: String, elapsedMs: Long): TapNestPose {
        val frames = clip.tracks.firstOrNull { it.layerId == layerId }?.frames
            ?.sortedBy { it.atMs } ?: return TapNestPose()
        if (frames.isEmpty()) return TapNestPose()
        val duration = clip.durationMs.coerceAtLeast(0L)
        val time = if (clip.loop && duration > 0L) {
            ((elapsedMs % duration) + duration) % duration
        } else elapsedMs.coerceIn(0L, duration)
        if (time <= frames.first().atMs) return frames.first().pose
        if (time >= frames.last().atMs) return frames.last().pose
        val idx = frames.indexOfLast { it.atMs <= time }
        if (idx < 0 || idx + 1 >= frames.size) return frames.last().pose
        val a = frames[idx]
        val b = frames[idx + 1]
        val range = b.atMs - a.atMs
        if (range <= 0L || a.easeToNext == TapNestEase.HOLD) return a.pose
        val raw = ((time - a.atMs).toFloat() / range.toFloat()).coerceIn(0f, 1f)
        val u = when (a.easeToNext) {
            TapNestEase.SMOOTH -> raw * raw * (3f - 2f * raw)
            else -> raw
        }
        fun mix(x: Float, y: Float) = x + (y - x) * u
        return TapNestPose(
            x = mix(a.pose.x, b.pose.x),
            y = mix(a.pose.y, b.pose.y),
            angle = mix(a.pose.angle, b.pose.angle),
            scaleX = mix(a.pose.scaleX, b.pose.scaleX),
            scaleY = mix(a.pose.scaleY, b.pose.scaleY),
            alpha = mix(a.pose.alpha, b.pose.alpha).coerceIn(0f, 1f)
        )
    }
}
