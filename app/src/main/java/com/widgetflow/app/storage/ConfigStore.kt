package com.widgetflow.app.storage

import android.content.Context
import com.widgetflow.app.model.DataSource
import com.widgetflow.app.model.Element
import com.widgetflow.app.model.ExtractRule
import com.widgetflow.app.model.KeyValue
import com.widgetflow.app.model.WidgetConfig
import org.json.JSONObject

/** 小部件存储：SharedPreferences + JSON 序列化（只存展示层，数据源见 SourceStore） */
object ConfigStore {

    private const val PREFS = "widgetflow_widgets"
    private const val PREFS_OLD = "widgetflow_configs"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun prefsOld(context: Context) =
        context.getSharedPreferences(PREFS_OLD, Context.MODE_PRIVATE)

    /** 首次使用：把旧版「数据源+小部件」合并配置自动拆分为独立的数据源与小部件 */
    fun migrate(context: Context) {
        val old = prefsOld(context)
        if (old.all.isEmpty()) return
        CrashLog.migrate(context, old.all.size)
        old.all.forEach { (key, value) ->
            val s = value as? String ?: return@forEach
            val ds = parseOldSource(s) ?: return@forEach
            ds.id = key
            SourceStore.save(context, ds)
            parseOldWidget(s, key)?.let { w ->
                prefs(context).edit().putString(w.id, w.toJson()).apply()
            }
        }
        old.edit().clear().apply()
    }

    /** 解析旧 wfw/1 合并配置中的数据源部分 */
    private fun parseOldSource(text: String): DataSource? {
        return try {
            val root = JSONObject(text)
            if (root.optString("format", "") != "wfw/1") return null
            val src = root.optJSONObject("source") ?: return null
            val ds = DataSource(id = WidgetConfig.newId())
            ds.name = root.optString("name", "数据源")
            ds.method = src.optString("method", "GET")
            ds.url = src.optString("url", "")
            src.optJSONObject("params")?.let { p ->
                p.keys().forEach { k -> ds.params.add(KeyValue(k, p.optString(k))) }
            }
            src.optJSONObject("headers")?.let { h ->
                h.keys().forEach { k -> ds.headers.add(KeyValue(k, h.optString(k))) }
            }
            ds.body = src.optString("body", "")
            ds.timeoutSec = src.optInt("timeout", 10)
            root.optJSONArray("extract")?.let { ex ->
                for (i in 0 until ex.length()) {
                    val o = ex.optJSONObject(i) ?: continue
                    if (!o.has("path")) continue
                    val path = o.getString("path")
                    var alias = o.optString("alias", "")
                    if (alias.isBlank()) alias = path.substringAfterLast('.').ifBlank { "field" }
                    ds.rules.add(ExtractRule(path, alias, o.optString("type", "json")))
                }
            }
            root.optJSONObject("runtime")?.let { rt ->
                rt.optJSONObject("aliasMap")?.let { am ->
                    am.keys().forEach { k -> ds.aliasMap[k] = am.optString(k) }
                }
                ds.lastUpdate = rt.optLong("lastUpdate", 0L)
                ds.lastStatus = rt.optString("lastStatus", WidgetConfig.STATUS_NONE)
                ds.lastError = rt.optString("lastError", "")
            }
            ds
        } catch (e: Exception) {
            null
        }
    }

    /** 解析旧 wfw/1 合并配置中的小部件部分，元素统一引用数据源 sourceId */
    private fun parseOldWidget(text: String, sourceId: String): WidgetConfig? {
        return try {
            val root = JSONObject(text)
            if (root.optString("format", "") != "wfw/1") return null
            val w = root.optJSONObject("widget") ?: return null
            val c = WidgetConfig(id = WidgetConfig.newId())
            c.name = root.optString("name", "小部件")
            c.size = if (w.optString("size") == "2x2") "2x2" else "4x2"
            c.refreshMinutes = w.optInt("refreshMinutes", 60)
            c.bgColor = w.optString("bgColor", "")
            w.optJSONArray("elements")?.let { els ->
                for (i in 0 until els.length()) {
                    val o = els.optJSONObject(i) ?: continue
                    c.elements.add(
                        Element(
                            o.optString("tpl", ""),
                            o.optInt("size", 14),
                            o.optString("color", "#1F2430"),
                            o.optDouble("x", 6.0).toFloat(),
                            o.optDouble("y", 12.0).toFloat(),
                            o.optDouble("w", 0.0).toFloat(),
                            o.optDouble("h", 0.0).toFloat(),
                            sourceId
                        )
                    )
                }
            }
            root.optJSONObject("runtime")?.let { rt ->
                c.lastUpdatedAt = rt.optLong("lastUpdate", 0L)
                rt.optJSONArray("widgetIds")?.let { ids ->
                    for (i in 0 until ids.length()) c.widgetIds.add(ids.optInt(i))
                }
            }
            c
        } catch (e: Exception) {
            null
        }
    }

    fun all(context: Context): MutableList<WidgetConfig> {
        val out = mutableListOf<WidgetConfig>()
        val p = prefs(context)
        for (key in p.all.keys) {
            val s = p.getString(key, null) ?: continue
            val c = WidgetConfig.fromJson(s) ?: continue
            if (c.id != key) {
                c.id = key
                prefs(context).edit().putString(key, c.toJson()).apply()
            }
            out.add(c)
        }
        out.sortByDescending { it.lastUpdatedAt }
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
        val c = WidgetConfig.fromJson(s) ?: return null
        if (c.id != id) {
            c.id = id
            prefs(context).edit().putString(id, c.toJson()).apply()
        }
        return c
    }

    fun findForWidget(context: Context, appWidgetId: Int): WidgetConfig? =
        all(context).firstOrNull { it.widgetIds.contains(appWidgetId) }

    fun exportJson(config: WidgetConfig): String = config.toJson(export = true)

    /** 导入小部件包；返回 null 表示格式不合法 */
    fun import(context: Context, text: String): WidgetConfig? {
        val c = WidgetConfig.fromJson(text) ?: return null
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

    /** 供旧数据迁移打日志 */
    private object CrashLog {
        fun migrate(context: Context, count: Int) {
            com.widgetflow.app.util.CrashLog.write(
                context, "migrate", "migrate $count old config(s) to source+widget"
            )
        }
    }
}
