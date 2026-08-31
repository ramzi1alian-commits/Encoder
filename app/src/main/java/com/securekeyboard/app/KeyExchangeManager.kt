package com.securekeyboard.app

import android.content.Context
import android.util.Base64
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays

/**
 * KeyExchangeManager
 *
 * Adds a genuine two-party public-key exchange (X25519) as an
 * alternative to CryptoEngine's shared-passphrase mode - see the design
 * discussion this came out of: two ordinary passwords (one "for
 * sending", one "for receiving") don't actually add security, because
 * AES-GCM is symmetric and both passwords still have to cross the same
 * channel as a shared secret. X25519 is different in kind: each side
 * keeps a private key that NEVER leaves its own device, and the two
 * sides only ever exchange PUBLIC keys - which are safe to send in the
 * clear over WhatsApp, SMS, read aloud, whatever channel is convenient.
 *
 * HOW IT WORKS:
 *  - The first time this feature is used, this device generates ONE
 *    long-term X25519 identity keypair. The private key is encrypted at
 *    rest via LocalStorageCrypto (Android Keystore-backed AES-256-GCM) -
 *    the same protection already used for the learned-word/phrase
 *    dictionaries - and never leaves this object as raw bytes.
 *  - The public key is shown so you can copy it to the other person.
 *    They paste it into THEIR app as your peer key, and send you theirs
 *    the same way.
 *  - Once both sides hold each other's public key, X25519 lets each
 *    side compute the exact same shared secret from (my private + their
 *    public) - the other side computes the identical value from (their
 *    private + my public) - without either private key ever being
 *    transmitted or reconstructable by an eavesdropper who only saw the
 *    two public keys.
 *  - The raw X25519 output is never used directly as an AES key; it's
 *    run through HKDF-SHA256 first, with an info string built from BOTH
 *    public keys in a fixed (sorted) order, so both sides land on the
 *    identical AES-256 key regardless of who initiated the exchange.
 *
 * WHAT THIS DOES NOT DO (read before relying on it for anything
 * sensitive): X25519 defeats a passive eavesdropper who only sees the
 * exchanged public keys and the ciphertext. It does NOT by itself defeat
 * an ACTIVE man-in-the-middle who intercepts and substitutes BOTH
 * sides' public keys during the very first exchange - that person could
 * then read everything while both ends believe they're talking directly
 * to each other. fingerprint() exists as the mitigation: read a few
 * characters of it to the other person over a channel you already trust
 * (a phone call, in person) and confirm it matches what their app shows
 * for the key you just received, BEFORE trusting it for anything
 * sensitive. There is also no forward secrecy - this is one long-term
 * identity key per device, not a fresh key per session/message, so
 * anyone who later obtains your private key can retroactively decrypt
 * anything encrypted under it that they also captured. Regenerating the
 * identity (see regenerateIdentity) is a genuine "burn it down and
 * start over" action, not routine maintenance: it invalidates the
 * shared key with every peer you'd previously exchanged with, and they
 * would all need your new public key again.
 */
object KeyExchangeManager {

    private const val PREFS_FILE = "secure_keyboard_keyexchange_prefs"
    private const val KEY_MY_PUBLIC = "my_public_key_b64"
    private const val KEY_PEER_PUBLIC = "peer_public_key_b64"
    private const val IDENTITY_FILE_NAME = "x25519_identity.enc"
    private const val X25519_KEY_LENGTH = 32

    // Domain-separation prefix mixed into the HKDF "info" parameter,
    // together with both public keys - keeps this derivation from ever
    // colliding with some other HKDF use elsewhere, and ties the output
    // key to this specific protocol/version.
    private const val HKDF_INFO_PREFIX = "SecureKeyboardX25519v1"

    /** Thrown by deriveSharedAesKey() when no valid peer public key has been saved yet. */
    class NoPeerKeyException : Exception()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private fun identityFile(context: Context): File =
        File(context.filesDir, IDENTITY_FILE_NAME)

