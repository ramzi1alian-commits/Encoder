package com.securekeyboard.app

import android.util.Base64
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * CryptoEngine
 *
 * The actual encrypt/decrypt implementation, extracted out of
 * EncryptActivity so the SAME code path is used whether the user is on
 * the full encryption screen OR using the keyboard's own quick-encrypt
 * panel (see SecureInputMethodService's crypto overlay page). Nothing
 * about the algorithm, format, or security properties changed in this
 * extraction - see the original design notes below, carried over as-is.
 *
 * AES-256-GCM with an Argon2id-derived key, fully offline (no networking
 * code anywhere in this app, no INTERNET permission in the manifest).
 * Passphrases and plaintext are handled as CharArray/ByteArray, never
 * String, so callers can explicitly zero them after use.
 *
 * MESSAGE-EXPIRY: an optional expiry duration can be attached at encrypt
 * time. After that time, THIS APP will refuse to decrypt the message,
 * and the expiry value itself is authenticated (GCM "additional
 * authenticated data") so tampering with it breaks the auth tag. This
 * is protection against casual/accidental decryption after a deadline,
 * not an unconditional guarantee the ciphertext becomes unrecoverable -
 * see the full explanation that used to live here, now in
 * EncryptActivity's class doc.
 */
object CryptoEngine {

    const val IV_LENGTH = 12
    const val SALT_LENGTH = 16
    const val HEADER_LENGTH = 10 // 1 (version) + 1 (hasExpiry) + 8 (expiry epoch seconds)
    const val FORMAT_VERSION: Byte = 2

    // Argon2id parameters - 64 MB / 3 passes / 1 lane, OWASP's
    // mobile-friendly baseline, well under a second on modern hardware.
    private const val ARGON_MEMORY_KB = 65536
    private const val ARGON_ITERATIONS = 3
    private const val ARGON_PARALLELISM = 1
    private const val KEY_LENGTH_BYTES = 32 // AES-256

    class ExpiredMessageException : Exception()

    /**
     * Encodes a CharArray as UTF-8 bytes WITHOUT ever allocating an
     * intermediate String (String.toByteArray() would create one, and
     * Strings can't be wiped from memory).
     */
    fun charsToUtf8Bytes(chars: CharArray): ByteArray {
        val byteBuffer = Charsets.UTF_8.encode(CharBuffer.wrap(chars))
        val bytes = ByteArray(byteBuffer.remaining())
        byteBuffer.get(bytes)
        if (byteBuffer.hasArray()) {
            Arrays.fill(byteBuffer.array(), 0)
        }
        return bytes
    }

    fun deriveKey(passChars: CharArray, salt: ByteArray): ByteArray {
        val passBytes = charsToUtf8Bytes(passChars)
        try {
            val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(ARGON_ITERATIONS)
                .withMemoryAsKB(ARGON_MEMORY_KB)
                .withParallelism(ARGON_PARALLELISM)
                .withSalt(salt)
                .build()
            val generator = Argon2BytesGenerator()
            generator.init(params)
            val keyBytes = ByteArray(KEY_LENGTH_BYTES)
            generator.generateBytes(passBytes, keyBytes)
            return keyBytes
        } finally {
            Arrays.fill(passBytes, 0)
        }
    }

    private fun buildHeader(hasExpiry: Boolean, expiryEpochSeconds: Long): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_LENGTH)
        buffer.put(FORMAT_VERSION)
        buffer.put(if (hasExpiry) 1.toByte() else 0.toByte())
        buffer.putLong(expiryEpochSeconds)
        return buffer.array()
    }

    fun encrypt(textChars: CharArray, passChars: CharArray, expirySeconds: Long?): String {
        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val keyBytes = deriveKey(passChars, salt)
        val plainBytes = charsToUtf8Bytes(textChars)
        try {
            val hasExpiry = expirySeconds != null
            val expiryEpoch = if (hasExpiry) (System.currentTimeMillis() / 1000L) + expirySeconds!! else 0L
            val header = buildHeader(hasExpiry, expiryEpoch)

            val key = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(header)
            val cipherBytes = cipher.doFinal(plainBytes)

            val combined = header + salt + iv + cipherBytes
            return Base64.encodeToString(combined, Base64.NO_WRAP)
        } finally {
            Arrays.fill(keyBytes, 0)
            Arrays.fill(plainBytes, 0)
        }
    }

    /**
     * Returns the decrypted plaintext as a CharArray (never String, so
     * the caller can zero it immediately after use - this is the most
     * sensitive value in the whole app).
     */
    fun decrypt(b64: String, passChars: CharArray): CharArray {
        val combined = Base64.decode(b64, Base64.NO_WRAP)
        require(combined.size > HEADER_LENGTH + SALT_LENGTH + IV_LENGTH) { "ciphertext too short" }

        val header = combined.copyOfRange(0, HEADER_LENGTH)
        val headerBuf = ByteBuffer.wrap(header)
        headerBuf.get() // version - not currently branched on, reserved for future format changes
        val hasExpiry = headerBuf.get().toInt() == 1
        val expiryEpoch = headerBuf.long

        // Checked BEFORE spending time on the (deliberately expensive)
        // Argon2id key derivation below, so an expired message fails
        // fast without doing the costly work first.
        if (hasExpiry && System.currentTimeMillis() / 1000L > expiryEpoch) {
            throw ExpiredMessageException()
        }

        val salt = combined.copyOfRange(HEADER_LENGTH, HEADER_LENGTH + SALT_LENGTH)
        val iv = combined.copyOfRange(HEADER_LENGTH + SALT_LENGTH, HEADER_LENGTH + SALT_LENGTH + IV_LENGTH)
        val cipherBytes = combined.copyOfRange(HEADER_LENGTH + SALT_LENGTH + IV_LENGTH, combined.size)
        val keyBytes = deriveKey(passChars, salt)
        try {
            val key = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(header)
            val plainBytes = cipher.doFinal(cipherBytes)
            try {
                val charBuffer = Charsets.UTF_8.decode(ByteBuffer.wrap(plainBytes))
                val chars = CharArray(charBuffer.remaining())
                charBuffer.get(chars)
                return chars
            } finally {
                Arrays.fill(plainBytes, 0)
            }
        } catch (e: AEADBadTagException) {
            // Wrong key OR a tampered header/ciphertext - GCM
            // deliberately can't tell you which, that's by design.
            throw e
        } finally {
            Arrays.fill(keyBytes, 0)
        }
    }

    /**
     * Cheap, non-cryptographic sanity check used by the keyboard's
     * quick-decrypt panel to decide whether clipboard content is even
     * WORTH attempting to decrypt (so it can say "no encrypted message
     * found" instead of running the semi-expensive Argon2id path on
     * whatever unrelated text happens to be on the clipboard).
     */
    fun looksLikeCiphertext(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 40) return false
        val decoded = try {
            Base64.decode(trimmed, Base64.NO_WRAP)
        } catch (_: Exception) {
            return false
        }
        return decoded.size > HEADER_LENGTH + SALT_LENGTH + IV_LENGTH
    }
}
