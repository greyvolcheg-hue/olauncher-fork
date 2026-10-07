package app.olauncher.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.data.FolderRow
import app.olauncher.databinding.AdapterFolderBinding

class FolderAdapter(
    private val labelGravity: Int,
    private val folderClickListener: (FolderRow) -> Unit,
    private val folderLongClickListener: (FolderRow, View) -> Unit,
) : ListAdapter<FolderRow, FolderAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<FolderRow>() {
            override fun areItemsTheSame(oldItem: FolderRow, newItem: FolderRow): Boolean =
                oldItem.view == newItem.view

            override fun areContentsTheSame(oldItem: FolderRow, newItem: FolderRow): Boolean =
                oldItem == newItem
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(AdapterFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position), labelGravity, folderClickListener, folderLongClickListener)

    class ViewHolder(private val binding: AdapterFolderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            row: FolderRow,
            labelGravity: Int,
            clickListener: (FolderRow) -> Unit,
            longClickListener: (FolderRow, View) -> Unit,
        ) = with(binding) {
            folderTitle.text = row.label
            folderTitle.gravity = labelGravity
            folderTitle.setOnClickListener { clickListener(row) }
            folderTitle.setOnLongClickListener {
                longClickListener(row, it)
                true
            }
        }
    }
}
