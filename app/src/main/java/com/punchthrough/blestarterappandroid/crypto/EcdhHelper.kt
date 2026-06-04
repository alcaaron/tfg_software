package com.punchthrough.blestarterappandroid.crypto

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

object EcdhHelper {

    // Fixed DER header for P-256 SubjectPublicKeyInfo (27 bytes), ending with 0x04 (uncompressed)
    private val P256_DER_HEADER = byteArrayOf(
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86.toByte(),
        0x48, 0xce.toByte(), 0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a.toByte(),
        0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x03, 0x01, 0x07, 0x03,
        0x42, 0x00, 0x04
    )

    fun generateKeyPair(): KeyPair {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"))
        return gen.generateKeyPair()
    }

    // Returns 64 raw bytes: X || Y (strips the 27-byte DER header)
    fun publicKeyToRaw(pubKey: PublicKey): ByteArray {
        val encoded = pubKey.encoded  // X.509 DER, 91 bytes for P-256
        return encoded.copyOfRange(P256_DER_HEADER.size, encoded.size)
    }

    // Reconstructs a PublicKey from 64 raw bytes (X || Y)
    fun rawToPublicKey(rawXY: ByteArray): PublicKey {
        require(rawXY.size == 64) { "Raw P-256 public key must be 64 bytes" }
        val der = P256_DER_HEADER + rawXY
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
    }

    fun privateKeyToBytes(privKey: PrivateKey): ByteArray = privKey.encoded  // PKCS#8 DER

    fun bytesToPrivateKey(bytes: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes))

    // Returns the raw 32-byte ECDH shared secret
    fun computeSharedSecret(privKey: PrivateKey, peerPubKey: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privKey)
        ka.doPhase(peerPubKey, true)
        return ka.generateSecret()
    }
}
