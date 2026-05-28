package com.punchthrough.blestarterappandroid.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.punchthrough.blestarterappandroid.BleViewModel
import com.punchthrough.blestarterappandroid.ChatActivity
import com.punchthrough.blestarterappandroid.R
import com.punchthrough.blestarterappandroid.ble.ConnectionManager
import com.punchthrough.blestarterappandroid.data.model.Contact
import com.punchthrough.blestarterappandroid.data.model.Message
import com.punchthrough.blestarterappandroid.databinding.FragmentChatsBinding
import java.util.UUID

private val MESSAGE_WRITE_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")

class ChatsFragment : Fragment() {

    private var _binding: FragmentChatsBinding? = null
    private val binding get() = _binding!!
    private val bleViewModel: BleViewModel by activityViewModels()

    private val adapter = ChatsAdapter { chatItem ->
        val contactName = chatItem.contact?.name?.takeIf { it.isNotBlank() }
            ?: "Nodo ${chatItem.lastMessage.contactAddress.toString(16).uppercase()}"
        startActivity(
            Intent(requireContext(), ChatActivity::class.java).apply {
                putExtra(ChatActivity.EXTRA_CONTACT_ADDRESS, chatItem.lastMessage.contactAddress)
                putExtra(ChatActivity.EXTRA_CONTACT_NAME, contactName)
            }
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.chatsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.chatsRecyclerView.adapter = adapter

        bleViewModel.lastMessages.observe(viewLifecycleOwner) { messages ->
            buildChatList(messages, bleViewModel.allContacts.value ?: emptyList())
        }
        bleViewModel.allContacts.observe(viewLifecycleOwner) { contacts ->
            bleViewModel.lastMessages.value?.let { buildChatList(it, contacts) }
        }

        binding.newMessageFab.setOnClickListener { showNewMessageDialog() }
    }

    private fun buildChatList(messages: List<Message>, contacts: List<Contact>) {
        val contactMap = contacts.associateBy { it.address }
        val chatItems = messages.map { ChatItem(contact = contactMap[it.contactAddress], lastMessage = it) }
        adapter.submitList(chatItems)
        binding.emptyText.visibility = if (chatItems.isEmpty()) View.VISIBLE else View.GONE
        binding.chatsRecyclerView.visibility = if (chatItems.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showNewMessageDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_new_message, null)
        val addressLayout = dialogView.findViewById<TextInputLayout>(R.id.addressInputLayout)
        val addressInput = dialogView.findViewById<TextInputEditText>(R.id.addressEditText)
        val messageInput = dialogView.findViewById<TextInputEditText>(R.id.messageEditText)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Nuevo mensaje")
            .setView(dialogView)
            .setPositiveButton("Enviar", null)
            .setNegativeButton("Cancelar", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val hexText = addressInput.text.toString().trim()
                            .removePrefix("0x").removePrefix("0X")
                        val address = hexText.toIntOrNull(16)
                        val text = messageInput.text.toString().trim()
                        when {
                            address == null || hexText.isEmpty() -> {
                                addressLayout.error = "Dirección hex inválida (ej: 1A)"
                            }
                            text.isEmpty() -> {
                                addressLayout.error = null
                                Toast.makeText(requireContext(), "Escribe un mensaje", Toast.LENGTH_SHORT).show()
                            }
                            else -> {
                                addressLayout.error = null
                                dialog.dismiss()
                                sendNewMessage(address, text)
                            }
                        }
                    }
                }
            }
            .show()
    }

    private fun sendNewMessage(address: Int, text: String) {
        val device = ConnectionManager.connectedDevices().firstOrNull()
        if (device != null) {
            val characteristic = ConnectionManager.servicesOnDevice(device)
                ?.flatMap { it.characteristics ?: emptyList() }
                ?.find { it.uuid == MESSAGE_WRITE_UUID }
            if (characteristic != null) {
                ConnectionManager.writeCharacteristic(
                    device, characteristic,
                    "AT+SEND=$address,$text\r\n".toByteArray(Charsets.UTF_8)
                )
            }
        }

        bleViewModel.onMessageSent(address, text)

        val contactName = bleViewModel.allContacts.value
            ?.find { it.address == address }?.name?.takeIf { it.isNotBlank() }
            ?: "Nodo ${address.toString(16).uppercase()}"

        startActivity(
            Intent(requireContext(), ChatActivity::class.java).apply {
                putExtra(ChatActivity.EXTRA_CONTACT_ADDRESS, address)
                putExtra(ChatActivity.EXTRA_CONTACT_NAME, contactName)
            }
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
