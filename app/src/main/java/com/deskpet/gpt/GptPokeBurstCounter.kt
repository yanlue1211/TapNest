package com.deskpet.gpt

/**
 * GPT edition's tap semantics borrowed from the public *behavioral contract*
 * of the SullyOS desktop companion, not from its role/memory system.
 *
 * Rapid double taps are reserved for opening input by GestureHandler, so only
 * actual onTap callbacks participate here. The image/angry animation is already
 * implemented by the original TapNestTapSequence in the animation renderer.
 */
internal class GptPokeBurstCounter {
    private var lastTapMs = -1L
    private var count = 0

    fun tap(nowMs: Long): Int {
        count = if (lastTapMs >= 0L && nowMs >= lastTapMs && nowMs - lastTapMs < 3000L)
            (count + 1).coerceAtMost(1000)
        else 1
        lastTapMs = nowMs
        return count
    }

    /** Called after a 3s quiet interval; no early model requests at 3/5/6 taps. */
    fun finishIfQuiet(nowMs: Long, minCount: Int): Int? {
        if (count == 0 || nowMs - lastTapMs < 3000L) return null
        val total = count
        reset()
        return total.takeIf { it >= minCount.coerceIn(1, 50) }
    }

    fun reset() {
        lastTapMs = -1L
        count = 0
    }
}
