package com.widgetflow.app.model

import org.json.JSONArray
import org.json.JSONObject

data class KeyValue(val key: String, val value: String)

/**
 * 抽取规则。type:
 *  - "json":   path 为 JSONPath，从 JSON 响应取值
 *  - "regex":  path 为正则表达式，从文本响应取首个匹配（优先第 1 捕获组）
 *  - "text":   整段文本响应
 */
data class ExtractRule(
    val path: String,
    var alias: String,
    val type: String = "json"
)

data class Element(
    var template: String,
    var fontSize: Int = 14,
    var color: String = "#1F2430",
    var x: Float = 6f,
    var y: Float = 12f,
    var width: Float = 0f,
    var height: Float = 0f,
    /** 引用哪个数据源（DataSource.id）；空 = 旧数据/未指定 */
    var sourceId: String = ""
) {
    /** 是否设置了显式宽高（0 表示自适应内容） */
    fun hasExplicitSize(): Boolean = width > 0f || height > 0f
}

/**
 * 数据源：负责拉取 API 并抽取出最终文本。
 * 与桌面小部件分离，可被多个小部件复用。
 */
data class DataSource(
    var id: String,
    var name: String = "",
    var method: String = "GET",
    var url: String = "",
    var params: MutableList<KeyValue> = mutableListOf(),
    var headers: MutableList<KeyValue> = mutableListOf(),
    var body: String = "",
    var timeoutSec: Int = 10,
    var rules: MutableList<ExtractRule> = mutableListOf(),
    // 运行时缓存：最近一次成功抽取结果
    var aliasMap: MutableMap<String, String> = mutableMapOf(),
    var lastUpdate: Long = 0L,
    var lastStatus: String = WidgetConfig.STATUS_NONE,
    var lastError: String = ""
) {

    /** 导出为 wfw/source/1 数据源包；export=true 时抹除敏感请求头的值 */
    fun toJson(export: Boolean = false): String {
        val root = JSONObject()
        root.put("format", "wfw/source/1")
        if (!export) root.put("id", id)
        root.put("name", name)
        val src = JSONObject()
        src.put("method", method)
        src.put("url", url)
        val p = JSONObject()
        params.forEach { if (it.key.isNotBlank()) p.put(it.key, it.value) }
        src.put("params", p)
        val h = JSONObject()
        headers.forEach {
            if (it.key.isNotBlank()) {
                val secret = it.key.equals("Authorization", true) ||
                    it.key.contains("token", true) || it.key.contains("secret", true)
                h.put(it.key, if (export && secret) "****" else it.value)
            }
        }
        src.put("headers", h)
        if (body.isNotBlank()) src.put("body", body)
        src.put("timeout", timeoutSec)
        root.put("source", src)

        val ex = JSONArray()
        rules.forEach { r ->
            val o = JSONObject().put("path", r.path).put("alias", r.alias)
            if (r.type != "json") o.put("type", r.type)
            ex.put(o)
        }
        root.put("extract", ex)

        if (!export) {
            val rt = JSONObject()
            val am = JSONObject()
            aliasMap.forEach { (k, v) -> am.put(k, v) }
            rt.put("aliasMap", am)
            rt.put("lastUpdate", lastUpdate)
            rt.put("lastStatus", lastStatus)
            rt.put("lastError", lastError)
            root.put("runtime", rt)
        }
        return root.toString(2)
    }

    companion object {
        /** 解析数据源包；格式不合法返回 null */
        fun fromJson(text: String): DataSource? {
            return try {
                val root = JSONObject(text)
                if (!root.optString("format", "").startsWith("wfw")) return null
                val src = root.optJSONObject("source") ?: return null
                val savedId = root.optString("id", "").trim()
                val s = DataSource(id = if (savedId.isNotBlank()) savedId else WidgetConfig.newId())
                s.name = root.optString("name", "数据源")
                s.method = src.optString("method", "GET")
                s.url = src.optString("url", "")
                src.optJSONObject("params")?.let { p ->
                    p.keys().forEach { k -> s.params.add(KeyValue(k, p.optString(k))) }
                }
                src.optJSONObject("headers")?.let { h ->
                    h.keys().forEach { k -> s.headers.add(KeyValue(k, h.optString(k))) }
                }
                s.body = src.optString("body", "")
                s.timeoutSec = src.optInt("timeout", 10)

                root.optJSONArray("extract")?.let { ex ->
                    for (i in 0 until ex.length()) {
                        val o = ex.optJSONObject(i) ?: continue
                        if (!o.has("path")) continue
                        val path = o.getString("path")
                        var alias = o.optString("alias", "")
                        if (alias.isBlank()) alias = path.substringAfterLast('.').ifBlank { "field" }
                        s.rules.add(ExtractRule(path, alias, o.optString("type", "json")))
                    }
                }

                root.optJSONObject("runtime")?.let { rt ->
                    rt.optJSONObject("aliasMap")?.let { am ->
                        am.keys().forEach { k -> s.aliasMap[k] = am.optString(k) }
                    }
                    s.lastUpdate = rt.optLong("lastUpdate", 0L)
                    s.lastStatus = rt.optString("lastStatus", WidgetConfig.STATUS_NONE)
                    s.lastError = rt.optString("lastError", "")
                }
                s
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * 小部件：只负责在桌面展示。
 * 元素通过 sourceId 引用一个或多个数据源，渲染时取各数据源的最新文本。
 */
data class WidgetConfig(
    var id: String,
    var name: String = "",
    var size: String = "4x2",
    var refreshMinutes: Int = 60,
    var bgColor: String = "",
    var elements: MutableList<Element> = mutableListOf(),
    var lastUpdatedAt: Long = 0L,
    val widgetIds: MutableSet<Int> = mutableSetOf()
) {

    /** 导出为 wfw/widget/1 小部件包；export=true 时不含 id 与运行态 */
    fun toJson(export: Boolean = false): String {
        val root = JSONObject()
        root.put("format", "wfw/widget/1")
        if (!export) root.put("id", id)
        root.put("name", name)
        val w = JSONObject()
        w.put("size", size)
        w.put("refreshMinutes", refreshMinutes)
        if (bgColor.isNotBlank()) w.put("bgColor", bgColor)
        val els = JSONArray()
        elements.forEach { e ->
            els.put(
                JSONObject()
                    .put("src", e.sourceId)
                    .put("tpl", e.template)
                    .put("size", e.fontSize)
                    .put("color", e.color)
                    .put("x", e.x.toDouble())
                    .put("y", e.y.toDouble())
                    .put("w", e.width.toDouble())
                    .put("h", e.height.toDouble())
            )
        }
        w.put("elements", els)
        root.put("widget", w)

        if (!export) {
            val rt = JSONObject()
            rt.put("lastUpdatedAt", lastUpdatedAt)
            val ids = JSONArray()
            widgetIds.forEach { ids.put(it) }
            rt.put("widgetIds", ids)
            root.put("runtime", rt)
        }
        return root.toString(2)
    }

    companion object {
        const val STATUS_OK = "ok"
        const val STATUS_ERR = "err"
        const val STATUS_NONE = "none"
        const val FREQ_DAILY_8 = -1
        const val MAX_ELEMENTS = 8
        const val MAX_RULES = 10

        fun newId(): String =
            "c" + System.currentTimeMillis() + "_" + (1000..9999).random()

        /** 解析小部件包；格式不合法返回 null */
        fun fromJson(text: String): WidgetConfig? {
            return try {
                val root = JSONObject(text)
                if (!root.optString("format", "").startsWith("wfw")) return null
                val w = root.optJSONObject("widget") ?: return null
                val savedId = root.optString("id", "").trim()
                val c = WidgetConfig(id = if (savedId.isNotBlank()) savedId else newId())
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
                                o.optString("src", "")
                            )
                        )
                    }
                }
                root.optJSONObject("runtime")?.let { rt ->
                    c.lastUpdatedAt = rt.optLong("lastUpdatedAt", 0L)
                    rt.optJSONArray("widgetIds")?.let { ids ->
                        for (i in 0 until ids.length()) c.widgetIds.add(ids.optInt(i))
                    }
                }
                c
            } catch (e: Exception) {
                null
            }
        }
    }
}
