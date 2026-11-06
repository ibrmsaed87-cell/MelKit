package com.spinel.melkit.storage

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

internal object CoverPhoto {
    private const val EDITOR_SIZE = 1600
    private const val COVER_SIZE = 320

    fun open(resolver: ContentResolver, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > EDITOR_SIZE) {
                    decoder.setTargetSize((info.size.width.toLong() * EDITOR_SIZE / longest).toInt().coerceAtLeast(1),
                        (info.size.height.toLong() * EDITOR_SIZE / longest).toInt().coerceAtLeast(1))
                }
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > EDITOR_SIZE) sample *= 2
        val image = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull()
        val transform = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                else -> Unit
            }
        }
        return if (transform.isIdentity) image else Bitmap.createBitmap(image, 0, 0, image.width, image.height, transform, true)
    }

    fun area(photo: Bitmap, zoom: Float, horizontal: Float, vertical: Float): Rect {
        val side = (minOf(photo.width, photo.height) / zoom).roundToInt().coerceAtLeast(1)
        val left = ((photo.width - side) * horizontal).roundToInt()
        val top = ((photo.height - side) * vertical).roundToInt()
        return Rect(left, top, left + side, top + side)
    }

    fun crop(photo: Bitmap, area: Rect): Bitmap = Bitmap.createBitmap(COVER_SIZE, COVER_SIZE, Bitmap.Config.ARGB_8888).apply {
        Canvas(this).drawBitmap(photo, area, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
    }

    fun encode(photo: Bitmap): ByteArray = ByteArrayOutputStream().use { output ->
        check(photo.compress(Bitmap.CompressFormat.PNG, 100, output)) { "SAVE_FAILED" }
        output.toByteArray()
    }
}
