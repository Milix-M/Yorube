package com.hermes.client.data.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.hermes.client.R
import com.hermes.client.data.api.RunImage
import java.io.ByteArrayOutputStream
import java.util.Base64

data class ImageAttachment(val image: RunImage, val thumbnail: Bitmap)

/** Reads the picker grant immediately; a content URI is never sent to the server. */
object ImageAttachments {
    const val MAX_IMAGES = 3
    const val MAX_BYTES = 5 * 1024 * 1024

    fun read(context: Context, uri: Uri): ImageAttachment {
        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES) { context.getString(R.string.image_too_large) }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw IllegalArgumentException(context.getString(R.string.image_unreadable))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
            bounds.outWidth.toLong() * bounds.outHeight <= 40_000_000 &&
            bounds.outMimeType in setOf("image/jpeg", "image/png", "image/webp", "image/gif")) {
            context.getString(R.string.image_unreadable)
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 160) inSampleSize *= 2
        }
        val thumbnail = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) {
            context.getString(R.string.image_unreadable)
        }
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.take(160).orEmpty().ifBlank { "image" }
        return ImageAttachment(RunImage(name,
            "data:${bounds.outMimeType};base64,${Base64.getEncoder().encodeToString(bytes)}"), thumbnail)
    }
}
