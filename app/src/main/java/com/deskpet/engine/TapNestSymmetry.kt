package com.deskpet.engine

/**
 * Copy a finished part (including all its action-frame definitions).
 *
 * Mirror the entire 2D character canvas at render time, rather than flipping a
 * cropped PNG inside its own bounds. This keeps the image, pivot, bones and
 * local positioning symmetric about the CHARACTER's vertical centre line.
 *
 * The returned layers are independent copies; changes to either side later
 * do not silently overwrite the other side.
 */
object TapNestSymmetry {
    /**
     * The original one-point wing animation contains a legacy _left / _right
     * sign convention. A fully reflected copy already mirrors its motion in
     * the canvas: using the destination _right sign would reverse it AGAIN.
     * Reuse the source's local-space sign so both wings rise and fall together.
     * Normal independently-uploaded wings keep their original behaviour.
     */
    fun idleSwingSign(layer: TapNestLayer): Float {
        val motionId = layer.mirroredFromId ?: layer.id
        return if (motionId.endsWith("_left") && motionId.contains("wing")) -1f else 1f
    }

    fun mirroredGroup(
        source: TapNestLayer,
        frames: List<TapNestLayer>,
        newId: String,
        newDisplayName: String
    ): List<TapNestLayer> {
        require(source.frameOwner == null)
        require(newId != source.id && newId.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,31}")))
        val mirrored = source.copy(
            id = newId, image = "", displayName = newDisplayName,
            mirrorX = !source.mirrorX, mirroredFromId = source.id
        )
        val alternateFrames = frames.filter { it.frameOwner == source.id }.map { frame ->
            frame.copy(
                id = "${newId}_f${frame.frameIndex}", image = "",
                displayName = "", frameOwner = newId, parentId = newId,
                mirrorX = !frame.mirrorX, mirroredFromId = frame.id
            )
        }
        return listOf(mirrored) + alternateFrames
    }
}
