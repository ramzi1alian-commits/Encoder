package com.securekeyboard.app

import android.content.Context
import android.graphics.Typeface

object Prefs {
    private const val FILE = "secure_keyboard_prefs"
    private const val KEY_ACCENT = "accent_color_name"
    private const val KEY_DARK = "dark_mode"
    private const val KEY_FONT = "font_choice"
    private const val KEY_DENSITY = "density"
    private const val KEY_HEIGHT = "keyboard_height"

    // Accent colors are stored as a stable string key, NOT as a raw
    // android resource id (the previous implementation did
    // prefs.getInt(KEY_ACCENT, R.color.accent_cyan) / putInt(..., colorRes)).
    // That was a real correctness/security bug: resource ids are NOT
    // guaranteed stable across builds, especially with shrinkResources +
    // minifyEnabled (both enabled in this project's release build) which
    // can renumber resource ids when unused resources are stripped. A
    // value saved by one build could silently resolve to a DIFFERENT,
    // unrelated resource (or none at all) after an update, which can
    // crash the input method service - and a crashing keyboard is a
    // denial-of-service that can lock a user out of typing entirely,
    // which matters a lot more on a device relied on for secure/urgent
    // communication. A string key looked up through a fixed map has no
    // such failure mode and safely falls back to a default if the
    // stored value is ever unrecognized (e.g. after a downgrade).
    private const val ACCENT_CYAN = "cyan"
    private const val ACCENT_TEAL = "teal"
    private const val ACCENT_GOLD = "gold"
    private const val ACCENT_PURPLE = "purple"
    private const val ACCENT_OLIVE = "olive"

    private fun accentNameToRes(name: String): Int = when (name) {
        ACCENT_TEAL -> R.color.accent_teal
        ACCENT_GOLD -> R.color.accent_gold
        ACCENT_PURPLE -> R.color.accent_purple
        ACCENT_OLIVE -> R.color.accent_olive
        else -> R.color.accent_cyan
    }

    private fun resToAccentName(colorRes: Int): String = when (colorRes) {
        R.color.accent_teal -> ACCENT_TEAL
        R.color.accent_gold -> ACCENT_GOLD
        R.color.accent_purple -> ACCENT_PURPLE
        R.color.accent_olive -> ACCENT_OLIVE
        else -> ACCENT_CYAN
    }

    fun accentColorRes(context: Context): Int {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_ACCENT, ACCENT_CYAN) ?: ACCENT_CYAN
        return accentNameToRes(name)
    }

    fun setAccentColorRes(context: Context, colorRes: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACCENT, resToAccentName(colorRes)).apply()
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
        val value = prefs.getInt(KEY_FONT, 0)
        return if (value in 0..2) value else 0
    }

    fun setFontChoice(context: Context, choice: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_FONT, choice).apply()
    }

    // 0 = comfortable, 1 = compact - controls app screen spacing only.
    fun density(context: Context): Int {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val value = prefs.getInt(KEY_DENSITY, 0)
        return if (value in 0..1) value else 0
    }

    fun setDensity(context: Context, value: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_DENSITY, value).apply()
    }

    // Keyboard row height, in dp, applied to every key row in the IME.
    // 0 = short, 1 = medium (default), 2 = tall. Stored as an index
    // (not a raw dp value) and mapped through HEIGHT_DP_VALUES below so
    // a corrupted/out-of-range stored value can never produce a
    // degenerate (zero or negative) key height that would make the
    // keyboard unusable.
    val HEIGHT_DP_VALUES = intArrayOf(42, 52, 64)

    fun heightLevel(context: Context): Int {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val value = prefs.getInt(KEY_HEIGHT, 1)
        return if (value in HEIGHT_DP_VALUES.indices) value else 1
    }

    fun setHeightLevel(context: Context, level: Int) {
        if (level !in HEIGHT_DP_VALUES.indices) return
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_HEIGHT, level).apply()
    }

    fun keyRowHeightDp(context: Context): Int = HEIGHT_DP_VALUES[heightLevel(context)]
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
     * Same idea but for a MaterialButton (mode/font/density/height buttons) -
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
