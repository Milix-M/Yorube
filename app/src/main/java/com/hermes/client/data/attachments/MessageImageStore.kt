package com.hermes.client.data.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import androidx.core.util.writeBytes
import com.hermes.client.data.api.RunImage
import com.hermes.client.data.store.ClientSettings
import com.hermes.client.data.store.ConversationState
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

data class MessageImage(val name: String, val token: String)
data class SavedMessageImages(val text: String, val images: List<MessageImage>)

/** Original uploads remain private to the app; UI messages retain file references, not Base64. */
class MessageImageStore(context: Context) {
    private val directory = File(context.filesDir, "message_images")

    @Synchronized
    fun stage(settings: ClientSettings, images: List<RunImage>): List<MessageImage> {
        if (images.isEmpty()) return emptyList()
        check(directory.isDirectory || directory.mkdirs()) { "Cannot save image preview" }
        return images.take(ImageAttachments.MAX_IMAGES).map { image ->
            require(image.dataUrl.startsWith("data:image/") && ";base64," in image.dataUrl) { "Invalid inline image" }
            require(image.dataUrl.length <= ImageAttachments.MAX_BYTES * 4 / 3 + 256) { "Image is too large" }
            val bytes = Base64.getDecoder().decode(image.dataUrl.substringAfter(","))
            require(bytes.size <= ImageAttachments.MAX_BYTES) { "Image is too large" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
                bounds.outWidth.toLong() * bounds.outHeight <= 40_000_000 &&
                bounds.outMimeType in setOf("image/png", "image/jpeg", "image/webp", "image/gif")) { "Invalid image" }
            val token = digest(ConversationState.profileKey(settings).toByteArray() + bytes) + ".img"
            val file = File(directory, token)
            if (!file.isFile) AtomicFile(file).writeBytes(bytes)
            MessageImage(image.name.take(160), token)
        }
    }

    @Synchronized
    fun bind(settings: ClientSettings, session: String, userIndex: Int?, run: String,
        text: String, images: List<MessageImage>) {
        if (images.isEmpty() || session.isBlank()) return
        val file = indexFile(settings, session)
        val index = readIndex(file)
        val entry = JSONObject().put("text", text).put("user_index", userIndex ?: -1)
            .put("images", JSONArray().apply {
                images.forEach { put(JSONObject().put("name", it.name).put("token", it.token)) }
            })
        index.put(run, entry)
        AtomicFile(file).writeBytes(index.toString().toByteArray(Charsets.UTF_8))
    }

    @Synchronized
    fun restore(settings: ClientSettings, session: String, userIndex: Int, lastUserIndex: Int,
        serverText: String): SavedMessageImages? {
        val index = readIndex(indexFile(settings, session))
        val entries = index.keys().asSequence().mapNotNull { index.optJSONObject(it) }.toList()
        val entry = entries.lastOrNull {
            val position = it.optInt("user_index", -1)
            (position == userIndex || position == -1 && userIndex == lastUserIndex) &&
                matchesText(it.optString("text"), serverText)
        } ?: return null
        val saved = entry.optJSONArray("images") ?: return null
        val images = (0 until saved.length()).mapNotNull { i ->
            val item = saved.optJSONObject(i) ?: return@mapNotNull null
            val token = item.optString("token")
            if (!validToken(token) || !File(directory, token).isFile) null
            else MessageImage(item.optString("name"), token)
        }
        return SavedMessageImages(entry.optString("text"), images)
    }

    fun preview(image: MessageImage): Bitmap? {
        if (!validToken(image.token)) return null
        val file = File(directory, image.token)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth.toLong() * bounds.outHeight > 40_000_000) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 1024) inSampleSize *= 2
        }
        return BitmapFactory.decodeFile(file.path, options)
    }

    private fun indexFile(settings: ClientSettings, session: String): File =
        File(directory, digest((ConversationState.profileKey(settings) + "\n" + session).toByteArray()) + ".json")

    private fun readIndex(file: File): JSONObject = try {
        if (file.isFile) JSONObject(file.readText()) else JSONObject()
    } catch (_: Exception) { JSONObject() }

    private fun matchesText(original: String, actual: String): Boolean = actual == original ||
        actual.startsWith(original + "\n\n[Image attached at:") ||
        original.isBlank() && actual.startsWith("[Image attached at:")

    private fun validToken(token: String): Boolean = token.matches(Regex("[0-9a-f]{64}\\.img"))

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
