package com.punchthrough.blestarterappandroid.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.punchthrough.blestarterappandroid.BleViewModel
import com.punchthrough.blestarterappandroid.ChatActivity
import com.punchthrough.blestarterappandroid.R
import com.punchthrough.blestarterappandroid.databinding.FragmentContactsBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val NEIGHBORS_REFRESH_INTERVAL_MS = 30_000L

class ContactsFragment : Fragment() {

    private var _binding: FragmentContactsBinding? = null
    private val binding get() = _binding!!
    private val bleViewModel: BleViewModel by activityViewModels()

    private val contactsAdapter = ContactsAdapter(
        onClick = { contact ->
            startActivity(
                Intent(requireContext(), ChatActivity::class.java).apply {
                    putExtra(ChatActivity.EXTRA_CONTACT_ADDRESS, contact.address)
                    putExtra(ChatActivity.EXTRA_CONTACT_NAME, contact.name.ifBlank { "Nodo ${"%08x".format(contact.address).uppercase()}" })
                }
            )
        },
        onLongClick = { contact ->
            AlertDialog.Builder(requireContext())
                .setTitle(contact.name.ifBlank { "Nodo ${"%08x".format(contact.address).uppercase()}" })
                .setItems(arrayOf("Editar nombre", "Eliminar")) { _, which ->
                    when (which) {
                        0 -> showEditDialog(contact.address, contact.name)
                        1 -> bleViewModel.deleteContact(contact)
                    }
                }
                .show()
        }
    )

    private val neighborsAdapter = NeighborsAdapter().also { adapter ->
        adapter.onClick = { node ->
            node.nodeId.toLongOrNull(16)?.toInt()?.let { address ->
                startActivity(
                    Intent(requireContext(), ChatActivity::class.java).apply {
                        putExtra(ChatActivity.EXTRA_CONTACT_ADDRESS, address)
                        putExtra(ChatActivity.EXTRA_CONTACT_NAME, "Nodo ${node.nodeId.uppercase()}")
                    }
                )
            }
        }
    }

    private var neighborsExpanded = true
    private var contactsExpanded = true

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentContactsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.neighborsRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = neighborsAdapter
            isNestedScrollingEnabled = false
        }
        binding.contactsRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = contactsAdapter
            isNestedScrollingEnabled = false
        }
        setupSwipeToDelete()

        // Expand/collapse Vecinos
        binding.neighborsHeader.setOnClickListener {
            neighborsExpanded = !neighborsExpanded
            binding.neighborsContent.visibility = if (neighborsExpanded) View.VISIBLE else View.GONE
            binding.neighborsExpandIndicator.text = if (neighborsExpanded) "▼" else "▶"
        }

        // Expand/collapse Contactos
        binding.contactsHeader.setOnClickListener {
            contactsExpanded = !contactsExpanded
            binding.contactsContent.visibility = if (contactsExpanded) View.VISIBLE else View.GONE
            binding.contactsExpandIndicator.text = if (contactsExpanded) "▼" else "▶"
        }

        // Manual refresh
        binding.refreshNeighborsButton.setOnClickListener {
            bleViewModel.sendAtCommand("AT+NEIGHBORS?\r\n")
        }

        // Add contact FAB
        binding.fabAddContact.setOnClickListener { showAddDialog() }

        // Observe contacts
        bleViewModel.allContacts.observe(viewLifecycleOwner) { contacts ->
            contactsAdapter.submitList(contacts)
            binding.emptyText.visibility = if (contacts.isEmpty()) View.VISIBLE else View.GONE
            binding.contactsRecyclerView.visibility = if (contacts.isEmpty()) View.GONE else View.VISIBLE
        }

        // Observe neighbors
        bleViewModel.neighbors.observe(viewLifecycleOwner) { nodes ->
            neighborsAdapter.submitList(nodes)
            binding.neighborsEmptyText.visibility = if (nodes.isEmpty()) View.VISIBLE else View.GONE
            binding.neighborsRecyclerView.visibility = if (nodes.isEmpty()) View.GONE else View.VISIBLE
        }

        // Auto-refresh neighbors when connected
        bleViewModel.connectedDevice.observe(viewLifecycleOwner) { device ->
            if (device != null) bleViewModel.sendAtCommand("AT+NEIGHBORS?\r\n")
        }

        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(NEIGHBORS_REFRESH_INTERVAL_MS)
                if (bleViewModel.connectedDevice.value != null) {
                    bleViewModel.sendAtCommand("AT+NEIGHBORS?\r\n")
                }
            }
        }
    }

    private fun showAddDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_add_contact, null)
        val nameInput = dialogView.findViewById<TextInputEditText>(R.id.nameEditText)
        val addressLayout = dialogView.findViewById<TextInputLayout>(R.id.addressInputLayout)
        val addressInput = dialogView.findViewById<TextInputEditText>(R.id.addressEditText)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Añadir contacto")
            .setView(dialogView)
            .setPositiveButton("Guardar", null)
            .setNegativeButton("Cancelar", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val name = nameInput.text.toString().trim()
                        val hexText = addressInput.text.toString().trim()
                            .removePrefix("0x").removePrefix("0X")
                        val address = hexText.toLongOrNull(16)?.toInt()
                        if (hexText.length != 8 || address == null) {
                            addressLayout.error = "Debe tener exactamente 8 caracteres hex"
                        } else {
                            addressLayout.error = null
                            dialog.dismiss()
                            bleViewModel.saveContact(address, name)
                        }
                    }
                }
            }
            .show()
    }

    private fun setupSwipeToDelete() {
        val deleteBackground = ColorDrawable(Color.parseColor("#F44336"))
        val swipeCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {

            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position < 0) return
                val contact = contactsAdapter.currentList[position]
                val label = contact.name.ifBlank { "Nodo ${"%08x".format(contact.address).uppercase()}" }

                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Eliminar contacto")
                    .setMessage("¿Eliminar a $label?")
                    .setPositiveButton("Eliminar") { _, _ ->
                        bleViewModel.deleteContact(contact)
                    }
                    .setNegativeButton("Cancelar") { _, _ ->
                        contactsAdapter.notifyItemChanged(position)
                    }
                    .setOnCancelListener {
                        contactsAdapter.notifyItemChanged(position)
                    }
                    .show()
            }

            override fun onChildDraw(
                c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
            ) {
                val item = viewHolder.itemView
                deleteBackground.setBounds(
                    item.right + dX.toInt(), item.top, item.right, item.bottom
                )
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
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.contactsRecyclerView)
    }

    private fun showEditDialog(address: Int, currentName: String) {
        val nameField = EditText(requireContext()).apply {
            setText(currentName)
            hint = "Nombre"
        }
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(nameField)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Editar contacto")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->
                bleViewModel.saveContact(address, nameField.text.toString().trim())
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
