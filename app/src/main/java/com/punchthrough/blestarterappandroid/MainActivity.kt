package com.punchthrough.blestarterappandroid

import android.content.Context
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.punchthrough.blestarterappandroid.ble.ConnectionEventListener
import com.punchthrough.blestarterappandroid.ble.ConnectionManager
import com.punchthrough.blestarterappandroid.ble.isNotifiable
import com.punchthrough.blestarterappandroid.databinding.ActivityMainBinding
import java.util.UUID

private val MESSAGE_NOTIFICATION_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val bleViewModel: BleViewModel by viewModels()

    private val connectionEventListener by lazy {
        ConnectionEventListener().apply {
            onConnectionSetupComplete = { gatt ->
                ConnectionManager.requestMtu(gatt.device, 256)
                bleViewModel.onDeviceConnected(gatt.device)
                val notifyChar = gatt.services
                    ?.flatMap { it.characteristics ?: emptyList() }
                    ?.find { it.uuid == MESSAGE_NOTIFICATION_UUID }
                if (notifyChar != null && notifyChar.isNotifiable()) {
                    ConnectionManager.enableNotifications(gatt.device, notifyChar)
                }
                bleViewModel.sendAtCommand("AT+INFO?\r\n")
                runOnUiThread {
                    binding.bottomNav.selectedItemId = R.id.deviceFragment
                }
            }

            onDisconnect = {
                bleViewModel.onDeviceDisconnected()
                runOnUiThread {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Dispositivo desconectado")
                        .setMessage("La conexión con el dispositivo BLE se ha perdido.")
                        .setPositiveButton("Aceptar", null)
                        .show()
                }
            }

            onCharacteristicChanged = { _, characteristic, value ->
                if (characteristic.uuid == MESSAGE_NOTIFICATION_UUID) {
                    val text = String(value, Charsets.UTF_8).trim()
                    when {
                        text.startsWith("+RCV=") -> {
                            // +RCV=<src_hex>,<node_id_hex>,<dst_hex>,<payload>
                            val parts = text.removePrefix("+RCV=").split(",", limit = 4)
                            if (parts.size == 4) {
                                parts[0].toLongOrNull(16)?.toInt()?.let { addr ->
                                    val dst = parts[2].trim()
                                    if (dst.uppercase() == "FFFFFFFF") {
                                        bleViewModel.onPublicMessageReceived(addr, parts[3])
                                    } else {
                                        bleViewModel.onMessageReceived(addr, parts[3])
                                        // Received unencrypted from a peer we have a key for:
                                        // they lost our KX response → reset and re-exchange
                                        if (bleViewModel.keyStore.hasPeerKey(addr)) {
                                            bleViewModel.resetAndExchange(addr)
                                        }
                                    }
                                }
                            }
                        }
                        text.startsWith("+RCVE2E=") -> {
                            // +RCVE2E=<src_hex>,<node_id_hex>,<plaintext>
                            val parts = text.removePrefix("+RCVE2E=").split(",", limit = 3)
                            if (parts.size == 3) {
                                parts[0].toLongOrNull(16)?.toInt()?.let { addr ->
                                    bleViewModel.onMessageReceived(addr, parts[2])
                                }
                            }
                        }
                        text.startsWith("+RCVGRP=") -> {
                            // +RCVGRP=<group_id_hex>,<src_hex>,<node_id_hex>,<plaintext>
                            val parts = text.removePrefix("+RCVGRP=").split(",", limit = 4)
                            if (parts.size == 4) {
                                val groupId = parts[0].toLongOrNull(16)?.toInt()
                                val src = parts[1].toLongOrNull(16)?.toInt()
                                if (groupId != null && src != null) {
                                    bleViewModel.onGroupMessageReceived(groupId, src, parts[3])
                                }
                            }
                        }
                        text.startsWith("+DISC=") -> {
                            // +DISC=<node_id_hex>  — new neighbor discovered
                            val nodeIdHex = text.removePrefix("+DISC=").trim()
                            nodeIdHex.toLongOrNull(16)?.toInt()?.let { nodeId ->
                                bleViewModel.initiateKeyExchange(nodeId)
                            }
                        }
                        text.startsWith("+KX=") -> {
                            // +KX=<src_hex>,<relay_hex>,<pub_key_128hex>
                            val parts = text.removePrefix("+KX=").split(",", limit = 3)
                            if (parts.size == 3) {
                                parts[0].toLongOrNull(16)?.toInt()?.let { src ->
                                    bleViewModel.onKeyExchangeReceived(src, parts[2].trim())
                                }
                            }
                        }
                        text.startsWith("+NEIGHBORS=") -> {
                            val neighbors = parseNeighbors(text)
                            bleViewModel.onNeighborsReceived(neighbors)
                            for (neighbor in neighbors) {
                                neighbor.nodeId.toLongOrNull(16)?.toInt()?.let { nodeId ->
                                    bleViewModel.initiateKeyExchange(nodeId)
                                }
                            }
                        }
                        text.contains("+NODEID=") || text.startsWith("+NODEID=") -> {
                            val info = parseDeviceInfo(text)
                            if (info.isNotEmpty()) bleViewModel.onDeviceInfoReceived(info)
                        }
                    }
                }
            }
        }
    }

    private fun parseDeviceInfo(text: String): Map<String, String> {
        val info = mutableMapOf<String, String>()
        text.split("\r\n", "\n").forEach { line ->
            val clean = line.trim()
            if (clean.startsWith("+") && clean.contains("=")) {
                val key = clean.removePrefix("+").substringBefore("=")
                val value = clean.substringAfter("=")
                if (key.isNotEmpty()) info[key] = value
            }
        }
        return info
    }

    private fun parseNeighbors(text: String): List<NeighborNode> {
        val nodes = mutableListOf<NeighborNode>()
        text.split("\r\n", "\n").forEach { line ->
            val clean = line.trim()
            if (clean.startsWith("+N:")) {
                val body = clean.removePrefix("+N:").trim().trimEnd(';')
                val nodeId = body.substringBefore(",").trim()
                val rssi = body.substringAfter("RSSI:").substringBefore(",").trim()
                val t = body.substringAfter("T:").trim()
                if (nodeId.isNotEmpty()) nodes.add(NeighborNode(nodeId, rssi, t))
            }
        }
        return nodes
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        AppCompatDelegate.setDefaultNightMode(
            prefs.getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        )
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ConnectionManager.registerListener(connectionEventListener)
        ConnectionManager.listenToBondStateChanges(this)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        binding.bottomNav.setupWithNavController(navController)

        bleViewModel.connectedDevice.observe(this) { device ->
            binding.bottomNav.menu.findItem(R.id.deviceFragment).setIcon(
                if (device != null) R.drawable.ic_device else R.drawable.ic_device_off
            )
        }
    }

    override fun onDestroy() {
        ConnectionManager.unregisterListener(connectionEventListener)
        super.onDestroy()
    }
}
