package com.punchthrough.blestarterappandroid.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object HkdfSha256 {

    fun derive(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // Extract: PRK = HMAC-SHA256(salt, IKM)
        mac.init(SecretKeySpec(salt ?: ByteArray(32), "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        // Expand: T(1) || T(2) || ... until length bytes collected
        val result = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var ctr = 1
        while (pos < length) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(ctr.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, result, pos, n)
            pos += n
            ctr++
        }
        return result
    }
}
