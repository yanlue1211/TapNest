package com.deskpet.engine

/**
 * The Compose presentation is atomic, but a WindowManager Surface resize is
 * asynchronous on some Android devices. Hold the taller pickup viewport for
 * a few already-rendered normal frames after releasing the pet; then shrink.
 * This never scales, redraws or mutates user PNGs, and adds no wall-clock timer.
 */
internal class TapNestPickupReleaseViewport(private val settlingFrames: Int = 3) {
    private var normalFramesRemaining = 0

    fun beginRelease() {
        normalFramesRemaining = settlingFrames.coerceAtLeast(1)
    }

    fun cancel() {
        normalFramesRemaining = 0
    }

    /** Called ONLY after a valid, matching normal bitmap was rendered. */
    fun canCollapseAfterNormalFrame(): Boolean {
        if (normalFramesRemaining <= 1) {
            normalFramesRemaining = 0
            return true
        }
        normalFramesRemaining--
        return false
    }
}
