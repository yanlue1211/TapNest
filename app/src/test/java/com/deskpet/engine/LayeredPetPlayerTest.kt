package com.deskpet.engine

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
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

    @Test fun longPickupArtShowsTailWithoutShrinkingOrMovingHead() {
        png("body.png", Color.GREEN)
        val file = tmp.newFile("long_pose.png")
        val art = Bitmap.createBitmap(64, 144, Bitmap.Config.ARGB_8888)
        art.eraseColor(Color.TRANSPARENT)
        // Head and tail are on the SAME image. In the previous 64px canvas,
        // the blue tail at y=120 was cut off by the framebuffer itself.
        for (y in 5 until 30) for (x in 15 until 49) art.setPixel(x, y, Color.RED)
        for (y in 110 until 140) for (x in 15 until 49) art.setPixel(x, y, Color.BLUE)
        file.outputStream().use { art.compress(Bitmap.CompressFormat.PNG, 100, it) }
        art.recycle()
        val rig = TapNestRig(
            canvasWidth = 64, canvasHeight = 64, pickupArtworkEnabled = true,
            layers = listOf(
                TapNestLayer("body", "body.png", zIndex = 0),
                TapNestLayer("pickup_pose", "long_pose.png", zIndex = 100)
            )
        )
        val cfg = tmp.newFile("long_pickup_rig.json").apply {
            writeText(Json.encodeToString(rig))
        }
        val player = requireNotNull(LayeredPetPlayer.load(cfg))
        val before = player.render()
        assertEquals(64, before.height)
        assertEquals(Color.GREEN, before.getPixel(32, 10))
        assertTrue(player.pickupHeight >= 144)
        player.setPickedUp(true)
        val lifted = player.render()
        assertEquals(before.width, lifted.width)
        assertEquals(player.pickupHeight, lifted.height)
        assertEquals("same head pixel position and scale", Color.RED,
            lifted.getPixel(32, 15))
        assertEquals("long tail stays visible", Color.BLUE,
            lifted.getPixel(32, 125))
        player.setPickedUp(false)
        val released = player.render()
        assertEquals(64, released.height)
        assertEquals(Color.GREEN, released.getPixel(32, 10))
        assertEquals("the published original frame was not mutated",
            Color.GREEN, before.getPixel(32, 15))
    }

    @Test fun manualWingDefaultAndTapFrameBothShowWithoutLegacyWingHiding() {
        png("body.png", Color.GREEN)
        png("wing_left.png", Color.RED)
        png("wing_left_f1.png", Color.BLUE)
        val rig = TapNestRig(canvasWidth = 64, canvasHeight = 64,
            templateId = TapNestTemplates.MANUAL, layers = listOf(
                TapNestLayer("body", "body.png", zIndex = 0),
                TapNestLayer("wing_left", "wing_left.png", zIndex = 1,
                    interactionConfigured = true, tapSwitchFrame = true,
                    tapFrame = 1),
                TapNestLayer("wing_left_f1", "wing_left_f1.png", zIndex = 1,
                    parentId = "wing_left", pivotX = 0f, pivotY = 0f,
                    frameOwner = "wing_left", frameIndex = 1)
            ))
        val cfg = tmp.newFile("public_rig.json").apply { writeText(Json.encodeToString(rig)) }
        val player = requireNotNull(LayeredPetPlayer.load(cfg))
        assertEquals("manual wing should be visible", Color.RED, player.render().getPixel(32, 32))
        player.tap()
        assertEquals("tap selects separate uploaded wing frame",
            Color.BLUE, player.render().getPixel(32, 32))
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
