package com.deskpet.engine

/** Limits are shared by editor, imports, forms and playback. */
object TapNestFrames {
    const val MAX_ACTION_FRAMES = 20
    const val MAX_LAYERS = 128

    fun frameId(ownerId: String, index: Int, used: Set<String>): String {
        val conventional = "${ownerId}_f$index"
        if (conventional.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,34}")) && conventional !in used) return conventional
        return (1..MAX_LAYERS + 1).map { "action_frame_$it" }.first { it !in used }
    }

    fun newFrame(owner: TapNestLayer, layers: List<TapNestLayer>, name: String = ""): TapNestLayer {
        require(layers.size < MAX_LAYERS) { "整体素材已达到 $MAX_LAYERS 层" }
        val existing = layers.filter { it.frameOwner == owner.id }
        require(existing.size < MAX_ACTION_FRAMES) { "当前部位已有 $MAX_ACTION_FRAMES 张动作图，可先在素材管理中整理" }
        val index = (1..MAX_ACTION_FRAMES).first { n -> existing.none { it.frameIndex == n } }
        return TapNestLayer(frameId(owner.id, index, layers.map { it.id }.toSet()), "",
            displayName = name, frameOwner = owner.id, frameIndex = index, parentId = owner.id,
            pivotX = 0f, pivotY = 0f, zIndex = owner.zIndex)
    }
}
