package com.deskpet.engine

import kotlinx.serialization.Serializable

/** Local rig format. Paths are relative to a character pack, not remote URLs. */
@Serializable
enum class TapNestEmotion { NEUTRAL, HAPPY, SAD, ANGRY, SHY }

@Serializable
data class TapNestRig(
    val formatVersion: Int = 1,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val templateId: String = "manual",
    val headMode: TapNestHeadMode = TapNestHeadMode.FULL_HEAD,
    /** -1 keeps current expression while stroking; 0..3 selects a dedicated face. */
    val pettingExpression: Int = -1, // legacy data: no longer exposed or triggered
    /** Optional expression while held, -1 keeps the usual face. */
    /** If true and the single uploaded PNG exists, use that one image while held. */
    val pickupArtworkEnabled: Boolean = false,
    val blinkExpression: Int = -1,
    val tapExpression: Int = -1,
    /** Optional uploaded frame for five consecutive taps, each gap strictly <3s. */
    val rapidTapAngryFrameId: String? = null,
    /** Map frame index to a mood; no labels means preserve legacy default face. */
    val expressionEmotions: Map<Int, TapNestEmotion> = emptyMap(),
    val layers: List<TapNestLayer> = emptyList(),
    val clips: List<TapNestClip> = emptyList(),
    /** Subtle whole-character breathing; opt in so old companions remain unchanged. */
    val idleBreathingEnabled: Boolean = false,
    val idleBreathingStrength: Float = 1f
)

/** Normalized control points from root to tip, all inside the imported PNG. */
@Serializable
enum class TapNestBoneSwingDirection { AUTO, HORIZONTAL, VERTICAL, HORIZONTAL_REVERSE, VERTICAL_REVERSE, ROTATION_REVERSE, FIXED }

@Serializable
data class TapNestBonePoint(val x: Float, val y: Float, val swingDirection: TapNestBoneSwingDirection = TapNestBoneSwingDirection.AUTO)

/** Existing rigs continue using the original phase-delay motion by default. */
@Serializable
enum class TapNestSwingMode { DELAY, SPRING }

/** OUTWARD_ONLY swings from the rest pose toward the outside of the body. */
@Serializable
enum class TapNestSwingRange { CENTERED, OUTWARD_ONLY }

@Serializable
enum class TapNestPartMotion { NOD, BOUNCE, FLAP, TWITCH, HAT_POP }

@Serializable
enum class TapNestPeriodicAction { SHAKE, SWITCH_FRAME }

@Serializable
enum class TapNestMotionScope { WHOLE, DISTAL, TIP, UPPER, MIDDLE, LOWER }

@Serializable
data class TapNestBoneRegion(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f)

@Serializable
data class TapNestBoneChain(
    val name: String = "飘带",
    val startReversed: Boolean? = null,
    val scope: TapNestMotionScope? = null,
    val softness: Float? = null,
    val damping: Float? = null,
    val swingMode: TapNestSwingMode? = null,
    val springVersion: Int? = null,
    val shapePreservation: Float? = null,
    val idleLagSeconds: Float? = null,
    val idlePresetId: String? = null,
    val swingRange: TapNestSwingRange? = null,
    val bones: List<TapNestBonePoint> = emptyList(),
    val region: TapNestBoneRegion = TapNestBoneRegion(),
    val plane: TapNestMotionPlane = TapNestMotionPlane()
) {
    fun asLayer(layer: TapNestLayer) = layer.copy(bones = bones,
        tapBones = null, periodicBones = null, idleBones = null, boneChains = emptyList(),
        idlePlane = if (plane.enabled) plane else layer.idlePlane,
        idleScope = scope ?: layer.idleScope,
        idleStartReversed = startReversed ?: layer.idleStartReversed,
        tapStartReversed = startReversed ?: layer.tapStartReversed,
        periodicStartReversed = startReversed ?: layer.periodicStartReversed,
        swingMode = swingMode ?: layer.swingMode, springVersion = springVersion ?: layer.springVersion,
        shapePreservation = shapePreservation ?: layer.shapePreservation,
        idleLagSeconds = idleLagSeconds ?: layer.idleLagSeconds,
        idlePresetId = idlePresetId ?: layer.idlePresetId, swingRange = swingRange ?: layer.swingRange,
        springSoftness = softness ?: layer.springSoftness, springDamping = damping ?: layer.springDamping,
        tapFlexible = layer.tapFlexible.copy(scope = scope ?: layer.tapFlexible.scope, softness = softness ?: layer.tapFlexible.softness, damping = damping ?: layer.tapFlexible.damping),
        periodicFlexible = layer.periodicFlexible.copy(scope = scope ?: layer.periodicFlexible.scope, softness = softness ?: layer.periodicFlexible.softness, damping = damping ?: layer.periodicFlexible.damping))
}

