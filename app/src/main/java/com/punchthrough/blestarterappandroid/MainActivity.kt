package com.punchthrough.blestarterappandroid

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
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
                bleViewModel.onDeviceConnected(gatt.device)
                val notifyChar = gatt.services
                    ?.flatMap { it.characteristics ?: emptyList() }
                    ?.find { it.uuid == MESSAGE_NOTIFICATION_UUID }
                if (notifyChar != null && notifyChar.isNotifiable()) {
                    ConnectionManager.enableNotifications(gatt.device, notifyChar)
                }
                runOnUiThread {
                    binding.bottomNav.selectedItemId = R.id.chatsFragment
                }
            }

            onDisconnect = {
                bleViewModel.onDeviceDisconnected()
            }

            onCharacteristicChanged = { _, characteristic, value ->
                if (characteristic.uuid == MESSAGE_NOTIFICATION_UUID) {
                    val text = String(value, Charsets.UTF_8).trim()
                    if (text.startsWith("+RCV=")) {
                        val parts = text.removePrefix("+RCV=").split(",")
                        if (parts.size == 3) {
                            parts[0].toIntOrNull()?.let { senderAddress ->
                                bleViewModel.onMessageReceived(senderAddress, parts[2])
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ConnectionManager.registerListener(connectionEventListener)
        ConnectionManager.listenToBondStateChanges(this)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        binding.bottomNav.setupWithNavController(navController)
    }

    override fun onDestroy() {
        ConnectionManager.unregisterListener(connectionEventListener)
        super.onDestroy()
    }
}