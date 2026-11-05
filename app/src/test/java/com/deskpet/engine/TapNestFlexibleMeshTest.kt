package com.deskpet.engine

import org.junit.Assert.*
import org.junit.Test

class TapNestFlexibleMeshTest {
    private val root = TapNestBonePoint(0f, 0f)
    private val tip = TapNestBonePoint(0f, 1f)

    @Test fun flexibleBoneNeedsTwoControlPointsAndIdleEnabled() {
        val layer = TapNestLayer("tail", "tail.png", bones = listOf(root, tip))
        assertNull(TapNestFlexibleMesh.vertices(layer, 300, 300, 350))
        assertNull(TapNestFlexibleMesh.vertices(layer.copy(idleSwing = true,
            bones = listOf(root)), 300, 300, 350))
    }

    @Test fun rootIsStationaryButTipMovesDuringSwing() {
        val layer = TapNestLayer("tail", "tail.png", idleSwing = true,
            idleAmplitude = 22f, idleSpeed = 1.2f, bones = listOf(root, tip))
        val vertices = requireNotNull(TapNestFlexibleMesh.vertices(layer, 320, 320, 350))
        assertEquals((TapNestFlexibleMesh.GRID + 1) * (TapNestFlexibleMesh.GRID + 1) * 2,
            vertices.size)
        assertEquals(0f, vertices[0], 0.01f)
        assertEquals(0f, vertices[1], 0.01f)
        val tipIndex = (TapNestFlexibleMesh.GRID * (TapNestFlexibleMesh.GRID + 1)) * 2
        assertTrue(kotlin.math.abs(vertices[tipIndex]) > 5f)
    }

    @Test fun shortTailStartsAtRootAndTipCatchesUpLater() {
        val tail = TapNestLayer("tail", "tail.png", idleSwing = true,
            idleAmplitude = 12f, idleSpeed = 1f, idleLagSeconds = 0.18f,
            bones = listOf(TapNestBonePoint(0.5f, 0.8f),
                TapNestBonePoint(0.5f, 0.6f),
                TapNestBonePoint(0.5f, 0.4f)))
        val early = TapNestFlexibleMesh.segmentAngles(tail, 250L)
        val later = TapNestFlexibleMesh.segmentAngles(tail, 430L)
        // Base segment reaches its turning maximum first.
        assertTrue(early[0] > later[0])
        // Tip segment reaches its turning maximum after the lower section.
        assertTrue(later[1] > early[1])
        assertTrue(early[0] > 0f)
    }

    @Test fun noDelayMakesBothSegmentsPeakTogether() {
        val tail = TapNestLayer("tail", "tail.png", idleSwing = true,
            idleAmplitude = 12f, idleSpeed = 1f, idleLagSeconds = 0f,
            bones = listOf(root, TapNestBonePoint(0.5f, 0.5f), tip))
        val peak = TapNestFlexibleMesh.segmentAngles(tail, 250L)
        assertEquals(12f, peak.sum(), 0.01f)
    }

    @Test fun straightNoAmplitudeDoesNotDistortImage() {
        val layer = TapNestLayer("cape", "cape.png", idleSwing = true,
            idleAmplitude = 0f, bones = listOf(root, tip))
        val mesh = requireNotNull(TapNestFlexibleMesh.vertices(layer, 320, 320, 350))
        assertEquals(320f, mesh[2 * TapNestFlexibleMesh.GRID], 0.1f)
        assertEquals(0f, mesh[2 * TapNestFlexibleMesh.GRID + 1], 0.1f)
    }
}
