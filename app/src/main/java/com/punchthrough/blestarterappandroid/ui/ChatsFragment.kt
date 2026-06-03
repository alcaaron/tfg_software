package com.punchthrough.blestarterappandroid.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.punchthrough.blestarterappandroid.BleViewModel
import com.punchthrough.blestarterappandroid.ChatActivity
import com.punchthrough.blestarterappandroid.R
import com.punchthrough.blestarterappandroid.data.model.Contact
import com.punchthrough.blestarterappandroid.data.model.Message
import com.punchthrough.blestarterappandroid.databinding.FragmentChatsBinding
import com.punchthrough.blestarterappandroid.security.SessionKeyStore
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec

class ChatsFragment : Fragment() {

    private var _binding: FragmentChatsBinding? = null
    private val binding get() = _binding!!
    private val bleViewModel: BleViewModel by activityViewModels()

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { handleGroupQrScanned(it) }
    }

    private val adapter = ChatsAdapter { chatItem ->
        val contactName = if (chatItem.isPinned) "Canal Público" else {
            chatItem.contact?.name?.takeIf { it.isNotBlank() }
                ?: "Nodo ${"%08x".format(chatItem.lastMessage.contactAddress).uppercase()}"
        }
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
        bleViewModel.keyEstablished.observe(viewLifecycleOwner) {
            bleViewModel.lastMessages.value?.let { msgs ->
                buildChatList(msgs, bleViewModel.allContacts.value ?: emptyList())
            }
        }

        binding.newMessageFab.setOnClickListener { showChatOptions() }
    }

    private fun buildChatList(messages: List<Message>, contacts: List<Contact>) {
        val contactMap = contacts.associateBy { it.address }

        val publicLastMsg = messages.firstOrNull { it.contactAddress == BleViewModel.PUBLIC_CHANNEL_ADDRESS }
            ?: Message(
                contactAddress = BleViewModel.PUBLIC_CHANNEL_ADDRESS,
                content = "",
                timestamp = 0L,
                isOutgoing = false
            )
        val publicItem = ChatItem(contact = null, lastMessage = publicLastMsg, isPinned = true)

        val regularItems = messages
            .filter { it.contactAddress != BleViewModel.PUBLIC_CHANNEL_ADDRESS }
            .map { ChatItem(contact = contactMap[it.contactAddress], lastMessage = it) }

        val chatItems = listOf(publicItem) + regularItems
        adapter.submitList(chatItems)
        binding.emptyText.visibility = View.GONE
        binding.chatsRecyclerView.visibility = View.VISIBLE
    }

    private fun showChatOptions() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_new_chat_options, null)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()
        dialogView.findViewById<ImageButton>(R.id.optionSendNode).setOnClickListener {
            dialog.dismiss()
            showNewMessageDialog()
        }
        dialogView.findViewById<ImageButton>(R.id.optionJoinGroup).setOnClickListener {
            dialog.dismiss()
            scanGroupQr()
        }
        dialogView.findViewById<ImageButton>(R.id.optionCreateGroup).setOnClickListener {
            dialog.dismiss()
            createGroup()
        }
        dialog.show()
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
                                addressLayout.error = "Dirección hex inválida (ej: AABBCCDD)"
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

    private fun createGroup() {
        val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val groupId = SecureRandom().nextInt()
        SessionKeyStore.storeGroupKey(groupId, SecretKeySpec(keyBytes, "AES"))
        val groupName = "Grupo ${"%08x".format(groupId).uppercase()}"
        bleViewModel.onGroupCreated(groupId, groupName)
        val qrContent = "A3MESH:GROUP:${"%08x".format(groupId)}:${Base64.encodeToString(keyBytes, Base64.NO_WRAP)}"
        val qrBitmap = generateQrBitmap(qrContent)
        showGroupQrDialog(groupId, qrBitmap)
    }

    private fun generateQrBitmap(content: String, size: Int = 600): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
    }

    private fun showGroupQrDialog(groupId: Int, bitmap: Bitmap) {
        val padding = (16 * resources.displayMetrics.density).toInt()
        val imageView = ImageView(requireContext()).apply {
            setImageBitmap(bitmap)
            setPadding(padding, padding, padding, 0)
            adjustViewBounds = true
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Grupo ${"%08x".format(groupId).uppercase()}")
            .setMessage("Comparte este QR con los miembros del grupo.")
            .setView(imageView)
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun scanGroupQr() {
        scanLauncher.launch(
            ScanOptions().apply {
                setPrompt("Escanea el QR del grupo")
                setBeepEnabled(false)
                setOrientationLocked(true)
            }
        )
    }

    private fun handleGroupQrScanned(content: String) {
        if (!content.startsWith("A3MESH:GROUP:")) {
            Toast.makeText(requireContext(), "QR no reconocido", Toast.LENGTH_SHORT).show()
            return
        }
        val body = content.removePrefix("A3MESH:GROUP:")
        val colonIdx = body.indexOf(':')
        if (colonIdx < 0) return
        val groupId = body.substring(0, colonIdx).toLongOrNull(16)?.toInt() ?: return
        val keyBytes = try {
            Base64.decode(body.substring(colonIdx + 1), Base64.NO_WRAP)
        } catch (e: Exception) { return }
        if (keyBytes.size != 16) return
        SessionKeyStore.storeGroupKey(groupId, SecretKeySpec(keyBytes, "AES"))
        val groupName = "Grupo ${"%08x".format(groupId).uppercase()}"
        bleViewModel.onGroupJoined(groupId, groupName)
        Toast.makeText(requireContext(), "$groupName añadido", Toast.LENGTH_SHORT).show()
    }

    private fun sendNewMessage(address: Int, text: String) {
        bleViewModel.sendMessage(address, text)

        val contactName = bleViewModel.allContacts.value
            ?.find { it.address == address }?.name?.takeIf { it.isNotBlank() }
            ?: "Nodo ${"%08x".format(address).uppercase()}"

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
