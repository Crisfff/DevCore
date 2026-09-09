package com.devcore.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<MaterialCardView>(R.id.newProject).setOnClickListener {
            Toast.makeText(this, "New Android project", Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialCardView>(R.id.openProject).setOnClickListener {
            Toast.makeText(this, "Open project", Toast.LENGTH_SHORT).show()
        }
    }
}
