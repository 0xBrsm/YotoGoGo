package com.yotogogo

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.yotogogo.databinding.ItemTrackBinding

class TrackAdapter(private val items: List<TrackItem>) :
    RecyclerView.Adapter<TrackAdapter.VH>() {

    inner class VH(val binding: ItemTrackBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.tvTrackName.text = item.displayTitle
        holder.binding.tvStatus.text = when (item.status) {
            DownloadStatus.PENDING     -> ""
            DownloadStatus.DOWNLOADING -> "⬇ downloading…"
            DownloadStatus.DONE        -> "✓ saved"
            DownloadStatus.ERROR       -> "✗ error"
        }
    }

    fun updateStatus(index: Int, status: DownloadStatus) {
        items[index].status = status
        notifyItemChanged(index)
    }
}
