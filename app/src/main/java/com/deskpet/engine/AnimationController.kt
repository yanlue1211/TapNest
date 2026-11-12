package com.deskpet.engine

import android.graphics.Bitmap
import com.deskpet.data.model.PetAnimationState
import com.deskpet.data.model.PetInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.withLock

class AnimationController(
    private val petEngine: PetEngine,
    private val scope: CoroutineScope
) : IAnimationStateController {

    /**
     * 帧包装：携带递增序号。
     * StateFlow 按 equals 去重，若直接发 Bitmap，双缓冲复用同一对象时不会触发重组；
     * seq 保证每帧都是"新值"，位图可以放心复用。
     */
    data class Frame(val bitmap: Bitmap, val seq: Long, val topInset: Int = 0)

    /** One render transaction: Compose must never combine independent flow generations. */
    data class Presentation(val frame: Frame?, val width: Int, val height: Int, val topInset: Int = 0)

    companion object {
        private const val IDLE_FRAME_INTERVAL_NS = 500_000_000L
        private const val ANIM_FRAME_INTERVAL_NS = 200_000_000L
        private const val LAYERED_FRAME_INTERVAL_NS = 33_333_333L // 30 FPS; smoother motion
        private const val MIN_FRAME_DELAY_NS = 16_000_000L
        private const val LOOP_COUNT_BEFORE_IDLE = 2
        private const val MIN_PET_SIZE_PX = 32

        /** 与 UI 缩放滑块范围一致（50%~500%），见 PetListScreen */
        private const val MIN_SCALE = 0.2f
        private const val MAX_SCALE = 5f
    }

    private val _state = MutableStateFlow(PetAnimationState.IDLE)
    override val currentState: StateFlow<PetAnimationState> = _state
    private val _frame = MutableStateFlow<Frame?>(null)
    val currentFrame: StateFlow<Frame?> = _frame
    /** 动画循环在 Main 线程读、reload 在 IO 线程写，必须 volatile 保证可见性 */
    @Volatile private var frameSeq = 0L

    /**
     * 系统省电模式联动（由 Service 的广播接收器维护）：
     * 用户主动开启省电 = 明确在乎电量，此时帧率自动减半（播放 5→2.5FPS、
     * 待机 2→1FPS），桌宠变成"用电良民"；平时体验完全不变。
     */
    @Volatile var powerSaveMode = false
    private val _w = MutableStateFlow(PetEngine.DEFAULT_PET_WIDTH)
    val petWidth: StateFlow<Int> = _w
    private val _h = MutableStateFlow(PetEngine.DEFAULT_PET_HEIGHT)
    val petHeight: StateFlow<Int> = _h
    private val _presentation = MutableStateFlow(Presentation(null,
        PetEngine.DEFAULT_PET_WIDTH, PetEngine.DEFAULT_PET_HEIGHT))
    val presentation: StateFlow<Presentation> = _presentation

    private val visualGeneration = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var fi = 0
    @Volatile private var pc = 0

    /** 动画循环在 Main 线程读、reload 在 IO 线程写，必须 volatile 保证可见性 */
    @Volatile private var loader: PetEngine.PetLoader? = null
    private var fj: Job? = null
    @Volatile private var actionVisual: TemporaryActionVisual? = null

    /** Decoding never runs on the main UI thread. */
    suspend fun playActionArtwork(file: java.io.File?) {
        val w = baseW
        val h = baseH
        val visual = withContext(Dispatchers.IO) { TemporaryActionVisual.load(file, w, h) }
        actionVisual = visual
    }
    @Volatile private var lifeInteraction: LifeInteractionAnimation? = null
    private val interactionGeneration = java.util.concurrent.atomic.AtomicLong()
    private var s = 1f
    private var baseW = PetEngine.DEFAULT_PET_WIDTH
    private var baseH = PetEngine.DEFAULT_PET_HEIGHT
    private var baseTopInset = 0
    val renderTopInset: Int get() = (baseTopInset * s).toInt()
    private var basePickupH = PetEngine.DEFAULT_PET_HEIGHT
    @Volatile private var layeredPickedUp = false
    // Window geometry follows the frame that has actually been published.
    @Volatile private var presentedLayeredPickedUp = false
    /** Original-height bounds are retained during dragging so the head never jumps. */
    val normalPetHeight: Int
        get() = (baseH * s).toInt().coerceAtLeast(MIN_PET_SIZE_PX)

    /** Fraction of the canvas ABOVE the first painted pixel, measured once at load.
     * Read by overlay position clamping; remains valid at any zoom level. */
    var transparentTopFraction: Float = 0f
        private set

    /** 加载宠物；会按当前缩放比例重新计算窗口尺寸。文件读取发生在线程池中，调用方需自行切 IO。 */
    fun load(p: PetInfo): Boolean {
        // 先清掉上一只的帧再创建新 loader：createLoader 内部会释放旧引擎资源
        // （如 GIF 双缓冲位图），必须保证渲染端不再引用它们
        _frame.value = null
        publishPresentation()
        actionVisual = null
        cancelInteraction()
        loader = petEngine.createLoader(p) ?: run {
            transparentTopFraction = 0f
            return false
        }
        val active = loader!!
        val (w, h) = petEngine.getPetSize(active)
        baseW = w
        baseH = h
        baseTopInset = (active as? PetEngine.PetLoader.Layered)?.player?.topInset ?: 0
        basePickupH = (active as? PetEngine.PetLoader.Layered)
            ?.player?.pickupHeight ?: h
        layeredPickedUp = false
        presentedLayeredPickedUp = false
        // PNG originals and layered rigs often have substantial transparent
        // canvas above the character. The WindowManager window may need to move
        // further than safeBounds.top to get the DRAWN HEAD up to the top.
        // Do this a single time on the existing IO reload path, never per frame.
        transparentTopFraction = when (active) {
            is PetEngine.PetLoader.Png ->
                PetVisibleBounds.transparentTopFraction(active.bitmap)
            is PetEngine.PetLoader.Layered ->
                active.player.render().let { frame ->
                    ((PetVisibleBounds.transparentTopFraction(frame) * frame.height - baseTopInset) /
                        baseH.coerceAtLeast(1)).coerceIn(0f, 1f)
                }
            else -> 0f
        }
        frameSeq = 0
        applyScale()
        reset()
        return true
    }

    /**
     * 重载宠物。必须串行化：reload 是纯阻塞函数（无挂起点），协程取消
     * 不能中断它——快速连续切换宠物时，旧协程的 reload 会和新协程的
     * reload 并发交错（loader 互相覆盖、引擎资源双重释放、最终显示错误
     * 的宠物）。Mutex 保证按调用顺序完整执行，最后一个 caller 胜出。
     */
    private val reloadMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun reload(p: PetInfo): Boolean = reloadMutex.withLock {
        stop()
        load(p)
    }

    fun start() {
        fj?.cancel()
        // Cubism owns its own WebGL frame clock. Creating a bitmap coroutine
        // would waste CPU while getCurrentFrame() deliberately returns null.
        if (loader is PetEngine.PetLoader.Live2D) { fj = null; return }
        fj = scope.launch(Dispatchers.Main) {
            var lt = System.nanoTime()
            while (isActive) {
                val l = loader ?: break
                val visual = actionVisual
                val interaction = lifeInteraction?.takeIf { it.active(android.os.SystemClock.uptimeMillis()) }
                if (interaction == null) {
                    if (lifeInteraction != null) actionVisual = null
                    lifeInteraction = null
                    (l as? PetEngine.PetLoader.Layered)?.player?.lifeInteraction = null
                }
                val generation = visualGeneration.get()
                val bitmap = withContext(Dispatchers.IO) {
                    val source = if (visual != null && !visual.expired()) visual.frame()
                    else petEngine.getCurrentFrame(l, _state.value, fi)
                    if (source != null && interaction != null)
                        interaction.render(source, android.os.SystemClock.uptimeMillis(),
                            if (visual == null) (l as? PetEngine.PetLoader.Layered)?.player?.topInset ?: 0 else 0) else source
                }
                if (visual != null && visual.expired()) actionVisual = null
                if (bitmap != null && generation == visualGeneration.get()) {
                    _frame.value = Frame(bitmap, ++frameSeq,
                        if (visual == null) (l as? PetEngine.PetLoader.Layered)?.player?.topInset ?: 0 else 0)
                    if (l is PetEngine.PetLoader.Layered && presentedLayeredPickedUp != layeredPickedUp) {
                        presentedLayeredPickedUp = layeredPickedUp
                        applyScale(publish = false)
                    }
                    publishPresentation()
                }
                fi++
                val tf = petEngine.getFrameCount(l, _state.value)
                if (tf <= 0) {
                    // 该状态没有可用帧（pet.json 配置 frames=0 等）：
                    // 必须立刻回 IDLE，否则状态机永远卡在这里，宠物"冻住"且
                    // AutonomousBehavior / 单击互动（都等 IDLE）全部失效
                    fi = 0
                    if (_state.value != PetAnimationState.IDLE) _state.value = PetAnimationState.IDLE
                } else if (fi >= tf) {
                    fi = 0
                    if (isCodexPet()) {
                        pc++
                        if (_state.value != PetAnimationState.IDLE && pc >= LOOP_COUNT_BEFORE_IDLE) {
                            _state.value = PetAnimationState.IDLE
                            pc = 0
                        }
                    }
                }
                // Codex Pet 待机时降到 2FPS 省电；GIF 没有状态机，始终按动画帧率播放。
                // 系统省电模式下整体再减半（播放 2.5FPS / 待机 1FPS）
                val idle = _state.value == PetAnimationState.IDLE &&
                    (isCodexPet() || l is PetEngine.PetLoader.Png)
                var ns = when {
                    interaction != null -> LAYERED_FRAME_INTERVAL_NS
                    actionVisual != null -> LAYERED_FRAME_INTERVAL_NS
                    l is PetEngine.PetLoader.Layered -> LAYERED_FRAME_INTERVAL_NS
                    idle -> IDLE_FRAME_INTERVAL_NS
                    else -> ANIM_FRAME_INTERVAL_NS
                }
                if (powerSaveMode) ns *= 2
                delay(((ns - (System.nanoTime() - lt)).coerceAtLeast(MIN_FRAME_DELAY_NS)) / 1_000_000)
                lt = System.nanoTime()
            }
        }
    }

    fun stop() {
        fj?.cancel()
        fj = null
    }

    override fun switchState(st: PetAnimationState) {
        // 拖拽时会连续回调；状态没变就不要重置帧序号，否则动画会卡在第一帧
        if (_state.value == st) return
        _state.value = st
        fi = 0
        pc = 0
    }

    fun reset() {
        _state.value = PetAnimationState.IDLE
        fi = 0
        pc = 0
    }

    fun setScale(v: Float) {
        s = v.coerceIn(MIN_SCALE, MAX_SCALE)
        if (loader != null) applyScale()
    }

    fun tapDialogues(common: List<String>): List<String> =
        (loader as? PetEngine.PetLoader.Layered)?.player?.formDialogues ?: common

    fun isCodexPet() = loader is PetEngine.PetLoader.CodexPet
    fun isLayeredPet() = loader is PetEngine.PetLoader.Layered
    fun isLive2DPet() = loader is PetEngine.PetLoader.Live2D
    fun tapLayeredPet() { cancelInteraction(); (loader as? PetEngine.PetLoader.Layered)?.player?.tap(countForBurst = false) }
    fun recordLayeredPhysicalTap() {
        if ((loader as? PetEngine.PetLoader.Layered)?.player?.recordPhysicalTap() == true)
            cancelInteraction()
    }
    fun setLayeredPickedUp(held: Boolean) {
        if (held) cancelInteraction()
        val layered = (loader as? PetEngine.PetLoader.Layered) ?: return
        if (layeredPickedUp == held) return
        visualGeneration.incrementAndGet()
        layered.player.setPickedUp(held)
        layeredPickedUp = held
        if (held) layered.player.preparedPickupFrame?.let {
            _frame.value = Frame(it, ++frameSeq, baseTopInset)
            presentedLayeredPickedUp = true
            applyScale()
        }
        // On release, keep the long canvas until a matching normal frame exists.
        // Wake the renderer now; stale in-flight frames are rejected by generation.
        start()
    }
    fun cancelInteraction() {
        interactionGeneration.incrementAndGet()
        lifeInteraction = null
        actionVisual = null
        (loader as? PetEngine.PetLoader.Layered)?.player?.lifeInteraction = null
    }

    suspend fun interactCompanion(action: com.deskpet.data.CompanionAction,
        emoji: String, settings: com.deskpet.data.InteractionAnimationSettings,
        artwork: java.io.File?) {
        cancelInteraction()
        if (!settings.enabled || layeredPickedUp) return
        val generation = interactionGeneration.get()
        val activeLoader = loader
        val visual = withContext(Dispatchers.IO) { TemporaryActionVisual.load(artwork, baseW, baseH) }
        // A late decode cannot resurrect an animation after dragging, changing form or another click.
        if (generation != interactionGeneration.get() || activeLoader !== loader || layeredPickedUp) return
        actionVisual = visual
        val session = LifeInteractionAnimation(action, emoji, settings, android.os.SystemClock.uptimeMillis())
        lifeInteraction = session
        (loader as? PetEngine.PetLoader.Layered)?.player?.lifeInteraction = session
        start() // Wake PNG idle immediately so its 500ms sleep cannot skip the falling phase.
    }

    /** Prepare a new layered player while the old frame loop remains visible. */
    suspend fun replaceLayered(p: PetInfo): Boolean = reloadMutex.withLock {
        cancelInteraction()
        val next = withContext(Dispatchers.IO) { petEngine.createLoader(p) as? PetEngine.PetLoader.Layered }
            ?: return@withLock false
        val first = withContext(Dispatchers.IO) { next.player.render() }
        val top = withContext(Dispatchers.IO) {
            ((PetVisibleBounds.transparentTopFraction(first) * first.height - next.player.topInset) /
                next.player.height.coerceAtLeast(1)).coerceIn(0f, 1f)
        }
        stop()
        loader = next
        baseW = next.player.width
        baseH = next.player.height
        baseTopInset = next.player.topInset
        basePickupH = next.player.pickupHeight
        layeredPickedUp = false
        presentedLayeredPickedUp = false
        transparentTopFraction = top
        reset()
        _frame.value = Frame(first, ++frameSeq, baseTopInset)
        applyScale()
        start()
        true
    }

    fun setAffection(value: Int) {
        (loader as? PetEngine.PetLoader.Layered)?.player?.setAffection(value)
    }

    private fun publishPresentation() {
        _presentation.value = Presentation(_frame.value, _w.value, _h.value, renderTopInset)
    }

    private fun applyScale(publish: Boolean = true) {
        _w.value = (baseW * s).toInt().coerceAtLeast(MIN_PET_SIZE_PX)
        _h.value = ((if (presentedLayeredPickedUp) basePickupH else baseH) * s)
            .toInt().coerceAtLeast(MIN_PET_SIZE_PX)
        if (publish) publishPresentation()
    }
}
