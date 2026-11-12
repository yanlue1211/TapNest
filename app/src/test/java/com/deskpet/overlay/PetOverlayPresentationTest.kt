package com.deskpet.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.deskpet.engine.AnimationController
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PetOverlayPresentationTest {
    @get:Rule val compose = createComposeRule()

    private fun artwork(height: Int): Bitmap = Bitmap.createBitmap(64, height, Bitmap.Config.ARGB_8888).also {
        val canvas = Canvas(it)
        canvas.drawColor(android.graphics.Color.GREEN)
        canvas.drawRect(10f, 10f, 30f, 30f, Paint().apply { color = android.graphics.Color.RED })
    }

    @Test fun normalHeldNormalKeepsHeadSquareEvenInsideAnOldTallViewport() {
        val normal = artwork(64)
        val held = artwork(192)
        val state = mutableStateOf(AnimationController.Presentation(AnimationController.Frame(normal, 1), 64, 64))
        compose.setContent {
            val density = LocalDensity.current
            Box(Modifier.requiredSize(with(density) { 64.toDp() }, with(density) { 192.toDp() })) {
                PetOverlayPresentation(state.value, Modifier.fillMaxSize().testTag("pet"))
            }
        }
        fun verifyHeadAndBottom(long: Boolean) {
            val pixels = compose.onNodeWithTag("pet").captureToImage().toPixelMap()
            assertEquals(64, pixels.width)
            assertEquals(192, pixels.height)
            assertTrue(pixels[15, 15].red > .9f)
            assertTrue(pixels[15, 29].red > .9f)
            assertTrue("Head must not stretch below its authored 20px height", pixels[15, 35].green > .9f)
            val bottomIsArtwork = pixels[15, 100].green > .9f && pixels[15, 100].red < .1f
            assertEquals("Normal pose leaves the extended tail area empty", long, bottomIsArtwork)
        }
        compose.waitForIdle()
        verifyHeadAndBottom(false)
        compose.runOnIdle { state.value = AnimationController.Presentation(AnimationController.Frame(held, 2), 64, 192) }
        compose.waitForIdle()
        verifyHeadAndBottom(true)
        compose.runOnIdle { state.value = AnimationController.Presentation(AnimationController.Frame(normal, 3), 64, 64) }
        compose.waitForIdle()
        verifyHeadAndBottom(false)
    }
}
