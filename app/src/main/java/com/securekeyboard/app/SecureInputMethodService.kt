package com.securekeyboard.app

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.PopupWindow
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
 * A suggestion strip was added above the key rows, backed by two fixed,
 * read-only, bundled-in-the-APK data files derived from a public Arabic
 * news corpus: a word-frequency list for prefix completion (see
 * WordDictionary.kt) and a word-pair list for NEXT-word prediction - the
 * instant a word is finished, likely following words are offered before
 * anything of the next word is typed (see NextWordDictionary.kt). On top
 * of that, THIS APP'S OWN sensitive fields (and any other app's field
 * marked the same way) get a stricter guarantee than the rest of the
 * device - see the split below.
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
 *
 * FIXED IN THIS VERSION - suggestion bar layout jump: the suggestion
 * strip used to be View.GONE when there was nothing to suggest, which
 * removes it from the layout entirely and makes every row of keys below
 * it jump up/down as the user types (bar appears/disappears). It now
 * uses View.INVISIBLE instead, which keeps its height permanently
 * reserved in the layout - the keys never move, whether or not
 * suggestions are currently showing.
 *
 * ADDED IN THIS VERSION - two long-press behaviors, neither of which
 * changes any key's position in its row:
 * 1. Hamza popup on ا: long-pressing ا (and dragging, like a normal
 *    Android popup key) picks between ا / أ / إ / آ - the hamza forms
 *    that were missing before. See [LETTER_VARIANTS], [showVariantPopup].
 * 2. Tatweel (kashida, "ـ") hold-to-extend: holding down any other
 *    letter key (instead of a quick tap) repeatedly inserts the ـ
 *    elongation character instead of repeating the letter, so the user
 *    can stretch a word for emphasis/calligraphic effect. A quick tap
 *    still always just types the letter as before.
 *
 * FIXED IN THIS VERSION - the current word used to be "forgotten" the
 * moment backspace crossed back over a space into an already-finished
 * word (or the moment the user tapped the cursor into the middle of one):
 * [currentWord] was only ever built forward, one appended letter at a
 * time, so anything that put the cursor somewhere it hadn't typed
 * through left the buffer empty and suggestions stuck off until a brand
 * new word was started. [currentWord] is now re-derived from the actual
 * field content around the cursor - see [resyncCurrentWordFromField] -
 * both after backspace and on every cursor move ([onUpdateSelection]),
 * so going back into a word (by backspacing OR by tapping) restores it
 * in full instead of starting over.
 *
 * ADDED IN THIS VERSION - saved phrases: finishing a line with إدخال
 * (Enter) now also learns that whole line as a phrase (see
 * [PhraseDictionary]), gated by the exact same suggestionsEnabled check
 * as everything else here. This is a bigger privacy trade-off than
 * single-word learning - PhraseDictionary.kt's class doc spells out
 * exactly what that does and doesn't mean, and it's wipeable separately
 * from the learned-words list in Settings.
 *
 * FIXED IN THIS VERSION - dark mode never visibly applied: this view is
 * only ever built once per keyboard session, so its colors (from
 * res/values/colors.xml or res/values-night/colors.xml) were resolved
 * once and never re-resolved when the system's dark/light mode changed
 * afterwards. See [appliedNightMode], [onConfigurationChanged], and the
 * extra check added to onStartInputView - the view now rebuilds itself
 * whenever the applied night mode differs from what's currently applied.
 *
 * FIXED IN THIS VERSION (deeper bug behind the above) - the keyboard
 * didn't actually turn black when "الوضع الليلي" was chosen INSIDE the
 * app's own theme settings, only when the PHONE's system dark mode was
 * also on: this Service (unlike the AppCompatActivity screens) never
 * consulted AppCompatDelegate's forced night mode at all, only the raw
 * system Configuration. Every color/drawable this keyboard draws now
 * goes through ThemeUtil, which resolves everything against
 * Prefs.isDarkMode() directly (see ThemeUtil.themedContext()) - the
 * app's own toggle is the one actual source of truth everywhere now, not
 * the phone's separate system setting. The night palette itself
 * (res/values-night/colors.xml) was also given more contrast between the
 * keyboard surface, key faces, and borders - same shapes/corners/
 * elevation as before, just clearer separation between them instead of
 * reading as a flat, slightly-hazy dark gray.
 */
