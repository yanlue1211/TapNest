package com.deskpet.overlay

/** Keep the ACTUAL painted head below the safe top, not the PNG's blank canvas. */
object PetVerticalPositionPolicy {
    fun minWindowY(safeTop: Float, windowHeight: Int, transparentTopFraction: Float): Float {
        val h = windowHeight.coerceAtLeast(0)
        return safeTop - h * transparentTopFraction.coerceIn(0f, 0.95f)
    }

    fun clampY(
        desiredY: Float,
        safeTop: Float,
        safeBottom: Float,
        windowHeight: Int,
        transparentTopFraction: Float
    ): Float {
        val min = minWindowY(safeTop, windowHeight, transparentTopFraction)
        val max = (safeBottom - windowHeight).coerceAtLeast(min)
        return desiredY.coerceIn(min, max)
    }
}
