package com.punchthrough.blestarterappandroid

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.punchthrough.blestarterappandroid.ble.ConnectionManager
import com.punchthrough.blestarterappandroid.data.database.AppDatabase
import com.punchthrough.blestarterappandroid.data.model.Contact
import com.punchthrough.blestarterappandroid.data.model.Message
import com.punchthrough.blestarterappandroid.security.CryptoManager
import com.punchthrough.blestarterappandroid.security.SessionKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID

data class NeighborNode(val nodeId: String, val rssi: String, val t: String)

private val BLE_WRITE_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")

class BleViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val PUBLIC_CHANNEL_ADDRESS = 0
    }

    // Base de datos
    private val db = AppDatabase.getInstance(app)
    private val messageDao = db.messageDao()
    private val contactDao = db.contactDao()

    private val _connectedDevice = MutableLiveData<BluetoothDevice?>(null)
    val connectedDevice: LiveData<BluetoothDevice?> = _connectedDevice

    private val _connectionStatus = MutableLiveData<String>("Desconectado")
    val connectionStatus: LiveData<String> = _connectionStatus

    private val _incomingMessage = MutableLiveData<Message>()
    val incomingMessage: LiveData<Message> = _incomingMessage

    private val _deviceInfo = MutableLiveData<Map<String, String>>(emptyMap())
    val deviceInfo: LiveData<Map<String, String>> = _deviceInfo

    private val _neighbors = MutableLiveData<List<NeighborNode>>(emptyList())
    val neighbors: LiveData<List<NeighborNode>> = _neighbors

    // Fires with the nodeId whenever a new E2E session key is established.
    // UI can observe this to update the lock icon in ChatActivity.
    private val _keyEstablished = MutableLiveData<Int>()
    val keyEstablished: LiveData<Int> = _keyEstablished

    val allContacts: LiveData<List<Contact>> = contactDao.getAllContacts()
    val lastMessages: LiveData<List<Message>> = messageDao.getLastMessagePerContact()

    @SuppressLint("MissingPermission")
    fun onDeviceConnected(device: BluetoothDevice) {
        _connectedDevice.postValue(device)
        _connectionStatus.postValue("Conectado a ${device.name ?: device.address}")
    }

    fun onDeviceDisconnected() {
        _connectedDevice.postValue(null)
        _connectionStatus.postValue("Desconectado")
        _deviceInfo.postValue(emptyMap())
        _neighbors.postValue(emptyList())
        SessionKeyStore.clearAll()
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

    // Llama a esto cuando llegue un mensaje del módulo LoRa (cualquier tipo).
    // El contenido siempre llega ya descifrado — la capa de seguridad descifra antes de llamar aquí.
    fun onMessageReceived(senderAddress: Int, content: String, encType: Int = 0) {
        val message = Message(
            contactAddress = senderAddress,
            content = content,
            timestamp = System.currentTimeMillis(),
            isOutgoing = false,
            encType = encType
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
            encType = 0,
            senderAddress = senderAddress
        )
        viewModelScope.launch(Dispatchers.IO) {
            messageDao.insert(message)
            _incomingMessage.postValue(message)
        }
    }

    fun onMessageSent(recipientAddress: Int, content: String, encType: Int = 0) {
        val message = Message(
            contactAddress = recipientAddress,
            content = content,
            timestamp = System.currentTimeMillis(),
            isOutgoing = true,
            encType = encType
        )
        viewModelScope.launch(Dispatchers.IO) {
            messageDao.insert(message)
        }
    }

    // -------------------------------------------------------
    // Capa de seguridad — ECDH + AES-128-GCM
    // -------------------------------------------------------

    /** True si hay una clave E2E establecida para este nodo. */
    fun hasE2eKey(nodeId: Int): Boolean = SessionKeyStore.hasE2eKey(nodeId)

    /**
     * Llamado cuando llega +DISC=<node_id>.
     * Inicia el handshake ECDH si aún no tenemos clave para ese nodo.
     */
    fun onDiscoveryEvent(nodeId: Int) {
        if (SessionKeyStore.hasE2eKey(nodeId) || SessionKeyStore.hasPendingKeyPair(nodeId)) return
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val kp = CryptoManager.generateKeyPair()
                SessionKeyStore.storePendingKeyPair(nodeId, kp)
                val pubB64 = CryptoManager.pubKeyToBase64(kp.public)
                sendAtCommand("AT+SENDKX=${nodeId.toHexNodeId()},$pubB64\r\n")
                Timber.d("[CRYPTO] KX iniciado con 0x${nodeId.toHexNodeId()}")
            } catch (e: Exception) {
                Timber.e(e, "[CRYPTO] Error generando KeyPair para 0x${nodeId.toHexNodeId()}")
            }
        }
    }

    /**
     * Llamado cuando llega +KX=<src>,<node_id>,<b64_pubkey>.
     * Si somos los iniciadores (tenemos un pendingKeyPair) → derivamos clave y listo.
     * Si somos los respondedores → derivamos clave y enviamos nuestra pubkey de vuelta.
     */
    fun onKeyExchangeReceived(src: Int, b64PubKey: String) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val remotePubBytes = CryptoManager.fromBase64(b64PubKey)
                val pendingKp = SessionKeyStore.consumePendingKeyPair(src)

                if (pendingKp != null) {
                    // Somos el iniciador: esta es la respuesta del remoto
                    val shared = CryptoManager.computeSharedSecret(pendingKp.private, remotePubBytes)
                    SessionKeyStore.storeE2eKey(src, CryptoManager.deriveAesKey(shared))
                    _keyEstablished.postValue(src)
                    Timber.d("[CRYPTO] Clave E2E establecida con 0x${src.toHexNodeId()} (iniciador)")
                } else {
                    // Somos el respondedor: derivamos clave y respondemos con nuestra pubkey
                    val kp = CryptoManager.generateKeyPair()
                    val shared = CryptoManager.computeSharedSecret(kp.private, remotePubBytes)
                    SessionKeyStore.storeE2eKey(src, CryptoManager.deriveAesKey(shared))
                    _keyEstablished.postValue(src)
                    val myPubB64 = CryptoManager.pubKeyToBase64(kp.public)
                    sendAtCommand("AT+SENDKX=${src.toHexNodeId()},$myPubB64\r\n")
                    Timber.d("[CRYPTO] Clave E2E establecida con 0x${src.toHexNodeId()} (respondedor)")
                }
            } catch (e: Exception) {
                Timber.e(e, "[CRYPTO] Fallo en key exchange con 0x${src.toHexNodeId()}")
            }
        }
    }

    /**
     * Llamado cuando llega +RCVE2E=<src>,<node_id>,<b64_cipher>.
     * Descifra con la clave E2E de src y almacena el texto plano en Room.
     */
    fun onE2eMessageReceived(src: Int, b64Cipher: String) {
        val key = SessionKeyStore.getE2eKey(src) ?: run {
            Timber.w("[CRYPTO] Sin clave E2E para 0x${src.toHexNodeId()}, mensaje descartado")
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val plaintext = CryptoManager.decrypt(key, CryptoManager.fromBase64(b64Cipher))
                    .toString(Charsets.UTF_8)
                onMessageReceived(src, plaintext, encType = 2)
            } catch (e: Exception) {
                Timber.e(e, "[CRYPTO] Fallo al descifrar E2E de 0x${src.toHexNodeId()}")
            }
        }
    }

    /**
     * Llamado cuando llega +RCVGRP=<group_id>,<src>,<node_id>,<b64_cipher>.
     * Descifra con la clave de grupo y almacena el texto plano en Room bajo el groupId.
     */
    fun onGroupMessageReceived(groupId: Int, src: Int, b64Cipher: String) {
        val key = SessionKeyStore.getGroupKey(groupId) ?: run {
            Timber.w("[CRYPTO] Sin clave para grupo 0x${groupId.toHexNodeId()}, mensaje descartado")
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val plaintext = CryptoManager.decrypt(key, CryptoManager.fromBase64(b64Cipher))
                    .toString(Charsets.UTF_8)
                val message = Message(
                    contactAddress = groupId,
                    content = plaintext,
                    timestamp = System.currentTimeMillis(),
                    isOutgoing = false,
                    encType = 1
                )
                viewModelScope.launch(Dispatchers.IO) {
                    messageDao.insert(message)
                    _incomingMessage.postValue(message)
                }
            } catch (e: Exception) {
                Timber.e(e, "[CRYPTO] Fallo al descifrar mensaje de grupo 0x${groupId.toHexNodeId()}")
            }
        }
    }

    fun onGroupCreated(groupId: Int, groupName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            contactDao.insertOrUpdate(Contact(address = groupId, name = groupName))
            messageDao.insert(Message(
                contactAddress = groupId,
                content = "Grupo creado",
                timestamp = System.currentTimeMillis(),
                isOutgoing = true
            ))
        }
    }

    fun onGroupJoined(groupId: Int, groupName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (contactDao.getByAddress(groupId) == null) {
                contactDao.insertOrUpdate(Contact(address = groupId, name = groupName))
                messageDao.insert(Message(
                    contactAddress = groupId,
                    content = "Te has unido al grupo",
                    timestamp = System.currentTimeMillis(),
                    isOutgoing = false
                ))
            }
        }
    }

    fun hasGroupKey(groupId: Int): Boolean = SessionKeyStore.hasGroupKey(groupId)

    /**
     * Envío unificado: grupo > E2E > plano.
     * Reemplaza las llamadas directas a sendAtCommand("AT+SEND=...") en la UI.
     */
    fun sendMessage(dst: Int, text: String) {
        if (dst == PUBLIC_CHANNEL_ADDRESS) {
            sendAtCommand("AT+SEND=FFFFFFFF,$text\r\n")
            onMessageSent(PUBLIC_CHANNEL_ADDRESS, text, encType = 0)
            return
        }
        val groupKey = SessionKeyStore.getGroupKey(dst)
        if (groupKey != null) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    val cipher = CryptoManager.encrypt(groupKey, text.toByteArray(Charsets.UTF_8))
                    sendAtCommand("AT+SENDGRP=${dst.toHexNodeId()},${CryptoManager.toBase64(cipher)}\r\n")
                    onMessageSent(dst, text, encType = 1)
                    Timber.d("[CRYPTO] Mensaje de grupo enviado a 0x${dst.toHexNodeId()}")
                } catch (e: Exception) {
                    Timber.e(e, "[CRYPTO] Fallo al cifrar mensaje de grupo")
                }
            }
            return
        }
        val e2eKey = SessionKeyStore.getE2eKey(dst)
        if (e2eKey != null) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    val cipher = CryptoManager.encrypt(e2eKey, text.toByteArray(Charsets.UTF_8))
                    sendAtCommand("AT+SENDE2E=${dst.toHexNodeId()},${CryptoManager.toBase64(cipher)}\r\n")
                    onMessageSent(dst, text, encType = 2)
                    Timber.d("[CRYPTO] Mensaje E2E enviado a 0x${dst.toHexNodeId()}")
                } catch (e: Exception) {
                    Timber.e(e, "[CRYPTO] Fallo al cifrar, enviando en plano")
                    sendAtCommand("AT+SEND=${dst.toHexNodeId()},$text\r\n")
                    onMessageSent(dst, text, encType = 0)
                }
            }
        } else {
            sendAtCommand("AT+SEND=${dst.toHexNodeId()},$text\r\n")
            onMessageSent(dst, text, encType = 0)
        }
    }

    private fun Int.toHexNodeId(): String = "%08x".format(this)

    // -------------------------------------------------------
    // Gestión de contactos
    // -------------------------------------------------------

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

    fun getMessagesForContact(address: Int): LiveData<List<Message>> {
        return messageDao.getMessagesForContact(address)
    }
}