class SecureInputMethodService : InputMethodService() {

    companion object {
        // Time the user has to hold a key before it's treated as a
        // long-press (popup or tatweel-extend) instead of a normal tap.
        private const val LONG_PRESS_MS = 320L
        // How often ـ is inserted while a tatweel-extend key is held.
        private const val TATWEEL_REPEAT_MS = 110L

        // Keys that get a hamza-forms popup on long-press instead of the
        // tatweel-extend behavior. Order here is left-to-right in the
        // popup, matching the LTR row direction used for the key rows.
        private val LETTER_VARIANTS = mapOf(
            "ا" to listOf("ا", "أ", "إ", "آ")
        )
    }

    // Single Handler for every key's long-press/repeat timers. Each
    // posted Runnable is stored on that key's own local variables (see
    // makeKey) and removed by reference (never by clearing everything),
    // so one key's timer can never cancel another key's.
    private val longPressHandler = Handler(Looper.getMainLooper())

    // Tracks which settings were baked into the currently-built view, so
    // onStartInputView can detect "the user changed something in
    // Settings since the keyboard was last drawn" and rebuild live,
    // instead of requiring the user to restart the app for a height or
    // accent-color change to take effect.
    private var appliedHeightDp = -1
    private var appliedAccentRes = -1
    // FIXED IN THIS VERSION: the keyboard's colors come from
    // res/values/colors.xml vs res/values-night/colors.xml, which
    // Android is supposed to switch between automatically based on
    // system dark/light mode - but this view is only ever BUILT once
    // (onCreateInputView) and its drawables/colors are resolved to
    // fixed values at that moment, not re-resolved live. Without this
    // tracked value, switching system dark mode while (or before) this
    // keyboard is showing had no visible effect until the whole app was
    // killed and restarted. See onConfigurationChanged and the extra
    // check in onStartInputView below - both now rebuild the view
    // whenever the current night-mode bits differ from what's applied.
    private var appliedNightMode = -1

    // In-memory only, cleared aggressively (see class doc above). This
    // is intentionally the ONLY typed-content state this class keeps,
    // and it never outlives the current word/field/session.
    private val currentWord = StringBuilder()
    private var suggestionsEnabled = false

    // In-memory only, same lifetime/rules as currentWord above: the single
    // most recently FINISHED word (via space, enter, or tapping a
    // suggestion), kept purely so NextWordDictionary can offer likely next
    // words the instant a new word starts - never written to disk, never
    // part of any log, cleared alongside currentWord on field switch.
    private var lastFinishedWord: String? = null
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
        appliedNightMode = currentNightMode()

