package com.uzair.streamtranslator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var key: EditText
    private lateinit var base: EditText
    private lateinit var model: EditText
    private lateinit var toggle: Button
    private lateinit var status: TextView
    private lateinit var log: TextView

    private val reqPerms = 1
    private val reqCapture = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("s", MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun text(t: String, size: Float, color: Int): TextView = TextView(this).apply {
            text = t
            textSize = size
            setTextColor(color)
            setPadding(0, pad / 2, 0, pad / 4)
        }

        fun field(hint: String, value: String, secret: Boolean = false): EditText = EditText(this).apply {
            this.hint = hint
            setText(value)
            setSingleLine(true)
            val variation = if (secret) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_TEXT_VARIATION_URI
            }
            inputType = InputType.TYPE_CLASS_TEXT or variation
        }

        root.addView(text("Stream Translator", 24f, 0xFFE8EEF2.toInt()))
        root.addView(
            text(
                "1. Paste an API key for a Whisper service (a free key from console.groq.com works).\n" +
                    "2. Tap Start. Android asks to record or cast your screen. Allow it. Only sound is used.\n" +
                    "3. Open YouTube or any app. The English is spoken in your earbuds and shown here.",
                14f, 0xFF93A3B1.toInt()
            )
        )

        root.addView(text("API key", 13f, 0xFF93A3B1.toInt()))
        key = field("Paste key here", prefs.getString("key", "") ?: "", secret = true)
        root.addView(key)

        root.addView(text("Service address", 13f, 0xFF93A3B1.toInt()))
        base = field("Service address", prefs.getString("base", "https://api.groq.com/openai/v1") ?: "")
        root.addView(base)

        root.addView(text("Model", 13f, 0xFF93A3B1.toInt()))
        model = field("Model", prefs.getString("model", "whisper-large-v3") ?: "")
        root.addView(model)

        toggle = Button(this).apply { textSize = 18f }
        root.addView(
            toggle,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = pad }
        )

        status = text("", 14f, 0xFFFB923C.toInt())
        root.addView(status)
        log = text("", 18f, 0xFFE8EEF2.toInt())
        root.addView(log)

        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(root)
        })

        toggle.setOnClickListener { onToggle() }
    }

    private fun save() {
        prefs.edit()
            .putString("key", key.text.toString().trim())
            .putString("base", base.text.toString().trim())
            .putString("model", model.text.toString().trim())
            .apply()
    }

    private fun onToggle() {
        if (CaptureService.running) {
            startService(Intent(this, CaptureService::class.java).setAction("STOP"))
            return
        }
        save()
        if (key.text.isBlank()) {
            status.text = "Paste your API key first."
            return
        }
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), reqPerms)
        } else {
            askCapture()
        }
    }

    private fun askCapture() {
val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code != reqPerms) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            askCapture()
        } else {
            status.text = "The microphone permission is needed to capture sound. Allow it in Android settings."
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqCapture) return
        if (resultCode == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, CaptureService::class.java)
                    .putExtra("code", resultCode)
                    .putExtra("data", data)
            )
        } else {
            status.text = "Capture permission was not given."
        }
    }

    private fun refresh() {
        toggle.text = if (CaptureService.running) "Stop" else "Start translating"
        status.text = CaptureService.statusText
        log.text = CaptureService.lines.joinToString("\n\n")
    }

    override fun onResume() {
        super.onResume()
        CaptureService.listener = { runOnUiThread { refresh() } }
        refresh()
    }

    override fun onPause() {
        CaptureService.listener = null
        super.onPause()
    }
}
