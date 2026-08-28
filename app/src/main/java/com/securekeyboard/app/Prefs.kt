package com.securekeyboard.app

import android.content.Context
import android.graphics.Typeface

object Prefs {
    private const val FILE = "secure_keyboard_prefs"
    private const val KEY_ACCENT = "accent_color"
    private const val KEY_DARK = "dark_mode"
    private const val KEY_FONT = "font_choice"
    private const val KEY_DENSITY = "density"

    fun accentColorRes(context: Context): Int {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_ACCENT, R.color.accent_cyan)
    }

    fun setAccentColorRes(context: Context, colorRes: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_ACCENT, colorRes).apply()
    }

    fun isDarkMode(context: Context): Boolean {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DARK, true)
    }

    fun setDarkMode(context: Context, dark: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DARK, dark).apply()
    }

    // 0 = default sans-serif, 1 = serif, 2 = monospace
    fun fontChoice(context: Context): Int {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_FONT, 0)
    }

    fun setFontChoice(context: Context, choice: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_FONT, choice).apply()
    }

    // 0 = comfortable, 1 = compact
    fun density(context: Context): Int {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_DENSITY, 0)
    }

    fun setDensity(context: Context, value: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_DENSITY, value).apply()
    }
}

object Fonts {
    fun currentTypeface(context: Context): Typeface {
        return when (Prefs.fontChoice(context)) {
            1 -> Typeface.SERIF
            2 -> Typeface.MONOSPACE
            else -> Typeface.SANS_SERIF
        }
    }

    /** Recursively applies the chosen typeface to every TextView/Button/EditText in a view tree. */
    fun applyToTree(view: android.view.View, typeface: Typeface) {
        if (view is android.widget.TextView) {
            view.typeface = typeface
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                applyToTree(view.getChildAt(i), typeface)
            }
        }
    }
}
