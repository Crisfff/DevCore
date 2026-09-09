package com.devcore.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ProjectStore {
    data class Project(
        val name: String,
        val packageName: String,
        val root: File,
        val updatedAt: Long
    )

    private fun projectsRoot(context: Context): File =
        File(context.filesDir, "projects").apply { mkdirs() }

    fun list(context: Context): List<Project> =
        projectsRoot(context).listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { root ->
                val meta = File(root, ".devcore/project.properties")
                if (!meta.exists()) return@mapNotNull null
                val props = meta.readLines().associate {
                    val i = it.indexOf('=')
                    if (i < 0) it to "" else it.substring(0, i) to it.substring(i + 1)
                }
                Project(
                    props["name"] ?: root.name,
                    props["package"] ?: "com.example.app",
                    root,
                    root.lastModified()
                )
            }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()

    fun get(context: Context, rootPath: String): Project? {
        val root = File(rootPath)
        if (!root.exists() || !root.isDirectory) return null
        return list(context).firstOrNull { it.root.absolutePath == root.absolutePath }
    }

    fun create(context: Context, name: String, packageName: String): Project {
        val safeName = name.trim().ifBlank { "MyApp" }.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val root = uniqueProjectDir(projectsRoot(context), safeName)
        val pkg = packageName.trim().ifBlank { "com.example.${safeName.lowercase(Locale.US)}" }
        val packagePath = pkg.replace('.', '/')

        val files = linkedMapOf(
            "settings.gradle.kts" to """
                pluginManagement {
                    repositories { google(); mavenCentral(); gradlePluginPortal() }
                }
                dependencyResolutionManagement {
                    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                    repositories { google(); mavenCentral() }
                }
                rootProject.name = "$safeName"
                include(":app")
            """.trimIndent(),
            "build.gradle.kts" to """
                plugins {
                    id("com.android.application") version "8.7.3" apply false
                    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
                }
            """.trimIndent(),
            "gradle.properties" to """
                android.useAndroidX=true
                android.enableJetifier=true
                org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
                kotlin.code.style=official
            """.trimIndent(),
            "app/build.gradle.kts" to """
                plugins {
                    id("com.android.application")
                    id("org.jetbrains.kotlin.android")
                }

                val devCoreBuildNumber = providers.gradleProperty("devcoreBuildNumber").orNull?.toIntOrNull() ?: 1

                android {
                    namespace = "$pkg"
                    compileSdk = 34

                    defaultConfig {
                        applicationId = "$pkg"
                        minSdk = 26
                        targetSdk = 34
                        versionCode = devCoreBuildNumber
                        versionName = "1.0.${'$'}devCoreBuildNumber"
                    }

                    compileOptions {
                        sourceCompatibility = JavaVersion.VERSION_17
                        targetCompatibility = JavaVersion.VERSION_17
                    }
                    kotlinOptions { jvmTarget = "17" }
                }

                dependencies {
                    implementation("androidx.core:core-ktx:1.15.0")
                    implementation("androidx.appcompat:appcompat:1.7.0")
                    implementation("com.google.android.material:material:1.12.0")
                }
            """.trimIndent(),
            "app/src/main/AndroidManifest.xml" to """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application
                        android:theme="@style/Theme.App"
                        android:label="$safeName">
                        <activity android:name=".MainActivity" android:exported="true">
                            <intent-filter>
                                <action android:name="android.intent.action.MAIN" />
                                <category android:name="android.intent.category.LAUNCHER" />
                            </intent-filter>
                        </activity>
                    </application>
                </manifest>
            """.trimIndent(),
            "app/src/main/java/$packagePath/MainActivity.kt" to """
                package $pkg

                import android.os.Bundle
                import androidx.appcompat.app.AppCompatActivity

                class MainActivity : AppCompatActivity() {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        setContentView(R.layout.activity_main)
                    }
                }
            """.trimIndent(),
            "app/src/main/res/layout/activity_main.xml" to """
                <?xml version="1.0" encoding="utf-8"?>
                <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent"
                    android:background="#000000">

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_gravity="center"
                        android:text="Hello"
                        android:textColor="#FFFFFF"
                        android:textSize="32sp" />
                </FrameLayout>
            """.trimIndent(),
            "app/src/main/res/values/styles.xml" to """
                <resources>
                    <style name="Theme.App" parent="Theme.MaterialComponents.DayNight.NoActionBar">
                        <item name="android:windowBackground">#000000</item>
                        <item name="android:statusBarColor">#000000</item>
                        <item name="android:navigationBarColor">#000000</item>
                    </style>
                </resources>
            """.trimIndent()
        )

        files.forEach { (path, text) ->
            val file = File(root, path)
            file.parentFile?.mkdirs()
            file.writeText(text)
        }

        val meta = File(root, ".devcore/project.properties")
        meta.parentFile?.mkdirs()
        meta.writeText("name=$safeName\npackage=$pkg\ncreated=${System.currentTimeMillis()}\n")
        File(root, ".devcore/build-number").writeText("0")
        root.setLastModified(System.currentTimeMillis())
        return Project(safeName, pkg, root, root.lastModified())
    }

    fun editableFiles(project: Project): List<File> =
        project.root.walkTopDown()
            .filter { it.isFile }
            .filterNot { it.path.contains("/.gradle/") || it.path.contains("/build/") || it.path.contains("/.devcore/") }
            .filter { it.extension.lowercase(Locale.US) in setOf("kt", "kts", "xml", "json", "properties", "gradle", "java", "txt", "md") }
            .sortedBy { it.relativeTo(project.root).path }
            .toList()

    fun createFile(project: Project, relativePath: String): File {
        val cleaned = relativePath.trim().trimStart('/').replace("\\", "/")
        require(cleaned.isNotBlank()) { "File path is empty" }
        val file = File(project.root, cleaned)
        require(file.canonicalPath.startsWith(project.root.canonicalPath + File.separator)) { "Invalid file path" }
        require(!file.exists()) { "File already exists" }
        file.parentFile?.mkdirs()
        file.writeText(defaultContentFor(file, project))
        touch(project)
        return file
    }

    fun createFolder(project: Project, relativePath: String): File {
        val cleaned = relativePath.trim().trimStart('/').replace("\\", "/")
        require(cleaned.isNotBlank()) { "Folder path is empty" }
        val folder = File(project.root, cleaned)
        require(folder.canonicalPath.startsWith(project.root.canonicalPath + File.separator)) { "Invalid folder path" }
        require(!folder.exists()) { "Folder already exists" }
        require(folder.mkdirs()) { "Could not create folder" }
        touch(project)
        return folder
    }

    fun deleteFile(project: Project, file: File) {
        require(file.canonicalPath.startsWith(project.root.canonicalPath + File.separator)) { "Invalid file" }
        require(!file.path.contains("/.devcore/")) { "Protected DevCore file" }
        if (file.isDirectory) file.deleteRecursively() else require(file.delete()) { "Could not delete file" }
        touch(project)
    }

    fun nextBuildNumber(project: Project): Int {
        val file = File(project.root, ".devcore/build-number")
        file.parentFile?.mkdirs()
        val current = file.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
        val next = current + 1
        file.writeText(next.toString())
        return next
    }

    fun touch(project: Project) {
        project.root.setLastModified(System.currentTimeMillis())
    }

    fun prettyDate(time: Long): String =
        SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.getDefault()).format(Date(time))

    private fun defaultContentFor(file: File, project: Project): String = when (file.extension.lowercase(Locale.US)) {
        "kt" -> "package ${project.packageName}\n\n"
        "xml" -> "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
        "json" -> "{}\n"
        else -> ""
    }

    private fun uniqueProjectDir(base: File, name: String): File {
        var candidate = File(base, name)
        var index = 2
        while (candidate.exists()) {
            candidate = File(base, "$name-$index")
            index++
        }
        candidate.mkdirs()
        return candidate
    }
}
