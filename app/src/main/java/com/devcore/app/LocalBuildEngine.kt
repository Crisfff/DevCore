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

        val javaBin = File(paths.javaHome, "bin/java")
        val gradleLauncher = File(paths.gradleHome, "lib").listFiles()
            ?.firstOrNull { it.isFile && it.name.startsWith("gradle-launcher-") && it.extension == "jar" }

        javaBin.setExecutable(true, false)
        paths.aapt2.setExecutable(true, false)

        if (!javaBin.exists() || gradleLauncher == null) {
            return Result(false, "Java or Gradle launcher is missing from the installed toolchain.", null)
        }

        return try {
            val buildNumber = ProjectStore.nextBuildNumber(project)
            val command = listOf(
                javaBin.absolutePath,
                "-Duser.home=${context.filesDir.absolutePath}",
                "-Xmx1536m",
                "-classpath",
                gradleLauncher.absolutePath,
                "org.gradle.launcher.GradleMain",
                "--no-daemon",
                "--console=plain",
                "-PdevcoreBuildNumber=$buildNumber",
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
            pb.environment()["LD_LIBRARY_PATH"] = listOf(
                File(paths.javaHome, "lib").absolutePath,
                File(paths.javaHome, "lib/server").absolutePath,
                System.getenv("LD_LIBRARY_PATH") ?: ""
            ).filter { it.isNotBlank() }.joinToString(":")
            pb.environment()["PATH"] = listOf(
                File(paths.javaHome, "bin").absolutePath,
                paths.aapt2.parentFile?.absolutePath.orEmpty(),
                "/system/bin",
                "/system/xbin"
            ).filter { it.isNotBlank() }.joinToString(":")

            val process = pb.start()
            val log = process.inputStream.bufferedReader().use { it.readText() }
            val code = process.waitFor()
            val apk = project.root.walkTopDown()
                .filter { it.isFile && it.extension == "apk" }
                .filter { it.path.contains("/build/outputs/apk/") }
                .maxByOrNull { it.lastModified() }

            if (code == 0 && apk != null) {
                Result(true, "Build #$buildNumber\n$log\n\nAPK: ${apk.absolutePath}", apk)
            } else {
                Result(false, "Build #$buildNumber\n$log\n\nGradle exited with code $code.", apk)
            }
        } catch (e: Exception) {
            Result(false, "Build failed to start: ${e.javaClass.simpleName}: ${e.message}", null)
        }
    }
}
