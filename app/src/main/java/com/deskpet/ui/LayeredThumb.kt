package com.deskpet.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.deskpet.data.model.PetInfo
import com.deskpet.engine.LayeredPetPlayer
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Composite all layers for the library thumbnail instead of showing only 'body'. */
internal fun loadLayeredThumb(pet: PetInfo): Bitmap? {
    pet.petJsonPath?.let { path ->
        val assembled = LayeredPetPlayer.load(File(path))?.render()
        if (assembled != null) {
            val factor = min(220f / max(assembled.width, assembled.height), 1f)
            val w = (assembled.width * factor).toInt().coerceAtLeast(1)
            val h = (assembled.height * factor).toInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(assembled, w, h, true)
        }
    }
    // Broken rigs still get a useful recovery thumbnail.
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(pet.spritesheetPath, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    var sample = 1
    while (options.outWidth / sample > 256 || options.outHeight / sample > 256) sample *= 2
    return BitmapFactory.decodeFile(pet.spritesheetPath,
        BitmapFactory.Options().apply { inSampleSize = sample })
}
