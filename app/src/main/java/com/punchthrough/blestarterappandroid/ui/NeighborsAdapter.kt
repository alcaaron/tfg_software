package com.punchthrough.blestarterappandroid.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.punchthrough.blestarterappandroid.NeighborNode
import com.punchthrough.blestarterappandroid.R

class NeighborsAdapter : ListAdapter<NeighborNode, NeighborsAdapter.ViewHolder>(DiffCallback) {

    var onClick: ((NeighborNode) -> Unit)? = null

    class ViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        fun bind(node: NeighborNode, onClick: ((NeighborNode) -> Unit)?) {
            itemView.findViewById<TextView>(R.id.neighborNodeId).text = itemView.context.getString(R.string.node_label, node.nodeId.uppercase())
            itemView.findViewById<TextView>(R.id.neighborRssi).text = "RSSI: ${node.rssi} dBm"
            itemView.findViewById<TextView>(R.id.neighborT).text = "T: ${node.t}"
            itemView.setOnClickListener { onClick?.invoke(node) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_neighbor, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), onClick)
    }

    companion object DiffCallback : DiffUtil.ItemCallback<NeighborNode>() {
        override fun areItemsTheSame(a: NeighborNode, b: NeighborNode) = a.nodeId == b.nodeId
        override fun areContentsTheSame(a: NeighborNode, b: NeighborNode) = a == b
    }
}
