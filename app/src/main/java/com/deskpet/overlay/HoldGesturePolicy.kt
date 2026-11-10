package com.deskpet.overlay

/** Gesture decisions independent of Compose, verified by unit tests. */
object HoldGesturePolicy {
    enum class Action { NONE, MENU, CLOSE }

    fun action(heldMs: Long, moved: Boolean, multiTouch: Boolean): Action = when {
        moved || multiTouch -> Action.NONE
        heldMs >= 5000L -> Action.CLOSE
        heldMs >= 1000L -> Action.MENU
        else -> Action.NONE
    }
}
