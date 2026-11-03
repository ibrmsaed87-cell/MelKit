package com.spinel.melkit.storage

import android.util.JsonReader
import android.util.JsonToken
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.io.InputStream
import java.util.Locale

/** Reads only the preview; a missing/unsupported preview says nothing about file validity. */
internal object GamePreviewReader {
    fun read(file: File): ByteArray = when (file.extension.lowercase(Locale.ROOT)) {
        "melmap" -> PreviewImages.readMap(file)
        "melworld" -> PreviewImages.readWorld(file)
        "melmod", "melsave" -> file.inputStream().use { readIcon(it) }
        else -> byteArrayOf()
    }

    fun readStream(input: InputStream, extension: String): ByteArray = when (extension.lowercase(Locale.ROOT)) {
        "melmap" -> PreviewImages.readStream(input, false)
        "melworld" -> PreviewImages.readStream(input, true)
        "melmod", "melsave" -> readIcon(input)
        else -> byteArrayOf()
    }

    private fun readIcon(input: InputStream): ByteArray {
        // Stream the root icon array, without loading model textures or save objects into memory.
        JsonReader(InputStreamReader(
            PreviewImages.limited(input, PreviewImages.MAX_JSON_BYTES.toLong()).buffered(), Charsets.UTF_8,
        )).use { reader ->
            if (reader.peek() != JsonToken.BEGIN_OBJECT) return byteArrayOf()
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() != "icon") {
                    reader.skipValue()
                    continue
                }
                if (reader.peek() != JsonToken.BEGIN_ARRAY) return byteArrayOf()
                reader.beginArray()
                val output = ByteArrayOutputStream()
                while (reader.hasNext()) {
                    if (output.size() >= PreviewImages.MAX_IMAGE_BYTES || reader.peek() != JsonToken.NUMBER) {
                        return byteArrayOf()
                    }
                    val value = reader.nextInt()
                    if (value !in 0..255) return byteArrayOf()
                    output.write(value)
                }
                reader.endArray()
                return PreviewImages.validated(output.toByteArray())
            }
        }
        return byteArrayOf()
    }
}
