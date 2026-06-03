package com.punchthrough.blestarterappandroid.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.punchthrough.blestarterappandroid.data.model.Message
import com.punchthrough.blestarterappandroid.databinding.ItemMessageIncomingBinding
import com.punchthrough.blestarterappandroid.databinding.ItemMessageOutgoingBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessagesAdapter(
    var isPublicChannel: Boolean = false,
    var onSenderLongClick: ((senderAddress: Int) -> Unit)? = null
) : ListAdapter<Message, RecyclerView.ViewHolder>(DiffCallback) {

    companion object {
        private const val VIEW_TYPE_INCOMING = 0
        private const val VIEW_TYPE_OUTGOING = 1

        val DiffCallback = object : DiffUtil.ItemCallback<Message>() {
            override fun areItemsTheSame(a: Message, b: Message) = a.id == b.id
            override fun areContentsTheSame(a: Message, b: Message) = a == b
        }
    }

    override fun getItemViewType(position: Int) =
        if (getItem(position).isOutgoing) VIEW_TYPE_OUTGOING else VIEW_TYPE_INCOMING

    inner class IncomingViewHolder(private val binding: ItemMessageIncomingBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(message: Message) {
            binding.messageText.text = message.content
            binding.messageTime.text = formatTime(message.timestamp)

            if (isPublicChannel && message.senderAddress != 0) {
                binding.senderText.visibility = View.VISIBLE
                binding.senderText.text = "Nodo ${"0x${"%08X".format(message.senderAddress)}"}"
                binding.root.setOnLongClickListener {
                    onSenderLongClick?.invoke(message.senderAddress)
                    true
                }
            } else {
                binding.senderText.visibility = View.GONE
                binding.root.setOnLongClickListener(null)
            }
        }
    }

    inner class OutgoingViewHolder(private val binding: ItemMessageOutgoingBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(message: Message) {
            binding.messageText.text = message.content
            binding.messageTime.text = formatTime(message.timestamp)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_OUTGOING) {
            OutgoingViewHolder(
                ItemMessageOutgoingBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )
        } else {
            IncomingViewHolder(
                ItemMessageIncomingBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is OutgoingViewHolder -> holder.bind(getItem(position))
            is IncomingViewHolder -> holder.bind(getItem(position))
        }
    }

    private fun formatTime(timestamp: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}
