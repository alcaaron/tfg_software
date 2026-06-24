package com.punchthrough.blestarterappandroid

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import com.punchthrough.blestarterappandroid.R
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map
import androidx.lifecycle.viewModelScope
import com.punchthrough.blestarterappandroid.ble.ConnectionManager
import com.punchthrough.blestarterappandroid.crypto.A3MeshKeyStore
import com.punchthrough.blestarterappandroid.crypto.EcdhHelper
import com.punchthrough.blestarterappandroid.crypto.HkdfSha256
import com.punchthrough.blestarterappandroid.crypto.NetworkKeyManager
import com.punchthrough.blestarterappandroid.data.database.AppDatabase
import com.punchthrough.blestarterappandroid.data.dao.UnreadCount
import com.punchthrough.blestarterappandroid.data.model.Contact
import com.punchthrough.blestarterappandroid.data.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.Calendar
import java.util.UUID

data class NeighborNode(val nodeId: String, val rssi: String, val t: String)

private val BLE_WRITE_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")

class BleViewModel(private val app: Application) : AndroidViewModel(app) {

    companion object {
        const val PUBLIC_CHANNEL_ADDRESS = 0
    }

    init {
        scheduleMidnightKeyRefresh()
    }

    private val db = AppDatabase.getInstance(app)
    private val messageDao = db.messageDao()
    private val contactDao = db.contactDao()

    val keyStore = A3MeshKeyStore(app)

    private val _connectedDevice = MutableLiveData<BluetoothDevice?>(null)
    val connectedDevice: LiveData<BluetoothDevice?> = _connectedDevice

    private val _connectionStatus = MutableLiveData<String>(app.getString(R.string.disconnected_status))
    val connectionStatus: LiveData<String> = _connectionStatus

    private val _incomingMessage = MutableLiveData<Message>()
    val incomingMessage: LiveData<Message> = _incomingMessage

    private val _deviceInfo = MutableLiveData<Map<String, String>>(emptyMap())
    val deviceInfo: LiveData<Map<String, String>> = _deviceInfo

    private val _neighbors = MutableLiveData<List<NeighborNode>>(emptyList())
    val neighbors: LiveData<List<NeighborNode>> = _neighbors

    private val _atResponse = MutableLiveData<String>()
    val atResponse: LiveData<String> = _atResponse

    fun onRawAtResponse(text: String) {
        _atResponse.postValue(text)
    }

    val allContacts: LiveData<List<Contact>> = contactDao.getAllContacts()
    val lastMessages: LiveData<List<Message>> = messageDao.getLastMessagePerContact()
    val unreadCounts: LiveData<Map<Int, Int>> = messageDao.getUnreadCountsPerContact()
        .map { list -> list.associate { it.contactAddress to it.count } }

    @SuppressLint("MissingPermission")
    fun onDeviceConnected(device: BluetoothDevice) {
        _connectedDevice.postValue(device)
        _connectionStatus.postValue(app.getString(R.string.connected_to, device.name ?: device.address))
        reprovisionAllKeys()
        sendAtCommand("AT+NEIGHBORS?\r\n")
    }

    fun onDeviceDisconnected() {
        _connectedDevice.postValue(null)
        _connectionStatus.postValue(app.getString(R.string.disconnected_status))
        _deviceInfo.postValue(emptyMap())
        _neighbors.postValue(emptyList())
    }

    fun onDeviceInfoReceived(info: Map<String, String>) {
        _deviceInfo.postValue(info)
    }

    fun onNeighborsReceived(nodes: List<NeighborNode>) {
        _neighbors.postValue(nodes)
    }

    fun sendAtCommand(command: String) {
        val device = ConnectionManager.connectedDevices().firstOrNull() ?: return
        val characteristic = ConnectionManager.servicesOnDevice(device)
            ?.flatMap { it.characteristics ?: emptyList() }
            ?.find { it.uuid == BLE_WRITE_UUID } ?: return
        ConnectionManager.writeCharacteristic(device, characteristic, command.toByteArray(Charsets.UTF_8))
    }

    fun onMessageReceived(senderAddress: Int, content: String) {
        val message = Message(
            contactAddress = senderAddress,
            content = content,
            timestamp = System.currentTimeMillis(),
            isOutgoing = false,
            isRead = false
        )
        viewModelScope.launch(Dispatchers.IO) {
            messageDao.insert(message)
            _incomingMessage.postValue(message)
            val contact = contactDao.getByAddress(senderAddress)
            if (contact != null) {
                contactDao.insertOrUpdate(contact.copy(lastSeen = System.currentTimeMillis()))
            }
        }
    }

