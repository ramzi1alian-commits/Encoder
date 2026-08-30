package com.securekeyboard.app

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import androidx.core.content.ContextCompat

object Prefs {
    private const val FILE = "secure_keyboard_prefs"
    private const val KEY_ACCENT = "accent_color_name"
    private const val KEY_DARK = "dark_mode"
    private const val KEY_FONT = "font_choice"
    private const val KEY_DENSITY = "density"
    private const val KEY_KEYBOARD_HEIGHT = "keyboard_height_dp"

    // Reasonable dp bounds for a comfortable-but-compact keyboard row.
    // (For reference: 1cm on a phone screen is roughly 63dp - the slider
    // lets the user go a bit under or over that instead of a single
    // hardcoded value baked into the code.)
    const val MIN_KEYBOARD_HEIGHT_DP = 40
    const val MAX_KEYBOARD_HEIGHT_DP = 72
    const val DEFAULT_KEYBOARD_HEIGHT_DP = 52

    // SECURITY/RELIABILITY FIX: the accent color used to be stored as a
    // raw android resource id (prefs.getInt(KEY_ACCENT, R.color.accent_cyan)
    // / putInt(..., colorRes)). Resource ids are NOT guaranteed stable
    // across build variants - and this project's release build has BOTH
    // minifyEnabled and shrinkResources enabled (see app/build.gradle),
    // either of which can renumber resource ids when the resource table
    // is stripped/repacked. A value saved by one installed build could
    // silently resolve to a completely different (or nonexistent)
    // resource after an app update, and ContextCompat.getColor() throws
    // Resources.NotFoundException on an invalid id - uncaught, that
    // crashes every single screen AND the keyboard itself (accentColor()
    // is called from SettingsActivity, ThemeSettingsActivity,
    // EncryptActivity, and every key drawn by the IME). A crashing
    // keyboard is a real availability problem: it can lock the user out
    // of typing in ANY app, not just this one.
    //
    // Fixed by storing a small, stable string key instead and mapping it
    // through a fixed table. An unrecognized/corrupted stored value can
    // only ever fall back to a safe default - it can never resolve to an
    // arbitrary or invalid resource id.
    private const val ACCENT_CYAN = "cyan"
    private const val ACCENT_TEAL = "teal"
    private const val ACCENT_GOLD = "gold"
    private const val ACCENT_PURPLE = "purple"

    private fun accentNameToRes(name: String?): Int = when (name) {
        ACCENT_TEAL -> R.color.accent_teal
        ACCENT_GOLD -> R.color.accent_gold
        ACCENT_PURPLE -> R.color.accent_purple
        else -> R.color.accent_cyan
    }

    private fun resToAccentName(colorRes: Int): String = when (colorRes) {
        R.color.accent_teal -> ACCENT_TEAL
        R.color.accent_gold -> ACCENT_GOLD
        R.color.accent_purple -> ACCENT_PURPLE
        else -> ACCENT_CYAN
    }

