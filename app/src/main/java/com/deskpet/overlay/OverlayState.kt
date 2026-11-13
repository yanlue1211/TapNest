package com.deskpet.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 悬浮窗运行状态（进程内共享）。
 *
 * 之前 MainActivity 用一个 `remember` 的 Boolean 记录是否在运行，
 * 进程/Activity 重建后就会和真实服务状态失联（宠物还浮着，界面却显示"开启"）。
 * 现在由服务自己发布状态，UI 只负责消费。
 */
object OverlayState {
    private val _petId = MutableStateFlow<String?>(null)
    val petId: StateFlow<String?> = _petId.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** The companion screen request survives Activity recreation within this process. */
    private val _settingsPetId = MutableStateFlow<String?>(null)
    val settingsPetId: StateFlow<String?> = _settingsPetId.asStateFlow()
    fun openCompanion(id: String) { _settingsPetId.value = id }
    fun closeCompanion() { _settingsPetId.value = null }

    data class CompanionActionEvent(
        val petId: String,
        val action: com.deskpet.data.CompanionAction,
        val line: String,
        val affection: Int,
        val emoji: String = ""
    )
    private val _interactions = kotlinx.coroutines.flow.MutableSharedFlow<CompanionActionEvent>(
        extraBufferCapacity = 16)
    val interactions = _interactions
    fun postInteraction(petId: String, action: com.deskpet.data.CompanionAction,
                        line: String, affection: Int, emoji: String = "") {
        _interactions.tryEmit(CompanionActionEvent(petId, action, line, affection, emoji))
    }

    /** The native dialog can hand a completed reply to the visible desktop pet.
     * Only text and the pet id cross this in-process bus; tokens never do. */
    data class GptReplyEvent(val petId: String, val reply: String)
    private val _gptReplies = kotlinx.coroutines.flow.MutableSharedFlow<GptReplyEvent>(
        extraBufferCapacity = 4
    )
    val gptReplies = _gptReplies.asSharedFlow()
    fun postGptReply(petId: String, reply: String) {
        if (reply.isNotBlank()) _gptReplies.tryEmit(GptReplyEvent(petId, reply))
    }

    /**
     * Tiny keyboard Activity hands text to the already-running desktop pet service
     * before disappearing. The service, not the Activity, owns the model call.
     * Returning false keeps the user's draft intact.
     */
    private var petChatSend: ((String, String) -> Boolean)? = null

    @androidx.annotation.MainThread
    fun registerPetChatSender(sender: (String, String) -> Boolean) {
        petChatSend = sender
    }

    @androidx.annotation.MainThread
    fun clearPetChatSender() {
        petChatSend = null
    }

    @androidx.annotation.MainThread
    fun submitPetChat(petId: String, text: String): Boolean =
        petChatSend?.invoke(petId, text) == true

    fun setRunning(id: String?) {
        _petId.value = id
        _running.value = id != null
    }
}