    fun onPublicMessageReceived(senderAddress: Int, content: String) {
        val message = Message(
            contactAddress = PUBLIC_CHANNEL_ADDRESS,
            content = content,
            timestamp = System.currentTimeMillis(),
            isOutgoing = false,
            senderAddress = senderAddress,
            isRead = false
        )
        viewModelScope.launch(Dispatchers.IO) {
            messageDao.insert(message)
            _incomingMessage.postValue(message)
        }
    }

    fun onGroupMessageReceived(groupId: Int, senderAddress: Int, content: String) {
        val message = Message(
            contactAddress = groupId,
            content = content,
            timestamp = System.currentTimeMillis(),
            isOutgoing = false,
            senderAddress = senderAddress,
            isRead = false
        )
        viewModelScope.launch(Dispatchers.IO) {
            messageDao.insert(message)
            _incomingMessage.postValue(message)
        }
    }

    fun onMessageSent(recipientAddress: Int, content: String) {
        val message = Message(
            contactAddress = recipientAddress,
            content = content,
            timestamp = System.currentTimeMillis(),
            isOutgoing = true
        )
        viewModelScope.launch(Dispatchers.IO) {
            messageDao.insert(message)
        }
    }

    fun sendMessage(dst: Int, text: String) {
        when {
            dst == PUBLIC_CHANNEL_ADDRESS -> {
                sendAtCommand("AT+SENDNET=ffffffff,$text\r\n")
            }
            keyStore.hasGroupKey(dst) -> {
                sendAtCommand("AT+SENDGRP=${dst.hexId},$text\r\n")
            }
            keyStore.hasPeerKey(dst) -> {
                sendAtCommand("AT+SENDE2E=${dst.hexId},$text\r\n")
            }
            else -> {
                sendAtCommand("AT+SEND=${dst.hexId},$text\r\n")
                initiateKeyExchange(dst)
            }
        }
        onMessageSent(dst, text)
    }

    // ── Key exchange ──────────────────────────────────────────────────────────

    fun initiateKeyExchange(nodeId: Int) {
        if (keyStore.hasPeerKey(nodeId)) return
        viewModelScope.launch(Dispatchers.Default) {
            val existingPubHex = keyStore.getPendingPublicKeyHex(nodeId)
            if (existingPubHex != null) {
                // Pending but peer's response never arrived: resend our public key to prompt re-response
                sendAtCommand("AT+SENDKX=${nodeId.hexId},$existingPubHex\r\n")
            } else {
                val pair = EcdhHelper.generateKeyPair()
                val rawPub = EcdhHelper.publicKeyToRaw(pair.public)
                val pubHex = rawPub.toHex()
                keyStore.setPendingKeyPair(nodeId, EcdhHelper.privateKeyToBytes(pair.private), pubHex)
                sendAtCommand("AT+SENDKX=${nodeId.hexId},$pubHex\r\n")
            }
        }
    }

    fun resetAndExchange(nodeId: Int) {
        keyStore.removePeerKey(nodeId)
        keyStore.clearPendingKeyExchange(nodeId)
        initiateKeyExchange(nodeId)
    }

