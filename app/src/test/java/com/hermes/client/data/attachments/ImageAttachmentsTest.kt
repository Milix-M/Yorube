package com.hermes.client.data.attachments

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.hermes.client.AppContainer
import com.hermes.client.data.api.HermesJson
import com.hermes.client.data.api.RunCreateRequest
import com.hermes.client.data.store.ClientSettings
import com.hermes.client.testutil.awaitCondition
import com.hermes.client.testutil.runVmTest
import com.hermes.client.ui.screens.ChatViewModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageAttachmentsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun imageBytes(): ByteArray = ByteArrayOutputStream().use { output ->
        Bitmap.createBitmap(320, 160, Bitmap.Config.ARGB_8888).apply {
            compress(Bitmap.CompressFormat.PNG, 100, output)
            recycle()
        }
        output.toByteArray()
    }
    private fun register(bytes: ByteArray, name: String = "photo"): Uri = Uri.parse("content://images/$name").also {
        shadowOf(context.contentResolver).registerInputStreamSupplier(it) { ByteArrayInputStream(bytes) }
    }

    @Test fun imageDataIsReadUnderPickerGrantAndPreviewIsBounded() {
        val bytes = imageBytes()
        val image = ImageAttachments.read(context, register(bytes))
        assertTrue(image.thumbnail.width <= 160)
        assertTrue(image.thumbnail.height <= 160)
        assertArrayEquals(bytes, Base64.getDecoder().decode(image.image.dataUrl.substringAfter(',')))
        assertTrue(image.image.dataUrl.startsWith("data:image/png;base64,"))
        val request = RunCreateRequest("describe", images = listOf(image.image))
        assertEquals(request, HermesJson.decodeFromString<RunCreateRequest>(HermesJson.encodeToString(RunCreateRequest.serializer(), request)))
    }

    @Test fun oversizedAndNonImageInputsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ImageAttachments.read(context, register(ByteArray(ImageAttachments.MAX_BYTES + 1), "large"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImageAttachments.read(context, register("not an image".toByteArray(), "invalid"))
        }
    }

    @Test fun providerDisplayNameIsPreservedAndCursorIsClosed() {
        val uri = register(imageBytes(), "named")
        var closed = false
        shadowOf(context.contentResolver).setCursor(uri, object : org.robolectric.fakes.BaseCursor() {
            override fun moveToFirst() = true
            override fun getString(columnIndex: Int) = "named.png"
            override fun close() { closed = true }
        })
        assertEquals("named.png", ImageAttachments.read(context, uri).image.name)
        assertTrue(closed)
    }

    @org.robolectric.annotation.Implements(android.content.ContentResolver::class)
    class UnavailableImageResolver {
        @org.robolectric.annotation.Implementation
        fun openInputStream(uri: Uri): java.io.InputStream? = null
    }

    @Test @Config(shadows = [UnavailableImageResolver::class])
    fun unavailableStreamIsReportedAsUnreadable() {
        val missing = Uri.parse("content://images/unavailable")
        assertThrows(IllegalArgumentException::class.java) { ImageAttachments.read(context, missing) }
    }

    @Test fun corruptPixelDataIsRejected() {
        val corrupt = imageBytes()
        var offset = 8
        while (offset + 12 <= corrupt.size) {
            val size = java.nio.ByteBuffer.wrap(corrupt, offset, 4).int
            if (String(corrupt, offset + 4, 4, Charsets.US_ASCII) == "IDAT") {
                corrupt.fill(0, offset + 8, offset + 8 + size)
                break
            }
            offset += size + 12
        }
        assertThrows(IllegalArgumentException::class.java) { ImageAttachments.read(context, register(corrupt, "corrupt")) }
    }

    @Test fun leavingScreenWhileReadingDoesNotAttachStaleImage() = runVmTest {
        val c = AppContainer(context)
        val vm = ChatViewModel(c, null)
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val uri = Uri.parse("content://images/slow")
        val bytes = imageBytes()
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
            object : java.io.InputStream() {
                val delegate = ByteArrayInputStream(bytes)
                override fun read() = delegate.read()
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    return delegate.read(buffer, offset, length)
                }
            }
        }
        try {
            vm.attachImage(uri)
            awaitCondition { started.count == 0L }
            vm.viewModelScope.cancel()
            release.countDown()
            awaitCondition { !vm.attaching }
            assertTrue(vm.attachments.isEmpty())
            assertNull(vm.error)
        } finally { release.countDown(); vm.viewModelScope.cancel(); c.runStreams.close() }
    }

    @Test fun viewModelSendsImageDataAndKeepsAttachmentOnFailedSend() = runVmTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(413)
                .setBody("""{"error":{"code":"request_too_large","message":"too large"}}"""))
            val c = AppContainer(context)
            c.settingsStore.save(ClientSettings(server.url("/").toString(), "k"))
            val vm = ChatViewModel(c, null)
            try {
                vm.attachImage(register(imageBytes()))
                vm.onSend() // Sending while the picker content is being read must do nothing.
                awaitCondition { !vm.attaching }
                assertEquals(1, vm.attachments.size)
                vm.input = "describe this"
                vm.onSend()
                awaitCondition { !vm.busy && vm.error != null }
                val request = server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()
                assertTrue(request.contains("data:image/png;base64,"))
                assertTrue(request.contains("describe this"))
                assertEquals(1, vm.attachments.size)
                assertEquals("describe this", vm.input)
                assertEquals(context.getString(com.hermes.client.R.string.message_too_large), vm.error)
            } finally { vm.viewModelScope.cancel(); c.runStreams.close() }
        }
    }

    @Test fun viewModelRejectsTooManyAndUnreadableAttachments() = runVmTest {
        val c = AppContainer(context)
        val vm = ChatViewModel(c, null)
        try {
            vm.attachImage(register(byteArrayOf(), "invalid"))
            awaitCondition { !vm.attaching }
            assertNotNull(vm.error)
            vm.dismissError()
            repeat(3) {
                vm.attachImage(register(imageBytes(), "image$it"))
                awaitCondition { !vm.attaching }
            }
            vm.attachImage(register(imageBytes(), "fourth"))
            assertEquals(3, vm.attachments.size)
            assertNotNull(vm.error)
            vm.onSteer()
            assertNotNull(vm.error)
        } finally { vm.viewModelScope.cancel(); c.runStreams.close() }
    }
}
