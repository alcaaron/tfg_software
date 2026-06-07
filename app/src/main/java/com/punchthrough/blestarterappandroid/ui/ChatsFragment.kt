package com.punchthrough.blestarterappandroid.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.punchthrough.blestarterappandroid.BleViewModel
import com.punchthrough.blestarterappandroid.ChatActivity
import com.punchthrough.blestarterappandroid.R
import com.punchthrough.blestarterappandroid.data.model.Contact
import com.punchthrough.blestarterappandroid.data.model.Message
import com.punchthrough.blestarterappandroid.databinding.FragmentChatsBinding

class ChatsFragment : Fragment() {

    private var _binding: FragmentChatsBinding? = null
    private val binding get() = _binding!!
    private val bleViewModel: BleViewModel by activityViewModels()

    private var isFabMenuOpen = false
    private val ANIM_DURATION = 180L

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { handleGroupCode(it) }
    }

    private val adapter = ChatsAdapter { chatItem ->
        closeFabMenu()
        val address = chatItem.lastMessage.contactAddress
        val contactName = when {
            chatItem.isPinned -> "Canal Público"
            chatItem.isGroup -> chatItem.groupName?.takeIf { it.isNotBlank() } ?: "Grupo"
            else -> chatItem.contact?.name?.takeIf { it.isNotBlank() }
                ?: "Nodo ${"%08x".format(address).uppercase()}"
        }
        startActivity(
            Intent(requireContext(), ChatActivity::class.java).apply {
                putExtra(ChatActivity.EXTRA_CONTACT_ADDRESS, address)
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

        // Start all speed-dial rows scaled to zero
        listOf(binding.speedDialCreateRow, binding.speedDialJoinRow, binding.speedDialMessageRow)
            .forEach { row ->
                row.scaleX = 0f
                row.scaleY = 0f
                row.alpha = 0f
            }

        binding.chatsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.chatsRecyclerView.adapter = adapter
        setupSwipeToDelete()

        bleViewModel.lastMessages.observe(viewLifecycleOwner) { messages ->
            buildChatList(
                messages,
                bleViewModel.allContacts.value ?: emptyList(),
                bleViewModel.unreadCounts.value ?: emptyMap()
            )
        }
        bleViewModel.allContacts.observe(viewLifecycleOwner) { contacts ->
            bleViewModel.lastMessages.value?.let {
                buildChatList(it, contacts, bleViewModel.unreadCounts.value ?: emptyMap())
            }
        }
        bleViewModel.unreadCounts.observe(viewLifecycleOwner) { counts ->
            bleViewModel.lastMessages.value?.let {
                buildChatList(it, bleViewModel.allContacts.value ?: emptyList(), counts)
            }
        }

        binding.newMessageFab.setOnClickListener {
            if (isFabMenuOpen) closeFabMenu() else openFabMenu()
        }
        binding.fabOverlay.setOnClickListener { closeFabMenu() }
        binding.miniFabNewMessage.setOnClickListener { closeFabMenu(); showDirectMessageDialog() }
        binding.miniFabCreateGroup.setOnClickListener { closeFabMenu(); showCreateGroupDialog() }
        binding.miniFabJoinGroup.setOnClickListener { closeFabMenu(); showJoinGroupOptions() }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (isFabMenuOpen) {
                        closeFabMenu()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            })
    }

    // ── Swipe to delete ───────────────────────────────────────────────────────

    private fun setupSwipeToDelete() {
        val deleteBackground = ColorDrawable(Color.parseColor("#F44336"))
        val swipeCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {

            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                val pos = viewHolder.bindingAdapterPosition
                if (pos < 0 || adapter.currentList[pos].isPinned) return 0
                return super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position < 0) return
                val item = adapter.currentList[position]
                val address = item.lastMessage.contactAddress
                val label = when {
                    item.isGroup -> item.groupName?.takeIf { it.isNotBlank() } ?: "Grupo"
                    else -> item.contact?.name?.takeIf { it.isNotBlank() }
                        ?: "Nodo ${"%08x".format(address).uppercase()}"
                }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Eliminar chat")
                    .setMessage("¿Eliminar la conversación con $label?")
                    .setPositiveButton("Eliminar") { _, _ ->
                        bleViewModel.deleteChat(address, item.isGroup)
                    }
                    .setNegativeButton("Cancelar") { _, _ ->
                        adapter.notifyItemChanged(position)
                    }
                    .setOnCancelListener {
                        adapter.notifyItemChanged(position)
                    }
                    .show()
            }

            override fun onChildDraw(
                c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
            ) {
                val item = viewHolder.itemView
                deleteBackground.setBounds(item.right + dX.toInt(), item.top, item.right, item.bottom)
                deleteBackground.draw(c)
                val icon = ContextCompat.getDrawable(recyclerView.context, R.drawable.ic_delete)
                if (icon != null) {
                    val margin = (item.height - icon.intrinsicHeight) / 2
                    val iconTop = item.top + margin
                    val iconRight = item.right - margin
                    val iconLeft = iconRight - icon.intrinsicWidth
                    if (iconLeft > item.right + dX.toInt()) {
                        icon.setBounds(iconLeft, iconTop, iconRight, iconTop + icon.intrinsicHeight)
                        icon.draw(c)
                    }
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.chatsRecyclerView)
    }

    // ── Speed dial animation ──────────────────────────────────────────────────

    private fun openFabMenu() {
        isFabMenuOpen = true
        binding.newMessageFab.setImageResource(R.drawable.ic_close)

        binding.fabOverlay.visibility = View.VISIBLE
        binding.fabOverlay.animate().alpha(1f).setDuration(ANIM_DURATION).start()

        binding.speedDialContainer.visibility = View.VISIBLE
        // Bottom row first (smallest delay), then upward
        animateRowIn(binding.speedDialMessageRow, 0)
        animateRowIn(binding.speedDialJoinRow, 60)
        animateRowIn(binding.speedDialCreateRow, 120)
    }

    private fun closeFabMenu() {
        if (!isFabMenuOpen) return
        isFabMenuOpen = false
        binding.newMessageFab.setImageResource(R.drawable.ic_new_chat)

        binding.fabOverlay.animate().alpha(0f).setDuration(ANIM_DURATION)
            .withEndAction { binding.fabOverlay.visibility = View.GONE }.start()

        // Top row out first, then downward
        animateRowOut(binding.speedDialCreateRow, 0)
        animateRowOut(binding.speedDialJoinRow, 40)
        animateRowOut(binding.speedDialMessageRow, 80) {
            binding.speedDialContainer.visibility = View.INVISIBLE
        }
    }

    private fun animateRowIn(row: View, delayMs: Long) {
        row.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setStartDelay(delayMs)
            .setDuration(ANIM_DURATION)
            .setInterpolator(OvershootInterpolator(1.4f))
            .start()
    }

    private fun animateRowOut(row: View, delayMs: Long, onEnd: (() -> Unit)? = null) {
        row.animate()
            .scaleX(0f).scaleY(0f).alpha(0f)
            .setStartDelay(delayMs)
            .setDuration(130)
            .withEndAction { onEnd?.invoke() }
            .start()
    }

    // ── Direct message dialog (existing behaviour) ────────────────────────────

    private fun showDirectMessageDialog() {
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
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val hexText = addressInput.text.toString().trim()
                            .removePrefix("0x").removePrefix("0X")
                        val address = hexText.toIntOrNull(16)
                        val text = messageInput.text.toString().trim()
                        when {
                            address == null || hexText.isEmpty() ->
                                addressLayout.error = "Dirección hex inválida (ej: AABBCCDD)"
                            text.isEmpty() -> {
                                addressLayout.error = null
                                Toast.makeText(requireContext(), "Escribe un mensaje", Toast.LENGTH_SHORT).show()
                            }
                            else -> {
                                addressLayout.error = null
                                dialog.dismiss()
                                bleViewModel.sendMessage(address, text)
                                val name = bleViewModel.allContacts.value
                                    ?.find { it.address == address }?.name?.takeIf { it.isNotBlank() }
                                    ?: "Nodo ${"%08x".format(address).uppercase()}"
                                openChat(address, name)
                            }
                        }
                    }
                }
            }
            .show()
    }

    // ── Create group ──────────────────────────────────────────────────────────

    private fun showCreateGroupDialog() {
        val editText = EditText(requireContext()).apply { hint = "Nombre del grupo" }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Crear grupo")
            .setView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 16, 48, 0)
                addView(editText)
            })
            .setPositiveButton("Crear") { _, _ ->
                val name = editText.text.toString().trim().ifBlank { "Grupo" }
                val groupId = bleViewModel.createGroup(name)
                showGroupQrDialog(groupId, name)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showGroupQrDialog(groupId: Int, name: String) {
        val code = bleViewModel.groupCode(groupId)
        val qrBitmap = generateQrBitmap(code)

        val dialogView = LayoutInflater.from(requireContext()).inflate(
            android.R.layout.simple_list_item_2, null
        )

        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }
        if (qrBitmap != null) {
            layout.addView(ImageView(requireContext()).apply {
                setImageBitmap(qrBitmap)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 480
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
            })
        }
        layout.addView(TextView(requireContext()).apply {
            text = "Comparte este código con los otros nodos:"
            setPadding(0, 16, 0, 8)
            textSize = 13f
        })
        layout.addView(TextView(requireContext()).apply {
            text = code
            textSize = 11f
            setTextIsSelectable(true)
        })

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Grupo creado: $name")
            .setView(layout)
            .setPositiveButton("Ir al chat") { _, _ ->
                openChat(groupId, name)
            }
            .setNegativeButton("Cerrar") { _, _ ->
                openChat(groupId, name)
            }
            .show()
    }

    // ── Join group ────────────────────────────────────────────────────────────

    private fun showJoinGroupOptions() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Unirse a grupo")
            .setItems(arrayOf("Escanear código QR", "Introducir código manualmente")) { _, which ->
                when (which) {
                    0 -> launchQrScanner()
                    1 -> showJoinByCodeDialog()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun launchQrScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Escanea el código QR del grupo")
            setBeepEnabled(false)
            setBarcodeImageEnabled(false)
        }
        scanLauncher.launch(options)
    }

    private fun showJoinByCodeDialog() {
        val editText = EditText(requireContext()).apply {
            hint = "a3g:GGGGGGGG:KKKK...KKK:nombre"
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Introducir código de grupo")
            .setView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 16, 48, 0)
                addView(editText)
            })
            .setPositiveButton("Unirse") { _, _ ->
                handleGroupCode(editText.text.toString().trim())
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun handleGroupCode(code: String) {
        val parsed = bleViewModel.parseGroupCode(code)
        if (parsed == null) {
            Toast.makeText(requireContext(), "Código de grupo inválido", Toast.LENGTH_SHORT).show()
            return
        }
        val (groupId, key, name) = parsed
        bleViewModel.joinGroup(groupId, name, key)
        Toast.makeText(requireContext(), "Unido a \"$name\"", Toast.LENGTH_SHORT).show()
        openChat(groupId, name)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun openChat(address: Int, name: String) {
        startActivity(
            Intent(requireContext(), ChatActivity::class.java).apply {
                putExtra(ChatActivity.EXTRA_CONTACT_ADDRESS, address)
                putExtra(ChatActivity.EXTRA_CONTACT_NAME, name)
            }
        )
    }

    private fun generateQrBitmap(content: String, size: Int = 512): Bitmap? {
        return try {
            val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
            BarcodeEncoder().createBitmap(matrix)
        } catch (e: Exception) {
            null
        }
    }

    private fun buildChatList(messages: List<Message>, contacts: List<Contact>, unreadCounts: Map<Int, Int>) {
        val contactMap = contacts.associateBy { it.address }
        val groupIdSet = bleViewModel.keyStore.getAllGroupIds().toSet()

        val publicLastMsg = messages.firstOrNull { it.contactAddress == BleViewModel.PUBLIC_CHANNEL_ADDRESS }
            ?: Message(
                contactAddress = BleViewModel.PUBLIC_CHANNEL_ADDRESS,
                content = "",
                timestamp = 0L,
                isOutgoing = false
            )
        val publicItem = ChatItem(
            contact = null,
            lastMessage = publicLastMsg,
            isPinned = true,
            unreadCount = unreadCounts[BleViewModel.PUBLIC_CHANNEL_ADDRESS] ?: 0
        )

        val regularItems = messages
            .filter { it.contactAddress != BleViewModel.PUBLIC_CHANNEL_ADDRESS }
            .map { msg ->
                val address = msg.contactAddress
                val isGroup = address in groupIdSet
                ChatItem(
                    contact = if (isGroup) null else contactMap[address],
                    lastMessage = msg,
                    isGroup = isGroup,
                    groupName = if (isGroup) bleViewModel.keyStore.getGroupName(address) else null,
                    unreadCount = unreadCounts[address] ?: 0
                )
            }

        adapter.submitList(listOf(publicItem) + regularItems)
        binding.emptyText.visibility = View.GONE
        binding.chatsRecyclerView.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