    /** Generates a brand new identity keypair and persists both halves. Not for direct external use - see regenerateIdentity(). */
    private fun generateAndStoreIdentity(context: Context): X25519PrivateKeyParameters {
        val generator = X25519KeyPairGenerator()
        generator.init(X25519KeyGenerationParameters(SecureRandom()))
        val keyPair = generator.generateKeyPair()
        val privateKey = keyPair.private as X25519PrivateKeyParameters
        val publicKey = keyPair.public as X25519PublicKeyParameters

        val privateBytes = privateKey.encoded
        try {
            identityFile(context).writeBytes(LocalStorageCrypto.encrypt(privateBytes))
        } finally {
            Arrays.fill(privateBytes, 0)
        }
        prefs(context).edit()
            .putString(KEY_MY_PUBLIC, Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP))
            .apply()
        return privateKey
    }

    /** Loads the stored identity private key from disk, transparently regenerating it if missing or unreadable. */
    private fun loadOrCreatePrivateKey(context: Context): X25519PrivateKeyParameters {
        val file = identityFile(context)
        if (file.exists()) {
            val decrypted = LocalStorageCrypto.decrypt(file.readBytes())
            if (decrypted != null && decrypted.size == X25519_KEY_LENGTH) {
                try {
                    return X25519PrivateKeyParameters(decrypted, 0)
                } finally {
                    Arrays.fill(decrypted, 0)
                }
            }
            // Corrupt/undecryptable identity file - same "start fresh
            // rather than crash" philosophy as LocalStorageCrypto.decrypt
            // itself. This does invalidate any previously exchanged
            // shared key with peers, same as an explicit regenerate.
        }
        return generateAndStoreIdentity(context)
    }

    /** Makes sure an identity keypair exists; safe to call repeatedly (e.g. every time the exchange screen opens). */
    fun ensureIdentityExists(context: Context) {
        if (!prefs(context).contains(KEY_MY_PUBLIC) || !identityFile(context).exists()) {
            generateAndStoreIdentity(context)
        }
    }

    /**
     * This device's own public key, Base64-encoded - not secret, safe to
     * display and share as-is.
     *
     * FIX (reported bug): this used to call ensureIdentityExists()
     * uncaught, and EncryptActivity.onCreate() calls this UNCONDITIONALLY
     * (via setUpKeyExchangeUi(), regardless of whether the user has even
     * touched the key-exchange switch) to populate the "your public key"
     * display. Identity generation goes through the Android Keystore
     * (see LocalStorageCrypto.encrypt, which - unlike its own decrypt() -
     * does NOT catch its own exceptions), and Keystore key generation is
     * known to occasionally throw on some devices/OEMs/emulators (e.g.
     * right after boot, or on custom ROMs with a flaky StrongBox/TEE
     * provider). An uncaught exception here meant EncryptActivity crashed
     * the INSTANT it was opened - before the user ever pressed anything -
     * which looked exactly like "opening the tool exits automatically".
     * Catching here means a Keystore failure degrades to "no key
     * available yet" (empty string) instead of crashing the whole
     * encrypt/decrypt screen, which is needed regardless of key-exchange
     * mode.
     */
    fun myPublicKeyBase64(context: Context): String {
        return try {
            ensureIdentityExists(context)
            prefs(context).getString(KEY_MY_PUBLIC, "") ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    fun hasPeerPublicKey(context: Context): Boolean =
        prefs(context).getString(KEY_PEER_PUBLIC, null)?.isNotBlank() == true

    fun peerPublicKeyBase64(context: Context): String? =
        prefs(context).getString(KEY_PEER_PUBLIC, null)

    /**
     * Validates and stores the other party's public key. Returns false
     * (storing nothing) if the pasted text doesn't decode to exactly 32
     * bytes, so the caller can show an inline "that doesn't look like a
     * valid key" error instead of silently accepting garbage that would
     * only surface as a confusing decrypt failure later.
     */
    fun setPeerPublicKeyBase64(context: Context, base64: String): Boolean {
        val trimmed = base64.trim()
        val decoded = try {
            Base64.decode(trimmed, Base64.NO_WRAP)
        } catch (_: Exception) {
            return false
        }
        if (decoded.size != X25519_KEY_LENGTH) return false
        prefs(context).edit().putString(KEY_PEER_PUBLIC, trimmed).apply()
        return true
    }

    fun clearPeerPublicKey(context: Context) {
        prefs(context).edit().remove(KEY_PEER_PUBLIC).apply()
    }

    /**
     * Wipes the local identity and generates a brand new one - see the
     * class doc's honesty note. Only ever call this behind an explicit
     * confirmation; it's a "burn it down and start over" action, not
     * routine maintenance.
     */
    fun regenerateIdentity(context: Context) {
        identityFile(context).delete()
        prefs(context).edit().remove(KEY_MY_PUBLIC).apply()
        generateAndStoreIdentity(context)
    }

    /**
     * Short human-comparable fingerprint (first 4 bytes of SHA-256, as
     * hex) of a Base64-encoded public key - meant to be read aloud or
     * compared over a channel you already trust to catch a
     * man-in-the-middle substitution during the FIRST exchange (see the
     * class doc). Returns null for blank/invalid input rather than
     * throwing, so callers can just hide the fingerprint line when
     * there's nothing valid to show yet.
     */
    fun fingerprint(base64PublicKey: String?): String? {
        if (base64PublicKey.isNullOrBlank()) return null
        val decoded = try {
            Base64.decode(base64PublicKey.trim(), Base64.NO_WRAP)
        } catch (_: Exception) {
            return null
        }
        if (decoded.size != X25519_KEY_LENGTH) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(decoded)
        return digest.take(4).joinToString(" ") { "%02X".format(it) }
    }

    /**
     * Computes the AES-256 key both sides land on: X25519(myPrivate,
     * theirPublic), then HKDF-SHA256 with an info string built from
     * both public keys in a fixed lexicographic order (so it doesn't
     * matter which side "started" the exchange - both compute the same
     * info bytes, and therefore the same output key). Throws
     * NoPeerKeyException if no valid peer key has been saved.
     *
     * The returned ByteArray is sensitive AES key material - callers
     * should pass it straight to CryptoEngine.encryptWithKey/
     * decryptWithKey and then Arrays.fill it with zeros, the same
     * convention used for every other key/passphrase byte array in this
     * app.
     */
    fun deriveSharedAesKey(context: Context): ByteArray {
        val peerB64 = peerPublicKeyBase64(context) ?: throw NoPeerKeyException()
        val peerBytes = try {
            Base64.decode(peerB64, Base64.NO_WRAP)
        } catch (_: Exception) {
            throw NoPeerKeyException()
        }
        if (peerBytes.size != X25519_KEY_LENGTH) throw NoPeerKeyException()

        val myPrivate = loadOrCreatePrivateKey(context)
        val myPublicB64 = myPublicKeyBase64(context)

        val agreement = X25519Agreement()
        agreement.init(myPrivate)
        val sharedSecret = ByteArray(agreement.agreementSize)
        try {
            agreement.calculateAgreement(X25519PublicKeyParameters(peerBytes, 0), sharedSecret, 0)

            val ordered = listOf(myPublicB64, peerB64).sorted()
            val info = (HKDF_INFO_PREFIX + ordered[0] + ordered[1]).toByteArray(Charsets.UTF_8)

            val hkdf = HKDFBytesGenerator(SHA256Digest())
            hkdf.init(HKDFParameters(sharedSecret, null, info))
            val aesKey = ByteArray(CryptoEngine.KEY_LENGTH_BYTES)
            hkdf.generateBytes(aesKey, 0, aesKey.size)
            return aesKey
        } finally {
            Arrays.fill(sharedSecret, 0)
        }
    }
}
