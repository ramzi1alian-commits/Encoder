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
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * EncryptActivity
 *
 * Encrypts/decrypts text locally on-device using AES-256-GCM with a
 * PBKDF2-derived key. Everything happens in this process's memory -
 * there is no networking code anywhere in this app (and no INTERNET
 * permission in the manifest), so nothing here can be sent anywhere.
 */
class EncryptActivity : AppCompatActivity() {

    private val ivLength = 12
    private val saltLength = 16
    private val iterations = 150000

    private lateinit var inputText: EditText
    private lateinit var inputKey: EditText
    private lateinit var errorText: TextView
    private lateinit var resultCard: LinearLayout
    private lateinit var resultText: TextView

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

        findViewById<MaterialButton>(R.id.btnEncryptAction).setOnClickListener {
            hideError()
            val text = inputText.text.toString()
            val pass = inputKey.text.toString()
            if (text.isBlank()) { showError(getString(R.string.err_no_text)); return@setOnClickListener }
            if (pass.isBlank()) { showError(getString(R.string.err_no_key)); return@setOnClickListener }
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

    private fun deriveKey(pass: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(pass.toCharArray(), salt, iterations, 256)
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun encrypt(text: String, pass: String): String {
        val salt = ByteArray(saltLength).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(ivLength).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(pass, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val cipherBytes = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        val combined = salt + iv + cipherBytes
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(b64: String, pass: String): String {
        val combined = Base64.decode(b64, Base64.NO_WRAP)
        val salt = combined.copyOfRange(0, saltLength)
        val iv = combined.copyOfRange(saltLength, saltLength + ivLength)
        val cipherBytes = combined.copyOfRange(saltLength + ivLength, combined.size)
        val key = deriveKey(pass, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }
}
