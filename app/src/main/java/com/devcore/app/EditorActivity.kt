package com.devcore.app

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
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
    }

    private fun reloadFiles() {
        files = ProjectStore.editableFiles(project)
        val labels = files.map { it.relativeTo(project.root).path }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val preferred = files.indexOfFirst { it.name == "MainActivity.kt" }.takeIf { it >= 0 } ?: 0
        if (files.isNotEmpty()) spinner.setSelection(preferred)
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

    override fun onPause() {
        saveCurrent(true)
        super.onPause()
    }
}
