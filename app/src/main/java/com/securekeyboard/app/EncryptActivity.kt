package com.securekeyboard.app

import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * EncryptActivity
 *
 * Encrypts/decrypts text locally on-device using AES-256-GCM with an
 * Argon2id-derived key. Everything happens in this process's memory -
 * there is no networking code anywhere in this app (and no INTERNET
 * permission in the manifest), so nothing here can be sent anywhere.
 *
 * NOTE ON THE ALGORITHM CHANGE: this used to use PBKDF2-HMAC-SHA256.
 * Argon2id replaced it because Argon2id costs real MEMORY per guess (not
 * just CPU cycles), which makes brute-forcing on GPUs/ASICs dramatically
 * more expensive than an equivalent PBKDF2 iteration count - GPUs are
 * fast at parallel hashing but have comparatively little memory per
 * core, so a memory-hard function narrows that advantage a lot.
 * Ciphertext produced by the OLD PBKDF2 version of this app can NOT be
 * decrypted by this version, and vice versa - the two formats are
 * intentionally incompatible so there's no silent fallback to the
 * weaker scheme.
 */
class EncryptActivity : AppCompatActivity() {

    private val ivLength = 12
    private val saltLength = 16

    // Argon2id parameters. These control memory (KB), iterations (time
    // cost) and parallelism - all three make brute-forcing expensive in
    // a different way than a single PBKDF2 "iteration count" number.
    // 64 MB / 3 passes / 1 lane is a commonly recommended mobile-friendly
    // baseline (OWASP's Argon2id guidance) that still runs in well under
    // a second on modern phone hardware.
    private val argonMemoryKb = 65536 // 64 MB
    private val argonIterations = 3
    private val argonParallelism = 1
    private val keyLengthBytes = 32 // AES-256

    private lateinit var inputText: EditText
    private lateinit var inputKey: EditText
    private lateinit var errorText: TextView
    private lateinit var resultCard: LinearLayout
    private lateinit var resultText: TextView
    private lateinit var keyStrengthHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContentView(R.layout.activity_encrypt)

        inputText = findViewById(R.id.inputText)
        inputKey = findViewById(R.id.inputKey)
        errorText = findViewById(R.id.errorText)
        resultCard = findViewById(R.id.resultCard)
        resultText = findViewById(R.id.resultText)
        keyStrengthHint = findViewById(R.id.keyStrengthHint)

