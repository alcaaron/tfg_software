package com.punchthrough.blestarterappandroid.security

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Stateless crypto primitives for the secure channel layer.
 *
 * Key exchange:  ECDH over P-256
 * Key derivation: SHA-256(shared_secret) → first 16 bytes = AES-128 key
 * Encryption:    AES-128-GCM, 12-byte random IV, 128-bit tag
 * Wire encoding: Base64 (NO_WRAP) for all binary blobs sent over BLE AT commands
 */
object CryptoManager {

    private const val EC_CURVE = "secp256r1"
    private const val GCM_IV_LEN = 12
    private const val GCM_TAG_BITS = 128
    private const val AES_KEY_LEN = 16 // AES-128

    // -------------------------------------------------------------------------
    // ECDH key pair
    // -------------------------------------------------------------------------

    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec(EC_CURVE))
        }.generateKeyPair()

    /**
     * Computes the raw ECDH shared secret.
     * [remotePubBytes] must be the X.509/SubjectPublicKeyInfo encoding of the
     * remote EC public key (what [pubKeyToBase64] / [pubKey.encoded] produces).
     */
    fun computeSharedSecret(localPrivate: PrivateKey, remotePubBytes: ByteArray): ByteArray {
        val remotePub = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(remotePubBytes))
        return KeyAgreement.getInstance("ECDH").apply {
            init(localPrivate)
            doPhase(remotePub, true)
        }.generateSecret()
    }

    /**
     * Derives an AES-128 [SecretKey] from a raw ECDH shared secret.
     * Uses the first 16 bytes of SHA-256(sharedSecret).
     */
    fun deriveAesKey(sharedSecret: ByteArray): SecretKey {
        val hash = MessageDigest.getInstance("SHA-256").digest(sharedSecret)
        return SecretKeySpec(hash, 0, AES_KEY_LEN, "AES")
    }

    // -------------------------------------------------------------------------
    // AES-128-GCM encrypt / decrypt
    // -------------------------------------------------------------------------

    /**
     * Encrypts [plaintext] with AES-128-GCM.
     * Returns a byte array: [IV (12 B)] + [ciphertext + 16-B GCM tag].
     */
    fun encrypt(key: SecretKey, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipherWithTag = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            doFinal(plaintext)
        }
        return iv + cipherWithTag
    }

    /**
     * Decrypts a byte array produced by [encrypt].
     * Throws if authentication fails (tampered or wrong key).
     */
    fun decrypt(key: SecretKey, cipherBytes: ByteArray): ByteArray {
        require(cipherBytes.size > GCM_IV_LEN) { "Ciphertext too short" }
        val iv = cipherBytes.copyOfRange(0, GCM_IV_LEN)
        val data = cipherBytes.copyOfRange(GCM_IV_LEN, cipherBytes.size)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            doFinal(data)
        }
    }

    // -------------------------------------------------------------------------
    // Wire encoding helpers
    // -------------------------------------------------------------------------

    /** Encodes a public key as Base64(X.509 DER) — safe to embed in an AT command. */
    fun pubKeyToBase64(pubKey: PublicKey): String =
        Base64.encodeToString(pubKey.encoded, Base64.NO_WRAP)

    /** Reconstructs an EC public key from bytes produced by [pubKeyToBase64]. */
    fun base64ToPubKey(b64: String): PublicKey {
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    }

    /** Generic Base64 encode (NO_WRAP — no line breaks that would break the AT protocol). */
    fun toBase64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    /** Generic Base64 decode. */
    fun fromBase64(b64: String): ByteArray =
        Base64.decode(b64, Base64.NO_WRAP)
}
