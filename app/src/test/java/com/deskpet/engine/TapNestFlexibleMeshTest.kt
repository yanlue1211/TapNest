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

    @Test fun straightNoAmplitudeDoesNotDistortImage() {
        val layer = TapNestLayer("cape", "cape.png", idleSwing = true,
            idleAmplitude = 0f, bones = listOf(root, tip))
        val mesh = requireNotNull(TapNestFlexibleMesh.vertices(layer, 320, 320, 350))
        assertEquals(320f, mesh[2 * TapNestFlexibleMesh.GRID], 0.1f)
        assertEquals(0f, mesh[2 * TapNestFlexibleMesh.GRID + 1], 0.1f)
    }
}
