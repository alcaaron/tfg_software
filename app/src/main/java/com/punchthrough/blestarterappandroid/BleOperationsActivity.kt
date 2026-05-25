/*
 * Copyright 2024 Punch Through Design LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.punchthrough.blestarterappandroid

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.punchthrough.blestarterappandroid.ble.ConnectionEventListener
import com.punchthrough.blestarterappandroid.ble.ConnectionManager
import com.punchthrough.blestarterappandroid.ble.ConnectionManager.parcelableExtraCompat
import com.punchthrough.blestarterappandroid.ble.isIndicatable
import com.punchthrough.blestarterappandroid.ble.isNotifiable
import com.punchthrough.blestarterappandroid.ble.isReadable
import com.punchthrough.blestarterappandroid.ble.isWritable
import com.punchthrough.blestarterappandroid.ble.isWritableWithoutResponse
import com.punchthrough.blestarterappandroid.ble.toHexString
import com.punchthrough.blestarterappandroid.databinding.ActivityBleOperationsBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import android.util.Log

// BLE Module UUIDs - separate characteristics for write and notifications
private val SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
private val MESSAGE_WRITE_CHARACTERISTIC_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
private val MESSAGE_NOTIFICATION_CHARACTERISTIC_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

class BleOperationsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBleOperationsBinding
    private val device: BluetoothDevice by lazy {
        intent.parcelableExtraCompat(BluetoothDevice.EXTRA_DEVICE)
            ?: error("Missing BluetoothDevice from MainActivity!")
    }
    private val dateFormatter = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US)
    private val characteristics by lazy {
        ConnectionManager.servicesOnDevice(device)?.flatMap { service ->
            service.characteristics ?: listOf()
        } ?: listOf()
    }
    private val characteristicProperties by lazy {
        characteristics.associateWith { characteristic ->
            mutableListOf<CharacteristicProperty>().apply {
                if (characteristic.isNotifiable()) add(CharacteristicProperty.Notifiable)
                if (characteristic.isIndicatable()) add(CharacteristicProperty.Indicatable)
                if (characteristic.isReadable()) add(CharacteristicProperty.Readable)
                if (characteristic.isWritable()) add(CharacteristicProperty.Writable)
                if (characteristic.isWritableWithoutResponse()) {
                    add(CharacteristicProperty.WritableWithoutResponse)
                }
            }.toList()
        }
    }
    private val characteristicAdapter: CharacteristicAdapter by lazy {
        CharacteristicAdapter(characteristics) { characteristic ->
            showCharacteristicOptions(characteristic)
        }
    }
    private val notifyingCharacteristics = mutableListOf<UUID>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConnectionManager.registerListener(connectionEventListener)

        binding = ActivityBleOperationsBinding.inflate(layoutInflater)

        setContentView(binding.root)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(true)
            title = getString(R.string.ble_playground)
        }

        setupTargetAddressSpinner()
        setupSendButton()
        setupClearButton()
        enableMessageNotifications()
    }

    private fun setupTargetAddressSpinner() {
        val adapter = ArrayAdapter.createFromResource(
            this,
            R.array.target_addresses,
            android.R.layout.simple_spinner_item
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.targetAddressSpinner.adapter = adapter
    }

    private fun setupSendButton() {
        binding.sendButton.setOnClickListener {
            sendMessage()
        }
    }

    private fun setupClearButton() {
        binding.clearButton.setOnClickListener {
            clearConversation()
        }
    }

    private fun clearConversation() {
        runOnUiThread {
            binding.logTextView.text = ""
        }
    }

    private fun parseReceivedMessage(receivedData: String): List<String>? {
        // Parse format: +RCV=<src>,<node_id>,<msg>
        try {
            if (receivedData.startsWith("+RCV=")) {
                val parts = receivedData.substring(5).split(",") // Remove "+RCV=" and split by comma
                if (parts.size == 3) {
                    // parts[0] = src address
                    // parts[1] = node_id address
                    // parts[2] = actual message
                    return parts
                }
            }
        } catch (e: Exception) {
            Log.e("BleOperations", "Error parsing received message: $receivedData", e)
        }
        return null
    }

    private fun sendMessage() {
        val message = binding.messageEditText.text.toString().trim()
        if (message.isEmpty()) {
            return
        }

        val targetAddress = binding.targetAddressSpinner.selectedItem.toString()
        val formattedMessage = "AT+SEND=$targetAddress,$message\r\n"
        
        // Show only the actual message in the conversation
        logConversation("You", message)
        
        writeMessageValue(formattedMessage)
        
        // Clear the input field after sending
        binding.messageEditText.text.clear()
        hideKeyboard()
    }

    private fun writeMessageValue(value: String) {
        val characteristic = characteristics.find { it.uuid == MESSAGE_WRITE_CHARACTERISTIC_UUID }
        if (characteristic != null) {
            Log.d("BleOperations", "Message write characteristic found: ${characteristic.uuid}")
            val bytes = value.toByteArray(Charsets.UTF_8)
            Log.d("BleOperations", "Writing to ${characteristic.uuid}: $value")
            ConnectionManager.writeCharacteristic(device, characteristic, bytes)
        } else {
            Log.e("BleOperations", "Message write characteristic not found!")
            // Don't show error messages in conversation view
        }
    }

    private fun enableMessageNotifications() {
        Log.d("BleOperations", "Looking for notification characteristic: $MESSAGE_NOTIFICATION_CHARACTERISTIC_UUID")
        
        // Debug: List all available characteristics (only in debug logs)
        characteristics.forEach { char ->
            Log.d("BleOperations", "Available characteristic: ${char.uuid}, properties: ${char.properties}")
        }
        
        val characteristic = characteristics.find { it.uuid == MESSAGE_NOTIFICATION_CHARACTERISTIC_UUID }
        if (characteristic != null) {
            Log.d("BleOperations", "Message notification characteristic found: ${characteristic.uuid}")
            
            // Check if the characteristic supports notifications
            if (characteristic.isNotifiable()) {
                Log.d("BleOperations", "Enabling notifications...")
                ConnectionManager.enableNotifications(device, characteristic)
            } else {
                Log.w("BleOperations", "Characteristic does not support notifications!")
                // Don't show warnings in conversation view
            }
        } else {
            Log.e("BleOperations", "Message notification characteristic not found!")
            // Don't show errors in conversation view
        }
    }

    override fun onDestroy() {
        ConnectionManager.unregisterListener(connectionEventListener)
        ConnectionManager.teardownConnection(device)
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    @SuppressLint("SetTextI18n")
    private fun log(message: String) {
        val formattedMessage = "${dateFormatter.format(Date())}: $message"
        runOnUiThread {
            val uiText = binding.logTextView.text
            val currentLogText = uiText.ifEmpty { "Beginning of log." }
            binding.logTextView.text = "$currentLogText\n$formattedMessage"
            binding.logScrollView.post { binding.logScrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun logConversation(sender: String, message: String) {
        val timestamp = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        val formattedMessage = when (sender) {
            "You" -> "[$timestamp - You] $message"
            else -> "[$timestamp - $sender] $message"
        }
        
        runOnUiThread {
            val currentText = binding.logTextView.text.toString()
            val newText = if (currentText.isEmpty()) {
                formattedMessage
            } else {
                "$currentText\n$formattedMessage"
            }
            binding.logTextView.text = newText
            binding.logScrollView.post { binding.logScrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun showCharacteristicOptions(
        characteristic: BluetoothGattCharacteristic
    ) = runOnUiThread {
        characteristicProperties[characteristic]?.let { properties ->
            AlertDialog.Builder(this)
                .setTitle("Select an action to perform")
                .setItems(properties.map { it.action }.toTypedArray()) { _, i ->
                    when (properties[i]) {
                        CharacteristicProperty.Readable -> {
                            log("Reading from ${characteristic.uuid}")
                            ConnectionManager.readCharacteristic(device, characteristic)
                        }
                        CharacteristicProperty.Writable, CharacteristicProperty.WritableWithoutResponse -> {
                            showWritePayloadDialog(characteristic)
                        }
                        CharacteristicProperty.Notifiable, CharacteristicProperty.Indicatable -> {
                            if (notifyingCharacteristics.contains(characteristic.uuid)) {
                                log("Disabling notifications on ${characteristic.uuid}")
                                ConnectionManager.disableNotifications(device, characteristic)
                            } else {
                                log("Enabling notifications on ${characteristic.uuid}")
                                ConnectionManager.enableNotifications(device, characteristic)
                            }
                        }
                    }
                }
                .show()
        }
    }

    @SuppressLint("InflateParams")
    private fun showWritePayloadDialog(characteristic: BluetoothGattCharacteristic) {
        val hexField = layoutInflater.inflate(R.layout.edittext_hex_payload, null) as EditText
        AlertDialog.Builder(this)
            .setView(hexField)
            .setPositiveButton("Write") { _, _ ->
                with(hexField.text.toString()) {
                    if (isNotBlank() && isNotEmpty()) {
                        val bytes = hexToBytes()
                        log("Writing to ${characteristic.uuid}: ${bytes.toHexString()}")
                        ConnectionManager.writeCharacteristic(device, characteristic, bytes)
                    } else {
                        log("Please enter a hex payload to write to ${characteristic.uuid}")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
            .apply {
                window?.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                )
                hexField.showKeyboard()
                show()
            }
    }

    private val connectionEventListener by lazy {
        ConnectionEventListener().apply {
            onDisconnect = {
                runOnUiThread {
                    AlertDialog.Builder(this@BleOperationsActivity)
                        .setTitle("Disconnected")
                        .setMessage("Disconnected from device.")
                        .setPositiveButton("OK") { _, _ -> onBackPressed() }
                        .show()
                }
            }

            onCharacteristicRead = { _, characteristic, value ->
                Log.d("BleOperations", "Read from ${characteristic.uuid}: ${value.toHexString()}")
                // Don't show technical read operations in conversation
            }

            onCharacteristicWrite = { _, characteristic ->
                Log.d("BleOperations", "Wrote to ${characteristic.uuid}")
                // Don't show write confirmations in conversation
            }

            onMtuChanged = { _, mtu ->
                Log.d("BleOperations", "MTU updated to $mtu")
                // Don't show MTU changes in conversation
            }

            onCharacteristicChanged = { _, characteristic, value ->
                Log.d("BleOperations", "Value changed on ${characteristic.uuid}: ${value.toHexString()}")
                
                // Try to decode as text
                val receivedData = String(value, Charsets.UTF_8).trim()
                Log.d("BleOperations", "Decoded text: $receivedData")
                
                if (characteristic.uuid == MESSAGE_NOTIFICATION_CHARACTERISTIC_UUID) {
                    Log.d("BleOperations", "Message received on notification characteristic: $receivedData")
                    
                    // Filter out confirmation messages and parse actual messages
                    if (receivedData.startsWith("+RCV=")) {
                        // Parse message format: +RCV=<src>,<node_id>,<msg>
                        val aMessageArguments = parseReceivedMessage(receivedData)
                        if (aMessageArguments != null) {
                            runOnUiThread {
                                logConversation(aMessageArguments[0], aMessageArguments[2])
                            }
                        }
                    } else if (receivedData == "+OK" || receivedData.startsWith("+OK")) {
                        // Filter out confirmation messages - don't show in conversation
                        Log.d("BleOperations", "Received confirmation message: $receivedData")
                    }
                    else if (receivedData.startsWith("alive"))
                    {
                        Log.d("BleOperations", "Heartbet received: $receivedData")
                        // Add internal logic processing of HB signal

                    }
                    else {
                        // For any other format, show as-is (fallback)
                        runOnUiThread {
                            logConversation("Contact", receivedData)
                        }
                    }
                } else {
                    // Still log technical data for debugging if needed
                    Log.d("BleOperations", "Data received on different characteristic: ${characteristic.uuid}")
                }
            }

            onNotificationsEnabled = { _, characteristic ->
                Log.d("BleOperations", "Notifications enabled on ${characteristic.uuid}")
                // Don't show notification status in conversation
                notifyingCharacteristics.add(characteristic.uuid)
            }

            onNotificationsDisabled = { _, characteristic ->
                Log.d("BleOperations", "Notifications disabled on ${characteristic.uuid}")
                // Don't show notification status in conversation
                notifyingCharacteristics.remove(characteristic.uuid)
            }
        }
    }

    private enum class CharacteristicProperty {
        Readable,
        Writable,
        WritableWithoutResponse,
        Notifiable,
        Indicatable;

        val action
            get() = when (this) {
                Readable -> "Read"
                Writable -> "Write"
                WritableWithoutResponse -> "Write Without Response"
                Notifiable -> "Toggle Notifications"
                Indicatable -> "Toggle Indications"
            }
    }

    private fun Activity.hideKeyboard() {
        hideKeyboard(currentFocus ?: View(this))
    }

    private fun Context.hideKeyboard(view: View) {
        val inputMethodManager = getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun hideKeyboard() {
        val inputMethodManager = getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(binding.messageEditText.windowToken, 0)
    }

    private fun EditText.showKeyboard() {
        val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        requestFocus()
        inputMethodManager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun String.hexToBytes() =
        this.chunked(2).map { it.uppercase(Locale.US).toInt(16).toByte() }.toByteArray()
}
