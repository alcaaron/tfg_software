package com.punchthrough.blestarterappandroid.crypto

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NetworkKeyManager {

    private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    // Derives the 32-byte AES-256 key for today from the master passphrase secret.
    // info = "A3MESH-NET-DAILY-v1" || "YYYY-MM-DD"
    fun deriveDailyKey(masterSecret: ByteArray): ByteArray {
        val today = DATE_FORMAT.format(Date())
        val info = "A3MESH-NET-DAILY-v1".toByteArray(Charsets.UTF_8) + today.toByteArray(Charsets.UTF_8)
        return HkdfSha256.derive(masterSecret, null, info, 32)
    }

    // Derives the master secret from a user-chosen passphrase string.
    // Stored in the key store; daily key is derived from this each session.
    fun passphraseToMasterSecret(passphrase: String): ByteArray {
        val info = "A3MESH-NET-MASTER-v1".toByteArray(Charsets.UTF_8)
        val ikm = passphrase.toByteArray(Charsets.UTF_8)
        return HkdfSha256.derive(ikm, null, info, 32)
    }
}
