package com.deskpet.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.deskpet.data.model.PetInfo

/** Display the body layer as a lightweight thumbnail without instantiating the animation engine. */
internal fun loadLayeredThumb(pet: PetInfo): Bitmap? {
    val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(pet.spritesheetPath, opt)
    if (opt.outWidth <= 0 || opt.outHeight <= 0) return null
    var sample = 1
    while (opt.outWidth / sample > 256 || opt.outHeight / sample > 256) sample *= 2
    return BitmapFactory.decodeFile(pet.spritesheetPath,
        BitmapFactory.Options().apply { inSampleSize = sample })
}
