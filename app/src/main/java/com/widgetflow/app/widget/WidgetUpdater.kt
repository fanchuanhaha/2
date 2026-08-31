package com.widgetflow.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
        val opts = awm.getAppWidgetOptions(appWidgetId)
        val density = context.resources.displayMetrics.density
        val widthDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).coerceAtLeast(60)
        val heightDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).coerceAtLeast(40)
        val wPx = (widthDp * density).toInt()
        val hPx = (heightDp * density).toInt()

        val rv = buildRemoteViews(context, widget, wPx, hPx)

        // 点按组件 → 刷新该组件的数据（不再打开 App）
        val owner = ownerComponent(context, appWidgetId)
        val refreshIntent = Intent(FlowWidgetProvider.ACTION_REFRESH).setComponent(owner)
            .putExtra(FlowWidgetProvider.EXTRA_WIDGET_ID, appWidgetId)
        val refreshPi = PendingIntent.getBroadcast(
            context, 30000 + appWidgetId, refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        rv.setOnClickPendingIntent(R.id.widget_root, refreshPi)

        awm.updateAppWidget(appWidgetId, rv)
    }

    /**
     * 构造与桌面组件完全一致的 RemoteViews（桌面更新与 App 内预览共用此方法，
     * 保证 App 里的预览 = 桌面实际效果）。
     * @param wPx 组件逻辑宽度（像素）；@param hPx 组件逻辑高度（像素）
     */
    fun buildRemoteViews(context: Context, widget: WidgetConfig, wPx: Int, hPx: Int): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_flow)
        val density = context.resources.displayMetrics.density

        // 自定义背景色/圆角：渲染圆角位图铺满；否则恢复默认圆角背景
        if (widget.bgColor.isNotBlank() || widget.cornerRadius >= 0) {
            applyCustomBg(context, rv, widget, wPx, hPx, density)
        } else {
            rv.setInt(R.id.widget_bg_img, "setVisibility", View.GONE)
            rv.setInt(R.id.widget_root, "setBackgroundResource", R.drawable.widget_bg)
            rv.setViewPadding(
                R.id.widget_root, (8 * density).toInt(), (8 * density).toInt(),
                (8 * density).toInt(), (8 * density).toInt()
            )
        }

        elementIds.forEach { rv.setInt(it, "setVisibility", View.GONE) }

        val time = if (widget.lastUpdatedAt > 0) formatTime(widget.lastUpdatedAt) else ""

        // 深色模式自适应默认文字色（用户未自定义颜色时使用）
        val dark = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val defaultInk = if (dark) "#E6E6E6" else "#1E1E1E"

        var anyErr = false
        widget.elements.take(WidgetConfig.MAX_ELEMENTS).forEachIndexed { i, el ->
            val id = elementIds[i]
            val src = SourceStore.find(context, el.sourceId)
            val map = src?.aliasMap ?: emptyMap()
            if (src?.lastStatus == WidgetConfig.STATUS_ERR) anyErr = true
            // 粗体/斜体：通过 StyleSpan 应用（RemoteViews 通用方案）
            val text = renderTemplate(el.template, map, time)
            val styled = android.text.SpannableString(text)
            if (el.typefaceStyle() != android.graphics.Typeface.NORMAL) {
                styled.setSpan(
                    android.text.style.StyleSpan(el.typefaceStyle()),
                    0, text.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            rv.setTextViewText(id, styled)
            // 字号 = 组件宽度的百分比：随组件缩放保持比例一致（与编辑/列表预览同规则）
            rv.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_PX, wPx * el.fontSize / 100f)
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

        rv.setInt(
            R.id.tv_badge, "setVisibility",
            if (anyErr) View.VISIBLE else View.GONE
        )
        return rv
    }

    /** 找到该组件 ID 所属的 Provider，用于点按刷新广播定向投递 */
    private fun ownerComponent(context: Context, appWidgetId: Int): ComponentName {
        val awm = AppWidgetManager.getInstance(context)
        val providers = listOf(
            FlowWidgetProvider1x1::class.java,
            FlowWidgetProvider1x2::class.java,
            FlowWidgetProvider2x1::class.java,
            FlowWidgetProvider2x2::class.java,
            FlowWidgetProvider4x2::class.java
        )
        providers.forEach { cls ->
            if (awm.getAppWidgetIds(ComponentName(context, cls)).contains(appWidgetId)) {
                return ComponentName(context, cls)
            }
        }
        return ComponentName(context, FlowWidgetProvider4x2::class.java)
    }

    /** 应用自定义圆角背景：渲染圆角位图铺满组件（支持任意颜色 + 任意圆角） */
    private fun applyCustomBg(
        context: Context, rv: RemoteViews, widget: WidgetConfig, wPx: Int, hPx: Int, density: Float
    ) {
        val color = try {
            Color.parseColor(widget.bgColor)
        } catch (e: IllegalArgumentException) {
            context.getColor(R.color.widget_bg_color)
        }
        val radiusDp = if (widget.cornerRadius >= 0) widget.cornerRadius else 20
        val radiusPx = (radiusDp * density).toInt().coerceAtLeast(0)
        val bmp = makeBgBitmap(wPx, hPx, color, radiusPx)
        rv.setImageViewBitmap(R.id.widget_bg_img, bmp)
        rv.setInt(R.id.widget_bg_img, "setVisibility", View.VISIBLE)
        // 去掉默认圆角背景，改为铺满的自定义圆角位图
        rv.setInt(R.id.widget_root, "setBackgroundColor", Color.TRANSPARENT)
        rv.setViewPadding(R.id.widget_root, 0, 0, 0, 0)
    }

    private fun makeBgBitmap(w: Int, h: Int, color: Int, radiusPx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        c.drawRoundRect(
            0f, 0f, w.toFloat(), h.toFloat(),
            radiusPx.toFloat(), radiusPx.toFloat(), p
        )
        return bmp
    }

    /** 未关联小部件时的占位渲染 */
    fun renderPlaceholder(context: Context, appWidgetId: Int) {
        val awm = AppWidgetManager.getInstance(context)
        val rv = RemoteViews(context.packageName, R.layout.widget_flow)
        val density = context.resources.displayMetrics.density
        elementIds.forEach { rv.setInt(it, "setVisibility", View.GONE) }
        rv.setInt(R.id.widget_bg_img, "setVisibility", View.GONE)
        rv.setInt(R.id.widget_root, "setBackgroundResource", R.drawable.widget_bg)
        rv.setViewPadding(
            R.id.widget_root, (8 * density).toInt(), (8 * density).toInt(),
            (8 * density).toInt(), (8 * density).toInt()
        )
        rv.setInt(R.id.tv0, "setVisibility", View.VISIBLE)
        rv.setTextViewText(R.id.tv0, context.getString(R.string.widget_placeholder))
        rv.setTextViewTextSize(R.id.tv0, TypedValue.COMPLEX_UNIT_SP, 13f)
        rv.setTextColor(R.id.tv0, context.getColor(R.color.muted))
        rv.setInt(R.id.tv_badge, "setVisibility", View.GONE)

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
        val providers = listOf(
            FlowWidgetProvider1x1::class.java,
            FlowWidgetProvider1x2::class.java,
            FlowWidgetProvider2x1::class.java,
            FlowWidgetProvider2x2::class.java,
            FlowWidgetProvider4x2::class.java
        )
        val ids = providers.flatMap { awm.getAppWidgetIds(ComponentName(context, it)).toList() }
        ids.forEach { render(context, it) }
    }

    fun formatTime(t: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))
}
