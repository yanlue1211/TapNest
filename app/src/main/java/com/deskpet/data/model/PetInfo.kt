package com.deskpet.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class PetInfo(
    val id: String,
    val type: PetType,
    val displayName: String,
    val petJsonPath: String? = null,
    val spritesheetPath: String,
    val positionX: Float = 0f,
    val positionY: Float = 200f,
    val scale: Float = 1.0f,
    val sourceId: String = "", // 去重标识
    /** Per-character, on-device preset speech. Missing keys in old backups use defaults. */
    val tapBubbleEnabled: Boolean = true,
    val tapDialogues: List<String> = listOf("嘿嘿，戳到我啦！", "我在这里～", "再陪我玩一会儿？")
    // 注：曾经的 isActive 字段已删除——它是死字段（UI 判定选中用 activePetId 实时比较，
    // 从没有人读过它），且维护它导致 setActivePet 每次切换都 O(n) 重写整个列表。
    // 旧 DataStore/备份里的 "isActive" 键由 ignoreUnknownKeys=true 安全忽略。
)
