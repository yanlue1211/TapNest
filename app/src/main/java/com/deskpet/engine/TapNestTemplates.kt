package com.deskpet.engine

import kotlinx.serialization.Serializable

@Serializable
enum class TapNestHeadMode { FULL_HEAD, BLANK_HEAD_WITH_FACE }

/** A template is a reusable rig + slot layout, not the character's picture files. */
@Serializable
data class TapNestSavedTemplate(val id: String, val name: String, val rig: TapNestRig)

data class TapNestSlotSpec(val id: String, val label: String, val optional: Boolean = true)

object TapNestTemplates {
    const val FAIRY = "fairy_twinkle_v1"
    const val MANUAL = "manual"

    // Default for a front-facing, seated cat/dog: the tail emerges from behind
    // the lower-right hip. PNG layers should share a transparent canvas.
    // These are editable in the mobile rig preview for other art layouts.
    const val SITTING_TAIL_PIVOT_X = 0.77f
    const val SITTING_TAIL_PIVOT_Y = 0.73f

    val fairySlots: List<TapNestSlotSpec> = listOf(
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

    fun fairy(mode: TapNestHeadMode = TapNestHeadMode.FULL_HEAD): TapNestRig = TapNestRig(
        canvasWidth = 512,
        canvasHeight = 512,
        templateId = FAIRY,
        headMode = mode,
        layers = fairySlots.mapIndexed { index, slot ->
            val isHead = slot.id.startsWith("head") || slot.id.startsWith("face")
            val order = when {
                slot.id == "tail" -> 0
                slot.id.startsWith("wing") -> 1
                slot.id == "body" -> 10
                slot.id.startsWith("head") -> 20
                slot.id.startsWith("face") -> 21
                slot.id.startsWith("bow") -> 30
                else -> index
            }
            TapNestLayer(
                id = slot.id, image = "", zIndex = order,
                parentId = when {
                    slot.id.startsWith("face_") -> "head_blank"
                    slot.id == "body" -> null
                    else -> "body"
                },
                pivotX = when (slot.id) {
                    "tail" -> SITTING_TAIL_PIVOT_X
                    else -> 0.5f
                },
                pivotY = when {
                    slot.id == "tail" -> SITTING_TAIL_PIVOT_Y
                    isHead -> 0.75f
                    else -> 0.5f
                }
            )
        }
    )

    /** Optional default frames; no image assets come with the manual template. */
    fun manual(): TapNestRig = TapNestRig(
        canvasWidth = 512, canvasHeight = 512, templateId = MANUAL,
        layers = listOf(
            TapNestLayer("body", "", zIndex = 10, pivotY = .65f,
                tapShake = true, tapMotion = TapNestPartMotion.BOUNCE),
            TapNestLayer("tail", "", parentId = "body", zIndex = 0,
                pivotX = .75f, pivotY = .72f, idleSwing = true,
                swingMode = TapNestSwingMode.SPRING,
                idleAmplitude = 85f, idleSpeed = .48f,
                springSoftness = .85f, springDamping = .45f,
                shapePreservation = .30f),
            TapNestLayer("wing_left", "", parentId = "body", zIndex = 5,
                pivotX = .42f, pivotY = .5f, idleSwing = true,
                idleAmplitude = 14f, idleSpeed = .75f,
                tapShake = true, tapMotion = TapNestPartMotion.FLAP),
            TapNestLayer("wing_right", "", parentId = "body", zIndex = 5,
                pivotX = .58f, pivotY = .5f, idleSwing = true,
                idleAmplitude = 14f, idleSpeed = .75f,
                tapShake = true, tapMotion = TapNestPartMotion.FLAP),
            TapNestLayer("ear_left", "", parentId = "head_0", zIndex = 15,
                pivotX = .40f, pivotY = .28f,
                periodicEnabled = true, periodicIntervalSeconds = 8f,
                tapMotion = TapNestPartMotion.TWITCH),
            TapNestLayer("ear_right", "", parentId = "head_0", zIndex = 15,
                pivotX = .60f, pivotY = .28f,
                periodicEnabled = true, periodicIntervalSeconds = 9f,
                tapMotion = TapNestPartMotion.TWITCH),
            TapNestLayer("hair_back", "", parentId = "body", zIndex = 8),
            TapNestLayer("head_0", "", parentId = "body", zIndex = 20,
                pivotX = .5f, pivotY = .72f,
                tapShake = true, tapMotion = TapNestPartMotion.NOD),
            TapNestLayer("hair_front", "", parentId = "head_0", zIndex = 25),
            TapNestLayer("eye_left", "", parentId = "head_0", zIndex = 26,
                pivotX = .43f, pivotY = .42f),
            TapNestLayer("eye_right", "", parentId = "head_0", zIndex = 26,
                pivotX = .57f, pivotY = .42f),
            TapNestLayer("bow_0", "", parentId = "body", zIndex = 30)
        )
    )

    val manualLabels = mapOf(
        "body" to "身体 · 默认帧", "head_0" to "头部 · 默认帧",
        "tail" to "尾巴 · 默认帧",
        "wing_left" to "左翅膀 · 默认帧", "wing_right" to "右翅膀 · 默认帧",
        "ear_left" to "左耳朵 · 默认帧", "ear_right" to "右耳朵 · 默认帧",
        "eye_left" to "左眼睛 · 默认帧", "eye_right" to "右眼睛 · 默认帧",
        "hair_front" to "前发 · 默认帧", "hair_back" to "后发 · 默认帧",
        "bow_0" to "装饰 · 默认帧"
    )

    /** Copy only positions, pivots, z ordering and animation definitions. All art stays local. */
    fun asReusableTemplate(rig: TapNestRig): TapNestRig = rig.copy(
        layers = rig.layers.map { it.copy(image = "") }
    )

    fun slotsFor(rig: TapNestRig): List<TapNestSlotSpec> =
        rig.layers.map { layer ->
            fairySlots.find { it.id == layer.id }
                ?: TapNestSlotSpec(layer.id, layer.id, layer.id != "body")
        }

    fun isRequired(slot: String, mode: TapNestHeadMode): Boolean =
        slot == "body" || slot == if (mode == TapNestHeadMode.FULL_HEAD) "head_0" else "head_blank"
}
