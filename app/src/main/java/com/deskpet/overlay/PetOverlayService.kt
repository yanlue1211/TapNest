package com.deskpet.overlay

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.app.PendingIntent
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.View
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.deskpet.R
import com.deskpet.data.PetRepository
import com.deskpet.data.model.PetInfo
import com.deskpet.data.model.TapDialogue
import com.deskpet.data.model.DragDirection
import com.deskpet.data.model.PetAnimationState
import com.deskpet.engine.*
import com.deskpet.gpt.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlin.math.abs

class PetOverlayService : LifecycleService(), ViewModelStoreOwner, SavedStateRegistryOwner {

    override val viewModelStore = ViewModelStore()
    override val savedStateRegistry = SavedStateRegistryController.create(this).also { it.performRestore(null) }.savedStateRegistry

    private lateinit var wm: WindowManager
    private var cv: View? = null
    private lateinit var repo: PetRepository
    private lateinit var engine: PetEngine
    private lateinit var anim: AnimationController
    private lateinit var gesture: GestureHandler
    private lateinit var parser: SpritesheetParser
    private var auto: AutonomousBehavior? = null
    private var speechBubble: TapSpeechBubble? = null
    private var speechJob: Job? = null
    private lateinit var companionStore: com.deskpet.data.CompanionStore
    private var reminderJob: Job? = null
    private var floatingPanel: CompanionFloatingPanel? = null
    private var formPanel: FormFloatingPanel? = null
    private var actionPanel: PetQuickActionPanel? = null
    private var gptPanel: GptPetChatPanel? = null
    private var gptModelList: List<GptModel> = emptyList()
    private var gptModelsFor: String? = null
    private var gptSending = false
    private var gptTapAt = 0L
    private val gptPrefs by lazy { GptPetPreferences(this) }
    private val gptCredentials by lazy { GptCredentials(this) }
    private val gptOAuth by lazy { GptOAuth(gptCredentials) }
    private val gptApi by lazy { GptChatApi(gptOAuth) }
    private var formSwitchJob: Job? = null
    private var currentPet: PetInfo? = null
    private var touchLineIndex = 0
    private var petId: String? = null
    private var job: Job? = null
    private var scaleJob: Job? = null
    /** 捏合缩放回写 DataStore 的防抖任务 */
    private var scaleSyncJob: Job? = null
    private var wx = 0f
    private var wy = 200f
    private var savedScale = 1f
    // CoroutineExceptionHandler 是必须的：SupervisorJob 只隔离子协程间的失败，
    // 不吞异常本身——漏网异常会走线程默认处理器直接崩掉前台服务进程。
    // 加上后降级为记日志，宠物最多停一拍而不是整个 App"已停止"。
    private val sc = CoroutineScope(
        Dispatchers.Main + SupervisorJob() + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Uncaught exception in overlay scope", e)
        }
    )

    /** 亮灭屏状态：省电广播到来时需要据此决定是否恢复自主行为（灭屏时不能开） */
    @Volatile private var screenOn = true

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) {
            // onReceive 不在协程作用域里，这里抛异常会直接崩进程，整体兜一层
            try {
                handleScreenBroadcast(i)
            } catch (e: Exception) {
                Log.e(TAG, "screen broadcast handling failed", e)
            }
        }

        private fun handleScreenBroadcast(i: Intent?) {
            if (!::anim.isInitialized) return
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            when (i?.action) {
                // 灭屏把动画循环和自主行为一起停，否则 auto 会在灭屏期间切状态，
                // 亮屏后宠物会播放一段"没来由"的动作
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    anim.setLayeredPickedUp(false)
                    anim.stop()
                    (cv as? Live2DOverlayView)?.pauseRenderer()
                    auto?.stop()
                    speechJob?.cancel()
                    speechBubble?.dismiss()
                    gptPanel?.dismiss()
                    gptPanel = null
                    actionPanel?.dismiss()
                    actionPanel = null
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    anim.start()
                    (cv as? Live2DOverlayView)?.resumeRenderer()
                    // 省电模式下自主乱跑属于"非必要活动"，不开
                    if (!pm.isPowerSaveMode) auto?.start()
                }
                // 系统省电开关切换（官方 isPowerSaveMode + 广播的标准联动）：
                // 帧率减半交给 AnimationController.powerSaveMode；自主行为
                // 按需停/恢复（恢复仅限亮屏状态）
                android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    anim.powerSaveMode = pm.isPowerSaveMode
                    if (pm.isPowerSaveMode) auto?.stop()
                    else if (screenOn) auto?.start()
                }
            }
        }
    }

    private fun onDrag(dx: Float, dy: Float, d: DragDirection) {
        wx += dx
        wy += dy
        updatePosition()
        if (anim.isCodexPet()) {
            anim.switchState(gesture.mapDirectionToState(d))
        }
        auto?.onUserTouch()
    }

    private var companionAnimationJob: Job? = null
    private fun cancelCompanionAnimation() {
        companionAnimationJob?.cancel()
        companionAnimationJob = null
        anim.cancelInteraction()
    }
    private fun animateCompanion(id: String, action: com.deskpet.data.CompanionAction, emoji: String, artwork: String?) {
        cancelCompanionAnimation()
        companionAnimationJob = sc.launch {
            val profile = withContext(Dispatchers.IO) { companionStore.load(id) }
            if (id != petId) return@launch
            anim.interactCompanion(action, emoji,
                profile.interactionAnimations[action] ?: com.deskpet.data.InteractionAnimationSettings(),
                companionStore.actionVisualFile(id, action, artwork ?: profile.actionVisuals[action]))
        }
    }

    private fun onTap() {
        cancelCompanionAnimation()
        if (anim.isCodexPet()) {
            anim.switchState(PetAnimationState.WAVING)
        } else {
            if (anim.isLayeredPet()) anim.tapLayeredPet()
            bounce()
        }
        auto?.onUserTouch()
        val pet = currentPet
        if (pet != null && gptPrefs.aiForPet(pet.id) &&
            android.os.SystemClock.elapsedRealtime() - gptTapAt >= 10_000L) {
            gptTapAt = android.os.SystemClock.elapsedRealtime()
            dispatchGptMessage("【桌宠互动】用户刚刚轻轻戳了你一下。请用当前形态的口气自然回应一句话，别把它说成工作报告。", isEvent = true)
        }
        if (pet != null && pet.tapBubbleEnabled) {
            val lines = anim.tapDialogues(pet.tapDialogues)
            val line = TapDialogue.at(lines, touchLineIndex)
            if (line != null) {
                touchLineIndex = (touchLineIndex + 1) % lines.size
                displayTapLine(line)
            }
        }
    }

    private fun onHoldClose() {
        // Closing is explicit. Preserve stored forms, chat and credentials.
        actionPanel?.dismiss(); actionPanel = null
        gptPanel?.dismiss(); gptPanel = null
        floatingPanel?.dismiss(); floatingPanel = null
        formPanel?.dismiss(); formPanel = null
        repo.setOverlayAutoResumeAsync(false)
        stopSelf()
    }

    /** 1–5 second stationary hold: choose actions first instead of piling up options. */
    private fun onHoldMenu() {
        val pet = currentPet ?: return
        floatingPanel?.dismiss(); floatingPanel = null
        formPanel?.dismiss(); formPanel = null
        gptPanel?.dismiss(); gptPanel = null
        actionPanel?.dismiss()
        actionPanel = PetQuickActionPanel(this, wm, this, this, this,
            onWardrobe = {
                actionPanel?.dismiss(); actionPanel = null
                openWardrobe()
            },
            onLife = {
                actionPanel?.dismiss(); actionPanel = null
                openCompanionActions()
            },
            onChat = {
                actionPanel?.dismiss(); actionPanel = null
                openGptPanel()
            },
            onAiChange = { enabled -> gptPrefs.setAiForPet(pet.id, enabled) },
            onClose = { actionPanel?.dismiss(); actionPanel = null }
        ).also { it.show(wx, wy, anim.petWidth.value, anim.petHeight.value, safeBounds(),
            showWardrobe = anim.isLayeredPet(), aiEnabled = gptPrefs.aiForPet(pet.id)) }
    }

    /** Keep the original wardrobe and all its per-part selectors unchanged. */
    private fun openWardrobe() {
        val pet = currentPet ?: return
        if (!anim.isLayeredPet()) return
        runCatching {
            val forms = com.deskpet.data.TapNestFormStore(this).load(pet)
            if (forms.forms.size <= 1 &&
                com.deskpet.engine.TapNestForms.availableParts(forms).isEmpty()) return
            floatingPanel?.dismiss(); floatingPanel = null
            formPanel?.dismiss()
            formPanel = FormFloatingPanel(this, wm, this, this, this).also { panel ->
                panel.show(forms, wx, wy, anim.petWidth.value, anim.petHeight.value, safeBounds(),
                    onForm = { formId -> switchForm(pet, formId, null, null) },
                    onPart = { category, formId -> switchForm(pet, null, category, formId) },
                    onClose = { panel.dismiss(); if (formPanel === panel) formPanel = null })
            }
        }.onFailure { Log.w(TAG, "outfit menu failed", it) }
    }

    private fun switchForm(pet: PetInfo, formId: String?, category: String?, source: String?) {
        cancelCompanionAnimation()
        formSwitchJob?.cancel()
        formSwitchJob = sc.launch {
            try {
                // Preserve the precise on-screen location before reloading the new rig.
                repo.savePosition(pet.id, wx, wy, savedScale)
                withContext(Dispatchers.IO) {
                    val store = com.deskpet.data.TapNestFormStore(this@PetOverlayService)
                    if (formId != null) store.setActive(pet, formId)
                    else if (category != null) store.setPart(pet, category, source)
                }
                formPanel?.dismiss()
                formPanel = null
                if (petId == pet.id) {
                    if (!anim.replaceLayered(pet)) error("形态素材加载失败")
                    updatePosition()
                    if (formId != null) {
                        touchLineIndex = 0
                        speechJob?.cancel()
                        speechBubble?.dismiss()
                    }
                    if (!screenOn) anim.stop()
                    val profile = withContext(Dispatchers.IO) { companionStore.load(pet.id) }
                    anim.setAffection(com.deskpet.data.CompanionAffection.at(profile, System.currentTimeMillis()))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.w(TAG, "form switch failed", e)
                Toast.makeText(this@PetOverlayService,
                    e.message ?: "形态切换失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Double tap now opens an actual keyboard/chat bubble above other apps. */
    private fun onDoubleTap() = openGptPanel()

    /** Original feed / wash / play panel is still available from the long-hold menu. */
    private fun openCompanionActions() {
        val id = petId ?: return
        val person = currentPet ?: return
        formPanel?.dismiss(); formPanel = null
        floatingPanel?.dismiss()
        floatingPanel = CompanionFloatingPanel(
            this, wm, this, this, this, companionStore,
            onAction = { action, line, affection, artwork, emoji ->
                anim.setAffection(affection)
                displayTapLine(line)
                animateCompanion(id, action, emoji, artwork)
                if (gptPrefs.aiForPet(id)) {
                    val item = when (action) {
                        com.deskpet.data.CompanionAction.FEED -> "喂你吃了 ${emoji.ifBlank { "小零食" }}"
                        com.deskpet.data.CompanionAction.BATH -> "给你洗了澡"
                        com.deskpet.data.CompanionAction.PLAY -> "拿着 ${emoji.ifBlank { "玩具" }} 陪你玩"
                    }
                    dispatchGptMessage(
                        "【桌宠互动】用户刚刚${item}。你已经完成了桌宠动作。用当前形态自然回应一到两句话，不要重复说明动画过程。",
                        isEvent = true
                    )
                }
            },
            onGptChat = ::openGptPanel,
            onClose = {
                floatingPanel?.dismiss()
                floatingPanel = null
            }
        ).also {
            it.show(id, person.displayName, wx, wy,
                anim.petWidth.value, anim.petHeight.value, safeBounds())
        }
    }

    /** Launchable full screen is for first-time account authorization and viewing full chat history. */
    private fun openGptAccount() {
        gptPanel?.dismiss(); gptPanel = null
        runCatching {
            startActivity(Intent(this, com.deskpet.gpt.GptCompanionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(com.deskpet.gpt.GptCompanionActivity.EXTRA_PET_ID, petId))
        }.onFailure { Log.w(TAG, "GPT Activity could not open", it) }
    }

    private fun openGptPanel() {
        val id = petId ?: return
        actionPanel?.dismiss(); actionPanel = null
        floatingPanel?.dismiss(); floatingPanel = null
        formPanel?.dismiss(); formPanel = null
        gptPanel?.dismiss()
        val panel = GptPetChatPanel(this, wm, this, this, this,
            onSend = { text, model -> dispatchGptMessage(text, model) },
            onModel = { slug ->
                sc.launch {
                    val subject = withContext(Dispatchers.IO) { gptCredentials.read()?.subject }
                    if (subject != null) gptPrefs.saveModel(subject, slug)
                }
            },
            onAccount = ::openGptAccount,
            onHistory = ::openGptAccount,
            onClose = { gptPanel?.dismiss(); gptPanel = null }
        )
        gptPanel = panel
        panel.show(wx, anim.petWidth.value, safeBounds())
        if (gptSending) panel.updateBusy(true)
        sc.launch {
            try {
                val session = withContext(Dispatchers.IO) { gptCredentials.read() }
                if (gptPanel !== panel || petId != id) return@launch
                panel.updateMode(withContext(Dispatchers.IO) {
                    GptStyle.current(this@PetOverlayService, id)
                })
                if (session == null || !session.allowed) {
                    panel.requireLogin()
                    return@launch
                }
                panel.setLoading()
                val snapshot = withContext(Dispatchers.IO) {
                    GptHistory(this@PetOverlayService, session.subject).load().takeLast(2)
                }
                if (gptPanel !== panel || petId != id) return@launch
                panel.updateHistory(snapshot.firstOrNull { it.role == "user" }?.text.orEmpty(),
                    snapshot.lastOrNull { it.role == "assistant" }?.text.orEmpty())
                val choices = withContext(Dispatchers.IO) {
                    if (gptModelsFor == session.subject && gptModelList.isNotEmpty()) gptModelList
                    else gptApi.models()
                }
                if (gptPanel !== panel || petId != id) return@launch
                gptModelList = choices
                gptModelsFor = session.subject
                val saved = gptPrefs.selectedModel(session.subject)
                val chosen = saved.takeIf { key -> choices.any { it.slug == key } }
                    ?: choices.firstOrNull()?.slug.orEmpty()
                panel.updateModels(choices, chosen)
                if (gptSending) panel.updateBusy(true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (gptPanel === panel) {
                    panel.fail(GptFriendlyErrors.text(e))
                    Log.w(TAG, "GPT floating panel could not load account/models", e)
                }
            }
        }
    }

    /** Single-flight GPT request shared by direct chat and opt-in physical interactions. */
    private fun dispatchGptMessage(raw: String, chosenModel: String = "", isEvent: Boolean = false): Boolean {
        val id = petId ?: return false
        val prompt = raw.trim().take(4000)
        if (prompt.isBlank() || gptSending) return false
        // Reserve immediately on UI thread; rapid taps cannot race before first suspension.
        gptSending = true
        gptPanel?.updateBusy(true)
        sc.launch {
            var acquired: String? = null
            try {
                val session = withContext(Dispatchers.IO) { gptCredentials.read() }
                    ?: throw IllegalStateException("请先在 GPT 聊天页面登录 ChatGPT")
                check(session.allowed) { "ChatGPT 账号没有授权使用订阅额度" }
                check(GptRequestGate.tryAcquire(session.subject)) { "另一处 GPT 对话正在回复，请等这轮结束" }
                acquired = session.subject
                val model = withContext(Dispatchers.IO) {
                    if (chosenModel.isNotBlank()) chosenModel
                    else {
                        val options = if (gptModelsFor == session.subject && gptModelList.isNotEmpty())
                            gptModelList else gptApi.models()
                        if (options.isEmpty()) error("暂时没有可用的 GPT 模型")
                        gptModelList = options
                        gptModelsFor = session.subject
                        val selected = gptPrefs.selectedModel(session.subject)
                        selected.takeIf { value -> options.any { it.slug == value } } ?: options.first().slug
                    }
                }
                val style = withContext(Dispatchers.IO) { GptStyle.current(this@PetOverlayService, id) }
                val recent = withContext(Dispatchers.IO) {
                    GptHistory(this@PetOverlayService, session.subject).load()
                }
                if (!isEvent && petId == id && screenOn) displayTapLine("我听见啦，正在想…", 6000)
                var lastUpdate = 0L
                val reply = withContext(Dispatchers.IO) {
                    gptApi.reply(model, GptStyle.instructions(style), recent, prompt) { streamed ->
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastUpdate > 160L) {
                            lastUpdate = now
                            sc.launch(Dispatchers.Main.immediate) {
                                if (petId == id) gptPanel?.updatePartial(streamed)
                            }
                        }
                    }
                }
                withContext(Dispatchers.IO) {
                    GptHistory(this@PetOverlayService, session.subject).append(prompt, reply)
                }
                if (petId == id) {
                    gptPanel?.complete(prompt, reply)
                    if (screenOn) showGptReply(reply, id)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.w(TAG, "GPT companion message failed", e)
                if (petId == id) {
                    val msg = GptFriendlyErrors.text(e)
                    gptPanel?.fail(msg)
                    if (screenOn && gptPanel == null) displayTapLine(msg, 6000)
                }
            } finally {
                acquired?.let(GptRequestGate::release)
                gptSending = false
                if (petId == id) gptPanel?.updateBusy(false)
            }
        }
        return true
    }

    /** Complete GPT replies are stored in history; the noninteractive pet bubble pages them. */
    private fun showGptReply(reply: String, id: String) {
        speechJob?.cancel()
        val bubble = speechBubble ?: TapSpeechBubble(this, wm).also { speechBubble = it }
        speechJob = sc.launch {
            try {
                for (part in GptBubbleSlices.split(reply)) {
                    if (!screenOn || petId != id) break
                    bubble.show(part)
                    bubble.follow(wx, wy + bounceOffset,
                        anim.petWidth.value, anim.petHeight.value, safeBounds())
                    delay((1800L + part.length * 90L).coerceIn(2500L, 6500L))
                }
            } finally {
                if (petId == id) bubble.dismiss()
            }
        }
    }

    private fun displayTapLine(line: String, durationMs: Long = 3200L) {
        speechJob?.cancel()
        val bubble = speechBubble ?: TapSpeechBubble(this, wm).also { speechBubble = it }
        bubble.show(line)
        bubble.follow(wx, wy + bounceOffset, anim.petWidth.value, anim.petHeight.value, safeBounds())
        speechJob = sc.launch {
            delay(durationMs)
            bubble.dismiss()
        }
    }

    private fun onScale(zoom: Float, focusX: Float, focusY: Float) {
        // 与 AnimationController / UI 滑块保持一致：50%~500%
        val ns = (savedScale * zoom).coerceIn(0.2f, 5f)
        // clamp 截断后实际生效的比例，位置补偿必须用它，否则锚点会漂
        val applied = if (savedScale > 0f) ns / savedScale else 1f
        val canvasFocusY = focusY - anim.renderTopInset
        savedScale = ns
        // 先更新尺寸（petWidth 状态同步生效），后续 clampPosition 才能用新尺寸
        // 把放大后的窗口夹回屏幕内，而不是放行一帧溢出
        anim.setScale(ns)
        (cv as? Live2DOverlayView)?.resize(anim.petWidth.value, anim.petHeight.value)
        // 捏合缩放同步回 DataStore（防抖落盘，捏合过程中不写）：否则列表页
        // 滑块还显示旧值，之后任意一次滑块微调会让宠物从捏合后的大小"瞬移"
        // 回滑块值。observeScale 收到回写后差值≈0 不会回环
        val pid = petId
        if (pid != null && applied != 1f) {
            scaleSyncJob?.cancel()
            scaleSyncJob = sc.launch {
                delay(400)
                try {
                    repo.setScale(pid, savedScale)
                } catch (e: Exception) {
                    Log.w(TAG, "scale sync failed", e)
                }
            }
        }
        if (applied != 1f) {
            // 以双指中心（centroid）为锚点缩放：窗口尺寸从 W 变为 W*applied 时，
            // 把窗口左上角反向平移 focus*(1-applied)，手指夹住的点保持不动。
            // 旧实现只改大小不补位置，放大时宠物朝右下"逃"、缩小时朝左上"缩"。
            wx += focusX * (1f - applied)
            wy += canvasFocusY * (1f - applied)
            updatePosition()
        }
        auto?.onUserTouch()
    }

    /** 手势结束时立刻落盘，避免进程被杀导致位置丢失 */
    private fun persistPositionSoon() {
        if (petId == null) return
        sc.launch {
            try {
                withTimeout(2000L) { persistPosition() }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save position", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        repo = (application as com.deskpet.DeskPetApp).petRepository
        companionStore = com.deskpet.data.CompanionStore(this)
        sc.launch {
            OverlayState.interactions.collect { event ->
                if (event.petId == petId) {
                    anim.setAffection(event.affection)
                    displayTapLine(event.line)
                    animateCompanion(event.petId, event.action, event.emoji, null)
                }
            }
        }
        parser = SpritesheetParser()
        engine = PetEngine(parser, GifRenderer())
        anim = AnimationController(engine, sc)
        gesture = GestureHandler(
            onDrag = ::onDrag,
            onTap = ::onTap,
            onDoubleTap = ::onDoubleTap,
            onHoldMenu = ::onHoldMenu,
            onHoldClose = ::onHoldClose,
            onPhysicalTap = { anim.recordLayeredPhysicalTap() },
            isCodexPet = anim::isCodexPet,
            onScale = ::onScale,
            onDragStart = {
                cancelCompanionAnimation()
                anim.setLayeredPickedUp(true)
                auto?.onUserTouch()
            },
            onDragEnd = {
                anim.setLayeredPickedUp(false)
                persistPositionSoon()
            }
        )
        registerReceiverSafely()
        startForeground()
    }

    /**
     * targetSdk 34+ 要求动态注册 receiver 时声明 exported 标志；
     * SCREEN_ON/OFF 是系统 protected broadcast，用 NOT_EXPORTED 即可正常收到。
     */
    private fun registerReceiverSafely() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, screenReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // 注册时同步一次省电/亮灭屏状态：服务可能在省电已开启或灭屏期间才启动
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (::anim.isInitialized) {
            anim.powerSaveMode = pm.isPowerSaveMode
        }
        // START_STICKY 可能在灭屏期间拉起服务，此时不会有 SCREEN_OFF 广播到来，
        // 不主动查一次就会在锁屏上白跑动画（loadPet 据此决定是否立即启动）
        screenOn = pm.isInteractive
    }

    private fun startForeground() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(android.app.NotificationChannel(
                CHANNEL_ID, "点点栖", android.app.NotificationManager.IMPORTANCE_LOW
            ).apply { description = "宠物悬浮窗运行中" })

            val contentIntent = android.app.PendingIntent.getActivity(
                this, 0,
                Intent(this, com.deskpet.MainActivity::class.java).apply {
                    // NEW_TASK 保证服务里能启动 Activity；CLEAR_TOP|SINGLE_TOP
                    // 复用已存在的实例走 onNewIntent——否则应用开着时点通知，
                    // 返回栈里会再叠一个重复的列表页
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                },
                android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val ntf = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("点点栖")
                .setContentText("正在运行")
                // 自有白色剪影小图标（状态栏/通知栏单色规范）；旧代码用的
                // android.R.drawable.ic_menu_gallery 是系统图库图标占位
                .setSmallIcon(R.drawable.ic_tapnest_stat)
                // 通知栏展开时显示彩色橘团大图（状态栏小图标按规范只能是单色剪影）
                .setContentIntent(contentIntent)
                .setOngoing(true)
                // 通知栏直接关闭宠物，不用回到 App 点「关闭」
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "关闭宠物",
                    PendingIntent.getService(
                        this, 1,
                        Intent(this, PetOverlayService::class.java).setAction(ACTION_STOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFY_ID, ntf, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFY_ID, ntf)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
            Toast.makeText(this@PetOverlayService, "前台服务失败: ${e.message}", Toast.LENGTH_LONG).show()
            // 只 catch 不停服没用：系统 5 秒超时后仍会抛 RemoteServiceException
            // （"did not then call startForeground"）直接崩溃，必须主动止损
            stopSelf()
        }
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        // LifecycleService 需要 super 来派发生命周期事件（否则 lifecycle 一直停在 CREATED）
        super.onStartCommand(i, f, s)
        // 通知栏「关闭宠物」按钮：这是用户主动关闭，要清掉自动恢复标志，
        // 否则下次打开 App 宠物会"复活"（违背用户刚才的关闭意图）
        if (i?.action == ACTION_STOP) {
            repo.setOverlayAutoResumeAsync(false)
            stopSelf()
            return START_NOT_STICKY
        }
        job?.cancel()
        job = sc.launch {
            try {
                val id = i?.getStringExtra(EXTRA_PET_ID) ?: repo.getActivePetId().first()
                if (id.isNullOrBlank()) {
                    Log.w(TAG, "No pet id to show")
                    stopSelf()
                    return@launch
                }
                show(id)
            } catch (e: CancellationException) {
                // 被新的启动请求取消（快速连续切换宠物）属正常流程：
                // 新协程会接管加载。绝不能在这里 stopSelf，否则会把
                // 正在加载新宠物的服务整个停掉（宠物消失）
                return@launch
            } catch (e: Exception) {
                Log.e(TAG, "show failed", e)
                Toast.makeText(this@PetOverlayService, "启动失败: ${e.message}", Toast.LENGTH_LONG).show()
                OverlayState.setRunning(null)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // 异步落盘，不用 runBlocking 阻塞主线程（最多 2s 的 ANR 余量风险）；
        // 手势结束时的同步保存已经兜底了主要的数据丢失路径
        persistPositionAsync()
        OverlayState.setRunning(null)
        scaleJob?.cancel()
        cancelCompanionAnimation()
        formSwitchJob?.cancel()
        formPanel?.dismiss()
        formPanel = null
        actionPanel?.dismiss()
        actionPanel = null
        gptPanel?.dismiss()
        gptPanel = null
        removeOverlayView()
        if (::anim.isInitialized) anim.stop()
        auto?.stop()
        speechJob?.cancel()
        reminderJob?.cancel()
        floatingPanel?.dismiss()
        floatingPanel = null
        formPanel?.dismiss()
        formPanel = null
        speechBubble?.dismiss()
        speechBubble = null
        currentPet = null
        if (::engine.isInitialized) engine.release()
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister screen receiver", e)
        }
        viewModelStore.clear()
        sc.cancel()
        super.onDestroy()
    }

    /** Manifest 里 stopWithTask="false"（悬浮窗应用标准做法）：划掉最近任务
     *  只关 App 界面，本服务带着宠物继续显示，本方法在划掉时回调——只做
     *  位置落盘这类收尾，绝不 stopSelf（否则配置白改、宠物照样消失）。
     *  关闭路径 = 通知栏「关闭宠物」/ 收纳坞开关 / 用户强停。 */
    override fun onTaskRemoved(r: Intent?) {
        super.onTaskRemoved(r)
        persistPositionAsync()
    }

    /** 在独立 IO 作用域异步保存位置，服务销毁后仍能完成写入 */
    private fun persistPositionAsync() {
        petId?.let { repo.savePositionAsync(it, wx, wy, savedScale) }
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        val p = displaySize()
        if (wx > p.x || wy > p.y) {
            wx = 100f
            wy = 100f
        }
        clampPosition()
        updatePosition()
    }

    private suspend fun persistPosition() {
        val id = petId ?: return
        repo.savePosition(id, wx, wy, savedScale)
    }

    private suspend fun show(id: String) {
        // 权限可能在运行期间被用户撤销（撤销时系统会立刻移除悬浮窗但服务还在）：
        // START_STICKY 重启或切换宠物时会走到这里，先快速失败，不做无谓的加载 IO
        if (!Settings.canDrawOverlays(this)) {
            OverlayState.setRunning(null)
            stopSelf()
            return
        }
        val info = withContext(Dispatchers.IO) { repo.getPet(id) }
        if (info == null) {
            Log.w(TAG, "Pet not found: $id")
            Toast.makeText(this, "宠物不存在，请重新选择", Toast.LENGTH_SHORT).show()
            OverlayState.setRunning(null)
            stopSelf()
            return
        }
        // 切换宠物前保存上一只的位置
        if (petId != null && petId != id) {
            try {
                withTimeout(2000L) { persistPosition() }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save previous pet position", e)
            }
        }
        speechJob?.cancel()
        reminderJob?.cancel()
        floatingPanel?.dismiss()
        floatingPanel = null
        actionPanel?.dismiss()
        actionPanel = null
        gptPanel?.dismiss()
        gptPanel = null
        speechBubble?.dismiss()
        currentPet = info
        touchLineIndex = 0
        petId = id
        wx = info.positionX
        wy = info.positionY
        // 解析 pet.json / 读取图片尺寸都是磁盘 IO，必须离开主线程
        val loaded = withContext(Dispatchers.IO) { anim.reload(info) }
        if (!loaded) {
            Toast.makeText(this, "宠物加载失败，文件可能已损坏", Toast.LENGTH_SHORT).show()
            OverlayState.setRunning(null)
            stopSelf()
            return
        }
        anim.setScale(info.scale)
        savedScale = info.scale
        val savedProfile = withContext(Dispatchers.IO) { companionStore.load(id) }
        anim.setAffection(com.deskpet.data.CompanionAffection.at(savedProfile, System.currentTimeMillis()))
        reminderJob?.cancel()
        reminderJob = sc.launch {
            while (isActive && petId == id) {
                if (screenOn) {
                    val due = withContext(Dispatchers.IO) { companionStore.due(id) }
                    for (note in due) {
                        displayTapLine("⏰ " + note.message, 7500L)
                    }
                    val refreshed = companionStore.load(id)
                    anim.setAffection(com.deskpet.data.CompanionAffection.at(
                        refreshed, System.currentTimeMillis()))
                }
                delay(10_000L)
            }
        }
        // 灭屏期间被系统拉起时不启动动画，等 SCREEN_ON 广播再开（registerReceiverSafely 已同步 screenOn）
        if (screenOn) anim.start()
        // 取消是协作式的：从最后一个挂起点返回到 add() 之间没有检查点，
        // 极速连点时旧协程可能带着"已过期"的宠物把视图加进窗口，
        // 造成 OverlayState 与屏幕实际显示不一致
        currentCoroutineContext().ensureActive()
        if (!add()) {
            OverlayState.setRunning(null)
            // 不停服会留下"正在运行"的通知和常驻前台服务，但宠物根本没显示
            stopSelf()
            return
        }
        observeScale(id)
        auto?.stop()
        auto = if (anim.isLayeredPet() || anim.isLive2DPet()) null
            else AutonomousBehavior(anim, sc)
        // 亮屏且非省电才启动自主行为；灭屏/省电场景由广播负责恢复
        if (screenOn && !anim.powerSaveMode) auto?.start()
        OverlayState.setRunning(id)
    }

    /** 悬浮窗运行期间跟随仓库里的缩放值（界面上的缩放滑块）*/
    private fun observeScale(id: String) {
        scaleJob?.cancel()
        scaleJob = sc.launch {
            repo.getAllPets().collect { pets ->
                val info = pets.find { it.id == id } ?: return@collect
                currentPet = info
                anim.setAffection(com.deskpet.data.CompanionAffection.at(
                    companionStore.load(id), System.currentTimeMillis()))
                if (!info.tapBubbleEnabled || anim.tapDialogues(info.tapDialogues).isEmpty()) {
                    speechJob?.cancel()
                    speechBubble?.dismiss()
                }
                if (abs(info.scale - savedScale) > 0.001f) {
                    savedScale = info.scale
                    anim.setScale(info.scale)
                    (cv as? Live2DOverlayView)?.resize(anim.petWidth.value, anim.petHeight.value)
                    updatePosition() // bubble and pet stay anchored after toolbar scaling
                }
            }
        }
    }

    @android.annotation.SuppressLint("RtlHardcoded")
    private fun add(): Boolean {
        if (!::wm.isInitialized) return false
        if (!Settings.canDrawOverlays(this)) {
            // 权限引导由 MainActivity 负责（它有 pendingStart + ON_RESUME 重试的完整流程）。
            // 这里不要跳设置页：Android 10+ 服务在"无权限"状态下启动 Activity 会被
            // 系统静默拦截（有 SYSTEM_ALERT_WINDOW 的 App 才豁免），跳了也白跳。
            return false
        }
        speechJob?.cancel()
        speechBubble?.dismiss()
        removeOverlayView()
        cv = if (anim.isLive2DPet()) {
            val manifest = currentPet?.petJsonPath?.let { java.io.File(it) }
                ?: return false
            try {
                Live2DOverlayView(
                    context = this,
                    manifest = manifest,
                    width = anim.petWidth.value, height = anim.petHeight.value,
                    onTap = ::onTap,
                    onDoubleTap = ::onDoubleTap,
                    onDrag = { dx, dy ->
                        onDrag(dx, dy, if (dx >= 0) DragDirection.RIGHT else DragDirection.LEFT)
                    },
                    onDragStart = { auto?.onUserTouch() },
                    onDragEnd = ::persistPositionSoon,
                    onScale = ::onScale,
                    onHoldMenu = ::onHoldMenu,
                    onHoldClose = ::onHoldClose
                ).also { if (!screenOn) it.pauseRenderer() }
            } catch (e: Exception) {
                Log.e(TAG, "Live2D overlay initialization failed", e)
                Toast.makeText(this, "Live2D 启动失败：${e.message}", Toast.LENGTH_LONG).show()
                return false
            }
        } else ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@PetOverlayService)
            setViewTreeViewModelStoreOwner(this@PetOverlayService)
            setViewTreeSavedStateRegistryOwner(this@PetOverlayService)
            setContent { PetOverlayRenderer(anim, gesture.modifier) }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 用绝对方向 LEFT 而非 START：RTL 语言（阿拉伯语/希伯来语等）下
            // START 会镜像成"距右边缘"，而 wx/clampPosition 全按左边缘计算——
            // 宠物位置镜像错位、拖拽方向相反、夹取越界。LEFT 对任何 locale 稳定
            gravity = Gravity.TOP or Gravity.LEFT
            x = wx.toInt()
            y = (wy - anim.renderTopInset).toInt()
        }
        return try {
            wm.addView(cv, params)
            true
        } catch (e: Exception) {
            Log.e(TAG, "addView", e)
            (cv as? Live2DOverlayView)?.dispose()
            cv = null
            Toast.makeText(this, "悬浮窗添加失败: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun removeOverlayView() {
        cv?.let { view ->
            try {
                wm.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove overlay view", e)
            } finally {
                (view as? Live2DOverlayView)?.dispose()
            }
        }
        cv = null
    }

    private fun updatePosition() {
        clampPosition()
        cv?.let { v ->
            (v.layoutParams as? WindowManager.LayoutParams)?.let { p ->
                p.x = wx.toInt()
                p.y = (wy + bounceOffset - anim.renderTopInset).toInt()
                if (v is Live2DOverlayView) {
                    p.width = anim.petWidth.value
                    p.height = anim.petHeight.value
                }
                try {
                    wm.updateViewLayout(v, p)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to update overlay position", e)
                }
            }
        }
        val bounds = safeBounds()
        speechBubble?.follow(wx, wy + bounceOffset, anim.petWidth.value, anim.petHeight.value, bounds)
        floatingPanel?.follow(wx, wy + bounceOffset,
            anim.petWidth.value, anim.petHeight.value, bounds)
    }

    private fun clampPosition() {
        // Content-aware safe bounds: some user-supplied 1500x1500 PNGs and
        // multi-part rigs leave a huge transparent upper canvas. Clamping
        // their WINDOW at status bar height means the actual painted HEAD
        // never reaches the screen top. Allow only the measured alpha padding
        // to go beyond the safe upper edge, keeping visible pixels accessible.
        // Bottom gesture-bar avoidance and horizontal grab handle are unchanged.
        val sb = safeBounds()
        val w = anim.petWidth.value
        // Pickup art can be much taller. Never clamp with its extended height:
        // that would teleport the grabbed HEAD upward near the bottom of screen.
        val h = anim.normalPetHeight
        // 允许宠物左右留一部分在屏幕外，但必须留出"把手"能拖回来。
        // 旧代码硬编码 32px，高分屏（3x 密度）上只有约 10dp，几乎抓不住。
        val margin = 24f * resources.displayMetrics.density
        wx = wx.coerceIn((sb.left - w + margin), (sb.right - margin))
        wy = PetVerticalPositionPolicy.clampY(
            wy, sb.top.toFloat(), sb.bottom.toFloat(), h,
            anim.transparentTopFraction
        )
    }

    /** 安全区矩形（扣除系统栏 + 挖孔）。API 30+ 精确；旧版本回退全屏。 */
    private fun safeBounds(): android.graphics.Rect {
        val b = displayBounds()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val types = android.view.WindowInsets.Type.systemBars() or
                android.view.WindowInsets.Type.displayCutout()
            val ins = wm.currentWindowMetrics.windowInsets.getInsets(types)
            return android.graphics.Rect(
                b.left + ins.left, b.top + ins.top,
                b.right - ins.right, b.bottom - ins.bottom
            )
        }
        return b
    }

    /** 当前旋转下的显示区域边界（区分于 displaySize() 的宽高形式） */
    private fun displayBounds(): android.graphics.Rect {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getSize(p)
            android.graphics.Rect(0, 0, p.x, p.y)
        }
    }

    /** API 30+ 用 currentWindowMetrics（跟随当前旋转）；旧版本回退 defaultDisplay。
     *  注意不能用 maximumWindowMetrics——它不随旋转变化，旋转后 clamp 维度错乱，
     *  宠物会被"夹"到当前屏幕之外再也找不到。 */
    private fun displaySize(): Point {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds.let { Point(it.width(), it.height()) }
        } else {
            @Suppress("DEPRECATION")
            Point().also { wm.defaultDisplay.getSize(it) }
        }
    }

    /** 单击 GIF 宠物的弹跳反馈。同一时间只允许一个弹跳：
     *  旧实现并发协程各自捕获基准 wy，连点会导致位置永久漂移（每次 +20px 漂向一边）。 */
    private var bounceJob: Job? = null
    /** 弹跳的瞬时视觉偏移（px）。只影响渲染窗口位置，绝不写入 wy——
     *  旧实现 delay 后把 wy 硬改回旧值，100ms 窗口内的拖拽/缩放会被静默丢弃。 */
    @Volatile
    private var bounceOffset = 0f

    private fun bounce() {
        if (bounceJob?.isActive == true) return
        bounceJob = sc.launch {
            try {
                bounceOffset = -8f * resources.displayMetrics.density
                updatePosition()
                delay(100)
            } finally {
                bounceOffset = 0f
                updatePosition()
            }
        }
    }

    companion object {
        const val EXTRA_PET_ID = "pet_id"
        const val ACTION_STOP = "com.deskpet.action.STOP_OVERLAY"
        private const val TAG = "PO"
        private const val NOTIFY_ID = 1
        private const val CHANNEL_ID = "deskpet_fg"
    }
}
