package com.deskpet.engine

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Verify head artwork is included and animation never clears an already published frame. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayeredPetPlayerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun png(name: String, color: Int): File {
        val file = tmp.newFile(name)
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    @Test fun compositeIncludesFullHeadAndRetainsPreviousFrame() {
        png("body.png", Color.GREEN)
        png("head_0.png", Color.RED)
        png("head_1.png", Color.BLUE)
        val rig = TapNestRig(
            canvasWidth = 64, canvasHeight = 64,
            headMode = TapNestHeadMode.FULL_HEAD,
            layers = listOf(
                TapNestLayer("body", "body.png", zIndex = 0),
                TapNestLayer("head_0", "head_0.png", zIndex = 1),
                TapNestLayer("head_1", "head_1.png", zIndex = 1)
            )
        )
        val cfg = tmp.newFile("rig.json").apply { writeText(Json.encodeToString(rig)) }
        val player = LayeredPetPlayer.load(cfg)
        assertNotNull(player)
        val first = player!!.render()
        assertEquals("Head, not body, must show in preview", Color.RED, first.getPixel(32, 32))
        player.tap()
        val second = player.render()
        assertNotSame("Do not mutate the bitmap the UI might still be drawing", first, second)
        assertEquals(Color.BLUE, second.getPixel(32, 32))
        assertEquals("Previously published frame stays stable", Color.RED, first.getPixel(32, 32))
    }
}
