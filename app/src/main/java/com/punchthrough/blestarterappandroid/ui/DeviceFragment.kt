package com.punchthrough.blestarterappandroid.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.punchthrough.blestarterappandroid.BleViewModel
import com.punchthrough.blestarterappandroid.R
import com.punchthrough.blestarterappandroid.ScanResultAdapter
import com.punchthrough.blestarterappandroid.ble.ConnectionManager
import com.punchthrough.blestarterappandroid.databinding.FragmentDeviceBinding
import com.punchthrough.blestarterappandroid.hasRequiredBluetoothPermissions
import timber.log.Timber

private const val NODE_NAME_PREFIX = "A3MESH_Node"

private val INFO_LABELS = mapOf(
    "NODEID"    to "ID del Nodo",
    "BATT"      to "Batería (%)",
    "FWVER"     to "Versión FW",
    "UPTIME"    to "Tiempo encendido",
    "FREQ"      to "Frecuencia (MHz)",
    "SF"        to "Spreading Factor",
    "NEIGHBORS" to "Vecinos activos",
    "FW"        to "Firmware",
    "BW"        to "Ancho de Banda",
    "CR"        to "Coding Rate",
    "POWER"     to "Potencia TX (dBm)",
    "CHANNEL"   to "Canal"
)

class DeviceFragment : Fragment() {

    private var _binding: FragmentDeviceBinding? = null
    private val binding get() = _binding!!
    private val bleViewModel: BleViewModel by activityViewModels()

    private val bluetoothAdapter by lazy {
        (requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private val bleScanner by lazy { bluetoothAdapter.bluetoothLeScanner }
    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

    private var isScanning = false
    private val scanResults = mutableListOf<ScanResult>()
    private var scanDialog: androidx.appcompat.app.AlertDialog? = null

    private val scanResultAdapter by lazy {
        ScanResultAdapter(scanResults) { result ->
            scanDialog?.dismiss()
            scanDialog = null
            if (isScanning) stopScan()
            Timber.w("Conectando a ${result.device.address}")
            ConnectionManager.connect(result.device, requireContext())
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) showScanDialog()
        else Toast.makeText(requireContext(), "Se necesitan permisos Bluetooth para escanear", Toast.LENGTH_LONG).show()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDeviceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.scanButton.setOnClickListener {
            if (!bluetoothAdapter.isEnabled) {
                showEnableBluetoothDialog()
            } else if (requireContext().hasRequiredBluetoothPermissions()) {
                showScanDialog()
            } else {
                requestBluetoothPermissions()
            }
        }

        binding.disconnectButton.setOnClickListener {
            bleViewModel.connectedDevice.value?.let { device ->
                ConnectionManager.teardownConnection(device)
            }
        }

        bleViewModel.connectedDevice.observe(viewLifecycleOwner) { device ->
            if (device != null) {
                binding.disconnectedGroup.visibility = View.GONE
                binding.connectedGroup.visibility = View.VISIBLE
                @SuppressLint("MissingPermission")
                val name = device.name ?: device.address
                binding.deviceNameText.text = name
                scanDialog?.dismiss()
                if (isScanning) stopScan()
            } else {
                binding.disconnectedGroup.visibility = View.VISIBLE
                binding.connectedGroup.visibility = View.GONE
            }
        }

        bleViewModel.deviceInfo.observe(viewLifecycleOwner) { info ->
            updateDeviceInfoDisplay(info)
        }
    }

    override fun onResume() {
        super.onResume()
        if (bleViewModel.connectedDevice.value != null) {
            bleViewModel.sendAtCommand("AT+INFO?\r\n")
        }
    }

    private fun showEnableBluetoothDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Bluetooth desactivado")
            .setMessage("Activa el Bluetooth para buscar nodos A3MESH.")
            .setPositiveButton("Activar") { _, _ ->
                @Suppress("DEPRECATION")
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

    private fun showScanDialog() {
        scanResults.clear()
        scanResultAdapter.notifyDataSetChanged()

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_scan_results, null)
        val recyclerView = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.dialogScanRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = scanResultAdapter
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        scanDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Dispositivos disponibles")
            .setView(dialogView)
            .setNegativeButton("Cancelar") { _, _ -> if (isScanning) stopScan() }
            .setOnDismissListener { if (isScanning) stopScan() }
            .show()

        startScan()
    }

    @SuppressLint("MissingPermission", "NotifyDataSetChanged")
    private fun startScan() {
        bleScanner.startScan(null, scanSettings, scanCallback)
        isScanning = true
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        bleScanner.stopScan(scanCallback)
        isScanning = false
    }

    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            if (!name.startsWith(NODE_NAME_PREFIX)) return
            val index = scanResults.indexOfFirst { it.device.address == result.device.address }
            if (index != -1) {
                scanResults[index] = result
                scanResultAdapter.notifyItemChanged(index)
            } else {
                scanResults.add(result)
                scanResultAdapter.notifyItemInserted(scanResults.size - 1)
            }
        }
        override fun onScanFailed(errorCode: Int) { Timber.e("Scan failed: $errorCode") }
    }

    private fun updateDeviceInfoDisplay(info: Map<String, String>) {
        val batteryValue = info["BATT"]
        binding.batteryText.text = batteryValue?.let { "$it%" } ?: ""
        if (batteryValue != null) {
            val pct = batteryValue.toIntOrNull() ?: 0
            val (drawableRes, colorRes) = when {
                pct > 50 -> R.drawable.ic_battery_high to R.color.battery_green
                pct > 20 -> R.drawable.ic_battery_mid  to R.color.battery_orange
                else     -> R.drawable.ic_battery_low  to R.color.battery_red
            }
            val batteryColor = requireContext().getColor(colorRes)
            binding.batteryText.setTextColor(batteryColor)
            val icon: Drawable? = androidx.core.content.ContextCompat
                .getDrawable(requireContext(), drawableRes)?.mutate()
            icon?.setTint(batteryColor)
            binding.batteryText.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
        } else {
            binding.batteryText.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
        }

        binding.deviceInfoContainer.removeAllViews()
        val displayInfo = info.filter { it.key != "BATT" }
        if (displayInfo.isEmpty()) return
        val inflater = LayoutInflater.from(requireContext())
        displayInfo.forEach { (key, value) ->
            val row = inflater.inflate(R.layout.item_device_info_field, binding.deviceInfoContainer, false)
            row.findViewById<TextView>(R.id.infoLabel).text = INFO_LABELS[key] ?: key
            row.findViewById<TextView>(R.id.infoValue).text = value
            binding.deviceInfoContainer.addView(row)
            val divider = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).also { it.setMargins(0, 2, 0, 2) }
                setBackgroundColor(
                    requireContext().getColor(android.R.color.darker_gray).let {
                        android.graphics.Color.argb(30,
                            android.graphics.Color.red(it),
                            android.graphics.Color.green(it),
                            android.graphics.Color.blue(it))
                    }
                )
            }
            binding.deviceInfoContainer.addView(divider)
        }
        if (binding.deviceInfoContainer.childCount > 0) {
            binding.deviceInfoContainer.removeViewAt(binding.deviceInfoContainer.childCount - 1)
        }
    }

    override fun onDestroyView() {
        scanDialog?.dismiss()
        if (isScanning) stopScan()
        super.onDestroyView()
        _binding = null
    }
}
