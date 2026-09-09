package com.devcore.app

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class BuildActivity : AppCompatActivity() {
    private lateinit var project: ProjectStore.Project
    private lateinit var output: TextView
    private lateinit var buildButton: Button
    private lateinit var installButton: Button
    private var builtApk: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_build)

        val path = intent.getStringExtra("projectPath") ?: run { finish(); return }
        project = ProjectStore.get(this, path) ?: run { finish(); return }

        findViewById<TextView>(R.id.buildProjectName).text = project.name
        output = findViewById(R.id.buildOutput)
        buildButton = findViewById(R.id.runBuildButton)
        installButton = findViewById(R.id.installApkButton)
        installButton.isEnabled = false

        buildButton.setOnClickListener { startBuild() }
        installButton.setOnClickListener {
            val apk = builtApk
            if (apk == null || !apk.exists()) {
                Toast.makeText(this, "APK not available", Toast.LENGTH_SHORT).show()
            } else {
                runCatching { ApkInstaller.install(this, apk) }
                    .onFailure { Toast.makeText(this, it.message ?: "Install failed", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun startBuild() {
        buildButton.isEnabled = false
        installButton.isEnabled = false
        builtApk = null
        output.text = "Starting local build...\n"

        LocalBuildEngine(this).build(project) { result ->
            runOnUiThread {
                output.text = result.log
                builtApk = result.apk
                buildButton.isEnabled = true
                installButton.isEnabled = result.success && result.apk != null
                if (result.success) {
                    Toast.makeText(this, "Build successful", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
