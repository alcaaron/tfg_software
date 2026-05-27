package com.punchthrough.blestarterappandroid.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.punchthrough.blestarterappandroid.data.model.Contact
import com.punchthrough.blestarterappandroid.data.model.Message
import com.punchthrough.blestarterappandroid.databinding.ItemChatBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Agrupa un contacto con su último mensaje
data class ChatItem(val contact: Contact?, val lastMessage: Message)

class ChatsAdapter(
    private val onClick: (ChatItem) -> Unit
) : ListAdapter<ChatItem, ChatsAdapter.ChatViewHolder>(DiffCallback) {

    inner class ChatViewHolder(private val binding: ItemChatBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ChatItem) {
            // Nombre: usa el del contacto si existe, si no muestra la dirección
            val displayName = item.contact?.name?.takeIf { it.isNotBlank() }
                ?: "Nodo ${item.lastMessage.contactAddress}"

            binding.contactName.text = displayName

            // Inicial del avatar
            binding.avatarText.text = displayName.first().uppercaseChar().toString()

            // Último mensaje con prefijo si es saliente
            val prefix = if (item.lastMessage.isOutgoing) "Tú: " else ""
            binding.lastMessage.text = "$prefix${item.lastMessage.content}"

            // Hora formateada
            binding.messageTime.text = formatTime(item.lastMessage.timestamp)

            binding.root.setOnClickListener { onClick(item) }
        }

        private fun formatTime(timestamp: Long): String {
            val now = System.currentTimeMillis()
            val diff = now - timestamp
            return if (diff < 24 * 60 * 60 * 1000) {
                // Menos de 24h — muestra solo la hora
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
            } else {
                // Más de 24h — muestra la fecha
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
        override fun areItemsTheSame(a: ChatItem, b: ChatItem) =
            a.lastMessage.contactAddress == b.lastMessage.contactAddress

        override fun areContentsTheSame(a: ChatItem, b: ChatItem) =
            a == b
    }
}