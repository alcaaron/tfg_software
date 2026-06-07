package com.punchthrough.blestarterappandroid.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.punchthrough.blestarterappandroid.BleViewModel
import com.punchthrough.blestarterappandroid.data.model.Contact
import com.punchthrough.blestarterappandroid.data.model.Message
import com.punchthrough.blestarterappandroid.databinding.ItemChatBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatItem(
    val contact: Contact?,
    val lastMessage: Message,
    val isPinned: Boolean = false,
    val isGroup: Boolean = false,
    val groupName: String? = null,
    val unreadCount: Int = 0
)

class ChatsAdapter(
    private val onClick: (ChatItem) -> Unit
) : ListAdapter<ChatItem, ChatsAdapter.ChatViewHolder>(DiffCallback) {

    inner class ChatViewHolder(private val binding: ItemChatBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ChatItem) {
            val address = item.lastMessage.contactAddress

            when {
                item.isPinned -> {
                    binding.contactName.text = "Canal Público"
                    binding.avatarText.text = "#"
                    binding.avatarText.backgroundTintList =
                        ColorStateList.valueOf(Color.parseColor("#66BB6A"))
                    binding.messageTime.visibility = android.view.View.GONE
                    binding.pinIcon.visibility = android.view.View.VISIBLE
                    binding.lastMessage.text = if (item.lastMessage.timestamp == 0L) {
                        "Sin cifrado · Canal abierto"
                    } else {
                        val prefix = if (item.lastMessage.isOutgoing) "Tú: " else ""
                        "$prefix${item.lastMessage.content}"
                    }
                }
                item.isGroup -> {
                    val name = item.groupName?.takeIf { it.isNotBlank() } ?: "Grupo"
                    binding.contactName.text = name
                    binding.avatarText.text = "#"
                    binding.avatarText.backgroundTintList =
                        ColorStateList.valueOf(deriveAvatarColor(address))
                    val prefix = if (item.lastMessage.isOutgoing) "Tú: " else ""
                    binding.lastMessage.text = "$prefix${item.lastMessage.content}"
                    binding.pinIcon.visibility = android.view.View.GONE
                    binding.messageTime.visibility = android.view.View.VISIBLE
                    binding.messageTime.text = formatTime(item.lastMessage.timestamp)
                }
                else -> {
                    val displayName = item.contact?.name?.takeIf { it.isNotBlank() }
                        ?: "Nodo ${"%08x".format(address).uppercase()}"
                    binding.contactName.text = displayName
                    binding.avatarText.text = displayName.first().uppercaseChar().toString()
                    binding.avatarText.backgroundTintList =
                        ColorStateList.valueOf(deriveAvatarColor(address))
                    val prefix = if (item.lastMessage.isOutgoing) "Tú: " else ""
                    binding.lastMessage.text = "$prefix${item.lastMessage.content}"
                    binding.pinIcon.visibility = android.view.View.GONE
                    binding.messageTime.visibility = android.view.View.VISIBLE
                    binding.messageTime.text = formatTime(item.lastMessage.timestamp)
                }
            }

            if (item.unreadCount > 0) {
                binding.unreadBadge.visibility = android.view.View.VISIBLE
                binding.unreadBadge.text = if (item.unreadCount > 99) "99+" else item.unreadCount.toString()
            } else {
                binding.unreadBadge.visibility = android.view.View.GONE
            }

            binding.root.setOnClickListener { onClick(item) }
        }

        private fun formatTime(timestamp: Long): String {
            val now = System.currentTimeMillis()
            val diff = now - timestamp
            return if (diff < 24 * 60 * 60 * 1000) {
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
            } else {
                SimpleDateFormat("dd/MM", Locale.getDefault()).format(Date(timestamp))
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val binding = ItemChatBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ChatViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object DiffCallback : DiffUtil.ItemCallback<ChatItem>() {
        fun deriveAvatarColor(address: Int): Int {
            val hue = ((address xor (address ushr 16)) and 0x7FFFFFFF) % 360
            return Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.55f, 0.75f))
        }
        override fun areItemsTheSame(a: ChatItem, b: ChatItem) =
            a.lastMessage.contactAddress == b.lastMessage.contactAddress

        override fun areContentsTheSame(a: ChatItem, b: ChatItem) =
            a == b
    }
}
