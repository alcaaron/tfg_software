package com.punchthrough.blestarterappandroid

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.punchthrough.blestarterappandroid.databinding.ActivityChatBinding
import com.punchthrough.blestarterappandroid.security.SessionKeyStore
import com.punchthrough.blestarterappandroid.ui.MessagesAdapter

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    // by viewModels() crea una instancia propia, pero sendMessage() funciona igualmente
    // porque ConnectionManager y SessionKeyStore son singletons de proceso.
    private val bleViewModel: BleViewModel by viewModels()
    private val messagesAdapter = MessagesAdapter()

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

        contactAddress = intent.getIntExtra(EXTRA_CONTACT_ADDRESS, -1)
        contactName = intent.getStringExtra(EXTRA_CONTACT_NAME) ?: "Nodo $contactAddress"

        binding.toolbar.title = contactName
        binding.toolbar.setNavigationOnClickListener { finish() }

        val layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.messagesRecyclerView.layoutManager = layoutManager
        binding.messagesRecyclerView.adapter = messagesAdapter

        bleViewModel.getMessagesForContact(contactAddress).observe(this) { messages ->
            messagesAdapter.submitList(messages) {
                if (messages.isNotEmpty()) {
                    binding.messagesRecyclerView.scrollToPosition(messages.size - 1)
                }
            }
        }

        binding.sendButton.setOnClickListener { sendMessage() }
        binding.messageEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendMessage(); true } else false
        }

        updateEncryptionStatus()
    }

    override fun onResume() {
        super.onResume()
        // Re-check por si el handshake terminó mientras la activity estaba en background
        updateEncryptionStatus()
    }

    private fun sendMessage() {
        val text = binding.messageEditText.text.toString().trim()
        if (text.isEmpty() || contactAddress == -1) return
        bleViewModel.sendMessage(contactAddress, text)
        binding.messageEditText.text?.clear()
    }

    private fun updateEncryptionStatus() {
        binding.toolbar.subtitle = when {
            contactAddress == PUBLIC_CHANNEL_ADDRESS -> "Canal público · Sin cifrado"
            SessionKeyStore.hasE2eKey(contactAddress) -> "Cifrado extremo a extremo"
            else -> null
        }
    }
}
