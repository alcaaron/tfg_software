package com.punchthrough.blestarterappandroid.security

import java.security.KeyPair
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey

/**
 * In-memory store for session keys.
 * Keys are NOT persisted — they are re-derived on each session via the ECDH handshake
 * triggered by +DISC= events.
 *
 * Thread-safe: all maps are ConcurrentHashMap.
 */
object SessionKeyStore {

    // node_id (Int) → AES-128 key established via ECDH
    private val e2eKeys = ConcurrentHashMap<Int, SecretKey>()

    // group_id (Int) → AES-128 key distributed out-of-band (QR code)
    private val groupKeys = ConcurrentHashMap<Int, SecretKey>()

    // Nodes for which we have sent our public key but not yet received theirs.
    // Consumed (removed) once the remote public key arrives.
    private val pendingKeyPairs = ConcurrentHashMap<Int, KeyPair>()

    // -------------------------------------------------------------------------
    // E2E (node-to-node) keys
    // -------------------------------------------------------------------------

    fun storeE2eKey(nodeId: Int, key: SecretKey) {
        e2eKeys[nodeId] = key
    }

    fun getE2eKey(nodeId: Int): SecretKey? = e2eKeys[nodeId]

    fun hasE2eKey(nodeId: Int): Boolean = e2eKeys.containsKey(nodeId)

    fun removeE2eKey(nodeId: Int) {
        e2eKeys.remove(nodeId)
    }

    // -------------------------------------------------------------------------
    // Group keys
    // -------------------------------------------------------------------------

    fun storeGroupKey(groupId: Int, key: SecretKey) {
        groupKeys[groupId] = key
    }

    fun getGroupKey(groupId: Int): SecretKey? = groupKeys[groupId]

    fun hasGroupKey(groupId: Int): Boolean = groupKeys.containsKey(groupId)

    fun removeGroupKey(groupId: Int) {
        groupKeys.remove(groupId)
    }

    // -------------------------------------------------------------------------
    // Pending ECDH state
    // -------------------------------------------------------------------------

    /** Called when we initiate a handshake: stores our KeyPair while we wait for the reply. */
    fun storePendingKeyPair(nodeId: Int, kp: KeyPair) {
        pendingKeyPairs[nodeId] = kp
    }

    /**
     * Returns and removes the pending KeyPair for [nodeId], or null if we didn't initiate.
     * Use this to distinguish initiator (has pending KP) from responder (no pending KP).
     */
    fun consumePendingKeyPair(nodeId: Int): KeyPair? = pendingKeyPairs.remove(nodeId)

    fun hasPendingKeyPair(nodeId: Int): Boolean = pendingKeyPairs.containsKey(nodeId)

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /** Wipes all keys — call on BLE disconnect to enforce forward secrecy. */
    fun clearAll() {
        e2eKeys.clear()
        groupKeys.clear()
        pendingKeyPairs.clear()
    }
}
