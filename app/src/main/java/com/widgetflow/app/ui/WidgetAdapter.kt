package com.widgetflow.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.widgetflow.app.R
import com.widgetflow.app.databinding.ItemWidgetGridBinding
import com.widgetflow.app.model.WidgetConfig
import com.widgetflow.app.widget.WidgetUpdater

/**
 * 小部件网格适配器：每个单元用与桌面完全一致的 RemoteViews 渲染真实预览，
 * 上面显示实际效果，下面显示名称。选择模式（onPick != null）时隐藏编辑/导出/删除按钮，
 * 点按单元直接关联到新添加的桌面组件。
 */
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
        val binding = ItemWidgetGridBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        val ctx = holder.binding.root.context

        holder.binding.cellName.text = c.name.ifBlank { "未命名小部件" }
        val status = if (c.lastUpdatedAt > 0) {
            "上次刷新 " + WidgetUpdater.formatTime(c.lastUpdatedAt)
        } else {
            "尚未刷新"
        }
        val desk = if (c.widgetIds.isNotEmpty()) " · 已在桌面" else ""
        holder.binding.cellSub.text = c.size + " · " + status + desk

        // 选择模式：只显示预览 + 名称，去掉编辑/导出/删除按钮
        val pick = onPick
        holder.binding.cellActions.visibility = if (pick != null) View.GONE else View.VISIBLE
        holder.binding.btnEdit.setOnClickListener { onEdit(c.id) }
        holder.binding.btnDelete.setOnClickListener { onDelete(c) }
        holder.binding.btnExport.setOnClickListener { onExport(c) }
        holder.binding.root.setOnClickListener {
            if (pick != null) pick(c) else onEdit(c.id)
        }

        renderPreview(holder, c)
    }

    /** 用与桌面完全一致的 RemoteViews 渲染真实预览 */
    private fun renderPreview(holder: VH, c: WidgetConfig) {
        val box = holder.binding.previewBox
        val ctx = box.context
        box.post {
            val w = box.width
            if (w <= 0) return@post
            val density = ctx.resources.displayMetrics.density
            val maxH = (210 * density).toInt()
            val ratio = aspectRatio(c.size) // 高 / 宽
            var h = (w * ratio).toInt()
            var wPx = w
            if (h > maxH) {
                h = maxH
                wPx = (maxH / ratio).toInt()
            }
            if (wPx <= 0 || h <= 0) return@post
            box.layoutParams = box.layoutParams.apply {
                this.width = wPx
                this.height = h
            }
            box.requestLayout()
            try {
                val rv = WidgetUpdater.buildRemoteViews(ctx, c, wPx, h)
                // 必须用 applicationContext 的普通 LayoutInflater 展开：
                // 若用 Activity 上下文会膨胀成 AppCompat 视图，RemoteViews 反射 setImageBitmap 会失败崩溃
                val view = rv.apply(ctx.applicationContext, null)
                box.removeAllViews()
                box.addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
            } catch (e: Exception) {
                // 预览失败不崩溃：仅显示名称
                box.removeAllViews()
            }
        }
    }

    class VH(val binding: ItemWidgetGridBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        /** 各尺寸组件的高宽比（与 widget_info 的 minWidth/minHeight 一致） */
        private fun aspectRatio(size: String): Float = when (size) {
            "1x1" -> 1f
            "1x2" -> 110f / 40f
            "2x1" -> 40f / 90f
            "2x2" -> 110f / 140f
            else -> 110f / 250f
        }
    }
}
