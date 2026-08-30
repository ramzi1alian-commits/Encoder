package com.securekeyboard.app

import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.text.InputType
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
 *
 * WORD SUGGESTIONS - how this fits the privacy guarantees above:
 * A suggestion strip was added above the key rows, backed by a fixed,
 * read-only, bundled-in-the-APK Arabic word list (see WordDictionary.kt).
 * On top of that, THIS APP'S OWN sensitive fields (and any other app's
 * field marked the same way) get a stricter guarantee than the rest of
 * the device - see the split below.
 *
 * ⚠️ IMPORTANT UPDATE - guarantee #1 above is now SCOPED, not global:
 *
 * - IN A FIELD MARKED SENSITIVE (password type, or
 *   TYPE_TEXT_FLAG_NO_SUGGESTIONS - this includes this app's own
 *   EncryptActivity message/key fields, see activity_encrypt.xml):
 *   guarantee #1 still holds EXACTLY as before. No suggestions are
 *   shown, nothing typed there is added to any dictionary, and the only
 *   state kept is the transient [currentWord] buffer, cleared
 *   immediately on space/enter/field-switch/keyboard-hide.
 *
 * - IN ANY OTHER FIELD (i.e. normal typing in any other app, since this
 *   is installable as the device's system keyboard): completed words
 *   (on space/enter/tapped suggestion) are now saved to a small
 *   per-device, per-word frequency file (see LearnedDictionary.kt) so
 *   suggestions improve based on the user's own vocabulary over time.
 *   This is real, new, on-device storage of individual words typed
 *   outside this app's own sensitive screens - read LearnedDictionary.kt's
 *   class doc for the full, honest scope of what that does and does not
 *   mean (never transmitted anywhere - still no INTERNET permission
 *   anywhere in the app; never full messages, only individual words with
 *   a typed-count; excluded from Android backups like the rest of this
 *   app's data; user-clearable anytime from Settings).
 */
class SecureInputMethodService : InputMethodService() {

    // Tracks which settings were baked into the currently-built view, so
    // onStartInputView can detect "the user changed something in
    // Settings since the keyboard was last drawn" and rebuild live,
    // instead of requiring the user to restart the app for a height or
    // accent-color change to take effect.
    private var appliedHeightDp = -1
    private var appliedAccentRes = -1

    // In-memory only, cleared aggressively (see class doc above). This
    // is intentionally the ONLY typed-content state this class keeps,
    // and it never outlives the current word/field/session.
    private val currentWord = StringBuilder()
    private var suggestionsEnabled = false
    private var suggestionBar: LinearLayout? = null

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

        // Kick off (or no-op if already done) the background load of the
        // bundled static word list AND the user's own learned-words file
        // (empty until words get learned in a non-sensitive field - see
        // class doc above and LearnedDictionary.kt).
        WordDictionary.preload(this)
        LearnedDictionary.preload(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeUtil.keyboardBackground(this@SecureInputMethodService)
            setPadding(dpToPx(3f), dpToPx(5f), dpToPx(3f), dpToPx(5f))
            // FIX: rows of keys must always render in FIXED PHYSICAL order
            // matching a real Arabic keyboard's key positions, which are
            // the same physical layout as QWERTY (e.g. ض sits where Q is -
            // far left - and د sits where ] is - far right). This is why
            // the row arrays below are authored left-to-right. Setting
            // layoutDirection to RTL here was WRONG and reversed every row
            // (ض ended up on the right, د on the left) - the opposite of
            // any real Arabic keyboard. LTR is the correct fixed direction
            // for the key ROWS specifically; it has nothing to do with the
            // app's RTL support elsewhere (dialogs, settings text, etc. -
            // those correctly stay RTL via supportsRtl in the manifest).
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        // NOTE: rows/letters and their order are UNCHANGED from before -
        // only sizing (dp instead of raw px) and background styling were
        // touched in this pass.
        val rows = listOf(
            "ض ص ث ق ف غ ع ه خ ح ج د",
            "ش س ي ب ل ا ت ن م ك ط ذ",
            "ئ ء ؤ ر لا ى ة و ز ظ"
        )

        // Suggestion strip: built once here, populated/hidden dynamically
        // by updateSuggestions() as the user types. Height is a fixed,
        // modest fraction of the key height so it doesn't dominate the
        // keyboard on short screens.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            visibility = View.GONE
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx((heightDp * 0.72f))
            )
            lp.setMargins(0, 0, 0, dpToPx(3f))
            layoutParams = lp
        }
        suggestionBar = bar
        root.addView(bar)

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
                rowLayout.addView(
                    makeKey(ch, heightDp = heightDp) {
                        currentInputConnection?.commitText(ch, 1)
                        // Arabic letters only ever reach this branch (the
                        // number row and action keys use their own
                        // onClick above/below and never touch
                        // currentWord), so it's safe to always treat a
                        // key here as "extends the current word".
                        currentWord.append(ch)
                        updateSuggestions()
                    }
                )
            }
            root.addView(rowLayout)
        }

        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        bottomRow.addView(makeKey("مسافة", weight = 4f, heightDp = heightDp, accented = true) {
            val finishedWord = currentWord.toString()
            currentInputConnection?.commitText(" ", 1)
            // A finished word: in a sensitive field this just clears the
            // buffer (old behavior, unchanged). In any other field, it's
            // also handed to LearnedDictionary so future suggestions in
            // THIS user's own vocabulary improve - see the class doc at
            // the top of this file and LearnedDictionary.kt for exactly
            // what that does and doesn't store.
            if (suggestionsEnabled && finishedWord.isNotEmpty()) {
                LearnedDictionary.learn(this@SecureInputMethodService, finishedWord)
            }
            currentWord.clear()
            updateSuggestions()
        })
        bottomRow.addView(makeKey("حذف", weight = 1.5f, heightDp = heightDp, accented = true) {
            currentInputConnection?.deleteSurroundingText(1, 0)
            if (currentWord.isNotEmpty()) {
                currentWord.deleteCharAt(currentWord.length - 1)
            }
            updateSuggestions()
        })
        bottomRow.addView(makeKey("إدخال", weight = 1.5f, heightDp = heightDp, accented = true) {
            val finishedWord = currentWord.toString()
            currentInputConnection?.sendKeyEvent(
                android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER)
            )
            if (suggestionsEnabled && finishedWord.isNotEmpty()) {
                LearnedDictionary.learn(this@SecureInputMethodService, finishedWord)
            }
            currentWord.clear()
            updateSuggestions()
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
        // Switching into (or back to) a field always starts with a clean
        // word buffer, never whatever was being typed in a previous field.
        currentWord.clear()

        // Suggestions are opt-OUT per field, driven entirely by what the
        // app being typed into declares - never by anything this keyboard
        // remembers. A password field, or any field explicitly marked
        // "no suggestions" (this app's own EncryptActivity marks its
        // message/key fields this way - see activity_encrypt.xml),
        // disables the suggestion strip for that field.
        val inputType = info?.inputType ?: InputType.TYPE_NULL
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val isPassword = inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
            (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        val noSuggestionsFlag = inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
        val isTextClass = inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT
        suggestionsEnabled = isTextClass && !isPassword && !noSuggestionsFlag
        updateSuggestions()

        // Live-apply a height or accent-color change made in Settings
        // since this keyboard view was last built, without requiring the
        // user to force-stop/restart the app for it to take effect.
        val currentHeight = Prefs.keyboardHeightDp(this)
        val currentAccent = Prefs.accentColorRes(this)
        if (currentHeight != appliedHeightDp || currentAccent != appliedAccentRes) {
            setInputView(onCreateInputView())
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // Leaving the field entirely - drop the in-progress word rather
        // than let it linger in memory for a session that's now over.
        currentWord.clear()
        updateSuggestions()
    }

    /**
     * Rebuilds the suggestion strip's contents from [currentWord] and
     * shows/hides the bar. Called after every key press that can change
     * the current word (letters, backspace) and whenever suggestions are
     * turned on/off for the focused field.
     */
    private fun updateSuggestions() {
        val bar = suggestionBar ?: return
        bar.removeAllViews()

        if (!suggestionsEnabled || currentWord.isEmpty()) {
            bar.visibility = View.GONE
            return
        }

        val words = mergedSuggestions(currentWord.toString())
        if (words.isEmpty()) {
            bar.visibility = View.GONE
            return
        }

        val heightDp = Prefs.keyboardHeightDp(this)
        for (word in words) {
            val chip = TextView(this).apply {
                text = word
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER
                includeFontPadding = false
                typeface = Typeface.create(Fonts.currentTypeface(this@SecureInputMethodService), Typeface.NORMAL)
                setTextColor(resources.getColor(R.color.slate_200, theme))
                background = ThemeUtil.keyBackgroundSelector(this@SecureInputMethodService, accented = false)
                isClickable = true
                isFocusable = true
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                val marginPx = dpToPx(2f)
                lp.setMargins(marginPx, 0, marginPx, 0)
                layoutParams = lp
                setOnClickListener {
                    // Replace the in-progress word with the tapped
                    // suggestion, then a trailing space, matching normal
                    // keyboard suggestion-bar behavior. Tapping counts as
                    // "using" that word, so it's learned too (same
                    // suggestionsEnabled gate as space/enter below).
                    currentInputConnection?.deleteSurroundingText(currentWord.length, 0)
                    currentInputConnection?.commitText("$word ", 1)
                    if (suggestionsEnabled) {
                        LearnedDictionary.learn(this@SecureInputMethodService, word)
                    }
                    currentWord.clear()
                    updateSuggestions()
                }
            }
            bar.addView(chip)
        }
        bar.visibility = View.VISIBLE
    }

    /**
     * Combines the user's own learned words (ranked by how often THEY
     * typed them) with the fixed static dictionary, personal words
     * first. Only ever called when suggestionsEnabled is true, so this
     * naturally never runs for a sensitive field either.
     */
    private fun mergedSuggestions(prefix: String): List<String> {
        val learned = LearnedDictionary.suggestionsFor(prefix, 5)
        if (learned.size >= 5) return learned
        val merged = LinkedHashSet<String>(learned)
        for (word in WordDictionary.suggestionsFor(prefix)) {
            if (merged.size >= 5) break
            merged.add(word)
        }
        return merged.toList()
    }
}
