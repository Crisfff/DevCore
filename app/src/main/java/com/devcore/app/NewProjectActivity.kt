package com.devcore.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class NewProjectActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_project)

        val nameInput = findViewById<EditText>(R.id.projectName)
        val packageInput = findViewById<EditText>(R.id.packageName)

        nameInput.setText("MyApp")
        packageInput.setText("com.example.myapp")

        findViewById<Button>(R.id.createProjectButton).setOnClickListener {
            val name = nameInput.text.toString().trim()
            val pkg = packageInput.text.toString().trim()

            if (name.isBlank()) {
                nameInput.error = "Project name is required"
                return@setOnClickListener
            }
            if (!pkg.matches(Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$"))) {
                packageInput.error = "Use a valid package, for example com.example.myapp"
                return@setOnClickListener
            }

            try {
                val project = ProjectStore.create(this, name, pkg)
                startActivity(Intent(this, EditorActivity::class.java).putExtra("projectPath", project.root.absolutePath))
                finish()
            } catch (e: Exception) {
                Toast.makeText(this, "Could not create project: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
