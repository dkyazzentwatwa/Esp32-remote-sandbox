package org.espsketchide.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.espsketchide.app.databinding.ItemSketchBinding
import org.espsketchide.app.model.Sketch

class SketchAdapter(
    private val onClick: (Sketch) -> Unit,
    private val onOverflowClick: (Sketch, View) -> Unit
) : ListAdapter<Sketch, SketchAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSketchBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val sketch = getItem(position)
        holder.binding.sketchNameText.text = sketch.name
        holder.binding.root.setOnClickListener { onClick(sketch) }
        holder.binding.sketchOverflowButton.setOnClickListener { onOverflowClick(sketch, it) }
    }

    class ViewHolder(val binding: ItemSketchBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Sketch>() {
            override fun areItemsTheSame(oldItem: Sketch, newItem: Sketch) =
                oldItem.folderId == newItem.folderId

            override fun areContentsTheSame(oldItem: Sketch, newItem: Sketch) =
                oldItem == newItem
        }
    }
}
