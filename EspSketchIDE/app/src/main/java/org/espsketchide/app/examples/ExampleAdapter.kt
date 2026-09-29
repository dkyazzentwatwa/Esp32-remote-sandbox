package org.espsketchide.app.examples

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.espsketchide.app.databinding.ItemExampleBinding
import org.espsketchide.app.databinding.ItemExampleHeaderBinding

class ExampleAdapter(
    private val rows: List<ExampleRow>,
    private val onClick: (Example) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is ExampleRow.Header -> TYPE_HEADER
        is ExampleRow.Item -> TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemExampleHeaderBinding.inflate(inflater, parent, false))
        } else {
            ItemHolder(ItemExampleBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is ExampleRow.Header -> (holder as HeaderHolder).binding.exampleHeaderText.text = row.title
            is ExampleRow.Item -> {
                val binding = (holder as ItemHolder).binding
                binding.exampleNameText.text = row.example.name
                binding.exampleDescriptionText.text = row.example.description
                binding.root.setOnClickListener { onClick(row.example) }
            }
        }
    }

    private class HeaderHolder(val binding: ItemExampleHeaderBinding) : RecyclerView.ViewHolder(binding.root)
    private class ItemHolder(val binding: ItemExampleBinding) : RecyclerView.ViewHolder(binding.root)

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ITEM = 1
    }
}
