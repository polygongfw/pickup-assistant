package com.coldbrew.cainiao.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.coldbrew.cainiao.R
import com.coldbrew.cainiao.data.Repo
import com.coldbrew.cainiao.databinding.ItemTraceBinding

class TraceAdapter : RecyclerView.Adapter<TraceAdapter.VH>() {

    private val items = ArrayList<Repo.TraceNode>()

    fun submit(list: List<Repo.TraceNode>) {
        items.clear()
        items.addAll(list.asReversed())   // 最新一条放最上面
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemTraceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position], position)

    inner class VH(private val b: ItemTraceBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(n: Repo.TraceNode, pos: Int) {
            val ctx = b.root.context
            b.tvTime.text = n.time
            b.tvDesc.text = n.desc.ifEmpty { n.status }
            b.line.visibility = if (pos == items.size - 1) View.INVISIBLE else View.VISIBLE
            if (pos == 0) {
                b.dot.setBackgroundResource(R.drawable.bg_timeline_dot)
                b.tvDesc.setTextColor(ContextCompat.getColor(ctx, R.color.text))
                b.tvDesc.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                b.dot.setBackgroundResource(R.drawable.bg_timeline_dot)
                b.tvDesc.setTypeface(null, android.graphics.Typeface.NORMAL)
                b.tvDesc.setTextColor(ContextCompat.getColor(ctx, R.color.text_sub))
            }
        }
    }
}
