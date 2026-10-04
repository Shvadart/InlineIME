package io.github.shvadart.inlineime

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val padding = (24 * resources.displayMetrics.density).toInt()
        val prefs = getSharedPreferences("ai_completion", MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, padding, padding, padding)
        }

        root.addView(TextView(this).apply {
            text = "InlineIME"
            textSize = 30f
        })

        root.addView(TextView(this).apply {
            text = "Pre-alpha keyboard. Enable InlineIME in Android settings, then select it as the current keyboard."
            textSize = 16f
            setPadding(0, padding, 0, padding)
        })

        root.addView(Button(this).apply {
            text = "1. Enable InlineIME"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        })

        root.addView(Button(this).apply {
            text = "2. Choose keyboard"
            setOnClickListener {
                getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            }
        })

        root.addView(TextView(this).apply {
            text = "AI completion"
            textSize = 20f
            setPadding(0, padding, 0, 8)
        })

        val aiEnabled = Switch(this).apply {
            text = "Enable AI completions"
            isChecked = prefs.getBoolean(KEY_AI_ENABLED, false)
        }
        root.addView(aiEnabled)

        val endpoint = EditText(this).apply {
            hint = "Backend endpoint"
            setText(prefs.getString(KEY_AI_ENDPOINT, "").orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        root.addView(
            endpoint,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        root.addView(TextView(this).apply {
            text = "The model API key stays on your backend and is not stored in the APK."
            textSize = 13f
            setPadding(0, 8, 0, 8)
        })

        root.addView(Button(this).apply {
            text = "Save AI settings"
            setOnClickListener {
                val value = endpoint.text.toString().trim()
                if (aiEnabled.isChecked && value.isBlank()) {
                    Toast.makeText(this@MainActivity, "Enter the backend endpoint first", Toast.LENGTH_SHORT).show()
                } else {
                    prefs.edit()
                        .putBoolean(KEY_AI_ENABLED, aiEnabled.isChecked)
                        .putString(KEY_AI_ENDPOINT, value)
                        .apply()
                    Toast.makeText(this@MainActivity, "AI settings saved", Toast.LENGTH_SHORT).show()
                }
            }
        })

        setContentView(root)
    }
    private companion object {
        const val KEY_AI_ENDPOINT = "endpoint"
        const val KEY_AI_ENABLED = "enabled"
    }
}
