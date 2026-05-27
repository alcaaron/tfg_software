package com.punchthrough.blestarterappandroid.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.punchthrough.blestarterappandroid.BleViewModel
import com.punchthrough.blestarterappandroid.ChatActivity
import com.punchthrough.blestarterappandroid.databinding.FragmentChatsBinding

class ChatsFragment : Fragment() {

    private var _binding: FragmentChatsBinding? = null
    private val binding get() = _binding!!

    // activityViewModels() comparte el mismo ViewModel con MainActivity y el resto de fragments
    private val bleViewModel: BleViewModel by activityViewModels()

    private val adapter = ChatsAdapter { chatItem ->
        val intent = android.content.Intent(requireContext(), ChatActivity::class.java).apply {
            putExtra(ChatActivity.EXTRA_CONTACT_ADDRESS, chatItem.lastMessage.contactAddress)
            putExtra(
                ChatActivity.EXTRA_CONTACT_NAME,
                chatItem.contact?.name?.takeIf { it.isNotBlank() }
                    ?: "Nodo ${chatItem.lastMessage.contactAddress}"
            )
        }
        startActivity(intent)
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

        // Configurar RecyclerView
        binding.chatsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.chatsRecyclerView.adapter = adapter

        // Observar últimos mensajes y contactos simultáneamente
        bleViewModel.lastMessages.observe(viewLifecycleOwner) { messages ->
            bleViewModel.allContacts.value?.let { contacts ->
                buildChatList(messages, contacts)
            } ?: buildChatList(messages, emptyList())
        }

        bleViewModel.allContacts.observe(viewLifecycleOwner) { contacts ->
            bleViewModel.lastMessages.value?.let { messages ->
                buildChatList(messages, contacts)
            }
        }
    }

    private fun buildChatList(
        messages: List<com.punchthrough.blestarterappandroid.data.model.Message>,
        contacts: List<com.punchthrough.blestarterappandroid.data.model.Contact>
    ) {
        val contactMap = contacts.associateBy { it.address }
        val chatItems = messages.map { message ->
            ChatItem(
                contact = contactMap[message.contactAddress],
                lastMessage = message
            )
        }
        adapter.submitList(chatItems)

        // Mostrar estado vacío si no hay conversaciones
        binding.emptyText.visibility = if (chatItems.isEmpty()) View.VISIBLE else View.GONE
        binding.chatsRecyclerView.visibility = if (chatItems.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null  // evitar memory leaks
    }
}