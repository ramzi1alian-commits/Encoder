package com.securekeyboard.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // This screen can show sensitive setup info, so block screenshots here too.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContentView(R.layout.activity_settings)

        findViewById<android.view.View>(R.id.btnEnable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        findViewById<android.view.View>(R.id.btnSwitch).setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        findViewById<android.view.View>(R.id.btnEncrypt).setOnClickListener {
            startActivity(Intent(this, EncryptActivity::class.java))
        }

        findViewById<android.view.View>(R.id.btnThemeSettings).setOnClickListener {
            startActivity(Intent(this, ThemeSettingsActivity::class.java))
        }

        val accent = resources.getColor(Prefs.accentColorRes(this), theme)
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEnable).backgroundTintList =
            android.content.res.ColorStateList.valueOf(accent)
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEncrypt).backgroundTintList =
            android.content.res.ColorStateList.valueOf(accent)

        Fonts.applyToTree(findViewById(android.R.id.content), Fonts.currentTypeface(this))
    }
}
