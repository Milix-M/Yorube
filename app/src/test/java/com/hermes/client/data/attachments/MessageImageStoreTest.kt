package com.hermes.client.data.attachments

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.data.api.RunImage
import com.hermes.client.data.store.ClientSettings
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MessageImageStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val settings = ClientSettings(baseUrl = "https://hermes.example", apiKey = "k")
    private val directory get() = File(context.filesDir, "message_images")
    private fun image(width: Int = 64, height: Int = 32): RunImage {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        bitmap.recycle()
        return RunImage("Example.png", "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes))
    }

    @Test fun uploadedImageIsRestoredAfterRecreationAndPreviewIsBounded() {
        val store = MessageImageStore(context)
        val original = image(2048, 512)
        val refs = store.stage(settings, listOf(original))
        assertEquals(refs, store.stage(settings, listOf(original)))
        store.bind(settings, "s", 0, "r", "Describe this image", refs)
        val restored = MessageImageStore(context).restore(settings, "s", 0, 0,
            "Describe this image\n\n[Image attached at: /server/example.png]\n[screenshot]")!!
        assertEquals("Describe this image", restored.text)
        assertEquals(refs, restored.images)
        val preview = store.preview(refs.single())!!
        assertEquals(1024, preview.width)
        assertEquals(256, preview.height)
        assertArrayEquals(Base64.getDecoder().decode(original.dataUrl.substringAfter(',')),
            File(directory, refs.single().token).readBytes())
    }

    @Test fun repeatedPromptsAreMatchedByPositionAndServerIdentity() {
        val store = MessageImageStore(context)
        val first = store.stage(settings, listOf(image()))
        val second = store.stage(settings, listOf(image(128, 64)))
        store.bind(settings, "s", 0, "first", "same text", first)
        store.bind(settings, "s", 1, "second", "same text", second)
        assertEquals(first, store.restore(settings, "s", 0, 1, "same text")!!.images)
        assertEquals(second, store.restore(settings, "s", 1, 1, "same text")!!.images)
        assertNull(store.restore(settings, "s", 1, 1, "different text"))
        assertNull(store.restore(settings.copy(apiKey = "other"), "s", 0, 0, "same text"))
        assertNull(store.restore(settings, "other-session", 0, 0, "same text"))
        assertNotEquals(first, store.stage(settings.copy(apiKey = "other"), listOf(image())))
    }

    @Test fun restoredOutboxImagesBindToTheLastUserAndSupportImageOnlyMessages() {
        val store = MessageImageStore(context)
        val refs = store.stage(settings, listOf(image()))
        store.bind(settings, "s", null, "restored", "", refs)
        assertNull(store.restore(settings, "s", 0, 1, "[Image attached at: /server/example.png]"))
        assertEquals(refs, store.restore(settings, "s", 1, 1, "[Image attached at: /server/example.png]")!!.images)
    }

    @Test fun missingOrCorruptPreviewNeverOpensArbitraryFiles() {
        val store = MessageImageStore(context)
        assertTrue(store.stage(settings, emptyList()).isEmpty())
        store.bind(settings, "s", 0, "r", "x", emptyList())
        assertNull(store.restore(settings, "s", 0, 0, "x"))
        assertNull(store.preview(MessageImage("bad", "../../secrets")))
        assertNull(store.preview(MessageImage("missing", "0".repeat(64) + ".img")))
        val refs = store.stage(settings, listOf(image()))
        store.bind(settings, "", 0, "ignored", "x", refs)
        store.bind(settings, "s", 0, "r", "x", refs)
        File(directory, refs.single().token).writeText("invalid image")
        assertNull(store.preview(refs.single()))
        val index = directory.listFiles()!!.single { it.extension == "json" }
        index.writeText("not JSON")
        assertNull(store.restore(settings, "s", 0, 0, "x"))
        index.writeText(JSONObject().put("r", JSONObject().put("text", "x").put("user_index", 0)).toString())
        assertNull(store.restore(settings, "s", 0, 0, "x"))
        val entries = JSONArray().put("invalid item")
            .put(JSONObject().put("token", "../bad"))
            .put(JSONObject().put("token", "0".repeat(64) + ".img"))
        index.writeText(JSONObject().put("r", JSONObject().put("text", "x").put("user_index", 0).put("images", entries)).toString())
        assertTrue(store.restore(settings, "s", 0, 0, "x")!!.images.isEmpty())
    }

    @Test fun invalidAndOversizedUploadsAreRejectedBeforeStorage() {
        val store = MessageImageStore(context)
        for (url in listOf("https://example.invalid/private.png", "data:image/png,abc",
            "data:image/png;base64,%%%", "data:image/png;base64,YmFk",
            "data:image/png;base64," + "A".repeat(ImageAttachments.MAX_BYTES * 4 / 3 + 300))) {
            assertThrows(IllegalArgumentException::class.java) { store.stage(settings, listOf(RunImage("bad", url))) }
        }
    }

    @Test fun unwritableStorageDoesNotLoseTheOriginalUpload() {
        directory.writeText("not a directory")
        val original = image()
        assertThrows(IllegalStateException::class.java) { MessageImageStore(context).stage(settings, listOf(original)) }
        assertTrue(original.dataUrl.startsWith("data:image/png;base64,"))
    }
}
