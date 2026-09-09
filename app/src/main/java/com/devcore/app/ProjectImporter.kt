package com.devcore.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

object ProjectImporter {
    fun import(context: Context, treeUri: Uri): ProjectStore.Project {
        val source = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Could not open selected folder")
        val name = source.name?.ifBlank { "ImportedProject" } ?: "ImportedProject"
        val base = File(context.filesDir, "projects").apply { mkdirs() }
        var target = File(base, name)
        var i = 2
        while (target.exists()) {
            target = File(base, "$name-$i")
            i++
        }
        target.mkdirs()
        copyTree(context, source, target)

        val packageName = detectPackage(target) ?: "com.example.${target.name.lowercase().replace('-', '_')}"
        val meta = File(target, ".devcore/project.properties")
        meta.parentFile?.mkdirs()
        meta.writeText("name=${target.name}\npackage=$packageName\ncreated=${System.currentTimeMillis()}\n")
        target.setLastModified(System.currentTimeMillis())
        return ProjectStore.Project(target.name, packageName, target, target.lastModified())
    }

    private fun copyTree(context: Context, node: DocumentFile, target: File) {
        node.listFiles().forEach { child ->
            val safeName = child.name ?: return@forEach
            if (safeName in setOf(".gradle", "build", ".idea")) return@forEach
            val out = File(target, safeName)
            if (child.isDirectory) {
                out.mkdirs()
                copyTree(context, child, out)
            } else if (child.isFile) {
                out.parentFile?.mkdirs()
                context.contentResolver.openInputStream(child.uri).use { input ->
                    requireNotNull(input) { "Could not read $safeName" }
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun detectPackage(root: File): String? {
        val gradle = File(root, "app/build.gradle.kts").takeIf { it.exists() }
            ?: File(root, "app/build.gradle").takeIf { it.exists() }
        if (gradle != null) {
            val text = runCatching { gradle.readText() }.getOrDefault("")
            Regex("(?:namespace|applicationId)\\s*(?:=\\s*)?[\\\"']([^\\\"']+)[\\\"']")
                .find(text)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return root.walkTopDown().firstOrNull { it.name == "AndroidManifest.xml" }
            ?.readText()
            ?.let { Regex("package=\\\"([^\\\"]+)\\\"").find(it)?.groupValues?.getOrNull(1) }
    }
}
