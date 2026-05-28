package com.punchthrough.blestarterappandroid.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.punchthrough.blestarterappandroid.BleViewModel
import com.punchthrough.blestarterappandroid.databinding.FragmentContactsBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val NEIGHBORS_REFRESH_INTERVAL_MS = 30_000L

class ContactsFragment : Fragment() {

    private var _binding: FragmentContactsBinding? = null
    private val binding get() = _binding!!
    private val bleViewModel: BleViewModel by activityViewModels()

    private val contactsAdapter = ContactsAdapter { contact ->
        AlertDialog.Builder(requireContext())
            .setTitle(contact.name.ifBlank { "Nodo ${contact.address.toString(16).uppercase()}" })
            .setItems(arrayOf("Editar nombre", "Eliminar")) { _, which ->
                when (which) {
                    0 -> showEditDialog(contact.address, contact.name)
                    1 -> bleViewModel.deleteContact(contact)
                }
            }
            .show()
    }

    private val neighborsAdapter = NeighborsAdapter()

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
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        val nameField = EditText(requireContext()).apply { hint = "Nombre (p.ej. Base Camp)" }
        val addressField = EditText(requireContext()).apply {
            hint = "Dirección LoRa hex (p.ej. 1A)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        layout.addView(nameField)
        layout.addView(addressField)

        AlertDialog.Builder(requireContext())
            .setTitle("Añadir contacto")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->
                val name = nameField.text.toString().trim()
                val hexText = addressField.text.toString().trim().removePrefix("0x").removePrefix("0X")
                val address = hexText.toIntOrNull(16)
                if (address != null) bleViewModel.saveContact(address, name)
            }
            .setNegativeButton("Cancelar", null)
            .show()
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