    fun accentColorRes(context: Context): Int {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return accentNameToRes(prefs.getString(KEY_ACCENT, ACCENT_CYAN))
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

    /**
     * Height (in dp, NOT raw pixels) of a single keyboard key row.
     *
     * IMPORTANT FIX: the keyboard used to hardcode a raw pixel value
     * (130px) for key height. Raw pixels are NOT device-independent, so
     * the same "130" produced wildly different physical sizes depending
     * on screen density (huge on an old mdpi screen, tiny on a modern
     * xxxhdpi screen). Storing/using dp here and converting to px with
     * the device's actual density (see SecureInputMethodService.dpToPx)
     * makes the key height consistent across devices, and adjustable by
     * the user in Settings instead of a single value baked in once.
     */
    fun keyboardHeightDp(context: Context): Int {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val value = prefs.getInt(KEY_KEYBOARD_HEIGHT, DEFAULT_KEYBOARD_HEIGHT_DP)
        return value.coerceIn(MIN_KEYBOARD_HEIGHT_DP, MAX_KEYBOARD_HEIGHT_DP)
    }

    fun setKeyboardHeightDp(context: Context, dp: Int) {
        val clamped = dp.coerceIn(MIN_KEYBOARD_HEIGHT_DP, MAX_KEYBOARD_HEIGHT_DP)
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_KEYBOARD_HEIGHT, clamped).apply()
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
        ContextCompat.getColor(context, Prefs.accentColorRes(context))

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
        val bg = GradientDrawable()
        bg.cornerRadius = 10f * view.resources.displayMetrics.density
        bg.setColor(ContextCompat.getColor(view.context, R.color.navy_800))
        val strokeWidthDp = if (selected) 2f else 1f
        val strokeColor = if (selected) accent else ContextCompat.getColor(view.context, R.color.navy_700)
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
                ContextCompat.getColor(context, R.color.navy_700)
            )
            button.setTextColor(ContextCompat.getColor(context, R.color.slate_200))
        }
    }

    /**
     * Corner radius used for every key / chip surface. Bumped from the
     * old 10dp to 16dp as part of the "modern, not 80s" visual refresh -
     * softer, more rounded corners read as current-generation UI instead
     * of the sharper/flatter look of the previous pass.
     */
    private const val KEY_CORNER_RADIUS_DP = 16f

    /**
     * Elevation (real View.elevation, not a drawn gradient trick) applied
     * to each key so it reads as a raised card floating over the darker
     * keyboard surface (see keyboardBackground) - this is the main thing
     * that was missing before and made the keyboard look flat/dated. Key
     * presses drop to a lower elevation (see applyPressedElevation) to
     * sell a tactile "push down" on touch.
     */
    const val KEY_ELEVATION_DP = 2.5f
    const val KEY_ELEVATION_PRESSED_DP = 0.5f

    /**
     * Professional-looking keyboard key background: a subtle vertical
     * gradient (instead of a flat single color) with a thin border, built
     * as a proper pressed/normal state-list so keys give visual feedback
     * on touch. When [accented] is true (the space/delete/enter action
     * keys) the border uses the user's chosen accent color instead of a
     * neutral one, so those keys read as visually distinct actions.
     */
    fun keyBackgroundSelector(context: Context, accented: Boolean): Drawable {
        val states = StateListDrawable()
        states.addState(intArrayOf(android.R.attr.state_pressed), keyShape(context, pressed = true, accented = accented))
        states.addState(intArrayOf(), keyShape(context, pressed = false, accented = accented))
        return states
    }

    private fun keyShape(context: Context, pressed: Boolean, accented: Boolean): GradientDrawable {
        val density = context.resources.displayMetrics.density
        val bg = GradientDrawable()
        bg.cornerRadius = KEY_CORNER_RADIUS_DP * density
        bg.orientation = GradientDrawable.Orientation.TOP_BOTTOM
        if (pressed) {
            val pressedColor = ContextCompat.getColor(context, R.color.navy_700)
            bg.colors = intArrayOf(pressedColor, pressedColor)
        } else {
            // White-to-very-light-gray top-to-bottom gradient, on top of
            // the darker keyboard surface color (navy_950) - this
            // contrast (rather than the old near-identical whites) plus
            // real elevation is what makes the key read as a raised card.
            bg.colors = intArrayOf(
                ContextCompat.getColor(context, R.color.navy_900),
                ContextCompat.getColor(context, R.color.navy_800)
            )
        }
        val strokeColor = if (accented) accentColor(context) else ContextCompat.getColor(context, R.color.navy_700)
        val strokeWidthDp = if (accented) 1.6f else 1f
        bg.setStroke((strokeWidthDp * density).toInt(), strokeColor)
        return bg
    }

    /**
     * Pill-shaped background (state-list, so tapping still gives visual
     * feedback exactly like a key) for suggestion-strip chips - fully
     * rounded rather than the boxier key corner radius, matching the
     * rounded "chip" look of modern keyboard suggestion bars.
     */
    fun suggestionChipBackground(context: Context): Drawable {
        val states = StateListDrawable()
        states.addState(intArrayOf(android.R.attr.state_pressed), pillShape(context, pressed = true))
        states.addState(intArrayOf(), pillShape(context, pressed = false))
        return states
    }

    private fun pillShape(context: Context, pressed: Boolean): GradientDrawable {
        val density = context.resources.displayMetrics.density
        val bg = GradientDrawable()
        bg.cornerRadius = 999f * density // large enough to always render as a full pill
        bg.setColor(
            ContextCompat.getColor(context, if (pressed) R.color.navy_700 else R.color.navy_800)
        )
        return bg
    }

    /** Applies (or removes) the "pressed" elevation drop for tactile feedback on touch. */
    fun applyPressedElevation(view: android.view.View, pressed: Boolean) {
        val density = view.resources.displayMetrics.density
        view.elevation = (if (pressed) KEY_ELEVATION_PRESSED_DP else KEY_ELEVATION_DP) * density
    }

    /** Flat, slightly darker fill for the whole keyboard surface so raised keys have contrast to sit on. */
    fun keyboardBackground(context: Context): Drawable {
        val bg = GradientDrawable()
        bg.setColor(ContextCompat.getColor(context, R.color.navy_950))
        return bg
    }
}
