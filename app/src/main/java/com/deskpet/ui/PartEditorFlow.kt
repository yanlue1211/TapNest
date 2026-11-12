package com.deskpet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.deskpet.engine.*

internal enum class PartEditorStep { SELECT, ENABLE, TYPE, FRAME, ANCHOR, MOTION, FLEX, BONES, SUMMARY, POSITION, MATERIAL }

/** Explicit route history: Back follows the path the user actually chose. */
internal data class PartEditorFlow(
    val step: PartEditorStep = PartEditorStep.SELECT,
    val trigger: PartTrigger = PartTrigger.IDLE,
    val history: List<PartEditorStep> = emptyList()
) {
    fun next(target: PartEditorStep) = copy(step = target, history = history + step)
    fun back() = if (history.isEmpty()) this else copy(step = history.last(), history = history.dropLast(1))
}

internal enum class PartTrigger(val label: String, val description: String) {
    IDLE("⏰ 闲置抖动", "隔一段时间动一次，或换一张动作帧"),
    TAP("👆 点触互动", "被戳时摇晃，或换一张动作帧"),
    CONTINUOUS("🌿 持续轻摆", "一直缓慢摇动"),
    BLINK("😉 自动眨眼", "偶尔切换到眨眼帧");

    fun plane(layer: TapNestLayer) = when(this) {
        TAP -> layer.tapPlane
        IDLE -> layer.periodicPlane
        else -> layer.idlePlane
    }
    fun withPlane(layer: TapNestLayer, plane: TapNestMotionPlane) = when(this) {
        TAP -> layer.copy(tapPlane = plane, tapBasicConfigured = true, tapMotion = if (plane.enabled) TapNestPartMotion.NOD else layer.tapMotion)
        IDLE -> layer.copy(periodicPlane = plane, periodicBasicConfigured = true, periodicMotion = if (plane.enabled) TapNestPartMotion.NOD else layer.periodicMotion)
        else -> layer.copy(idlePlane = plane)
    }
    fun chains(layer: TapNestLayer) = when(this) {
        TAP -> layer.tapBoneChains ?: layer.boneChains
        IDLE -> layer.periodicBoneChains ?: layer.boneChains
        else -> layer.idleBoneChains ?: layer.boneChains
    }
    fun withChains(layer: TapNestLayer, chains: List<TapNestBoneChain>) = when {
        this == TAP && separate(layer) -> layer.copy(tapBoneChains = chains)
        this == IDLE && separate(layer) -> layer.copy(periodicBoneChains = chains)
        this == CONTINUOUS && separate(layer) -> layer.copy(idleBoneChains = chains)
        else -> layer.copy(boneChains = chains)
    }
    fun bones(layer: TapNestLayer): List<TapNestBonePoint> = when(this) {
        TAP -> layer.tapBones ?: layer.bones
        IDLE -> layer.periodicBones ?: layer.bones
        CONTINUOUS -> layer.idleBones ?: layer.bones
        BLINK -> layer.bones
    }
    fun separate(layer: TapNestLayer) = when(this) {
        TAP -> layer.tapBones != null
        IDLE -> layer.periodicBones != null
        CONTINUOUS -> layer.idleBones != null
        BLINK -> false
    }
    fun withSeparate(layer: TapNestLayer, enabled: Boolean): TapNestLayer = when(this) {
        TAP -> layer.copy(tapBones = if (enabled) bones(layer).toList() else null, tapBoneChains = if (enabled) chains(layer).toList() else null)
        IDLE -> layer.copy(periodicBones = if (enabled) bones(layer).toList() else null, periodicBoneChains = if (enabled) chains(layer).toList() else null)
        CONTINUOUS -> layer.copy(idleBones = if (enabled) bones(layer).toList() else null, idleBoneChains = if (enabled) chains(layer).toList() else null)
        BLINK -> layer
    }
    fun withBones(layer: TapNestLayer, points: List<TapNestBonePoint>) = when {
        this == TAP && separate(layer) -> layer.copy(tapBones = points)
        this == IDLE && separate(layer) -> layer.copy(periodicBones = points)
        this == CONTINUOUS && separate(layer) -> layer.copy(idleBones = points)
        else -> layer.copy(bones = points)
    }

