package com.securekeyboard.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

class ThemeSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_theme_settings)

        findViewById<android.view.View>(R.id.swatchCyan).setOnClickListener {
            Prefs.setAccentColorRes(this, R.color.accent_cyan)
            recreate()
        }
        findViewById<android.view.View>(R.id.swatchTeal).setOnClickListener {
            Prefs.setAccentColorRes(this, R.color.accent_teal)
            recreate()
        }
        findViewById<android.view.View>(R.id.swatchGold).setOnClickListener {
            Prefs.setAccentColorRes(this, R.color.accent_gold)
            recreate()
        }
        findViewById<android.view.View>(R.id.swatchPurple).setOnClickListener {
            Prefs.setAccentColorRes(this, R.color.accent_purple)
            recreate()
        }

        findViewById<android.view.View>(R.id.btnDayMode).setOnClickListener {
            Prefs.setDarkMode(this, false)
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
        findViewById<android.view.View>(R.id.btnNightMode).setOnClickListener {
            Prefs.setDarkMode(this, true)
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }

        findViewById<android.view.View>(R.id.btnFontSans).setOnClickListener {
            Prefs.setFontChoice(this, 0)
            recreate()
        }
        findViewById<android.view.View>(R.id.btnFontSerif).setOnClickListener {
            Prefs.setFontChoice(this, 1)
            recreate()
        }
        findViewById<android.view.View>(R.id.btnFontMono).setOnClickListener {
            Prefs.setFontChoice(this, 2)
            recreate()
        }

        findViewById<android.view.View>(R.id.btnCompact).setOnClickListener {
            Prefs.setDensity(this, 1)
        }
        findViewById<android.view.View>(R.id.btnComfortable).setOnClickListener {
            Prefs.setDensity(this, 0)
        }

        applyFontToScreen()
    }

    private fun applyFontToScreen() {
        val root = findViewById<android.view.View>(android.R.id.content)
        Fonts.applyToTree(root, Fonts.currentTypeface(this))
    }
}