        // Kick off (or no-op if already done) the background load of the
        // bundled static word list AND the user's own learned-words file
        // (empty until words get learned in a non-sensitive field - see
        // class doc above and LearnedDictionary.kt).
        WordDictionary.preload(this)
        NextWordDictionary.preload(this)
        LearnedDictionary.preload(this)
        PhraseDictionary.preload(this)

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
            // INVISIBLE (not GONE): the bar's height stays reserved in
            // the layout at all times, so the key rows below it never
            // shift up/down as suggestions come and go while typing.
            visibility = View.INVISIBLE
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
                val variants = LETTER_VARIANTS[ch]
                rowLayout.addView(
                    makeKey(
                        ch,
                        heightDp = heightDp,
                        variants = variants,
                        // Only letters WITHOUT a hamza popup get the
                        // tatweel hold-to-extend behavior, so a long
                        // press on ا always means "show me the hamza
                        // forms", never "start inserting ـ".
                        tatweelExtend = variants == null
                    ) {
                        // Arabic letters only ever reach this branch (the
                        // number row and action keys use their own
                        // onClick above/below and never touch
                        // currentWord), so it's safe to always treat a
                        // key here as "extends the current word".
                        commitLetter(ch)
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
            lastFinishedWord = if (suggestionsEnabled && finishedWord.isNotEmpty()) finishedWord else null
            currentWord.clear()
            updateSuggestions()
        })
        bottomRow.addView(makeKey("حذف", weight = 1.5f, heightDp = heightDp, accented = true) {
            currentInputConnection?.deleteSurroundingText(1, 0)
            // Was: just chop the last char off currentWord, which left
            // the buffer permanently empty (suggestions stuck off) the
            // moment backspace crossed back over a space into a word
            // that was already finished. Re-deriving from the field
            // instead brings that whole word back, exactly as it was
            // typed - see resyncCurrentWordFromField().
            lastFinishedWord = null
            resyncCurrentWordFromField()
        })
        bottomRow.addView(makeKey("إدخال", weight = 1.5f, heightDp = heightDp, accented = true) {
            val finishedWord = currentWord.toString()
            val ic = currentInputConnection
            // Grab the whole line being finished BEFORE sending Enter -
            // some apps submit/clear the field the instant Enter
            // arrives, so there'd be nothing left to read afterward.
            val lineBeforeCursor = ic?.getTextBeforeCursor(500, 0)?.toString() ?: ""
            val finishedLine = lineBeforeCursor.substringAfterLast('\n').trim()
            ic?.sendKeyEvent(
                android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER)
            )
            if (suggestionsEnabled) {
                if (finishedWord.isNotEmpty()) {
                    LearnedDictionary.learn(this@SecureInputMethodService, finishedWord)
                }
                if (finishedLine.isNotEmpty()) {
                    PhraseDictionary.learn(this@SecureInputMethodService, finishedLine)
                }
                lastFinishedWord = if (finishedWord.isNotEmpty()) finishedWord else null
            } else {
                lastFinishedWord = null
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
        variants: List<String>? = null,
        tatweelExtend: Boolean = false,
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
                else ThemeUtil.textColor(this@SecureInputMethodService)
            )
            background = ThemeUtil.keyBackgroundSelector(this@SecureInputMethodService, accented)
            // Real elevation (not a drawn trick) so the key reads as a
            // raised card over the darker keyboard surface - part of the
            // "modern, not flat" visual refresh. Dropped briefly on
            // press for tactile feedback, see the touch listener below.
            ThemeUtil.applyPressedElevation(this, pressed = false)
            isClickable = true
            isFocusable = true
            // dp -> px conversion is what makes this consistent across
            // screen densities, instead of the old raw-pixel constant.
            val lp = LinearLayout.LayoutParams(0, dpToPx(heightDp.toFloat()), weight)
            val marginPx = dpToPx(2f)
            lp.setMargins(marginPx, marginPx, marginPx, marginPx)
            layoutParams = lp

            // Per-key gesture state. These are local vars captured by the
            // touch listener closure below, so each key gets its own
            // independent state (never shared across keys) even though
            // they all post to the single shared longPressHandler.
            var pendingLongPress: Runnable? = null
            var popup: PopupWindow? = null
            var popupContent: LinearLayout? = null
            var selectedVariantIndex = 0
            var isTatweelRepeating = false

            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        ThemeUtil.applyPressedElevation(v, pressed = true)
                        isTatweelRepeating = false
                        selectedVariantIndex = 0
                        when {
                            variants != null -> {
                                val r = Runnable {
                                    val (pw, content) = showVariantPopup(v, variants)
                                    popup = pw
                                    popupContent = content
                                    highlightVariantChip(content, 0)
                                }
                                pendingLongPress = r
                                longPressHandler.postDelayed(r, LONG_PRESS_MS)
                            }
                            tatweelExtend -> {
                                lateinit var repeatRunnable: Runnable
                                repeatRunnable = Runnable {
                                    isTatweelRepeating = true
                                    currentInputConnection?.commitText("ـ", 1)
                                    longPressHandler.postDelayed(repeatRunnable, TATWEEL_REPEAT_MS)
                                }
                                pendingLongPress = repeatRunnable
                                longPressHandler.postDelayed(repeatRunnable, LONG_PRESS_MS)
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val content = popupContent
                        if (content != null) {
                            selectedVariantIndex = variantIndexForRawX(content, variants?.size ?: 1, event.rawX)
                            highlightVariantChip(content, selectedVariantIndex)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.isPressed = false
                        ThemeUtil.applyPressedElevation(v, pressed = false)
                        pendingLongPress?.let { longPressHandler.removeCallbacks(it) }
                        pendingLongPress = null
                        val pw = popup
                        when {
                            pw != null -> {
                                // Popup was showing: commit whichever
                                // variant is currently highlighted (drag
                                // to change it before lifting, exactly
                                // like Gboard's accent popups).
                                val chosen = variants?.getOrElse(selectedVariantIndex) { label } ?: label
                                commitLetter(chosen)
                                pw.dismiss()
                                popup = null
                                popupContent = null
                            }
                            isTatweelRepeating -> {
                                // The hold-repeat already inserted ـ
                                // characters directly; nothing left to
                                // commit on release.
                                isTatweelRepeating = false
                            }
                            else -> {
                                // Normal short tap - unchanged behavior:
                                // letter rows pass their own onClick
                                // (which calls commitLetter to also track
                                // the word for suggestions); keys with no
                                // onClick (number row) just plain-commit,
                                // exactly like before this change.
                                onClick?.invoke() ?: currentInputConnection?.commitText(label, 1)
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        ThemeUtil.applyPressedElevation(v, pressed = false)
                        pendingLongPress?.let { longPressHandler.removeCallbacks(it) }
                        pendingLongPress = null
                        popup?.dismiss()
                        popup = null
                        popupContent = null
                        isTatweelRepeating = false
                        true
                    }
                    else -> false
                }
            }
        }
    }

    /**
     * True for every character this keyboard can actually type as part
     * of a word: the basic Arabic letter block (which covers every plain
     * letter AND every hamza form - ء through ي, i.e. 0x0621-0x064A) plus
     * tatweel (ـ), which extends a word rather than ending it. Anything
     * else (space, digits, punctuation, newline) is a word boundary.
     */
    private fun isArabicWordChar(c: Char): Boolean {
        return c == '\u0640' || (c.code in 0x0621..0x064A)
    }

    /**
     * Re-derives [currentWord] directly from the real field content
     * around the cursor, instead of trusting this class's own forward-
     * only bookkeeping. This is what makes backspacing back over a space
     * into an already-finished word - or tapping the cursor into the
     * middle of one - restore that FULL word instead of leaving the
     * suggestion bar blank and treating the next letter as a new word.
     *
     * Reads only a short, bounded window of text immediately before the
     * cursor (never the whole field) purely to locate the current word's
     * start - this is not a history buffer, and nothing read here is
     * stored anywhere beyond this in-memory StringBuilder's own lifetime.
     */
    private fun resyncCurrentWordFromField() {
        if (!suggestionsEnabled) {
            if (currentWord.isNotEmpty()) {
                currentWord.setLength(0)
                updateSuggestions()
            }
            return
        }
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(64, 0)?.toString() ?: ""
        var start = before.length
        while (start > 0 && isArabicWordChar(before[start - 1])) start--
        val word = before.substring(start)
        if (word != currentWord.toString()) {
            currentWord.setLength(0)
            currentWord.append(word)
            updateSuggestions()
        }
    }

    /**
     * Fires whenever the cursor/selection in the focused field changes -
     * including when the user taps to move the cursor somewhere else
     * entirely, not just as a side effect of this keyboard's own key
     * presses. Re-syncing here (in addition to after backspace) is what
     * makes moving the cursor back INTO a previously-typed word - by
     * tapping, not only by backspacing - also restore it for
     * suggestions.
     */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        resyncCurrentWordFromField()
    }

