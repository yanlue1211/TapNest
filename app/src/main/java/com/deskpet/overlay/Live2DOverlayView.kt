package com.deskpet.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewAssetLoader
import com.deskpet.data.Live2DModelFiles
import java.io.File
import kotlin.math.hypot

/**
 * Independent Live2D renderer and gesture shell, kept OUT of PNG bone logic.
 * Its web frontend is portable to SullyOS; Kotlin owns the Android floating
 * window and all private-file access. No JavaScript bridge, chat, API keys or
 * character/memory data are exposed to the renderer.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
class Live2DOverlayView(
    context: Context,
    manifest: File,
    width: Int,
    height: Int,
    private val onTap: () -> Unit,
    private val onDoubleTap: () -> Unit,
    private val onDrag: (Float, Float) -> Unit,
    private val onDragStart: () -> Unit,
    private val onDragEnd: () -> Unit,
    private val onScale: (Float, Float, Float) -> Unit,
    private val onHoldMenu: () -> Unit,
    private val onHoldClose: () -> Unit
) : FrameLayout(context) {

    private val root = Live2DModelFiles.validate(manifest).root
    private val ui = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var web: WebView? = null
    private var tapPending: Runnable? = null
    private var lastTapAt = -10_000L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var multiple = false
    private var pinchDistance = 0f
    private var finished = false

    private val assets = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .addPathHandler("/model/", WebViewAssetLoader.PathHandler { relative ->
            // Paths are relative to exactly this imported pet's model folder.
            val path = Uri.decode(relative)
            // Return a local 404 rather than null: null lets WebView attempt
            // a NETWORK fallback for a missing/private model path.
            val notFound = WebResourceResponse(null, null, null)
            if (path.contains('%')) return@PathHandler notFound
            val file = Live2DModelFiles.resolveAsset(root, path)
                ?: return@PathHandler notFound
            val mime = when (file.extension.lowercase()) {
                "json" -> "application/json"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "moc3" -> "application/octet-stream"
                "wav" -> "audio/wav"
                "ogg" -> "audio/ogg"
                "mp3" -> "audio/mpeg"
                else -> "application/octet-stream"
            }
            WebResourceResponse(mime, null, file.inputStream())
        }).build()

    init {
        setBackgroundColor(Color.TRANSPARENT)
        clipChildren = false
        val browser = WebView(context)
        web = browser
        browser.setBackgroundColor(Color.TRANSPARENT)
        browser.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        browser.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mediaPlaybackRequiresUserGesture = true
        }
        browser.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                return if (request.url.host == "appassets.androidplatform.net")
                    assets.shouldInterceptRequest(request.url)
                else null
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        }
        browser.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                Log.d("TapNestLive2D", "${message.messageLevel()}: ${message.message()}")
                return true
            }
        }
        addView(browser, LayoutParams(width, height))
        browser.setOnTouchListener { _, event -> onTouch(event) }
        val url = "https://appassets.androidplatform.net/assets/live2d/index.html?model=" +
            Uri.encode("/model/" + manifest.name)
        browser.loadUrl(url)
    }

    private fun onTouch(event: MotionEvent): Boolean {
        if (finished) return true
        val now = event.eventTime
        val x = event.rawX
        val y = event.rawY
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = now; downX = x; downY = y
                lastX = x; lastY = y
                dragging = false; multiple = false; pinchDistance = 0f
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiple = true
                if (dragging) { dragging = false; onDragEnd() }
                pinchDistance = separation(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1) {
                    multiple = true
                    val distance = separation(event)
                    if (pinchDistance > 0 && distance > 0) {
                        val ratio = (distance / pinchDistance).coerceIn(.7f, 1.4f)
                        if (ratio != 1f) onScale(ratio, x, y)
                    }
                    pinchDistance = distance
                } else if (!multiple) {
                    val held = now - downTime
                    if (held >= 500L && (dragging ||
                        hypot((x-downX).toDouble(), (y-downY).toDouble()) > slop)) {
                        if (!dragging) { dragging = true; onDragStart() }
                        onDrag(x - lastX, y - lastY)
                    }
                }
                lastX = x; lastY = y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                multiple = true
                pinchDistance = 0f
            }
            MotionEvent.ACTION_UP -> {
                when {
                    dragging -> onDragEnd()
                    multiple -> Unit
                    hypot((x-downX).toDouble(), (y-downY).toDouble()) > slop -> Unit
                    else -> when (HoldGesturePolicy.action(now-downTime, moved=false, multiTouch=false)) {
                        HoldGesturePolicy.Action.CLOSE -> onHoldClose()
                        HoldGesturePolicy.Action.MENU -> onHoldMenu()
                        HoldGesturePolicy.Action.NONE -> {
                            if (HoldGesturePolicy.isShortTap(now-downTime)) tap(now, x, y)
                        }
                    }
                }
                dragging = false
                multiple = false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) onDragEnd()
                dragging = false; multiple = false
            }
        }
        return true
    }

    private fun separation(e: MotionEvent): Float = if (e.pointerCount < 2) 0f
        else hypot((e.getX(0)-e.getX(1)).toDouble(),
            (e.getY(0)-e.getY(1)).toDouble()).toFloat()

    private fun tap(now: Long, x: Float, y: Float) {
        val second = now - lastTapAt < ViewConfiguration.getDoubleTapTimeout() &&
            hypot((x-lastTapX).toDouble(), (y-lastTapY).toDouble()) < slop * 3
        if (second) {
            tapPending?.let(ui::removeCallbacks)
            tapPending = null
            lastTapAt = -10_000L
            onDoubleTap()
        } else {
            lastTapAt = now; lastTapX = x; lastTapY = y
            val pending = Runnable {
                if (!finished) {
                    onTap()
                    web?.evaluateJavascript("window.tapnestTap && window.tapnestTap()", null)
                }
                tapPending = null
            }
            tapPending = pending
            ui.postDelayed(pending, ViewConfiguration.getDoubleTapTimeout().toLong())
        }
    }

    fun resize(w: Int, h: Int) {
        web?.layoutParams = LayoutParams(w.coerceAtLeast(32), h.coerceAtLeast(32))
    }

    fun pauseRenderer() {
        web?.evaluateJavascript("window.tapnestPause && window.tapnestPause(true)", null)
        web?.onPause()
    }

    fun resumeRenderer() {
        web?.onResume()
        web?.evaluateJavascript("window.tapnestPause && window.tapnestPause(false)", null)
    }

    fun dispose() {
        finished = true
        tapPending?.let(ui::removeCallbacks); tapPending = null
        web?.let {
            it.setOnTouchListener(null)
            it.stopLoading()
            it.loadUrl("about:blank")
            removeView(it)
            it.destroy()
        }
        web = null
    }
}
