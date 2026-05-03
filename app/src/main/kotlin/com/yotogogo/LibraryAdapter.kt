package com.yotogogo

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.yotogogo.databinding.ItemLibraryCardBinding

class LibraryAdapter(
    private val items: List<YotoCard>,
    private val onClick: (YotoCard) -> Unit
) : RecyclerView.Adapter<LibraryAdapter.VH>() {

    inner class VH(val binding: ItemLibraryCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemLibraryCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        // square tiles
        val size = (parent.width - parent.paddingStart - parent.paddingEnd) / COLUMNS
        binding.root.layoutParams = binding.root.layoutParams.also {
            (it as ViewGroup.MarginLayoutParams).width = size
        }
        binding.imgCover.layoutParams = binding.imgCover.layoutParams.also { it.height = size }
        return VH(binding)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val card = items[position]
        holder.binding.tvCardTitle.text = card.title ?: card.slug ?: "Unknown"
        val imageUrl = card.metadata?.cover?.imageL
            ?: card.metadata?.cover?.imageM
            ?: card.metadata?.cover?.imageS
        if (imageUrl != null) {
            holder.binding.imgCover.load(imageUrl) {
                crossfade(true)
                placeholder(android.R.color.darker_gray)
            }
        } else {
            holder.binding.imgCover.setImageDrawable(null)
        }
        holder.binding.root.setOnClickListener { onClick(card) }
    }

    companion object {
        const val COLUMNS = 3
    }
}