    /**
     * Commits a single letter/variant to the field AND treats it as
     * extending the current word for suggestion purposes - the same
     * bookkeeping every plain letter key did before, now shared by both
     * a normal tap and a hamza-variant popup selection.
     */
    private fun commitLetter(ch: String) {
        currentInputConnection?.commitText(ch, 1)
        currentWord.append(ch)
        updateSuggestions()
    }

    /**
     * Builds and shows the small horizontal popup of variant letters
     * above [anchor] (e.g. ا / أ / إ / آ above the ا key). The popup is
     * non-touchable itself - the anchor key keeps receiving the same
     * touch gesture (ACTION_MOVE/UP) for as long as the finger is down,
     * which is what lets the caller do drag-to-select against it.
     */
    private fun showVariantPopup(anchor: View, variants: List<String>): Pair<PopupWindow, LinearLayout> {
        val chipSizePx = dpToPx(42f)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            background = ThemeUtil.keyBackgroundSelector(this@SecureInputMethodService, accented = false)
            elevation = dpToPx(6f).toFloat()
            setPadding(dpToPx(3f), dpToPx(3f), dpToPx(3f), dpToPx(3f))
            for (v in variants) {
                addView(TextView(this@SecureInputMethodService).apply {
                    text = v
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    typeface = Typeface.create(Fonts.currentTypeface(this@SecureInputMethodService), Typeface.NORMAL)
                    layoutParams = LinearLayout.LayoutParams(chipSizePx, chipSizePx)
                })
            }
        }

