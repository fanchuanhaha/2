package com.widgetflow.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.widgetflow.app.R
import com.widgetflow.app.model.WidgetConfig
import com.widgetflow.app.model.renderTemplate
import com.widgetflow.app.model.resolveAliases
import com.widgetflow.app.net.ApiClient
import com.widgetflow.app.net.ApiResult
import com.widgetflow.app.storage.ConfigStore
import com.widgetflow.app.storage.SourceStore
import com.widgetflow.app.ui.MainActivity
import com.widgetflow.app.util.CrashLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 小组件渲染与数据更新。
 * 小部件只负责展示：每个元素引用一个数据源（Element.sourceId），
 * 刷新时并行拉取其引用的全部数据源，各自完成后重绘。
 */
object WidgetUpdater {

    private val elementIds = intArrayOf(
        R.id.tv0, R.id.tv1, R.id.tv2, R.id.tv3, R.id.tv4, R.id.tv5, R.id.tv6, R.id.tv7
    )

    /** 拉取该小部件引用的全部数据源（失败自动重试 2 次），完成后渲染 */
    fun updateNow(context: Context, appWidgetId: Int) {
        val widget = ConfigStore.findForWidget(context, appWidgetId)
        if (widget == null) {
            renderPlaceholder(context, appWidgetId)
            return
        }
        refreshWidget(context, widget)
    }

    /** 刷新一个小部件：并行拉取其引用的数据源 */
    fun refreshWidget(context: Context, widget: WidgetConfig) {
        val srcIds = widget.elements.map { it.sourceId }
            .filter { it.isNotBlank() }.distinct()
        if (srcIds.isEmpty()) {
            widget.widgetIds.forEach { render(context, it) }
            return
        }
        if (!ApiClient.isOnline(context)) {
            srcIds.forEach { sid ->
                SourceStore.find(context, sid)?.let { s ->
                    s.lastStatus = WidgetConfig.STATUS_ERR
                    s.lastError = "网络不可用"
                    SourceStore.save(context, s)
                }
            }
            widget.lastUpdatedAt = System.currentTimeMillis()
            ConfigStore.save(context, widget)
            widget.widgetIds.forEach { render(context, it) }
            return
        }
        srcIds.forEach { sid ->
            val s = SourceStore.find(context, sid) ?: return@forEach
            ApiClient.executeAsync(s, retries = 2) { result ->
                try {
                    when (result) {
                        is ApiResult.Success -> {
                            s.aliasMap = resolveAliases(s.rules, result.json, result.body)
                            s.lastUpdate = System.currentTimeMillis()
                            s.lastStatus = WidgetConfig.STATUS_OK
                            s.lastError = ""
                        }
                        is ApiResult.Failure -> {
                            // 兜底：保留上次成功数据，仅标记错误状态
                            s.lastStatus = WidgetConfig.STATUS_ERR
                            s.lastError = result.reason
                        }
                    }
                    SourceStore.save(context, s)
                } catch (t: Throwable) {
                    CrashLog.e(context, "refreshWidget", t)
                }
                widget.lastUpdatedAt = System.currentTimeMillis()
                ConfigStore.save(context, widget)
                // 该数据源被多个小部件共享时，全部一起重绘
                rerenderWidgetsUsing(context, sid)
            }
        }
    }

    /** 重绘所有引用了指定数据源的小部件 */
    private fun rerenderWidgetsUsing(context: Context, sourceId: String) {
        ConfigStore.all(context).forEach { w ->
            if (w.elements.any { it.sourceId == sourceId }) {
                w.widgetIds.forEach { render(context, it) }
            }
        }
    }

    /** 只重绘（不请求），用于布局变化 / 保存配置后 */
    fun render(context: Context, appWidgetId: Int) {
        try {
            renderInner(context, appWidgetId)
        } catch (t: Throwable) {
            CrashLog.e(context, "render", t)
            try {
                renderPlaceholder(context, appWidgetId)
            } catch (t2: Throwable) {
                CrashLog.e(context, "renderPlaceholder-fallback", t2)
            }
        }
    }

