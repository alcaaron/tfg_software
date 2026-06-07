package com.punchthrough.blestarterappandroid.ui

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.punchthrough.blestarterappandroid.BleViewModel
import com.punchthrough.blestarterappandroid.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val bleViewModel: BleViewModel by activityViewModels()

    private val prefs by lazy {
        requireContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        updateProfileDisplay(prefs.getString("my_name", "") ?: "")
        binding.editNameButton.setOnClickListener { showEditNameDialog() }

        val savedMode = prefs.getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        binding.darkModeSwitch.isChecked = savedMode == AppCompatDelegate.MODE_NIGHT_YES
        binding.darkModeRow.setOnClickListener {
            binding.darkModeSwitch.isChecked = !binding.darkModeSwitch.isChecked
            applyNightMode(binding.darkModeSwitch.isChecked)
        }

        binding.languageRow.setOnClickListener { showLanguageDialog() }

        val demoEnabled = prefs.getBoolean("demo_mode", false)
        binding.demoModeSwitch.isChecked = demoEnabled
        binding.demoModeRow.setOnClickListener {
            binding.demoModeSwitch.isChecked = !binding.demoModeSwitch.isChecked
            val enabled = binding.demoModeSwitch.isChecked
            prefs.edit().putBoolean("demo_mode", enabled).apply()
            bleViewModel.sendAtCommand(if (enabled) "AT+DEMO=1\r\n" else "AT+DEMO=0\r\n")
        }
    }

    private fun updateProfileDisplay(name: String) {
        binding.profileName.text = name.ifBlank { "Sin nombre" }
        binding.profileAvatar.text = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    }

    private fun applyNightMode(dark: Boolean) {
        val mode = if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        prefs.edit().putInt("night_mode", mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun showEditNameDialog() {
        val editText = EditText(requireContext()).apply {
            setText(prefs.getString("my_name", ""))
            hint = "Tu nombre"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Editar nombre")
            .setView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 16, 48, 0)
                addView(editText)
            })
            .setPositiveButton("Guardar") { _, _ ->
                val newName = editText.text.toString().trim()
                prefs.edit().putString("my_name", newName).apply()
                updateProfileDisplay(newName)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showLanguageDialog() {
        val languages = arrayOf("Español", "English", "Français")
        val current = prefs.getInt("language_index", 0)
        AlertDialog.Builder(requireContext())
            .setTitle("Idioma")
            .setSingleChoiceItems(languages, current) { dialog, which ->
                prefs.edit().putInt("language_index", which).apply()
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
