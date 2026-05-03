package com.yotogogo

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.yotogogo.databinding.ItemLibraryCardBinding

class LibraryAdapter(
    private val items: List<YotoCard>,
    private val onClick: (YotoCard) -> Unit
) : RecyclerView.Adapter<LibraryAdapter.VH>() {

    inner class VH(val binding: ItemLibraryCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemLibraryCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val card = items[position]
        holder.binding.tvCardTitle.text = card.title ?: card.slug ?: card.cardId ?: "Unknown"
        holder.binding.root.setOnClickListener { onClick(card) }
    }
}
