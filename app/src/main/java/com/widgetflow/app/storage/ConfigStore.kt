package com.widgetflow.app.storage

import android.content.Context
import com.widgetflow.app.model.WidgetConfig

/** 配置存储：SharedPreferences + JSON 序列化（单条配置 ≤ 256KB，无索引压力） */
object ConfigStore {

    private const val PREFS = "widgetflow_configs"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): MutableList<WidgetConfig> {
        val out = mutableListOf<WidgetConfig>()
        val p = prefs(context)
        for (key in p.all.keys) {
            val s = p.getString(key, null) ?: continue
            WidgetConfig.fromJson(s)?.let { out.add(it) }
        }
        out.sortByDescending { it.lastUpdate }
        return out
    }

    fun save(context: Context, config: WidgetConfig) {
        prefs(context).edit().putString(config.id, config.toJson()).apply()
    }

    fun delete(context: Context, id: String) {
        prefs(context).edit().remove(id).apply()
    }

    fun find(context: Context, id: String): WidgetConfig? {
        val s = prefs(context).getString(id, null) ?: return null
        return WidgetConfig.fromJson(s)
    }

    fun findForWidget(context: Context, appWidgetId: Int): WidgetConfig? =
        all(context).firstOrNull { it.widgetIds.contains(appWidgetId) }

    /** 导入配置包；返回 null 表示格式不合法 */
    fun import(context: Context, text: String): WidgetConfig? {
        val c = WidgetConfig.fromJson(text) ?: return null
        if (c.url.isBlank()) return null
        val existing = all(context)
        var n = 2
        var name = c.name
        while (existing.any { it.name == name }) {
            name = c.name + " ($n)"
            n++
        }
        c.name = name
        save(context, c)
        return c
    }

    fun exportJson(config: WidgetConfig): String = config.toJson(export = true)
}
