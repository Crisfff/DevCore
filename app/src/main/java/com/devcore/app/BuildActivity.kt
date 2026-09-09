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
    private lateinit var status: TextView
    private lateinit var setupButton: Button
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
        status = findViewById(R.id.toolchainStatus)
        setupButton = findViewById(R.id.setupToolsButton)
        buildButton = findViewById(R.id.runBuildButton)
        installButton = findViewById(R.id.installApkButton)
        installButton.isEnabled = false

        refreshToolchainStatus()

        setupButton.setOnClickListener { setupToolchain() }
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

    private fun refreshToolchainStatus() {
        val manager = ToolchainManager(this)
        val paths = manager.resolve()
        when {
            !manager.isSupportedDevice() -> {
                status.text = "Local build currently supports arm64-v8a devices."
                setupButton.isEnabled = false
                buildButton.isEnabled = false
            }
            paths != null -> {
                val mb = manager.sizeBytes() / (1024 * 1024)
                status.text = "Build tools ready · ${mb} MB"
                setupButton.text = "Recheck Build Tools"
                buildButton.isEnabled = true
            }
            else -> {
                status.text = "Build tools not installed · first setup requires a large download"
                setupButton.text = "Setup Build Tools"
                buildButton.isEnabled = false
            }
        }
    }

    private fun setupToolchain() {
        val manager = ToolchainManager(this)
        if (manager.resolve() != null) {
            refreshToolchainStatus()
            output.text = "Toolchain verified. You can build now."
            return
        }

        setupButton.isEnabled = false
        buildButton.isEnabled = false
        output.text = "Preparing local build environment…\nKeep DevCore open during the first setup.\n"

        Thread {
            val result = manager.install { message ->
                runOnUiThread {
                    output.append("$message\n")
                    status.text = message
                }
            }
            runOnUiThread {
                setupButton.isEnabled = true
                result.onSuccess {
                    output.append("\n✓ Toolchain installed successfully.\n")
                    refreshToolchainStatus()
                }.onFailure { error ->
                    output.append("\n✗ Setup failed: ${error.message}\n")
                    status.text = "Build tools setup failed"
                    buildButton.isEnabled = false
                }
            }
        }.start()
    }

    private fun startBuild() {
        if (ToolchainManager(this).resolve() == null) {
            Toast.makeText(this, "Setup the local build tools first", Toast.LENGTH_SHORT).show()
            return
        }
        buildButton.isEnabled = false
        installButton.isEnabled = false
        builtApk = null
        output.text = "Starting local Gradle build…\n"

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
