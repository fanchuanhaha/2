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
import com.widgetflow.app.widget.WidgetUpdater

class ConfigAdapter(
    private val onEdit: (String) -> Unit,
    private val onDelete: (WidgetConfig) -> Unit,
    private val onExport: (WidgetConfig) -> Unit,
    private val onPick: ((WidgetConfig) -> Unit)?
) : RecyclerView.Adapter<ConfigAdapter.VH>() {

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
        holder.binding.cfgName.text = c.name.ifBlank { "未命名配置" }
        holder.binding.cfgUrl.text = "${c.method}  ${c.url}"
        holder.binding.cfgSize.text = c.size

        val dotColor = when {
            c.lastStatus == WidgetConfig.STATUS_ERR -> R.color.err
            c.lastStatus == WidgetConfig.STATUS_OK -> R.color.ok
            else -> R.color.muted
        }
        holder.binding.cfgDot.setBackgroundColor(ContextCompat.getColor(ctx, dotColor))

        val statusText = when (c.lastStatus) {
            WidgetConfig.STATUS_ERR -> "刷新失败：${c.lastError}"
            WidgetConfig.STATUS_OK -> "上次刷新 ${WidgetUpdater.formatTime(c.lastUpdate)}"
            else -> "尚未刷新"
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

    class VH(val binding: ItemConfigBinding) : RecyclerView.ViewHolder(binding.root)
}
