package com.devcore.app

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class EditorActivity : AppCompatActivity() {
    private lateinit var project: ProjectStore.Project
    private lateinit var files: List<File>
    private lateinit var spinner: Spinner
    private lateinit var editor: EditText
    private lateinit var pathLabel: TextView
    private var currentFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        val projectPath = intent.getStringExtra("projectPath") ?: run { finish(); return }
        project = ProjectStore.get(this, projectPath) ?: run {
            Toast.makeText(this, "Project not found", Toast.LENGTH_LONG).show()
            finish(); return
        }

        findViewById<TextView>(R.id.editorProjectName).text = project.name
        spinner = findViewById(R.id.fileSpinner)
        editor = findViewById(R.id.codeEditor)
        pathLabel = findViewById(R.id.filePathLabel)

        reloadFiles()

        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                saveCurrent(silent = true)
                if (position in files.indices) openFile(files[position])
            }
        }

        findViewById<Button>(R.id.saveFileButton).setOnClickListener { saveCurrent(false) }
        findViewById<Button>(R.id.buildProjectButton).setOnClickListener {
            saveCurrent(true)
            startActivity(Intent(this, BuildActivity::class.java).putExtra("projectPath", project.root.absolutePath))
        }
        findViewById<Button>(R.id.newFileButton).setOnClickListener { showCreateDialog(folder = false) }
        findViewById<Button>(R.id.newFolderButton).setOnClickListener { showCreateDialog(folder = true) }
        findViewById<Button>(R.id.deleteFileButton).setOnClickListener { confirmDeleteCurrent() }
    }

    private fun reloadFiles(select: File? = null) {
        files = ProjectStore.editableFiles(project)
        val labels = files.map { it.relativeTo(project.root).path }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        if (files.isEmpty()) {
            currentFile = null
            pathLabel.text = "No editable files"
            editor.setText("")
            return
        }
        val preferred = when {
            select != null -> files.indexOfFirst { it.absolutePath == select.absolutePath }.takeIf { it >= 0 }
            currentFile != null -> files.indexOfFirst { it.absolutePath == currentFile?.absolutePath }.takeIf { it >= 0 }
            else -> files.indexOfFirst { it.name == "MainActivity.kt" }.takeIf { it >= 0 }
        } ?: 0
        spinner.setSelection(preferred)
        openFile(files[preferred])
    }

    private fun openFile(file: File) {
        currentFile = file
        pathLabel.text = file.relativeTo(project.root).path
        editor.setText(runCatching { file.readText() }.getOrDefault(""))
        editor.setSelection(editor.text.length)
    }

    private fun saveCurrent(silent: Boolean) {
        val file = currentFile ?: return
        runCatching {
            file.writeText(editor.text.toString())
            ProjectStore.touch(project)
        }.onSuccess {
            if (!silent) Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "Save failed: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showCreateDialog(folder: Boolean) {
        val input = EditText(this).apply {
            hint = if (folder) "app/src/main/res/drawable" else "app/src/main/java/com/example/MyFile.kt"
            setSingleLine(true)
            setPadding(36, 12, 36, 12)
        }
        AlertDialog.Builder(this)
            .setTitle(if (folder) "New Folder" else "New File")
            .setMessage("Enter a path relative to the project root")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                runCatching {
                    if (folder) {
                        ProjectStore.createFolder(project, input.text.toString())
                        null
                    } else {
                        ProjectStore.createFile(project, input.text.toString())
                    }
                }.onSuccess { created ->
                    reloadFiles(created)
                }.onFailure {
                    Toast.makeText(this, it.message ?: "Could not create item", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun confirmDeleteCurrent() {
        val file = currentFile ?: return
        val relative = file.relativeTo(project.root).path
        AlertDialog.Builder(this)
            .setTitle("Delete file?")
            .setMessage(relative)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                runCatching { ProjectStore.deleteFile(project, file) }
                    .onSuccess {
                        currentFile = null
                        reloadFiles()
                    }
                    .onFailure { Toast.makeText(this, it.message ?: "Delete failed", Toast.LENGTH_LONG).show() }
            }
            .show()
    }

    override fun onPause() {
        saveCurrent(true)
        super.onPause()
    }
}
