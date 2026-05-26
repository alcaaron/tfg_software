package com.punchthrough.blestarterappandroid

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.punchthrough.blestarterappandroid.data.database.AppDatabase
import com.punchthrough.blestarterappandroid.data.model.Contact
import com.punchthrough.blestarterappandroid.data.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BleViewModel(app: Application) : AndroidViewModel(app) {

    // Base de datos
    private val db = AppDatabase.getInstance(app)
    private val messageDao = db.messageDao()
    private val contactDao = db.contactDao()

    // --- Estado de conexión BLE ---
    // Guarda el dispositivo BLE actualmente conectado (null = desconectado)
    private val _connectedDevice = MutableLiveData<BluetoothDevice?>(null)
    val connectedDevice: LiveData<BluetoothDevice?> = _connectedDevice

    // Texto de estado legible para mostrar en la UI ("Conectado", "Desconectado"...)
    private val _connectionStatus = MutableLiveData<String>("Desconectado")
    val connectionStatus: LiveData<String> = _connectionStatus

    // --- Mensajes entrantes ---
    // Cada vez que llega un mensaje nuevo por BLE, se emite aquí
    // Los fragments que estén escuchando reaccionarán automáticamente
    private val _incomingMessage = MutableLiveData<Message>()
    val incomingMessage: LiveData<Message> = _incomingMessage

    // --- Contactos y mensajes (desde Room) ---
    val allContacts: LiveData<List<Contact>> = contactDao.getAllContacts()
    val lastMessages: LiveData<List<Message>> = messageDao.getLastMessagePerContact()

    // -------------------------------------------------------
    // Funciones que llama la lógica BLE existente
    // -------------------------------------------------------

    // Llama a esto cuando el ConnectionManager confirme conexión
    fun onDeviceConnected(device: BluetoothDevice) {
        _connectedDevice.postValue(device)
        _connectionStatus.postValue("Conectado a ${device.name ?: device.address}")
    }

    // Llama a esto cuando el ConnectionManager detecte desconexión
    fun onDeviceDisconnected() {
        _connectedDevice.postValue(null)
        _connectionStatus.postValue("Desconectado")
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