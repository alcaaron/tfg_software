package com.punchthrough.blestarterappandroid

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.punchthrough.blestarterappandroid.databinding.ActivityChatBinding
import com.punchthrough.blestarterappandroid.ui.MessagesAdapter

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private val bleViewModel: BleViewModel by viewModels()
    private val messagesAdapter = MessagesAdapter()

    // Dirección y nombre del contacto, pasados desde ChatsFragment
    private var contactAddress: Int = -1
    private var contactName: String = ""

    companion object {
        const val EXTRA_CONTACT_ADDRESS = "extra_contact_address"
        const val EXTRA_CONTACT_NAME = "extra_contact_name"
        val MESSAGE_WRITE_UUID = java.util.UUID.fromString(
            "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Recuperar datos del contacto
        contactAddress = intent.getIntExtra(EXTRA_CONTACT_ADDRESS, -1)
        contactName = intent.getStringExtra(EXTRA_CONTACT_NAME)
            ?: "Nodo $contactAddress"

        // Configurar toolbar
        binding.toolbar.title = contactName
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Configurar RecyclerView
        val layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true  // los mensajes nuevos aparecen abajo
        }
        binding.messagesRecyclerView.layoutManager = layoutManager
        binding.messagesRecyclerView.adapter = messagesAdapter

        // Observar mensajes de esta conversación desde Room
        bleViewModel.getMessagesForContact(contactAddress)
            .observe(this) { messages ->
                messagesAdapter.submitList(messages) {
                    // Scroll automático al último mensaje
                    if (messages.isNotEmpty()) {
                        binding.messagesRecyclerView.scrollToPosition(messages.size - 1)
                    }
                }
            }

        // Botón enviar
        binding.sendButton.setOnClickListener { sendMessage() }

        // Enviar también al pulsar "Enter" en el teclado
        binding.messageEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }
    }

    private fun sendMessage() {
        val text = binding.messageEditText.text.toString().trim()
        if (text.isEmpty() || contactAddress == -1) return

        // Formatear comando AT y enviar por BLE
        val atCommand = "AT+SEND=$contactAddress,$text\r\n"
        sendViaBle(atCommand)

        // Guardar en Room como mensaje saliente
        bleViewModel.onMessageSent(contactAddress, text)

        binding.messageEditText.text?.clear()
    }

    private fun sendViaBle(command: String) {
        val device = bleViewModel.connectedDevice.value ?: return
        val services = com.punchthrough.blestarterappandroid.ble.ConnectionManager
            .servicesOnDevice(device) ?: return
        val characteristic = services
            .flatMap { it.characteristics ?: emptyList() }
            .find { it.uuid == MESSAGE_WRITE_UUID } ?: return

        com.punchthrough.blestarterappandroid.ble.ConnectionManager
            .writeCharacteristic(device, characteristic, command.toByteArray(Charsets.UTF_8))
    }
}