package com.devcore.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<MaterialButton>(R.id.newProject).setOnClickListener {
            Toast.makeText(this, "New Android project", Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.openProject).setOnClickListener {
            Toast.makeText(this, "Open project", Toast.LENGTH_SHORT).show()
        }
    }
}
