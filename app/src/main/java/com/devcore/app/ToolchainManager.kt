package com.devcore.app

import android.content.Context
import android.os.Build
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream

class ToolchainManager(private val context: Context) {
    private val root = File(context.filesDir, "toolchain")
    private val marker = File(root, ".ready")

    companion object {
        private const val JDK_ASSET = "toolchain/jdk17-arm64.tar.xz"
        private const val SDK_ASSET = "toolchain/android-sdk.tar.xz"
        private const val BUILD_TOOLS_ASSET = "toolchain/build-tools-34.0.4-aarch64.tar.xz"
        private const val GRADLE_ASSET = "toolchain/gradle-8.9-bin.zip"
    }

    data class ToolchainPaths(
        val javaHome: File,
        val gradleHome: File,
        val sdkRoot: File,
        val aapt2: File
    )

    fun isSupportedDevice(): Boolean = Build.SUPPORTED_64_BIT_ABIS.any { it == "arm64-v8a" }

    fun hasBundledToolchain(): Boolean = runCatching {
        listOf(JDK_ASSET, SDK_ASSET, BUILD_TOOLS_ASSET, GRADLE_ASSET).all { asset ->
            context.assets.open(asset).use { true }
        }
    }.getOrDefault(false)

    fun resolve(): ToolchainPaths? {
        if (!marker.exists()) return null
        return resolveWithoutMarker()
    }

    private fun resolveWithoutMarker(): ToolchainPaths? {
        if (!root.exists()) return null
        val java = root.walkTopDown().firstOrNull { it.isFile && it.name == "java" && it.parentFile?.name == "bin" } ?: return null
        val gradle = root.walkTopDown().firstOrNull { it.isFile && it.name == "gradle" && it.parentFile?.name == "bin" } ?: return null
        val aapt2 = root.walkTopDown().firstOrNull { it.isFile && it.name == "aapt2" } ?: return null
        val androidJar = root.walkTopDown().firstOrNull {
            it.isFile && it.name == "android.jar" && it.parentFile?.name?.startsWith("android-") == true
        } ?: return null
        val sdkRoot = generateSequence(androidJar.parentFile) { it.parentFile }
            .firstOrNull { File(it, "platforms").exists() } ?: return null

        return ToolchainPaths(
            javaHome = java.parentFile.parentFile,
            gradleHome = gradle.parentFile.parentFile,
            sdkRoot = sdkRoot,
            aapt2 = aapt2
        )
    }

    fun install(onProgress: (String) -> Unit): Result<ToolchainPaths> = runCatching {
        require(isSupportedDevice()) { "Local builds currently require an arm64-v8a device." }
        require(hasBundledToolchain()) { "This DevCore build does not contain the embedded build toolchain." }

        root.mkdirs()
        marker.delete()

        val jdkDir = File(root, "jdk").apply { mkdirs() }
        val sdkDir = File(root, "sdk").apply { mkdirs() }
        val gradleDir = File(root, "gradle").apply { mkdirs() }

        if (jdkDir.walkTopDown().none { it.name == "java" && it.parentFile?.name == "bin" }) {
            onProgress("Extracting embedded OpenJDK 17…")
            context.assets.open(JDK_ASSET).use { extractTarXz(it, jdkDir) }
        } else onProgress("OpenJDK 17 already prepared.")

        if (sdkDir.walkTopDown().none { it.name == "android.jar" }) {
            onProgress("Extracting embedded Android SDK…")
            context.assets.open(SDK_ASSET).use { extractTarXz(it, sdkDir) }
        } else onProgress("Android SDK already prepared.")

        if (sdkDir.walkTopDown().none { it.name == "aapt2" }) {
            onProgress("Extracting embedded Android build tools…")
            context.assets.open(BUILD_TOOLS_ASSET).use { extractTarXz(it, sdkDir) }
        } else onProgress("Android build tools already prepared.")

        if (gradleDir.walkTopDown().none { it.name == "gradle" && it.parentFile?.name == "bin" }) {
            onProgress("Extracting embedded Gradle 8.9…")
            context.assets.open(GRADLE_ASSET).use { extractZip(it, gradleDir) }
        } else onProgress("Gradle already prepared.")

        onProgress("Preparing executables…")
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            if (
                file.parentFile?.name == "bin" ||
                file.name in setOf("aapt", "aapt2", "aidl", "zipalign", "adb", "gradle", "java", "javac", "keytool")
            ) {
                file.setExecutable(true, false)
            }
        }

        val paths = resolveWithoutMarker()
            ?: error("Embedded tools were extracted, but DevCore could not locate Java, Gradle, Android SDK or aapt2.")

        marker.writeText("installed=${System.currentTimeMillis()}\nsource=embedded\n")
        onProgress("Build tools ready.")
        paths
    }

    fun sizeBytes(): Long = if (!root.exists()) 0L else root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun extractTarXz(input: InputStream, destination: File) {
        val pendingLinks = mutableListOf<Triple<File, String, Boolean>>()
        XZCompressorInputStream(BufferedInputStream(input)).use { xz ->
            TarArchiveInputStream(xz).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null) {
                    val normalized = normalizeArchivePath(entry.name)
                    if (normalized != null) {
                        val out = safeFile(destination, normalized)
                        when {
                            entry.isDirectory -> out.mkdirs()
                            entry.isSymbolicLink -> pendingLinks += Triple(out, entry.linkName, false)
                            entry.isLink -> pendingLinks += Triple(out, entry.linkName, true)
                            else -> {
                                out.parentFile?.mkdirs()
                                FileOutputStream(out).use { tar.copyTo(it) }
                                if (entry.mode and 0b001001001 != 0) out.setExecutable(true, false)
                            }
                        }
                    }
                    entry = tar.nextTarEntry
                }
            }
        }

        pendingLinks.forEach { (out, linkName, hardLink) ->
            runCatching {
                out.parentFile?.mkdirs()
                Files.deleteIfExists(out.toPath())
                if (hardLink) {
                    val normalizedTarget = normalizeArchivePath(linkName) ?: return@runCatching
                    Files.createLink(out.toPath(), safeFile(destination, normalizedTarget).toPath())
                } else {
                    Files.createSymbolicLink(out.toPath(), java.nio.file.Paths.get(linkName))
                }
            }
        }
    }

    private fun extractZip(input: InputStream, destination: File) {
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val normalized = normalizeArchivePath(entry.name)
                if (normalized != null) {
                    val out = safeFile(destination, normalized)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { zip.copyTo(it) }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun normalizeArchivePath(relative: String): String? {
        var value = relative.replace('\\', '/').trim()
        while (value.startsWith("./")) value = value.removePrefix("./")
        value = value.trimStart('/')
        if (value.isBlank() || value == ".") return null
        val parts = value.split('/').filter { it.isNotBlank() && it != "." }
        require(parts.none { it == ".." }) { "Unsafe archive path: $relative" }
        return parts.joinToString("/").takeIf { it.isNotBlank() }
    }

    private fun safeFile(base: File, relative: String): File {
        val out = File(base, relative)
        val baseCanonical = base.canonicalFile
        val outCanonical = out.canonicalFile
        require(
            outCanonical == baseCanonical || outCanonical.path.startsWith(baseCanonical.path + File.separator)
        ) { "Unsafe archive path: $relative" }
        return outCanonical
    }
}
