package com.punchthrough.blestarterappandroid.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.punchthrough.blestarterappandroid.BleViewModel
import com.punchthrough.blestarterappandroid.ScanResultAdapter
import com.punchthrough.blestarterappandroid.ble.ConnectionManager
import com.punchthrough.blestarterappandroid.databinding.BottomSheetConnectBinding
import com.punchthrough.blestarterappandroid.hasRequiredBluetoothPermissions

private const val A3MESH_PREFIX = "A3MESH_Node"

class ConnectBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetConnectBinding? = null
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

    private val scanResultAdapter by lazy {
        ScanResultAdapter(scanResults) { result ->
            if (isScanning) stopScan()
            ConnectionManager.connect(result.device, requireContext())
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) tryStartScan()
        else Toast.makeText(
            requireContext(),
            "Se necesitan permisos Bluetooth para escanear",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = false
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetConnectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            isDraggable = false
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    requireActivity().finish()
                }
            }
        )

        binding.devicesRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.devicesRecyclerView.adapter = scanResultAdapter

        binding.scanButton.setOnClickListener { tryStartScan() }

        @Suppress("DEPRECATION")
        binding.btActivateButton.setOnClickListener {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }

        bleViewModel.connectedDevice.observe(viewLifecycleOwner) { device ->
            if (device != null) dismissAllowingStateLoss()
        }

        requireContext().registerReceiver(
            btStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        )

        tryStartScan()
    }

    private fun tryStartScan() {
        if (!bluetoothAdapter.isEnabled) {
            binding.btOffGroup.visibility = View.VISIBLE
            binding.scanGroup.visibility = View.GONE
            return
        }
        binding.btOffGroup.visibility = View.GONE
        binding.scanGroup.visibility = View.VISIBLE
        if (requireContext().hasRequiredBluetoothPermissions()) startScan()
        else requestBluetoothPermissions()
    }

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
            when (state) {
                BluetoothAdapter.STATE_ON -> tryStartScan()
                BluetoothAdapter.STATE_OFF -> {
                    if (isScanning) stopScan()
                    _binding?.btOffGroup?.visibility = View.VISIBLE
                    _binding?.scanGroup?.visibility = View.GONE
                }
            }
        }
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            )
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

    @SuppressLint("MissingPermission", "NotifyDataSetChanged")
    private fun startScan() {
        scanResults.clear()
        scanResultAdapter.notifyDataSetChanged()
        bleScanner.startScan(null, scanSettings, scanCallback)
        isScanning = true
        binding.scanButton.isEnabled = false
        binding.scanningRow.visibility = View.VISIBLE
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        bleScanner.stopScan(scanCallback)
        isScanning = false
        binding.scanButton.isEnabled = true
        binding.scanningRow.visibility = View.GONE
    }

    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            if (!name.startsWith(A3MESH_PREFIX)) return
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
            _binding?.scanButton?.isEnabled = true
            _binding?.scanningRow?.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        if (isScanning) stopScan()
        runCatching { requireContext().unregisterReceiver(btStateReceiver) }
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ConnectBottomSheet"
    }
}
