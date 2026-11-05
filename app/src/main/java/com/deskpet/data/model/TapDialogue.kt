package com.deskpet.data.model

/** Local-only, deterministic touch dialogue. Never calls any network or LLM service. */
object TapDialogue {
    const val MAX_LINES = 20
    const val MAX_CHARS = 60

    /** Trims lines, removes blank ones, caps total storage and visible bubble length. */
    fun sanitize(lines: List<String>): List<String> =
        lines.take(200).map { it.trim().take(MAX_CHARS) }
            .filter { it.isNotBlank() }.take(MAX_LINES)

    /** Cycle without repetition until the next round; the sequence lives only in memory. */
    fun at(lines: List<String>, tapIndex: Int): String? =
        lines.takeIf { it.isNotEmpty() }?.get(Math.floorMod(tapIndex, lines.size))
}
