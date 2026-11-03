package com.spinel.melkit.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory

internal object PreviewBitmap {
    fun decode(bytes: ByteArray): Bitmap? {
        if (PreviewImages.validated(bytes).isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096) return null
        var sample = 1
        while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
