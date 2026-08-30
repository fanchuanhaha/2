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
import com.widgetflow.app.ui.MainActivity
import com.widgetflow.app.ui.WizardActivity
import com.widgetflow.app.util.CrashLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 小组件渲染与数据更新。
 * 布局策略：widget_flow.xml 提供一个 FrameLayout + 8 个 TextView，
 * 元素坐标按百分比换算为像素，API 31+ 用 setViewLayoutMargin，
 * 低版本回退 setPaddingLeft/Top（绝对定位的两种等价实现）。
 */
object WidgetUpdater {

    private val elementIds = intArrayOf(
        R.id.tv0, R.id.tv1, R.id.tv2, R.id.tv3, R.id.tv4, R.id.tv5, R.id.tv6, R.id.tv7
    )

    /** 拉取数据（失败自动重试 2 次）后渲染 */
    fun updateNow(context: Context, appWidgetId: Int) {
        val config = ConfigStore.findForWidget(context, appWidgetId)
        if (config == null) {
            renderPlaceholder(context, appWidgetId)
            return
        }
        if (!ApiClient.isOnline(context)) {
            config.lastStatus = WidgetConfig.STATUS_ERR
            config.lastError = "网络不可用"
            ConfigStore.save(context, config)
            render(context, appWidgetId)
            return
        }
        ApiClient.executeAsync(config, retries = 2) { result ->
            try {
                when (result) {
                    is ApiResult.Success -> {
                        config.aliasMap = resolveAliases(config.rules, result.json, result.body)
                        config.lastUpdate = System.currentTimeMillis()
                        config.lastStatus = WidgetConfig.STATUS_OK
                        config.lastError = ""
                        ConfigStore.save(context, config)
                    }
                    is ApiResult.Failure -> {
                        // 兜底：保留上次成功数据，仅标记错误状态
                        config.lastStatus = WidgetConfig.STATUS_ERR
                        config.lastError = result.reason
                        ConfigStore.save(context, config)
                    }
                }
            } catch (t: Throwable) {
                CrashLog.e(context, "updateNow", t)
            }
            render(context, appWidgetId)
        }
    }

    /** 只重绘（不请求），用于布局变化 / 保存配置后 */
    fun render(context: Context, appWidgetId: Int) {
        try {
            renderInner(context, appWidgetId)
        } catch (t: Throwable) {
            CrashLog.e(context, "render", t)
            // 渲染失败时降级为占位，避免整个进程崩溃
            try {
                renderPlaceholder(context, appWidgetId)
            } catch (t2: Throwable) {
                CrashLog.e(context, "renderPlaceholder-fallback", t2)
            }
        }
    }

    private fun renderInner(context: Context, appWidgetId: Int) {
        val awm = AppWidgetManager.getInstance(context)
        val config = ConfigStore.findForWidget(context, appWidgetId)
        if (config == null) {
            renderPlaceholder(context, appWidgetId)
            return
        }
        val rv = RemoteViews(context.packageName, R.layout.widget_flow)

        val opts = awm.getAppWidgetOptions(appWidgetId)
        val density = context.resources.displayMetrics.density
        val widthDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).coerceAtLeast(60)
        val heightDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).coerceAtLeast(40)
        val wPx = (widthDp * density).toInt()
        val hPx = (heightDp * density).toInt()

        elementIds.forEach { rv.setInt(it, "setVisibility", View.GONE) }

        val time = if (config.lastUpdate > 0) formatTime(config.lastUpdate) else ""

        // 深色模式自适应默认文字色（用户未自定义颜色时使用）
        val dark = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val defaultInk = if (dark) "#E6E9F5" else "#1F2430"

        config.elements.take(WidgetConfig.MAX_ELEMENTS).forEachIndexed { i, el ->
            val id = elementIds[i]
            rv.setTextViewText(id, renderTemplate(el.template, config.aliasMap, time))
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

        val err = config.lastStatus == WidgetConfig.STATUS_ERR
        rv.setTextViewText(
            R.id.tv_time,
            if (err) context.getString(R.string.widget_wait_retry) else time
        )
        rv.setInt(
            R.id.tv_badge, "setVisibility",
            if (err) View.VISIBLE else View.GONE
        )

        // 点按组件主体 → 打开编辑器
        val editIntent = Intent(context, WizardActivity::class.java)
            .putExtra(WizardActivity.EXTRA_CONFIG_ID, config.id)
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

    /** 未关联配置时的占位渲染 */
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

    /** 重绘所有已放置的组件（开机恢复、配置删除后） */
    fun renderAll(context: Context) {
        val awm = AppWidgetManager.getInstance(context)
        val ids = awm.getAppWidgetIds(ComponentName(context, FlowWidgetProvider4x2::class.java)) +
            awm.getAppWidgetIds(ComponentName(context, FlowWidgetProvider2x2::class.java))
        ids.forEach { render(context, it) }
    }

    fun formatTime(t: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))
}
