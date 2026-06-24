package com.punchthrough.blestarterappandroid.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.punchthrough.blestarterappandroid.R
import com.punchthrough.blestarterappandroid.data.model.Contact
import com.punchthrough.blestarterappandroid.databinding.ItemContactBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ContactsAdapter(
    private val onClick: (Contact) -> Unit,
    private val onLongClick: (Contact) -> Unit
) : ListAdapter<Contact, ContactsAdapter.ContactViewHolder>(DiffCallback) {

    inner class ContactViewHolder(private val binding: ItemContactBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(contact: Contact) {
            val ctx = binding.root.context
            binding.contactName.text = contact.name.ifBlank { ctx.getString(R.string.no_name) }
            binding.avatarText.text = contact.name.firstOrNull()?.uppercaseChar()?.toString() ?: "#"
            binding.avatarText.backgroundTintList =
                ColorStateList.valueOf(ChatsAdapter.deriveAvatarColor(contact.address))
            binding.contactAddress.text = "Addr: ${"%08X".format(contact.address)}"
            binding.contactLastSeen.text = if (contact.lastSeen > 0) {
                ctx.getString(R.string.last_seen_prefix) + SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(contact.lastSeen))
            } else {
                ctx.getString(R.string.never_seen)
            }
            binding.root.setOnClickListener { onClick(contact) }
            binding.root.setOnLongClickListener {
                onLongClick(contact)
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val binding = ItemContactBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ContactViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object DiffCallback : DiffUtil.ItemCallback<Contact>() {
        override fun areItemsTheSame(a: Contact, b: Contact) = a.address == b.address
        override fun areContentsTheSame(a: Contact, b: Contact) = a == b
    }
}