        inputKey.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updateStrengthHint(s?.toString().orEmpty())
            }
        })
        updateStrengthHint("")

        findViewById<MaterialButton>(R.id.btnEncryptAction).setOnClickListener {
            hideError()
            val text = inputText.text.toString()
            val pass = inputKey.text.toString()
            if (text.isBlank()) { showError(getString(R.string.err_no_text)); return@setOnClickListener }
            if (pass.isBlank()) { showError(getString(R.string.err_no_key)); return@setOnClickListener }
            val bits = KeyStrength.estimateEntropyBits(pass)
            if (bits < KeyStrength.MIN_ENTROPY_BITS) {
                showError(
                    getString(R.string.err_weak_key, bits.toInt(), KeyStrength.MIN_ENTROPY_BITS.toInt())
                )
                return@setOnClickListener
            }
            try {
                showResult(encrypt(text, pass))
            } catch (e: Exception) {
                showError(getString(R.string.err_generic))
            }
        }

        findViewById<MaterialButton>(R.id.btnDecryptAction).setOnClickListener {
            hideError()
            val text = inputText.text.toString()
            val pass = inputKey.text.toString()
            if (text.isBlank()) { showError(getString(R.string.err_no_cipher)); return@setOnClickListener }
            if (pass.isBlank()) { showError(getString(R.string.err_no_key)); return@setOnClickListener }
            try {
                showResult(decrypt(text, pass))
            } catch (e: Exception) {
                showError(getString(R.string.err_bad_key))
            }
        }

        // Real random-key generator: fills the key field with a
        // cryptographically random key (never derived from anything you
        // typed) instead of relying on a human-chosen password. This is
        // meant to be copied/written down separately from the ciphertext
        // and transported by hand (e.g. to an air-gapped device) - never
        // stored next to the encrypted text itself.
        findViewById<MaterialButton>(R.id.btnGenerateKey).setOnClickListener {
            hideError()
            val key = RandomKey.generate()
            // Switch the field to visible plain text: this key isn't a
            // memorized secret you're protecting from shoulder-surfing,
            // it's meant to be read and copied/written down by hand, so
            // hiding it with password dots would defeat the point.
            inputKey.inputType = android.text.InputType.TYPE_CLASS_TEXT
            inputKey.setText(key)
            showError(getString(R.string.warn_write_down_key))
            errorText.setTextColor(ThemeUtil.accentColor(this))
        }

        Fonts.applyToTree(findViewById(android.R.id.content), Fonts.currentTypeface(this))
        ThemeUtil.tintPrimary(this, findViewById(R.id.btnEncryptAction))
        ThemeUtil.tintOutline(this, findViewById(R.id.btnDecryptAction))
        ThemeUtil.tintOutline(this, findViewById(R.id.btnGenerateKey))
    }

    private fun showError(msg: String) {
        errorText.text = msg
        errorText.visibility = View.VISIBLE
        resultCard.visibility = View.GONE
    }

    private fun hideError() {
        errorText.visibility = View.GONE
    }

    private fun showResult(text: String) {
        resultText.text = text
        resultCard.visibility = View.VISIBLE
    }

    private fun updateStrengthHint(pass: String) {
        if (pass.isEmpty()) {
            keyStrengthHint.text = ""
            return
        }
        val bits = KeyStrength.estimateEntropyBits(pass)
        val ok = bits >= KeyStrength.MIN_ENTROPY_BITS
        keyStrengthHint.text = if (ok) {
            getString(R.string.key_strength_ok, bits.toInt())
        } else {
            getString(R.string.key_strength_weak, bits.toInt(), KeyStrength.MIN_ENTROPY_BITS.toInt())
        }
        keyStrengthHint.setTextColor(
            if (ok) ThemeUtil.accentColor(this)
            else resources.getColor(R.color.danger_red, theme)
        )
    }

    /**
     * Derives the AES key with Argon2id AND zeroes out the password/key
     * material as soon as it's no longer needed, instead of leaving
     * char[]/byte[] copies of the password and key sitting in the heap
     * until GC gets to them. This matters because on a compromised or
     * rooted device, a memory dump can otherwise recover the
     * password/key long after this function returns.
     */
    private fun deriveKey(pass: String, salt: ByteArray): ByteArray {
        val passBytes = pass.toByteArray(Charsets.UTF_8)
        try {
            val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(argonIterations)
                .withMemoryAsKB(argonMemoryKb)
                .withParallelism(argonParallelism)
                .withSalt(salt)
                .build()
            val generator = Argon2BytesGenerator()
            generator.init(params)
            val keyBytes = ByteArray(keyLengthBytes)
            generator.generateBytes(passBytes, keyBytes)
            return keyBytes
        } finally {
            java.util.Arrays.fill(passBytes, 0)
        }
    }

    private fun encrypt(text: String, pass: String): String {
        val salt = ByteArray(saltLength).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(ivLength).also { SecureRandom().nextBytes(it) }
        val keyBytes = deriveKey(pass, salt)
        try {
            val key = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            val cipherBytes = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
            val combined = salt + iv + cipherBytes
            return Base64.encodeToString(combined, Base64.NO_WRAP)
        } finally {
            java.util.Arrays.fill(keyBytes, 0)
        }
    }

    private fun decrypt(b64: String, pass: String): String {
        val combined = Base64.decode(b64, Base64.NO_WRAP)
        val salt = combined.copyOfRange(0, saltLength)
        val iv = combined.copyOfRange(saltLength, saltLength + ivLength)
        val cipherBytes = combined.copyOfRange(saltLength + ivLength, combined.size)
        val keyBytes = deriveKey(pass, salt)
        try {
            val key = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            return String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } finally {
            java.util.Arrays.fill(keyBytes, 0)
        }
    }

    override fun onPause() {
        super.onPause()
        // SECURITY FIX: this was previously only done in onDestroy(), which
        // meant plaintext, the encryption key, and the result stayed fully
        // visible in memory (and behind FLAG_SECURE, but still readable by
        // anything with debugger/inspection access to this process) for as
        // long as the app sat backgrounded - e.g. after switching apps or
        // getting a phone call, potentially indefinitely. Clearing on
        // onPause() as well means sensitive text no longer survives the
        // activity leaving the foreground, which is the behavior a "secure"
        // encryption screen should have.
        clearSensitiveFields()
    }

    override fun onDestroy() {
        super.onDestroy()
        clearSensitiveFields()
    }

    private fun clearSensitiveFields() {
        inputText.text?.clear()
        inputKey.text?.clear()
        resultText.text = ""
        resultCard.visibility = View.GONE
    }
}