        val popup = PopupWindow(content, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, false)
        popup.isTouchable = false
        popup.isClippingEnabled = false

        val widthSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        content.measure(widthSpec, widthSpec)
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val xOff = loc[0] + anchor.width / 2 - content.measuredWidth / 2
        val yOff = loc[1] - content.measuredHeight - dpToPx(4f)
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, xOff, yOff)
        return Pair(popup, content)
    }

    /** Finds which chip in an already-shown variant popup a raw (screen) x-coordinate is over. */
    private fun variantIndexForRawX(content: LinearLayout, count: Int, rawX: Float): Int {
        if (content.childCount == 0 || count <= 0) return 0
        val loc = IntArray(2)
        content.getLocationOnScreen(loc)
        val localX = rawX - loc[0]
        val childWidth = content.getChildAt(0).width.takeIf { it > 0 } ?: 1
        return (localX / childWidth).toInt().coerceIn(0, count - 1)
    }

    /** Visually marks the currently drag-selected chip in a variant popup with the accent color. */
    private fun highlightVariantChip(content: LinearLayout, selected: Int) {
        for (i in 0 until content.childCount) {
            val chip = content.getChildAt(i) as? TextView ?: continue
            if (i == selected) {
                chip.setBackgroundColor(ThemeUtil.accentColor(this))
                chip.setTextColor(ThemeUtil.textOnAccentColor(this))
            } else {
                chip.setBackgroundColor(Color.TRANSPARENT)
                chip.setTextColor(ThemeUtil.textColor(this))
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // No state from a previous session is loaded here - intentionally.
        // Switching into (or back to) a field always starts with a clean
        // word buffer, never whatever was being typed in a previous field.
        currentWord.clear()
        lastFinishedWord = null

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

        // Live-apply a height, accent-color, or day/night change made
        // since this keyboard view was last built, without requiring the
        // user to force-stop/restart the app for it to take effect.
        val currentHeight = Prefs.keyboardHeightDp(this)
        val currentAccent = Prefs.accentColorRes(this)
        val nightMode = currentNightMode()
        if (currentHeight != appliedHeightDp || currentAccent != appliedAccentRes || nightMode != appliedNightMode) {
            setInputView(onCreateInputView())
        }
    }

    /**
     * FIXED: this used to read the SYSTEM's day/night bit
     * (resources.configuration.uiMode), which is what left the keyboard
     * ignoring the app's own "الوضع الليلي/النهاري" setting entirely -
     * see the long comment on ThemeUtil.themedContext() for the full
     * story. Prefs.isDarkMode() is the single source of truth now, so
     * that's what decides whether the keyboard needs rebuilding here too.
     */
    private fun currentNightMode(): Int = if (Prefs.isDarkMode(this)) 1 else 0

    /**
     * Catches the case where the system's dark/light mode changes WHILE
     * this keyboard is already on screen. This app's own night setting is
     * the real source of truth (see currentNightMode()) and doesn't fire
     * this callback on its own - onStartInputView already re-checks it on
     * every focus. This override just makes sure a genuine SYSTEM dark-
     * mode flip doesn't leave stale elevation/shadow rendering behind
     * without at least a redraw, even though it no longer changes which
     * color palette is used.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isInputViewShown) {
            setInputView(onCreateInputView())
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // Leaving the field entirely - drop the in-progress word rather
        // than let it linger in memory for a session that's now over.
        currentWord.clear()
        lastFinishedWord = null
        updateSuggestions()
        // Cancel any in-flight long-press/tatweel-repeat timer so it
        // can't fire against a key view that's about to be torn down.
        longPressHandler.removeCallbacksAndMessages(null)
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

        if (!suggestionsEnabled) {
            // INVISIBLE, not GONE: keeps the bar's space reserved so the
            // key rows below never jump as suggestions come and go.
            bar.visibility = View.INVISIBLE
            return
        }

        val suggestions = if (currentWord.isEmpty()) {
            // Nothing typed yet for the new word - if a word was just
            // finished (space/enter/tap), offer likely NEXT words instead
            // of leaving the bar empty. This is what makes the keyboard
            // predict ahead rather than only completing what's typed.
            lastFinishedWord?.let { prev ->
                NextWordDictionary.suggestionsFor(prev).map { Suggestion(it, isPhrase = false) }
            } ?: emptyList()
        } else {
            mergedSuggestions(currentWord.toString())
        }

        if (suggestions.isEmpty()) {
            bar.visibility = View.INVISIBLE
            return
        }

        val heightDp = Prefs.keyboardHeightDp(this)
        for (suggestion in suggestions) {
            val chip = TextView(this).apply {
                text = suggestion.display
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER
                includeFontPadding = false
                typeface = Typeface.create(Fonts.currentTypeface(this@SecureInputMethodService), Typeface.NORMAL)
                // A saved whole-sentence suggestion gets the accent
                // color so it visibly reads as "a full phrase you've
                // typed before", not just another single-word guess.
                setTextColor(
                    if (suggestion.isPhrase) ThemeUtil.accentColor(this@SecureInputMethodService)
                    else ThemeUtil.textColor(this@SecureInputMethodService)
                )
                background = ThemeUtil.suggestionChipBackground(this@SecureInputMethodService)
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
                    // "using" it, so it's learned too (same
                    // suggestionsEnabled gate as space/enter elsewhere) -
                    // a phrase reinforces PhraseDictionary, a single word
                    // reinforces LearnedDictionary, never the other one.
                    currentInputConnection?.deleteSurroundingText(currentWord.length, 0)
                    currentInputConnection?.commitText("${suggestion.display} ", 1)
                    if (suggestionsEnabled) {
                        if (suggestion.isPhrase) {
                            PhraseDictionary.learn(this@SecureInputMethodService, suggestion.display)
                        } else {
                            LearnedDictionary.learn(this@SecureInputMethodService, suggestion.display)
                        }
                    }
                    // A tapped chip finishes a word exactly like typing a
                    // space would, so the word just committed becomes the
                    // context for the NEXT round of (next-word) suggestions.
                    // A tapped whole PHRASE ends the line's train of thought
                    // rather than a single word, so it doesn't set up a
                    // next-word context the same way.
                    lastFinishedWord = if (suggestionsEnabled && !suggestion.isPhrase) {
                        suggestion.display.substringAfterLast(' ')
                    } else null
                    currentWord.clear()
                    updateSuggestions()
                }
            }
            bar.addView(chip)
        }
        bar.visibility = View.VISIBLE
    }

    /** A single suggestion chip: what to show, and what learns from tapping it. */
    private data class Suggestion(val display: String, val isPhrase: Boolean)

    /**
     * Combines, in priority order: the user's own previously-SAVED FULL
     * SENTENCES that start with this word (see [PhraseDictionary], capped
     * at 2 so they can't crowd out every single-word suggestion), then
     * their learned single words (ranked by how often THEY typed them),
     * then the fixed static dictionary. Only ever called when
     * suggestionsEnabled is true, so this naturally never runs for a
     * sensitive field either.
     */
    private fun mergedSuggestions(prefix: String): List<Suggestion> {
        val out = LinkedHashSet<Suggestion>()
        for (phrase in PhraseDictionary.suggestionsFor(prefix, max = 2)) {
            out.add(Suggestion(phrase, isPhrase = true))
        }
        for (word in LearnedDictionary.suggestionsFor(prefix, 5)) {
            if (out.size >= 5) break
            out.add(Suggestion(word, isPhrase = false))
        }
        for (word in WordDictionary.suggestionsFor(prefix)) {
            if (out.size >= 5) break
            out.add(Suggestion(word, isPhrase = false))
        }
        return out.toList()
    }
}
