package com.widgetflow.app.ui

import android.view.Gravity
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
 * 上面显示实际效果，下面显示名称。选择模式（onPick != null）时隐藏编辑/添加/删除按钮，
 * 点按单元直接关联到新添加的桌面组件。
 */
class WidgetAdapter(
    private val onEdit: (String) -> Unit,
    private val onDelete: (WidgetConfig) -> Unit,
    private val onAdd: (WidgetConfig) -> Unit,
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
        holder.binding.btnAdd.setOnClickListener { onAdd(c) }
        holder.binding.root.setOnClickListener {
            if (pick != null) pick(c) else onEdit(c.id)
        }

        renderPreview(holder, c)
    }

    /** 用与桌面完全一致的 RemoteViews 渲染真实预览（按小部件实际尺寸等比缩小，文字也随比例缩放） */
    private fun renderPreview(holder: VH, c: WidgetConfig) {
        val box = holder.binding.previewBox
        val ctx = box.context
        box.post {
            val cellW = box.width
            if (cellW <= 0) return@post
            val density = ctx.resources.displayMetrics.density
            // 该尺寸在桌面上的实际大小（dp），与 widget_info 的 minWidth/minHeight 一致
            val (wDp, hDp) = actualSizeDp(c.size)
            val wPx = (wDp * density).toInt()
            val hPx = (hDp * density).toInt()
            if (wPx <= 0 || hPx <= 0) return@post
            // 整体等比缩放到单元格宽度（同时缩放文字，保证与桌面效果一致）
            val maxH = (210 * density).toInt()
            var scale = cellW.toFloat() / wPx
            if ((hPx * scale).toInt() > maxH) scale = maxH.toFloat() / hPx
            val dispW = (wPx * scale).toInt().coerceAtLeast(1)
            val dispH = (hPx * scale).toInt().coerceAtLeast(1)
            box.layoutParams = box.layoutParams.apply {
                width = dispW
                height = dispH
            }
            box.requestLayout()
            try {
                val rv = WidgetUpdater.buildRemoteViews(ctx, c, wPx, hPx)
                // 必须用 applicationContext 的普通 LayoutInflater 展开：
                // 若用 Activity 上下文会膨胀成 AppCompat 视图，RemoteViews 反射 setImageBitmap 会失败崩溃
                val view = rv.apply(ctx.applicationContext, null)
                view.pivotX = 0f
                view.pivotY = 0f
                view.scaleX = scale
                view.scaleY = scale
                box.removeAllViews()
                box.addView(
                    view,
                    FrameLayout.LayoutParams(wPx, hPx, Gravity.TOP or Gravity.START)
                )
            } catch (e: Exception) {
                // 预览失败不崩溃：仅显示名称
                box.removeAllViews()
            }
        }
    }

    class VH(val binding: ItemWidgetGridBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        /** 各尺寸组件在桌面上的实际大小（dp，高宽），与 widget_info 的 minWidth/minHeight 一致 */
        private fun actualSizeDp(size: String): Pair<Int, Int> = when (size) {
            "1x1" -> 40 to 40
            "1x2" -> 40 to 110
            "2x1" -> 90 to 40
            "2x2" -> 140 to 110
            else -> 250 to 110 // 4x2
        }
    }
}
