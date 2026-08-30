package com.widgetflow.app.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.widgetflow.app.R
import com.widgetflow.app.databinding.ItemSourceBinding
import com.widgetflow.app.model.DataSource
import com.widgetflow.app.model.WidgetConfig
import com.widgetflow.app.widget.WidgetUpdater

class SourceAdapter(
    private val onTest: (DataSource) -> Unit,
    private val onEdit: (String) -> Unit,
    private val onExport: (DataSource) -> Unit,
    private val onDelete: (DataSource) -> Unit
) : RecyclerView.Adapter<SourceAdapter.VH>() {

    private val items = mutableListOf<DataSource>()

    fun submit(list: List<DataSource>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSourceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]
        val ctx = holder.binding.root.context
        holder.binding.srcName.text = s.name.ifBlank { "未命名数据源" }
        holder.binding.srcUrl.text = "${s.method}  ${s.url}"
        holder.binding.srcRules.text = "${s.rules.size} 条规则"

        val dotColor = when {
            s.lastStatus == WidgetConfig.STATUS_ERR -> R.color.err
            s.lastStatus == WidgetConfig.STATUS_OK -> R.color.ok
            else -> R.color.muted
        }
        holder.binding.srcDot.setBackgroundColor(ContextCompat.getColor(ctx, dotColor))

        val statusText = when (s.lastStatus) {
            WidgetConfig.STATUS_ERR -> "测试失败：${s.lastError}"
            WidgetConfig.STATUS_OK -> "上次成功 ${WidgetUpdater.formatTime(s.lastUpdate)}"
            else -> "尚未测试"
        }
        holder.binding.srcStatus.text = statusText

        holder.binding.srcBtnTest.setOnClickListener { onTest(s) }
        holder.binding.srcBtnEdit.setOnClickListener { onEdit(s.id) }
        holder.binding.srcBtnExport.setOnClickListener { onExport(s) }
        holder.binding.srcBtnDelete.setOnClickListener { onDelete(s) }
    }

    class VH(val binding: ItemSourceBinding) : RecyclerView.ViewHolder(binding.root)
}
