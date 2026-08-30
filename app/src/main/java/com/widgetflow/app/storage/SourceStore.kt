package com.widgetflow.app.storage

import android.content.Context
import com.widgetflow.app.model.DataSource

/** 数据源存储：SharedPreferences + JSON 序列化（与桌面小部件分开保存） */
object SourceStore {

    const val PREFS = "widgetflow_sources"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): MutableList<DataSource> {
        val out = mutableListOf<DataSource>()
        val p = prefs(context)
        for (key in p.all.keys) {
            val s = p.getString(key, null) ?: continue
            val c = DataSource.fromJson(s) ?: continue
            if (c.id != key) {
                c.id = key
                prefs(context).edit().putString(key, c.toJson()).apply()
            }
            out.add(c)
        }
        out.sortByDescending { it.lastUpdate }
        return out
    }

    fun save(context: Context, config: DataSource) {
        prefs(context).edit().putString(config.id, config.toJson()).apply()
    }

    fun delete(context: Context, id: String) {
        prefs(context).edit().remove(id).apply()
    }

    fun find(context: Context, id: String): DataSource? {
        val s = prefs(context).getString(id, null) ?: return null
        val c = DataSource.fromJson(s) ?: return null
        if (c.id != id) {
            c.id = id
            prefs(context).edit().putString(id, c.toJson()).apply()
        }
        return c
    }

    /** 导入数据源包；返回 null 表示格式不合法 */
    fun import(context: Context, text: String): DataSource? {
        val c = DataSource.fromJson(text) ?: return null
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
}
