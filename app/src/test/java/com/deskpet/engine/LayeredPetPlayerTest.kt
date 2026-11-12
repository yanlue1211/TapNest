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
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
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
    @Test fun lifeExpressionsCancelOnPickupAndMissingFormFramesUseNormalFace() {
        png("body.png", Color.GREEN)
        png("head_0.png", Color.RED)
        png("head_1.png", Color.BLUE)
        val rig = TapNestRig(canvasWidth = 64, canvasHeight = 64, layers = listOf(
            TapNestLayer("body", "body.png"),
            TapNestLayer("head_0", "head_0.png", zIndex = 1),
            TapNestLayer("head_1", "head_1.png", zIndex = 1)))
        val cfg = tmp.newFile("interaction_rig.json").apply { writeText(Json.encodeToString(rig)) }
        val player = requireNotNull(LayeredPetPlayer.load(cfg))
        val now = android.os.SystemClock.uptimeMillis()
        player.lifeInteraction = LifeInteractionAnimation(com.deskpet.data.CompanionAction.FEED, "🍪",
            com.deskpet.data.InteractionAnimationSettings(expressions = listOf(1, 1, 1)), now)
        assertEquals(Color.BLUE, player.render().getPixel(32, 32))
        player.setPickedUp(true)
        org.junit.Assert.assertNull(player.lifeInteraction)
        player.setPickedUp(false)
        assertEquals(Color.RED, player.render().getPixel(32, 32))
        // A stage bound in another form cannot replace this form's normal expression.
        player.lifeInteraction = LifeInteractionAnimation(com.deskpet.data.CompanionAction.PLAY, "🧸",
            com.deskpet.data.InteractionAnimationSettings(expressions = listOf(3, 3, 3)), now)
        assertEquals(Color.RED, player.render().getPixel(32, 32))
        player.tap()
        org.junit.Assert.assertNull(player.lifeInteraction)
    }

    @Test fun fifthTapUsesUploadedAngryFrameAndPickupResetsTheStreak() {
        png("angry_body.png", Color.GREEN)
        png("normal_head.png", Color.RED)
        png("angry_head.png", Color.BLUE)
        val rig = TapNestRig(canvasWidth = 64, canvasHeight = 64,
            templateId = TapNestTemplates.MANUAL, rapidTapAngryFrameId = "head_f1",
            layers = listOf(TapNestLayer("body", "angry_body.png"),
                TapNestLayer("head", "normal_head.png", zIndex = 1),
                TapNestLayer("head_f1", "angry_head.png", zIndex = 1,
                    parentId = "head", frameOwner = "head", frameIndex = 1)))
        val cfg = tmp.newFile("angry_rig.json").apply { writeText(Json.encodeToString(rig)) }
        val player = requireNotNull(LayeredPetPlayer.load(cfg))
        repeat(4) {
            player.tap()
            assertEquals(Color.RED, player.render().getPixel(32, 32))
        }
        player.tap()
        assertEquals(Color.BLUE, player.render().getPixel(32, 32))
        android.os.SystemClock.sleep(1800L)
        assertEquals(Color.RED, player.render().getPixel(32, 32))
        player.setPickedUp(true)
        player.setPickedUp(false)
        player.tap()
        assertEquals(Color.RED, player.render().getPixel(32, 32))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun releaseNeverShrinksTheWindowWhileTheHeldFrameIsStillPublished() {
        png("release_body.png", Color.GREEN)
        val heldArt = tmp.newFile("release_held.png")
        Bitmap.createBitmap(64, 144, Bitmap.Config.ARGB_8888).let { bitmap ->
            bitmap.eraseColor(Color.BLUE)
            heldArt.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val rig = TapNestRig(canvasWidth = 64, canvasHeight = 64, pickupArtworkEnabled = true,
            layers = listOf(TapNestLayer("body", "release_body.png"),
                TapNestLayer("pickup_pose", "release_held.png")))
        val cfg = tmp.newFile("release_rig.json").apply { writeText(Json.encodeToString(rig)) }
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        val job = kotlinx.coroutines.SupervisorJob()
        val scope = kotlinx.coroutines.CoroutineScope(job + dispatcher)
        try {
            val controller = AnimationController(PetEngine(SpritesheetParser(), GifRenderer()), scope)
            assertTrue(controller.load(com.deskpet.data.model.PetInfo("release", com.deskpet.data.model.PetType.LAYERED,
                "release", petJsonPath = cfg.absolutePath, spritesheetPath = "")))
            val normalHeight = controller.petHeight.value
            controller.setLayeredPickedUp(true)
            val heldHeight = controller.petHeight.value
            assertTrue(heldHeight > normalHeight)
            val heldFrame = controller.currentFrame.value!!
            controller.setLayeredPickedUp(false)
            // Main dispatcher has not rendered the replacement frame yet.
            assertEquals(heldHeight, controller.petHeight.value)
            assertEquals(heldFrame, controller.currentFrame.value)
            controller.setScale(2f)
            assertEquals(heldHeight * 2, controller.petHeight.value)
            controller.setLayeredPickedUp(true)
            assertEquals(heldHeight * 2, controller.petHeight.value)
            controller.stop()
        } finally {
            job.cancel()
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }
}
