package com.devcore.app

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView

class MainActivity : AppCompatActivity() {

    private val openProjectLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        Thread {
            runCatching { ProjectImporter.import(this, uri) }
                .onSuccess { project ->
                    runOnUiThread {
                        startActivity(Intent(this, EditorActivity::class.java).putExtra("projectPath", project.root.absolutePath))
                    }
                }
                .onFailure { error ->
                    runOnUiThread { Toast.makeText(this, "Import failed: ${error.message}", Toast.LENGTH_LONG).show() }
                }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<MaterialCardView>(R.id.newProject).setOnClickListener {
            startActivity(Intent(this, NewProjectActivity::class.java))
        }
        findViewById<MaterialCardView>(R.id.openProject).setOnClickListener {
            openProjectLauncher.launch(null)
        }
    }

    override fun onResume() {
        super.onResume()
        renderProjects()
    }

    private fun renderProjects() {
        val list = findViewById<LinearLayout>(R.id.projectList)
        list.removeAllViews()
        val projects = ProjectStore.list(this)

        if (projects.isEmpty()) {
            TextView(this).apply {
                text = "No projects yet"
                setTextColor(Color.parseColor("#757B86"))
                textSize = 15f
                setPadding(4, 18, 4, 18)
                list.addView(this)
            }
            return
        }

        projects.take(6).forEach { project ->
            val card = MaterialCardView(this).apply {
                radius = dp(18).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(Color.parseColor("#0B0D10"))
                strokeColor = Color.parseColor("#24272D")
                strokeWidth = dp(1)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(92)).apply {
                    bottomMargin = dp(12)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, EditorActivity::class.java).putExtra("projectPath", project.root.absolutePath))
                }
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }

            val iconBox = MaterialCardView(this).apply {
                radius = dp(14).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(Color.parseColor("#111318"))
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
            }
            val icon = ImageView(this).apply {
                setImageResource(R.drawable.ic_code)
                imageTintList = ContextCompat.getColorStateList(this@MainActivity, android.R.color.holo_orange_light)
                setPadding(dp(14), dp(14), dp(14), dp(14))
            }
            iconBox.addView(icon)

            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = dp(16) }
            }
            val name = TextView(this).apply {
                text = project.name
                setTextColor(Color.WHITE)
                textSize = 19f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            val date = TextView(this).apply {
                text = ProjectStore.prettyDate(project.updatedAt)
                setTextColor(Color.parseColor("#9298A5"))
                textSize = 13f
            }
            info.addView(name)
            info.addView(date)

            val more = ImageView(this).apply {
                setImageResource(R.drawable.ic_more_vert)
                imageTintList = ContextCompat.getColorStateList(this@MainActivity, android.R.color.white)
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
                setPadding(dp(7), dp(7), dp(7), dp(7))
            }

            row.addView(iconBox)
            row.addView(info)
            row.addView(more)
            card.addView(row)
            list.addView(card)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
