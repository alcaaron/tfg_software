package com.punchthrough.blestarterappandroid

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.punchthrough.blestarterappandroid.databinding.ActivityChatBinding
import com.punchthrough.blestarterappandroid.ble.ConnectionEventListener
import com.punchthrough.blestarterappandroid.ble.ConnectionManager
import com.punchthrough.blestarterappandroid.ui.MessagesAdapter

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    // by viewModels() crea una instancia propia, pero sendMessage() funciona igualmente
    // porque ConnectionManager y SessionKeyStore son singletons de proceso.
    private val bleViewModel: BleViewModel by viewModels()
    private val messagesAdapter = MessagesAdapter()

    private val connectionEventListener by lazy {
        ConnectionEventListener().apply {
            onDisconnect = {
                runOnUiThread {
                    if (!isFinishing) {
                        MaterialAlertDialogBuilder(this@ChatActivity)
                            .setTitle("Dispositivo desconectado")
                            .setMessage("La conexión con el dispositivo BLE se ha perdido.")
                            .setPositiveButton("Volver") { _, _ -> finish() }
                            .setCancelable(false)
                            .show()
                    }
                }
            }
        }
    }

    private var contactAddress: Int = -1
    private var contactName: String = ""

    companion object {
        const val EXTRA_CONTACT_ADDRESS = "extra_contact_address"
        const val EXTRA_CONTACT_NAME = "extra_contact_name"
        const val PUBLIC_CHANNEL_ADDRESS = BleViewModel.PUBLIC_CHANNEL_ADDRESS
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ConnectionManager.registerListener(connectionEventListener)

        contactAddress = intent.getIntExtra(EXTRA_CONTACT_ADDRESS, -1)
        contactName = intent.getStringExtra(EXTRA_CONTACT_NAME) ?: "Nodo $contactAddress"

        binding.toolbar.title = contactName
        binding.toolbar.setNavigationOnClickListener { finish() }

        messagesAdapter.isPublicChannel = (contactAddress == PUBLIC_CHANNEL_ADDRESS)
        messagesAdapter.onSenderLongClick = { senderAddress -> showSenderOptionsDialog(senderAddress) }

        val layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.messagesRecyclerView.layoutManager = layoutManager
        binding.messagesRecyclerView.adapter = messagesAdapter

        bleViewModel.markConversationRead(contactAddress)

        bleViewModel.getMessagesForContact(contactAddress).observe(this) { messages ->
            messagesAdapter.submitList(messages) {
                if (messages.isNotEmpty()) {
                    binding.messagesRecyclerView.scrollToPosition(messages.size - 1)
                    bleViewModel.markConversationRead(contactAddress)
                }
            }
        }

        binding.sendButton.setOnClickListener { sendMessage() }
        binding.messageEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendMessage(); true } else false
        }

    }

    override fun onDestroy() {
        ConnectionManager.unregisterListener(connectionEventListener)
        super.onDestroy()
    }

    private fun sendMessage() {
        val text = binding.messageEditText.text.toString().trim()
        if (text.isEmpty() || contactAddress == -1) return
        bleViewModel.sendMessage(contactAddress, text)
        binding.messageEditText.text?.clear()
    }

    private fun showSenderOptionsDialog(senderAddress: Int) {
        val nodeLabel = "Nodo ${"0x${"%08X".format(senderAddress)}"}"
        MaterialAlertDialogBuilder(this)
            .setTitle(nodeLabel)
            .setItems(arrayOf("Enviar mensaje", "Añadir como contacto")) { _, which ->
                when (which) {
                    0 -> startActivity(
                        Intent(this, ChatActivity::class.java).apply {
                            putExtra(EXTRA_CONTACT_ADDRESS, senderAddress)
                            putExtra(EXTRA_CONTACT_NAME, nodeLabel)
                        }
                    )
                    1 -> bleViewModel.saveContact(senderAddress, "")
                }
            }
            .show()
    }

}