    fun onKeyExchangeReceived(src: Int, pubKeyHex: String) {
        if (pubKeyHex.length != 128) return
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                val peerRaw = pubKeyHex.fromHex()
                val peerPubKey = EcdhHelper.rawToPublicKey(peerRaw)

                if (keyStore.hasPendingKeyExchange(src)) {
                    // We initiated: finalize with our stored private key
                    val privBytes = keyStore.getPendingPrivateKey(src) ?: return@launch
                    val privKey = EcdhHelper.bytesToPrivateKey(privBytes)
                    val sharedSecret = EcdhHelper.computeSharedSecret(privKey, peerPubKey)
                    val sessionKey = deriveSessionKey(sharedSecret)
                    keyStore.clearPendingKeyExchange(src)
                    keyStore.setPeerKey(src, sessionKey)
                    provisionPeerKey(src, sessionKey)
                } else {
                    // They initiated: respond with our public key then finalize
                    val pair = EcdhHelper.generateKeyPair()
                    val rawPub = EcdhHelper.publicKeyToRaw(pair.public)
                    val pubHex = rawPub.toHex()
                    sendAtCommand("AT+SENDKX=${src.hexId},$pubHex\r\n")
                    val sharedSecret = EcdhHelper.computeSharedSecret(pair.private, peerPubKey)
                    val sessionKey = deriveSessionKey(sharedSecret)
                    keyStore.setPeerKey(src, sessionKey)
                    provisionPeerKey(src, sessionKey)
                }
            }
        }
    }

    private fun deriveSessionKey(sharedSecret: ByteArray): ByteArray =
        HkdfSha256.derive(sharedSecret, null, "A3MESH-N2N-v1".toByteArray(Charsets.UTF_8), 32)

    private fun provisionPeerKey(nodeId: Int, key: ByteArray) {
        sendAtCommand("AT+SETPEERKEY=${nodeId.hexId},${key.toHex()}\r\n")
    }

    // ── Key reprovisioning on reconnect ───────────────────────────────────────

    fun reprovisionAllKeys() {
        viewModelScope.launch(Dispatchers.Default) {
            // Peer keys
            for (nodeId in keyStore.getAllPeerIds()) {
                val key = keyStore.getPeerKey(nodeId) ?: continue
                sendAtCommand("AT+SETPEERKEY=${nodeId.hexId},${key.toHex()}\r\n")
            }
            // Group keys
            for (groupId in keyStore.getAllGroupIds()) {
                val key = keyStore.getGroupKey(groupId) ?: continue
                sendAtCommand("AT+SETGRPKEY=${groupId.hexId},${key.toHex()}\r\n")
            }
            // Network key: only send if the day changed since last provisioning
            refreshNetKeyIfNeeded()
        }
    }

    private fun refreshNetKeyIfNeeded() {
        val today = NetworkKeyManager.today()
        if (today != keyStore.getLastNetKeyDate()) {
            val dailyKey = NetworkKeyManager.deriveDailyKey()
            sendAtCommand("AT+SETNETKEY=${dailyKey.toHex()}\r\n")
            keyStore.setLastNetKeyDate(today)
        }
    }

    private fun scheduleMidnightKeyRefresh() {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val now = Calendar.getInstance()
                val nextMidnight = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 5)
                    set(Calendar.MILLISECOND, 0)
                }
                delay(nextMidnight.timeInMillis - now.timeInMillis)
                if (_connectedDevice.value != null) {
                    refreshNetKeyIfNeeded()
                }
            }
        }
    }

    // ── Group management ──────────────────────────────────────────────────────

    fun createGroup(name: String): Int {
        val rng = SecureRandom()
        val groupId = rng.nextInt() or Int.MIN_VALUE  // high bit set → negative Int
        val key = ByteArray(32).also { rng.nextBytes(it) }
        keyStore.setGroupKey(groupId, name, key)
        sendAtCommand("AT+SETGRPKEY=${groupId.hexId},${key.toHex()}\r\n")
        viewModelScope.launch(Dispatchers.IO) {
            messageDao.insert(Message(
                contactAddress = groupId,
                content = app.getString(R.string.msg_group_created),
                timestamp = System.currentTimeMillis(),
                isOutgoing = true
            ))
        }
        return groupId
    }

    fun joinGroup(groupId: Int, name: String, key: ByteArray) {
        keyStore.setGroupKey(groupId, name, key)
        sendAtCommand("AT+SETGRPKEY=${groupId.hexId},${key.toHex()}\r\n")
        viewModelScope.launch(Dispatchers.IO) {
            messageDao.insert(Message(
                contactAddress = groupId,
                content = app.getString(R.string.msg_group_joined),
                timestamp = System.currentTimeMillis(),
                isOutgoing = false
            ))
        }
    }

    // Encodes a group's ID + key + name as a shareable QR-friendly string.
    // Format: "a3g:<8hex_id>:<64hex_key>:<name>"
    fun groupCode(groupId: Int): String {
        val key = keyStore.getGroupKey(groupId) ?: return ""
        val name = keyStore.getGroupName(groupId)
        return "a3g:${groupId.hexId}:${key.toHex()}:$name"
    }

    // Returns Triple(groupId, key, name) or null if format is invalid.
    fun parseGroupCode(code: String): Triple<Int, ByteArray, String>? {
        if (!code.startsWith("a3g:")) return null
        val parts = code.removePrefix("a3g:").split(":", limit = 3)
        if (parts.size != 3) return null
        val groupId = parts[0].toLongOrNull(16)?.toInt() ?: return null
        val key = try { parts[1].fromHex() } catch (e: Exception) { return null }
        if (key.size != 32) return null
        return Triple(groupId, key, parts[2])
    }

    // ── Contact / message persistence ────────────────────────────────────────

    fun saveContact(address: Int, name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            contactDao.insertOrUpdate(Contact(address = address, name = name))
        }
    }

    fun deleteContact(contact: Contact) {
        viewModelScope.launch(Dispatchers.IO) {
            contactDao.delete(contact)
        }
    }

    fun markConversationRead(address: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            messageDao.markAsRead(address)
        }
    }

    fun deleteChat(address: Int, isGroup: Boolean) {
        if (isGroup) keyStore.removeGroupKey(address) else keyStore.removePeerKey(address)
        viewModelScope.launch(Dispatchers.IO) {
            messageDao.deleteConversation(address)
        }
    }

    fun getMessagesForContact(address: Int): LiveData<List<Message>> =
        messageDao.getMessagesForContact(address)

    // ── Private helpers ───────────────────────────────────────────────────────

    private val Int.hexId: String get() = "%08x".format(this.toLong() and 0xFFFFFFFFL)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.fromHex(): ByteArray {
        check(length % 2 == 0)
        return ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    }
}
