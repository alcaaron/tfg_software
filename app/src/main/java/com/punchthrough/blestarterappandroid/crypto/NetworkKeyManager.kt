package com.punchthrough.blestarterappandroid.crypto

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NetworkKeyManager {

    private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    // Fixed network seed — all A3MESH nodes share the same derivation.
    // The daily key rotates every day; yesterday's key cannot be reused.
    private val NETWORK_SEED = "A3MESH-NET-v1".toByteArray(Charsets.UTF_8)

    // Derives the 32-byte AES-256 key for today automatically.
    // info = "A3MESH-NET-DAILY-v1" || "YYYY-MM-DD"
    fun deriveDailyKey(): ByteArray {
        val today = DATE_FORMAT.format(Date())
        val info = "A3MESH-NET-DAILY-v1".toByteArray(Charsets.UTF_8) + today.toByteArray(Charsets.UTF_8)
        return HkdfSha256.derive(NETWORK_SEED, null, info, 32)
    }
}
