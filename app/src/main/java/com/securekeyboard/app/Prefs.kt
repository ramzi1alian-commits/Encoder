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
    /**
     * IMPORTANT (fixed after a real bug report): this used to return the raw
     * generic families Typeface.SERIF / Typeface.MONOSPACE for the "classic"
     * and "mono" choices. On many ROMs (MIUI/Xiaomi in particular) those
     * generic families have NO Arabic glyph coverage at all, so Arabic text
     * silently fell back to tofu boxes or unrelated glyphs from a fallback
     * font - this is exactly what showed up as "symbols instead of letters"
     * on the keyboard.
     *
     * Typeface.DEFAULT (and its bold variant) is backed by the system's
     * default font family, which always includes a proper Arabic fallback
     * chain on stock Android and every major OEM skin. So instead of
     * swapping the font family for "classic"/"mono", we keep the same
     * Arabic-safe family and only vary the style, which gives visual
     * differentiation without ever risking broken Arabic rendering.
     */
    fun currentTypeface(context: Context): Typeface {
        return when (Prefs.fontChoice(context)) {
            1 -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            2 -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            else -> Typeface.DEFAULT
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

/**
 * Centralizes accent-color application so every screen (including the
 * theme-picker screen itself) actually reflects the chosen accent, instead
 * of each Activity manually tinting one or two buttons and forgetting the
 * rest - which was the root cause of "the theme doesn't apply" bug reports.
 */
object ThemeUtil {

    fun accentColor(context: Context): Int =
        androidx.core.content.ContextCompat.getColor(context, Prefs.accentColorRes(context))

    /** Tints a filled ("primary") button's background with the current accent. */
    fun tintPrimary(context: Context, vararg buttons: com.google.android.material.button.MaterialButton) {
        val tint = android.content.res.ColorStateList.valueOf(accentColor(context))
        buttons.forEach { it.backgroundTintList = tint }
    }

    /** Colors an outline ("secondary") button's stroke with the current accent. */
    fun tintOutline(context: Context, vararg buttons: com.google.android.material.button.MaterialButton) {
        val color = accentColor(context)
        buttons.forEach {
            it.strokeColor = android.content.res.ColorStateList.valueOf(color)
            it.setTextColor(color)
        }
    }

    /**
     * Draws a colored selection border around a plain swatch card (a
     * LinearLayout, not a Material widget) so the user can see which
     * accent color is active - this was missing entirely before, which
     * made color choices look like they weren't being saved.
     */
    fun setSelected(view: android.view.View, selected: Boolean, accent: Int) {
        val bg = android.graphics.drawable.GradientDrawable()
        bg.cornerRadius = 10f * view.resources.displayMetrics.density
        bg.setColor(androidx.core.content.ContextCompat.getColor(view.context, R.color.navy_800))
        val strokeWidthDp = if (selected) 2f else 1f
        val strokeColor = if (selected) accent else androidx.core.content.ContextCompat.getColor(view.context, R.color.navy_700)
        bg.setStroke((strokeWidthDp * view.resources.displayMetrics.density).toInt(), strokeColor)
        view.background = bg
    }

    /**
     * Same idea but for a MaterialButton (mode/font/density buttons) -
     * uses MaterialButton's own stroke properties instead of replacing its
     * background drawable outright, so we don't fight its built-in ripple
     * and corner-radius handling.
     */
    fun setSelected(
        button: com.google.android.material.button.MaterialButton,
        selected: Boolean,
        accent: Int
    ) {
        val context = button.context
        if (selected) {
            button.strokeWidth = (2 * context.resources.displayMetrics.density).toInt()
            button.strokeColor = android.content.res.ColorStateList.valueOf(accent)
            button.setTextColor(accent)
        } else {
            button.strokeWidth = (1 * context.resources.displayMetrics.density).toInt()
            button.strokeColor = android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(context, R.color.navy_700)
            )
            button.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.slate_200))
        }
    }
}
