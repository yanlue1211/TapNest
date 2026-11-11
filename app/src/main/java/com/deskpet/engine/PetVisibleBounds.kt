package com.deskpet.engine

import android.graphics.Bitmap

/**
 * How much blank alpha canvas precedes the actual artwork at rest.
 *
 * A full-canvas PNG/layered rig may render in a 320px window, even when the
 * character occupies only the lower half. WindowManager clamps the WINDOW
 * to the top screen inset, so its visible head can never reach the top.
 * Measure once when loading (NOT every animation frame), then allow only
 * that transparent part of the window to travel above the safe display edge.
 *
 * The returned fraction is independent of user zoom.
 */
object PetVisibleBounds {
    fun transparentTopFraction(bitmap: Bitmap): Float {
        if (bitmap.isRecycled || !bitmap.hasAlpha() ||
            bitmap.width <= 0 || bitmap.height <= 0) return 0f
        val w = bitmap.width
        val h = bitmap.height
        val row = IntArray(w)
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            if (row.any { ((it ushr 24) and 0xff) > 16 }) {
                return y.toFloat() / h
            }
        }
        // Fully transparent/invalid art should not make its invisible window
        // extend above the status bar where it could become unreachable.
        return 0f
    }
}
