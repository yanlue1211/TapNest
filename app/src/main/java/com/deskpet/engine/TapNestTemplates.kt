package com.deskpet.engine

import kotlinx.serialization.Serializable

@Serializable
enum class TapNestHeadMode { FULL_HEAD, BLANK_HEAD_WITH_FACE }

/** A template is a reusable rig + slot layout, not the character's picture files. */
@Serializable
data class TapNestSavedTemplate(val id: String, val name: String, val rig: TapNestRig)

data class TapNestSlotSpec(val id: String, val label: String, val optional: Boolean = true)

object TapNestTemplates {
    const val MANUAL = "manual"

    /** IDs retained strictly to label older saved character rigs. Not a selectable template. */
    private val legacySlots: List<TapNestSlotSpec> = listOf(
        TapNestSlotSpec("body", "身体（静态）", false),
        TapNestSlotSpec("tail", "尾巴（坐姿后侧，单图摇摆）"),
        TapNestSlotSpec("wing_0", "翅膀 · 平时"),
        TapNestSlotSpec("wing_1", "翅膀 · 戳动"),
        TapNestSlotSpec("head_blank", "空白头部"),
        TapNestSlotSpec("head_0", "完整头部 · 表情 1"),
        TapNestSlotSpec("head_1", "完整头部 · 表情 2"),
        TapNestSlotSpec("head_2", "完整头部 · 表情 3"),
        TapNestSlotSpec("head_3", "完整头部 · 表情 4"),
        TapNestSlotSpec("face_0", "单独表情 · 1"),
        TapNestSlotSpec("face_1", "单独表情 · 2"),
        TapNestSlotSpec("face_2", "单独表情 · 3"),
        TapNestSlotSpec("face_3", "单独表情 · 4"),
        TapNestSlotSpec("bow_0", "胸前蝴蝶结 · 平时"),
        TapNestSlotSpec("bow_1", "胸前蝴蝶结 · 戳动")
    )

    /** Optional default frames; no image assets come with the manual template. */
    fun manual(): TapNestRig = TapNestRig(
        canvasWidth = 512, canvasHeight = 512, templateId = MANUAL,
        layers = listOf(
            TapNestLayer("body", "", zIndex = 10, pivotY = .65f,
                tapShake = true, tapMotion = TapNestPartMotion.BOUNCE, interactionConfigured = true),
            TapNestLayer("tail", "", parentId = "body", zIndex = 0,
                pivotX = .75f, pivotY = .72f, idleSwing = true,
                swingMode = TapNestSwingMode.SPRING,
                idleAmplitude = 85f, idleSpeed = .48f,
                springSoftness = .85f, springDamping = .45f,
                shapePreservation = .30f, interactionConfigured = true),
            TapNestLayer("wing_left", "", parentId = "body", zIndex = 5,
                pivotX = .42f, pivotY = .5f, idleSwing = true,
                idleAmplitude = 14f, idleSpeed = .75f,
                tapShake = true, tapMotion = TapNestPartMotion.FLAP, interactionConfigured = true),
            TapNestLayer("wing_right", "", parentId = "body", zIndex = 5,
                pivotX = .58f, pivotY = .5f, idleSwing = true,
                idleAmplitude = 14f, idleSpeed = .75f,
                tapShake = true, tapMotion = TapNestPartMotion.FLAP, interactionConfigured = true),
            TapNestLayer("ear_left", "", parentId = "head_0", zIndex = 15,
                pivotX = .40f, pivotY = .28f,
                periodicEnabled = true, periodicIntervalSeconds = 8f,
                tapMotion = TapNestPartMotion.TWITCH, interactionConfigured = true),
            TapNestLayer("ear_right", "", parentId = "head_0", zIndex = 15,
                pivotX = .60f, pivotY = .28f,
                periodicEnabled = true, periodicIntervalSeconds = 9f,
                tapMotion = TapNestPartMotion.TWITCH, interactionConfigured = true),
            TapNestLayer("head_0", "", parentId = "body", zIndex = 20,
                pivotX = .5f, pivotY = .72f,
                tapShake = true, tapMotion = TapNestPartMotion.NOD, interactionConfigured = true),
         )
    )

    val manualLabels = mapOf(
        "body" to "身体 · 默认帧", "head_0" to "头部 · 默认帧",
        "tail" to "尾巴 · 默认帧",
        "wing_left" to "左翅膀 · 默认帧", "wing_right" to "右翅膀 · 默认帧",
        "ear_left" to "左耳朵 · 默认帧", "ear_right" to "右耳朵 · 默认帧"
    )

    /**
     * Make optional components without any bundled art. Symmetric creation
     * mirrors only the initial anchor; each side has its own image and settings.
     */
    fun newPart(id: String, zIndex: Int): TapNestLayer {
        val lower = id.lowercase()
        val isWing = lower.contains("wing")
        val isEar = lower.contains("ear")
        val isTail = lower.contains("tail")
        val isHead = lower.contains("head")
        val isBody = lower == "body"
        val isRight = lower.endsWith("_right")
        val isPaired = lower.endsWith("_right") || lower.endsWith("_left")
        val pivotX = when {
            isPaired && isRight -> .60f
            isPaired -> .40f
            else -> .5f
        }
        val parent = if (isEar || lower.contains("eye")) "head_0" else if (!isBody) "body" else null
        return TapNestLayer(id = id, image = "", parentId = parent, zIndex = zIndex,
            pivotX = pivotX,
            pivotY = when { isEar -> .28f; isWing -> .5f; isHead -> .72f; else -> .65f },
            idleSwing = isWing || isTail,
            idleAmplitude = if (isTail) 85f else if (isWing) 14f else 10f,
            idleSpeed = if (isTail) .48f else .7f,
            swingMode = if (isTail) TapNestSwingMode.SPRING else TapNestSwingMode.DELAY,
            springSoftness = if (isTail) .85f else .72f,
            springDamping = if (isTail) .45f else .40f,
            shapePreservation = if (isTail) .30f else 0f,
            tapShake = isWing || isHead || isBody,
            tapMotion = when {
                isWing -> TapNestPartMotion.FLAP
                isEar -> TapNestPartMotion.TWITCH
                isBody -> TapNestPartMotion.BOUNCE
                else -> TapNestPartMotion.NOD
            },
            periodicEnabled = isEar,
            periodicIntervalSeconds = if (isEar) (if (isRight) 9f else 8f) else 7f,
            interactionConfigured = true
        )
    }

    /** Copy only positions, pivots, z ordering and animation definitions. All art stays local. */
    fun asReusableTemplate(rig: TapNestRig): TapNestRig = rig.copy(
        layers = rig.layers.map { it.copy(image = "") }
    )

    fun slotsFor(rig: TapNestRig): List<TapNestSlotSpec> =
        rig.layers.map { layer ->
            if (rig.templateId == MANUAL)
                TapNestSlotSpec(layer.id,
                    manualLabels[layer.id] ?: if (layer.frameOwner != null)
                        "动作帧 · ${layer.frameOwner} #${layer.frameIndex}" else layer.id,
                    layer.id != "body")
            else legacySlots.find { it.id == layer.id }
                ?: TapNestSlotSpec(layer.id, layer.id, layer.id != "body")
        }

    fun isRequired(slot: String, mode: TapNestHeadMode): Boolean =
        slot == "body" || slot == if (mode == TapNestHeadMode.FULL_HEAD) "head_0" else "head_blank"
}
