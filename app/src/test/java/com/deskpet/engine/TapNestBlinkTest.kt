package com.deskpet.engine

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TapNestBlinkTest {
    @Test fun blinkingIsBriefAndIrregularDuringIdle() {
        var closed = 0
        var groups = 0
        var previous = false
        for (ms in 0L..30000L step 10L) {
            val blink = TapNestBlink.isBlinking(ms)
            if (blink) closed++
            if (blink && !previous) groups++
            previous = blink
        }
        assertTrue("must blink occasionally", groups in 4..12)
        assertTrue("must remain mostly open", closed < 200)
        assertFalse(TapNestBlink.isBlinking(-1L))
    }

    @Test fun existingSavedRigsKeepTheirOriginalExpressionBehavior() {
        val old = TapNestRig(canvasWidth = 512, canvasHeight = 512)
        val json = Json.encodeToString(TapNestRig.serializer(), old)
        val back = Json.decodeFromString(TapNestRig.serializer(), json)
        assertEquals(-1, back.blinkExpression)
        assertEquals(-1, back.tapExpression)
        val customized = back.copy(blinkExpression = 2, tapExpression = 3)
        val again = Json.decodeFromString(
            TapNestRig.serializer(), Json.encodeToString(TapNestRig.serializer(), customized))
        assertEquals(2, again.blinkExpression)
        assertEquals(3, again.tapExpression)
    }

    @Test fun boneDirectionDefaultsToAutoAndSurvivesStorage() {
        val old = TapNestBonePoint(0.2f, 0.8f)
        assertEquals(TapNestBoneSwingDirection.AUTO, old.swingDirection)
        val custom = old.copy(swingDirection = TapNestBoneSwingDirection.VERTICAL)
        val json = Json.encodeToString(TapNestBonePoint.serializer(), custom)
        assertEquals(TapNestBoneSwingDirection.VERTICAL,
            Json.decodeFromString(TapNestBonePoint.serializer(), json).swingDirection)
    }
}
