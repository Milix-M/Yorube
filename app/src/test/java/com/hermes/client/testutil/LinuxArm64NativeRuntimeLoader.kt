package com.hermes.client.testutil

import android.os.Build
import java.io.File
import java.net.JarURLConnection
import java.nio.file.Files
import java.util.Locale
import javax.annotation.Priority
import org.robolectric.nativeruntime.DefaultNativeRuntimeLoader
import org.robolectric.util.TempDirectory

/**
 * Loads the official Linux ARM64 runtime through Robolectric's NativeRuntimeLoader SPI.
 *
 * Robolectric 4.17 rejects Linux ARM64 before looking for the library, even though
 * nativeruntime-dist-compat 1.0.20 now ships it. Keep this adapter paired with those
 * pinned versions; remove it when upgrading to a runner with Linux ARM64 support.
 * Other hosts retain Robolectric's default loader and native graphics behavior.
 */
@Priority(0)
class LinuxArm64NativeRuntimeLoader : DefaultNativeRuntimeLoader() {
    @Synchronized
    override fun ensureLoaded() {
        if (System.getProperty("os.name") != "Linux" ||
            System.getProperty("os.arch").orEmpty() !in setOf("aarch64", "arm64")
        ) {
            super.ensureLoaded()
            return
        }
        if (loaded.get()) return
        check(Build.VERSION.SDK_INT in 26..34) {
            "The Linux ARM64 compatibility loader supports API 26–34; newer APIs need a different native runtime"
        }

        val library = "native/linux/aarch64/librobolectric-nativeruntime.so"
        val resource = checkNotNull(checkNotNull(javaClass.classLoader).getResource(library)) {
            "Missing $library; Linux ARM64 requires nativeruntime-dist-compat 1.0.20"
        }
        val directory = TempDirectory("yorube-nativeruntime").basePath
        val connection = resource.openConnection() as JarURLConnection
        connection.useCaches = false
        connection.jarFile.use { jar ->
            for (entry in jar.entries()) {
                if (entry.isDirectory) continue
                if (entry.name != library && entry.name != "icu/icudt68l.dat" &&
                    !entry.name.startsWith("fonts/")
                ) continue
                val target = directory.resolve(entry.name).normalize()
                check(target.startsWith(directory)) { "Invalid runtime resource: ${entry.name}" }
                Files.createDirectories(target.parent)
                jar.getInputStream(entry).use { Files.copy(it, target) }
            }
        }
        System.setProperty("icu.locale.default", Locale.getDefault().toLanguageTag())
        System.setProperty("icu.data.path", directory.resolve("icu/icudt68l.dat").toString())
        System.setProperty("robolectric.nativeruntime.fontdir", directory.resolve("fonts").toString() + File.separator)
        // Native initialization can re-enter the loader, as with the default loader.
        loaded.set(true)
        try {
            System.load(directory.resolve(library).toString())
        } catch (failure: Throwable) {
            loaded.set(false)
            throw failure
        }
    }
}
