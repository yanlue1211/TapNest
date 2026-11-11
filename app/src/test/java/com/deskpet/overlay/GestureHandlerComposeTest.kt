package com.deskpet.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Actual pointer event regression for the interaction between the separate
 * tap, long-press drag, multi-touch, and no-consume outfit observer recognizers.
 * Pure policy tests cannot catch one recognizer consuming another's movement.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GestureHandlerComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longPressThenMoveActuallyDragsWithoutMenuOrTap() {
        var dragged = 0f
        var dragsStarted = 0
        var taps = 0
        var menus = 0
        var closes = 0
        val gestures = GestureHandler(
            onDrag = { dx, _, _ -> dragged += dx },
            onTap = { taps++ },
            isCodexPet = { false },
            onDragStart = { dragsStarted++ },
            onHoldMenu = { menus++ },
            onHoldClose = { closes++ }
        )
        compose.setContent {
            Box(Modifier.size(200.dp).testTag("pet").then(gestures.modifier))
        }
        compose.onNodeWithTag("pet").performTouchInput {
            down(center)
            advanceEventTime(750L)
            moveBy(Offset(32f, 0f))
            advanceEventTime(100L)
            moveBy(Offset(32f, 0f))
            up()
        }
        compose.waitForIdle()
        assertTrue("长按后拖动应有真正的位置变化", dragged > 20f)
        assertEquals(1, dragsStarted)
        assertEquals("拖动不能误触发点击", 0, taps)
        assertEquals("拖动不能误弹换装", 0, menus)
        assertEquals("拖动不能误关闭", 0, closes)
    }
}
