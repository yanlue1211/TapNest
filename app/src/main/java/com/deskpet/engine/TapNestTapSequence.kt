package com.deskpet.engine

/** Per-player/per-preview state: never shared between pets or forms. */
internal class TapNestTapSequence {
    private var lastTap: Long? = null
    private var count = 0

    fun tap(nowMs: Long): Boolean {
        val previous = lastTap
        count = if (previous != null && nowMs >= previous && nowMs - previous < 3000L)
            (count + 1).coerceAtMost(5) else 1
        lastTap = nowMs
        return count >= 5
    }

    fun reset() { lastTap = null; count = 0 }
}
