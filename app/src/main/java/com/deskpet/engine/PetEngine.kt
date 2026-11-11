package com.deskpet.engine

import android.graphics.Bitmap
import com.deskpet.data.model.CodexPetConfig
import com.deskpet.data.model.PetAnimationState
import com.deskpet.data.model.PetInfo
import com.deskpet.data.model.PetType

class PetEngine(
    private val spritesheetParser: SpritesheetParser,
    private val gifRenderer: GifRenderer
) {
    companion object {
        const val SPRITESHEET_COLS = 8
        const val SPRITESHEET_ROWS = 9
        const val DEFAULT_PET_WIDTH = 192
        const val DEFAULT_PET_HEIGHT = 208
    }


    sealed class PetLoader {
        data class CodexPet(
            val config: CodexPetConfig,
            val spritesheetPath: String
        ) : PetLoader()

        data class Gif(val filePath: String) : PetLoader()

        data class Png(val bitmap: Bitmap) : PetLoader()

        data class Layered(val player: LayeredPetPlayer) : PetLoader()

        /** Real Cubism model; frames are painted by the separate transparent WebView. */
        data class Live2D(val manifest: java.io.File) : PetLoader()
    }

    fun createLoader(petInfo: PetInfo): PetLoader? {
        return when (petInfo.type) {
            PetType.LIVE2D -> petInfo.petJsonPath?.let { path ->
                runCatching {
                    val file = java.io.File(path)
                    com.deskpet.data.Live2DModelFiles.validate(file)
                    PetLoader.Live2D(file)
                }.getOrNull()
            }
            PetType.CODEX_PET -> {
                val config = petInfo.petJsonPath?.let {
                    spritesheetParser.parse(it)
                } ?: return null

                PetLoader.CodexPet(config, petInfo.spritesheetPath)
            }
            PetType.PNG -> {
                val path = petInfo.spritesheetPath
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                    bounds.outWidth.toLong() * bounds.outHeight.toLong() > 16_000_000L) return null
                var sample = 1
                while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
                val bitmap = android.graphics.BitmapFactory.decodeFile(path,
                    android.graphics.BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }) ?: return null
                PetLoader.Png(bitmap)
            }
            PetType.LAYERED -> {
                petInfo.petJsonPath?.let { LayeredPetPlayer.load(java.io.File(it)) }
                    ?.let { PetLoader.Layered(it) }
            }
            PetType.GIF -> {
                if (gifRenderer.load(petInfo.spritesheetPath)) {
                    PetLoader.Gif(petInfo.spritesheetPath)
                } else {
                    null
                }
            }
        }
    }

    fun getCurrentFrame(
        loader: PetLoader,
        state: PetAnimationState,
        frameIndex: Int
    ): Bitmap? {
        return when (loader) {
            is PetLoader.CodexPet -> {
                spritesheetParser.extractFrame(
                    loader.config,
                    loader.spritesheetPath,
                    state,
                    frameIndex
                )
            }
            is PetLoader.Png -> loader.bitmap
            is PetLoader.Live2D -> null
            is PetLoader.Layered -> loader.player.render()
            is PetLoader.Gif -> {
                gifRenderer.getFrame()
            }
        }
    }

    fun getFrameCount(loader: PetLoader, state: PetAnimationState): Int {
        return when (loader) {
            is PetLoader.CodexPet -> spritesheetParser.getFrameCount(loader.config, state)
            is PetLoader.Gif -> 1  // GIF 不分帧，每帧独立
            is PetLoader.Png -> 1
            is PetLoader.Layered -> 1
            is PetLoader.Live2D -> 1
        }
    }

    fun getPetSize(loader: PetLoader): Pair<Int, Int> {
        return when (loader) {
            is PetLoader.CodexPet -> {
                // spritesheet 8x9 grid, cell = spritesheetW/8 x spritesheetH/9
                // But we need to read actual dimensions
                val spritesheet = java.io.File(loader.spritesheetPath)
                if (!spritesheet.exists()) return Pair(DEFAULT_PET_WIDTH, DEFAULT_PET_HEIGHT)
                val options = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                android.graphics.BitmapFactory.decodeFile(loader.spritesheetPath, options)
                if (options.outWidth <= 0 || options.outHeight <= 0) {
                    return Pair(DEFAULT_PET_WIDTH, DEFAULT_PET_HEIGHT)
                }
                // 与 extractFrame 的渲染尺寸保持一致（超大单帧会被降采样，
                // 窗口若按原始单元格算会比宠物画面大一圈）
                val (w, h) = SpritesheetParser.renderCellSize(options.outWidth, options.outHeight)
                if (w <= 0 || h <= 0) Pair(DEFAULT_PET_WIDTH, DEFAULT_PET_HEIGHT) else Pair(w, h)
            }
            is PetLoader.Gif -> Pair(gifRenderer.width, gifRenderer.height)
            is PetLoader.Png -> {
                // Original images can be 1024px+, but floating characters should start compact.
                val longest = kotlin.math.max(loader.bitmap.width, loader.bitmap.height).coerceAtLeast(1)
                val factor = kotlin.math.min(340f / longest, 1f)
                Pair((loader.bitmap.width * factor).toInt().coerceAtLeast(32),
                    (loader.bitmap.height * factor).toInt().coerceAtLeast(32))
            }
            is PetLoader.Layered -> Pair(loader.player.width, loader.player.height)
            is PetLoader.Live2D -> Pair(300, 360)
        }
    }

    /** 释放解码缓存（服务销毁时调用）*/
    fun release() {
        spritesheetParser.release()
        gifRenderer.release()
    }
}
