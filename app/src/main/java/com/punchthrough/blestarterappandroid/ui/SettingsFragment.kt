package com.punchthrough.blestarterappandroid.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.punchthrough.blestarterappandroid.BleViewModel
import com.punchthrough.blestarterappandroid.ble.ConnectionManager
import com.punchthrough.blestarterappandroid.databinding.FragmentSettingsBinding
import java.util.UUID

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val bleViewModel: BleViewModel by activityViewModels()

    private val prefs by lazy {
        requireContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    }

    private val writeUUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Cargar valores guardados
        binding.myNameField.setText(prefs.getString("my_name", ""))
        binding.myAddressField.setText(prefs.getInt("my_address", 1).toString())
        binding.channelField.setText(prefs.getInt("channel", 0).toString())
        binding.powerField.setText(prefs.getInt("power", 22).toString())

        binding.saveButton.setOnClickListener {
            saveAndSend()
        }
    }

    private fun saveAndSend() {
        val name = binding.myNameField.text.toString().trim()
        val address = binding.myAddressField.text.toString().toIntOrNull() ?: 1
        val channel = binding.channelField.text.toString().toIntOrNull() ?: 0
        val power = binding.powerField.text.toString().toIntOrNull() ?: 22

        // Guardar en SharedPreferences
        prefs.edit()
            .putString("my_name", name)
            .putInt("my_address", address)
            .putInt("channel", channel)
            .putInt("power", power)
            .apply()

        // Enviar comandos AT al módulo si hay dispositivo conectado
        val device = bleViewModel.connectedDevice.value
        if (device != null) {
            val services = ConnectionManager.servicesOnDevice(device)
            val characteristic = services
                ?.flatMap { it.characteristics ?: emptyList() }
                ?.find { it.uuid == writeUUID }

            if (characteristic != null) {
                val commands = listOf(
                    "AT+ADDRESS=$address\r\n",
                    "AT+CHANNEL=$channel\r\n",
                    "AT+CRFOP=$power\r\n"
                )
                commands.forEach { cmd ->
                    ConnectionManager.writeCharacteristic(
                        device, characteristic, cmd.toByteArray(Charsets.UTF_8)
                    )
                }
                binding.atStatus.text = "✓ Configuración enviada al módulo"
            } else {
                binding.atStatus.text = "Configuración guardada (sin dispositivo conectado)"
            }
        } else {
            binding.atStatus.text = "Configuración guardada (sin dispositivo conectado)"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}