package com.deskpet.engine

import android.graphics.Bitmap
import android.graphics.Color
import com.deskpet.data.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LifeInteractionAnimationTest {
    @Test fun legacyProfileDefaultsDoNotRequireNewArt() {
        val old = Json.decodeFromString<CompanionProfile>("""{"affection":42}""")
        assertTrue(old.interactionAnimations.isEmpty())
        assertTrue(InteractionAnimationSettings().enabled)
        assertEquals(listOf(-1, -1, -1), InteractionAnimationSettings().expressions)
    }

    @Test fun everyActionMovesThroughThreeStagesAndExpiresAtEverySpeed() {
        for (action in CompanionAction.entries) for (speed in InteractionSpeed.entries) {
            val session = LifeInteractionAnimation(action, "🍪", InteractionAnimationSettings(speed = speed,
                expressions = listOf(1, 2, 3)), 1000L)
            val middle = if (action == CompanionAction.FEED) 1250L else 1750L
            fun at(ms: Long) = 1000L + (ms / speed.factor).toLong() + 1
            assertEquals(0, session.stage(at(100)))
            assertEquals(1, session.stage(at(800)))
            assertEquals(2, session.stage(at(500 + middle + 100)))
            assertEquals(2, session.expression(at(800)))
            assertFalse(session.active(at(4000)))
        }
    }

    @Test fun disabledAndUnboundExpressionsAreSafe() {
        val off = LifeInteractionAnimation(CompanionAction.BATH, "", InteractionAnimationSettings(enabled = false), 0)
        assertFalse(off.active(0))
        val noArt = LifeInteractionAnimation(CompanionAction.PLAY, "🧸", InteractionAnimationSettings(expressions = emptyList()), 0)
        assertNull(noArt.expression(800))
    }

    @Test fun sourcePixelsAndDimensionsSurviveAllStagesAndExpiredSessionReturnsOriginal() {
        val source = Bitmap.createBitmap(160, 240, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.GREEN)
        for (action in CompanionAction.entries) {
            val session = LifeInteractionAnimation(action, "💚", InteractionAnimationSettings(), 0)
            for (time in listOf(0L, 200L, 400L, 700L, 1500L, 2300L)) {
                val output = session.render(source, time)
                assertEquals(160, output.width)
                assertEquals(240, output.height)
                assertEquals(Color.GREEN, source.getPixel(0, 0))
            }
            assertSame(source, session.render(source, 10000L))
        }
    }
}
