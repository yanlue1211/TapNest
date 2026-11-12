package com.deskpet.engine

import kotlin.math.sqrt

/**
 * Four reusable animation presets. Values apply to the selected layer only,
 * never replace a character's picture or other parts of their rig.
 * Bone positions are percentages measured along the art's actual centerline.
 */
data class TapNestTailPreset(
    val id: String,
    val title: String,
    val description: String,
    val bonePercentages: List<Int>,
    val amplitude: Float,
    val speed: Float,
    val softness: Float,
    val damping: Float,
    val preservation: Float,
    val swingRange: TapNestSwingRange
)

object TapNestTailPresets {
    val ALL = listOf(
        TapNestTailPreset("upright_dog", "🐶 竖直狗尾（粗·半长）",
            "较粗、与身体差不多长、从身后竖起；大幅摇摆但尽量保护轮廓。",
            listOf(0, 28, 57, 85), 85f, 0.48f, 0.85f, 0.45f, 0.30f,
            TapNestSwingRange.CENTERED),
        TapNestTailPreset("upright_cat", "🐱 竖直猫尾（细·略长）",
            "细长直立或微弯尾巴；弯曲层次丰富，尾尖有更明显的惯性。",
            listOf(0, 22, 44, 66, 88), 74f, 0.46f, 0.88f, 0.40f, 0.24f,
            TapNestSwingRange.CENTERED),
        TapNestTailPreset("diagonal_dog", "🐶 斜向狗尾（单侧）",
            "身体左边或右边的斜狗尾；在原始姿势向外摇，不穿越另一侧。",
            listOf(0, 28, 57, 85), 48f, 0.46f, 0.82f, 0.48f, 0.45f,
            TapNestSwingRange.OUTWARD_ONLY),
        TapNestTailPreset("diagonal_cat", "🐱 斜向猫尾（单侧）",
            "身体左边或右边的细长斜猫尾；在同一侧柔和摇摆、保留跟随感。",
            listOf(0, 22, 44, 66, 88), 55f, 0.44f, 0.90f, 0.38f, 0.28f,
            TapNestSwingRange.OUTWARD_ONLY)
    ) + listOf(
        TapNestTailPreset("soft_ear", "🐶 柔软狗耳", "低幅度、缓慢跟随；不会改变已绑点位。",
            listOf(0, 33, 66, 100), 8f, .35f, .75f, .6f, .15f, TapNestSwingRange.CENTERED),
        TapNestTailPreset("happy_tail", "🐕 开心摇尾巴", "连续轻快摇动，尖端保留拖尾。",
            listOf(0, 33, 66, 100), 30f, .7f, .8f, .45f, .15f, TapNestSwingRange.CENTERED),
        TapNestTailPreset("soft_wing", "🪽 轻柔翅膀", "小幅柔和摆动，保留手动方向设置。",
            listOf(0, 33, 66, 100), 12f, .45f, .6f, .7f, .2f, TapNestSwingRange.CENTERED),
        TapNestTailPreset("ribbon", "🎀 飘带自然摆动", "慢速、柔软，末端自然落后。",
            listOf(0, 25, 50, 75, 100), 18f, .3f, .9f, .5f, .1f, TapNestSwingRange.CENTERED)
    )

    fun applySettings(layer: TapNestLayer, preset: TapNestTailPreset): TapNestLayer =
        layer.copy(
            idleSwing = true,
            idlePresetId = preset.id,
            swingMode = TapNestSwingMode.SPRING,
            springVersion = if (preset.id in setOf("soft_ear", "happy_tail", "soft_wing", "ribbon")) 2 else layer.springVersion,
            swingRange = preset.swingRange,
            idleAmplitude = preset.amplitude,
            idleSpeed = preset.speed,
            springSoftness = preset.softness,
            springDamping = preset.damping,
            shapePreservation = preset.preservation
        )

    /**
     * Re-sample along the user's ORIGINAL root-to-tip path. Never guess absolute
     * coordinates from a full transparent canvas, and never shift root/tip.
     * The last control point should first be placed ~85-90% into the actual
     * artwork: percentage 85/88 means the suggested position in the art,
     * NOT a fraction of the already marked endpoint.
     */
    fun repositionBones(
        existing: List<TapNestBonePoint>,
        preset: TapNestTailPreset
    ): List<TapNestBonePoint> {
        if (existing.size < 2) return existing
        val lengths = FloatArray(existing.size)
        for (i in 1 until existing.size) {
            val dx = existing[i].x - existing[i - 1].x
            val dy = existing[i].y - existing[i - 1].y
            lengths[i] = lengths[i - 1] + sqrt(dx * dx + dy * dy)
        }
        val total = lengths.last()
        if (total < 0.0001f) return existing
        return preset.bonePercentages.map { percent ->
            when (percent) {
                preset.bonePercentages.first() -> existing.first()
                preset.bonePercentages.last() -> existing.last()
                else -> {
                    val progress = percent.toFloat() / preset.bonePercentages.last()
                    val target = total * progress
                    var index = 1
                    while (index < lengths.lastIndex && lengths[index] < target) index++
                    val start = existing[index - 1]
                    val end = existing[index]
                    val part = (lengths[index] - lengths[index - 1]).coerceAtLeast(0.000001f)
                    val u = ((target - lengths[index - 1]) / part).coerceIn(0f, 1f)
                    TapNestBonePoint(
                        (start.x + (end.x - start.x) * u).coerceIn(0f, 1f),
                        (start.y + (end.y - start.y) * u).coerceIn(0f, 1f),
                        end.swingDirection
                    )
                }
            }
        }
    }
}
