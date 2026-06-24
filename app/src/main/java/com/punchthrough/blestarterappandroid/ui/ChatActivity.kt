package com.punchthrough.blestarterappandroid

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.punchthrough.blestarterappandroid.databinding.ActivityChatBinding
import com.punchthrough.blestarterappandroid.ble.ConnectionEventListener
import com.punchthrough.blestarterappandroid.ble.ConnectionManager
import com.punchthrough.blestarterappandroid.ui.MessagesAdapter

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private val bleViewModel: BleViewModel by viewModels()
    private val messagesAdapter = MessagesAdapter()

    private val connectionEventListener by lazy {
        ConnectionEventListener().apply {
            onDisconnect = {
                runOnUiThread {
                    if (!isFinishing) {
                        MaterialAlertDialogBuilder(this@ChatActivity)
                            .setTitle(getString(R.string.device_disconnected))
                            .setMessage(getString(R.string.device_disconnected_msg))
                            .setPositiveButton(getString(R.string.btn_go_back)) { _, _ -> finish() }
                            .setCancelable(false)
                            .show()
                    }
                }
            }
        }
    }

    private var contactAddress: Int = -1
    private var contactName: String = ""

    companion object {
        const val EXTRA_CONTACT_ADDRESS = "extra_contact_address"
        const val EXTRA_CONTACT_NAME = "extra_contact_name"
        const val PUBLIC_CHANNEL_ADDRESS = BleViewModel.PUBLIC_CHANNEL_ADDRESS
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase, LocaleHelper.getSavedLocale(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ConnectionManager.registerListener(connectionEventListener)

        contactAddress = intent.getIntExtra(EXTRA_CONTACT_ADDRESS, -1)
        contactName = intent.getStringExtra(EXTRA_CONTACT_NAME)
            ?: getString(R.string.node_label, "%08X".format(contactAddress))

        binding.toolbar.title = contactName
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.inflateMenu(R.menu.menu_chat)
        val isGroup = bleViewModel.keyStore.getAllGroupIds().contains(contactAddress)
        binding.toolbar.menu.findItem(R.id.action_share_group)?.isVisible = isGroup
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_share_group) { showGroupQrDialog(); true } else false
        }

        messagesAdapter.isPublicChannel = (contactAddress == PUBLIC_CHANNEL_ADDRESS)
        messagesAdapter.onSenderLongClick = { senderAddress -> showSenderOptionsDialog(senderAddress) }

        val layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.messagesRecyclerView.layoutManager = layoutManager
        binding.messagesRecyclerView.adapter = messagesAdapter

        bleViewModel.markConversationRead(contactAddress)

        bleViewModel.getMessagesForContact(contactAddress).observe(this) { messages ->
            messagesAdapter.submitList(messages) {
                if (messages.isNotEmpty()) {
                    binding.messagesRecyclerView.scrollToPosition(messages.size - 1)
                    bleViewModel.markConversationRead(contactAddress)
                }
            }
        }

        binding.sendButton.setOnClickListener { sendMessage() }
        binding.messageEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendMessage(); true } else false
        }
    }

    override fun onDestroy() {
        ConnectionManager.unregisterListener(connectionEventListener)
        super.onDestroy()
    }

    private fun sendMessage() {
        val text = binding.messageEditText.text.toString().trim()
        if (text.isEmpty() || contactAddress == -1) return
        if (ConnectionManager.connectedDevices().isEmpty()) {
            showNoDeviceDialog()
            return
        }
        bleViewModel.sendMessage(contactAddress, text)
        binding.messageEditText.text?.clear()
    }

    private fun showNoDeviceDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.no_device_title))
            .setMessage(getString(R.string.no_device_msg))
            .setPositiveButton(getString(R.string.btn_go_connect)) { _, _ -> finish() }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun showGroupQrDialog() {
        val code = bleViewModel.groupCode(contactAddress)
        if (code.isEmpty()) return
        val qrBitmap = generateQrBitmap(code)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }
        if (qrBitmap != null) {
            layout.addView(ImageView(this).apply {
                setImageBitmap(qrBitmap)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 480
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
            })
        }
        layout.addView(TextView(this).apply {
            text = getString(R.string.share_code_msg)
            setPadding(0, 16, 0, 8)
            textSize = 13f
        })
        layout.addView(TextView(this).apply {
            text = code
            textSize = 11f
            setTextIsSelectable(true)
        })

        MaterialAlertDialogBuilder(this)
            .setTitle(contactName)
            .setView(layout)
            .setPositiveButton(getString(R.string.btn_close), null)
            .show()
    }

    private fun generateQrBitmap(content: String, size: Int = 512): Bitmap? {
        return try {
            val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
            BarcodeEncoder().createBitmap(matrix)
        } catch (e: Exception) {
            null
        }
    }

    private fun showSenderOptionsDialog(senderAddress: Int) {
        val nodeLabel = getString(R.string.node_hex_label, "%08X".format(senderAddress))
        MaterialAlertDialogBuilder(this)
            .setTitle(nodeLabel)
            .setItems(arrayOf(getString(R.string.sender_send), getString(R.string.sender_add_contact))) { _, which ->
                when (which) {
                    0 -> startActivity(
                        Intent(this, ChatActivity::class.java).apply {
                            putExtra(EXTRA_CONTACT_ADDRESS, senderAddress)
                            putExtra(EXTRA_CONTACT_NAME, nodeLabel)
                        }
                    )
                    1 -> bleViewModel.saveContact(senderAddress, "")
                }
            }
            .show()
    }
}
