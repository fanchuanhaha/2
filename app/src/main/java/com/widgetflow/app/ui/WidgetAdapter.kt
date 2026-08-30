package com.widgetflow.app.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.widgetflow.app.R
import com.widgetflow.app.databinding.ItemConfigBinding
import com.widgetflow.app.model.WidgetConfig
import com.widgetflow.app.storage.SourceStore
import com.widgetflow.app.widget.WidgetUpdater

class WidgetAdapter(
    private val onEdit: (String) -> Unit,
    private val onDelete: (WidgetConfig) -> Unit,
    private val onExport: (WidgetConfig) -> Unit,
    private val onPick: ((WidgetConfig) -> Unit)?
) : RecyclerView.Adapter<WidgetAdapter.VH>() {

    private val items = mutableListOf<WidgetConfig>()

    fun submit(list: List<WidgetConfig>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemConfigBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        val ctx = holder.binding.root.context
        holder.binding.cfgName.text = c.name.ifBlank { "未命名小部件" }
        // 展示引用到的数据源名称，帮助用户区分
        holder.binding.cfgUrl.text = sourceSummary(ctx, c)
        holder.binding.cfgSize.text = c.size

        val dotColor = when {
            c.elements.any { el ->
                SourceStore.find(ctx, el.sourceId)?.lastStatus == WidgetConfig.STATUS_ERR
            } -> R.color.err
            c.lastUpdatedAt > 0 -> R.color.ok
            else -> R.color.muted
        }
        holder.binding.cfgDot.setBackgroundColor(ContextCompat.getColor(ctx, dotColor))

        val statusText = if (c.lastUpdatedAt > 0) {
            "上次刷新 ${WidgetUpdater.formatTime(c.lastUpdatedAt)}"
        } else {
            "尚未刷新"
        }
        holder.binding.cfgStatus.text = statusText
        holder.binding.cfgDesk.visibility =
            if (c.widgetIds.isNotEmpty()) View.VISIBLE else View.GONE

        holder.binding.btnEdit.setOnClickListener { onEdit(c.id) }
        holder.binding.btnDelete.setOnClickListener { onDelete(c) }
        holder.binding.btnExport.setOnClickListener { onExport(c) }

        val pick = onPick
        if (pick != null) {
            holder.binding.root.setOnClickListener { pick(c) }
            holder.binding.root.isClickable = true
        } else {
            holder.binding.root.setOnClickListener(null)
            holder.binding.root.isClickable = false
        }
    }

    /** 汇总该小部件引用了哪些数据源 */
    private fun sourceSummary(ctx: Context, c: WidgetConfig): String {
        val names = c.elements.mapNotNull { el ->
            SourceStore.find(ctx, el.sourceId)?.name?.takeIf { it.isNotBlank() }
        }.distinct()
        return if (names.isEmpty()) {
            ctx.getString(R.string.source_refs) + ": （未绑定）"
        } else {
            ctx.getString(R.string.source_refs) + ": " + names.joinToString(" · ")
        }
    }

    class VH(val binding: ItemConfigBinding) : RecyclerView.ViewHolder(binding.root)
}