@Serializable
data class TapNestMotionPlane(
    val enabled: Boolean = false,
    val linear: Boolean = false,
    /** Screen-space axis: 0 right, 90 down; the rest image is never rotated. */
    val axisDegrees: Float = 0f,
    val negativeLimit: Float = 45f,
    val positiveLimit: Float = 45f
)

@Serializable
data class TapNestImpulse(
    val enabled: Boolean = false,
    val scope: TapNestMotionScope = TapNestMotionScope.TIP,
    val amplitude: Float = 18f,
    val speed: Float = 3f,
    val softness: Float = .7f,
    val damping: Float = .45f,
    val count: Int = 2,
    val durationMs: Long = 680L
)

@Serializable
data class TapNestLayer(
    val id: String,
    val image: String,
    /** Human-readable label, including Chinese; id remains safe ASCII for local filenames. */
    val displayName: String = "",
    /** Reflect the whole part across the character canvas' vertical centre. */
    val mirrorX: Boolean = false,
    /** Reflect the drive when the artwork has already been drawn for this side. */
    val reflectedMotion: Boolean = false,
    /** Original part used when one-tap cloning; independent after creation. */
    val mirroredFromId: String? = null,
    /**
     * Optional stable accessory group (e.g. custom_part_1). Only actual image
     * groups appear in the long-hold wardrobe. Empty in old saved rigs; those
     * are recognized by legacy ear/wing/tail/bow names without migrations.
     */
    val wardrobeCategory: String = "",
    val parentId: String? = null,
    val zIndex: Int = 0,
    val pivotX: Float = 0.5f,
    val pivotY: Float = 0.5f,
    val x: Float = 0f,
    val y: Float = 0f,
    val restScale: Float = 1f,
    val bones: List<TapNestBonePoint> = emptyList(),
    val tapBones: List<TapNestBonePoint>? = null,
    val boneRegion: TapNestBoneRegion = TapNestBoneRegion(),
    val tapBoneRegion: TapNestBoneRegion? = null,
    val periodicBoneRegion: TapNestBoneRegion? = null,
    val idleBoneRegion: TapNestBoneRegion? = null,
    val boneChains: List<TapNestBoneChain> = emptyList(),
    val tapBoneChains: List<TapNestBoneChain>? = null,
    val periodicBoneChains: List<TapNestBoneChain>? = null,
    val idleBoneChains: List<TapNestBoneChain>? = null,
    val tapPlane: TapNestMotionPlane = TapNestMotionPlane(),
    val periodicPlane: TapNestMotionPlane = TapNestMotionPlane(),
    val idlePlane: TapNestMotionPlane = TapNestMotionPlane(),
    val periodicBones: List<TapNestBonePoint>? = null,
    val idleBones: List<TapNestBonePoint>? = null,
    val idleSwing: Boolean = false,
    val idleStartReversed: Boolean = false,
    val tapStartReversed: Boolean = false,
    val periodicStartReversed: Boolean = false,
    val idleFlexibleEnabled: Boolean = true,
    val idleScope: TapNestMotionScope = TapNestMotionScope.WHOLE,
    val idlePresetId: String = "",
    val idleAmplitude: Float = 10f,
    val idleSpeed: Float = 0.7f,
    /** Time (seconds) that each successive flexible segment trails its parent. */
    val idleLagSeconds: Float = 0.18f,
    val swingMode: TapNestSwingMode = TapNestSwingMode.DELAY,
    /** Higher means a softer spring, not a larger static deflection. */
    val springSoftness: Float = 0.72f,
    /** Opt-in integrator; old saves retain their original spring response. */
    val springVersion: Int = 1,
    /** Damping ratio: low values add overshoot; high values settle quickly. */
    val springDamping: Float = 0.40f,
    /** 0: freely bend the skin, 1: preserve the original stroke as a rigid shape. */
    val shapePreservation: Float = 0f,
    /** Centered for classic oscillation; one-sided for diagonal tail silhouettes. */
    val swingRange: TapNestSwingRange = TapNestSwingRange.CENTERED,
    /** Non-null for a nested alternative image frame; it is not a separate pet limb. */
    val frameOwner: String? = null,
    val frameIndex: Int = 0,
    /** False on old imported rigs: preserve legacy reaction animations. */
    val interactionConfigured: Boolean = false,
    /** Motion settings are edited on the owner/default-frame screen. */
    val tapShake: Boolean = false,
    /** Opt in when editing basic parameters; preserve legacy clips otherwise. */
    val tapBasicConfigured: Boolean = false,
    val periodicBasicConfigured: Boolean = false,
    val tapFlexible: TapNestImpulse = TapNestImpulse(),
    val periodicFlexible: TapNestImpulse = TapNestImpulse(),
    val tapMotion: TapNestPartMotion = TapNestPartMotion.NOD,
    /** Hat flight height as fraction of the full PNG canvas (e.g. .22 = 22%). */
    val hatFlightHeight: Float = 0.22f,
    /** Null keeps the previous shared tap/periodic motion for existing saves. */
    val periodicMotion: TapNestPartMotion? = null,
    val tapSwitchFrame: Boolean = false,
    val tapFrame: Int = 1,
    val periodicEnabled: Boolean = false,
    val periodicIntervalSeconds: Float = 7f,
    val periodicAction: TapNestPeriodicAction = TapNestPeriodicAction.SHAKE,
    val periodicFrame: Int = 1,
    /** Additional frame switch alongside SHAKE; legacy SWITCH_FRAME stays valid. */
    val periodicSwitchFrame: Boolean = false,
    /** Left/right ear and wing pairs share one idle action clock unless disabled. */
    val syncPairedPeriodic: Boolean = true,
    val syncPairedAll: Boolean = false,
    val idleBlinkEnabled: Boolean = false,
    val blinkFrame: Int = 1,
    /** Optional outfit overlay. Appended so old positional constructors stay valid. */
    val bodyLinkedClothing: Boolean = false
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
/** Deterministic wobble when lifting a layered companion, shared by overlay and preview. */
/**
 * Independent height for a long pickup PNG. Width is deliberately NOT resized:
 * the character's head stays the same size and its top-centre anchor stays put.
 *
 * The 8-degree side sway sweeps the bottom corners further down, so leave a
 * small extra transparent strip to avoid chopping clothes/tails during rocking.
 */
object TapNestPickupCanvas {
    fun requiredHeight(canvasWidth: Int, canvasHeight: Int, imageWidth: Int,
                       imageHeight: Int, imageScale: Float): Int {
        if (canvasWidth <= 0 || canvasHeight <= 0 ||
            imageWidth <= 0 || imageHeight <= 0) return canvasHeight.coerceAtLeast(1)
        val sc = imageScale.coerceIn(.3f, 3f)
        // Preserve exact legacy dimensions if this pose already fits.
        if (imageHeight.toDouble() * sc <= canvasHeight) return canvasHeight
        val widestSide = kotlin.math.max(
            canvasWidth * .5f, kotlin.math.abs(imageWidth - canvasWidth * .5f)
        ) * sc
        val swingAllowance = widestSide * kotlin.math.sin(Math.toRadians(8.0)).toFloat()
        val bottom = imageHeight.toDouble() * sc + swingAllowance + 2.0
        return kotlin.math.max(canvasHeight.toDouble(), bottom)
            .coerceAtMost(16384.0).let { kotlin.math.ceil(it) }.toInt()
    }
}

object TapNestPickupMotion {
    fun angle(heldForMs: Long, amplitude: Float, cyclesPerSecond: Float): Float {
        val seconds = heldForMs.coerceAtLeast(0L) / 1000.0
        return (kotlin.math.sin(seconds * 2.0 * kotlin.math.PI *
            cyclesPerSecond.coerceIn(.4f, 2.5f)) *
            amplitude.coerceIn(0f, 24f)).toFloat()
    }
}

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

/**
 * Deterministic, softly irregular spontaneous blinks, for both editor preview
 * and live overlay. No separate timer or thread, and no random state to save.
 */
object TapNestBlink {
    private const val WINDOW_MS = 5200L
    private fun startInWindow(index: Long): Long {
        val scramble = (index * 1103515245L + 12345L) xor
            ((index + 17L) * 2654435761L)
        return index * WINDOW_MS + 800L + (scramble and 0x7fffffffL) % 2200L
    }

    fun isBlinking(elapsedMs: Long): Boolean {
        if (elapsedMs < 0L) return false
        val window = elapsedMs / WINDOW_MS
        val start = startInWindow(window)
        val first = elapsedMs >= start && elapsedMs < start + 150L
        val double = window % 5L == 3L &&
            elapsedMs >= start + 215L && elapsedMs < start + 330L
        return first || double
    }
}
