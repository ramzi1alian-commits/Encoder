package com.securekeyboard.app

import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView

/**
 * SecureInputMethodService
 *
 * Privacy guarantees this class is designed to uphold:
 *
 * 1. NOTHING TYPED IS EVER STORED. Key presses are sent straight to the
 *    focused text field via InputConnection.commitText() and are never
 *    written to a variable, file, database, or log that outlives the
 *    single key press. There is no history buffer, no "recent words"
 *    file, no analytics call, anywhere in this class.
 *
 * 2. NO NETWORK ACCESS IS POSSIBLE. This entire app declares no
 *    android.permission.INTERNET in the manifest, so even if this code
 *    were modified to try to send data somewhere, the Android sandbox
 *    would block the socket at the OS level.
 *
 * 3. SCREENSHOTS OF THE KEYBOARD ARE BLOCKED where the platform allows
 *    it, via WindowManager.LayoutParams.FLAG_SECURE on this input
 *    window. See onCreateInputView().
 *
 * Limitations to be upfront about (see README):
 * - FLAG_SECURE blocks Android's built-in screenshot/screen-recording
 *   APIs. It cannot stop someone physically photographing the screen
 *   with another camera, and it cannot override a rooted device with
 *   modified system software.
 * - The app you're typing INTO (e.g. a chat app) still receives the
 *   text you type, exactly as it would with any keyboard - that's
 *   how typing works. This keyboard only guarantees that this app,
 *   specifically, does not add any collection, storage, or
 *   transmission of what you type.
 *
 * FIXED IN THIS VERSION: key row height used to be a hardcoded raw pixel
 * value (130px), which is NOT device-independent - the same number
 * produced a huge key on an old low-density screen and a tiny sliver on
 * a modern high-density screen. It's now computed in dp (see dpToPx)
 * from a user-adjustable preference (Settings > keyboard height), so it
 * looks consistent across devices and the user - not a one-time
 * hardcoded guess - controls how tall the keys are.
 */
class SecureInputMethodService : InputMethodService() {

    // Tracks which settings were baked into the currently-built view, so
    // onStartInputView can detect "the user changed something in
    // Settings since the keyboard was last drawn" and rebuild live,
    // instead of requiring the user to restart the app for a height or
    // accent-color change to take effect.
    private var appliedHeightDp = -1
    private var appliedAccentRes = -1

    private fun dpToPx(dp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()

    override fun onCreateInputView(): View {
        // Block screenshots / screen recording of the keyboard surface,
        // to the extent the Android platform allows for an IME window.
        window?.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        val heightDp = Prefs.keyboardHeightDp(this)
        appliedHeightDp = heightDp
        appliedAccentRes = Prefs.accentColorRes(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeUtil.keyboardBackground(this@SecureInputMethodService)
            setPadding(dpToPx(3f), dpToPx(5f), dpToPx(3f), dpToPx(5f))
            // FIX: rows of keys were rendering left-to-right regardless of
            // the Arabic text direction. android:supportsRtl in the
            // manifest mirrors an ACTIVITY's window automatically based on
            // locale, but an InputMethodService's window is a separate
            // system overlay that does NOT reliably inherit that
            // mirroring - it depends on OEM/IME framework behavior. Setting
            // layoutDirection explicitly here guarantees the row always
            // reads right-to-left regardless of device/locale quirks.
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        // NOTE: rows/letters and their order are UNCHANGED from before -
        // only sizing (dp instead of raw px) and background styling were
        // touched in this pass.
        val rows = listOf(
            "ض ص ث ق ف غ ع ه خ ح ج د",
            "ش س ي ب ل ا ت ن م ك ط ذ",
            "ئ ء ؤ ر لا ى ة و ز ظ"
        )

        val numberRow = "1 2 3 4 5 6 7 8 9 0".split(" ")
        val numberLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for (n in numberRow) {
            numberLayout.addView(makeKey(n, heightDp = heightDp))
        }
        root.addView(numberLayout)

        for (row in rows) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for (ch in row.split(" ")) {
                rowLayout.addView(makeKey(ch, heightDp = heightDp))
            }
            root.addView(rowLayout)
        }

        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        bottomRow.addView(makeKey("مسافة", weight = 4f, heightDp = heightDp, accented = true) {
            currentInputConnection?.commitText(" ", 1)
        })
        bottomRow.addView(makeKey("حذف", weight = 1.5f, heightDp = heightDp, accented = true) {
            currentInputConnection?.deleteSurroundingText(1, 0)
        })
        bottomRow.addView(makeKey("إدخال", weight = 1.5f, heightDp = heightDp, accented = true) {
            currentInputConnection?.sendKeyEvent(
                android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER)
            )
        })
        root.addView(bottomRow)

        return root
    }

    private fun makeKey(
        label: String,
        weight: Float = 1f,
        heightDp: Int = Prefs.DEFAULT_KEYBOARD_HEIGHT_DP,
        accented: Boolean = false,
        onClick: (() -> Unit)? = null
    ): TextView {
        return TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            isAllCaps = false
            includeFontPadding = false
            // The root cause of the original "letters render as tiny
            // symbols" bug: a plain Button carries a Material-style
            // minWidth (~48-88dp) and internal padding baked into the
            // theme. On a 12-key row each key's actual available width
            // is far smaller than that, so the padding ate almost all
            // the space and clipped the glyph down to a sliver (often
            // just a dot). A TextView has no such baked-in minimum/
            // padding, so it uses the full narrow width for the
            // character itself.
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
            typeface = Typeface.create(Fonts.currentTypeface(this@SecureInputMethodService), Typeface.NORMAL)
            setTextColor(
                if (accented) ThemeUtil.accentColor(this@SecureInputMethodService)
                else resources.getColor(R.color.slate_200, theme)
            )
            background = ThemeUtil.keyBackgroundSelector(this@SecureInputMethodService, accented)
            isClickable = true
            isFocusable = true
            // dp -> px conversion is what makes this consistent across
            // screen densities, instead of the old raw-pixel constant.
            val lp = LinearLayout.LayoutParams(0, dpToPx(heightDp.toFloat()), weight)
            val marginPx = dpToPx(2f)
            lp.setMargins(marginPx, marginPx, marginPx, marginPx)
            layoutParams = lp
            setOnClickListener {
                // Text goes directly to the focused field. Nothing here
                // is retained, logged, or queued anywhere else.
                onClick?.invoke() ?: currentInputConnection?.commitText(label, 1)
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // No state from a previous session is loaded here - intentionally.

        // Live-apply a height or accent-color change made in Settings
        // since this keyboard view was last built, without requiring the
        // user to force-stop/restart the app for it to take effect.
        val currentHeight = Prefs.keyboardHeightDp(this)
        val currentAccent = Prefs.accentColorRes(this)
        if (currentHeight != appliedHeightDp || currentAccent != appliedAccentRes) {
            setInputView(onCreateInputView())
        }
    }
}
