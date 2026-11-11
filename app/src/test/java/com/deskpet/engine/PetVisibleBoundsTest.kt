package com.deskpet.engine

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PetVisibleBoundsTest {
    @Test fun detectsTransparentHeadroom() {
        val b = Bitmap.createBitmap(16, 20, Bitmap.Config.ARGB_8888)
        try {
            b.eraseColor(Color.TRANSPARENT)
            b.setPixel(3, 8, Color.RED)
            b.setPixel(4, 10, Color.BLUE)
            assertEquals(.4f, PetVisibleBounds.transparentTopFraction(b), .0001f)
        } finally { b.recycle() }
    }

    @Test fun fullyTransparentImageHasNoWindowAllowance() {
        val b = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
        try {
            b.eraseColor(Color.TRANSPARENT)
            assertEquals(0f, PetVisibleBounds.transparentTopFraction(b), .0001f)
        } finally { b.recycle() }
    }

    @Test fun nearlyInvisiblePixelsAreIgnored() {
        val b = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        try {
            b.eraseColor(Color.TRANSPARENT)
            b.setPixel(3, 0, Color.argb(2, 255, 0, 0))
            b.setPixel(3, 5, Color.argb(240, 255, 0, 0))
            assertEquals(.5f, PetVisibleBounds.transparentTopFraction(b), .0001f)
        } finally { b.recycle() }
    }
}
