package com.widgetflow.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.widgetflow.app.model.WidgetConfig
import com.widgetflow.app.storage.ConfigStore
import com.widgetflow.app.util.CrashLog
import java.util.Calendar

/**
 * 刷新调度：AlarmManager 每 30 分钟一次心跳（系统 updatePeriodMillis 下限），
 * 心跳时检查每个组件是否到达自己的刷新周期。
 */
object RefreshScheduler {

    private const val HEARTBEAT_MS = 30 * 60 * 1000L

    fun ensureScheduled(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + HEARTBEAT_MS,
            HEARTBEAT_MS,
            heartbeatPi(context)
        )
    }

    private fun heartbeatPi(context: Context): PendingIntent {
        val i = Intent(context, HeartbeatReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, 30001, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun onHeartbeat(context: Context) {
        val now = System.currentTimeMillis()
        ConfigStore.all(context).forEach { c ->
            if (c.widgetIds.isEmpty()) return@forEach
            val never = c.lastUpdate == 0L
            val due = never || when (c.refreshMinutes) {
                WidgetConfig.FREQ_DAILY_8 -> now >= nextDaily8(c.lastUpdate)
                else -> now - c.lastUpdate >= c.refreshMinutes * 60_000L
            }
            if (due) {
                c.widgetIds.forEach { WidgetUpdater.updateNow(context, it) }
            }
        }
    }

    /** t 之后下一个 8:00 的时间戳 */
    private fun nextDaily8(t: Long): Long {
        val cal = Calendar.getInstance()
        if (t > 0) cal.timeInMillis = t
        cal.set(Calendar.HOUR_OF_DAY, 8)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= t) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }
}

/** 定时心跳 */
class HeartbeatReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RefreshScheduler.ensureScheduled(context)
        RefreshScheduler.onHeartbeat(context)
    }
}

/** 开机：重注册闹钟并恢复桌面组件显示 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            RefreshScheduler.ensureScheduled(context)
            WidgetUpdater.renderAll(context)
        }
    }
}

/** requestPinAppWidget 成功后的回调：把新放置的组件关联到配置 */
class PinResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
            val configId = intent.getStringExtra("configId")
            CrashLog.write(context, "pin", "appWidgetId=$appWidgetId configId=$configId")
            if (appWidgetId < 0 || configId == null) return
            val c = ConfigStore.find(context, configId) ?: return
            c.widgetIds.add(appWidgetId)
            ConfigStore.save(context, c)
            WidgetUpdater.updateNow(context, appWidgetId)
        } catch (t: Throwable) {
            CrashLog.e(context, "pin", t)
        }
    }
}
