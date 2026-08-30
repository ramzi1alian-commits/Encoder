package com.securekeyboard.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
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

        findViewById<android.view.View>(R.id.btnClearLearned).setOnClickListener {
            // Immediate, user-triggered wipe of the personal learned-word
            // dictionary (see LearnedDictionary.kt) - both the in-memory
            // map and the on-disk file. Does not touch the static bundled
            // dictionary (WordDictionary), which was never user data to
            // begin with.
            LearnedDictionary.clear(this)
            Toast.makeText(this, R.string.learned_cleared_toast, Toast.LENGTH_SHORT).show()
        }

        applyCurrentTheme()
    }

    override fun onResume() {
        super.onResume()
        // Re-apply (without a full recreate, to avoid a recreate/onResume
        // loop) in case the user changed the accent/font on the theme
        // screen and came back here - onCreate alone would miss that.
        applyCurrentTheme()
    }

    private fun applyCurrentTheme() {
        ThemeUtil.tintPrimary(
            this,
            findViewById(R.id.btnEnable),
            findViewById(R.id.btnEncrypt)
        )
        ThemeUtil.tintOutline(
            this,
            findViewById(R.id.btnSwitch),
            findViewById(R.id.btnThemeSettings),
            findViewById(R.id.btnClearLearned)
        )
        Fonts.applyToTree(findViewById(android.R.id.content), Fonts.currentTypeface(this))
    }
}