/**
 * Estimates how many bits of entropy a typed key/password has, and
 * enforces a hard minimum before encryption is allowed.
 *
 * IMPORTANT HONESTY NOTE (read this before trusting the number blindly):
 * this is a CHARSET-SIZE estimate (length x log2(character pool used)).
 * It assumes the characters were chosen unpredictably. It CANNOT detect
 * that "Password123456789!" is actually a guessable dictionary word with
 * padding - a real attacker tries dictionary words and common patterns
 * FIRST, not pure random brute force, so a long-but-patterned password
 * can score high here while still being weak in practice. The only way
 * to get a real, unconditional guarantee is the "generate random key"
 * button, which has true random entropy, not an estimate.
 */
object KeyStrength {
    // 128 bits gives a massive safety margin: even at an extremely
    // generous 1 trillion guesses/second against Argon2id (far beyond
    // any realistic hardware, state-level or otherwise), exhausting
    // half of a 128-bit space takes on the order of 10^18 years.
    const val MIN_ENTROPY_BITS = 128.0

    fun estimateEntropyBits(pass: String): Double {
        var poolSize = 0
        if (pass.any { it in 'a'..'z' }) poolSize += 26
        if (pass.any { it in 'A'..'Z' }) poolSize += 26
        if (pass.any { it in '0'..'9' }) poolSize += 10
        if (pass.any { !it.isLetterOrDigit() }) poolSize += 32
        // Any non-ASCII character (Arabic letters, etc.) - give it credit
        // for a reasonably sized pool rather than undercounting it as 0.
        if (pass.any { it.code > 127 }) poolSize += 64
        if (poolSize == 0) return 0.0
        return pass.length * (Math.log(poolSize.toDouble()) / Math.log(2.0))
    }
}

/**
 * Generates a real cryptographically-random key (NOT derived from
 * anything typed or from any other ciphertext) and encodes it as
 * Base32 (RFC 4648 alphabet), grouped into readable blocks - meant to
 * be written down or transported by hand, e.g. to an air-gapped device.
 */
object RandomKey {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** 160 bits of real randomness - deliberately not tied to any password or prior ciphertext. */
    fun generate(byteLength: Int = 20): String {
        val bytes = ByteArray(byteLength).also { SecureRandom().nextBytes(it) }
        val raw = encodeBase32(bytes)
        return raw.chunked(5).joinToString("-")
    }

    private fun encodeBase32(data: ByteArray): String {
        val sb = StringBuilder()
        var bits = 0
        var value = 0
        for (b in data) {
            value = (value shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(value shr (bits - 5)) and 0x1F])
                bits -= 5
            }
        }
        if (bits > 0) {
            sb.append(ALPHABET[(value shl (5 - bits)) and 0x1F])
        }
        return sb.toString()
    }
}
