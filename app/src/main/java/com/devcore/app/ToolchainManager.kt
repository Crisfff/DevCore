package com.devcore.app

import android.content.Context
import android.os.Build
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class ToolchainManager(private val context: Context) {
    private val root = File(context.filesDir, "toolchain")
    private val marker = File(root, ".ready")

    data class Paths(
        val javaHome: File,
        val gradleHome: File,
        val sdkRoot: File,
        val aapt2: File
    )

    fun isSupportedDevice(): Boolean = Build.SUPPORTED_64_BIT_ABIS.any { it == "arm64-v8a" }

    fun resolve(): Paths? {
        if (!marker.exists()) return null
        return resolveWithoutMarker()
    }

    private fun resolveWithoutMarker(): Paths? {
        if (!root.exists()) return null
        val java = root.walkTopDown().firstOrNull { it.isFile && it.name == "java" && it.parentFile?.name == "bin" }
            ?: return null
        val gradle = root.walkTopDown().firstOrNull { it.isFile && it.name == "gradle" && it.parentFile?.name == "bin" }
            ?: return null
        val aapt2 = root.walkTopDown().firstOrNull { it.isFile && it.name == "aapt2" }
            ?: return null
        val androidJar = root.walkTopDown().firstOrNull { it.isFile && it.name == "android.jar" && it.parentFile?.name?.startsWith("android-") == true }
            ?: return null
        val sdkRoot = generateSequence(androidJar.parentFile) { it.parentFile }
            .firstOrNull { File(it, "platforms").exists() }
            ?: return null

        return Paths(
            javaHome = java.parentFile.parentFile,
            gradleHome = gradle.parentFile.parentFile,
            sdkRoot = sdkRoot,
            aapt2 = aapt2
        )
    }

    fun install(onProgress: (String) -> Unit): Result<Paths> = runCatching {
        require(isSupportedDevice()) { "Local builds currently require an arm64-v8a device." }
        root.mkdirs()
        marker.delete()

        val jdkDir = File(root, "jdk").apply { mkdirs() }
        val sdkDir = File(root, "sdk").apply { mkdirs() }
        val gradleDir = File(root, "gradle").apply { mkdirs() }

        if (jdkDir.walkTopDown().none { it.name == "java" && it.parentFile?.name == "bin" }) {
            onProgress("Downloading OpenJDK 17…")
            extractTarXz(
                "https://github.com/itsaky/openjdk-17-android/releases/download/01-01-2022/jdk17-arm64.tar.xz",
                jdkDir,
                onProgress
            )
        } else onProgress("OpenJDK 17 already installed.")

        if (sdkDir.walkTopDown().none { it.name == "android.jar" }) {
            onProgress("Downloading Android SDK…")
            extractTarXz(
                "https://github.com/AndroidIDEOfficial/androidide-tools/releases/download/sdk/android-sdk.tar.xz",
                sdkDir,
                onProgress
            )
        } else onProgress("Android SDK already installed.")

        if (sdkDir.walkTopDown().none { it.name == "aapt2" }) {
            onProgress("Downloading Android build tools 34.0.4…")
            extractTarXz(
                "https://github.com/AndroidIDEOfficial/androidide-tools/releases/download/v34.0.4/build-tools-34.0.4-aarch64.tar.xz",
                sdkDir,
                onProgress
            )
        } else onProgress("Android build tools already installed.")

        if (gradleDir.walkTopDown().none { it.name == "gradle" && it.parentFile?.name == "bin" }) {
            onProgress("Downloading Gradle 8.9…")
            extractZip(
                "https://services.gradle.org/distributions/gradle-8.9-bin.zip",
                gradleDir,
                onProgress
            )
        } else onProgress("Gradle already installed.")

        onProgress("Preparing executables…")
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            if (file.parentFile?.name == "bin" || file.name in setOf("aapt", "aapt2", "aidl", "zipalign", "adb", "gradle", "java", "javac", "keytool")) {
                file.setExecutable(true, false)
            }
        }

        val paths = resolveWithoutMarker() ?: error("Toolchain files were downloaded, but DevCore could not locate Java, Gradle, Android SDK or aapt2.")
        marker.writeText("installed=${System.currentTimeMillis()}\n")
        onProgress("Build tools ready.")
        paths
    }

    fun sizeBytes(): Long = if (!root.exists()) 0L else root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun connection(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 30_000
        readTimeout = 60_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "DevCore/0.2")
    }

    private fun extractTarXz(url: String, destination: File, progress: (String) -> Unit) {
        val conn = connection(url)
        conn.connect()
        require(conn.responseCode in 200..299) { "Download failed: HTTP ${conn.responseCode}" }
        val total = conn.contentLengthLong
        val counting = ProgressInputStream(BufferedInputStream(conn.inputStream), total) { downloaded, all ->
            if (all > 0) progress("Downloading… ${(downloaded * 100 / all).coerceIn(0, 100)}%")
        }
        XZCompressorInputStream(counting).use { xz ->
            TarArchiveInputStream(xz).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null) {
                    val out = safeFile(destination, entry.name)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else if (entry.isSymbolicLink) {
                        // Symlinks are recreated later only when they are essential. Most JDK/SDK files are regular files.
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { tar.copyTo(it) }
                        if (entry.mode and 0b001001001 != 0) out.setExecutable(true, false)
                    }
                    entry = tar.nextTarEntry
                }
            }
        }
        conn.disconnect()
    }

    private fun extractZip(url: String, destination: File, progress: (String) -> Unit) {
        val conn = connection(url)
        conn.connect()
        require(conn.responseCode in 200..299) { "Download failed: HTTP ${conn.responseCode}" }
        val total = conn.contentLengthLong
        val counting = ProgressInputStream(BufferedInputStream(conn.inputStream), total) { downloaded, all ->
            if (all > 0) progress("Downloading… ${(downloaded * 100 / all).coerceIn(0, 100)}%")
        }
        ZipInputStream(counting).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val out = safeFile(destination, entry.name)
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zip.copyTo(it) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        conn.disconnect()
    }

    private fun safeFile(base: File, relative: String): File {
        val out = File(base, relative)
        val basePath = base.canonicalPath + File.separator
        require(out.canonicalPath.startsWith(basePath)) { "Unsafe archive path: $relative" }
        return out
    }

    private class ProgressInputStream(
        input: java.io.InputStream,
        private val total: Long,
        private val progress: (Long, Long) -> Unit
    ) : java.io.FilterInputStream(input) {
        private var count = 0L
        private var lastReport = 0L
        override fun read(): Int {
            val result = super.read()
            if (result >= 0) add(1)
            return result
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val result = super.read(buffer, offset, length)
            if (result > 0) add(result.toLong())
            return result
        }
        private fun add(value: Long) {
            count += value
            if (count - lastReport >= 2L * 1024 * 1024 || count == total) {
                lastReport = count
                progress(count, total)
            }
        }
    }
}
