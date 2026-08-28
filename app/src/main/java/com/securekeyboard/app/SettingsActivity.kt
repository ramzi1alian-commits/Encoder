package com.securekeyboard.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // This screen can show sensitive setup info, so block screenshots here too.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        layout.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 20f
        })

        layout.addView(TextView(this).apply {
            text = getString(R.string.setup_explainer)
            textSize = 14f
            setPadding(0, 24, 0, 32)
        })

        layout.addView(Button(this).apply {
            text = getString(R.string.enable_keyboard)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        })

        layout.addView(Button(this).apply {
            text = getString(R.string.switch_keyboard)
            setOnClickListener {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            }
        })

        setContentView(layout)
    }
}
