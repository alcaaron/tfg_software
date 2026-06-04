package com.punchthrough.blestarterappandroid.crypto

import android.content.Context
import android.content.SharedPreferences

class A3MeshKeyStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("a3mesh_keys", Context.MODE_PRIVATE)

    // ── Network master secret ─────────────────────────────────────────────────

    fun setNetMasterSecret(masterSecret: ByteArray) {
        prefs.edit().putString("net_master_secret", masterSecret.toHex()).apply()
    }

    fun getNetMasterSecret(): ByteArray? =
        prefs.getString("net_master_secret", null)?.fromHex()

    fun hasNetMasterSecret(): Boolean = prefs.contains("net_master_secret")

    // ── Peer keys (provisioned from ECDH) ────────────────────────────────────

    fun setPeerKey(nodeId: Int, key: ByteArray) {
        prefs.edit().putString("peer_key_${nodeId.hex}", key.toHex()).apply()
        addPeerId(nodeId)
    }

    fun getPeerKey(nodeId: Int): ByteArray? =
        prefs.getString("peer_key_${nodeId.hex}", null)?.fromHex()

    fun hasPeerKey(nodeId: Int): Boolean = prefs.contains("peer_key_${nodeId.hex}")

    fun removePeerKey(nodeId: Int) {
        prefs.edit().remove("peer_key_${nodeId.hex}").apply()
        removePeerId(nodeId)
    }

    fun getAllPeerIds(): List<Int> {
        val raw = prefs.getString("peer_ids", "") ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split(",").mapNotNull { it.trim().toLongOrNull(16)?.toInt() }
    }

    private fun addPeerId(nodeId: Int) {
        val existing = getAllPeerIds().toMutableList()
        if (!existing.contains(nodeId)) {
            existing.add(nodeId)
            prefs.edit().putString("peer_ids", existing.joinToString(",") { it.hex }).apply()
        }
    }

    private fun removePeerId(nodeId: Int) {
        val existing = getAllPeerIds().toMutableList()
        existing.remove(nodeId)
        prefs.edit().putString("peer_ids", existing.joinToString(",") { it.hex }).apply()
    }

    // ── Pending ECDH private keys ─────────────────────────────────────────────

    fun setPendingPrivateKey(nodeId: Int, pkcs8Bytes: ByteArray) {
        prefs.edit().putString("pending_kx_${nodeId.hex}", pkcs8Bytes.toHex()).apply()
    }

    fun getPendingPrivateKey(nodeId: Int): ByteArray? =
        prefs.getString("pending_kx_${nodeId.hex}", null)?.fromHex()

    fun hasPendingKeyExchange(nodeId: Int): Boolean = prefs.contains("pending_kx_${nodeId.hex}")

    fun clearPendingKeyExchange(nodeId: Int) {
        prefs.edit().remove("pending_kx_${nodeId.hex}").apply()
    }

    // ── Group keys ────────────────────────────────────────────────────────────

    fun setGroupKey(groupId: Int, name: String, key: ByteArray) {
        prefs.edit()
            .putString("grp_key_${groupId.hex}", key.toHex())
            .putString("grp_name_${groupId.hex}", name)
            .apply()
        addGroupId(groupId)
    }

    fun getGroupKey(groupId: Int): ByteArray? =
        prefs.getString("grp_key_${groupId.hex}", null)?.fromHex()

    fun getGroupName(groupId: Int): String =
        prefs.getString("grp_name_${groupId.hex}", "Grupo ${groupId.hex}") ?: "Grupo"

    fun hasGroupKey(groupId: Int): Boolean = prefs.contains("grp_key_${groupId.hex}")

    fun getAllGroupIds(): List<Int> {
        val raw = prefs.getString("group_ids", "") ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split(",").mapNotNull { it.trim().toLongOrNull(16)?.toInt() }
    }

    fun removeGroupKey(groupId: Int) {
        prefs.edit()
            .remove("grp_key_${groupId.hex}")
            .remove("grp_name_${groupId.hex}")
            .apply()
        removeGroupId(groupId)
    }

    private fun addGroupId(groupId: Int) {
        val existing = getAllGroupIds().toMutableList()
        if (!existing.contains(groupId)) {
            existing.add(groupId)
            prefs.edit().putString("group_ids", existing.joinToString(",") { it.hex }).apply()
        }
    }

    private fun removeGroupId(groupId: Int) {
        val existing = getAllGroupIds().toMutableList()
        existing.remove(groupId)
        prefs.edit().putString("group_ids", existing.joinToString(",") { it.hex }).apply()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private val Int.hex: String get() = "%08x".format(this.toLong() and 0xFFFFFFFFL)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.fromHex(): ByteArray {
        check(length % 2 == 0)
        return ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    }
}
