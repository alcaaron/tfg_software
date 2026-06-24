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
import androidx.lifecycle.Observer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.punchthrough.blestarterappandroid.BleViewModel
import com.punchthrough.blestarterappandroid.R
import com.punchthrough.blestarterappandroid.databinding.DialogLoraConfigBinding
import com.punchthrough.blestarterappandroid.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val bleViewModel: BleViewModel by activityViewModels()

    private val prefs by lazy {
        requireContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    }

    private var isWaitingForLoraApply = false

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
        binding.advancedSettingsRow.setOnClickListener { showLoraConfigDialog() }
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

    private fun showLoraConfigDialog() {
        val dialogBinding = DialogLoraConfigBinding.inflate(LayoutInflater.from(requireContext()))

        // Default to LONG_FAST preset: SF11, BW250, CR 4/5
        dialogBinding.sfToggleGroup.check(R.id.btnSf11)
        dialogBinding.bwToggleGroup.check(R.id.btnBw250)
        dialogBinding.crToggleGroup.check(R.id.btnCr5)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .setCancelable(false)
            .create()

        // Query current values from the module
        bleViewModel.sendAtCommand("AT+LORA?\r\n")

        val atObserver = Observer<String> { response ->
            when {
                response.startsWith("+LORA=") -> {
                    val parts = response.removePrefix("+LORA=").split(",")
                    if (parts.size >= 3) {
                        val sf = parts[0].trim().toIntOrNull()
                        val bw = parts[1].trim().toIntOrNull()
                        val cr = parts[2].trim().toIntOrNull()
                        when (sf) {
                            7 -> dialogBinding.sfToggleGroup.check(R.id.btnSf7)
                            8 -> dialogBinding.sfToggleGroup.check(R.id.btnSf8)
                            9 -> dialogBinding.sfToggleGroup.check(R.id.btnSf9)
                            10 -> dialogBinding.sfToggleGroup.check(R.id.btnSf10)
                            11 -> dialogBinding.sfToggleGroup.check(R.id.btnSf11)
                            12 -> dialogBinding.sfToggleGroup.check(R.id.btnSf12)
                        }
                        when (bw) {
                            125 -> dialogBinding.bwToggleGroup.check(R.id.btnBw125)
                            250 -> dialogBinding.bwToggleGroup.check(R.id.btnBw250)
                            500 -> dialogBinding.bwToggleGroup.check(R.id.btnBw500)
                        }
                        when (cr) {
                            5 -> dialogBinding.crToggleGroup.check(R.id.btnCr5)
                            6 -> dialogBinding.crToggleGroup.check(R.id.btnCr6)
                            7 -> dialogBinding.crToggleGroup.check(R.id.btnCr7)
                            8 -> dialogBinding.crToggleGroup.check(R.id.btnCr8)
                        }
                    }
                }
                response.startsWith("+OK") && isWaitingForLoraApply -> {
                    isWaitingForLoraApply = false
                    dialog.dismiss()
                }
                response.startsWith("+ERR=") && isWaitingForLoraApply -> {
                    isWaitingForLoraApply = false
                    val errCode = response.removePrefix("+ERR=").trim()
                    MaterialAlertDialogBuilder(requireContext())
                        .setMessage("Something went wrong. Error $errCode.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }

        bleViewModel.atResponse.observeForever(atObserver)

        dialogBinding.btnCancel.setOnClickListener { dialog.dismiss() }

        dialogBinding.btnApply.setOnClickListener {
            val sf = sfFromButtonId(dialogBinding.sfToggleGroup.checkedButtonId)
            val bw = bwFromButtonId(dialogBinding.bwToggleGroup.checkedButtonId)
            val cr = crFromButtonId(dialogBinding.crToggleGroup.checkedButtonId)
            isWaitingForLoraApply = true
            bleViewModel.sendAtCommand("AT+LORA=$sf,$bw,$cr\r\n")
        }

        dialog.setOnDismissListener {
            bleViewModel.atResponse.removeObserver(atObserver)
            isWaitingForLoraApply = false
        }

        dialog.show()
    }

    private fun sfFromButtonId(id: Int) = when (id) {
        R.id.btnSf7 -> 7
        R.id.btnSf8 -> 8
        R.id.btnSf9 -> 9
        R.id.btnSf10 -> 10
        R.id.btnSf11 -> 11
        R.id.btnSf12 -> 12
        else -> 11
    }

    private fun bwFromButtonId(id: Int) = when (id) {
        R.id.btnBw125 -> 125
        R.id.btnBw250 -> 250
        R.id.btnBw500 -> 500
        else -> 250
    }

    private fun crFromButtonId(id: Int) = when (id) {
        R.id.btnCr5 -> 5
        R.id.btnCr6 -> 6
        R.id.btnCr7 -> 7
        R.id.btnCr8 -> 8
        else -> 5
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
