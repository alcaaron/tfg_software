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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

data class NeighborNode(val nodeId: String, val rssi: String, val t: String)

private val BLE_WRITE_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")

class BleViewModel(app: Application) : AndroidViewModel(app) {

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

    // Llama a esto cuando llegue un mensaje +RCV del módulo LoRa
    // senderAddress = dirección del nodo que envía (el número del +RCV)
    // content = el texto del mensaje ya parseado
    fun onMessageReceived(senderAddress: Int, content: String) {
        val message = Message(
            contactAddress = senderAddress,
            content = content,
            timestamp = System.currentTimeMillis(),
            isOutgoing = false
        )
        // Guarda en Room y notifica a los observers
        viewModelScope.launch(Dispatchers.IO) {
            messageDao.insert(message)
            _incomingMessage.postValue(message)

            // Actualiza lastSeen del contacto si existe
            val contact = contactDao.getByAddress(senderAddress)
            if (contact != null) {
                contactDao.insertOrUpdate(contact.copy(lastSeen = System.currentTimeMillis()))
            }
        }
    }

    // Llama a esto cuando el usuario envíe un mensaje
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