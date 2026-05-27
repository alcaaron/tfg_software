package com.punchthrough.blestarterappandroid.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import com.punchthrough.blestarterappandroid.BleViewModel
import com.punchthrough.blestarterappandroid.ScanResultAdapter
import com.punchthrough.blestarterappandroid.ble.ConnectionManager
import com.punchthrough.blestarterappandroid.databinding.FragmentDeviceBinding
import timber.log.Timber

class DeviceFragment : Fragment() {

    private var _binding: FragmentDeviceBinding? = null
    private val binding get() = _binding!!
    private val bleViewModel: BleViewModel by activityViewModels()

    private val bluetoothAdapter by lazy {
        val manager = requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }
    private val bleScanner by lazy { bluetoothAdapter.bluetoothLeScanner }
    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

    private var isScanning = false
        set(value) {
            field = value
            binding.scanButton.text = if (value) "Detener escaneo" else "Iniciar escaneo"
        }

    private val scanResults = mutableListOf<ScanResult>()
    private val scanResultAdapter by lazy {
        ScanResultAdapter(scanResults) { result ->
            if (isScanning) stopScan()
            Timber.w("Conectando a ${result.device.address}")
            ConnectionManager.connect(result.device, requireContext())
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDeviceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.scanResultsRecyclerView.apply {
            adapter = scanResultAdapter
            layoutManager = LinearLayoutManager(requireContext())
            isNestedScrollingEnabled = false
            (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        }

        binding.scanButton.setOnClickListener {
            if (isScanning) stopScan() else startScan()
        }

        // Mostrar estado de conexión BLE
        bleViewModel.connectionStatus.observe(viewLifecycleOwner) { status ->
            binding.connectionStatus.text = status
        }
    }

    @SuppressLint("MissingPermission", "NotifyDataSetChanged")
    private fun startScan() {
        scanResults.clear()
        scanResultAdapter.notifyDataSetChanged()
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
            val index = scanResults.indexOfFirst { it.device.address == result.device.address }
            if (index != -1) {
                scanResults[index] = result
                scanResultAdapter.notifyItemChanged(index)
            } else {
                scanResults.add(result)
                scanResultAdapter.notifyItemInserted(scanResults.size - 1)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            Timber.e("Scan failed: $errorCode")
        }
    }

    override fun onDestroyView() {
        if (isScanning) stopScan()
        super.onDestroyView()
        _binding = null
    }
}