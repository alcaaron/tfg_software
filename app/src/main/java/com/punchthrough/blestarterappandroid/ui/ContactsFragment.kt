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
import androidx.recyclerview.widget.LinearLayoutManager
import com.punchthrough.blestarterappandroid.BleViewModel
import com.punchthrough.blestarterappandroid.databinding.FragmentContactsBinding

class ContactsFragment : Fragment() {

    private var _binding: FragmentContactsBinding? = null
    private val binding get() = _binding!!
    private val bleViewModel: BleViewModel by activityViewModels()

    private val adapter = ContactsAdapter { contact ->
        // Pulsación larga: opciones sobre el contacto
        AlertDialog.Builder(requireContext())
            .setTitle(contact.name.ifBlank { "Nodo ${contact.address}" })
            .setItems(arrayOf("Editar nombre", "Eliminar")) { _, which ->
                when (which) {
                    0 -> showEditDialog(contact.address, contact.name)
                    1 -> bleViewModel.deleteContact(contact)
                }
            }
            .show()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentContactsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.contactsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.contactsRecyclerView.adapter = adapter

        bleViewModel.allContacts.observe(viewLifecycleOwner) { contacts ->
            adapter.submitList(contacts)
            binding.emptyText.visibility = if (contacts.isEmpty()) View.VISIBLE else View.GONE
            binding.contactsRecyclerView.visibility = if (contacts.isEmpty()) View.GONE else View.VISIBLE
        }

        binding.fabAddContact.setOnClickListener {
            showAddDialog()
        }
    }

    private fun showAddDialog() {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        val nameField = EditText(requireContext()).apply { hint = "Nombre (p.ej. Base Camp)" }
        val addressField = EditText(requireContext()).apply {
            hint = "Dirección LoRa (número, p.ej. 2)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        layout.addView(nameField)
        layout.addView(addressField)

        AlertDialog.Builder(requireContext())
            .setTitle("Añadir contacto")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->
                val name = nameField.text.toString().trim()
                val address = addressField.text.toString().trim().toIntOrNull()
                if (address != null) {
                    bleViewModel.saveContact(address, name)
                }
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
                val newName = nameField.text.toString().trim()
                bleViewModel.saveContact(address, newName)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}