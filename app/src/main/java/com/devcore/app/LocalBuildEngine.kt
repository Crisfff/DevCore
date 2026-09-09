package com.devcore.app

import android.content.Context
import java.io.File
import java.util.concurrent.Executors

class LocalBuildEngine(private val context: Context) {

    data class Result(val success: Boolean, val log: String, val apk: File?)

    private val executor = Executors.newSingleThreadExecutor()

    fun build(project: ProjectStore.Project, callback: (Result) -> Unit) {
        executor.execute { callback(runBuild(project)) }
    }

    private fun runBuild(project: ProjectStore.Project): Result {
        val paths = ToolchainManager(context).resolve()
            ?: return Result(false, "Local build tools are not installed. Tap Setup Build Tools first.", null)

        val gradleBin = File(paths.gradleHome, "bin/gradle")
        val javaBin = File(paths.javaHome, "bin/java")
        gradleBin.setExecutable(true, false)
        javaBin.setExecutable(true, false)
        paths.aapt2.setExecutable(true, false)

        if (!gradleBin.exists() || !javaBin.exists()) {
            return Result(false, "Java or Gradle executable is missing from the installed toolchain.", null)
        }

        return try {
            val command = listOf(
                gradleBin.absolutePath,
                "--no-daemon",
                "--console=plain",
                "-Pandroid.aapt2FromMavenOverride=${paths.aapt2.absolutePath}",
                "assembleDebug"
            )

            val pb = ProcessBuilder(command)
                .directory(project.root)
                .redirectErrorStream(true)

            pb.environment()["JAVA_HOME"] = paths.javaHome.absolutePath
            pb.environment()["ANDROID_HOME"] = paths.sdkRoot.absolutePath
            pb.environment()["ANDROID_SDK_ROOT"] = paths.sdkRoot.absolutePath
            pb.environment()["GRADLE_USER_HOME"] = File(context.filesDir, "gradle-home").apply { mkdirs() }.absolutePath
            pb.environment()["HOME"] = context.filesDir.absolutePath
            pb.environment()["PATH"] = listOf(
                File(paths.javaHome, "bin").absolutePath,
                File(paths.gradleHome, "bin").absolutePath,
                paths.aapt2.parentFile?.absolutePath.orEmpty(),
                System.getenv("PATH") ?: "/system/bin"
            ).filter { it.isNotBlank() }.joinToString(":")

            val process = pb.start()
            val log = process.inputStream.bufferedReader().use { it.readText() }
            val code = process.waitFor()
            val apk = project.root.walkTopDown()
                .filter { it.isFile && it.extension == "apk" }
                .filter { it.path.contains("/build/outputs/apk/") }
                .maxByOrNull { it.lastModified() }

            if (code == 0 && apk != null) {
                Result(true, "$log\n\nAPK: ${apk.absolutePath}", apk)
            } else {
                Result(false, "$log\n\nGradle exited with code $code.", apk)
            }
        } catch (e: Exception) {
            Result(false, "Build failed to start: ${e.javaClass.simpleName}: ${e.message}", null)
        }
    }
}
