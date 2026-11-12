package com.deskpet.engine

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class TapNestMotionV2Test {
    private val ear = TapNestLayer(id = "ear_left", image = "ear.png",
        interactionConfigured = true, tapShake = true,
        bones = listOf(TapNestBonePoint(.5f, .9f), TapNestBonePoint(.5f, .65f),
            TapNestBonePoint(.5f, .4f), TapNestBonePoint(.5f, .1f)),
        tapFlexible = TapNestImpulse(enabled = true))

    @Test fun tipImpulseWorksWithIdleDisabledAndKeepsRootSegmentStill() {
        val angles = TapNestLayerActions.jointAngles(ear, listOf(ear), 100L, 100L, null)!!
        assertEquals(0f, angles[0], .0001f)
        assertTrue(abs(angles.sum()) > .01f)
        assertNotNull(TapNestFlexibleMesh.vertices(ear, 512, 512, 100L, jointAngles = angles))
    }

    @Test fun touchOverridesTimedPulseWithoutAccumulationAndReturnsToIdle() {
        val layer = ear.copy(periodicEnabled = true, periodicIntervalSeconds = 2f,
            periodicFlexible = TapNestImpulse(enabled = true, amplitude = 50f))
        val idle = floatArrayOf(2f, 1f, -1f)
        val tapped = TapNestLayerActions.jointAngles(layer, listOf(layer), 2100L, 100L, idle)!!
        val onlyTap = TapNestLayerActions.jointAngles(ear, listOf(ear), 2100L, 100L, idle)!!
        assertArrayEquals(onlyTap, tapped, .0001f)
        repeat(100) {
            assertArrayEquals(tapped, TapNestLayerActions.jointAngles(layer, listOf(layer), 2100L, 100L, idle), .0001f)
        }
        assertSame(idle, TapNestLayerActions.jointAngles(ear, listOf(ear), 3000L, 680L, idle))
    }

    @Test fun savedNamesAndIndependentActionsRoundTripWithoutChangingBindings() {
        val rig = TapNestRig(canvasWidth = 512, canvasHeight = 512, layers = listOf(
            ear.copy(periodicFlexible = TapNestImpulse(enabled = true, amplitude = 5f)),
            TapNestLayer(id = "ear_left_f1", image = "unchanged.png", frameOwner = ear.id,
                frameIndex = 1, displayName = "开心眯眼🐶")))
        val decoded = Json.decodeFromString(TapNestRig.serializer(), Json.encodeToString(TapNestRig.serializer(), rig))
        assertEquals(rig, decoded)
        assertEquals("开心眯眼🐶", TapNestLayerActions.frameLabel(decoded.layers[0], decoded.layers, 1))
        assertEquals(18f, decoded.layers[0].tapFlexible.amplitude, .001f)
        assertEquals(5f, decoded.layers[0].periodicFlexible.amplitude, .001f)
        val old = Json.decodeFromString(TapNestLayer.serializer(), """{"id":"tail","image":"tail.png"}""")
        assertFalse(old.tapFlexible.enabled)
        assertFalse(old.periodicFlexible.enabled)
    }

    @Test fun changingUnrelatedSettingsDoesNotResetSpringHistory() {
        val layer = ear.copy(idleSwing = true, swingMode = TapNestSwingMode.SPRING)
        val a = TapNestSpringChain(); val b = TapNestSpringChain()
        for (t in 0L..330L step 33L) { a.step(layer, t); b.step(layer, t) }
        assertArrayEquals(a.step(layer, 363L), b.step(layer.copy(displayName = "新名字", x = 10f), 363L), .0001f)
    }
}
