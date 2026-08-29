package com.securekeyboard.app

import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
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
 */
class SecureInputMethodService : InputMethodService() {

    override fun onCreateInputView(): View {
        // Block screenshots / screen recording of the keyboard surface,
        // to the extent the Android platform allows for an IME window.
        window?.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(resources.getColor(R.color.navy_950, theme))
            setPadding(6, 10, 6, 10)
        }

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
            numberLayout.addView(makeKey(n))
        }
        root.addView(numberLayout)

        for (row in rows) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for (ch in row.split(" ")) {
                rowLayout.addView(makeKey(ch))
            }
            root.addView(rowLayout)
        }

        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        bottomRow.addView(makeKey("مسافة", weight = 4f) {
            currentInputConnection?.commitText(" ", 1)
        }.apply {
            setTextColor(resources.getColor(Prefs.accentColorRes(this@SecureInputMethodService), theme))
        })
        bottomRow.addView(makeKey("حذف", weight = 1.5f) {
            currentInputConnection?.deleteSurroundingText(1, 0)
        })
        bottomRow.addView(makeKey("إدخال", weight = 1.5f) {
            currentInputConnection?.sendKeyEvent(
                android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER)
            )
        })
        root.addView(bottomRow)

        return root
    }

    private fun makeKey(label: String, weight: Float = 1f, onClick: (() -> Unit)? = null): TextView {
        return TextView(this).apply {
            text = label
            textSize = 18f
            gravity = Gravity.CENTER
            isAllCaps = false
            includeFontPadding = false
            // The root cause of the "letters render as tiny symbols" bug:
            // a plain Button carries a Material-style minWidth (~48-88dp)
            // and internal padding baked into the theme. On a 12-key row
            // each key's actual available width is far smaller than that,
            // so the padding ate almost all the space and clipped the
            // glyph down to a sliver (often just a dot). A TextView has
            // no such baked-in minimum/padding, so it uses the full
            // narrow width for the character itself.
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
            typeface = Typeface.create(Fonts.currentTypeface(this@SecureInputMethodService), Typeface.NORMAL)
            setTextColor(resources.getColor(R.color.slate_200, theme))
            background = resources.getDrawable(R.drawable.bg_key, theme)
            isClickable = true
            isFocusable = true
            val lp = LinearLayout.LayoutParams(0, 130, weight)
            lp.setMargins(4, 4, 4, 4)
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
    }
}
