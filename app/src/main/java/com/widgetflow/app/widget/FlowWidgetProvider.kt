package com.widgetflow.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.widgetflow.app.storage.ConfigStore
import com.widgetflow.app.util.CrashLog

/**
 * 小组件基类：两个规格（4x2 / 2x2）共用全部逻辑，
 * 仅通过 manifest 中不同的 meta-data 声明不同的初始尺寸。
 */
open class FlowWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { id ->
            try {
                if (ConfigStore.findForWidget(context, id) == null) {
                    // 新放到桌面的组件：先渲染占位，等用户点按选择配置
                    WidgetUpdater.renderPlaceholder(context, id)
                } else {
                    WidgetUpdater.updateNow(context, id)
                }
            } catch (t: Throwable) {
                CrashLog.e(context, "onUpdate", t)
            }
        }
        RefreshScheduler.ensureScheduled(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle?
    ) {
        // 用户调整了组件尺寸：按新尺寸重算像素坐标
        WidgetUpdater.render(context, appWidgetId)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { wid ->
            val c = ConfigStore.findForWidget(context, wid) ?: return@forEach
            c.widgetIds.remove(wid)
            ConfigStore.save(context, c)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            val id = intent.getIntExtra(EXTRA_WIDGET_ID, -1)
            CrashLog.write(context, "widget", "ACTION_REFRESH id=$id")
            if (id >= 0) {
                try {
                    WidgetUpdater.updateNow(context, id)
                } catch (t: Throwable) {
                    CrashLog.e(context, "onReceive-refresh", t)
                }
            }
        } else {
            super.onReceive(context, intent)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.widgetflow.app.ACTION_REFRESH"
        const val EXTRA_WIDGET_ID = "appWidgetId"
    }
}

class FlowWidgetProvider4x2 : FlowWidgetProvider()
class FlowWidgetProvider2x2 : FlowWidgetProvider()