    fun enabled(layer: TapNestLayer) = when(this) {
        IDLE -> layer.interactionConfigured && layer.periodicEnabled
        TAP -> layer.interactionConfigured && (layer.tapShake || layer.tapSwitchFrame)
        CONTINUOUS -> layer.idleSwing
        BLINK -> layer.interactionConfigured && layer.idleBlinkEnabled
    }
    fun setEnabled(layer: TapNestLayer, enabled: Boolean): TapNestLayer = when(this) {
        IDLE -> layer.copy(interactionConfigured = true, periodicEnabled = enabled)
        TAP -> layer.copy(interactionConfigured = true,
            tapShake = enabled && (!layer.tapSwitchFrame || layer.tapShake),
            tapSwitchFrame = enabled && layer.tapSwitchFrame)
        CONTINUOUS -> layer.copy(idleSwing = enabled)
        BLINK -> layer.copy(interactionConfigured = true, idleBlinkEnabled = enabled)
    }
    fun chooseFrame(layer: TapNestLayer): TapNestLayer = when(this) {
        TAP -> layer.copy(tapShake = false, tapSwitchFrame = true)
        IDLE -> layer.copy(periodicAction = TapNestPeriodicAction.SWITCH_FRAME, periodicSwitchFrame = false)
        else -> layer
    }
    fun chooseMotion(layer: TapNestLayer): TapNestLayer = when(this) {
        TAP -> layer.copy(tapShake = true, tapSwitchFrame = false)
        IDLE -> layer.copy(periodicAction = TapNestPeriodicAction.SHAKE, periodicSwitchFrame = false)
        else -> layer
    }
    fun setFlexible(layer: TapNestLayer, enabled: Boolean): TapNestLayer = when(this) {
        TAP -> layer.copy(tapFlexible = layer.tapFlexible.copy(enabled = enabled))
        IDLE -> layer.copy(periodicFlexible = layer.periodicFlexible.copy(enabled = enabled))
        CONTINUOUS -> if (enabled) layer.copy(idleFlexibleEnabled = true, swingMode = TapNestSwingMode.SPRING, springVersion = 2) else layer.copy(idleFlexibleEnabled = false)
        BLINK -> layer
    }
}

@Composable
internal fun PartFlowHeader(flow: PartEditorFlow) {
    Text(if (flow.step == PartEditorStep.SELECT) "当前部位 · 动作设置" else flow.trigger.label,
        style = MaterialTheme.typography.titleMedium)
    Text(when(flow.step) {
        PartEditorStep.SELECT -> "选择动作 → 是否开启 → 怎么动 → 保存"
        PartEditorStep.ENABLE -> "是否开启"
        PartEditorStep.TYPE -> "选择动作表现"
        PartEditorStep.FRAME -> "上传或选择目标帧"
        PartEditorStep.ANCHOR -> "放置固定锚点"
        PartEditorStep.MOTION -> "方向、幅度与速度"
        PartEditorStep.FLEX -> "是否需要柔性弯曲"
        PartEditorStep.BONES -> "骨链与柔性效果"
        PartEditorStep.SUMMARY -> "测试并保存"
        PartEditorStep.POSITION -> "位置与大小"
        PartEditorStep.MATERIAL -> "素材与名称"
    }, style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun FlowChoice(title: String, description: String, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$title →", style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun PartMotionSettings(layer: TapNestLayer, trigger: PartTrigger, update: (TapNestLayer) -> Unit) {
    val current = if (trigger == PartTrigger.TAP) layer.tapMotion else layer.periodicMotion ?: layer.tapMotion
    Text("选择摇晃方式")
    TapNestPartMotion.entries.forEach { motion ->
        FilterChip(current == motion, {
            update(if (trigger == PartTrigger.TAP) layer.copy(tapMotion = motion) else layer.copy(periodicMotion = motion))
        }, label = { Text(when(motion) {
            TapNestPartMotion.NOD -> "左右摇晃"
            TapNestPartMotion.BOUNCE -> "上下弹跳"
            TapNestPartMotion.FLAP -> "扇动"
            TapNestPartMotion.TWITCH -> "快速轻抖"
            TapNestPartMotion.HAT_POP -> "帽子弹飞"
        }) })
    }
    if (current == TapNestPartMotion.HAT_POP) BindingSlider("弹飞高度", layer.hatFlightHeight, .08f, .4f) {
        update(layer.copy(hatFlightHeight = it))
    }

}
