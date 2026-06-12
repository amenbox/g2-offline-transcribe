package com.g2transcribe

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.g2transcribe.databinding.ItemTranscriptBinding

data class TranscriptEntry(val timestamp: String, val text: String)

class TranscriptAdapter : RecyclerView.Adapter<TranscriptAdapter.VH>() {

    private val items = mutableListOf<TranscriptEntry>()

    fun addEntry(entry: TranscriptEntry) {
        items.add(entry)
        notifyItemInserted(items.size - 1)
    }

    fun updateLastEntry(entry: TranscriptEntry) {
        if (items.isNotEmpty()) {
            items[items.size - 1] = entry
            notifyItemChanged(items.size - 1)
        } else {
            addEntry(entry)
        }
    }

    fun replaceAll(entries: List<TranscriptEntry>) {
        items.clear()
        items.addAll(entries)
        notifyDataSetChanged()
    }

    fun getAll(): List<TranscriptEntry> = items.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemTranscriptBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]
        holder.binding.timestampText.text = e.timestamp
        holder.binding.bodyText.text = e.text
    }

    override fun getItemCount() = items.size

    class VH(val binding: ItemTranscriptBinding) : RecyclerView.ViewHolder(binding.root)
}
