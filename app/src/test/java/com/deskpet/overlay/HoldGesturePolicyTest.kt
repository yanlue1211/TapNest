package com.deskpet.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class HoldGesturePolicyTest {
    @Test fun shortTapIsNotHold() = assertEquals(
        HoldGesturePolicy.Action.NONE, HoldGesturePolicy.action(999, false, false))

    @Test fun oneToFiveSecondsOpensOutfitOnly() {
        assertEquals(HoldGesturePolicy.Action.MENU,
            HoldGesturePolicy.action(1000, false, false))
        assertEquals(HoldGesturePolicy.Action.MENU,
            HoldGesturePolicy.action(4999, false, false))
    }

    @Test fun stationaryFiveSecondsCloses() = assertEquals(
        HoldGesturePolicy.Action.CLOSE, HoldGesturePolicy.action(5000, false, false))

    @Test fun draggingOrPinchingNeverClosesOrOpens() {
        assertEquals(HoldGesturePolicy.Action.NONE,
            HoldGesturePolicy.action(7000, true, false))
        assertEquals(HoldGesturePolicy.Action.NONE,
            HoldGesturePolicy.action(7000, false, true))
    }
}
