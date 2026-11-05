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

    val fairySlots: List<TapNestSlotSpec> = listOf(
        TapNestSlotSpec("body", "身体（静态）", false),
        TapNestSlotSpec("tail", "尾巴（单张 PNG，自动摇摆）"),
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
                pivotX = when (slot.id) {
                    "tail" -> 0.15f
                    else -> 0.5f
                },
                pivotY = when {
                    slot.id == "tail" -> 0.15f
                    isHead -> 0.75f
                    else -> 0.5f
                }
            )
        }
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
