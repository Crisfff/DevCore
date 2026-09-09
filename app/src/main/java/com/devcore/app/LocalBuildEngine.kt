package com.devcore.app

import android.content.Context
import java.io.File
import java.util.concurrent.Executors

class LocalBuildEngine(private val context: Context) {

    data class Result(val success: Boolean, val log: String, val apk: File?)

    private val executor = Executors.newSingleThreadExecutor()

    fun build(project: ProjectStore.Project, callback: (Result) -> Unit) {
        executor.execute {
            callback(runBuild(project))
        }
    }

    private fun runBuild(project: ProjectStore.Project): Result {
        val toolchain = File(context.filesDir, "toolchain")
        val javaHome = File(toolchain, "jdk")
        val gradleBin = File(toolchain, "gradle/bin/gradle")
        val sdkRoot = File(toolchain, "android-sdk")
        val aapt2 = File(sdkRoot, "build-tools/34.0.4/aapt2")

        if (!gradleBin.exists() || !File(javaHome, "bin/java").exists() || !sdkRoot.exists()) {
            return Result(
                false,
                "DevCore local build tools are not installed yet.\n\n" +
                    "Required: OpenJDK, Gradle, Android SDK and Android-native aapt2.\n" +
                    "The editor and project system are ready, but this device still needs the local toolchain package.",
                null
            )
        }

        val command = mutableListOf(
            gradleBin.absolutePath,
            "--no-daemon",
            "assembleDebug"
        )
        if (aapt2.exists()) {
            command.add("-Pandroid.aapt2FromMavenOverride=${aapt2.absolutePath}")
        }

        return try {
            val pb = ProcessBuilder(command)
                .directory(project.root)
                .redirectErrorStream(true)
            pb.environment()["JAVA_HOME"] = javaHome.absolutePath
            pb.environment()["ANDROID_HOME"] = sdkRoot.absolutePath
            pb.environment()["ANDROID_SDK_ROOT"] = sdkRoot.absolutePath
            pb.environment()["PATH"] = listOf(
                File(javaHome, "bin").absolutePath,
                File(sdkRoot, "platform-tools").absolutePath,
                File(sdkRoot, "build-tools/34.0.4").absolutePath,
                System.getenv("PATH") ?: ""
            ).joinToString(":")

            val process = pb.start()
            val log = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            val apk = project.root.walkTopDown()
                .firstOrNull { it.isFile && it.name.endsWith("-debug.apk") && it.path.contains("/outputs/apk/") }
            Result(code == 0 && apk != null, log, apk)
        } catch (e: Exception) {
            Result(false, "Build failed to start: ${e.message}", null)
        }
    }
}
