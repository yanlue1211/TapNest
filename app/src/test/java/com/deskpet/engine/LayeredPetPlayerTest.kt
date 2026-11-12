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
            val heldPresentation = controller.presentation.value
            assertEquals(heldFrame, heldPresentation.frame)
            assertEquals(heldHeight, heldPresentation.height)
            controller.setLayeredPickedUp(false)
            // Main dispatcher has not rendered the replacement frame yet.
            assertEquals(heldHeight, controller.petHeight.value)
            assertEquals(heldFrame, controller.currentFrame.value)
            assertEquals(heldPresentation, controller.presentation.value)
            controller.setScale(2f)
            assertEquals(heldHeight * 2, controller.petHeight.value)
            assertEquals(heldHeight * 2, controller.presentation.value.height)
            assertEquals(heldFrame, controller.presentation.value.frame)
            controller.setLayeredPickedUp(true)
            assertEquals(heldHeight * 2, controller.petHeight.value)
            controller.stop()
        } finally {
            job.cancel()
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }
    @Test fun tappedAlternativeArtworkBouncesTogetherWithItsOwner() {
        png("combo_body.png", Color.GREEN)
        png("combo_default.png", Color.RED)
        val alternative = tmp.newFile("combo_tap.png")
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bitmap).drawRect(20f, 20f, 30f, 30f,
            android.graphics.Paint().apply { color = Color.BLUE })
        alternative.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val rig = TapNestRig(canvasWidth = 64, canvasHeight = 64, templateId = TapNestTemplates.MANUAL,
            layers = listOf(TapNestLayer("body", "combo_body.png"),
                TapNestLayer("custom_part", "combo_default.png", zIndex = 1,
                    interactionConfigured = true, tapShake = true, tapSwitchFrame = true,
                    tapMotion = TapNestPartMotion.BOUNCE),
                TapNestLayer("custom_part_f1", "combo_tap.png", zIndex = 1,
                    parentId = "custom_part", frameOwner = "custom_part", frameIndex = 1,
                    pivotX = 0f, pivotY = 0f)))
        val cfg = tmp.newFile("combo_rig.json").apply { writeText(Json.encodeToString(rig)) }
        val player = requireNotNull(LayeredPetPlayer.load(cfg))
        player.tap()
        android.os.SystemClock.sleep(160L)
        val shown = player.render()
        val blueRows = (0 until shown.height).filter { y ->
            (0 until shown.width).any { x -> shown.getPixel(x, y) == Color.BLUE }
        }
        assertTrue("The alternative frame must actually be drawn", blueRows.isNotEmpty())
        assertTrue("The alternative frame must move along with the bounce", blueRows.first() < 20)
    }
    @Test fun mirroredPartsInheritParentTransformsBeforeTheirOwnReflection() {
        png("parent_body.png", Color.GREEN)
        val transparent = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        tmp.newFile("carrier.png").outputStream().use { transparent.compress(Bitmap.CompressFormat.PNG, 100, it) }
        fun art(name: String, color: Int): Bitmap {
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(bitmap).drawRect(20f, 22f, 26f, 28f, android.graphics.Paint().apply { this.color = color })
            tmp.newFile(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return bitmap
        }
        val ear = art("attached_ear.png", Color.BLUE)
        val alt = art("attached_ear_alt.png", Color.CYAN)
        val cases = listOf(
            TapNestLayer("carrier", "carrier.png", x = 7f, y = 3f),
            TapNestLayer("carrier", "carrier.png", x = 3f, y = 2f, restScale = .8f),
            TapNestLayer("carrier", "carrier.png", idleSwing = true, idleFlexibleEnabled = false, idleAmplitude = 20f, idleSpeed = .5f),
            TapNestLayer("carrier", "carrier.png", x = 7f, mirrorX = true))
        cases.forEachIndexed { index, carrier ->
            for (useFrame in listOf(false, true)) {
                val part = TapNestLayer("ear_right", "attached_ear.png", parentId = carrier.id,
                    mirrorX = index != 3, zIndex = 1, interactionConfigured = true, tapSwitchFrame = useFrame)
                val frame = TapNestLayer("ear_right_f1", "attached_ear_alt.png", parentId = part.id,
                    frameOwner = part.id, frameIndex = 1, mirrorX = true, zIndex = 1)
                val rig = TapNestRig(canvasWidth = 64, canvasHeight = 64, templateId = TapNestTemplates.MANUAL,
                    layers = listOf(TapNestLayer("body", "parent_body.png"), carrier, part, frame))
                val cfg = tmp.newFile("parent_${index}_${useFrame}.json").apply { writeText(Json.encodeToString(rig)) }
                val player = requireNotNull(LayeredPetPlayer.load(cfg))
                if (useFrame) player.tap()
                android.os.SystemClock.sleep(500L)
                val actual = player.render()
                val expected = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(expected)
                canvas.drawColor(Color.GREEN)
                if (carrier.mirrorX) { canvas.translate(64f, 0f); canvas.scale(-1f, 1f) }
                val scale = carrier.restScale
                canvas.translate(carrier.x + 32f * scale, carrier.y + 32f * scale)
                canvas.rotate(if (carrier.idleSwing) 20f else 0f)
                canvas.scale(scale, scale)
                canvas.translate(-32f, -32f)
                if (part.mirrorX) { canvas.translate(64f, 0f); canvas.scale(-1f, 1f) }
                if (!carrier.idleSwing) {
                    val matrix = android.graphics.Matrix()
                    canvas.getMatrix(matrix)
                    val mapped = floatArrayOf(22f, 24f)
                    matrix.mapPoints(mapped)
                    val assets = rig.layers.associate { it.id to com.deskpet.ui.PreviewAsset(transparent, 64, 64) }
                    val local = com.deskpet.ui.previewLocalPoint(androidx.compose.ui.geometry.Offset(mapped[0], mapped[1]),
                        androidx.compose.ui.unit.IntSize(64, 64), rig, part, assets)
                    assertEquals(22f, local.x, .001f)
                    assertEquals(24f, local.y, .001f)
                }
                canvas.drawBitmap(if (useFrame) alt else ear, null, android.graphics.RectF(0f, 0f, 64f, 64f),
                    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG))
                val expectedPixels = IntArray(4096); val actualPixels = IntArray(4096)
                expected.getPixels(expectedPixels, 0, 64, 0, 0, 64, 64)
                actual.getPixels(actualPixels, 0, 64, 0, 0, 64, 64)
                expectedPixels.indices.forEach { pixel ->
                    val a = expectedPixels[pixel]; val b = actualPixels[pixel]
                    val difference = maxOf(kotlin.math.abs(Color.red(a) - Color.red(b)),
                        kotlin.math.abs(Color.green(a) - Color.green(b)), kotlin.math.abs(Color.blue(a) - Color.blue(b)),
                        kotlin.math.abs(Color.alpha(a) - Color.alpha(b)))
                    assertTrue("parent case $index, alternative=$useFrame, pixel=$pixel", difference <= 2)
                }
            }
        }
    }
    @Test fun moreThanThreeFramesAndMoreThanFortyEightLayersLoadAndPlayTheSelectedImage() {
        png("many_default.png", Color.RED)
        png("many_blink.png", Color.BLUE)
        val owner = TapNestLayer("custom_head", "many_default.png", zIndex = 1,
            interactionConfigured = true, tapSwitchFrame = true, tapFrame = 20)
        val frames = (1..20).map { n -> TapNestLayer("custom_head_f$n", "many_blink.png",
            frameOwner = owner.id, frameIndex = n, parentId = owner.id, zIndex = 1, pivotX = 0f, pivotY = 0f) }
        val extras = (1..30).map { TapNestLayer("empty_part_$it", "") }
        val rig = TapNestRig(canvasWidth = 64, canvasHeight = 64, templateId = TapNestTemplates.MANUAL,
            layers = listOf(TapNestLayer("body", "many_default.png"), owner) + frames + extras)
        val cfg = tmp.newFile("many_frames.json").apply { writeText(Json.encodeToString(rig)) }
        val player = requireNotNull(LayeredPetPlayer.load(cfg))
        player.tap()
        assertEquals(Color.BLUE, player.render().getPixel(32, 32))
    }
}
