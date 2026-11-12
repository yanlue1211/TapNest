package com.deskpet.engine

/**
 * Optional PNG clothing, composited in the body's own local coordinates.
 *
 * It is NOT a second independent animated limb: the body is its parent for
 * rigid movement and the same body is its skin source for mesh/spring motion.
 * The image itself is independently replaceable. This avoids duplicating
 * animation parameters (which would drift after subsequent body edits).
 */
object TapNestClothing {
    const val ID = "body_clothing"

    fun isLinked(layer: TapNestLayer): Boolean = layer.bodyLinkedClothing

    /** Add a blank clothing slot only when needed. Never change older layers. */
    fun withSlot(rig: TapNestRig): TapNestRig {
        val body = rig.layers.firstOrNull { it.id == "body" } ?: return rig
        if (rig.layers.any { it.bodyLinkedClothing || it.id == ID }) return rig
        if (rig.layers.size >= TapNestFrames.MAX_LAYERS) return rig
        return rig.copy(layers = rig.layers + TapNestLayer(
            id = ID, image = "", displayName = "服装", bodyLinkedClothing = true,
            parentId = body.id, zIndex = body.zIndex + 1,
            // With scale=1, these values form an identity local transform.
            pivotX = 0f, pivotY = 0f, restScale = 1f))
    }

    /** All body settings, action-frame selection, and subsequent edits are live. */
    fun skinOwner(layer: TapNestLayer, nodes: Map<String, TapNestLayer>): TapNestLayer =
        if (isLinked(layer)) nodes["body"] ?: layer
        else layer.frameOwner?.let(nodes::get) ?: layer

    fun sortZ(layer: TapNestLayer, nodes: Map<String, TapNestLayer>): Int =
        if (isLinked(layer)) (nodes["body"]?.zIndex ?: layer.zIndex) + 1 else layer.zIndex

    /**
     * Clothing art is a full-canvas overlay on the body. Normalize even an
     * imported PNG with a different source size onto the body's local canvas.
     * For mesh drawing, the bitmap sampler still reads the original image.
     */
    fun drawingSize(layer: TapNestLayer, sourceWidth: Int, sourceHeight: Int,
                    bodySize: Pair<Int, Int>?): Pair<Int, Int> =
        if (isLinked(layer)) bodySize ?: (sourceWidth to sourceHeight)
        else sourceWidth to sourceHeight
}