    private fun renderInner(context: Context, appWidgetId: Int) {
        val awm = AppWidgetManager.getInstance(context)
        val widget = ConfigStore.findForWidget(context, appWidgetId)
        if (widget == null) {
            renderPlaceholder(context, appWidgetId)
            return
        }
        val rv = RemoteViews(context.packageName, R.layout.widget_flow)

        // 自定义背景色（空 = 使用主题默认的圆角背景 drawable）
        if (widget.bgColor.isNotBlank()) {
            try {
                rv.setInt(R.id.widget_root, "setBackgroundColor", Color.parseColor(widget.bgColor))
            } catch (e: IllegalArgumentException) {
                // 非法颜色值：保持默认背景
            }
        }

        val opts = awm.getAppWidgetOptions(appWidgetId)
        val density = context.resources.displayMetrics.density
        val widthDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).coerceAtLeast(60)
        val heightDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).coerceAtLeast(40)
        val wPx = (widthDp * density).toInt()
        val hPx = (heightDp * density).toInt()

        elementIds.forEach { rv.setInt(it, "setVisibility", View.GONE) }

        val time = if (widget.lastUpdatedAt > 0) formatTime(widget.lastUpdatedAt) else ""

        // 深色模式自适应默认文字色（用户未自定义颜色时使用）
        val dark = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val defaultInk = if (dark) "#E6E9F5" else "#1F2430"

        var anyErr = false
        widget.elements.take(WidgetConfig.MAX_ELEMENTS).forEachIndexed { i, el ->
            val id = elementIds[i]
            val src = SourceStore.find(context, el.sourceId)
            val map = src?.aliasMap ?: emptyMap()
            if (src?.lastStatus == WidgetConfig.STATUS_ERR) anyErr = true
            rv.setTextViewText(id, renderTemplate(el.template, map, time))
            rv.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, el.fontSize.toFloat())
            try {
                rv.setTextColor(id, Color.parseColor(el.color.ifBlank { defaultInk }))
            } catch (e: IllegalArgumentException) {
                rv.setTextColor(
                    id, context.getColor(if (dark) R.color.ink_dark else R.color.ink)
                )
            }
            // 显式宽高（画布拖拽缩放设置，0=自适应内容）；API 31+ 支持
            if (Build.VERSION.SDK_INT >= 31 && (el.width > 0f || el.height > 0f)) {
                if (el.width > 0f) {
                    val wDp = (el.width * wPx / 100f / density)
                    rv.setViewLayoutWidth(id, wDp, TypedValue.COMPLEX_UNIT_DIP)
                }
                if (el.height > 0f) {
                    val hDp = (el.height * hPx / 100f / density)
                    rv.setViewLayoutHeight(id, hDp, TypedValue.COMPLEX_UNIT_DIP)
                }
            }
            rv.setInt(id, "setVisibility", View.VISIBLE)
            val x = ((el.x / 100f) * wPx).toInt().coerceIn(0, wPx)
            val y = ((el.y / 100f) * hPx).toInt().coerceIn(0, hPx)
            // 绝对定位：以 padding 实现（RemoteViews 通用方案，全版本可用）
            rv.setViewPadding(id, x, y, 0, 0)
        }

        rv.setTextViewText(
            R.id.tv_time,
            if (anyErr) context.getString(R.string.widget_wait_retry) else time
        )
        rv.setInt(
            R.id.tv_badge, "setVisibility",
            if (anyErr) View.VISIBLE else View.GONE
        )

        // 点按组件主体 → 打开小部件编辑器
        val editIntent = Intent(context, com.widgetflow.app.ui.WidgetEditorActivity::class.java)
            .putExtra(com.widgetflow.app.ui.WidgetEditorActivity.EXTRA_WIDGET_ID, widget.id)
        val editPi = PendingIntent.getActivity(
            context, appWidgetId, editIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        rv.setOnClickPendingIntent(R.id.widget_root, editPi)

        // 点按右下角时间区 → 手动刷新
        val refreshIntent = Intent(context, FlowWidgetProvider4x2::class.java)
            .setAction(FlowWidgetProvider.ACTION_REFRESH)
            .putExtra(FlowWidgetProvider.EXTRA_WIDGET_ID, appWidgetId)
        val refreshPi = PendingIntent.getBroadcast(
            context, 10000 + appWidgetId, refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        rv.setOnClickPendingIntent(R.id.tv_time, refreshPi)

        awm.updateAppWidget(appWidgetId, rv)
    }

    /** 未关联小部件时的占位渲染 */
    fun renderPlaceholder(context: Context, appWidgetId: Int) {
        val awm = AppWidgetManager.getInstance(context)
        val rv = RemoteViews(context.packageName, R.layout.widget_flow)
        elementIds.forEach { rv.setInt(it, "setVisibility", View.GONE) }
        rv.setInt(R.id.tv0, "setVisibility", View.VISIBLE)
        rv.setTextViewText(R.id.tv0, context.getString(R.string.widget_placeholder))
        rv.setTextViewTextSize(R.id.tv0, TypedValue.COMPLEX_UNIT_SP, 13f)
        rv.setTextColor(R.id.tv0, context.getColor(R.color.muted))
        rv.setInt(R.id.tv_badge, "setVisibility", View.GONE)
        rv.setTextViewText(R.id.tv_time, " ")

        val pickIntent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_PICK_WIDGET_ID, appWidgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            context, 20000 + appWidgetId, pickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        rv.setOnClickPendingIntent(R.id.widget_root, pi)
        awm.updateAppWidget(appWidgetId, rv)
    }

    /** 重绘所有已放置的组件（开机恢复、删除后） */
    fun renderAll(context: Context) {
        val awm = AppWidgetManager.getInstance(context)
        val ids = awm.getAppWidgetIds(ComponentName(context, FlowWidgetProvider4x2::class.java)) +
            awm.getAppWidgetIds(ComponentName(context, FlowWidgetProvider2x2::class.java))
        ids.forEach { render(context, it) }
    }

    fun formatTime(t: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))
}
